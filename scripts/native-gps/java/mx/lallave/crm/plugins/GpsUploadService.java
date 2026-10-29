package mx.lallave.crm.plugins;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class GpsUploadService extends Service {
    private static final String TAG = "LlaveGpsUpload";
    private static final String CHANNEL_ID = "llave_gps_upload";
    private static final String PREFS_NAME = "llave_gps_uploader";
    private static final int NOTIF_ID = 4242;
    /** True mientras el servicio de rastreo esta vivo en este proceso (lo lee el comando SMS ESTADO/REACTIVAR GPS). */
    static volatile boolean RUNNING = false;
    private static final String DEFAULT_NOTIFY_URL = "https://lallave-sms.dioj4582.workers.dev/";
    private static volatile JSONObject pubCache;
    private static volatile long pubTs;
    private static volatile String pubSid = "";

    private FusedLocationProviderClient fused;
    private LocationCallback callback;
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean uploading = new AtomicBoolean(false);
    private volatile Location lastLoc;
    private long intervalMs = 15000L;
    private PowerManager.WakeLock wakeLock; // Control para mantener el CPU despierto

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            SharedPreferences p = prefs();
            if (!p.getBoolean("running", false)) {
                stopSelf();
                return;
            }
            final Location loc = lastLoc;
            boolean recentOk = System.currentTimeMillis() - p.getLong("lastUploadOk", 0) < intervalMs - 2000
                    && p.getInt("pending", 0) == 0;
            if (loc != null && recentOk) {
                // ya se envió hace poco: nada que hacer en este ciclo
            } else if (loc != null) {
                net.execute(new Runnable() {
                    @Override
                    public void run() {
                        upload(loc, false);
                    }
                });
            } else {
                try {
                    fused.getLastLocation().addOnSuccessListener(l -> {
                        if (l != null) {
                            lastLoc = l;
                            net.execute(new Runnable() {
                                @Override
                                public void run() {
                                    upload(l, false);
                                }
                            });
                        } else {
                            net.execute(new Runnable() {
                                @Override
                                public void run() {
                                    heartbeatOnly();
                                }
                            });
                        }
                    });
                } catch (SecurityException e) {
                    setErr("Sin permiso de ubicacion");
                }
            }
            handler.postDelayed(this, intervalMs);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        fused = LocationServices.getFusedLocationProviderClient(this);
        RUNNING = true;
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Adquirir o asegurar el WakeLock para evitar que Android suspenda el CPU
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && (wakeLock == null || !wakeLock.isHeld())) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LlaveCRM::GpsUploadWakeLock");
            wakeLock.acquire(10 * 60 * 1000L); // 10 minutos de timeout de seguridad
        }

        SharedPreferences p = prefs();
        int sec = p.getInt("intervalSec", 15);
        if (sec < 10) sec = 10;
        intervalMs = sec * 1000L;

        Notification n = buildNotification("Enviando ubicacion del vehiculo");
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIF_ID, n);
        }

        startUpdates();
        handler.removeCallbacks(tick);
        handler.post(tick);
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // El chofer deslizo la app de recientes: el servicio sigue (foreground). Si el sistema lo detiene, START_STICKY lo reinicia.
        Log.i(TAG, "app quitada de recientes; el rastreo continua");
        super.onTaskRemoved(rootIntent);
    }

    private void startUpdates() {
        if (callback != null) {
            try {
                fused.removeLocationUpdates(callback);
            } catch (Exception ignored) {
            }
            callback = null;
        }

        LocationRequest req = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
                .setMinUpdateIntervalMillis(10000L)
                .setMinUpdateDistanceMeters(0f)
                .setMaxUpdateDelayMillis(intervalMs)
                .setWaitForAccurateLocation(false)
                .build();

        callback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult result) {
                if (result == null) return;
                final Location loc = result.getLastLocation();
                if (loc == null) return;
                lastLoc = loc;
                prefs().edit().putLong("lastFix", loc.getTime() > 0 ? loc.getTime() : System.currentTimeMillis()).apply();
                net.execute(new Runnable() {
                    @Override
                    public void run() {
                        upload(loc, true);
                    }
                });
            }
        };

        try {
            fused.requestLocationUpdates(req, callback, Looper.getMainLooper());
        } catch (SecurityException e) {
            Log.e(TAG, "Sin permiso de ubicacion", e);
            setErr("Sin permiso de ubicacion");
            stopSelf();
        }
    }

    private void upload(Location loc, boolean fromCallback) {
        if (!uploading.compareAndSet(false, true)) return;
        
        // Renovar el WakeLock en cada ciclo para evitar expiraciones en viajes largos
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.acquire(10 * 60 * 1000L);
        }

        SharedPreferences p = prefs();
        try {
            if (!p.getBoolean("running", false)) {
                stopSelf();
                return;
            }
            String base = p.getString("supabaseUrl", "");
            String key = p.getString("anonKey", "");
            String rowId = p.getString("rowId", "");
            String vehId = p.getString("vehId", "");
            String token = p.getString("token", "");
            if (base.isEmpty() || key.isEmpty() || rowId.isEmpty() || vehId.isEmpty() || token.isEmpty()) {
                setErr("Config incompleta (url/key/rowId/vehId/token)");
                return;
            }
            String sid = "llave2";
            if (rowId.startsWith("gps_track_")) {
                sid = rowId.substring("gps_track_".length());
            }

            long ts = System.currentTimeMillis();
            double speedKmh = 0;
            if (loc.hasSpeed() && loc.getSpeed() >= 0) {
                speedKmh = loc.getSpeed() * 3.6;
            }

            // Viajes automaticos: inicia con movimiento, termina tras 10 min parado; sin viaje solo 1 envio cada 2 min
            String autoTripId = null;
            String endTripId = null;
            long endTripTs = 0;
            if (p.getBoolean("auto", false)) {
                String cur = p.getString("tripId", "");
                int hits = AutoTrip.nextHits(p.getInt("moveHits", 0), speedKmh);
                long lastMove = p.getLong("lastMove", 0);
                if (AutoTrip.isMoving(speedKmh)) lastMove = ts;
                int act = AutoTrip.action(!cur.isEmpty(), hits, ts, lastMove);
                SharedPreferences.Editor ae = p.edit().putInt("moveHits", hits).putLong("lastMove", lastMove);
                if (act == AutoTrip.START) {
                    autoTripId = "auto_" + Long.toString(ts, 36);
                    ae.putString("tripId", autoTripId).putString("alertTrip", autoTripId)
                      .remove("zones").putLong("lastSpeedAlert", 0);
                } else if (act == AutoTrip.END) {
                    endTripId = cur;
                    endTripTs = lastMove > 0 ? lastMove : ts;
                }
                ae.apply();
                boolean noTrip = cur.isEmpty() && autoTripId == null;
                if (noTrip && endTripId == null
                        && ts - p.getLong("lastUploadOk", 0) < AutoTrip.IDLE_UPLOAD_MS
                        && p.getInt("pending", 0) == 0) {
                    return; // parado y sin viaje: no gastar bateria ni datos
                }
            }

            JSONObject live = new JSONObject();
            live.put("lat", loc.getLatitude());
            live.put("lng", loc.getLongitude());
            live.put("speed", speedKmh);
            live.put("accuracy", loc.hasAccuracy() ? (Object) Double.valueOf(loc.getAccuracy()) : JSONObject.NULL);
            live.put("heading", loc.hasBearing() ? (Object) Double.valueOf(loc.getBearing()) : JSONObject.NULL);
            live.put("ts", ts);
            live.put("seen", ts);
            live.put("active", true);
            addHealth(live, p, ts, loc.getTime());
            // Bateria desde el servicio nativo: la pagina (JS) se congela en segundo plano y no puede reportarla
            try {
                Intent bi = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (bi != null) {
                    int lvl = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                    int scale = bi.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                    if (lvl >= 0 && scale > 0) {
                        live.put("battery", Math.round(lvl * 100f / scale));
                        int st = bi.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                        live.put("charging", st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL);
                    }
                }
            } catch (Exception ignore) { }

            JSONObject patch = new JSONObject();
            String tripId = p.getString("tripId", "");
            if (!tripId.isEmpty()) {
                if (endTripId == null) live.put("tripId", tripId);
                JSONArray points = new JSONArray(p.getString("queue", "[]"));
                points.put(pointRow(ts, loc.getLatitude(), loc.getLongitude(), speedKmh));
                JSONObject trip = new JSONObject();
                trip.put("id", tripId);
                trip.put("startTs", ts);
                if (endTripId != null) trip.put("endTs", endTripTs);
                trip.put("points", points);
                patch.put("trip", trip);
            }
            patch.put("live", live);

            // Alertas de velocidad y zonas calculadas aqui (no dependen de la pagina, que se congela en segundo plano)
            JSONArray alerts = new JSONArray();
            JSONArray events = new JSONArray();
            JSONObject zonesNext = new JSONObject();
            long lastSpeedNext = p.getLong("lastSpeedAlert", 0);
            boolean alertsOk = false;
            try {
                lastSpeedNext = buildAlerts(p, base, key, sid, vehId, loc, speedKmh, ts, alerts, events, zonesNext);
                alertsOk = true;
            } catch (Exception ex) {
                Log.w(TAG, "alertas: " + ex.getMessage());
            }
            if (alertsOk) {
                if (alerts.length() > 0) patch.put("alerts", alerts);
                if (events.length() > 0) patch.put("events", events);
                JSONObject zone = new JSONObject();
                zone.put("zones", zonesNext);
                zone.put("lastSpeedAlert", lastSpeedNext);
                zone.put("signalOk", true);
                patch.put("zone", zone);
            }

            JSONObject args = new JSONObject();
            args.put("p_sid", sid);
            args.put("p_veh", vehId);
            args.put("p_token", token);
            args.put("p", patch);
            callRpc(base, key, "gps_apply", args);

            // No vaciar la cola mientras no exista un viaje activo.
            // Si el GPS sube la posición en vivo antes de que la app asigne tripId,
            // los puntos pendientes deben sobrevivir para enviarse cuando el viaje comience.
            SharedPreferences.Editor saved = p.edit()
                    .putLong("lastUploadOk", ts)
                    .putString("lastError", "");
            if (alertsOk) {
                saved.putString("zones", zonesNext.toString()).putLong("lastSpeedAlert", lastSpeedNext);
            }
            if (!tripId.isEmpty()) {
                saved.putString("queue", "[]")
                     .putInt("pending", 0);
            }
            if (endTripId != null) {
                saved.putString("tripId", "").putInt("moveHits", 0);
            }
            saved.apply();
            if (alertsOk && alerts.length() > 0) notifyAdmins(p, sid, vehId, token, alerts);
            Log.i(TAG, "OK " + vehId + " " + loc.getLatitude() + "," + loc.getLongitude()
                    + (fromCallback ? " (cb)" : " (tick)"));
        } catch (Exception e) {
            Log.e(TAG, "upload fallo", e);
            String msg = e.getMessage();
            if (msg == null) msg = "upload fallo";
            setErr(msg.length() > 160 ? msg.substring(0, 160) : msg);
            enqueueOffline(loc);
        } finally {
            uploading.set(false);
        }
    }

    /** Salud del telefono para el vigilante del dashboard: hora del ultimo fix GPS, ubicacion encendida, cola, error. */
    private void addHealth(JSONObject live, SharedPreferences p, long hb, long fixTime) {
        try {
            live.put("hb", hb);
            live.put("fix", fixTime > 0 ? fixTime : p.getLong("lastFix", 0));
            live.put("locOn", locationEnabled());
            live.put("pend", p.getInt("pending", 0));
            String err = p.getString("lastError", "");
            live.put("err", err == null ? "" : (err.length() > 60 ? err.substring(0, 60) : err));
            live.put("nat", true);
        } catch (Exception ignored) { }
    }

    private boolean locationEnabled() {
        try {
            android.location.LocationManager lm = (android.location.LocationManager) getSystemService(LOCATION_SERVICE);
            if (lm == null) return false;
            if (Build.VERSION.SDK_INT >= 28) return lm.isLocationEnabled();
            return lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
                    || lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER);
        } catch (Exception e) { return false; }
    }

    /** Sin ninguna posicion disponible: manda solo el latido para que el dashboard sepa que la app sigue viva. */
    private void heartbeatOnly() {
        if (!uploading.compareAndSet(false, true)) return;
        SharedPreferences p = prefs();
        try {
            String base = p.getString("supabaseUrl", ""), key = p.getString("anonKey", "");
            String rowId = p.getString("rowId", ""), vehId = p.getString("vehId", ""), token = p.getString("token", "");
            if (base.isEmpty() || key.isEmpty() || rowId.isEmpty() || vehId.isEmpty() || token.isEmpty()) return;
            String sid = rowId.startsWith("gps_track_") ? rowId.substring("gps_track_".length()) : "llave2";
            JSONObject live = new JSONObject();
            long ts = System.currentTimeMillis();
            addHealth(live, p, ts, 0);
            live.put("nofix", true);
            try {
                Intent bi = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (bi != null) {
                    int lvl = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = bi.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                    if (lvl >= 0 && scale > 0) live.put("battery", Math.round(lvl * 100f / scale));
                }
            } catch (Exception ignore) { }
            JSONObject patch = new JSONObject();
            patch.put("live", live);
            JSONObject args = new JSONObject();
            args.put("p_sid", sid); args.put("p_veh", vehId); args.put("p_token", token); args.put("p", patch);
            callRpc(base, key, "gps_apply", args);
            p.edit().putLong("lastHb", ts).putString("lastError", "").apply();
        } catch (Exception e) {
            String m = e.getMessage();
            setErr(m == null ? "latido fallo" : (m.length() > 160 ? m.substring(0, 160) : m));
        } finally {
            uploading.set(false);
        }
    }

    /** Guarda en prefs los numeros autorizados (SMS) y contactos SOS que el administrador definio en el CRM. */
    private void applyPublicConfig(JSONObject pl, SharedPreferences p) {
        try {
            JSONObject cfg = pl.optJSONObject("config");
            if (cfg == null) return;
            SharedPreferences.Editor e = p.edit();
            JSONArray admins = cfg.optJSONArray("adminPhones");
            if (admins != null) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < admins.length(); i++) {
                    String n = admins.optString(i, "").trim();
                    if (n.isEmpty()) continue;
                    if (sb.length() > 0) sb.append(',');
                    sb.append(n);
                }
                e.putString("smsAdmins", sb.toString());
            }
            JSONArray sos = cfg.optJSONArray("sosContacts");
            if (sos != null && sos.length() > 0) {
                e.putString("sosContacts", sos.toString());
            } else if (admins != null && admins.length() > 0) {
                // Sin contactos de emergencia propios: el SOS avisa a los administradores
                JSONArray fb = new JSONArray();
                for (int i = 0; i < admins.length(); i++) {
                    String n = admins.optString(i, "").trim();
                    if (n.isEmpty()) continue;
                    JSONObject o = new JSONObject();
                    o.put("name", "Administracion");
                    o.put("phone", n);
                    fb.put(o);
                }
                e.putString("sosContacts", fb.toString());
            } else if (sos != null) {
                e.putString("sosContacts", "[]");
            }
            e.apply();
        } catch (Exception ignored) { }
    }

    private static String last3(String v) {
        return v.length() > 3 ? v.substring(v.length() - 3) : v;
    }

    private static String fmtNum(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    /** Devuelve el nuevo lastSpeedAlert; llena alerts/events y zonesOut. No guarda nada (se guarda tras subir con exito). */
    private long buildAlerts(SharedPreferences p, String base, String key, String sid, String vehId,
                             Location loc, double speedKmh, long ts,
                             JSONArray alerts, JSONArray events, JSONObject zonesOut) throws Exception {
        JSONObject pub = loadPublic(base, key, sid, p);
        JSONObject cfg = pub != null ? pub.optJSONObject("config") : null;
        if (cfg == null) cfg = new JSONObject();

        double limit = 90;
        JSONObject byVeh = cfg.optJSONObject("speedByVeh");
        double lv = byVeh != null ? byVeh.optDouble(vehId, 0) : 0;
        if (lv > 0) {
            limit = lv;
        } else {
            double g = cfg.optDouble("speedLimit", 0);
            if (g > 0) limit = g;
        }
        boolean speedEnabled = !(cfg.has("speedEnabled") && !cfg.isNull("speedEnabled") && !cfg.optBoolean("speedEnabled", true));
        double cm = cfg.optDouble("speedCooldownMin", 5);
        if (!(cm > 0)) cm = 5;
        if (cm < 1) cm = 1;
        long coolMs = (long) (cm * 60000);

        List<AlertEngine.Geo> geos = new ArrayList<AlertEngine.Geo>();
        JSONArray ga = pub != null ? pub.optJSONArray("geofences") : null;
        if (ga != null) {
            for (int i = 0; i < ga.length(); i++) {
                JSONObject g = ga.optJSONObject(i);
                if (g == null) continue;
                if (g.has("active") && !g.isNull("active") && !g.optBoolean("active", true)) continue;
                JSONArray vids = g.optJSONArray("vehIds");
                if (vids != null && vids.length() > 0) {
                    boolean match = false;
                    for (int j = 0; j < vids.length(); j++) {
                        if (vehId.equals(String.valueOf(vids.opt(j)))) match = true;
                    }
                    if (!match) continue;
                }
                AlertEngine.Geo geo = new AlertEngine.Geo();
                geo.id = g.optString("id", "");
                geo.name = g.optString("name", "");
                geo.lat = g.optDouble("lat", 0);
                geo.lng = g.optDouble("lng", 0);
                geo.radiusM = g.optDouble("radiusM", 0);
                Object ae = g.opt("alertEnter");
                Object ax = g.opt("alertExit");
                geo.enterNotFalse = !(ae instanceof Boolean && !((Boolean) ae).booleanValue());
                geo.enterTruthy = g.optBoolean("alertEnter", false);
                geo.exitNotFalse = !(ax instanceof Boolean && !((Boolean) ax).booleanValue());
                geos.add(geo);
            }
        }

        Map<String, Boolean> zones = new HashMap<String, Boolean>();
        JSONObject zsPrev = new JSONObject(p.getString("zones", "{}"));
        Iterator<String> it = zsPrev.keys();
        while (it.hasNext()) {
            String k = it.next();
            zones.put(k, zsPrev.optBoolean(k, false));
        }

        AlertEngine.Result r = AlertEngine.eval(loc.getLatitude(), loc.getLongitude(), speedKmh, ts,
                limit, speedEnabled, coolMs, geos, zones, p.getLong("lastSpeedAlert", 0));

        String alias = p.getString("alias", "");
        String placas = p.getString("placas", "");
        String vehName = !alias.isEmpty() ? alias : (!placas.isEmpty() ? placas : "Veh\u00edculo");
        String sfx = last3(vehId);
        String t36 = Long.toString(ts, 36);
        String tripId = p.getString("tripId", "");

        for (AlertEngine.Item item : r.items) {
            if ("speed".equals(item.kind)) {
                JSONObject a = new JSONObject();
                a.put("id", "a_" + t36 + "_sp" + sfx);
                a.put("type", "speed");
                a.put("severity", "danger");
                a.put("vehId", vehId);
                a.put("vehName", vehName);
                a.put("msg", "Exceso de velocidad: " + Math.round(item.speed) + " km/h (l\u00edmite " + fmtNum(item.limit) + ")");
                a.put("speed", Math.round(item.speed));
                a.put("limit", item.limit);
                a.put("lat", loc.getLatitude());
                a.put("lng", loc.getLongitude());
                a.put("ts", ts);
                alerts.put(a);
                continue;
            }
            String gname = item.geoName.isEmpty() ? "zona" : item.geoName;
            if ("enter".equals(item.kind) || "exit".equals(item.kind)) {
                boolean enter = "enter".equals(item.kind);
                JSONObject a = new JSONObject();
                a.put("id", "a_" + t36 + (enter ? "_e" : "_x") + last3(item.geoId) + sfx);
                a.put("type", item.kind);
                a.put("severity", enter ? "info" : "danger");
                a.put("vehId", vehId);
                a.put("vehName", vehName);
                a.put("geoId", item.geoId);
                a.put("geoName", item.geoName);
                a.put("msg", (enter ? "Entr\u00f3 a " : "Sali\u00f3 de ") + gname);
                a.put("lat", loc.getLatitude());
                a.put("lng", loc.getLongitude());
                a.put("ts", ts);
                alerts.put(a);
            }
            if (item.breach) {
                JSONObject e = new JSONObject();
                e.put("id", "e_" + t36 + "_GE" + sfx);
                e.put("kind", "GEOFENCE_BREACH");
                e.put("vehId", vehId);
                e.put("vehName", vehName);
                e.put("driver", p.getString("driver", ""));
                e.put("tripId", tripId.isEmpty() ? JSONObject.NULL : tripId);
                e.put("ts", ts);
                e.put("lat", loc.getLatitude());
                e.put("lng", loc.getLongitude());
                e.put("speed", Math.round(speedKmh));
                e.put("passenger", p.getBoolean("passenger", true));
                e.put("geoId", item.geoId);
                e.put("geoName", item.geoName);
                e.put("direction", item.direction);
                e.put("risk", "LOW");
                events.put(e);
            }
        }
        for (Map.Entry<String, Boolean> en : zones.entrySet()) {
            zonesOut.put(en.getKey(), en.getValue().booleanValue());
        }
        return r.lastSpeedAlert;
    }

    /** Config del CRM (limite de velocidad y zonas): fila gps_public_<sid>, se refresca cada 2 min. */
    private JSONObject loadPublic(String base, String key, String sid, SharedPreferences p) {
        long now = System.currentTimeMillis();
        if (pubCache != null && sid.equals(pubSid) && now - pubTs < 120000L) return pubCache;
        try {
            URL url = new URL(base + "/rest/v1/clientes?select=payload&id=eq.gps_public_" + sid);
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("GET");
            c.setRequestProperty("apikey", key);
            c.setRequestProperty("Authorization", "Bearer " + key);
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            int code = c.getResponseCode();
            String body = readBody(c, code >= 200 && code < 300);
            if (code >= 200 && code < 300) {
                JSONArray arr = new JSONArray(body);
                if (arr.length() > 0) {
                    JSONObject pl = arr.getJSONObject(0).optJSONObject("payload");
                    if (pl != null) {
                        pubCache = pl;
                        pubTs = now;
                        pubSid = sid;
                        p.edit().putString("pubJson", pl.toString()).apply();
                        applyPublicConfig(pl, p);
                        return pl;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "config publica: " + e.getMessage());
        }
        if (pubCache != null && sid.equals(pubSid)) {
            pubTs = now - 100000L; // reintentar pronto
            return pubCache;
        }
        try {
            String saved = p.getString("pubJson", "");
            if (!saved.isEmpty()) return new JSONObject(saved);
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Avisa al Worker de SMS (mismo formato que la pagina). Solo velocidad y salida de zona. */
    private void notifyAdmins(SharedPreferences p, String sid, String vehId, String token, JSONArray alerts) {
        try {
            String u = p.getString("notifyUrl", "");
            if (u.isEmpty()) u = DEFAULT_NOTIFY_URL;
            JSONArray items = new JSONArray();
            for (int i = 0; i < alerts.length() && items.length() < 3; i++) {
                JSONObject a = alerts.optJSONObject(i);
                if (a == null) continue;
                String t = a.optString("type", "");
                if (!"speed".equals(t) && !"exit".equals(t)) continue;
                JSONObject o = new JSONObject();
                o.put("type", t);
                o.put("risk", "");
                o.put("vehName", a.optString("vehName", ""));
                o.put("msg", a.optString("msg", ""));
                o.put("lat", a.opt("lat"));
                o.put("lng", a.opt("lng"));
                o.put("ts", a.opt("ts"));
                items.put(o);
            }
            if (items.length() == 0) return;
            JSONObject body = new JSONObject();
            body.put("s", sid);
            body.put("v", vehId);
            body.put("t", token);
            body.put("alerts", items);
            final String target = u;
            final byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        HttpURLConnection c = (HttpURLConnection) new URL(target).openConnection();
                        c.setRequestMethod("POST");
                        c.setDoOutput(true);
                        c.setRequestProperty("Content-Type", "text/plain");
                        c.setConnectTimeout(10000);
                        c.setReadTimeout(10000);
                        c.setFixedLengthStreamingMode(bytes.length);
                        OutputStream os = c.getOutputStream();
                        os.write(bytes);
                        os.close();
                        int code = c.getResponseCode();
                        readBody(c, code >= 200 && code < 300);
                        c.disconnect();
                    } catch (Exception e) {
                        Log.w(TAG, "sms: " + e.getMessage());
                    }
                }
            }).start();
        } catch (Exception e) {
            Log.w(TAG, "notify: " + e.getMessage());
        }
    }

    private static JSONArray pointRow(long ts, double lat, double lng, double speedKmh) throws Exception {
        JSONArray row = new JSONArray();
        row.put(ts);
        row.put(Math.round(lat * 1000000.0) / 1000000.0);
        row.put(Math.round(lng * 1000000.0) / 1000000.0);
        row.put((int) Math.round(speedKmh));
        return row;
    }

    private void enqueueOffline(Location loc) {
        try {
            SharedPreferences p = prefs();
            JSONArray q = new JSONArray(p.getString("queue", "[]"));
            double sp = 0;
            if (loc.hasSpeed() && loc.getSpeed() >= 0) sp = loc.getSpeed() * 3.6;
            q.put(pointRow(System.currentTimeMillis(), loc.getLatitude(), loc.getLongitude(), sp));
            JSONArray clipped = new JSONArray();
            int from = q.length() - 300;
            if (from < 0) from = 0;
            for (int i = from; i < q.length(); i++) {
                clipped.put(q.get(i));
            }
            p.edit().putString("queue", clipped.toString()).putInt("pending", clipped.length()).apply();
        } catch (Exception ignored) {
        }
    }

    private void callRpc(String base, String key, String fn, JSONObject args) throws Exception {
        URL url = new URL(base + "/rest/v1/rpc/" + fn);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("apikey", key);
        c.setRequestProperty("Authorization", "Bearer " + key);
        c.setRequestProperty("Content-Type", "application/json");
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        byte[] bytes = args.toString().getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(bytes.length);
        OutputStream os = c.getOutputStream();
        os.write(bytes);
        os.close();
        int code = c.getResponseCode();
        String resp = readBody(c, code >= 200 && code < 300);
        if (code < 200 || code >= 300) {
            throw new RuntimeException("RPC " + code + " " + resp);
        }
    }

    private void setErr(String msg) {
        prefs().edit().putString("lastError", msg == null ? "" : msg).apply();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
    }

    private static String readBody(HttpURLConnection c, boolean ok) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(
                ok ? c.getInputStream()
                        : (c.getErrorStream() != null ? c.getErrorStream() : c.getInputStream()),
                StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return sb.toString();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "La Llave GPS", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Seguimiento de vehiculo en ruta");
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(ch);
    }

    private Notification buildNotification(String text) {
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, open != null ? open : new Intent(),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("La Llave · GPS activo")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(tick);
        if (fused != null && callback != null) {
            try {
                fused.removeLocationUpdates(callback);
            } catch (Exception ignored) {
            }
        }
        net.shutdownNow();

        // Liberar el WakeLock de forma segura
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Exception ignored) {}
        }

        RUNNING = false;
        prefs().edit().putBoolean("running", false).apply();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
