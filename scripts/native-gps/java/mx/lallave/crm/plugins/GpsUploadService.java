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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class GpsUploadService extends Service {
    private static final String TAG = "LlaveGpsUpload";
    private static final String CHANNEL_ID = "llave_gps_upload";
    private static final String PREFS_NAME = "llave_gps_uploader";
    private static final int NOTIF_ID = 4242;

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

            JSONObject live = new JSONObject();
            live.put("lat", loc.getLatitude());
            live.put("lng", loc.getLongitude());
            live.put("speed", speedKmh);
            live.put("accuracy", loc.hasAccuracy() ? (Object) Double.valueOf(loc.getAccuracy()) : JSONObject.NULL);
            live.put("heading", loc.hasBearing() ? (Object) Double.valueOf(loc.getBearing()) : JSONObject.NULL);
            live.put("ts", ts);
            live.put("seen", ts);
            live.put("active", true);

            JSONObject patch = new JSONObject();
            String tripId = p.getString("tripId", "");
            if (!tripId.isEmpty()) {
                live.put("tripId", tripId);
                JSONArray points = new JSONArray(p.getString("queue", "[]"));
                points.put(pointRow(ts, loc.getLatitude(), loc.getLongitude(), speedKmh));
                JSONObject trip = new JSONObject();
                trip.put("id", tripId);
                trip.put("startTs", ts);
                trip.put("points", points);
                patch.put("trip", trip);
            }
            patch.put("live", live);

            JSONObject args = new JSONObject();
            args.put("p_sid", sid);
            args.put("p_veh", vehId);
            args.put("p_token", token);
            args.put("p", patch);
            callRpc(base, key, "gps_apply", args);

            p.edit()
                    .putLong("lastUploadOk", ts)
                    .putString("lastError", "")
                    .putString("queue", "[]")
                    .putInt("pending", 0)
                    .apply();
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

        prefs().edit().putBoolean("running", false).apply();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
