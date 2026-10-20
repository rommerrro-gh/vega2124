-- Private bot sessions and durable replies; old webhook/report records stay intact.
CREATE TABLE max_bot_sessions (
    user_id TEXT PRIMARY KEY REFERENCES users(id),
    state TEXT NOT NULL CHECK(state IN ('IDLE','COMPOSE','REVIEW','REPLY','CONFIRM','REOPEN')),
    house_id TEXT REFERENCES houses(id),
    kind TEXT NOT NULL CHECK(kind IN ('GENERAL','PROBLEM','PAYMENT')),
    draft_id TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    case_id TEXT REFERENCES resident_cases(id),
    revision INTEGER,
    updated_at TEXT NOT NULL
);
CREATE TABLE max_bot_draft_files (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(id),
    draft_id TEXT NOT NULL,
    title TEXT NOT NULL,
    mime TEXT NOT NULL CHECK(mime IN ('application/pdf','image/jpeg','image/png')),
    content BLOB NOT NULL,
    created_at TEXT NOT NULL
);
CREATE INDEX max_bot_draft_files_user_idx ON max_bot_draft_files(user_id,draft_id);
CREATE TABLE max_bot_updates (
    update_id TEXT PRIMARY KEY,
    processed_at TEXT NOT NULL
);
CREATE TABLE max_bot_outbox (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(id),
    house_id TEXT REFERENCES houses(id),
    case_id TEXT REFERENCES resident_cases(id),
    kind TEXT NOT NULL CHECK(kind IN ('REPLY','CASE_EVENT')),
    body_json TEXT NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TEXT NOT NULL,
    sent_at TEXT,
    cancelled_at TEXT,
    last_error TEXT,
    created_at TEXT NOT NULL
);
CREATE INDEX max_bot_outbox_pending_idx ON max_bot_outbox(sent_at,cancelled_at,next_attempt_at);
