# ---- build ------------------------------------------------------------------
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN ./gradlew --no-daemon -q dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon -q bootJar -x test

# ---- run --------------------------------------------------------------------
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar

# Heap = 75% of the CONTAINER limit (512MB -> ~384MB); start at that size too, so the heap does not keep
#   resizing during a run (a fixed, known envelope = a clean experiment).
# MetaspaceSize=128m: avoids the "Pause Full (Metadata GC Threshold)" while the app is warming up.
# ExitOnOutOfMemoryError: an OOM kills the JVM -> Docker restarts it -> visible as a restart in cAdvisor/Grafana.
# GC log: NOT on stdout (Prometheus already has every pause via jvm_gc_pause). Kept as a rotating file for
#   post-mortems:  docker exec order-service tail -f /app/logs/gc.log
ENV JAVA_TOOL_OPTIONS="-XX:InitialRAMPercentage=75 -XX:MaxRAMPercentage=75 -XX:MetaspaceSize=128m -XX:+ExitOnOutOfMemoryError -Xlog:gc*:file=/app/logs/gc.log:time,uptime,level,tags:filecount=5,filesize=10m"
RUN mkdir -p /app/logs

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
