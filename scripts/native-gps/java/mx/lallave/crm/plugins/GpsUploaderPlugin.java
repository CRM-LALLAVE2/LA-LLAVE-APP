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

/**
 * Arranca/detiene el servicio nativo que sube GPS a Supabase
 * sin depender del WebView (funciona con pantalla bloqueada).
 */
@CapacitorPlugin(name = "GpsUploader")
public class GpsUploaderPlugin extends Plugin {

    public static final String PREFS = "llave_gps_uploader";

    @PluginMethod
    public void start(PluginCall call) {
        String supabaseUrl = call.getString("supabaseUrl", "");
        String anonKey = call.getString("anonKey", "");
        String rowId = call.getString("rowId", "");
        String vehId = call.getString("vehId", "");
        String token = call.getString("token", "");
        String alias = call.getString("alias", "");
        String placas = call.getString("placas", "");
        String driver = call.getString("driver", "");
        boolean passenger = call.getBoolean("passenger", true);
        String tripId = call.getString("tripId", "");
        int intervalSec = call.getInt("intervalSec", 15);

        if (supabaseUrl.isEmpty() || anonKey.isEmpty() || rowId.isEmpty() || vehId.isEmpty()) {
            call.reject("Faltan supabaseUrl, anonKey, rowId o vehId");
            return;
        }

        Context ctx = getContext();
        SharedPreferences.Editor ed = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        ed.putString("supabaseUrl", supabaseUrl.replaceAll("/$", ""));
        ed.putString("anonKey", anonKey);
        ed.putString("rowId", rowId);
        ed.putString("vehId", vehId);
        ed.putString("token", token);
        ed.putString("alias", alias);
        ed.putString("placas", placas);
        ed.putString("driver", driver);
        ed.putBoolean("passenger", passenger);
        ed.putString("tripId", tripId);
        ed.putInt("intervalSec", Math.max(10, Math.min(60, intervalSec)));
        ed.putBoolean("running", true);
        ed.apply();

        Intent i = new Intent(ctx, GpsUploadService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }

        JSObject ret = new JSObject();
        ret.put("ok", true);
        call.resolve(ret);
    }

    @PluginMethod
    public void stop(PluginCall call) {
        Context ctx = getContext();
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("running", false).apply();
        ctx.stopService(new Intent(ctx, GpsUploadService.class));
        JSObject ret = new JSObject();
        ret.put("ok", true);
        call.resolve(ret);
    }

    @PluginMethod
    public void isRunning(PluginCall call) {
        boolean running = getContext()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("running", false);
        JSObject ret = new JSObject();
        ret.put("running", running);
        call.resolve(ret);
    }
}
