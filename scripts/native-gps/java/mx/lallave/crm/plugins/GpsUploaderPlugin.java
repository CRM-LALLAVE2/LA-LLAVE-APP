package mx.lallave.crm.plugins;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "GpsUploader")
public class GpsUploaderPlugin extends Plugin {

    public static final String PREFS = "llave_gps_uploader";

    @PluginMethod
    public void start(PluginCall call) {
        try {
            String supabaseUrl = call.getString("supabaseUrl", "");
            String anonKey = call.getString("anonKey", "");
            String rowId = call.getString("rowId", "");
            String vehId = call.getString("vehId", "");
            String token = call.getString("token", "");
            String alias = call.getString("alias", "");
            String placas = call.getString("placas", "");
            String driver = call.getString("driver", "");
            String tripId = call.getString("tripId", "");
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
            ed.putBoolean("running", true);
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

    @PluginMethod
    public void getStatus(PluginCall call) {
        SharedPreferences p = getContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSObject ret = new JSObject();
        ret.put("running", p.getBoolean("running", false));
        ret.put("lastUploadOk", p.getLong("lastUploadOk", 0));
        ret.put("pending", p.getInt("pending", 0));
        ret.put("lastError", p.getString("lastError", ""));
        call.resolve(ret);
    }
}
