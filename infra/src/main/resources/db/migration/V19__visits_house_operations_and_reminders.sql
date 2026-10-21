CREATE TABLE case_visits (
 id TEXT PRIMARY KEY, case_id TEXT NOT NULL REFERENCES resident_cases(id), source_organization_id TEXT NOT NULL REFERENCES organizations(id),
 purpose TEXT NOT NULL, worker_id TEXT NOT NULL REFERENCES users(id),
 state TEXT NOT NULL CHECK(state IN ('OFFERED','CONFIRMED','CHANGE_REQUESTED','COMPLETED','CANCELLED')),
 selected_slot_id TEXT REFERENCES visit_slots(id), resident_note TEXT, report TEXT, revision INTEGER NOT NULL DEFAULT 0,
 created_by TEXT NOT NULL REFERENCES users(id), created_at TEXT NOT NULL, updated_at TEXT NOT NULL
);
CREATE UNIQUE INDEX one_open_case_visit ON case_visits(case_id) WHERE state IN ('OFFERED','CONFIRMED','CHANGE_REQUESTED');
CREATE TABLE visit_slots (
 id TEXT PRIMARY KEY, visit_id TEXT NOT NULL REFERENCES case_visits(id), starts_at TEXT NOT NULL, ends_at TEXT NOT NULL
);
CREATE INDEX visits_worker ON case_visits(worker_id,state);
CREATE TABLE house_mass_problems (
 id TEXT PRIMARY KEY, house_id TEXT NOT NULL REFERENCES houses(id), source_organization_id TEXT NOT NULL REFERENCES organizations(id),
 title TEXT NOT NULL, scope TEXT NOT NULL, summary TEXT NOT NULL, next_step TEXT NOT NULL,
 state TEXT NOT NULL CHECK(state IN ('DRAFT','ACTIVE','RESOLVED','CANCELLED')), due_at TEXT, due_basis TEXT NOT NULL,
 revision INTEGER NOT NULL DEFAULT 0, created_by TEXT NOT NULL REFERENCES users(id),created_at TEXT NOT NULL,updated_at TEXT NOT NULL
);
CREATE TABLE case_mass_links (
 case_id TEXT PRIMARY KEY REFERENCES resident_cases(id), problem_id TEXT NOT NULL REFERENCES house_mass_problems(id),
 linked_by TEXT NOT NULL REFERENCES users(id), created_at TEXT NOT NULL
);
CREATE TABLE mass_work_orders (
 id TEXT PRIMARY KEY, problem_id TEXT NOT NULL REFERENCES house_mass_problems(id), title TEXT NOT NULL, scope TEXT NOT NULL,
 worker_id TEXT NOT NULL REFERENCES users(id), due_at TEXT, due_basis TEXT NOT NULL,
 state TEXT NOT NULL CHECK(state IN ('ASSIGNED','IN_PROGRESS','BLOCKED','REPORTED','ACCEPTED','REWORK','CANCELLED')),
 report TEXT, reviewed_by TEXT REFERENCES users(id), revision INTEGER NOT NULL DEFAULT 0,
 created_at TEXT NOT NULL,updated_at TEXT NOT NULL
);
CREATE TABLE house_operations_notices (
 id TEXT PRIMARY KEY,house_id TEXT NOT NULL REFERENCES houses(id),source_organization_id TEXT NOT NULL REFERENCES organizations(id),
 title TEXT NOT NULL,kind TEXT NOT NULL CHECK(kind IN ('PLANNED_WORK','OUTAGE')),
 resource TEXT NOT NULL CHECK(resource IN ('WATER','HOT_WATER','ELECTRICITY','HEATING','GAS','LIFT','OTHER')),
 scope TEXT NOT NULL,summary TEXT NOT NULL,advice TEXT NOT NULL,contact TEXT NOT NULL,source_reference TEXT NOT NULL,
 starts_at TEXT NOT NULL,ends_at TEXT,end_basis TEXT NOT NULL,
 state TEXT NOT NULL CHECK(state IN ('DRAFT','PUBLISHED','IN_PROGRESS','COMPLETED','CANCELLED','WITHDRAWN')),
 published_at TEXT,revision INTEGER NOT NULL DEFAULT 0,created_by TEXT NOT NULL REFERENCES users(id),created_at TEXT NOT NULL,updated_at TEXT NOT NULL
);
CREATE TABLE operations_history (
 id TEXT PRIMARY KEY,house_id TEXT NOT NULL REFERENCES houses(id),case_id TEXT REFERENCES resident_cases(id),entity_id TEXT NOT NULL,
 action TEXT NOT NULL,note TEXT NOT NULL,public_note TEXT,snapshot_json TEXT NOT NULL CHECK(json_valid(snapshot_json)),
 actor_id TEXT NOT NULL REFERENCES users(id),created_at TEXT NOT NULL
);
CREATE INDEX operations_history_entity ON operations_history(entity_id,created_at);
CREATE TABLE house_update_subscriptions (
 house_id TEXT NOT NULL REFERENCES houses(id),user_id TEXT NOT NULL REFERENCES users(id),
 notices INTEGER NOT NULL DEFAULT 0,payments INTEGER NOT NULL DEFAULT 0,mass_problems INTEGER NOT NULL DEFAULT 0,
 updated_at TEXT NOT NULL,PRIMARY KEY(house_id,user_id)
);
CREATE TABLE house_reminder_policies (
 house_id TEXT PRIMARY KEY REFERENCES houses(id),source_organization_id TEXT NOT NULL REFERENCES organizations(id),
 enabled INTEGER NOT NULL DEFAULT 0,before_hours INTEGER NOT NULL,repeat_hours INTEGER NOT NULL,escalate_hours INTEGER NOT NULL,
 manager_id TEXT NOT NULL REFERENCES users(id),revision INTEGER NOT NULL DEFAULT 0,updated_by TEXT NOT NULL REFERENCES users(id),updated_at TEXT NOT NULL
);
CREATE TABLE operations_notifications (
 id TEXT PRIMARY KEY,user_id TEXT NOT NULL REFERENCES users(id),house_id TEXT NOT NULL REFERENCES houses(id),case_id TEXT REFERENCES resident_cases(id),
 kind TEXT NOT NULL CHECK(kind IN ('VISIT','NOTICE','PAYMENT','MASS','REMINDER','ESCALATION')),
 entity_id TEXT NOT NULL,entity_revision INTEGER NOT NULL DEFAULT 0,dedupe_key TEXT NOT NULL,body TEXT NOT NULL,
 source_organization_id TEXT NOT NULL REFERENCES organizations(id),
 deadline_kind TEXT CHECK(deadline_kind IS NULL OR deadline_kind IN ('CASE_STEP','WORK_ORDER','EXCHANGE_PACKET','LEGACY_HANDOFF','BILLING_CORRECTION','VISIT','SHARED_WORK')),
 due_at TEXT,created_at TEXT NOT NULL,read_at TEXT,sent_at TEXT,cancelled_at TEXT,
 attempts INTEGER NOT NULL DEFAULT 0,next_attempt_at TEXT NOT NULL,lease_token TEXT,lease_until TEXT,last_error TEXT,
 UNIQUE(user_id,dedupe_key)
);
CREATE INDEX operations_notifications_pending ON operations_notifications(sent_at,cancelled_at,next_attempt_at);
CREATE TABLE automatic_case_escalations (
 case_id TEXT NOT NULL REFERENCES resident_cases(id),entity_id TEXT NOT NULL,due_at TEXT NOT NULL,
 manager_id TEXT NOT NULL REFERENCES users(id),escalation_id TEXT NOT NULL REFERENCES case_escalations(id),
 PRIMARY KEY(case_id,entity_id,due_at,manager_id)
);

CREATE INDEX mass_problem_house ON house_mass_problems(house_id,state);
CREATE INDEX mass_links_problem ON case_mass_links(problem_id);
CREATE INDEX notices_house ON house_operations_notices(house_id,starts_at);
CREATE INDEX operations_inbox_user ON operations_notifications(user_id,created_at);
