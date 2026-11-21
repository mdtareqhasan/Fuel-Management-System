"""Print the JSON key structure of a live response.

Used during Day 2 to make the risk summary script match what the backend actually
emits, rather than what it was assumed to emit.
"""

import json
import sys
import urllib.request

URL = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080/api/risk"

with urllib.request.urlopen(URL, timeout=30) as response:
    data = json.loads(response.read().decode("utf-8"))


def show(label, value, depth=0):
    if isinstance(value, dict):
        print(f"{label}: {sorted(value.keys())}")
        if depth < 2:
            for key, child in list(value.items())[:2]:
                if isinstance(child, dict) and child:
                    show(f"  {label}.{key}", child, depth + 1)
                elif isinstance(child, list) and child:
                    show(f"  {label}[0]", child[0], depth + 1)
    elif isinstance(value, list):
        print(f"{label}: list[{len(value)}]")
        if value and depth < 2:
            show(f"  {label}[0]", value[0], depth + 1)


show("root", data)
