package mx.lallave.crm.plugins;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.core.app.ActivityCompat;
import org.json.JSONArray;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "GpsUploader")
public class GpsUploaderPlugin extends Plugin {

    public static final String PREFS = "llave_gps_uploader";

    private boolean smsGranted() {
        Context c = getContext();
        return c.checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
                && c.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED;
    }

    private void askSms() {
        try {
            if (!smsGranted() && getActivity() != null) {
                ActivityCompat.requestPermissions(getActivity(),
                        new String[]{Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS}, 7311);
            }
        } catch (Exception ignored) {}
    }

    /** Pide permiso de SMS (respaldo "LA LLAVE"). */
    @PluginMethod
    public void requestSmsPermission(PluginCall call) {
        askSms();
        JSObject r = new JSObject();
        r.put("granted", smsGranted());
        call.resolve(r);
    }

    /** Configura palabra secreta y numeros autorizados: {keyword?, numbers?: []} */
    @PluginMethod
    public void setSmsConfig(PluginCall call) {
        SharedPreferences.Editor e = getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        String kw = call.getString("keyword");
        if (kw != null && !kw.trim().isEmpty()) e.putString("smsKeyword", kw.trim());
        try {
            JSONArray a = call.getArray("numbers");
            if (a != null) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < a.length(); i++) { if (i > 0) sb.append(','); sb.append(a.getString(i)); }
                e.putString("smsAllowed", sb.toString());
            }
        } catch (Exception ignored) {}
        e.apply();
        JSObject r = new JSObject(); r.put("ok", true); r.put("granted", smsGranted());
        call.resolve(r);
    }

    @PluginMethod
    public void start(PluginCall call) {
        try {
            SharedPreferences sp0 = getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (!sp0.getBoolean("smsAsked", false)) { sp0.edit().putBoolean("smsAsked", true).apply(); askSms(); }
            String supabaseUrl = call.getString("supabaseUrl", "");
            String anonKey = call.getString("anonKey", "");
            String rowId = call.getString("rowId", "");
            String vehId = call.getString("vehId", "");
            String token = call.getString("token", "");
            String alias = call.getString("alias", "");
            String placas = call.getString("placas", "");
            String driver = call.getString("driver", "");
            String tripId = call.getString("tripId", "");
            String notifyUrl = call.getString("notifyUrl", "");
            boolean passenger = call.getBoolean("passenger", true);
            int intervalSec = call.getInt("intervalSec", 15);
            if (intervalSec < 10) intervalSec = 10;

            if (supabaseUrl == null || supabaseUrl.isEmpty()
                    || anonKey == null || anonKey.isEmpty()
                    || rowId == null || rowId.isEmpty()
                    || vehId == null || vehId.isEmpty()
                    || token == null || token.trim().isEmpty()) {
                call.reject("Faltan supabaseUrl, anonKey, rowId, vehId o token GPS del QR");
                return;
            }

            SharedPreferences.Editor ed = getContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit();
            SharedPreferences old = getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String prevTrip = old.getString("alertTrip", "");
            String newTrip = tripId != null ? tripId : "";
            if (!newTrip.equals(prevTrip)) {
                // viaje nuevo: se reinicia el estado de zonas y el enfriamiento de alertas de velocidad
                ed.remove("zones");
                ed.remove("lastSpeedAlert");
                ed.putString("alertTrip", newTrip);
            }
            ed.putString("notifyUrl", notifyUrl != null ? notifyUrl : "");
            ed.putBoolean("running", true);
            ed.putBoolean("wantRun", true);
            ed.putBoolean("auto", call.getBoolean("auto", true));
            ed.putString("supabaseUrl", supabaseUrl);
            ed.putString("anonKey", anonKey);
            ed.putString("rowId", rowId);
            ed.putString("vehId", vehId);
            ed.putString("token", token != null ? token : "");
            ed.putString("alias", alias != null ? alias : "");
            ed.putString("placas", placas != null ? placas : "");
            ed.putString("driver", driver != null ? driver : "");
            ed.putString("tripId", tripId != null ? tripId : "");
            ed.putBoolean("passenger", passenger);
            ed.putInt("intervalSec", intervalSec);
            ed.putString("lastError", "");
            ed.apply();

            Intent i = new Intent(getContext(), GpsUploadService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getContext().startForegroundService(i);
            } else {
                getContext().startService(i);
            }

            JSObject ret = new JSObject();
            ret.put("ok", true);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("No se pudo iniciar GpsUploader: " + e.getMessage(), e);
        }
    }

    @PluginMethod
    public void stop(PluginCall call) {
        try {
            getContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("running", false)
                    .putBoolean("wantRun", false)
                    .apply();

            Intent i = new Intent(getContext(), GpsUploadService.class);
            getContext().stopService(i);

            JSObject ret = new JSObject();
            ret.put("ok", true);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("No se pudo detener GpsUploader: " + e.getMessage(), e);
        }
    }

    /**
     * SOS: manda un SMS a los contactos de emergencia definidos en el CRM (funciona SIN internet).
     * Parametros: {lat?, lng?, accuracy?, driver?, vehicle?}
     */
    @PluginMethod
    public void sendSosSms(PluginCall call) {
        JSObject r = new JSObject();
        try {
            SharedPreferences sp = getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONArray contacts = call.getArray("contacts");
            if (contacts == null || contacts.length() == 0) contacts = new JSONArray(sp.getString("sosContacts", "[]"));
            boolean cancel = call.getBoolean("cancel", false);
            String alias = call.getString("vehicle", sp.getString("alias", ""));
            String driver = call.getString("driver", sp.getString("driver", ""));
            Double lat = call.getDouble("lat");
            Double lng = call.getDouble("lng");
            StringBuilder sb = new StringBuilder(cancel ? "SOS CANCELADO - La Llave\n" : "EMERGENCIA SOS - La Llave\n");
            sb.append(driver == null || driver.isEmpty() ? "El chofer" : driver);
            if (alias != null && !alias.isEmpty()) sb.append(" (").append(alias).append(")");
            sb.append(cancel ? " aviso que fue una falsa alarma. No hay emergencia." : " pidio ayuda.");
            if (cancel) {
                // sin ubicacion ni llamada urgente
            } else if (lat != null && lng != null && !(lat == 0 && lng == 0)) {
                sb.append("\nUbicacion: https://maps.google.com/?q=").append(lat).append(",").append(lng);
            } else {
                sb.append("\nUbicacion no disponible.");
            }
            if (!cancel) sb.append("\nLlamar de inmediato.");
            int sent = 0, total = 0;
            for (int i = 0; i < contacts.length(); i++) {
                String ph = contacts.optJSONObject(i) != null ? contacts.optJSONObject(i).optString("phone", "") : contacts.optString(i, "");
                ph = ph.replaceAll("[^+0-9]", "");
                if (ph.length() < 7) continue;
                total++;
                if (sendSmsRaw(ph, sb.toString())) sent++;
            }
            r.put("sent", sent);
            r.put("total", total);
            r.put("smsGranted", smsGranted());
            call.resolve(r);
        } catch (Exception e) {
            call.reject("sendSosSms: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("deprecation")
    private boolean sendSmsRaw(String to, String text) {
        try {
            if (!smsGranted()) return false;
            android.telephony.SmsManager sm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? getContext().getSystemService(android.telephony.SmsManager.class) : null;
            if (sm == null) sm = android.telephony.SmsManager.getDefault();
            if (sm == null) return false;
            java.util.ArrayList<String> parts = sm.divideMessage(text);
            if (parts.size() > 1) sm.sendMultipartTextMessage(to, null, parts, null, null);
            else sm.sendTextMessage(to, null, text, null, null);
            return true;
        } catch (Exception e) { return false; }
    }

    /** Estado de la configuracion del telefono para el asistente de la app. */
    @PluginMethod
    public void getSetupStatus(PluginCall call) {
        JSObject r = new JSObject();
        Context c = getContext();
        r.put("fine", c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED);
        r.put("background", Build.VERSION.SDK_INT < 29
                || c.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED);
        r.put("notifications", Build.VERSION.SDK_INT < 33
                || c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED);
        r.put("sms", smsGranted());
        boolean batt = true;
        try {
            android.os.PowerManager pm = (android.os.PowerManager) c.getSystemService(Context.POWER_SERVICE);
            if (pm != null && Build.VERSION.SDK_INT >= 23) batt = pm.isIgnoringBatteryOptimizations(c.getPackageName());
        } catch (Exception ignored) {}
        r.put("battery", batt);
        boolean loc = true;
        try {
            android.location.LocationManager lm = (android.location.LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            if (lm != null) loc = Build.VERSION.SDK_INT >= 28 ? lm.isLocationEnabled()
                    : lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER);
        } catch (Exception ignored) {}
        r.put("locationOn", loc);
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        r.put("auto", sp.getBoolean("auto", false));
        r.put("wantRun", sp.getBoolean("wantRun", false));
        call.resolve(r);
    }

    /** Abre la pantalla de Ajustes correcta o pide el permiso: what = location | background | notifications | sms | battery | gps */
    @PluginMethod
    public void fixSetup(PluginCall call) {
        String what = call.getString("what", "");
        Context c = getContext();
        try {
            if ("location".equals(what) && getActivity() != null) {
                ActivityCompat.requestPermissions(getActivity(),
                        new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 7312);
            } else if ("notifications".equals(what) && Build.VERSION.SDK_INT >= 33 && getActivity() != null) {
                ActivityCompat.requestPermissions(getActivity(), new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7313);
            } else if ("sms".equals(what)) {
                askSms();
            } else if ("battery".equals(what) && Build.VERSION.SDK_INT >= 23) {
                Intent i = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:" + c.getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { c.startActivity(i); } catch (Exception e) {
                    Intent j = new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                    j.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); c.startActivity(j);
                }
            } else if ("gps".equals(what)) {
                Intent i = new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); c.startActivity(i);
            } else { // background / cualquier otro: ajustes de la app (ahi se elige "Permitir todo el tiempo")
                Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:" + c.getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); c.startActivity(i);
            }
            JSObject r = new JSObject(); r.put("ok", true); call.resolve(r);
        } catch (Exception e) {
            call.reject("fixSetup: " + e.getMessage(), e);
        }
    }

    @PluginMethod
    public void getStatus(PluginCall call) {
        SharedPreferences p = getContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSObject ret = new JSObject();
        ret.put("running", p.getBoolean("running", false));
        ret.put("lastUploadOk", p.getLong("lastUploadOk", 0));
        ret.put("pending", p.getInt("pending", 0));
        ret.put("lastError", p.getString("lastError", ""));
        ret.put("smsGranted", smsGranted());
        call.resolve(ret);
    }
}
