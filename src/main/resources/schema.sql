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
    created_at         TEXT    NOT NULL
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
