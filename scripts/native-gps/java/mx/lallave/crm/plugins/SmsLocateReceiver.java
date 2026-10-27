package mx.lallave.crm.plugins;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Telephony;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Respaldo por SMS: si llega el mensaje "LA LLAVE" (palabra secreta) al telefono del chofer,
 * responde por SMS con la ubicacion actual (enlace de Google Maps).
 * - Palabra secreta configurable (prefs "smsKeyword"), por defecto "LA LLAVE".
 * - Opcional: lista de numeros autorizados (prefs "smsAllowed", separados por coma). Vacia = acepta cualquiera.
 * - Maximo 1 respuesta cada 30 s por remitente.
 */
public class SmsLocateReceiver extends BroadcastReceiver {

    private static final String PREFS = GpsUploaderPlugin.PREFS;
    private static final String DEFAULT_KEYWORD = "LA LLAVE";
    private static final long FIX_TIMEOUT_MS = 20000;
    private static final long MIN_GAP_MS = 30000;

    static String norm(String s) {
        if (s == null) return "";
        return s.trim().replaceAll("\\s+", " ").toUpperCase(java.util.Locale.ROOT);
    }

    static String digits(String s) {
        if (s == null) return "";
        String d = s.replaceAll("\\D", "");
        return d.length() > 10 ? d.substring(d.length() - 10) : d;
    }

    static boolean allowed(String allowedCsv, String sender) {
        if (allowedCsv == null || allowedCsv.trim().isEmpty()) return true;
        String s = digits(sender);
        if (s.isEmpty()) return false;
        for (String a : allowedCsv.split(",")) {
            if (!digits(a).isEmpty() && digits(a).equals(s)) return true;
        }
        return false;
    }

    @Override
    public void onReceive(final Context context, Intent intent) {
        if (intent == null || !Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) return;
        SmsMessage[] msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        if (msgs == null || msgs.length == 0) return;

        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String keyword = norm(prefs.getString("smsKeyword", DEFAULT_KEYWORD));
        if (keyword.isEmpty()) keyword = DEFAULT_KEYWORD;
        String allowed = prefs.getString("smsAllowed", "");

        // Une las partes de un mismo SMS por remitente
        String sender = null;
        StringBuilder body = new StringBuilder();
        for (SmsMessage m : msgs) {
            if (m == null) continue;
            if (sender == null) sender = m.getDisplayOriginatingAddress();
            if (m.getDisplayMessageBody() != null) body.append(m.getDisplayMessageBody());
        }
        if (sender == null || !norm(body.toString()).equals(keyword)) return;
        if (!allowed(allowed, sender)) return;

        long now = System.currentTimeMillis();
        long last = prefs.getLong("smsLastReply", 0);
        if (now - last < MIN_GAP_MS) return;
        prefs.edit().putLong("smsLastReply", now).putString("smsLastSender", sender).apply();

        final PendingResult pr = goAsync();
        reply(context.getApplicationContext(), sender, pr);
    }

    private void reply(final Context ctx, final String sender, final PendingResult pr) {
        final AtomicBoolean done = new AtomicBoolean(false);
        final Handler handler = new Handler(Looper.getMainLooper());
        final LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);

        if (lm == null || (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)) {
            send(ctx, sender, "La Llave: el telefono no tiene permiso de ubicacion.");
            pr.finish();
            return;
        }

        // Ultima ubicacion conocida como respaldo
        Location best = null;
        try {
            for (String p : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(p);
                if (l == null) continue;
                if (best == null || l.getTime() > best.getTime()) best = l;
            }
        } catch (SecurityException ignored) {}
        final Location fallback = best;

        final LocationListener[] holder = new LocationListener[1];
        final Runnable finish = new Runnable() {
            @Override public void run() {
                try { if (holder[0] != null) lm.removeUpdates(holder[0]); } catch (Exception ignored) {}
            }
        };
        final Runnable timeout = new Runnable() {
            @Override public void run() {
                if (!done.compareAndSet(false, true)) return;
                finish.run();
                if (fallback != null) sendLoc(ctx, sender, fallback, true);
                else send(ctx, sender, "La Llave: no se pudo obtener la ubicacion (sin senal GPS).");
                pr.finish();
            }
        };
        holder[0] = new LocationListener() {
            @Override public void onLocationChanged(Location location) {
                if (!done.compareAndSet(false, true)) return;
                handler.removeCallbacks(timeout);
                finish.run();
                sendLoc(ctx, sender, location, false);
                pr.finish();
            }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
            @Override public void onProviderEnabled(String provider) {}
            @Override public void onProviderDisabled(String provider) {}
        };

        try {
            boolean requested = false;
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, holder[0], Looper.getMainLooper());
                requested = true;
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, holder[0], Looper.getMainLooper());
                requested = true;
            }
            if (!requested) {
                if (done.compareAndSet(false, true)) {
                    if (fallback != null) sendLoc(ctx, sender, fallback, true);
                    else send(ctx, sender, "La Llave: la ubicacion (GPS) esta apagada en el telefono.");
                    pr.finish();
                }
                return;
            }
        } catch (Exception e) {
            if (done.compareAndSet(false, true)) {
                if (fallback != null) sendLoc(ctx, sender, fallback, true);
                pr.finish();
            }
            return;
        }
        handler.postDelayed(timeout, FIX_TIMEOUT_MS);
    }

    private void sendLoc(Context ctx, String to, Location l, boolean old) {
        String age = "";
        if (old) {
            long mins = Math.max(0, (System.currentTimeMillis() - l.getTime()) / 60000);
            age = " (ultima conocida, hace " + mins + " min)";
        }
        String msg = "La Llave - ubicacion" + age + " ±" + (int) l.getAccuracy() + "m:\n"
                + "https://maps.google.com/?q=" + l.getLatitude() + "," + l.getLongitude();
        send(ctx, to, msg);
    }

    @SuppressWarnings("deprecation")
    private void send(Context ctx, String to, String text) {
        try {
            SmsManager sm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? ctx.getSystemService(SmsManager.class) : SmsManager.getDefault();
            ArrayList<String> parts = sm.divideMessage(text);
            if (parts.size() > 1) sm.sendMultipartTextMessage(to, null, parts, null, null);
            else sm.sendTextMessage(to, null, text, null, null);
        } catch (Exception ignored) {
            // sin saldo / sin permiso SEND_SMS
        }
    }
}
