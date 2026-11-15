# Implementation Plan — Sync vs Async Under Extreme Load

Source spec: `part-1-hitting-resource-limits.md`.
Goal: push one resource-limited Spring Boot order service until it breaks (sync), watch every layer in Grafana, then add Kafka (async) and show **where the pressure moves**.

**Order of work:** Phase A (sync, end to end, with Grafana fully set up and the break observed) → Phase B (async on the same stack, same experiments).

---

## Current state

- Fresh Spring Initializr skeleton: Boot 4.1.2, Java 25 toolchain, Gradle 9.8.2.
- Starters already present: actuator, data-jpa, kafka, webmvc. Only the `main` class exists.
- Missing: MySQL driver, Prometheus registry, validation, schema migration, all domain code, Docker/infra, k6 scripts.
- Machine: Docker has 12 CPUs / ~7.6 GB RAM. k6 is installed on the host. JDK 25 is installed locally, so the toolchain works. The image builds in a JDK 25 container anyway.

---

## Target repo layout

```text
stress-testing-microservice/
├── build.gradle, Dockerfile
├── src/main/java/in/codefarm/stress_testing_microservice/
│   ├── order/      (Order, OrderItem, OrderService, OrderController, DTOs, exceptions)
│   ├── inventory/  (Inventory, InventoryRepository with PESSIMISTIC_WRITE)
│   ├── customer/   product/
│   └── async/      (Phase B: OrderProducer, OrderConsumer, OrderRequestedEvent, Kafka config)
├── src/main/resources/
│   ├── application.yaml (+ profiles: api, consumer)
│   └── db/migration/  (Flyway: V1__schema.sql, V2__seed.sql)
├── infra/
│   ├── docker-compose.yml            (app + mysql + observability)
│   ├── docker-compose.kafka.yml      (Phase B overlay: kafka, kafka-exporter, consumer)
│   ├── prometheus/prometheus.yml
│   ├── alloy/config.alloy
│   ├── loki/loki.yaml
│   ├── mysql/my.cnf
│   └── grafana/provisioning/{datasources,dashboards}/ + dashboards/order-service.json
├── k6/
│   ├── lib/payload.js                (random customer/product, hot-product mode)
│   ├── sync/{smoke,baseline,ramp,overload}.js
│   └── async/{smoke,same-load,ramp}.js
└── results/  (k6 summaries + Grafana screenshots per experiment, plus the comparison table)
```

---

# Phase A — Synchronous flow

### A1. Build and dependencies
- Add `mysql-connector-j` (runtime), `micrometer-registry-prometheus`, `spring-boot-starter-validation`, and the Flyway starter with `flyway-mysql`. Add Testcontainers (MySQL) for one integration test.
- `application.yaml`:
  - Expose `health,info,prometheus,metrics`.
  - Turn on `percentiles-histogram` for `http.server.requests` so Prometheus can compute p50/p95/p99.
  - Set `server.tomcat.mbeanregistry.enabled=true` to get Tomcat thread metrics.
  - Set Tomcat `threads.max` (200) and `accept-count` explicitly, so the queue limits are known.
  - Hikari: `maximum-pool-size` 10, `connection-timeout` 2–3s. These make pool exhaustion visible as `SQLTransientConnectionException` and 5xx instead of hanging.
  - `spring.jpa.open-in-view=false`.
  - Structured JSON console logs (Boot's built-in `logging.structured.format.console`) so Loki can parse fields.

### A2. Domain and business logic
- Flyway `V1__schema.sql` creates `customers`, `products`, `inventory (product_id PK, available, reserved, version)`, `orders (id, request_id UNIQUE NULL, customer_id, status, total, created_at)` and `order_items`.
  - `request_id` is unused in Phase A. It is added now so the schema doesn't change between phases.
- `V2__seed.sql` (or a seed profile) loads 100k customers, 10k products and inventory with very high stock. Stock-outs should not end the run early; they stay available via a "low stock" mode.
- `OrderService` has `validate()`, `reserveInventory()`, `createOrder()` and `persist()`, all in one `@Transactional reserve()`:
  - It validates the customer exists and the quantity is between 1 and 10.
  - `inventoryRepository.findForUpdate()` uses `@Lock(PESSIMISTIC_WRITE)`, which is real `SELECT … FOR UPDATE` row locking.
  - It saves the order and its items, then returns the DTO.
- `POST /orders/reserve-sync` returns `200` with the order. Errors map to HTTP codes: `404` unknown customer or product, `409` insufficient stock, `400` validation, `503` pool timeout (via `@ControllerAdvice`).
- `GET /orders/{id}`.
- Custom metrics: counter `orders.reserved{path,outcome}` and timer `orders.reserve.duration`.

### A3. Containerize
- Multi-stage `Dockerfile`: eclipse-temurin 25 JDK builds `bootJar`, then the 25 JRE runs it.
- JVM flags: `-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Xlog:gc*:stdout` (optional).
  - `ExitOnOutOfMemoryError` with `restart: unless-stopped` makes an OOM show up as a visible container restart in cAdvisor.

### A4. `infra/docker-compose.yml` (all with explicit limits)
| Service | Image | Limits | Notes |
|---|---|---|---|
| order-service | built | **1 CPU / 512 MB** | the system under test, `restart: unless-stopped` |
| mysql | mysql:8.4 | 2 CPU / 1 GB | `my.cnf`: buffer pool, `max_connections`, perf schema on |
| mysqld-exporter | prom/mysqld-exporter | small | gives Prometheus TPS, lock waits, threads, slow queries |
| prometheus | prom/prometheus | 1 GB | `--web.enable-remote-write-receiver` (for k6), 5s scrape |
| cadvisor | gcr.io/cadvisor/cadvisor | small | ⚠️ verify early on Docker Desktop/macOS (container name labels) |
| loki | grafana/loki | 512 MB | single-binary, filesystem storage |
| alloy | grafana/alloy | small | `discovery.docker` → `loki.source.docker` → Loki |
| grafana | grafana/grafana | 512 MB | datasources + dashboard provisioned from files, anonymous admin |

Pin all image versions. The total stays well under the ~7.6 GB Docker budget.

### A5. Grafana dashboard (provisioned JSON, one screen)
Variables: `$instance` and `$container`. Rows:
1. **Load (k6)**: these come from k6 Prometheus remote-write, so the load and its effects line up on one time axis.
   - Request rate sent, dropped iterations, VUs.
2. **Application**:
   - RPS by status (`http_server_requests_seconds_count`).
   - p50/p95/p99 via `histogram_quantile`.
   - 4xx/5xx rate.
   - Tomcat busy/max threads.
3. **JVM**:
   - Heap used/max, non-heap.
   - GC pause rate and time.
   - Live threads.
4. **Container (cAdvisor)**:
   - CPU % of limit.
   - **CPU throttled periods/seconds**.
   - Memory working set vs limit.
   - Network I/O.
   - Restarts (via `container_start_time_seconds` changes / `container_last_seen`).
5. **Database**:
   - Hikari active/idle/pending, `hikaricp_connections_acquire_seconds`, timeouts.
   - MySQL TPS, row lock waits/time, threads running.
6. **Logs**: Loki panel filtered to `level=ERROR` for order-service, plus a log-rate-by-level graph.
7. *(Phase B)* **Kafka**: added later.

### A6. k6 scripts (sync)
- `lib/payload.js`:
  - Picks random customer and product IDs.
  - `HOT_PRODUCTS=N` env var concentrates traffic on N products to provoke lock contention.
- Every script uses `ramping-arrival-rate` or `constant-arrival-rate` and has thresholds (p95, error rate).
- Run with `k6 run -o experimental-prometheus-rw --tag testid=<exp>` so k6 metrics land in Prometheus.
- Scripts:
  - `smoke.js`: 5 req/s for 30s.
  - `baseline.js`: **Exp 1**, 100 req/s for 5 min.
  - `ramp.js`: **Exp 2**, 100 → 250 → 500 → 750 → 1000 → 1500 → 2000, 2 min per step. Env vars can override all of this.
  - `overload.js`: **Exp 3**, holds about 1.5× the max sustainable rate found in Exp 2 for 10 min, then drops to baseline for 5 min to measure **recovery time**.

### A7. Run and record (Phase A exit criteria)
- [ ] `docker compose up` gives a healthy stack. The dashboard shows live data in every row, and logs show up in Loki.
- [ ] Exp 1 recorded: the normal CPU, heap, p95 and pool usage.
- [ ] Exp 2: the **max sustainable throughput** and the **first error point** are identified. The first saturated resource is named (CPU throttling, Hikari pending, or MySQL locks).
- [ ] Exp 3: the service visibly degrades under sustained overload. Latency climbs, pool timeouts appear, then 5xx. If memory or OOM is reached, the container restart is captured. Recovery time is measured.
- [ ] Optional variations:
  - Re-run Exp 2 at 2 CPU / 1 GB to show how resources change capacity.
  - Run with `HOT_PRODUCTS=5` to show the bottleneck moving to row locks.
- [ ] `results/sync/` holds the k6 summaries, screenshots and a short findings note.

If the service never breaks memory-wise, we don't fake it. We write down that the bottleneck was CPU or the DB, which is exactly the "capacity is not a single number" point. A lower memory limit can be tried as a separate run.

---

# Phase B — Asynchronous flow (Kafka)

### B1. Infra overlay `docker-compose.kafka.yml`
- `kafka`: `apache/kafka` in single-node KRaft mode, 1 CPU / 1 GB. Topic `order-requests` with 6 partitions, plus `order-requests.DLT`.
- `kafka-exporter` (danielqsj) provides **consumer group lag** per topic and partition.
- `order-consumer`: the **same image** run with `SPRING_PROFILES_ACTIVE=consumer`, with its own **1 CPU / 512 MB** limit.
  - API CPU and consumer CPU then show up separately on the dashboard, matching the Phase 2 diagram.
  - The API container runs profile `api` (producer only, no listeners).

### B2. Async code (same `OrderService`)
- `POST /orders/reserve-async`:
  1. Validates the request (bean validation only, **no DB call**, so the API path doesn't touch MySQL).
  2. Generates a `requestId` (UUID) and publishes `OrderRequestedEvent{requestId, customerId, productId, qty, acceptedAt}`, keyed by `productId` so per-product ordering holds.
  3. Returns `202 {requestId, status: PROCESSING}`.
  - Producer settings: `acks=all`, idempotence on, `linger.ms` 5–10, and a bounded `buffer.memory` / `max.block.ms`. Send failures map to `503`.
- `OrderConsumer` (`@KafkaListener`, concurrency 3–6) calls `OrderService.reserve()` with the `requestId`.
  - **Idempotency**: the unique `orders.request_id` makes a duplicate delivery a no-op.
  - Business rejections (out of stock, unknown customer) are persisted as `REJECTED` and **not retried**.
  - Transient errors: `DefaultErrorHandler` with backoff, then `DeadLetterPublishingRecoverer` to the DLT. Poison messages go to the DLT.
- `GET /orders/requests/{requestId}` returns `PROCESSING` (no row yet), `CONFIRMED` or `REJECTED`.
- Metrics:
  - Timer `orders.e2e.latency` = `now − acceptedAt`, recorded in the consumer. This is the **time to completion**.
  - Counter `orders.consumed{outcome}`.
  - The Spring Kafka producer/consumer Micrometer metrics are already enabled.

### B3. Dashboard: Kafka row
- Producer send rate and errors (API), consumer records/s, **consumer lag** (kafka-exporter), and e2e latency p95/p99.
- Business completion rate (`orders.consumed` vs `202`s).
- Consumer container CPU/memory next to the API container.

### B4. k6 scripts (async)
- `same-load.js`: **Exp 4**, the exact ramp from Exp 2 against `/reserve-async`.
- `ramp.js`: **Exp 5**, 500 → 1000 → 2000 → 5000 req/s.
- Optional: a small sampler VU polls `GET /orders/requests/{id}` to measure completion from the client side.
- After the load stops, keep watching until **lag drains to 0** and record the drain time.

### B5. Run and record (Phase B exit criteria)
- [ ] Exp 4: API p95 stays low at loads where sync failed. Lag grows at about *incoming − processing* rate. Compare against the formula.
- [ ] Exp 5: the API limit is found (now producer and HTTP bound). Lag explodes and the drain time is measured. Check whether the consumer/DB rate matches the sync max throughput; it should be roughly the same.
- [ ] Fill the comparison table from the spec (§9) in `results/comparison.md` **using measured numbers only**.

---

## Risks and decisions to settle early

1. **cAdvisor on Docker Desktop (macOS)** sometimes lacks container name labels. Verify on day 1. The fallback is `docker stats` scraping or Docker Desktop's own metrics.
2. **k6 on the same machine** competes for CPU. Because the app is capped at 1 CPU, this is acceptable. Keep an eye on host CPU in k6 output (`dropped_iterations` tells us when k6 itself is the limit).
3. **Seed size and stock levels**: too little stock and the test turns into a 409 storm. Use high stock by default and a separate low-stock run.
4. **Default decisions taken** (change if you disagree):
   - The consumer runs as a separate container from the same codebase.
   - Flyway handles the schema.
   - k6 runs on the host with Prometheus remote-write.
   - MySQL is limited to 2 CPU / 1 GB, so the app is the first bottleneck. A later run can starve MySQL instead.

## Execution order (suggested checkpoints)
1. A1–A2: code plus an integration test → `./gradlew test` is green.
2. A3–A4: the stack is up, `/actuator/prometheus` is scraped, and logs are in Loki.
3. A5: dashboard is provisioned and the smoke test fills every panel.
4. A6–A7: experiments 1–3 → **sync findings written**.
5. B1–B2: Kafka plus the consumer, idempotency and DLT tested.
6. B3–B5: Kafka dashboard row, experiments 4–5 → **comparison table**.
