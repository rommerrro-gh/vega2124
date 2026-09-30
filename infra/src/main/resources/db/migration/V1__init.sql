PRAGMA journal_mode=WAL;
PRAGMA busy_timeout=5000;
PRAGMA foreign_keys=ON;

CREATE TABLE users
(
    id           TEXT PRIMARY KEY,
    max_user_id  TEXT UNIQUE,
    display_name TEXT NOT NULL,
    phone_enc    TEXT,
    status       TEXT NOT NULL DEFAULT 'ACTIVE',
    created_at   TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE organizations
(
    id            TEXT PRIMARY KEY,
    type          TEXT NOT NULL,
    ogrn          TEXT,
    inn           TEXT,
    name          TEXT NOT NULL,
    contacts_json TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(contacts_json))
);
CREATE TABLE houses
(
    id               TEXT PRIMARY KEY,
    fias_guid        TEXT,
    gis_guid         TEXT,
    cadastral_number TEXT,
    oktmo            TEXT,
    address          TEXT NOT NULL,
    timezone         TEXT NOT NULL DEFAULT 'Europe/Moscow'
);
CREATE TABLE house_memberships
(
    house_id            TEXT NOT NULL REFERENCES houses (id),
    user_id             TEXT NOT NULL REFERENCES users (id),
    role                TEXT NOT NULL,
    verification_status TEXT NOT NULL,
    unit                TEXT,
    notifications_json  TEXT NOT NULL DEFAULT '{}' CHECK (json_valid(notifications_json)),
    PRIMARY KEY (house_id, user_id, role)
);
CREATE TABLE house_invitations
(
    id               TEXT PRIMARY KEY,
    house_id         TEXT    NOT NULL REFERENCES houses (id),
    token_hash       TEXT    NOT NULL UNIQUE,
    expires_at       TEXT    NOT NULL,
    activation_limit INTEGER NOT NULL,
    activation_count INTEGER NOT NULL DEFAULT 0,
    revoked_at       TEXT
);
CREATE TABLE organization_memberships
(
    organization_id TEXT NOT NULL REFERENCES organizations (id),
    user_id         TEXT NOT NULL REFERENCES users (id),
    role            TEXT NOT NULL,
    house_scope     TEXT,
    PRIMARY KEY (organization_id, user_id, role)
);
CREATE TABLE chat_bindings
(
    chat_id          TEXT PRIMARY KEY,
    house_id         TEXT    NOT NULL REFERENCES houses (id),
    permissions_json TEXT    NOT NULL DEFAULT '{}' CHECK (json_valid(permissions_json)),
    active           INTEGER NOT NULL DEFAULT 1
);
CREATE TABLE reports
(
    id                   TEXT PRIMARY KEY,
    house_id             TEXT NOT NULL REFERENCES houses (id),
    author_id            TEXT NOT NULL REFERENCES users (id),
    raw_text             TEXT NOT NULL,
    original_max_payload TEXT,
    normalized_json      TEXT CHECK (normalized_json IS NULL OR json_valid(normalized_json)),
    search_text          TEXT,
    category             TEXT,
    zone_json            TEXT CHECK (zone_json IS NULL OR json_valid(zone_json)),
    utility_service      TEXT,
    utility_scale        TEXT,
    occurred_at          TEXT,
    responsible_party    TEXT,
    withdrawn_at         TEXT,
    withdraw_reason      TEXT,
    created_at           TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE issues
(
    id              TEXT PRIMARY KEY,
    house_id        TEXT NOT NULL REFERENCES houses (id),
    category        TEXT,
    zone_json       TEXT CHECK (zone_json IS NULL OR json_valid(zone_json)),
    status          TEXT NOT NULL,
    priority        TEXT,
    sla_due_at      TEXT,
    organization_id TEXT REFERENCES organizations (id),
    dispatcher_id   TEXT REFERENCES users (id),
    executor_id     TEXT REFERENCES users (id),
    created_at      TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE issue_reports
(
    id          TEXT PRIMARY KEY,
    issue_id    TEXT NOT NULL REFERENCES issues (id),
    report_id   TEXT NOT NULL REFERENCES reports (id),
    link_reason TEXT,
    score       REAL,
    linked_at   TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    unlinked_at TEXT
);
CREATE UNIQUE INDEX uq_active_issue_per_report ON issue_reports (report_id) WHERE unlinked_at IS NULL;
CREATE TABLE issue_participants
(
    issue_id          TEXT NOT NULL REFERENCES issues (id),
    user_id           TEXT NOT NULL REFERENCES users (id),
    confirmation_type TEXT,
    confirmed_at      TEXT,
    PRIMARY KEY (issue_id, user_id)
);
CREATE TABLE incidents
(
    id         TEXT PRIMARY KEY,
    type       TEXT NOT NULL,
    territory  TEXT,
    status     TEXT NOT NULL,
    created_by TEXT REFERENCES users (id),
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE incident_issues
(
    incident_id TEXT NOT NULL REFERENCES incidents (id),
    issue_id    TEXT NOT NULL REFERENCES issues (id),
    PRIMARY KEY (incident_id, issue_id)
);
CREATE TABLE comments
(
    id         TEXT PRIMARY KEY,
    issue_id   TEXT NOT NULL REFERENCES issues (id),
    author_id  TEXT NOT NULL REFERENCES users (id),
    text       TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    hidden_at  TEXT
);
CREATE TABLE external_tickets
(
    id              TEXT PRIMARY KEY,
    issue_id        TEXT NOT NULL REFERENCES issues (id),
    organization_id TEXT NOT NULL REFERENCES organizations (id),
    adapter         TEXT NOT NULL,
    external_id     TEXT,
    transfer_status TEXT NOT NULL,
    official_status TEXT,
    synced_at       TEXT,
    UNIQUE (adapter, organization_id, external_id)
);
CREATE TABLE attachments
(
    id            TEXT PRIMARY KEY,
    report_id     TEXT NOT NULL REFERENCES reports (id),
    storage_key   TEXT NOT NULL,
    mime          TEXT NOT NULL,
    hashes_json   TEXT CHECK (hashes_json IS NULL OR json_valid(hashes_json)),
    metadata_json TEXT CHECK (metadata_json IS NULL OR json_valid(metadata_json)),
    created_at    TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE status_events
(
    id          TEXT PRIMARY KEY,
    issue_id    TEXT NOT NULL REFERENCES issues (id),
    from_status TEXT,
    to_status   TEXT NOT NULL,
    actor_id    TEXT REFERENCES users (id),
    reason      TEXT,
    created_at  TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE house_fields
(
    house_id   TEXT NOT NULL REFERENCES houses (id),
    key        TEXT NOT NULL,
    value_json TEXT NOT NULL CHECK (json_valid(value_json)),
    source     TEXT NOT NULL,
    source_url TEXT,
    fetched_at TEXT NOT NULL,
    valid_at   TEXT,
    confidence REAL,
    PRIMARY KEY (house_id, key)
);
CREATE TABLE house_events
(
    id         TEXT PRIMARY KEY,
    house_id   TEXT NOT NULL REFERENCES houses (id),
    type       TEXT NOT NULL,
    title      TEXT NOT NULL,
    body       TEXT,
    starts_at  TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE announcements
(
    id         TEXT PRIMARY KEY,
    house_id   TEXT NOT NULL REFERENCES houses (id),
    author_id  TEXT NOT NULL REFERENCES users (id),
    title      TEXT NOT NULL,
    body       TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE polls
(
    id                         TEXT PRIMARY KEY,
    house_id                   TEXT    NOT NULL REFERENCES houses (id),
    question                   TEXT    NOT NULL,
    is_official                INTEGER NOT NULL DEFAULT 0 CHECK (is_official = 0),
    results_hidden_until_close INTEGER NOT NULL DEFAULT 0,
    closes_at                  TEXT,
    created_at                 TEXT    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE poll_options
(
    id       TEXT PRIMARY KEY,
    poll_id  TEXT    NOT NULL REFERENCES polls (id),
    label    TEXT    NOT NULL,
    position INTEGER NOT NULL,
    UNIQUE (poll_id, position)
);
CREATE TABLE poll_votes
(
    poll_id   TEXT NOT NULL REFERENCES polls (id),
    user_id   TEXT NOT NULL REFERENCES users (id),
    option_id TEXT NOT NULL REFERENCES poll_options (id),
    voted_at  TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (poll_id, user_id)
);
CREATE TABLE audit_events
(
    id          TEXT PRIMARY KEY,
    actor_id    TEXT,
    action      TEXT NOT NULL,
    entity      TEXT NOT NULL,
    entity_id   TEXT,
    before_json TEXT CHECK (before_json IS NULL OR json_valid(before_json)),
    after_json  TEXT CHECK (after_json IS NULL OR json_valid(after_json)),
    created_at  TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE
VIRTUAL TABLE reports_fts USING fts5(search_text, content='reports', content_rowid='rowid', tokenize='unicode61');
CREATE TRIGGER reports_fts_insert
    AFTER INSERT
    ON reports BEGIN
INSERT INTO reports_fts(rowid, search_text)
VALUES (new.rowid, new.search_text);
END;
CREATE TRIGGER reports_fts_delete
    AFTER DELETE
    ON reports BEGIN
INSERT INTO reports_fts(reports_fts, rowid, search_text)
VALUES ('delete', old.rowid, old.search_text);
END;
CREATE TRIGGER reports_fts_update
    AFTER UPDATE OF search_text ON reports BEGIN
INSERT INTO reports_fts(reports_fts, rowid, search_text)
VALUES ('delete', old.rowid, old.search_text);
INSERT INTO reports_fts(rowid, search_text)
VALUES (new.rowid, new.search_text);
END;
