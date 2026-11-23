"""Captures the Day 4 evidence that a screenshot cannot show.

Takes a set of Prometheus screenshots as text — the metric values at the
moment of capture — and writes them to a file the documentation can point at.

The point of capturing the values as text, rather than as a picture of a
dashboard, is that a text file is diffable, greppable and survives a rebuild
of the Grafana container.
"""

import json
import sys
import time
import urllib.request

BACKEND = "http://localhost:8080"
PROMETHEUS = "http://localhost:9090"


def get_text(url, timeout=30):
    with urllib.request.urlopen(url, timeout=timeout) as response:
        return response.read().decode("utf-8")


def get_json(url, timeout=30):
    return json.loads(get_text(url, timeout))


def metric_lines(metrics, prefixes):
    """Every sample line whose metric name starts with one of the prefixes."""
    found = []
    for line in metrics.splitlines():
        if line.startswith("#"):
            continue
        name = line.split(" ")[0].split("{")[0]
        if name.startswith(prefixes):
            found.append(line)
    return sorted(found)


def main():
    label = sys.argv[1] if len(sys.argv) > 1 else "steady-state"
    path = sys.argv[2] if len(sys.argv) > 2 else f"docs/evidence/day-04/metrics-{label}.txt"

    metrics = get_text(f"{BACKEND}/actuator/prometheus")
    status = get_json(f"{BACKEND}/api/system-status")

    lines = []
    lines.append("=" * 78)
    lines.append(f"PROMETHEUS EVIDENCE — {label}")
    lines.append(f"captured at {time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())}")
    lines.append("=" * 78)
    lines.append("")
    lines.append("SYSTEM STATUS")
    lines.append("-" * 78)
    for key, value in status.items():
        lines.append(f"  {key:<26} {value}")

    lines.append("")
    lines.append("RESILIENCE METRICS")
    lines.append("-" * 78)
    for line in metric_lines(metrics, ("fuel_circuit_breaker", "fuel_stream",
                                      "fuel_approvals_", "fuel_approval_seconds",
                                      "fuel_refresh_")):
        lines.append(f"  {line}")

    lines.append("")
    lines.append("DATA QUALITY METRICS")
    lines.append("-" * 78)
    for line in metric_lines(metrics, ("fuel_stale_responses", "fuel_rejected_payloads",
                                      "fuel_anomaly_flags", "fuel_forecast_fallback",
                                      "fuel_commands_")):
        lines.append(f"  {line}")

    lines.append("")
    lines.append("PROMETHEUS RULE HEALTH")
    lines.append("-" * 78)
    rules = get_json(f"{PROMETHEUS}/api/v1/rules")
    for group in rules.get("data", {}).get("groups", []):
        lines.append(f"  group: {group['name']} (interval {group['interval']}s)")
        for rule in group.get("rules", []):
            state = rule.get("state", "unknown")
            health = rule.get("health", "unknown")
            lines.append(f"    [{state:<4} {health:<7}] {rule['name']}")
            if rule.get("lastError"):
                lines.append(f"           last error: {rule['lastError']}")

    lines.append("")
    lines.append("ACTIVE ALERTS")
    lines.append("-" * 78)
    alerts = get_json(f"{PROMETHEUS}/api/v1/alerts")
    active = alerts.get("data", {}).get("alerts", [])
    if not active:
        lines.append("  (none firing)")
    for alert in active:
        labels = alert.get("labels", {})
        lines.append(f"  {labels.get('alertname')} severity={labels.get('severity')} "
                     f"state={alert.get('state')}")
        lines.append(f"    {alert.get('annotations', {}).get('summary', '')}")

    with open(path, "w", encoding="utf-8") as handle:
        handle.write("\n".join(lines) + "\n")

    print(f"wrote {path} ({len(lines)} lines)")
    print("\n".join(lines[:20]))


if __name__ == "__main__":
    main()
