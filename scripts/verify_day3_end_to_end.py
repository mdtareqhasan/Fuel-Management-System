"""Day 3 end-to-end verification.

This is the live proof for the Day 3 acceptance criteria. It drives the real
stack — the official simulator, the real backend, the real database — and
checks the four claims the brief makes:

    1. A proposal is generated without any simulator write.
    2. A duplicate approval results in exactly one simulator allocation.
    3. The approved quantity never exceeds the reviewed quantity.
    4. An accepted request whose response is lost is reconciled without a
       duplicate shipment.

Nothing here is mocked. Every number printed is measured.
"""

import json
import sys
import time
import urllib.error
import urllib.request

SIM = "http://localhost:8000"
BACKEND = "http://localhost:8080"

PASS = "\033[92mPASS\033[0m"
FAIL = "\033[91mFAIL\033[0m"

results = []


def check(name, condition, detail=""):
    results.append((name, bool(condition), detail))
    print(f"[{PASS if condition else FAIL}] {name}" + (f" — {detail}" if detail else ""))
    return bool(condition)


def request(method, url, body=None, timeout=60):
    """Performs a request and returns (status, parsed_body, raw_text)."""
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            raw = response.read().decode("utf-8")
            try:
                return response.status, json.loads(raw), raw
            except json.JSONDecodeError:
                return response.status, None, raw
    except urllib.error.HTTPError as error:
        raw = error.read().decode("utf-8")
        try:
            return error.code, json.loads(raw), raw
        except json.JSONDecodeError:
            return error.code, None, raw


def get(url):
    return request("GET", url)


def post(url, body=None, timeout=60):
    return request("POST", url, body, timeout)


def simulator_allocations():
    _, body, _ = get(f"{SIM}/v1/allocations")
    return body or []


def allocations_with_key(key):
    return [a for a in simulator_allocations() if a.get("idempotency_key") == key]


def section(title):
    print()
    print("=" * 78)
    print(title)
    print("=" * 78)


def pending_proposal():
    """Returns a proposal that is waiting for review, creating one if needed.

    The scheduler only creates a proposal when none is pending, so a run that
    approves several proposals exhausts them. The verification needs one at
    each stage, so it asks for a fresh one when the list runs dry.
    """
    _, proposals, _ = get(f"{BACKEND}/api/proposals")
    pending = [p for p in proposals if p["status"] == "PENDING_REVIEW"]
    if pending:
        return pending[0]

    status, fresh, raw = post(f"{BACKEND}/api/proposals")
    if status == 201:
        return fresh
    return None


def simulator_status():
    """The simulator's own mode and tick."""
    _, instance, _ = get(f"{SIM}/v1/instance")
    return (instance or {}).get("status"), (instance or {}).get("tick")


def pause_simulator():
    """Puts the simulator into PAUSED mode.

    The approval path is mode-aware by design (ADR 004). In RUNNING mode the
    world advances ~5.46 ticks/sec while a simulator request takes ~2 s, so
    the world moves ~11 ticks during a single request cycle. A proposal made
    against that world is stale before an operator can approve it, and the
    approval is refused with PROPOSAL_CHANGED before the rule under test is
    ever reached.

    PAUSED mode is the reviewable mode: the tick only moves when the operator
    steps it, so a proposal stays valid for the length of a review.
    """
    status, _, _ = get(f"{SIM}/v1/instance")
    mode, _ = simulator_status()
    if mode == "PAUSED":
        return True

    post(f"{SIM}/admin/pause")
    time.sleep(1)
    mode, tick = simulator_status()
    print(f"simulator mode: {mode} (tick {tick})")
    return mode == "PAUSED"


def resume_simulator():
    """Puts the simulator back into RUNNING mode."""
    post(f"{SIM}/admin/run")
    time.sleep(1)
    mode, tick = simulator_status()
    print(f"simulator mode: {mode} (tick {tick})")
    return mode == "RUNNING"


def current_tick():
    """The simulator's current tick."""
    _, instance, _ = get(f"{SIM}/v1/instance")
    return (instance or {}).get("tick")


def backend_tick():
    """The tick the backend's cached snapshot is at."""
    _, risk, _ = get(f"{BACKEND}/api/risk")
    return (risk or {}).get("tick")


def wait_for_backend_to_catch_up(timeout=60):
    """Waits until the backend's snapshot is at the simulator's tick.

    The approval path revalidates against the backend's CACHED snapshot, and
    the dispatch budget is computed from that snapshot's tick. If the backend
    has not refreshed yet, it still sees the old tick — where the previous
    approval already spent the depot's allowance — and every line comes back
    CHANGED for the wrong reason.
    """
    target = current_tick()
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if backend_tick() == target:
            return True
        time.sleep(1)
    return False


def fresh_pending_proposal():
    """Returns a pending proposal that is still valid for the current world.

    Two things have to be true for a stage to be able to approve a proposal:

    1. The proposal is **current**. The approval path checks freshness FIRST
       and rejects a stale proposal with PROPOSAL_CHANGED before it ever
       looks at the quantity. A stage that wants to exercise the quantity
       rules has to pick a current proposal, or it measures the freshness
       guard instead of the rule under test.

    2. The depot's **dispatch allowance** is still available. The allowance
       is consumed at POST time and resets each tick. The first approval in
       this run ships the whole network ceiling, so the next stage has to
       move to a new tick before it can ship anything.

    So this steps the tick, waits for the backend to refresh onto it, and
    then returns the proposal for that tick.
    """
    post(f"{SIM}/admin/step")
    wait_for_backend_to_catch_up()

    _, proposals, _ = get(f"{BACKEND}/api/proposals")
    tick = current_tick()
    for proposal in proposals:
        if proposal["status"] == "PENDING_REVIEW" and proposal.get("proposed_at_tick") == tick:
            return proposal

    # The scheduler may not have run yet. Ask for one directly.
    status, fresh, raw = post(f"{BACKEND}/api/proposals")
    if status == 201:
        return fresh

    # 409 means the scheduler already has one pending. Take that one.
    _, proposals, _ = get(f"{BACKEND}/api/proposals")
    tick = current_tick()
    for proposal in proposals:
        if proposal["status"] == "PENDING_REVIEW" and proposal.get("proposed_at_tick") == tick:
            return proposal
    for proposal in proposals:
        if proposal["status"] == "PENDING_REVIEW":
            return proposal

    return None


def proposal_on_a_fresh_tick():
    """Returns a proposal for a world with a fresh dispatch allowance.

    The depot's dispatch allowance is consumed at POST time and resets each
    tick. So an approval that follows another approval in the same tick finds
    the allowance already spent — which is correct behaviour, but not what a
    test of "approve a reduced quantity" needs.

    Stepping one tick resets the allowance and gives a clean world to approve
    against.
    """
    post(f"{SIM}/admin/step")
    time.sleep(2)

    _, proposals, _ = get(f"{BACKEND}/api/proposals")
    for proposal in proposals:
        if proposal["status"] == "PENDING_REVIEW":
            return proposal

    return pending_proposal()


# ---------------------------------------------------------------- reset

section("0. RESET — a clean world and a clean application run")

status, instance, _ = post(f"{SIM}/admin/reset")
if status != 200:
    print(f"reset failed with {status}")
    sys.exit(1)
print(f"simulator reset: mode={instance.get('status')} tick={instance.get('tick')}")

# The application's run id changes on a reset, so the old proposals and
# commands are isolated rather than mixed in.
time.sleep(3)

_, risk, _ = get(f"{BACKEND}/api/risk")
print(f"backend tick: {risk.get('tick')}  quality eligible: {risk.get('quality', {}).get('eligible')}")


# ------------------------------------------------------- build up demand

section("1. BUILD UP THE WORLD — 30 ticks so there is demand history and risk")

for _ in range(30):
    post(f"{SIM}/admin/step")
    time.sleep(0.12)

time.sleep(6)

_, risk, _ = get(f"{BACKEND}/api/risk")
print(f"tick now: {risk.get('tick')}")
print(f"proposals in the plan: {len(risk.get('proposals', []))}")
print(f"unmet needs: {len(risk.get('unmet_needs', []))}")

# Without pressure there is nothing to decide, which is correct behaviour but
# makes for a dull verification. A demand spike creates a real, urgent need.
if len(risk.get("proposals", [])) == 0:
    print()
    print("no pressure yet — injecting a demand spike so there is something to decide")
    _, stations, _ = get(f"{SIM}/v1/stations")
    target = sorted(stations, key=lambda s: s.get("inventory", {}).get("DIESEL", 0))
    victim = target[0]
    print(f"target station: {victim['id']} (diesel {victim['inventory'].get('DIESEL')})")

    status, event, raw = post(
        f"{SIM}/admin/events",
        {
            "type": "demand_spike",
            "start_tick": risk.get("tick", 0) + 1,
            "duration_ticks": 40,
            "parameters": {"station_id": victim["id"], "fuel_type": "DIESEL", "multiplier": 3.0},
        },
    )
    print(f"event injected -> HTTP {status}: {raw[:200]}")

    for _ in range(40):
        post(f"{SIM}/admin/step")
        time.sleep(0.12)

    time.sleep(8)
    _, risk, _ = get(f"{BACKEND}/api/risk")
    print(f"tick now: {risk.get('tick')}")
    print(f"proposals in the plan: {len(risk.get('proposals', []))}")
    print(f"unmet needs: {len(risk.get('unmet_needs', []))}")

for proposal in risk.get("proposals", [])[:6]:
    print(
        f"  {proposal['station_id']:<22} {proposal['fuel']:<8} "
        f"qty={proposal['quantity_liters']:<8} depot={proposal['depot_id']}"
    )

check(
    "the plan has at least one feasible shipment to review",
    len(risk.get("proposals", [])) > 0,
    f"{len(risk.get('proposals', []))} proposals",
)


# ------------------------------------------------- create the proposal

section("2. CREATE THE PROPOSAL — no simulator write happens here")

# The review stages that follow need a world that holds still.
#
# The approval path is mode-aware by design (ADR 004). In RUNNING mode the
# world advances ~5.46 ticks/sec and a simulator request takes ~2 s, so the
# world moves ~11 ticks during one request cycle. A proposal made against
# that world is stale before the operator can approve it, and the approval is
# refused with PROPOSAL_CHANGED before the rule under test is reached.
#
# PAUSED mode is the reviewable mode. The tick only moves when the operator
# steps it, so a proposal stays valid for the length of a review.
pause_simulator()
print()

before = len(simulator_allocations())

status, proposal, raw = post(f"{BACKEND}/api/proposals")
if status == 409:
    # The scheduler already created one. Use the pending one.
    _, proposals, _ = get(f"{BACKEND}/api/proposals")
    pending = [p for p in proposals if p["status"] == "PENDING_REVIEW"]
    if pending:
        proposal = pending[0]
        status = 200
    else:
        print("no proposal available to review")
        sys.exit(1)

print(f"proposal id : {proposal['id']}")
print(f"status      : {proposal['status']}")
print(f"mode        : {proposal['mode']}")
print(f"tick        : {proposal['proposed_at_tick']}")
print(f"reviewed    : {proposal['reviewed_quantity']} L")
for line in proposal["lines"]:
    print(
        f"  line {line['line_id']:<22} {line['station_id']:<22} {line['fuel_type']:<8} "
        f"reviewed={line['reviewed_quantity']:<8} route={line['route_id']}"
    )
    for reason in line["reasons"]:
        print(f"      reason: {reason}")

after = len(simulator_allocations())
check(
    "creating a proposal performs NO simulator write",
    after == before,
    f"allocations before={before} after={after}",
)

proposal_id = proposal["id"]


# --------------------------------------------------- duplicate approval

section("3. DUPLICATE APPROVAL — one proposal line means one simulator allocation")

status, first, raw = post(
    f"{BACKEND}/api/proposals/{proposal_id}/approve",
    {"actor": "day3-verifier", "quantities": {}},
)
print(f"first approval  -> HTTP {status}")
if status == 200:
    print(f"  proposal status: {first['status']}")
    print(f"  shipped        : {first['shipped_quantity']} L of {first['reviewed_quantity']} L reviewed")
    for line in first["lines"]:
        print(
            f"    line {line['line_id']:<22} reviewed={line['reviewed_quantity']:<8} "
            f"final={line['final_quantity']:<8} {line['status']:<20} {line['reason']}"
        )
else:
    print(f"  body: {raw[:400]}")

check("the first approval succeeds", status == 200, f"HTTP {status}")

time.sleep(4)

keys = []
for line in proposal["lines"]:
    keys.append(f"proposal-{proposal_id}-{line['line_id']}")

matching = []
for key in keys:
    matching.extend(allocations_with_key(key))

print(f"simulator allocations matching the proposal's keys: {len(matching)}")
for allocation in matching:
    print(
        f"  id={allocation['id']} key={allocation['idempotency_key']} "
        f"qty={allocation['quantity']} status={allocation['status']}"
    )

check(
    "the approval created exactly one simulator allocation per proposal line",
    len(matching) == len(proposal["lines"]),
    f"{len(matching)} allocations for {len(proposal['lines'])} lines",
)

# Now the duplicate: the same proposal, approved again.
status, second, raw = post(
    f"{BACKEND}/api/proposals/{proposal_id}/approve",
    {"actor": "day3-verifier", "quantities": {}},
)
print(f"second approval -> HTTP {status}")
print(f"  body: {raw[:300]}")

check(
    "the duplicate approval is REFUSED with ALREADY_APPROVED",
    status == 409 and '"ALREADY_APPROVED"' in raw,
    f"HTTP {status}",
)

time.sleep(3)
matching_after = []
for key in keys:
    matching_after.extend(allocations_with_key(key))

check(
    "the duplicate approval created NO second simulator allocation",
    len(matching_after) == len(matching),
    f"{len(matching)} before, {len(matching_after)} after",
)


# ------------------------------------------------ quantity above reviewed

section("4. QUANTITY ABOVE THE REVIEWED ONE — must be refused")

# A fresh proposal is needed because the first one is settled. It also has to
# be CURRENT: the approval path checks freshness before the quantity, so a
# stale proposal would be refused with PROPOSAL_CHANGED and this stage would
# measure the wrong rule.
second_proposal = fresh_pending_proposal()

if second_proposal:
    line_id = second_proposal["lines"][0]["line_id"]
    reviewed = second_proposal["lines"][0]["reviewed_quantity"]
    too_much = reviewed + 1

    status, body, raw = post(
        f"{BACKEND}/api/proposals/{second_proposal['id']}/approve",
        {"actor": "day3-verifier", "quantities": {line_id: too_much}},
    )
    print(f"asked for {too_much} L of a reviewed {reviewed} L -> HTTP {status}")
    print(f"  body: {raw[:300]}")
    check(
        "a quantity above the reviewed one is REFUSED",
        status == 422 and '"QUANTITY_EXCEEDS_REVIEWED"' in raw,
        f"HTTP {status}",
    )
else:
    check("a second proposal was available to over-request", False, "no pending proposal")


# ----------------------------------------------------- reduced quantity

section("5. A REDUCED QUANTITY — approved below the reviewed amount")

third = fresh_pending_proposal()

if third:
    line_id = third["lines"][0]["line_id"]
    reviewed = third["lines"][0]["reviewed_quantity"]
    reduced = max(1, int(reviewed * 0.5))
    status, body, raw = post(
        f"{BACKEND}/api/proposals/{third['id']}/approve",
        {"actor": "day3-verifier", "quantities": {line_id: reduced}},
    )
    print(f"asked for {reduced} L of a reviewed {reviewed} L -> HTTP {status}")
    if status == 200:
        for line in body["lines"]:
            print(
                f"    line {line['line_id']:<22} reviewed={line['reviewed_quantity']:<8} "
                f"final={line['final_quantity']:<8} {line['status']}"
            )
        target = next((l for l in body["lines"] if l["line_id"] == line_id), None)
        check(
            "the reduced quantity was accepted and is at or below the reviewed one",
            target is not None and target["final_quantity"] <= reduced,
            f"shipped {target['final_quantity'] if target else '?'} of {reduced} requested",
        )
        check(
            "no line shipped more than was reviewed",
            all(line["final_quantity"] <= line["reviewed_quantity"] for line in body["lines"]),
        )
    else:
        print(f"  body: {raw[:400]}")
        check("the reduced quantity was accepted", False, f"HTTP {status}")
else:
    check("a proposal was available to reduce", False, "no pending proposal")


# ---------------------------------------------- the lost response case

section("5b. THE LOST RESPONSE — a shipment the application never heard back about")

# The simulator's latency fault delays the response past the application's
# 2 second read timeout. The POST still reaches the simulator, so the shipment
# may exist even though the application records an UNKNOWN outcome.
# The scheduler only creates a proposal when none is pending, so the run above
# may have consumed them all. The proposal also has to be current, because the
# approval path checks freshness before anything else.
lost_proposal = fresh_pending_proposal()

if lost_proposal:
    lost_line = lost_proposal["lines"][0]
    lost_key = f"proposal-{lost_proposal['id']}-{lost_line['line_id']}"

    # The latency must exceed the application's read timeout.
    #
    # The window is deliberately wide. Under a latency fault every simulator
    # request takes delay_ms + the simulator's own baseline (~2 s), and the
    # approval path makes several reads before it writes. With a 20 s window
    # the fault expired mid-approval and the write came back normally, so
    # the check measured nothing. The delay is also set well above the
    # application's 2 s read timeout so the write is genuinely lost.
    status, fault, raw = post(
        f"{SIM}/admin/faults",
        {"type": "latency", "duration_seconds": 120, "parameters": {"delay_ms": 8000}},
    )
    print(f"latency fault injected -> HTTP {status}: {raw[:160]}")

    # The approval itself will now hit the timeout. It has to record the
    # command as UNKNOWN, not as REJECTED.
    #
    # The quantity is the line's own reviewed quantity. A hardcoded number
    # would be rejected with QUANTITY_EXCEEDS_REVIEWED whenever the line
    # happens to be smaller than it, and this stage would measure the
    # quantity rule instead of the lost response.
    lost_quantity = lost_line["reviewed_quantity"]
    try:
        status, body, raw = post(
            f"{BACKEND}/api/proposals/{lost_proposal['id']}/approve",
            {"actor": "day3-verifier", "quantities": {lost_line["line_id"]: lost_quantity}},
            timeout=90,
        )
        print(f"approval under latency -> HTTP {status} (asked for {lost_quantity} L)")
    except Exception as error:
        print(f"approval under latency raised: {error}")

    # Clear the fault so the rest of the run is not slowed by it.
    post(f"{SIM}/admin/faults/clear")
    print("fault cleared")

    _, shipments_lost, _ = get(f"{BACKEND}/api/shipments")
    lost_command = next(
        (s for s in (shipments_lost or []) if s["idempotency_key"] == lost_key), None)

    if lost_command:
        print(
            f"command {lost_command['command_id'][:8]} status={lost_command['command_status']} "
            f"attempts={lost_command['attempts']} last_error={lost_command['last_error']}"
        )
        check(
            "a timed-out simulator write is observed as UNKNOWN before reconciliation",
            lost_command["command_status"] == "UNKNOWN" and bool(lost_command["last_error"]),
            f"status={lost_command['command_status']} last_error={lost_command['last_error']}",
        )
    else:
        check("the lost-response command was recorded", False, "no command with that key")

    # The shipment may or may not exist. Reconciling must find it by key.
    before_reconcile = allocations_with_key(lost_key)
    print(f"simulator allocations with the key before reconciling: {len(before_reconcile)}")

    post(f"{BACKEND}/api/shipments/reconcile", timeout=90)
    time.sleep(4)

    after_reconcile = allocations_with_key(lost_key)
    print(f"simulator allocations with the key after reconciling:  {len(after_reconcile)}")
    for allocation in after_reconcile:
        print(
            f"  id={allocation['id']} qty={allocation['quantity']} "
            f"status={allocation['status']} key={allocation['idempotency_key']}"
        )

    check(
        "reconciling does not create a second shipment",
        len(after_reconcile) <= max(1, len(before_reconcile)),
        f"{len(before_reconcile)} before, {len(after_reconcile)} after",
    )

    _, shipments_after, _ = get(f"{BACKEND}/api/shipments")
    reconciled = next(
        (s for s in (shipments_after or []) if s["idempotency_key"] == lost_key), None)
    if reconciled:
        print(
            f"after reconciling: command={reconciled['command_status']} "
            f"simulator={reconciled['simulator_status']} "
            f"allocation={reconciled['simulator_allocation_id']}"
        )
        check(
            "the reconciled command points at the shipment that already existed",
            reconciled["command_status"] == "SENT"
            and reconciled["simulator_allocation_id"] is not None,
            f"command={reconciled['command_status']} "
            f"allocation={reconciled['simulator_allocation_id']}",
        )
    else:
        check("the reconciled command is in the ledger", False, "command missing")
else:
    check("a proposal was available for the lost-response case", False, "no pending proposal")


# --------------------------------------------------- the shipment ledger

section("6. THE SHIPMENT LEDGER — application status and simulator status, separately")

status, shipments, raw = get(f"{BACKEND}/api/shipments")
print(f"HTTP {status}, {len(shipments or [])} shipments")
for shipment in shipments or []:
    print(
        f"  cmd={shipment['command_status']:<10} sim={str(shipment['simulator_status']):<14} "
        f"alloc={str(shipment['simulator_allocation_id']):<6} "
        f"qty={shipment['quantity']} attempts={shipment['attempts']} "
        f"key={shipment['idempotency_key']}"
    )
    if shipment["last_error"]:
        print(f"      last error: {shipment['last_error']}")

check(
    "the ledger shows every command with its application status and the simulator's status separately",
    all("command_status" in s and "simulator_status" in s for s in (shipments or [])),
    f"{len(shipments or [])} shipments",
)

sent = [s for s in (shipments or []) if s["command_status"] == "SENT"]
check(
    "at least one command reached SENT",
    len(sent) > 0,
    f"{len(sent)} sent",
)


# ------------------------------------------- follow the shipment lifecycle

section("7. FOLLOW THE SHIPMENT — departure then arrival")
if sent:
    target = sent[0]
    allocation_id = target["simulator_allocation_id"]
    print(f"following allocation {allocation_id}")

    seen_status = None
    for step in range(40):
        found = [a for a in simulator_allocations() if a["id"] == allocation_id]
        if found:
            current = found[0]["status"]
            if current != seen_status:
                print(
                    f"  tick {found[0].get('created_tick')} -> status {current} "
                    f"departure={found[0].get('departure_tick')} "
                    f"arrival={found[0].get('actual_arrival_tick')}"
                )
                seen_status = current
            if current in ("DELIVERED", "ARRIVED", "COMPLETED"):
                break
        post(f"{SIM}/admin/step")
        time.sleep(0.4)

    check(
        "the shipment reached a terminal state",
        seen_status in ("DELIVERED", "ARRIVED", "COMPLETED"),
        f"final status {seen_status}",
    )

    _, shipments_after, _ = get(f"{BACKEND}/api/shipments")
    for shipment in shipments_after or []:
        if shipment["simulator_allocation_id"] == allocation_id:
            print(
                f"  ledger now: command={shipment['command_status']} "
                f"simulator={shipment['simulator_status']}"
            )
            check(
                "the ledger's simulator column tracks the simulator's own status",
                shipment["simulator_status"] == seen_status,
                f"ledger={shipment['simulator_status']} simulator={seen_status}",
            )
else:
    check("a shipment was available to follow", False, "no SENT command")


# ---------------------------------------------- the database is the record

section("8. THE DATABASE HOLDS THE DECISION")

import subprocess

try:
    rows = subprocess.run(
        [
            "docker", "exec", "fuel-postgres", "psql", "-U", "fuel", "-d", "fuelplatform",
            "-c",
            "SELECT status, count(*) FROM commands GROUP BY status ORDER BY status;",
            "-c",
            "SELECT status, count(*) FROM proposals GROUP BY status ORDER BY status;",
            "-c",
            "SELECT action, outcome, count(*) FROM audit_events GROUP BY action, outcome ORDER BY action;",
        ],
        capture_output=True,
        text=True,
        timeout=30,
    )
    if rows.returncode != 0:
        raise RuntimeError(rows.stderr.strip() or f"docker exec exited with {rows.returncode}")
    print(rows.stdout)
    check(
        "the audit trail recorded approvals and reconciliation",
        "APPROVAL" in rows.stdout and "COMMAND_RECONCILED" in rows.stdout,
        "audit_events contains APPROVAL and COMMAND_RECONCILED rows",
    )
except Exception as error:  # pragma: no cover - the check reports it
    print(f"could not query the database: {error}")
    check("the database holds the decision", False, str(error))


# ------------------------------------------------------------- summary

section("SUMMARY")

passed = sum(1 for _, ok, _ in results if ok)
total = len(results)
for name, ok, detail in results:
    print(f"[{PASS if ok else FAIL}] {name}" + (f" — {detail}" if detail else ""))

print()
print(f"{passed}/{total} checks passed")
sys.exit(0 if passed == total else 1)
