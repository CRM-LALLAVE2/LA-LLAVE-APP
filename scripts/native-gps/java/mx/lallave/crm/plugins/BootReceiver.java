package mx.lallave.crm.plugins;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.core.content.ContextCompat;

/**
 * Al encender el telefono (o actualizar la app) vuelve a iniciar el rastreo si el chofer lo habia activado
 * (prefs "wantRun"). Si el chofer toco "Detener", NO se reinicia.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String a = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(a)) return;
        SharedPreferences p = ctx.getSharedPreferences(GpsUploaderPlugin.PREFS, Context.MODE_PRIVATE);
        boolean cfg = !p.getString("supabaseUrl", "").isEmpty() && !p.getString("vehId", "").isEmpty()
                && !p.getString("token", "").isEmpty() && !p.getString("rowId", "").isEmpty();
        if (!p.getBoolean("wantRun", false) || !cfg) return;
        try {
            p.edit().putBoolean("running", true).apply();
            ContextCompat.startForegroundService(ctx, new Intent(ctx, GpsUploadService.class));
            Log.i(TAG, "rastreo reiniciado tras " + a);
        } catch (Exception e) {
            p.edit().putBoolean("running", false)
                    .putString("lastError", "No pudo iniciar al encender: " + e.getClass().getSimpleName()).apply();
            Log.w(TAG, "no se pudo reiniciar: " + e.getMessage());
        }
    }
}
