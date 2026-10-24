package mx.lallave.crm.plugins;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reglas de alertas de velocidad y zonas (misma logica que evalAlerts() de gps.html),
 * sin dependencias de Android para poder probarse en cualquier JVM.
 */
final class AlertEngine {

    static final class Geo {
        String id = "";
        String name = "";
        double lat, lng, radiusM;
        boolean enterNotFalse = true;  // alertEnter !== false
        boolean enterTruthy = false;   // alertEnter verdadero
        boolean exitNotFalse = true;   // alertExit !== false
    }

    static final class Item {
        String kind;            // "speed" | "enter" | "exit"
        double speed, limit;    // solo "speed"
        String geoId = "", geoName = "";
        boolean breach;         // genera evento GEOFENCE_BREACH
        String direction = "";  // "enter" | "exit"
    }

    static final class Result {
        long lastSpeedAlert;
        final List<Item> items = new ArrayList<Item>();
    }

    static double distM(double lat1, double lng1, double lat2, double lng2) {
        double R = 6371000d;
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = Math.toRadians(lat2 - lat1), dl = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * R * Math.asin(Math.min(1d, Math.sqrt(a)));
    }

    /** `zones` se modifica (estado dentro/fuera por zona). */
    static Result eval(double lat, double lng, double speedKmh, long ts,
                       double limit, boolean speedEnabled, long coolMs,
                       List<Geo> geos, Map<String, Boolean> zones, long lastSpeedAlert) {
        Result r = new Result();
        r.lastSpeedAlert = lastSpeedAlert;
        if (speedEnabled && speedKmh > limit && (ts - lastSpeedAlert) > coolMs) {
            r.lastSpeedAlert = ts;
            Item it = new Item();
            it.kind = "speed";
            it.speed = speedKmh;
            it.limit = limit;
            r.items.add(it);
        }
        if (geos == null) return r;
        for (Geo g : geos) {
            boolean inside = distM(lat, lng, g.lat, g.lng) <= g.radiusM;
            Boolean was = zones.get(g.id);
            zones.put(g.id, inside);
            if (was == null || was.booleanValue() == inside) continue;
            boolean breach = (inside && g.enterTruthy && !g.exitNotFalse) || (!inside && g.exitNotFalse);
            if (inside && g.enterNotFalse) {
                r.items.add(zoneItem("enter", g, breach));
            } else if (!inside && g.exitNotFalse) {
                r.items.add(zoneItem("exit", g, breach));
            } else if (breach) {
                // evento sin alerta visible (igual que el JS: el evento se registra aparte)
                Item it = zoneItem(inside ? "enter" : "exit", g, true);
                it.kind = "breachonly";
                r.items.add(it);
            }
        }
        return r;
    }

    private static Item zoneItem(String kind, Geo g, boolean breach) {
        Item it = new Item();
        it.kind = kind;
        it.direction = kind;
        it.geoId = g.id;
        it.geoName = g.name;
        it.breach = breach;
        return it;
    }
}
