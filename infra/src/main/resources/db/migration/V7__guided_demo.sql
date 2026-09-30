CREATE TABLE guided_demo_sessions (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(id),
    organization_id TEXT NOT NULL REFERENCES organizations(id),
    house_id TEXT NOT NULL REFERENCES houses(id),
    previous_house_id TEXT REFERENCES houses(id),
    active INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1)),
    created_at TEXT NOT NULL
);
CREATE UNIQUE INDEX guided_demo_active_user ON guided_demo_sessions(user_id) WHERE active = 1;
