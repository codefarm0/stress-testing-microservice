package in.codefarm.stress_testing_microservice.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Every few seconds, one line that answers "what is the service doing right now?":
 *
 * <pre>
 * [load] 5s | 812.4 req/s ok=4012 rejected=0 failed=50 inflight=210 | latency avg=245ms max=3011ms
 *        | db-pool active=10/10 waiting=190 timeouts+=50 | tomcat busy=200/200 | heap 301/384MB gc=420ms | cpu 99%
 * </pre>
 *
 * Level is INFO when healthy, WARN when there is pressure (pool waiters / saturated threads),
 * ERROR when requests are failing. Silent while idle (heartbeat once a minute).
 */
@Component
public class LoadStatsReporter {

    private static final Logger log = LoggerFactory.getLogger("load-stats");
    private static final long MB = 1024 * 1024;

    private final OrderStats stats;
    private final MeterRegistry registry;
    private final Duration interval;

    private double lastGcMillis = -1;
    private double lastPoolTimeouts = -1;
    private int idleTicks;

    public LoadStatsReporter(OrderStats stats, MeterRegistry registry,
                             @Value("${order.stats.interval:5s}") Duration interval) {
        this.stats = stats;
        this.registry = registry;
        this.interval = interval;
    }

    @Scheduled(fixedRateString = "${order.stats.interval:5s}", initialDelayString = "${order.stats.interval:5s}")
    public void report() {
        OrderStats.Snapshot s = stats.snapshotAndReset();

        double poolActive = gauge("hikaricp.connections.active");
        double poolMax = gauge("hikaricp.connections.max");
        double poolPending = gauge("hikaricp.connections.pending");
        double poolTimeoutsTotal = counter("hikaricp.connections.timeout");
        double tomcatBusy = gauge("tomcat.threads.busy");
        double tomcatMax = gauge("tomcat.threads.config.max");
        double heapUsed = sumGauge("jvm.memory.used", "area", "heap");
        double heapMax = sumGauge("jvm.memory.max", "area", "heap");
        double cpu = gauge("process.cpu.usage");
        double gcMillis = gcPauseMillis();

        double gcDelta = lastGcMillis < 0 ? 0 : gcMillis - lastGcMillis;
        double timeoutsDelta = lastPoolTimeouts < 0 ? 0 : poolTimeoutsTotal - lastPoolTimeouts;
        lastGcMillis = gcMillis;
        lastPoolTimeouts = poolTimeoutsTotal;

        long inFlight = stats.inFlight();
        boolean idle = s.completed() == 0 && inFlight == 0 && poolPending == 0;
        if (idle && ++idleTicks % 12 != 1) {
            return;
        }
        if (!idle) {
            idleTicks = 0;
        }

        double rate = s.completed() / (interval.toMillis() / 1000.0);
        Level level = s.failed() > 0 ? Level.ERROR
                : (poolPending > 0 || (tomcatMax > 0 && tomcatBusy >= tomcatMax)) ? Level.WARN
                : Level.INFO;

        log.atLevel(level)
                .setMessage(String.format(
                        "[load] %ds | %.1f req/s ok=%d rejected=%d failed=%d inflight=%d | latency avg=%dms max=%dms"
                                + " | db-pool active=%.0f/%.0f waiting=%.0f timeouts+=%.0f | tomcat busy=%.0f/%.0f"
                                + " | heap %d/%dMB gc=%.0fms | cpu %.0f%%",
                        interval.toSeconds(), rate, s.confirmed(), s.rejected(), s.failed(), inFlight,
                        s.avgMillis(), s.maxMillis(),
                        poolActive, poolMax, poolPending, timeoutsDelta, tomcatBusy, tomcatMax,
                        (long) (heapUsed / MB), (long) (heapMax / MB), gcDelta, cpu * 100))
                .addKeyValue("throughput", Math.round(rate * 10) / 10.0)
                .addKeyValue("confirmed", s.confirmed())
                .addKeyValue("rejected", s.rejected())
                .addKeyValue("failed", s.failed())
                .addKeyValue("inflight", inFlight)
                .addKeyValue("latencyAvgMs", s.avgMillis())
                .addKeyValue("latencyMaxMs", s.maxMillis())
                .addKeyValue("poolActive", (long) poolActive)
                .addKeyValue("poolPending", (long) poolPending)
                .addKeyValue("tomcatBusy", (long) tomcatBusy)
                .addKeyValue("heapUsedMb", (long) (heapUsed / MB))
                .addKeyValue("cpuPct", Math.round(cpu * 100))
                .log();
    }

    private double gauge(String name) {
        Gauge g = registry.find(name).gauge();
        return g == null ? 0 : g.value();
    }

    private double counter(String name) {
        var c = registry.find(name).counter();
        return c == null ? 0 : c.count();
    }

    private double sumGauge(String name, String tagKey, String tagValue) {
        return registry.find(name).tag(tagKey, tagValue).gauges().stream()
                .mapToDouble(Gauge::value)
                .filter(v -> v >= 0)
                .sum();
    }

    private double gcPauseMillis() {
        return registry.find("jvm.gc.pause").timers().stream()
                .mapToDouble(t -> t.totalTime(TimeUnit.MILLISECONDS))
                .sum();
    }
}
