"""Prints the Day 3 schema as the database actually holds it.

The migration file says what was intended. This prints what exists, so the two
can be compared instead of assumed to be the same.
"""

import os
import subprocess
import sys

CONTAINER = "fuel-postgres"
DATABASE = os.environ.get("PGDATABASE", "fuelplatform")
USER = os.environ.get("PGUSER", "fuel")

QUERIES = [
    ("TABLES", """
        SELECT table_name FROM information_schema.tables
        WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
        ORDER BY table_name
    """),
    ("COLUMNS", """
        SELECT table_name || '.' || column_name || ' ' || data_type
        FROM information_schema.columns
        WHERE table_schema = 'public'
        ORDER BY table_name, ordinal_position
    """),
    ("CONSTRAINTS", """
        SELECT conrelid::regclass::text || ' | ' || conname || ' | ' || pg_get_constraintdef(oid)
        FROM pg_constraint
        WHERE connamespace = 'public'::regnamespace
        ORDER BY conrelid::regclass::text, conname
    """),
    ("INDEXES", """
        SELECT tablename || ' | ' || indexname
        FROM pg_indexes WHERE schemaname = 'public'
        ORDER BY tablename, indexname
    """),
    ("ROW COUNTS", """
        SELECT 'snapshots      | ' || count(*) FROM snapshots
        UNION ALL SELECT 'proposals      | ' || count(*) FROM proposals
        UNION ALL SELECT 'proposal_lines | ' || count(*) FROM proposal_lines
        UNION ALL SELECT 'commands       | ' || count(*) FROM commands
        UNION ALL SELECT 'audit_events   | ' || count(*) FROM audit_events
    """),
    ("COMMAND STATUSES", """
        SELECT status, count(*) FROM commands GROUP BY status ORDER BY status
    """),
    ("PROPOSAL STATUSES", """
        SELECT status, count(*) FROM proposals GROUP BY status ORDER BY status
    """),
    ("AUDIT ACTIONS", """
        SELECT action, outcome, count(*) FROM audit_events
        GROUP BY action, outcome ORDER BY action, outcome
    """),
    ("COMMAND STATUS VS SIMULATOR STATUS", """
        SELECT status || ' -> simulator ' || COALESCE(simulator_status, '(none)')
        FROM commands ORDER BY status LIMIT 20
    """),
]


def run(sql):
    """Runs one query and returns its rows as text."""
    result = subprocess.run(
        ["docker", "exec", "-i", CONTAINER,
         "psql", "-U", USER, "-d", DATABASE, "-c", sql],
        capture_output=True, text=True, timeout=60)
    if result.returncode != 0:
        return f"ERROR: {result.stderr.strip()}"
    return result.stdout.rstrip()


for title, sql in QUERIES:
    print("=" * 78)
    print(title)
    print("=" * 78)
    print(run(sql))
    print()

print("=" * 78)
print("MIGRATION HISTORY")
print("=" * 78)
print(run("SELECT installed_rank, version, description, success FROM flyway_schema_history ORDER BY installed_rank"))
