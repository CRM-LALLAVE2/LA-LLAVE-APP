package mx.lallave.crm.plugins;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.os.Build;
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

/**
 * Servicio en primer plano: obtiene GPS en nativo y sube a Supabase
 * aunque el WebView esté congelado.
 */
public class GpsUploadService extends Service {
    private static final String TAG = "LlaveGpsUpload";
    private static final String CHANNEL_ID = "llave_gps_upload";
    private static final int NOTIF_ID = 4242;

    private FusedLocationProviderClient fused;
    private LocationCallback callback;
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private long lastUploadMs = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        fused = LocationServices.getFusedLocationProviderClient(this);
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = buildNotification("Enviando ubicación del vehículo");
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIF_ID, n);
        }
        startUpdates();
        return START_STICKY;
    }

    private void startUpdates() {
        SharedPreferences p = getSharedPreferences(GpsUploaderPlugin.PREFS, MODE_PRIVATE);
        int intervalSec = p.getInt("intervalSec", 15);
        long intervalMs = intervalSec * 1000L;

        LocationRequest req = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(Math.max(10000, intervalMs - 5000))
            .setMinUpdateDistanceMeters(0)
            .setWaitForAccurateLocation(false)
            .build();

        callback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult result) {
                if (result == null) return;
                Location loc = result.getLastLocation();
                if (loc == null) return;
                long now = System.currentTimeMillis();
                if (now - lastUploadMs < 8000) return;
                lastUploadMs = now;
                net.execute(() -> upload(loc));
            }
        };

        try {
            fused.requestLocationUpdates(req, callback, Looper.getMainLooper());
        } catch (SecurityException e) {
            Log.e(TAG, "Sin permiso de ubicación", e);
            stopSelf();
        }
    }

    private void upload(Location loc) {
        SharedPreferences p = getSharedPreferences(GpsUploaderPlugin.PREFS, MODE_PRIVATE);
        if (!p.getBoolean("running", false)) {
            stopSelf();
            return;
        }
        String base = p.getString("supabaseUrl", "");
        String key = p.getString("anonKey", "");
        String rowId = p.getString("rowId", "");
        String vehId = p.getString("vehId", "");
        if (base.isEmpty() || key.isEmpty() || rowId.isEmpty() || vehId.isEmpty()) return;

        try {
            // 1) Leer payload actual
            JSONObject payload = fetchPayload(base, key, rowId);
            if (payload == null) payload = new JSONObject();
            payload.put("id", rowId);
            payload.put("tipoRegistro", "gps_flota");
            if (!payload.has("sucursal")) {
                String sid = rowId.startsWith("gps_track_") ? rowId.substring("gps_track_".length()) : "llave2";
                payload.put("sucursal", sid);
            }

            JSONObject live = payload.optJSONObject("live");
            if (live == null) live = new JSONObject();
            JSONObject point = live.optJSONObject(vehId);
            if (point == null) point = new JSONObject();

            double speedKmh = loc.hasSpeed() && loc.getSpeed() >= 0 ? loc.getSpeed() * 3.6 : 0;
            long ts = System.currentTimeMillis();
            point.put("lat", loc.getLatitude());
            point.put("lng", loc.getLongitude());
            point.put("speed", speedKmh);
            point.put("accuracy", loc.hasAccuracy() ? loc.getAccuracy() : JSONObject.NULL);
            point.put("heading", loc.hasBearing() ? loc.getBearing() : JSONObject.NULL);
            point.put("ts", ts);
            point.put("seen", ts);
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

            // 2) Upsert
            upsertPayload(base, key, rowId, payload);
            Log.i(TAG, "OK " + vehId + " " + loc.getLatitude() + "," + loc.getLongitude());
        } catch (Exception e) {
            Log.e(TAG, "upload falló", e);
        }
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
            Log.w(TAG, "GET " + code + " " + body);
            return null;
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

    private static String readBody(HttpURLConnection c, boolean ok) throws Exception {
        BufferedReader br = new BufferedReader(new InputStreamReader(
            ok ? c.getInputStream() : (c.getErrorStream() != null ? c.getErrorStream() : c.getInputStream()),
            StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return sb.toString();
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
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
        if (fused != null && callback != null) {
            try { fused.removeLocationUpdates(callback); } catch (Exception ignored) {}
        }
        net.shutdownNow();
        getSharedPreferences(GpsUploaderPlugin.PREFS, MODE_PRIVATE)
            .edit().putBoolean("running", false).apply();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
