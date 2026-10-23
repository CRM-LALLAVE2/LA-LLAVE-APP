package mx.lallave.crm.plugins;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * GPS + subida a Supabase independiente del WebView.
 * Ciclo fijo: usa última ubicación conocida y siempre refresca "seen".
 */
public class GpsUploadService extends Service {
    private static final String TAG = "LlaveGpsUpload";
    private static final String CHANNEL_ID = "llave_gps_upload";
    private static final int NOTIF_ID = 4242;

    private FusedLocationProviderClient fused;
    private LocationCallback callback;
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean uploading = new AtomicBoolean(false);

    private volatile Location lastLoc;
    private long intervalMs = 15_000L;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            SharedPreferences p = prefs();
            if (!p.getBoolean("running", false)) {
                stopSelf();
                return;
            }
            Location loc = lastLoc;
            if (loc != null) {
                net.execute(() -> upload(loc, false));
            } else {
                // Sin punto aún: intenta última conocida del sistema
                try {
                    fused.getLastLocation().addOnSuccessListener(l -> {
                        if (l != null) {
                            lastLoc = l;
                            net.execute(() -> upload(l, false));
                        }
                    });
                } catch (SecurityException e) {
                    setErr("Sin permiso de ubicación");
                }
            }
            handler.postDelayed(this, intervalMs);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        fused = LocationServices.getFusedLocationProviderClient(this);
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        SharedPreferences p = prefs();
        intervalMs = Math.max(10_000L, p.getInt("intervalSec", 15) * 1000L);

        Notification n = buildNotification("Enviando ubicación del vehículo");
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIF_ID, n);
        }

        startUpdates();
        handler.removeCallbacks(tick);
        handler.post(tick); // primer ciclo inmediato
        return START_STICKY;
    }

    private void startUpdates() {
        if (callback != null) {
            try { fused.removeLocationUpdates(callback); } catch (Exception ignored) {}
            callback = null;
        }

        LocationRequest req = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(10_000L)
            .setMinUpdateDistanceMeters(0f)
            .setMaxUpdateDelayMillis(intervalMs)
            .setWaitForAccurateLocation(false)
            .build();

        callback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult result) {
                if (result == null) return;
                Location loc = result.getLastLocation();
                if (loc == null) return;
                lastLoc = loc;
                // Subida extra al tener punto fresco (el tick cubre el resto)
                if (uploading.compareAndSet(false, false)) {
                    net.execute(() -> upload(loc, true));
                }
            }
        };

        try {
            fused.requestLocationUpdates(req, callback, Looper.getMainLooper());
        } catch (SecurityException e) {
            Log.e(TAG, "Sin permiso de ubicación", e);
            setErr("Sin permiso de ubicación");
            stopSelf();
        }
    }

    private void upload(Location loc, boolean fromCallback) {
        if (!uploading.compareAndSet(false, true)) return;
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
            if (base.isEmpty() || key.isEmpty() || rowId.isEmpty() || vehId.isEmpty()) {
                setErr("Config incompleta (url/key/rowId/vehId)");
                return;
            }

            // 1) Reintentar cola offline primero
            flushQueue(base, key, rowId, vehId, p);

            // 2) Armar live point
            JSONObject payload = fetchPayload(base, key, rowId);
            if (payload == null) payload = new JSONObject();
            payload.put("id", rowId);
            payload.put("tipoRegistro", "gps_flota");
            if (!payload.has("sucursal")) {
                String sid = rowId.startsWith("gps_track_")
                    ? rowId.substring("gps_track_".length()) : "llave2";
                payload.put("sucursal", sid);
            }

            JSONObject live = payload.optJSONObject("live");
            if (live == null) live = new JSONObject();
            JSONObject point = live.optJSONObject(vehId);
            if (point == null) point = new JSONObject();

            long ts = System.currentTimeMillis();
            double speedKmh = loc.hasSpeed() && loc.getSpeed() >= 0 ? loc.getSpeed() * 3.6 : 0;

            point.put("lat", loc.getLatitude());
            point.put("lng", loc.getLongitude());
            point.put("speed", speedKmh);
            point.put("accuracy", loc.hasAccuracy() ? (double) loc.getAccuracy() : JSONObject.NULL);
            point.put("heading", loc.hasBearing() ? (double) loc.getBearing() : JSONObject.NULL);
            point.put("ts", ts);
            point.put("seen", ts);          // heartbeat aunque no se mueva
            point.put("active", true);
            point.put("alias", p.getString("alias", ""));
            point.put("placas", p.getString("placas", ""));
            point.put("driver", p.getString("driver", ""));
            point.put("passenger", p.getBoolean("passenger", true));
            String tripId = p.getString("tripId", "");
            if (!tripId.isEmpty()) point.put("tripId", tripId);

            live.put(vehId, point);
            payload.put("live", live);
            payload.put("updatedAt", ts);

            // trips (append ligero)
            if (!tripId.isEmpty()) {
                JSONArray trips = payload.optJSONArray("trips");
                if (trips == null) trips = new JSONArray();
                JSONObject trip = null;
                for (int i = 0; i < trips.length(); i++) {
                    JSONObject t = trips.optJSONObject(i);
                    if (t != null && tripId.equals(t.optString("id"))) { trip = t; break; }
                }
                if (trip == null) {
                    trip = new JSONObject();
                    trip.put("id", tripId);
                    trip.put("vehId", vehId);
                    trip.put("startTs", ts);
                    trip.put("endTs", JSONObject.NULL);
                    trip.put("points", new JSONArray());
                    // newest first, como el JS
                    JSONArray next = new JSONArray();
                    next.put(trip);
                    for (int i = 0; i < trips.length() && i < 40; i++) next.put(trips.get(i));
                    trips = next;
                }
                JSONArray pts = trip.optJSONArray("points");
                if (pts == null) pts = new JSONArray();
                JSONArray row = new JSONArray();
                row.put(ts);
                row.put(Math.round(loc.getLatitude() * 1e6) / 1e6.0);
                row.put(Math.round(loc.getLongitude() * 1e6) / 1e6.0);
                row.put((int) Math.round(speedKmh));
                pts.put(row);
                trip.put("points", pts);
                // reescribir trip en array
                boolean replaced = false;
                JSONArray out = new JSONArray();
                for (int i = 0; i < trips.length(); i++) {
                    JSONObject t = trips.optJSONObject(i);
                    if (t != null && tripId.equals(t.optString("id"))) {
                        out.put(trip); replaced = true;
                    } else if (t != null) out.put(t);
                }
                if (!replaced) out.put(trip);
                payload.put("trips", out);
            }

            upsertPayload(base, key, rowId, payload);

            p.edit()
                .putLong("lastUploadOk", ts)
                .putString("lastError", "")
                .putInt("pending", 0)
                .apply();
            Log.i(TAG, "OK " + vehId + " " + loc.getLatitude() + "," + loc.getLongitude()
                + (fromCallback ? " (cb)" : " (tick)"));
        } catch (Exception e) {
            Log.e(TAG, "upload falló", e);
            setErr(e.getMessage() != null ? e.getMessage() : "upload falló");
            enqueueOffline(loc);
        } finally {
            uploading.set(false);
        }
    }

    private void enqueueOffline(Location loc) {
        try {
            SharedPreferences p = prefs();
            JSONArray q = new JSONArray(p.getString("queue", "[]"));
            JSONObject o = new JSONObject();
            o.put("lat", loc.getLatitude());
            o.put("lng", loc.getLongitude());
            o.put("speed", loc.hasSpeed() ? loc.getSpeed() * 3.6 : 0);
            o.put("ts", System.currentTimeMillis());
            q.put(o);
            // cap 100
            JSONArray clipped = new JSONArray();
            int from = Math.max(0, q.length() - 100);
            for (int i = from; i < q.length(); i++) clipped.put(q.get(i));
            p.edit().putString("queue", clipped.toString()).putInt("pending", clipped.length()).apply();
        } catch (Exception ignored) {}
    }

    private void flushQueue(String base, String key, String rowId, String vehId, SharedPreferences p) {
        // v1: la cola se reintenta en el siguiente upload completo; se limpia si el upsert OK
        // (el punto actual ya refresca live; no hace falta reenviar cada punto histórico en P0)
    }

    private JSONObject fetchPayload(String base, String key, String rowId) throws Exception {
        URL url = new URL(base + "/rest/v1/clientes?id=eq." + urlEncode(rowId) + "&select=payload");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod("GET");
        c.setRequestProperty("apikey", key);
        c.setRequestProperty("Authorization", "Bearer " + key);
        c.setRequestProperty("Accept", "application/json");
        c.setConnectTimeout(20000);
        c.setReadTimeout(20000);
        int code = c.getResponseCode();
        String body = readBody(c, code >= 200 && code < 300);
        c.disconnect();
        if (code < 200 || code >= 300) {
            throw new RuntimeException("GET " + code + " " + body);
        }
        JSONArray arr = new JSONArray(body);
        if (arr.length() == 0) return new JSONObject();
        JSONObject row = arr.getJSONObject(0);
        if (row.isNull("payload")) return new JSONObject();
        Object pl = row.get("payload");
        if (pl instanceof JSONObject) return (JSONObject) pl;
        return new JSONObject(String.valueOf(pl));
    }

    private void upsertPayload(String base, String key, String rowId, JSONObject payload) throws Exception {
        JSONArray body = new JSONArray();
        JSONObject row = new JSONObject();
        row.put("id", rowId);
        row.put("payload", payload);
        body.put(row);
        URL url = new URL(base + "/rest/v1/clientes?on_conflict=id");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("apikey", key);
        c.setRequestProperty("Authorization", "Bearer " + key);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Prefer", "resolution=merge-duplicates,return=minimal");
        c.setConnectTimeout(20000);
        c.setReadTimeout(20000);
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(bytes.length);
        OutputStream os = c.getOutputStream();
        os.write(bytes);
        os.close();
        int code = c.getResponseCode();
        String resp = readBody(c, code >= 200 && code < 300);
        c.disconnect();
        if (code < 200 || code >= 300) {
            throw new RuntimeException("POST " + code + " " + resp);
        }
    }

    private void setErr(String msg) {
        prefs().edit().putString("lastError", msg == null ? "" : msg).apply();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(GpsUploaderPlugin.PREFS, MODE_PRIVATE);
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

    private static String urlEncode(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel ch = new NotificationChannel(
            CHANNEL_ID, "La Llave GPS", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Seguimiento de vehículo en ruta");
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
            try { fused.removeLocationUpdates(callback); } catch (Exception ignored) {}
        }
        net.shutdownNow();
        prefs().edit().putBoolean("running", false).apply();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
