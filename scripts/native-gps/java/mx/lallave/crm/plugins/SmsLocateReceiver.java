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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Respaldo por SMS en el telefono del chofer.
 *
 * Comandos reconocidos (cuerpo del SMS, sin distinguir mayusculas):
 *   LA LLAVE       — responde con ubicacion (palabra secreta configurable en prefs smsKeyword)
 *   ESTADO GPS     — permisos, GPS del sistema, ultimo fix y ultima subida al servidor
 *   REACTIVAR GPS  — revisa permisos/GPS, reinicia GpsUploadService si el viaje esta activo, reporta resultado
 *
 * - Opcional: lista de numeros autorizados (prefs "smsAllowed", separados por coma). Vacia = acepta cualquiera.
 * - Maximo 1 respuesta cada 30 s por remitente.
 */
public class SmsLocateReceiver extends BroadcastReceiver {

    private static final String TAG = "SmsLocateReceiver";
    private static final String PREFS = GpsUploaderPlugin.PREFS;
    private static final String DEFAULT_KEYWORD = "LA LLAVE";
    private static final long FIX_TIMEOUT_MS = 20000;
    private static final long MIN_GAP_MS = 30000;

    private enum Cmd { LOCATE, STATUS, REACTIVATE }

    static String norm(String s) {
        if (s == null) return "";
        return s.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
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

    /** Detecta comando. ESTADO/REACTIVAR tienen prioridad sobre la palabra secreta. */
    static Cmd parseCmd(String body, String keyword) {
        String t = norm(body);
        if (t.isEmpty()) return null;
        // Acepta con o sin espacios extra, y variantes comunes
        if (t.equals("ESTADO GPS") || t.equals("ESTADO") || t.startsWith("ESTADO GPS"))
            return Cmd.STATUS;
        if (t.equals("REACTIVAR GPS") || t.equals("REACTIVAR") || t.startsWith("REACTIVAR GPS"))
            return Cmd.REACTIVATE;
        String kw = norm(keyword);
        if (kw.isEmpty()) kw = DEFAULT_KEYWORD;
        if (t.equals(kw) || t.contains(kw))
            return Cmd.LOCATE;
        return null;
    }

    @Override
    public void onReceive(final Context context, Intent intent) {
        if (intent == null || !Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) return;

        SmsMessage[] msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        if (msgs == null || msgs.length == 0) return;

        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String keyword = prefs.getString("smsKeyword", DEFAULT_KEYWORD);
        String allowed = prefs.getString("smsAllowed", "");

        String sender = null;
        StringBuilder body = new StringBuilder();
        for (SmsMessage m : msgs) {
            if (m == null) continue;
            if (sender == null) sender = m.getDisplayOriginatingAddress();
            if (m.getDisplayMessageBody() != null) body.append(m.getDisplayMessageBody());
        }
        if (sender == null) return;

        Cmd cmd = parseCmd(body.toString(), keyword);
        if (cmd == null) {
            Log.d(TAG, "SMS ignorado (no es comando). Recibido: '" + norm(body.toString()) + "'");
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
        final Context app = context.getApplicationContext();
        final String to = sender;

        if (cmd == Cmd.STATUS) {
            handleStatus(app, to, pr);
        } else if (cmd == Cmd.REACTIVATE) {
            handleReactivate(app, to, pr);
        } else {
            replyLocate(app, to, pr);
        }
    }

    private boolean hasLocPermission(Context ctx) {
        return ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean locationServicesOn(LocationManager lm) {
        if (lm == null) return false;
        try {
            return lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception e) {
            return false;
        }
    }

    private String ago(long ts) {
        if (ts <= 0) return "nunca";
        long s = Math.max(0, (System.currentTimeMillis() - ts) / 1000);
        if (s < 60) return "hace " + s + " s";
        long m = s / 60;
        if (m < 60) return "hace " + m + " min";
        long h = m / 60;
        if (h < 48) return "hace " + h + " h";
        try {
            return new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(new Date(ts));
        } catch (Exception e) {
            return "hace " + h + " h";
        }
    }

    @SuppressLint("MissingPermission")
    private Location lastKnown(Context ctx, LocationManager lm) {
        if (lm == null || !hasLocPermission(ctx)) return null;
        Location best = null;
        try {
            for (String p : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(p);
                if (l == null) continue;
                if (best == null || l.getTime() > best.getTime()) best = l;
            }
        } catch (SecurityException e) {
            Log.e(TAG, "lastKnown", e);
        }
        return best;
    }

    private void handleStatus(final Context ctx, final String sender, final PendingResult pr) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        boolean perm = hasLocPermission(ctx);
        boolean locOn = locationServicesOn(lm);
        boolean running = p.getBoolean("running", false);
        long lastOk = p.getLong("lastUploadOk", 0);
        int pending = p.getInt("pending", 0);
        String lastErr = p.getString("lastError", "");
        Location fix = lastKnown(ctx, lm);

        StringBuilder sb = new StringBuilder();
        sb.append("La Llave ESTADO GPS\n");
        sb.append("Ubicacion sistema: ").append(locOn ? "activada" : "apagada").append('\n');
        sb.append("Permiso app: ").append(perm ? "concedido" : "denegado").append('\n');
        sb.append("Servicio tracking: ").append(running ? "activo" : "detenido").append('\n');
        if (fix != null) {
            sb.append("Ultimo fix: ").append(ago(fix.getTime()));
            if (fix.hasAccuracy()) sb.append(" ±").append((int) fix.getAccuracy()).append('m');
            sb.append('\n');
            sb.append("https://maps.google.com/?q=").append(fix.getLatitude()).append(',').append(fix.getLongitude()).append('\n');
        } else {
            sb.append("Ultimo fix: no disponible\n");
        }
        sb.append("Ultima subida: ").append(ago(lastOk));
        if (pending > 0) sb.append(" · pendientes: ").append(pending);
        if (lastErr != null && !lastErr.isEmpty()) {
            sb.append('\n').append("Ultimo error: ").append(lastErr.length() > 80 ? lastErr.substring(0, 80) : lastErr);
        }
        send(ctx, sender, sb.toString().trim());
        pr.finish();
    }

    private void handleReactivate(final Context ctx, final String sender, final PendingResult pr) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        boolean perm = hasLocPermission(ctx);
        boolean locOn = locationServicesOn(lm);
        boolean wasRunning = p.getBoolean("running", false);
        ArrayList<String> acts = new ArrayList<String>();

        if (!perm) {
            send(ctx, sender,
                    "La Llave REACTIVAR GPS\n"
                            + "Permiso de ubicacion: denegado.\n"
                            + "El chofer debe ir a Ajustes > Apps > La Llave > Ubicacion > Permitir todo el tiempo.");
            pr.finish();
            return;
        }
        if (!locOn) {
            send(ctx, sender,
                    "La Llave REACTIVAR GPS\n"
                            + "Ubicacion del telefono: apagada.\n"
                            + "Enciende el GPS / ubicacion en Ajustes del telefono e intenta de nuevo.");
            pr.finish();
            return;
        }

        // Reiniciar servicio de tracking si hay configuracion de viaje (running o token guardado)
        boolean hasConfig = wasRunning
                || !p.getString("token", "").isEmpty()
                || !p.getString("vehId", "").isEmpty();
        if (hasConfig) {
            try {
                p.edit().putBoolean("running", true).putString("lastError", "").apply();
                Intent stop = new Intent(ctx, GpsUploadService.class);
                ctx.stopService(stop);
                Intent start = new Intent(ctx, GpsUploadService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(start);
                } else {
                    ctx.startService(start);
                }
                acts.add(wasRunning ? "servicio reiniciado" : "servicio iniciado");
            } catch (Exception e) {
                acts.add("servicio: " + (e.getMessage() != null ? e.getMessage() : "error"));
                Log.e(TAG, "reactivate service", e);
            }
        } else {
            acts.add("sin viaje activo (abre el QR e inicia viaje en la app)");
        }

        // Intentar un fix fresco y responder
        final String actionsTxt = join(acts, "; ");
        final AtomicBoolean done = new AtomicBoolean(false);
        final Handler handler = new Handler(Looper.getMainLooper());
        final Location fallback = lastKnown(ctx, lm);

        final LocationListener[] holder = new LocationListener[1];
        final Runnable finishListener = new Runnable() {
            @Override public void run() {
                try {
                    if (holder[0] != null && lm != null) lm.removeUpdates(holder[0]);
                } catch (Exception ignored) {}
            }
        };

        final Runnable finishWith = new Runnable() {
            @Override public void run() { /* filled below via reply */ }
        };

        final Runnable replyAndFinish = new Runnable() {
            Location loc;
            boolean stale;
            @Override public void run() {
                StringBuilder sb = new StringBuilder();
                sb.append("La Llave REACTIVAR GPS\n");
                sb.append("Permiso: ok · Ubicacion: activada\n");
                sb.append("Acciones: ").append(actionsTxt).append('\n');
                if (loc != null) {
                    sb.append(stale ? "Fix (ultima conocida)" : "Fix fresco");
                    sb.append(": ").append(ago(loc.getTime()));
                    if (loc.hasAccuracy()) sb.append(" ±").append((int) loc.getAccuracy()).append('m');
                    sb.append('\n');
                    sb.append("https://maps.google.com/?q=").append(loc.getLatitude()).append(',').append(loc.getLongitude());
                } else {
                    sb.append("Sin fix GPS (posible techo/tunel). Servicio revisado.");
                }
                send(ctx, sender, sb.toString().trim());
                pr.finish();
            }
        };

        // Use a small helper class-like approach with final arrays
        final Location[] locBox = new Location[1];
        final boolean[] staleBox = new boolean[1];

        final Runnable doReply = new Runnable() {
            @Override public void run() {
                StringBuilder sb = new StringBuilder();
                sb.append("La Llave REACTIVAR GPS\n");
                sb.append("Permiso: ok · Ubicacion: activada\n");
                sb.append("Acciones: ").append(actionsTxt).append('\n');
                Location loc = locBox[0];
                if (loc != null) {
                    sb.append(staleBox[0] ? "Fix (ultima conocida)" : "Fix fresco");
                    sb.append(": ").append(ago(loc.getTime()));
                    if (loc.hasAccuracy()) sb.append(" ±").append((int) loc.getAccuracy()).append('m');
                    sb.append('\n');
                    sb.append("https://maps.google.com/?q=").append(loc.getLatitude()).append(',').append(loc.getLongitude());
                } else {
                    sb.append("Sin fix GPS (posible techo/tunel). Servicio revisado.");
                }
                send(ctx, sender, sb.toString().trim());
                pr.finish();
            }
        };

        final Runnable timeout = new Runnable() {
            @Override public void run() {
                if (!done.compareAndSet(false, true)) return;
                finishListener.run();
                locBox[0] = fallback;
                staleBox[0] = true;
                doReply.run();
            }
        };

        holder[0] = new LocationListener() {
            @Override public void onLocationChanged(Location location) {
                if (!done.compareAndSet(false, true)) return;
                handler.removeCallbacks(timeout);
                finishListener.run();
                locBox[0] = location;
                staleBox[0] = false;
                doReply.run();
            }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
            @Override public void onProviderEnabled(String provider) {}
            @Override public void onProviderDisabled(String provider) {}
        };

        try {
            boolean requested = false;
            if (lm != null && lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, holder[0], Looper.getMainLooper());
                requested = true;
            }
            if (lm != null && lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, holder[0], Looper.getMainLooper());
                requested = true;
            }
            if (!requested) {
                if (done.compareAndSet(false, true)) {
                    locBox[0] = fallback;
                    staleBox[0] = true;
                    doReply.run();
                }
                return;
            }
        } catch (SecurityException e) {
            if (done.compareAndSet(false, true)) {
                send(ctx, sender, "La Llave REACTIVAR GPS\nSin permiso de ubicacion al leer sensores.");
                pr.finish();
            }
            return;
        } catch (Exception e) {
            Log.e(TAG, "reactivate updates", e);
            if (done.compareAndSet(false, true)) {
                locBox[0] = fallback;
                staleBox[0] = true;
                doReply.run();
            }
            return;
        }
        handler.postDelayed(timeout, FIX_TIMEOUT_MS);
    }

    @SuppressLint("MissingPermission")
    private void replyLocate(final Context ctx, final String sender, final PendingResult pr) {
        final AtomicBoolean done = new AtomicBoolean(false);
        final Handler handler = new Handler(Looper.getMainLooper());
        final LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);

        if (lm == null || !hasLocPermission(ctx)) {
            Log.e(TAG, "Sin permisos de ubicacion otorgados.");
            send(ctx, sender, "La Llave: el telefono no tiene permiso de ubicacion otorgado.");
            pr.finish();
            return;
        }

        final Location fallback = lastKnown(ctx, lm);

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

    private static String join(ArrayList<String> parts, String sep) {
        if (parts == null || parts.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(parts.get(i));
        }
        return sb.toString();
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
