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
    private long intervalMs = 15000L;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            SharedPreferences p = prefs();
            if (!p.getBoolean("running", false)) {
                stopSelf();
                return;
            }
            Location loc = lastLoc;
            if (loc != null) {
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
        SharedPreferences p = prefs();
        int sec = p.getInt("intervalSec", 15);
        if (sec < 10) {
            sec = 10;
        }
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
                if (result == null) {
                    return;
                }
                Location loc = result.getLastLocation();
                if (loc == null) {
                    return;
                }
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
        if (!uploading.compareAndSet(false, true)) {
            return;
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
            if (base.isEmpty() || key.isEmpty() || rowId.isEmpty() || vehId.isEmpty()) {
                setErr("Config incompleta (url/key/rowId/vehId)");
                return;
            }

            JSONObject payload = fetchPayload(base, key, rowId);
            if (payload == null) {
                payload = new JSONObject();
            }
            payload.put("id", rowId);
            payload.put("tipoRegistro", "gps_flota");
            if (!payload.has("sucursal")) {
                String sid = "llave2";
                if (rowId.startsWith("gps_track_")) {
                    sid = rowId.substring("gps_track_".length());
                }
                payload.put("sucursal", sid);
            }

            JSONObject live = payload.optJSONObject("live");
            if (live == null) {
                live = new JSONObject();
            }
            JSONObject point = live.optJSONObject(vehId);
            if (point == null) {
                point = new JSONObject();
            }

            long ts = System.currentTimeMillis();
            double speedKmh = 0;
            if (loc.hasSpeed() && loc.getSpeed() >= 0) {
                speedKmh = loc.getSpeed() * 3.6;
            }

            point.put("lat", loc.getLatitude());
            point.put("lng", loc.getLongitude());
            point.put("speed", speedKmh);
            if (loc.hasAccuracy()) {
                point.put("accuracy", (double) loc.getAccuracy());
            } else {
                point.put("accuracy", JSONObject.NULL);
            }
            if (loc.hasBearing()) {
                point.put("heading", (double) loc.getBearing());
            } else {
                point.put("heading", JSONObject.NULL);
            }
            point.put("ts", ts);
            point.put("seen", ts);
            point.put("active", true);
            point.put("alias", p.getString("alias", ""));
            point.put("placas", p.getString("placas", ""));
            point.put("driver", p.getString("driver", ""));
            point.put("passenger", p.getBoolean("passenger", true));
            String tripId = p.getString("tripId", "");
