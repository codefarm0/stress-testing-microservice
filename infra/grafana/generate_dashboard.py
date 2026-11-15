#!/usr/bin/env python3
"""
Generates infra/grafana/dashboards/order-service.json.

    python3 infra/grafana/generate_dashboard.py

Grafana re-reads the file automatically (file provisioning), no restart needed.
Edit panels HERE, not in the JSON.
"""
import json
from pathlib import Path

PROM = {"type": "prometheus", "uid": "prometheus"}
LOKI = {"type": "loki", "uid": "loki"}

APP = 'application="order-service"'
ORDERS_URI = 'uri=~"/orders.*"'
RI = "$__rate_interval"
SUT = 'name=~"$container"'  # cAdvisor container name selector

panels = []
_y = 0
_x = 0
_row_h = 0
_id = 0


def _next_id():
    global _id
    _id += 1
    return _id


def row(title, collapsed=False):
    global _y, _x, _row_h
    _y += _row_h
    _x, _row_h = 0, 0
    panels.append({"type": "row", "title": title, "id": _next_id(), "collapsed": collapsed,
                   "gridPos": {"h": 1, "w": 24, "x": 0, "y": _y}, "panels": []})
    _y += 1


def _place(w, h):
    global _x, _y, _row_h
    if _x + w > 24:
        _y += _row_h
        _x, _row_h = 0, 0
    pos = {"h": h, "w": w, "x": _x, "y": _y}
    _x += w
    _row_h = max(_row_h, h)
    return pos


def _targets(queries, ds):
    out = []
    for i, q in enumerate(queries):
        expr, legend = (q if isinstance(q, tuple) else (q, ""))
        t = {"refId": chr(65 + i), "datasource": ds, "expr": expr, "legendFormat": legend}
        out.append(t)
    return out


def ts(title, queries, unit="short", w=8, h=8, desc="", stack=False, overrides=None, minv=None, maxv=None,
       thresholds=None):
    custom = {"lineWidth": 2, "fillOpacity": 10, "showPoints": "never", "spanNulls": False,
              "stacking": {"mode": "normal" if stack else "none"}}
    defaults = {"unit": unit, "custom": custom}
    if minv is not None:
        defaults["min"] = minv
    if maxv is not None:
        defaults["max"] = maxv
    if thresholds:
        defaults["thresholds"] = {"mode": "absolute", "steps": thresholds}
        custom["thresholdsStyle"] = {"mode": "line+area"}
    panels.append({
        "type": "timeseries", "title": title, "id": _next_id(), "description": desc,
        "datasource": PROM, "gridPos": _place(w, h),
        "targets": _targets(queries, PROM),
        "fieldConfig": {"defaults": defaults, "overrides": overrides or []},
        "options": {"legend": {"displayMode": "table", "placement": "bottom", "calcs": ["lastNotNull", "max"]},
                    "tooltip": {"mode": "multi", "sort": "desc"}},
    })


def stat(title, expr, unit="short", w=3, h=4, steps=None, decimals=None, desc=""):
    defaults = {"unit": unit, "thresholds": {"mode": "absolute", "steps": steps or [{"color": "green", "value": None}]}}
    if decimals is not None:
        defaults["decimals"] = decimals
    panels.append({
        "type": "stat", "title": title, "id": _next_id(), "description": desc, "datasource": PROM,
        "gridPos": _place(w, h), "targets": _targets([expr], PROM),
        "fieldConfig": {"defaults": defaults, "overrides": []},
        "options": {"colorMode": "background", "graphMode": "area", "reduceOptions": {"calcs": ["lastNotNull"]}},
    })


def loki_ts(title, queries, w=8, h=8):
    panels.append({
        "type": "timeseries", "title": title, "id": _next_id(), "datasource": LOKI, "gridPos": _place(w, h),
        "targets": [dict(t, queryType="range") for t in _targets(queries, LOKI)],
        "fieldConfig": {"defaults": {"custom": {"drawStyle": "bars", "fillOpacity": 60,
                                                "stacking": {"mode": "normal"}}},
                        "overrides": [
                            {"matcher": {"id": "byName", "options": "ERROR"},
                             "properties": [{"id": "color", "value": {"mode": "fixed", "fixedColor": "red"}}]},
                            {"matcher": {"id": "byName", "options": "WARN"},
                             "properties": [{"id": "color", "value": {"mode": "fixed", "fixedColor": "orange"}}]}]},
        "options": {"legend": {"displayMode": "list", "placement": "bottom"}},
    })


def logs(title, expr, w=24, h=10):
    panels.append({
        "type": "logs", "title": title, "id": _next_id(), "datasource": LOKI, "gridPos": _place(w, h),
        "targets": _targets([expr], LOKI),
        "options": {"showTime": True, "wrapLogMessage": True, "sortOrder": "Descending", "enableLogDetails": True},
    })


def color(name, c):
    return {"matcher": {"id": "byName", "options": name},
            "properties": [{"id": "color", "value": {"mode": "fixed", "fixedColor": c}}]}


def regex_color(rx, c):
    return {"matcher": {"id": "byRegexp", "options": rx},
            "properties": [{"id": "color", "value": {"mode": "fixed", "fixedColor": c}}]}


def dashed(name):
    return {"matcher": {"id": "byName", "options": name},
            "properties": [{"id": "custom.lineStyle", "value": {"fill": "dash", "dash": [10, 10]}},
                           {"id": "custom.fillOpacity", "value": 0}]}


GREEN, ORANGE, RED = "green", "orange", "red"

# =====================================================================================================
row("Summary — right now")
stat("Served req/s", f'sum(rate(http_server_requests_seconds_count{{{APP},{ORDERS_URI}}}[1m]))', "reqps", decimals=0)
stat("p99 latency", f'histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{{{APP},{ORDERS_URI}}}[1m])))',
     "s", steps=[{"color": GREEN, "value": None}, {"color": ORANGE, "value": 0.5}, {"color": RED, "value": 2}])
stat("5xx rate", f'sum(rate(http_server_requests_seconds_count{{{APP},{ORDERS_URI},status=~"5.."}}[1m])) '
                 f'/ sum(rate(http_server_requests_seconds_count{{{APP},{ORDERS_URI}}}[1m]))',
     "percentunit", decimals=1, steps=[{"color": GREEN, "value": None}, {"color": ORANGE, "value": 0.001}, {"color": RED, "value": 0.01}])
stat("CPU of limit", 'sum(rate(container_cpu_usage_seconds_total{name="order-service"}[1m])) '
                     '/ (max(container_spec_cpu_quota{name="order-service"}) / max(container_spec_cpu_period{name="order-service"}))',
     "percentunit", steps=[{"color": GREEN, "value": None}, {"color": ORANGE, "value": 0.7}, {"color": RED, "value": 0.9}])
stat("Memory of limit", 'max(container_memory_working_set_bytes{name="order-service"}) / max(container_spec_memory_limit_bytes{name="order-service"})',
     "percentunit", steps=[{"color": GREEN, "value": None}, {"color": ORANGE, "value": 0.8}, {"color": RED, "value": 0.9}])
stat("DB pool waiting", f'sum(hikaricp_connections_pending{{{APP}}})',
     steps=[{"color": GREEN, "value": None}, {"color": ORANGE, "value": 1}, {"color": RED, "value": 50}])
stat("Tomcat busy threads", f'sum(tomcat_threads_busy_threads{{{APP}}})',
     steps=[{"color": GREEN, "value": None}, {"color": ORANGE, "value": 150}, {"color": RED, "value": 195}])
stat("Restarts (range)", 'sum(changes(container_start_time_seconds{name="order-service"}[$__range]))',
     steps=[{"color": GREEN, "value": None}, {"color": RED, "value": 1}])

# =====================================================================================================
row("Load generator (k6)")
ts("Offered load vs served", [
    (f'sum(rate(k6_http_reqs_total{{testid=~"$testid"}}[{RI}]))', "k6 sent"),
    (f'sum(rate(http_server_requests_seconds_count{{{APP},{ORDERS_URI}}}[{RI}]))', "service completed"),
    (f'sum(rate(k6_dropped_iterations_total{{testid=~"$testid"}}[{RI}]))', "k6 dropped (no free VU)"),
], "reqps", desc="If 'sent' > 'completed' the service is falling behind. Dropped iterations = k6 could not even start the request (all VUs stuck waiting).",
   overrides=[color("k6 sent", "blue"), color("service completed", GREEN), color("k6 dropped (no free VU)", RED)])
ts("Client-side latency (k6)", [
    (f'max(k6_http_req_duration_p50{{testid=~"$testid"}})', "p50"),
    (f'max(k6_http_req_duration_p95{{testid=~"$testid"}})', "p95"),
    (f'max(k6_http_req_duration_p99{{testid=~"$testid"}})', "p99"),
], "s", desc="What the client actually experiences, including time queued in the TCP accept backlog.")
ts("k6 VUs & failures", [
    (f'sum(k6_vus{{testid=~"$testid"}})', "active VUs"),
    (f'sum(rate(k6_http_req_failed_total{{testid=~"$testid"}}[{RI}]))', "failed req/s"),
], "short", overrides=[color("failed req/s", RED)])

# =====================================================================================================
row("Application — HTTP & business")
ts("Throughput by HTTP status", [
    (f'sum by (status) (rate(http_server_requests_seconds_count{{{APP},{ORDERS_URI}}}[{RI}]))', "{{status}}"),
], "reqps", stack=True, overrides=[regex_color("2..", GREEN), regex_color("4..", ORANGE), regex_color("5..", RED)])
ts("Server latency p50 / p95 / p99", [
    (f'histogram_quantile(0.50, sum by (le) (rate(http_server_requests_seconds_bucket{{{APP},{ORDERS_URI}}}[{RI}])))', "p50"),
    (f'histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{{{APP},{ORDERS_URI}}}[{RI}])))', "p95"),
    (f'histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{{{APP},{ORDERS_URI}}}[{RI}])))', "p99"),
], "s", desc="Measured inside Tomcat: excludes time a request spent waiting in the accept queue.")
ts("Error rate", [
    (f'sum(rate(http_server_requests_seconds_count{{{APP},{ORDERS_URI},status=~"5.."}}[{RI}])) / sum(rate(http_server_requests_seconds_count{{{APP},{ORDERS_URI}}}[{RI}]))', "5xx"),
    (f'sum(rate(http_server_requests_seconds_count{{{APP},{ORDERS_URI},status=~"4.."}}[{RI}])) / sum(rate(http_server_requests_seconds_count{{{APP},{ORDERS_URI}}}[{RI}]))', "4xx"),
], "percentunit", minv=0, overrides=[color("5xx", RED), color("4xx", ORANGE)])
ts("Business outcomes (orders/s)", [
    (f'sum by (path, outcome) (rate(orders_reserved_total{{{APP}}}[{RI}]))', "{{path}} {{outcome}}"),
], "short", stack=True, desc="confirmed / rejected_* (business rule) / failed_* (pool_timeout, lock_timeout, ...)",
   overrides=[regex_color(".*confirmed", GREEN), regex_color(".*rejected.*", ORANGE), regex_color(".*failed.*", RED)])
ts("Business operation time (incl. connection wait + commit)", [
    (f'histogram_quantile(0.50, sum by (le, path) (rate(orders_reserve_duration_seconds_bucket{{{APP}}}[{RI}])))', "{{path}} p50"),
    (f'histogram_quantile(0.99, sum by (le, path) (rate(orders_reserve_duration_seconds_bucket{{{APP}}}[{RI}])))', "{{path}} p99"),
], "s")
ts("Where does the time go? (avg per phase, confirmed orders)", [
    (f'sum by (phase) (rate(orders_reserve_phase_seconds_sum{{{APP}}}[{RI}])) / sum by (phase) (rate(orders_reserve_phase_seconds_count{{{APP}}}[{RI}]))', "{{phase}}"),
], "s", stack=True, desc="db-conn = waiting for a Hikari connection, lock = waiting for the inventory row lock. "
                         "The phase that grows first under load is the bottleneck.")
ts("Tomcat request threads", [
    (f'sum(tomcat_threads_busy_threads{{{APP}}})', "busy"),
    (f'sum(tomcat_threads_current_threads{{{APP}}})', "current"),
    (f'sum(tomcat_threads_config_max_threads{{{APP}}})', "max"),
], "short", desc="busy == max -> every worker is occupied; new connections wait in the accept queue (accept-count) and then get refused.",
   overrides=[dashed("max"), color("max", RED), color("busy", "purple")])

# =====================================================================================================
row("JVM")
ts("Heap", [
    (f'sum(jvm_memory_used_bytes{{{APP},area="heap"}})', "used"),
    (f'sum(jvm_memory_committed_bytes{{{APP},area="heap"}})', "committed"),
    (f'sum(jvm_memory_max_bytes{{{APP},area="heap"}})', "max"),
], "bytes", overrides=[dashed("max"), color("max", RED)])
ts("GC: time spent pausing", [
    (f'sum(rate(jvm_gc_pause_seconds_sum{{{APP}}}[{RI}]))', "GC time / wall time"),
], "percentunit", minv=0, desc="Fraction of wall-clock time in GC pauses. Climbing towards 10%+ = memory pressure.")
ts("GC: pauses per second", [
    (f'sum by (gc, cause) (rate(jvm_gc_pause_seconds_count{{{APP}}}[{RI}]))', "{{gc}} - {{cause}}"),
], "short")
ts("Non-heap & direct memory", [
    (f'sum(jvm_memory_used_bytes{{{APP},area="nonheap"}})', "non-heap used"),
    (f'sum(jvm_buffer_memory_used_bytes{{{APP}}})', "buffers (direct/mapped)"),
], "bytes")
ts("JVM threads by state", [
    (f'sum by (state) (jvm_threads_states_threads{{{APP}}})', "{{state}}"),
], "short", stack=True)
ts("JVM process CPU", [
    (f'max(process_cpu_usage{{{APP}}})', "process cpu (of visible cores)"),
], "percentunit", minv=0, maxv=1)

# =====================================================================================================
row("Container (cAdvisor)")
ts("CPU usage vs limit", [
    (f'sum by (name) (rate(container_cpu_usage_seconds_total{{{SUT}}}[{RI}])) / on (name) (max by (name) (container_spec_cpu_quota{{{SUT}}}) / max by (name) (container_spec_cpu_period{{{SUT}}}))', "{{name}}"),
], "percentunit", minv=0, thresholds=[{"color": "transparent", "value": None}, {"color": "red", "value": 0.9}])
ts("CPU throttling", [
    (f'sum by (name) (rate(container_cpu_cfs_throttled_periods_total{{{SUT}}}[{RI}])) / sum by (name) (rate(container_cpu_cfs_periods_total{{{SUT}}}[{RI}]))', "{{name}} throttled periods"),
], "percentunit", minv=0, desc="Share of 100ms CFS periods in which the container wanted more CPU than its limit allowed.")
ts("Memory working set vs limit", [
    (f'max by (name) (container_memory_working_set_bytes{{{SUT}}})', "{{name}} working set"),
    (f'max by (name) (container_spec_memory_limit_bytes{{{SUT}}})', "{{name}} limit"),
], "bytes", overrides=[regex_color(".*limit", RED)] + [
    {"matcher": {"id": "byRegexp", "options": ".*limit"},
     "properties": [{"id": "custom.lineStyle", "value": {"fill": "dash", "dash": [10, 10]}}, {"id": "custom.fillOpacity", "value": 0}]}])
ts("Network I/O", [
    (f'sum by (name) (rate(container_network_receive_bytes_total{{{SUT}}}[{RI}]))', "{{name}} rx"),
    (f'-sum by (name) (rate(container_network_transmit_bytes_total{{{SUT}}}[{RI}]))', "{{name}} tx"),
], "Bps")
ts("Restarts & OOM kills", [
    (f'sum by (name) (changes(container_start_time_seconds{{{SUT}}}[2m]))', "{{name}} restarts"),
    (f'sum by (name) (increase(container_oom_events_total{{{SUT}}}[2m]))', "{{name}} OOM events"),
], "short", minv=0, overrides=[regex_color(".*", RED)])
ts("All containers — CPU cores used", [
    (f'sum by (name) (rate(container_cpu_usage_seconds_total{{container_label_com_docker_compose_project="stress-test"}}[{RI}]))', "{{name}}"),
], "short", stack=True, desc="Is something else on the box (Prometheus, Loki, ...) stealing CPU?")

# =====================================================================================================
row("Database — connection pool & MySQL")
ts("Hikari pool", [
    (f'sum(hikaricp_connections_active{{{APP}}})', "active"),
    (f'sum(hikaricp_connections_idle{{{APP}}})', "idle"),
    (f'sum(hikaricp_connections_pending{{{APP}}})', "waiting threads"),
    (f'sum(hikaricp_connections_max{{{APP}}})', "max"),
], "short", desc="active == max and 'waiting threads' > 0 -> the pool is the bottleneck.",
   overrides=[dashed("max"), color("max", RED), color("waiting threads", ORANGE)])
ts("Connection acquire time & timeouts", [
    (f'histogram_quantile(0.99, sum by (le) (rate(hikaricp_connections_acquire_seconds_bucket{{{APP}}}[{RI}])))', "acquire p99"),
    (f'histogram_quantile(0.50, sum by (le) (rate(hikaricp_connections_acquire_seconds_bucket{{{APP}}}[{RI}])))', "acquire p50"),
    (f'sum(rate(hikaricp_connections_timeout_total{{{APP}}}[{RI}]))', "timeouts/s"),
], "s", overrides=[{"matcher": {"id": "byName", "options": "timeouts/s"},
                    "properties": [{"id": "unit", "value": "short"}, {"id": "custom.axisPlacement", "value": "right"},
                                   {"id": "color", "value": {"mode": "fixed", "fixedColor": "red"}}]}])
ts("Connection hold time (avg)", [
    (f'sum(rate(hikaricp_connections_usage_seconds_sum{{{APP}}}[{RI}])) / sum(rate(hikaricp_connections_usage_seconds_count{{{APP}}}[{RI}]))', "held per transaction"),
], "s", desc="How long each transaction keeps a connection. Pool capacity ≈ pool size / hold time.")
ts("MySQL transactions & queries", [
    (f'sum by (command) (rate(mysql_global_status_commands_total{{command=~"commit|rollback"}}[{RI}]))', "{{command}}/s"),
    (f'rate(mysql_global_status_queries[{RI}])', "queries/s"),
], "short")
ts("InnoDB row lock waits", [
    (f'rate(mysql_global_status_innodb_row_lock_waits[{RI}])', "lock waits/s"),
    ('mysql_global_status_innodb_row_lock_current_waits', "waiting right now"),
    (f'rate(mysql_global_status_innodb_row_lock_time[{RI}]) / rate(mysql_global_status_innodb_row_lock_waits[{RI}]) / 1000', "avg wait (s)"),
], "short", desc="SELECT ... FOR UPDATE on inventory. Rises sharply with HOT_PRODUCTS.")
ts("MySQL threads", [
    ('mysql_global_status_threads_running', "running"),
    ('mysql_global_status_threads_connected', "connected"),
], "short")

# =====================================================================================================
row("Logs (Loki)")
loki_ts("Log lines by level", [
    ('sum by (level) (count_over_time({service="order-service"} [$__interval]))', "{{level}}"),
], w=12)
loki_ts("Failures by type", [
    ('sum by (failure) (count_over_time({service="order-service", level="ERROR"} | json | failure != "" [$__interval]))', "{{failure}}"),
], w=12)
logs("Load stats (every 5s)", '{service="order-service", logger="load-stats"} | json | line_format "{{.level}}  {{.message}}"', h=9)
logs("Orders — one line per request (CONFIRMED / REJECTED / FAILED)",
     '{service="order-service", logger=~".*OrderService"} | json | line_format "{{.level}} {{.message}}"', h=10)
logs("Errors & warnings", '{service="order-service", level=~"ERROR|WARN", logger!="load-stats"} | json | line_format "{{.level}} [{{.logger_name}}] {{.message}}"', h=9)

# =====================================================================================================
dashboard = {
    "uid": "order-service-stress",
    "title": "Order Service — Stress Test",
    "tags": ["stress-test"],
    "timezone": "browser",
    "schemaVersion": 39,
    "refresh": "5s",
    "time": {"from": "now-15m", "to": "now"},
    "graphTooltip": 1,  # shared crosshair: line up cause and effect across panels
    "templating": {"list": [
        {"name": "testid", "label": "k6 test id", "type": "query", "datasource": PROM,
         "query": {"query": "label_values(k6_http_reqs_total, testid)", "refId": "A"},
         "refresh": 2, "includeAll": True, "multi": True, "allValue": ".*",
         "current": {"text": "All", "value": "$__all"}},
        {"name": "container", "label": "containers", "type": "custom",
         "query": "order-service,mysql", "includeAll": True, "multi": True, "allValue": "order-service|mysql",
         "current": {"text": "All", "value": "$__all"},
         "options": []},
    ]},
    "annotations": {"list": []},
    "panels": panels,
}

out = Path(__file__).parent / "dashboards" / "order-service.json"
out.write_text(json.dumps(dashboard, indent=2))
print(f"wrote {out} ({len(panels)} panels)")
