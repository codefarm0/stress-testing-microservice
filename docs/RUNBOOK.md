# Runbook — Phase A (sync)

All commands run from the project root.

## 0. Tests (Testcontainers, needs Docker running)
```bash
./gradlew test
```

## 1. Start the stack
```bash
docker compose -f infra/docker-compose.yml up -d --build
docker compose -f infra/docker-compose.yml ps            # all "running"/"healthy"
docker logs -f order-service                             # look for the "Capacity envelope" line
```
The first start takes about 30s longer while Flyway seeds 100k customers and 10k products.

| What | URL |
|---|---|
| Grafana dashboard | http://localhost:3000 (home dashboard, no login) |
| Prometheus targets | http://localhost:9090/targets (all 4 should be UP) |
| App metrics | http://localhost:8080/actuator/prometheus |
| cAdvisor | http://localhost:8081 |

Change the capacity envelope without editing files:
```bash
APP_CPUS=2.0 APP_MEMORY=1G docker compose -f infra/docker-compose.yml up -d order-service
DB_POOL_SIZE=20 docker compose -f infra/docker-compose.yml up -d order-service
ORDER_LOG_LEVEL=WARN docker compose -f infra/docker-compose.yml up -d order-service    # drop per-order INFO lines for max-load runs
```

## 2. Experiments
In Grafana, pick the run in the **k6 test id** dropdown.
```bash
k6/run.sh k6/sync/smoke.js     smoke                    # every panel should get data
k6/run.sh k6/sync/baseline.js  exp1-baseline            # 100 req/s, 5 min
k6/run.sh k6/sync/ramp.js      exp2-ramp                # 100 -> 2000 req/s, 2 min steps
k6/run.sh k6/sync/overload.js  exp3-overload -e RATE=<1.5x max from exp2>
```
Variations:
```bash
k6/run.sh k6/sync/ramp.js exp2-ramp-hot -e HOT_PRODUCTS=5        # row-lock contention
k6/run.sh k6/sync/ramp.js exp2-ramp-fine -e LEVELS=200,300,400,500,600 -e STEP=1m
```
Summaries and logs go to `results/sync/`.

## 3. Useful log queries (Grafana → Explore → Loki)
```text
{service="order-service", logger="load-stats"} | json            # the 5-second summary lines
{service="order-service", level="ERROR"} | json | failure="POOL_TIMEOUT"
{service="order-service"} | json | requestId="<id from X-Request-Id header>"
{service="order-service"} | json | lockWaitMs > 100             # orders that waited on a row lock
docker exec order-service tail -f /app/logs/gc.log                # GC log (file, not in Loki)
```

## 4. Reset between runs
```bash
docker compose -f infra/docker-compose.yml restart order-service               # fresh JVM
docker compose -f infra/docker-compose.yml down -v                             # wipe everything incl. DB
```

## Editing the dashboard
Edit `infra/grafana/generate_dashboard.py`, then run `python3 infra/grafana/generate_dashboard.py`. Grafana picks up the change within about 10s.
