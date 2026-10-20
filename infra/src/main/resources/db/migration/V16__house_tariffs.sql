CREATE TABLE tariff_permissions (
 house_id TEXT NOT NULL REFERENCES houses(id), user_id TEXT NOT NULL REFERENCES users(id),
 role TEXT NOT NULL CHECK(role IN ('EDITOR','PUBLISHER')), organization_id TEXT NOT NULL REFERENCES organizations(id),
 granted_by TEXT NOT NULL REFERENCES users(id), granted_at TEXT NOT NULL,
 PRIMARY KEY(house_id,user_id,role)
);
CREATE TABLE house_tariffs (
 id TEXT PRIMARY KEY, house_id TEXT NOT NULL REFERENCES houses(id), series_id TEXT NOT NULL,
 replaces_id TEXT REFERENCES house_tariffs(id), state TEXT NOT NULL DEFAULT 'DRAFT' CHECK(state IN ('DRAFT','PUBLISHED','SUPERSEDED','WITHDRAWN')),
 title TEXT NOT NULL, category TEXT NOT NULL CHECK(category IN ('UTILITY','HOUSING','CAPITAL_REPAIR','OTHER')),
 provider TEXT NOT NULL, applicability TEXT NOT NULL, unit TEXT NOT NULL CHECK(unit IN ('M3','KWH','GCAL','M2','PERSON','MONTH','OTHER')),
 unit_label TEXT NOT NULL, amount TEXT NOT NULL, valid_from TEXT NOT NULL, valid_until TEXT,
 source_reference TEXT NOT NULL, source_url TEXT, explanation TEXT NOT NULL,
 edited_by TEXT NOT NULL REFERENCES users(id), published_by TEXT REFERENCES users(id), published_at TEXT,
 revision INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL, updated_at TEXT NOT NULL
);
CREATE INDEX idx_tariffs_schedule ON house_tariffs(house_id,series_id,valid_from,state);
CREATE TABLE tariff_files (
 id TEXT PRIMARY KEY, tariff_id TEXT NOT NULL REFERENCES house_tariffs(id), title TEXT NOT NULL,
 storage_key TEXT NOT NULL, mime TEXT NOT NULL, size_bytes INTEGER NOT NULL,
 uploaded_by TEXT NOT NULL REFERENCES users(id), created_at TEXT NOT NULL
);
CREATE TABLE tariff_history (
 id TEXT PRIMARY KEY, tariff_id TEXT NOT NULL REFERENCES house_tariffs(id), revision INTEGER NOT NULL,
 actor_id TEXT NOT NULL REFERENCES users(id), action TEXT NOT NULL, reason TEXT NOT NULL,
 snapshot_json TEXT NOT NULL, created_at TEXT NOT NULL,
 UNIQUE(tariff_id,revision)
);
