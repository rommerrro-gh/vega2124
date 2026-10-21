CREATE TABLE exchange_channels (
 id TEXT PRIMARY KEY, house_id TEXT NOT NULL REFERENCES houses(id), source_organization_id TEXT NOT NULL REFERENCES organizations(id),
 organization_name TEXT NOT NULL, participant_type TEXT NOT NULL CHECK(participant_type IN ('UK','WARRANTY','CONTRACTOR','BILLING','OTHER')),
 mode TEXT NOT NULL CHECK(mode IN ('MANUAL','PORTAL')), contact TEXT NOT NULL, source_reference TEXT NOT NULL,
 disclosure_scope TEXT NOT NULL, state TEXT NOT NULL CHECK(state IN ('DRAFT','VERIFIED','RETIRED')),
 checked_by TEXT REFERENCES users(id), checked_at TEXT, revision INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL
);
CREATE TABLE exchange_packets (
 id TEXT PRIMARY KEY, case_id TEXT NOT NULL REFERENCES resident_cases(id), channel_id TEXT NOT NULL REFERENCES exchange_channels(id),
 channel_snapshot TEXT CHECK(channel_snapshot IS NULL OR json_valid(channel_snapshot)), source_organization_id TEXT NOT NULL REFERENCES organizations(id),
 subject TEXT NOT NULL, statement TEXT NOT NULL, disclosure_basis TEXT NOT NULL, authority TEXT NOT NULL,
 materials_note TEXT NOT NULL, include_address INTEGER NOT NULL, address_snapshot TEXT,
 state TEXT NOT NULL CHECK(state IN ('DRAFT','READY','SENT','RECEIVED','ANSWERED','REVIEWED','CANCELLED')),
 due_at TEXT, due_basis TEXT NOT NULL, created_by TEXT NOT NULL REFERENCES users(id),
 revision INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL, updated_at TEXT NOT NULL
);
CREATE INDEX exchange_packets_case ON exchange_packets(case_id,state);
CREATE TABLE exchange_packet_files (
 id TEXT PRIMARY KEY, packet_id TEXT NOT NULL REFERENCES exchange_packets(id), version_id TEXT NOT NULL REFERENCES case_document_versions(id),
 title TEXT NOT NULL, mime TEXT NOT NULL, sha256 TEXT NOT NULL, size_bytes INTEGER NOT NULL, storage_key TEXT NOT NULL,
 UNIQUE(packet_id,version_id)
);
CREATE TABLE exchange_invitations (
 id TEXT PRIMARY KEY, packet_id TEXT NOT NULL REFERENCES exchange_packets(id), token_hash TEXT NOT NULL UNIQUE,
 recipient_name TEXT NOT NULL, participation_basis TEXT NOT NULL, expires_at TEXT NOT NULL,
 created_by TEXT NOT NULL REFERENCES users(id), created_at TEXT NOT NULL, accepted_by TEXT REFERENCES users(id), revoked_at TEXT
);
CREATE TABLE exchange_replies (
 id TEXT PRIMARY KEY, packet_id TEXT NOT NULL REFERENCES exchange_packets(id), version INTEGER NOT NULL,
 state TEXT NOT NULL CHECK(state IN ('DRAFT','PUBLISHED','RETURNED','ACCEPTED')),
 origin TEXT NOT NULL CHECK(origin IN ('RECIPIENT','MANUAL')), answer TEXT NOT NULL,
 author_id TEXT NOT NULL REFERENCES users(id), created_at TEXT NOT NULL, published_at TEXT,
 review_note TEXT, resident_summary TEXT, reviewed_by TEXT REFERENCES users(id), reviewed_at TEXT,
 UNIQUE(packet_id,version)
);
CREATE TABLE exchange_reply_files (
 id TEXT PRIMARY KEY, reply_id TEXT NOT NULL REFERENCES exchange_replies(id), title TEXT NOT NULL,
 mime TEXT NOT NULL, sha256 TEXT NOT NULL, size_bytes INTEGER NOT NULL, storage_key TEXT NOT NULL,
 imported_version_id TEXT REFERENCES case_document_versions(id), uploaded_at TEXT NOT NULL
);
CREATE TABLE exchange_history (
 id TEXT PRIMARY KEY, packet_id TEXT NOT NULL REFERENCES exchange_packets(id), action TEXT NOT NULL,
 note TEXT NOT NULL, evidence_version_id TEXT REFERENCES case_document_versions(id), actor_id TEXT NOT NULL REFERENCES users(id),
 snapshot_json TEXT NOT NULL CHECK(json_valid(snapshot_json)), created_at TEXT NOT NULL
);
CREATE TABLE case_followup (
 case_id TEXT PRIMARY KEY REFERENCES resident_cases(id), deputy_id TEXT REFERENCES users(id),
 source_organization_id TEXT NOT NULL REFERENCES organizations(id), updated_by TEXT NOT NULL REFERENCES users(id), updated_at TEXT NOT NULL
);
CREATE TABLE case_control_grants (
 case_id TEXT NOT NULL REFERENCES resident_cases(id), user_id TEXT NOT NULL REFERENCES users(id),
 PRIMARY KEY(case_id,user_id)
);
CREATE TABLE case_escalations (
 id TEXT PRIMARY KEY, case_id TEXT NOT NULL REFERENCES resident_cases(id), target_id TEXT NOT NULL REFERENCES users(id),
 state TEXT NOT NULL CHECK(state IN ('OPEN','ACKNOWLEDGED','CLOSED')), reason TEXT NOT NULL, resolution TEXT,
 created_by TEXT NOT NULL REFERENCES users(id), created_at TEXT NOT NULL, updated_at TEXT NOT NULL
);
CREATE TABLE control_history (
 id TEXT PRIMARY KEY, house_id TEXT NOT NULL REFERENCES houses(id), case_id TEXT REFERENCES resident_cases(id),
 action TEXT NOT NULL, note TEXT NOT NULL, snapshot_json TEXT NOT NULL CHECK(json_valid(snapshot_json)),
 actor_id TEXT NOT NULL REFERENCES users(id), created_at TEXT NOT NULL
);
CREATE TABLE pilot_plans (
 house_id TEXT PRIMARY KEY REFERENCES houses(id), state TEXT NOT NULL CHECK(state IN ('PREPARING','READY_FOR_ACCEPTANCE','PAUSED')),
 scope TEXT NOT NULL, acceptance_reference TEXT NOT NULL, restore_reference TEXT NOT NULL,
 contact_reference TEXT NOT NULL, training_reference TEXT NOT NULL, rollback_plan TEXT NOT NULL,
 revision INTEGER NOT NULL DEFAULT 0, updated_by TEXT NOT NULL REFERENCES users(id), updated_at TEXT NOT NULL
);
