-- CrisisConnect database schema (SQLite)
-- DatabaseManager strips comments, splits on semicolons and executes each statement.

CREATE TABLE IF NOT EXISTS organizations (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    name          TEXT    NOT NULL UNIQUE,
    type          TEXT    NOT NULL,
    contact_phone TEXT,
    email         TEXT,
    city          TEXT    NOT NULL,
    latitude      REAL    NOT NULL,
    longitude     REAL    NOT NULL,
    created_at    TEXT    NOT NULL
);

CREATE TABLE IF NOT EXISTS resources (
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    organization_id    INTEGER NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    type               TEXT    NOT NULL,
    name               TEXT    NOT NULL,
    available_quantity INTEGER NOT NULL CHECK (available_quantity >= 0),
    total_capacity     INTEGER NOT NULL CHECK (total_capacity >= 0),
    city               TEXT    NOT NULL,
    latitude           REAL    NOT NULL,
    longitude          REAL    NOT NULL,
    expiry_date        TEXT,
    created_at         TEXT    NOT NULL
);

CREATE TABLE IF NOT EXISTS emergency_requests (
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    title              TEXT    NOT NULL,
    disaster_type      TEXT    NOT NULL,
    resource_type      TEXT    NOT NULL,
    quantity_needed    INTEGER NOT NULL CHECK (quantity_needed > 0),
    quantity_fulfilled INTEGER NOT NULL DEFAULT 0,
    people_affected    INTEGER NOT NULL DEFAULT 0,
    urgency            TEXT    NOT NULL,
    city               TEXT    NOT NULL,
    latitude           REAL    NOT NULL,
    longitude          REAL    NOT NULL,
    status             TEXT    NOT NULL DEFAULT 'OPEN',
    contact_phone      TEXT,
    vulnerable_groups  INTEGER NOT NULL DEFAULT 0,
    source             TEXT    NOT NULL DEFAULT 'DESKTOP',
    created_at         TEXT    NOT NULL,
    tracking_code      TEXT,
    verification       TEXT    NOT NULL DEFAULT 'VERIFIED',
    verified_by        TEXT,
    verified_at        TEXT,
    verification_note  TEXT
);

CREATE TABLE IF NOT EXISTS allocations (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    request_id   INTEGER NOT NULL REFERENCES emergency_requests(id) ON DELETE CASCADE,
    resource_id  INTEGER NOT NULL REFERENCES resources(id) ON DELETE CASCADE,
    quantity     INTEGER NOT NULL CHECK (quantity > 0),
    distance_km  REAL    NOT NULL,
    created_at   TEXT    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_resources_type   ON resources(type);
CREATE INDEX IF NOT EXISTS idx_requests_status  ON emergency_requests(status);
CREATE INDEX IF NOT EXISTS idx_alloc_request    ON allocations(request_id);

-- =====================================================================
--  v2: accounts, sessions, SOS tracking, notification outbox, audit log
-- =====================================================================

CREATE TABLE IF NOT EXISTS users (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    email           TEXT    NOT NULL UNIQUE,
    name            TEXT    NOT NULL,
    password_hash   TEXT    NOT NULL,
    role            TEXT    NOT NULL DEFAULT 'COORDINATOR',
    status          TEXT    NOT NULL DEFAULT 'PENDING',
    organization_id INTEGER REFERENCES organizations(id) ON DELETE SET NULL,
    email_verified  INTEGER NOT NULL DEFAULT 0,
    failed_logins   INTEGER NOT NULL DEFAULT 0,
    lock_until      TEXT,
    last_login_at   TEXT,
    created_at      TEXT    NOT NULL,
    city            TEXT,
    designation     TEXT
);

CREATE TABLE IF NOT EXISTS sessions (
    token_hash  TEXT    PRIMARY KEY,
    user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at  TEXT    NOT NULL,
    user_agent  TEXT,
    created_at  TEXT    NOT NULL
);

CREATE TABLE IF NOT EXISTS email_codes (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code_hash   TEXT    NOT NULL,
    expires_at  TEXT    NOT NULL,
    attempts    INTEGER NOT NULL DEFAULT 0,
    created_at  TEXT    NOT NULL
);

CREATE TABLE IF NOT EXISTS notifications (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    channel         TEXT    NOT NULL,
    recipient       TEXT    NOT NULL,
    recipient_name  TEXT,
    message         TEXT    NOT NULL,
    event           TEXT    NOT NULL,
    request_id      INTEGER REFERENCES emergency_requests(id) ON DELETE SET NULL,
    status          TEXT    NOT NULL,
    error           TEXT,
    created_at      TEXT    NOT NULL
);

CREATE TABLE IF NOT EXISTS audit_log (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    actor       TEXT    NOT NULL,
    action      TEXT    NOT NULL,
    details     TEXT,
    ip          TEXT,
    created_at  TEXT    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_sessions_user      ON sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_notif_request      ON notifications(request_id);
CREATE INDEX IF NOT EXISTS idx_notif_created      ON notifications(created_at);
CREATE INDEX IF NOT EXISTS idx_audit_created      ON audit_log(created_at)
