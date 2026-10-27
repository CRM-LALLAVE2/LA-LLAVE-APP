package mx.lallave.crm.plugins;

import android.Manifest;
import android.annotation.SuppressLint;
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
import android.util.Log;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Respaldo por SMS: si llega el mensaje "LA LLAVE" (palabra secreta) al telefono del chofer,
 * responde por SMS con la ubicacion actual (enlace de Google Maps).
 * - Palabra secreta configurable (prefs "smsKeyword"), por defecto "LA LLAVE".
 * - Opcional: lista de numeros autorizados (prefs "smsAllowed", separados por coma). Vacia = acepta cualquiera.
 * - Maximo 1 respuesta cada 30 s por remitente.
 */
public class SmsLocateReceiver extends BroadcastReceiver {

    private static final String TAG = "SmsLocateReceiver";
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

        if (sender == null) return;

        String receivedText = norm(body.toString());
        if (!receivedText.equals(keyword)) {
            Log.d(TAG, "SMS ignorado. Esperado: '" + keyword + "' | Recibido: '" + receivedText + "'");
            return;
        }

        if (!allowed(allowed, sender)) {
            Log.w(TAG, "SMS descartado: remitente no autorizado (" + sender + ")");
            return;
        }

        long now = System.currentTimeMillis();
        long last = prefs.getLong("smsLastReply", 0);
        if (now - last < MIN_GAP_MS) {
            Log.w(TAG, "SMS ignorado por limite de tiempo (gap < 30s)");
            return;
        }

        prefs.edit().putLong("smsLastReply", now).putString("smsLastSender", sender).apply();

        final PendingResult pr = goAsync();
        reply(context.getApplicationContext(), sender, pr);
    }

    @SuppressLint("MissingPermission")
    private void reply(final Context ctx, final String sender, final PendingResult pr) {
        final AtomicBoolean done = new AtomicBoolean(false);
        final Handler handler = new Handler(Looper.getMainLooper());
        final LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);

        // Validacion de permisos de ubicacion
        if (lm == null || (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)) {
            Log.e(TAG, "Sin permisos de ubicacion otorgados.");
            send(ctx, sender, "La Llave: el telefono no tiene permiso de ubicacion otorgado.");
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
        } catch (SecurityException e) {
            Log.e(TAG, "Error al leer lastKnownLocation", e);
        }
        final Location fallback = best;

        final LocationListener[] holder = new LocationListener[1];
        final Runnable finishListener = new Runnable() {
            @Override public void run() {
                try {
                    if (holder[0] != null) lm.removeUpdates(holder[0]);
                } catch (Exception ignored) {}
            }
        };

        final Runnable timeout = new Runnable() {
            @Override public void run() {
                if (!done.compareAndSet(false, true)) return;
                finishListener.run();
                if (fallback != null) {
                    sendLoc(ctx, sender, fallback, true);
                } else {
                    send(ctx, sender, "La Llave: tiempo de espera agotado sin senal GPS.");
                }
                pr.finish();
            }
        };

        holder[0] = new LocationListener() {
            @Override public void onLocationChanged(Location location) {
                if (!done.compareAndSet(false, true)) return;
                handler.removeCallbacks(timeout);
                finishListener.run();
                sendLoc(ctx, sender, location, false);
                pr.finish();
            }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
            @Override public void onProviderEnabled(String provider) {}
            @Override public void onProviderDisabled(String provider) {}
        };

        try {
            boolean requested = false;
            // Usar requestLocationUpdates en lugar del deprecado requestSingleUpdate
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, holder[0], Looper.getMainLooper());
                requested = true;
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, holder[0], Looper.getMainLooper());
                requested = true;
            }

            if (!requested) {
                if (done.compareAndSet(false, true)) {
                    if (fallback != null) {
                        sendLoc(ctx, sender, fallback, true);
                    } else {
                        send(ctx, sender, "La Llave: la ubicacion (GPS) esta apagada en el telefono.");
                    }
                    pr.finish();
                }
                return;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error solicitando updates al LocationManager", e);
            if (done.compareAndSet(false, true)) {
                finishListener.run();
                if (fallback != null) {
                    sendLoc(ctx, sender, fallback, true);
                } else {
                    send(ctx, sender, "La Llave: error al activar los sensores GPS.");
                }
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
            if (ctx.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "No se puede responder: Falta permiso SEND_SMS.");
                return;
            }

            SmsManager sm = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                sm = ctx.getSystemService(SmsManager.class);
            }
            if (sm == null) {
                sm = SmsManager.getDefault();
            }

            if (sm == null) {
                Log.e(TAG, "SmsManager no esta disponible en este dispositivo.");
                return;
            }

            ArrayList<String> parts = sm.divideMessage(text);
            if (parts.size() > 1) {
                sm.sendMultipartTextMessage(to, null, parts, null, null);
            } else {
                sm.sendTextMessage(to, null, text, null, null);
            }
            Log.d(TAG, "SMS enviado con exito a: " + to);
        } catch (Exception e) {
            Log.e(TAG, "Fallo al enviar SMS (verifique saldo o permisos): " + e.getMessage(), e);
        }
    }
}
