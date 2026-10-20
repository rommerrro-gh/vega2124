CREATE TABLE workflow_staff (
    house_id TEXT NOT NULL REFERENCES houses(id), user_id TEXT NOT NULL REFERENCES users(id),
    function TEXT NOT NULL CHECK(function IN ('COORDINATOR','ENGINEER','WARRANTY','CONTRACTOR')),
    organization_id TEXT NOT NULL REFERENCES organizations(id), organization_label TEXT NOT NULL,
    authority TEXT NOT NULL, granted_by TEXT NOT NULL REFERENCES users(id), active INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL, PRIMARY KEY(house_id,user_id,function)
);
CREATE TABLE workflow_invitations (
    id TEXT PRIMARY KEY, token_hash TEXT NOT NULL UNIQUE, house_id TEXT NOT NULL REFERENCES houses(id),
    function TEXT NOT NULL CHECK(function IN ('COORDINATOR','ENGINEER','WARRANTY','CONTRACTOR')),
    organization_id TEXT NOT NULL REFERENCES organizations(id), organization_label TEXT NOT NULL, authority TEXT NOT NULL,
    created_by TEXT NOT NULL REFERENCES users(id), created_at TEXT NOT NULL, expires_at TEXT NOT NULL,
    accepted_by TEXT REFERENCES users(id), revoked_at TEXT
);
CREATE TABLE house_warranties (
    id TEXT PRIMARY KEY, house_id TEXT NOT NULL REFERENCES houses(id), title TEXT NOT NULL,
    object_type TEXT NOT NULL CHECK(object_type IN ('CONSTRUCTION','EQUIPMENT','FINISHING','OTHER')),
    state TEXT NOT NULL CHECK(state IN ('DRAFT','VERIFIED','SUPERSEDED')), developer TEXT, contractor TEXT,
    contract_reference TEXT, basis TEXT NOT NULL, transferred_on TEXT, starts_on TEXT, ends_on TEXT,
    checked_by TEXT REFERENCES users(id), checked_at TEXT, revision INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL, updated_at TEXT NOT NULL
);
CREATE INDEX house_warranties_house_idx ON house_warranties(house_id);
CREATE TABLE warranty_history (
    id TEXT PRIMARY KEY, warranty_id TEXT NOT NULL REFERENCES house_warranties(id), actor_id TEXT NOT NULL REFERENCES users(id),
    revision INTEGER NOT NULL, snapshot_json TEXT NOT NULL CHECK(json_valid(snapshot_json)), reason TEXT NOT NULL, created_at TEXT NOT NULL,
    UNIQUE(warranty_id,revision)
);
CREATE TABLE warranty_files (
    id TEXT PRIMARY KEY, warranty_id TEXT NOT NULL REFERENCES house_warranties(id), title TEXT NOT NULL,
    storage_key TEXT NOT NULL, mime TEXT NOT NULL, size_bytes INTEGER NOT NULL,
    uploaded_by TEXT NOT NULL REFERENCES users(id), created_at TEXT NOT NULL
);
CREATE TABLE case_warranty_reviews (
    id TEXT PRIMARY KEY, case_id TEXT NOT NULL REFERENCES resident_cases(id), warranty_id TEXT REFERENCES house_warranties(id),
    outcome TEXT NOT NULL CHECK(outcome IN ('NEED_INFORMATION','POSSIBLE_WARRANTY','WARRANTY_CONFIRMED','NON_WARRANTY','DISPUTED')),
    refusal_reason TEXT CHECK(refusal_reason IN ('NORMAL_WEAR','MISUSE','THIRD_PARTY_WORK','OUTSIDE_TERMS','OTHER')),
    basis TEXT NOT NULL, explanation TEXT NOT NULL, evidence_version_id TEXT REFERENCES case_document_versions(id),
    reviewed_by TEXT NOT NULL REFERENCES users(id), created_at TEXT NOT NULL
);
CREATE INDEX case_warranty_reviews_case_idx ON case_warranty_reviews(case_id);
CREATE TABLE case_handoffs (
    id TEXT PRIMARY KEY, case_id TEXT NOT NULL REFERENCES resident_cases(id), recipient TEXT NOT NULL,
    recipient_type TEXT NOT NULL CHECK(recipient_type IN ('DEVELOPER','GENERAL_CONTRACTOR','EXPERT','OTHER')),
    channel TEXT NOT NULL, basis TEXT NOT NULL, authority TEXT NOT NULL, statement TEXT NOT NULL,
    state TEXT NOT NULL CHECK(state IN ('PREPARED','SENT','RECEIVED','RESPONDED','CANCELLED')),
    due_at TEXT, revision INTEGER NOT NULL DEFAULT 0, created_by TEXT NOT NULL REFERENCES users(id), created_at TEXT NOT NULL
);
CREATE TABLE case_handoff_events (
    id TEXT PRIMARY KEY, handoff_id TEXT NOT NULL REFERENCES case_handoffs(id), state TEXT NOT NULL,
    note TEXT NOT NULL, evidence_version_id TEXT REFERENCES case_document_versions(id),
    actor_id TEXT NOT NULL REFERENCES users(id), created_at TEXT NOT NULL
);
CREATE TABLE case_inspections (
    id TEXT PRIMARY KEY, case_id TEXT NOT NULL REFERENCES resident_cases(id), inspector_id TEXT NOT NULL REFERENCES users(id),
    location TEXT NOT NULL, participants TEXT NOT NULL, starts_at TEXT NOT NULL, ends_at TEXT NOT NULL,
    state TEXT NOT NULL CHECK(state IN ('PROPOSED','SCHEDULED','COMPLETED','NO_ACCESS','CANCELLED')),
    resident_availability TEXT NOT NULL DEFAULT 'PENDING' CHECK(resident_availability IN ('PENDING','CONFIRMED','REQUEST_OTHER_TIME')),
    revision INTEGER NOT NULL DEFAULT 0, created_by TEXT NOT NULL REFERENCES users(id), created_at TEXT NOT NULL
);
CREATE TABLE inspection_acts (
    id TEXT PRIMARY KEY, inspection_id TEXT NOT NULL REFERENCES case_inspections(id), version INTEGER NOT NULL,
    state TEXT NOT NULL CHECK(state IN ('DRAFT','PUBLISHED')), inspected_at TEXT NOT NULL,
    participants TEXT NOT NULL, findings TEXT NOT NULL, defects TEXT NOT NULL, preliminary_cause TEXT,
    evidence_version_id TEXT REFERENCES case_document_versions(id), correction_reason TEXT,
    created_by TEXT NOT NULL REFERENCES users(id), created_at TEXT NOT NULL, published_at TEXT,
    UNIQUE(inspection_id,version)
);
CREATE TABLE inspection_responses (
    id TEXT PRIMARY KEY, inspection_id TEXT NOT NULL REFERENCES case_inspections(id), act_id TEXT REFERENCES inspection_acts(id),
    action TEXT NOT NULL CHECK(action IN ('CONFIRM_TIME','REQUEST_OTHER_TIME','COMMENT_ACT')),
    comment TEXT NOT NULL, actor_id TEXT NOT NULL REFERENCES users(id), created_at TEXT NOT NULL
);
CREATE TABLE case_work_orders (
    id TEXT PRIMARY KEY, case_id TEXT NOT NULL REFERENCES resident_cases(id), title TEXT NOT NULL, scope TEXT NOT NULL,
    assignee_id TEXT NOT NULL REFERENCES users(id), basis_type TEXT NOT NULL CHECK(basis_type IN ('WARRANTY','MAINTENANCE','OTHER')),
    basis TEXT NOT NULL, due_at TEXT, due_basis TEXT NOT NULL, act_id TEXT REFERENCES inspection_acts(id),
    state TEXT NOT NULL CHECK(state IN ('ASSIGNED','IN_PROGRESS','BLOCKED','REPORTED','NEED_REWORK','ACCEPTED','CANCELLED')),
    revision INTEGER NOT NULL DEFAULT 0, created_by TEXT NOT NULL REFERENCES users(id), created_at TEXT NOT NULL, updated_at TEXT NOT NULL
);
CREATE INDEX case_work_orders_case_idx ON case_work_orders(case_id);
CREATE TABLE work_order_reports (
    id TEXT PRIMARY KEY, work_order_id TEXT NOT NULL REFERENCES case_work_orders(id), version INTEGER NOT NULL,
    report TEXT NOT NULL, evidence_version_id TEXT REFERENCES case_document_versions(id),
    created_by TEXT NOT NULL REFERENCES users(id), created_at TEXT NOT NULL, UNIQUE(work_order_id,version)
);
CREATE TABLE workflow_history (
    id TEXT PRIMARY KEY, case_id TEXT NOT NULL REFERENCES resident_cases(id), entity_id TEXT NOT NULL,
    action TEXT NOT NULL, note TEXT NOT NULL, actor_id TEXT NOT NULL REFERENCES users(id), created_at TEXT NOT NULL
);

-- One person may combine engineering and coordination without replacing a prior assignment.
CREATE TABLE case_collaborators_v15 (
    case_id TEXT NOT NULL REFERENCES resident_cases(id), user_id TEXT NOT NULL REFERENCES users(id),
    function TEXT NOT NULL CHECK(function IN ('COORDINATOR','ENGINEER','WARRANTY','CONTRACTOR','BILLING')),
    assigned_by TEXT NOT NULL REFERENCES users(id), created_at TEXT NOT NULL, source_organization_id TEXT REFERENCES organizations(id),
    PRIMARY KEY(case_id,user_id,function)
);
INSERT INTO case_collaborators_v15(case_id,user_id,function,assigned_by,created_at,source_organization_id)
SELECT c.case_id,c.user_id,c.function,c.assigned_by,c.created_at,
    (SELECT a.organization_id FROM uk_admin_houses a JOIN resident_cases r ON r.house_id=a.house_id
      WHERE a.user_id=c.assigned_by AND r.id=c.case_id LIMIT 1)
FROM case_collaborators c;
DROP TABLE case_collaborators;
ALTER TABLE case_collaborators_v15 RENAME TO case_collaborators;

CREATE INDEX workflow_staff_user_idx ON workflow_staff(user_id,active,house_id);
CREATE INDEX workflow_invitations_house_idx ON workflow_invitations(house_id);
CREATE INDEX case_inspections_case_idx ON case_inspections(case_id,state);
CREATE INDEX case_handoffs_case_idx ON case_handoffs(case_id,state);
CREATE INDEX workflow_history_case_idx ON workflow_history(case_id);
