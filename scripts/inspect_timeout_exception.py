"""Prints the exact exception the JDK client raises on a read timeout.

The circuit breaker must tell a slow simulator from a dead one, and that
decision rests on the exception type. This prints the real chain rather than
assuming it.
"""

import json
import subprocess
import textwrap
import urllib.request

SIM = "http://localhost:8000"

JAVA_PROBE = textwrap.dedent("""
    import java.net.http.*;
    import java.net.URI;
    import java.time.Duration;

    public class Probe {
        public static void main(String[] args) throws Exception {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(1000))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(args[0]))
                    .timeout(Duration.ofMillis(2000))
                    .GET()
                    .build();
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                System.out.println("HTTP " + response.statusCode());
            } catch (Exception e) {
                System.out.println("EXCEPTION CHAIN");
                Throwable current = e;
                int depth = 0;
                while (current != null && depth < 10) {
                    System.out.println("  [" + depth + "] " + current.getClass().getName() + ": " + current.getMessage());
                    current = current.getCause();
                    depth++;
                }
            }
        }
    }
""")


def post(path, body=None, timeout=30):
    data = json.dumps(body if body is not None else {}).encode()
    request = urllib.request.Request(f"{SIM}{path}", data=data, method="POST")
    request.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.status


print("=" * 78)
print("WRITING THE PROBE")
print("=" * 78)

with open("Probe.java", "w", encoding="utf-8") as handle:
    handle.write(JAVA_PROBE)

compile_result = subprocess.run(
    ["javac", "Probe.java"], capture_output=True, text=True)
print(f"javac exit={compile_result.returncode}")
if compile_result.returncode != 0:
    print(compile_result.stderr[:800])
    raise SystemExit(1)

print()
print("=" * 78)
print("BASELINE — no fault")
print("=" * 78)
result = subprocess.run(
    ["java", "-cp", ".", "Probe", f"{SIM}/v1/instance"],
    capture_output=True, text=True)
print(result.stdout)
if result.stderr:
    print(result.stderr[:400])

print()
print("=" * 78)
print("UNDER A LATENCY FAULT (3 s delay, 2 s client timeout)")
print("=" * 78)
print(f"fault -> HTTP {post('/admin/faults', {'type': 'latency', 'duration_seconds': 10, 'parameters': {'delay_ms': 3000}})}")
result = subprocess.run(
    ["java", "-cp", ".", "Probe", f"{SIM}/v1/instance"],
    capture_output=True, text=True)
print(result.stdout)
if result.stderr:
    print(result.stderr[:400])

post("/admin/faults/clear")
print("fault cleared")
