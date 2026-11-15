package in.codefarm.stress_testing_microservice.order;

/**
 * Where did the time of one reservation go? Captured per request and printed in the order log line.
 *
 * <pre>
 *  start ── tx.begin ──► began ── validate ──► validated ── SELECT..FOR UPDATE ──► locked ── INSERTs ──► persisted ── COMMIT ──► end
 *          (= waiting for a                                   (= waiting for the
 *           pool connection)                                    inventory row lock)
 * </pre>
 * Under overload, "db-conn" explodes first (pool exhausted); with HOT_PRODUCTS, "lock" explodes.
 */
final class ReservationTimings {

    private final long start = System.nanoTime();
    private long began;
    private long validated;
    private long locked;
    private long persisted;
    private long end;

    void began() { began = System.nanoTime(); }
    void validated() { validated = System.nanoTime(); }
    void locked() { locked = System.nanoTime(); }
    void persisted() { persisted = System.nanoTime(); }
    void end() { end = System.nanoTime(); }

    long totalNanos() { return (end == 0 ? System.nanoTime() : end) - start; }
    long totalMs() { return ms(start, end == 0 ? System.nanoTime() : end); }
    long connMs() { return ms(start, began); }
    long validateMs() { return ms(began, validated); }
    long lockMs() { return ms(validated, locked); }
    long insertMs() { return ms(locked, persisted); }
    long commitMs() { return ms(persisted, end); }

    /** The phases that actually happened, e.g. "db-conn 1ms, validate 2ms, lock 0ms" for a rejected order. */
    String breakdown() {
        StringBuilder sb = new StringBuilder();
        append(sb, "db-conn", start, began);
        append(sb, "validate", began, validated);
        append(sb, "lock", validated, locked);
        append(sb, "insert", locked, persisted);
        append(sb, "commit", persisted, end);
        return sb.isEmpty() ? "no db connection" : sb.toString();
    }

    private static void append(StringBuilder sb, String name, long from, long to) {
        if (from == 0 || to == 0) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append(", ");
        }
        sb.append(name).append(' ').append(ms(from, to)).append("ms");
    }

    private static long ms(long from, long to) {
        return from == 0 || to == 0 ? 0 : (to - from) / 1_000_000;
    }
}
