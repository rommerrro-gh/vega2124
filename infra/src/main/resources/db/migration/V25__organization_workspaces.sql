CREATE TABLE organization_team (
 organization_id TEXT NOT NULL REFERENCES organizations(id), user_id TEXT NOT NULL REFERENCES users(id),
 role TEXT NOT NULL CHECK(role IN ('ADMIN','COORDINATOR','SPECIALIST')), department TEXT NOT NULL DEFAULT '',
 active INTEGER NOT NULL DEFAULT 1 CHECK(active IN (0,1)), PRIMARY KEY(organization_id,user_id,role)
);
CREATE TABLE organization_team_functions (
 organization_id TEXT NOT NULL REFERENCES organizations(id), user_id TEXT NOT NULL REFERENCES users(id),
 function TEXT NOT NULL CHECK(function IN ('ENGINEER','WARRANTY','CONTRACTOR')),
 PRIMARY KEY(organization_id,user_id,function)
);
CREATE TABLE organization_work_houses (
 organization_id TEXT NOT NULL REFERENCES organizations(id), house_id TEXT NOT NULL REFERENCES houses(id),
 active INTEGER NOT NULL DEFAULT 1 CHECK(active IN (0,1)), PRIMARY KEY(organization_id,house_id)
);
CREATE TABLE organization_team_invitations (
 id TEXT PRIMARY KEY, token_hash TEXT NOT NULL UNIQUE, organization_id TEXT NOT NULL REFERENCES organizations(id),
 role TEXT NOT NULL CHECK(role IN ('ADMIN','COORDINATOR','SPECIALIST')), department TEXT NOT NULL DEFAULT '',
 functions_json TEXT NOT NULL CHECK(json_valid(functions_json)), created_by TEXT NOT NULL REFERENCES users(id),
 expires_at TEXT NOT NULL, accepted_by TEXT REFERENCES users(id), revoked INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE organization_case_transfers (
 id TEXT PRIMARY KEY, case_id TEXT NOT NULL REFERENCES resident_cases(id), from_organization_id TEXT NOT NULL REFERENCES organizations(id),
 to_organization_id TEXT NOT NULL REFERENCES organizations(id), state TEXT NOT NULL CHECK(state IN ('OFFERED','ACCEPTED','RETURNED','REFUSED')),
 reason TEXT NOT NULL, reply TEXT NOT NULL DEFAULT '', revision INTEGER NOT NULL DEFAULT 0,
 created_by TEXT NOT NULL REFERENCES users(id), accepted_by TEXT REFERENCES users(id), created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
 CHECK(from_organization_id <> to_organization_id)
);
CREATE UNIQUE INDEX organization_transfer_pending ON organization_case_transfers(case_id) WHERE state='OFFERED';
CREATE TABLE organization_transfer_history (
 id TEXT PRIMARY KEY, transfer_id TEXT NOT NULL REFERENCES organization_case_transfers(id), state TEXT NOT NULL,
 note TEXT NOT NULL, actor_id TEXT NOT NULL REFERENCES users(id), created_at TEXT NOT NULL
);
INSERT OR IGNORE INTO organization_team(organization_id,user_id,role)
 SELECT organization_id,user_id,'ADMIN' FROM organization_memberships WHERE role='UK_ADMIN' AND access_status='ACTIVE';
INSERT OR IGNORE INTO organization_work_houses(organization_id,house_id)
 SELECT organization_id,house_id FROM organization_houses WHERE status='ACTIVE';
INSERT OR IGNORE INTO organization_team(organization_id,user_id,role,department)
 SELECT organization_id,user_id,CASE WHEN function='COORDINATOR' THEN 'COORDINATOR' ELSE 'SPECIALIST' END,organization_label
 FROM workflow_staff WHERE active=1;
INSERT OR IGNORE INTO organization_team_functions(organization_id,user_id,function)
 SELECT organization_id,user_id,function FROM workflow_staff WHERE active=1 AND function IN ('ENGINEER','WARRANTY','CONTRACTOR');
CREATE TABLE organization_demo_fixtures(id TEXT PRIMARY KEY);
CREATE TABLE organization_case_owners (
 case_id TEXT PRIMARY KEY REFERENCES resident_cases(id), organization_id TEXT NOT NULL REFERENCES organizations(id)
);
