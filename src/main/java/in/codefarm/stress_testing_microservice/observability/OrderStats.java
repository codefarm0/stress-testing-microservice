package in.codefarm.stress_testing_microservice.observability;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Cheap in-memory counters for the periodic stats log line.
 * (Prometheus has the real time series; this exists so "docker logs" alone tells the story.)
 */
@Component
public class OrderStats {

    private final LongAdder confirmed = new LongAdder();
    private final LongAdder rejected = new LongAdder();
    private final LongAdder failed = new LongAdder();
    private final LongAdder totalNanos = new LongAdder();
    private final AtomicLong maxNanos = new AtomicLong();
    private final LongAdder inFlight = new LongAdder();

    public void started() {
        inFlight.increment();
    }

    public void confirmed(long nanos) {
        confirmed.increment();
        finished(nanos);
    }

    public void rejected(long nanos) {
        rejected.increment();
        finished(nanos);
    }

    public void failed(long nanos) {
        failed.increment();
        finished(nanos);
    }

    private void finished(long nanos) {
        inFlight.decrement();
        totalNanos.add(nanos);
        maxNanos.accumulateAndGet(nanos, Math::max);
    }

    public long inFlight() {
        return inFlight.sum();
    }

    /** Returns the counts since the previous snapshot and resets them. */
    public Snapshot snapshotAndReset() {
        long c = confirmed.sumThenReset();
        long r = rejected.sumThenReset();
        long f = failed.sumThenReset();
        long total = totalNanos.sumThenReset();
        long max = maxNanos.getAndSet(0);
        return new Snapshot(c, r, f, total, max);
    }

    public record Snapshot(long confirmed, long rejected, long failed, long totalNanos, long maxNanos) {

        public long completed() {
            return confirmed + rejected + failed;
        }

        public long avgMillis() {
            return completed() == 0 ? 0 : totalNanos / completed() / 1_000_000;
        }

        public long maxMillis() {
            return maxNanos / 1_000_000;
        }
    }
}
