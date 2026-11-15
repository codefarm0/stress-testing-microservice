package in.codefarm.stress_testing_microservice.observability;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Logs the capacity envelope once at startup, so every experiment's logs begin with
 * "this is what the service had to work with".
 */
@Component
public class StartupReport {

    private static final Logger log = LoggerFactory.getLogger("startup");
    private static final long MB = 1024 * 1024;

    private final DataSource dataSource;
    private final Environment env;

    public StartupReport(DataSource dataSource, Environment env) {
        this.dataSource = dataSource;
        this.env = env;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void report() {
        Runtime rt = Runtime.getRuntime();
        int poolSize = dataSource instanceof HikariDataSource h ? h.getMaximumPoolSize() : -1;
        long poolTimeout = dataSource instanceof HikariDataSource h ? h.getConnectionTimeout() : -1;

        log.atInfo()
                .setMessage(String.format(
                        "Capacity envelope: cpus=%d (cgroup quota=%s) | container memory limit=%s | max heap=%dMB"
                                + " | tomcat max threads=%s accept-count=%s | db pool size=%d timeout=%dms | jvm=%s gc=%s",
                        rt.availableProcessors(), cgroupCpu(), cgroupMemory(), rt.maxMemory() / MB,
                        env.getProperty("server.tomcat.threads.max"), env.getProperty("server.tomcat.accept-count"),
                        poolSize, poolTimeout, Runtime.version(), gcNames()))
                .addKeyValue("cpus", rt.availableProcessors())
                .addKeyValue("maxHeapMb", rt.maxMemory() / MB)
                .addKeyValue("dbPoolSize", poolSize)
                .log();
    }

    private static String cgroupCpu() {
        return read("/sys/fs/cgroup/cpu.max").map(v -> {
            String[] p = v.trim().split("\\s+");
            if (p.length == 2 && !"max".equals(p[0])) {
                return String.format("%.2f", Double.parseDouble(p[0]) / Double.parseDouble(p[1]));
            }
            return "unlimited";
        }).orElse("n/a");
    }

    private static String cgroupMemory() {
        return read("/sys/fs/cgroup/memory.max").map(v -> {
            String t = v.trim();
            return "max".equals(t) ? "unlimited" : (Long.parseLong(t) / MB) + "MB";
        }).orElse("n/a");
    }

    private static java.util.Optional<String> read(String file) {
        try {
            Path p = Path.of(file);
            return Files.isReadable(p) ? java.util.Optional.of(Files.readString(p)) : java.util.Optional.empty();
        } catch (Exception e) {
            return java.util.Optional.empty();
        }
    }

    private static String gcNames() {
        return String.join(",", ManagementFactory.getGarbageCollectorMXBeans().stream()
                .map(java.lang.management.GarbageCollectorMXBean::getName).toList());
    }
}
