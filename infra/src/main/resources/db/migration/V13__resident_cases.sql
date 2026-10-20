-- Additive: legacy reports, issues, invitations and stored files are unchanged.
CREATE TABLE resident_cases (
    number INTEGER PRIMARY KEY AUTOINCREMENT,
    id TEXT NOT NULL UNIQUE,
    house_id TEXT NOT NULL REFERENCES houses(id),
    author_id TEXT NOT NULL REFERENCES users(id),
    kind TEXT NOT NULL CHECK (kind IN ('GENERAL','PROBLEM','PAYMENT','WARRANTY')),
    status TEXT NOT NULL CHECK (status IN ('RECEIVED','IN_REVIEW','WAITING_RESIDENT','IN_PROGRESS','RESULT_PROPOSED','CLOSED','REOPENED')),
    description TEXT NOT NULL,
    location TEXT,
    client_request_id TEXT NOT NULL,
    request_hash TEXT NOT NULL,
    responsible_user_id TEXT REFERENCES users(id),
    next_step TEXT NOT NULL,
    due_at TEXT,
    revision INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    UNIQUE (author_id, client_request_id)
);
CREATE INDEX resident_cases_house_idx ON resident_cases(house_id, updated_at);
CREATE INDEX resident_cases_author_idx ON resident_cases(author_id, updated_at);

CREATE TABLE case_billing_permissions (
    house_id TEXT NOT NULL REFERENCES houses(id),
    user_id TEXT NOT NULL REFERENCES users(id),
    granted_by TEXT NOT NULL REFERENCES users(id),
    created_at TEXT NOT NULL,
    PRIMARY KEY (house_id, user_id)
);
CREATE TABLE case_collaborators (
    case_id TEXT NOT NULL REFERENCES resident_cases(id),
    user_id TEXT NOT NULL REFERENCES users(id),
    function TEXT NOT NULL CHECK (function IN ('COORDINATOR','ENGINEER','WARRANTY','CONTRACTOR','BILLING')),
    assigned_by TEXT NOT NULL REFERENCES users(id),
    created_at TEXT NOT NULL,
    PRIMARY KEY (case_id, user_id)
);
CREATE TABLE case_events (
    id TEXT PRIMARY KEY,
    case_id TEXT NOT NULL REFERENCES resident_cases(id),
    actor_id TEXT NOT NULL REFERENCES users(id),
    type TEXT NOT NULL CHECK (type IN ('REGISTERED','ASSIGNED','UPDATED','MESSAGE','DOCUMENT','REOPENED','CONFIRMED')),
    text TEXT NOT NULL,
    client_request_id TEXT,
    created_at TEXT NOT NULL,
    UNIQUE (case_id, actor_id, client_request_id)
);
CREATE INDEX case_events_case_idx ON case_events(case_id, created_at);
CREATE TABLE case_documents (
    id TEXT PRIMARY KEY,
    case_id TEXT NOT NULL REFERENCES resident_cases(id),
    title TEXT NOT NULL,
    created_at TEXT NOT NULL
);
CREATE TABLE case_document_versions (
    id TEXT PRIMARY KEY,
    document_id TEXT NOT NULL REFERENCES case_documents(id),
    version INTEGER NOT NULL CHECK (version > 0),
    uploaded_by TEXT NOT NULL REFERENCES users(id),
    storage_key TEXT NOT NULL UNIQUE,
    mime TEXT NOT NULL CHECK (mime IN ('application/pdf','image/jpeg','image/png')),
    sha256 TEXT NOT NULL,
    size_bytes INTEGER NOT NULL,
    created_at TEXT NOT NULL,
    UNIQUE (document_id, version)
);
