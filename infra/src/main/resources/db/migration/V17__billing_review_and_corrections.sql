ALTER TABLE case_billing_permissions ADD COLUMN source_organization_id TEXT REFERENCES organizations(id);
UPDATE case_billing_permissions SET source_organization_id=(SELECT a.organization_id FROM uk_admin_houses a JOIN organization_houses h ON h.house_id=a.house_id AND h.organization_id=a.organization_id WHERE a.house_id=case_billing_permissions.house_id AND a.user_id=case_billing_permissions.granted_by AND h.status='ACTIVE' AND a.access_status='ACTIVE' LIMIT 1);
CREATE TABLE billing_reviews (
 id TEXT PRIMARY KEY, case_id TEXT NOT NULL REFERENCES resident_cases(id), cycle INTEGER NOT NULL,
 state TEXT NOT NULL CHECK(state IN ('DRAFT','PUBLISHED','SUPERSEDED','CANCELLED')),
 period TEXT NOT NULL, outcome TEXT NOT NULL CHECK(outcome IN ('EXPLAINED','DISCREPANCY','NEED_INFORMATION')),
 findings TEXT NOT NULL, explanation TEXT NOT NULL, limitations TEXT NOT NULL, source_reference TEXT NOT NULL,
 evidence_version_id TEXT REFERENCES case_document_versions(id), edited_by TEXT NOT NULL REFERENCES users(id),
 published_by TEXT REFERENCES users(id), created_at TEXT NOT NULL, published_at TEXT
);
CREATE INDEX billing_reviews_case ON billing_reviews(case_id,cycle,state);
CREATE TABLE billing_review_lines (
 review_id TEXT NOT NULL REFERENCES billing_reviews(id), position INTEGER NOT NULL,
 title TEXT NOT NULL, period TEXT NOT NULL, unit TEXT NOT NULL,
 mode TEXT NOT NULL CHECK(mode IN ('MANUAL','SIMPLE_PRODUCT')), charged TEXT NOT NULL,
 quantity TEXT, rate TEXT, adjustment TEXT NOT NULL, expected TEXT, difference TEXT,
 source_reference TEXT NOT NULL, evidence_version_id TEXT REFERENCES case_document_versions(id),
 tariff_id TEXT REFERENCES house_tariffs(id), tariff_snapshot TEXT CHECK(tariff_snapshot IS NULL OR json_valid(tariff_snapshot)),
 PRIMARY KEY(review_id,position)
);
CREATE TABLE billing_questions (
 id TEXT PRIMARY KEY, case_id TEXT NOT NULL REFERENCES resident_cases(id),
 state TEXT NOT NULL CHECK(state IN ('OPEN','ANSWERED','CANCELLED')), question TEXT NOT NULL, purpose TEXT NOT NULL,
 requested_by TEXT NOT NULL REFERENCES users(id), created_at TEXT NOT NULL, response TEXT, evidence_version_id TEXT REFERENCES case_document_versions(id),
 answered_at TEXT
);
CREATE TABLE billing_corrections (
 id TEXT PRIMARY KEY, case_id TEXT NOT NULL REFERENCES resident_cases(id), review_id TEXT NOT NULL REFERENCES billing_reviews(id),
 state TEXT NOT NULL CHECK(state IN ('DRAFT','SENT','ACKNOWLEDGED','APPLIED','REWORK','VERIFIED','REFUSAL_RECEIVED','REFUSAL_REVIEWED','CANCELLED')),
 period TEXT NOT NULL, scope TEXT NOT NULL, before_amount TEXT NOT NULL, after_amount TEXT NOT NULL, delta TEXT NOT NULL,
 basis TEXT NOT NULL, recipient TEXT NOT NULL, channel TEXT NOT NULL, authority TEXT NOT NULL,
 due_at TEXT, due_basis TEXT NOT NULL, prepared_by TEXT NOT NULL REFERENCES users(id),
 actual_after TEXT, evidence_version_id TEXT REFERENCES case_document_versions(id), recorded_by TEXT REFERENCES users(id),
 created_at TEXT NOT NULL, updated_at TEXT NOT NULL
);
CREATE INDEX billing_corrections_case ON billing_corrections(case_id,state);
CREATE TABLE billing_history (
 id TEXT PRIMARY KEY, case_id TEXT NOT NULL REFERENCES resident_cases(id), entity_id TEXT NOT NULL,
 action TEXT NOT NULL, note TEXT NOT NULL, actor_id TEXT NOT NULL REFERENCES users(id),
 snapshot_json TEXT NOT NULL CHECK(json_valid(snapshot_json)), created_at TEXT NOT NULL
);
-- Keep in-progress resident drafts and replies while adding a dedicated clarification reply state.
CREATE TABLE max_bot_sessions_v17 (
 user_id TEXT PRIMARY KEY REFERENCES users(id),
 state TEXT NOT NULL CHECK(state IN ('IDLE','COMPOSE','REVIEW','REPLY','CONFIRM','REOPEN','BILLING_ANSWER')),
 house_id TEXT REFERENCES houses(id),kind TEXT NOT NULL CHECK(kind IN ('GENERAL','PROBLEM','PAYMENT')),
 draft_id TEXT NOT NULL,description TEXT NOT NULL DEFAULT '',case_id TEXT REFERENCES resident_cases(id),revision INTEGER,updated_at TEXT NOT NULL
);
INSERT INTO max_bot_sessions_v17 SELECT * FROM max_bot_sessions;
DROP TABLE max_bot_sessions;
ALTER TABLE max_bot_sessions_v17 RENAME TO max_bot_sessions;
