package mx.lallave.crm.plugins;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.telephony.SmsManager;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * Comandos SMS de administrador (ademas de "LA LLAVE", que sigue igual):
 *   ESTADO GPS    -> responde hora del ultimo fix GPS, ultima subida al servidor y si la ubicacion esta encendida.
 *   REACTIVAR GPS -> revisa permisos, ubicacion y servicio; reinicia el rastreo si esta permitido; responde el resultado real.
 * Solo responden a numeros autorizados (prefs "smsAdmins", que vienen del CRM, o "smsAllowed" si se configuro).
 * Android no permite encender la ubicacion ni conceder permisos por SMS: en esos casos se informa tal cual.
 */
final class SmsCommands {

    private static final String TAG = "SmsCommands";
    private static final String PREFS = GpsUploaderPlugin.PREFS;
    private static final long MIN_GAP_MS = 15000;

    private SmsCommands() {}

    /** Devuelve "ESTADO" / "REACTIVAR" o null si el texto no es un comando. */
    static String match(String normalized) {
        if (normalized == null) return null;
        String t = normalized.replace("Á", "A").replace("É", "E").replace("Í", "I")
                .replace("Ó", "O").replace("Ú", "U").replaceAll("[.!]+$", "").trim();
        if (t.equals("ESTADO GPS")) return "ESTADO";
        if (t.equals("REACTIVAR GPS")) return "REACTIVAR";
        return null;
    }

    static boolean authorized(SharedPreferences p, String sender) {
        String a = p.getString("smsAdmins", "");
        String b = p.getString("smsAllowed", "");
        String all = (a == null ? "" : a) + "," + (b == null ? "" : b);
        if (all.replace(",", "").trim().isEmpty()) return false; // sin lista: nadie autorizado
        return SmsLocateReceiver.allowed(all, sender);
    }

    static void handle(final Context ctx, final String cmd, final String sender, final BroadcastReceiver.PendingResult pr) {
        final SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!authorized(p, sender)) {
            Log.w(TAG, "Comando " + cmd + " descartado: remitente no autorizado (" + sender + ")");
            pr.finish();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - p.getLong("smsCmdLast_" + cmd, 0) < MIN_GAP_MS) {
            pr.finish();
            return;
        }
        p.edit().putLong("smsCmdLast_" + cmd, now).apply();

        new Thread(new Runnable() {
            @Override public void run() {
                String text;
                try {
                    text = "ESTADO".equals(cmd) ? estado(ctx, p) : reactivar(ctx, p);
                } catch (Exception e) {
                    Log.e(TAG, "comando fallo", e);
                    text = "La Llave: el comando " + cmd + " fallo en el telefono (" + e.getClass().getSimpleName() + ").";
                }
                sendSms(ctx, sender, text);
                pr.finish();
            }
        }).start();
    }

    // ---------- datos del telefono ----------

    static boolean fineGranted(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean bgGranted(Context c) {
        if (Build.VERSION.SDK_INT < 29) return true;
        return c.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean locationOn(Context c) {
        try {
            LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return false;
            if (Build.VERSION.SDK_INT >= 28) return lm.isLocationEnabled();
            return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception e) { return false; }
    }

    static boolean internetOn(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network n = cm.getActiveNetwork();
            if (n == null) return false;
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) { return false; }
    }

    static int battery(Context c) {
        try {
            Intent bi = c.registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (bi == null) return -1;
            int l = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), s = bi.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            return (l >= 0 && s > 0) ? Math.round(l * 100f / s) : -1;
        } catch (Exception e) { return -1; }
    }

    static String when(long ts) {
        if (ts <= 0) return "sin registro";
        long s = Math.max(0, (System.currentTimeMillis() - ts) / 1000);
        String rel = s < 90 ? ("hace " + s + " s") : s < 5400 ? ("hace " + (s / 60) + " min") : s < 172800 ? ("hace " + (s / 3600) + " h") : ("hace " + (s / 86400) + " d");
        return rel + " (" + new SimpleDateFormat("dd/MM HH:mm", Locale.US).format(new Date(ts)) + ")";
    }

    static String statusBlock(Context c, SharedPreferences p) {
        StringBuilder sb = new StringBuilder();
        sb.append("Ubicacion del telefono: ").append(locationOn(c) ? "ENCENDIDA" : "APAGADA").append("\n");
        String perm = !fineGranted(c) ? "FALTA ubicacion" : (!bgGranted(c) ? "falta 'Siempre'" : "OK");
        sb.append("Permisos: ").append(perm).append("\n");
        boolean want = p.getBoolean("wantRun", false);
        sb.append("Rastreo: ").append(GpsUploadService.RUNNING ? "ACTIVO" : (want ? "DETENIDO (debia estar activo)" : "no iniciado en la app")).append("\n");
        sb.append("Ultimo fix GPS: ").append(when(p.getLong("lastFix", 0))).append("\n");
        sb.append("Ultima subida: ").append(when(p.getLong("lastUploadOk", 0))).append("\n");
        sb.append("Internet: ").append(internetOn(c) ? "SI" : "NO");
        int pend = p.getInt("pending", 0);
        if (pend > 0) sb.append(" | por enviar: ").append(pend);
        int b = battery(c);
        if (b >= 0) sb.append(" | bateria ").append(b).append("%");
        String err = p.getString("lastError", "");
        if (err != null && !err.isEmpty()) sb.append("\nUltimo error: ").append(err.length() > 70 ? err.substring(0, 70) : err);
        return sb.toString();
    }

    static String estado(Context c, SharedPreferences p) {
        String alias = p.getString("alias", "");
        return "La Llave ESTADO GPS" + (alias.isEmpty() ? "" : " - " + alias) + "\n" + statusBlock(c, p);
    }

    // ---------- reactivar ----------

    static String reactivar(Context c, SharedPreferences p) {
        String alias = p.getString("alias", "");
        String head = "La Llave REACTIVAR GPS" + (alias.isEmpty() ? "" : " - " + alias) + "\n";
        String result;

        boolean cfgOk = !p.getString("supabaseUrl", "").isEmpty() && !p.getString("vehId", "").isEmpty()
                && !p.getString("token", "").isEmpty() && !p.getString("rowId", "").isEmpty();
        long fixAge = p.getLong("lastFix", 0) > 0 ? System.currentTimeMillis() - p.getLong("lastFix", 0) : Long.MAX_VALUE;

        if (!fineGranted(c)) {
            result = "NO SE PUDO: la app no tiene permiso de ubicacion. Android no permite concederlo por SMS: el chofer debe abrir la app y aceptar.";
        } else if (!locationOn(c)) {
            result = "NO SE PUDO: la ubicacion (GPS) del telefono esta APAGADA. Android no permite encenderla por SMS: el chofer debe activarla.";
        } else if (!cfgOk || !p.getBoolean("wantRun", false)) {
            result = "NO SE REINICIO: el rastreo no esta iniciado en esta app (o el chofer lo detuvo). Debe abrir la app e iniciar el GPS.";
        } else if (GpsUploadService.RUNNING && fixAge < 120000) {
            result = "No hizo falta: el rastreo ya esta ACTIVO y con GPS reciente.";
        } else {
            boolean was = GpsUploadService.RUNNING;
            p.edit().putBoolean("running", true).apply();
            String err = null;
            try {
                Intent i = new Intent(c, GpsUploadService.class);
                ContextCompat.startForegroundService(c, i);
            } catch (Exception e) {
                err = e.getClass().getSimpleName();
            }
            long t0 = System.currentTimeMillis();
            while (err == null && System.currentTimeMillis() - t0 < 4500 && !GpsUploadService.RUNNING) {
                try { Thread.sleep(300); } catch (InterruptedException ignored) { break; }
            }
            if (err != null || !GpsUploadService.RUNNING) {
                p.edit().putBoolean("running", false).apply();
                result = "NO SE PUDO REINICIAR: Android bloqueo el inicio en segundo plano" + (err == null ? "" : " (" + err + ")")
                        + ". El chofer debe abrir la app.";
            } else {
                result = was ? "REINICIADO: se reinicio la captura de ubicacion; esperar 1-2 min a que llegue un fix."
                        : "REINICIADO: el servicio estaba detenido y se volvio a iniciar; esperar 1-2 min a que llegue un fix.";
            }
        }
        return head + "Resultado: " + result + "\n" + statusBlock(c, p);
    }

    // ---------- envio ----------

    @SuppressWarnings("deprecation")
    static boolean sendSms(Context ctx, String to, String text) {
        try {
            if (ctx.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Falta permiso SEND_SMS");
                return false;
            }
            SmsManager sm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? ctx.getSystemService(SmsManager.class) : null;
            if (sm == null) sm = SmsManager.getDefault();
            if (sm == null) return false;
            ArrayList<String> parts = sm.divideMessage(text);
            if (parts.size() > 1) sm.sendMultipartTextMessage(to, null, parts, null, null);
            else sm.sendTextMessage(to, null, text, null, null);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Fallo al enviar SMS: " + e.getMessage(), e);
            return false;
        }
    }
}
