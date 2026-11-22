-- Day 3 · Persistence and operator decisions.
--
-- The shape of this schema follows from two decisions that are easy to get wrong:
--
--   ADR 003 — no allocation reaches the simulator without a server-enforced
--              operator approval. So every shipment has a named actor and a
--              proposal it came from.
--   ADR 004 — a proposal is a reviewed envelope. The operator reviewed an
--              UPPER BOUND on the quantity. The quantity may fall after
--              revalidation, but never rise. So the reviewed quantity and the
--              final quantity are separate columns, and the database refuses to
--              let the final exceed the reviewed.
--
-- Every table carries run_id. The simulator's instance id is always 1 and is
-- useless for isolation, so a reset starts a new application run and old rows
-- stay readable but never mix with new ones.
--
-- run_id is TEXT, not UUID. The domain carries it as an opaque string
-- (WorldSnapshot.runId()), and storing it as a UUID would put a conversion at
-- every repository boundary for no benefit: the value is never compared
-- against anything except itself.

-- ---------------------------------------------------------------- snapshots
--
-- One row per validated observation of the world. A snapshot is the thing a
-- proposal was built from, so it must outlive the process that observed it.

CREATE TABLE snapshots (
    id                UUID PRIMARY KEY,
    run_id            TEXT        NOT NULL,
    simulator_tick    BIGINT      NOT NULL,
    tick_start        BIGINT,
    tick_end          BIGINT,
    sim_time          TEXT,
    observed_at       TIMESTAMPTZ NOT NULL,
    quality           TEXT        NOT NULL,
    decision_eligible BOOLEAN     NOT NULL,
    warnings          TEXT        NOT NULL DEFAULT '',
    -- The full validated payload. A proposal must be explainable against the
    -- exact world it was computed from, and re-deriving it later would depend
    -- on code that may since have changed.
    payload           JSONB       NOT NULL
);

CREATE INDEX idx_snapshots_run_tick ON snapshots (run_id, simulator_tick DESC);

-- --------------------------------------------------------------- proposals
--
-- A proposal is the set of shipment lines the operator is asked to review.
-- It is created from a snapshot and never edited afterwards: a change of
-- world produces a NEW proposal, not a mutation of the old one.

CREATE TABLE proposals (
    id                UUID PRIMARY KEY,
    run_id            TEXT        NOT NULL,
    snapshot_id       UUID        NOT NULL REFERENCES snapshots (id),
    policy_version    TEXT        NOT NULL,
    -- The tick the proposal was computed for.
    proposed_at_tick  BIGINT      NOT NULL,
    -- The simulator's mode when it was created. It decides how the freshness
    -- rule behaves at approval time (ADR 004).
    mode              TEXT        NOT NULL,
    status            TEXT        NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL,
    -- Total reviewed quantity across all lines. A convenience for the ledger.
    -- The authoritative per-line quantities live on proposal_lines.
    reviewed_quantity DOUBLE PRECISION NOT NULL DEFAULT 0,
    CONSTRAINT chk_proposal_status CHECK (status IN (
        'PENDING_REVIEW', 'APPROVED', 'APPROVED_WITH_REDUCTION',
        'PARTIALLY_CHANGED', 'CHANGED', 'BLOCKED', 'EXPIRED', 'CANCELLED'
    ))
);

CREATE INDEX idx_proposals_run_status ON proposals (run_id, status);
CREATE INDEX idx_proposals_snapshot ON proposals (snapshot_id);

-- ---------------------------------------------------------- proposal_lines
--
-- One line is one shipment. The unique constraint on (proposal_id, line_id)
-- is what makes INV-12 true at the database level rather than only in code:
-- one proposal line can produce at most one command.

CREATE TABLE proposal_lines (
    id                      UUID PRIMARY KEY,
    proposal_id             UUID        NOT NULL REFERENCES proposals (id) ON DELETE CASCADE,
    line_id                 TEXT        NOT NULL,
    station_id              TEXT        NOT NULL,
    fuel_type               TEXT        NOT NULL,
    source_depot_id         TEXT        NOT NULL,
    route_id                TEXT        NOT NULL,
    -- What the operator was shown. This is the UPPER BOUND.
    reviewed_quantity       DOUBLE PRECISION NOT NULL,
    -- What was actually sent. Never greater than reviewed_quantity.
    final_quantity          DOUBLE PRECISION,
    estimated_arrival_tick  BIGINT,
    cover_before_hours      DOUBLE PRECISION,
    cover_after_hours       DOUBLE PRECISION,
    reasons                 TEXT        NOT NULL DEFAULT '',
    constraints             TEXT        NOT NULL DEFAULT '',
    alternatives            JSONB       NOT NULL DEFAULT '[]'::jsonb,
    status                  TEXT        NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_proposal_line UNIQUE (proposal_id, line_id),
    CONSTRAINT chk_line_status CHECK (status IN (
        'PENDING', 'APPROVED_AS_REVIEWED', 'APPROVED_REDUCED',
        'CHANGED', 'BLOCKED'
    )),
    -- The invariant, enforced by the database and not only by the service:
    -- a final quantity may be lower than the reviewed one, never higher.
    CONSTRAINT chk_final_not_above_reviewed
        CHECK (final_quantity IS NULL OR final_quantity <= reviewed_quantity),
    CONSTRAINT chk_final_not_negative
        CHECK (final_quantity IS NULL OR final_quantity >= 0)
);

CREATE INDEX idx_proposal_lines_proposal ON proposal_lines (proposal_id);
CREATE INDEX idx_proposal_lines_station ON proposal_lines (station_id, fuel_type);

-- --------------------------------------------------------------- commands
--
-- A command is the application's intent to make the simulator write. The
-- idempotency key and the exact request body are written HERE, before any
-- POST is attempted (INV-10). That ordering is the whole point: if the
-- process dies after the POST but before the response is read, the retry
-- must reuse the same key and the same body, and the body must already be
-- on disk.

CREATE TABLE commands (
    id                UUID PRIMARY KEY,
    run_id            TEXT        NOT NULL,
    proposal_id       UUID        NOT NULL REFERENCES proposals (id),
    proposal_line_id  UUID        NOT NULL REFERENCES proposal_lines (id),
    -- One command per proposal line. The unique constraint is INV-12.
    idempotency_key   TEXT        NOT NULL,
    -- The exact JSON body that will be POSTed. Frozen at approval time.
    request_body      JSONB       NOT NULL,
    status            TEXT        NOT NULL,
    -- Set once the simulator has confirmed. NULL while the outcome is unknown.
    simulator_allocation_id BIGINT,
    simulator_status  TEXT,
    attempts          INT         NOT NULL DEFAULT 0,
    last_error        TEXT,
    created_at        TIMESTAMPTZ NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL,
    -- The simulator allocation this command resolved to, once known.
    resolved_at       TIMESTAMPTZ,
    CONSTRAINT uq_command_line UNIQUE (proposal_line_id),
    CONSTRAINT uq_command_key UNIQUE (idempotency_key),
    CONSTRAINT chk_command_status CHECK (status IN (
        'APPROVED', 'SENDING', 'SENT', 'UNKNOWN', 'REJECTED', 'FAILED', 'CANCELLED'
    ))
);

CREATE INDEX idx_commands_run_status ON commands (run_id, status);
CREATE INDEX idx_commands_status ON commands (status);

-- ------------------------------------------------------------------ audit
--
-- Every decision an operator made, and every write the application attempted.
-- This is the record that makes "a shipment happened" answerable months later.

CREATE TABLE audit_events (
    id                UUID PRIMARY KEY,
    run_id            TEXT        NOT NULL,
    -- WHEN, WHO, WHAT, ON WHAT. The four questions any audit record must answer.
    occurred_at       TIMESTAMPTZ NOT NULL,
    actor             TEXT        NOT NULL,
    action            TEXT        NOT NULL,
    entity_type       TEXT        NOT NULL,
    entity_id         TEXT,
    outcome           TEXT        NOT NULL,
    correlation_id    TEXT,
    -- Everything the decision depended on, so the reasoning is inspectable.
    details           JSONB       NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX idx_audit_run_time ON audit_events (run_id, occurred_at DESC);
CREATE INDEX idx_audit_entity ON audit_events (entity_type, entity_id);
