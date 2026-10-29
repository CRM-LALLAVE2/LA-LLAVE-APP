package mx.lallave.crm.plugins;

/** Logica pura de viajes automaticos (sin Android) para poder probarla en una JVM normal. */
final class AutoTrip {
    static final double MOVE_KMH = 10.0;          // arriba de esto se considera "en movimiento"
    static final int HITS_TO_START = 2;           // lecturas seguidas en movimiento para iniciar viaje
    static final long IDLE_END_MS = 10 * 60 * 1000L;   // parado este tiempo = fin del viaje
    static final long IDLE_UPLOAD_MS = 2 * 60 * 1000L; // sin viaje: una posicion cada 2 min
    static final int NONE = 0, START = 1, END = 2;

    private AutoTrip() {}

    static boolean isMoving(double speedKmh) { return speedKmh >= MOVE_KMH; }

    static int nextHits(int hits, double speedKmh) { return isMoving(speedKmh) ? hits + 1 : 0; }

    static int action(boolean hasTrip, int hits, long ts, long lastMoveTs) {
        if (!hasTrip && hits >= HITS_TO_START) return START;
        if (hasTrip && lastMoveTs > 0 && ts - lastMoveTs >= IDLE_END_MS) return END;
        return NONE;
    }
}
