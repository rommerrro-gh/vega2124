CREATE TABLE guided_demo_organizations (
    session_id TEXT NOT NULL REFERENCES guided_demo_sessions(id),
    organization_id TEXT NOT NULL REFERENCES organizations(id),
    PRIMARY KEY (session_id, organization_id)
);

CREATE TABLE guided_demo_houses (
    session_id TEXT NOT NULL REFERENCES guided_demo_sessions(id),
    house_id TEXT NOT NULL REFERENCES houses(id),
    PRIMARY KEY (session_id, house_id)
);

INSERT INTO guided_demo_organizations(session_id, organization_id)
SELECT id, organization_id FROM guided_demo_sessions;

INSERT INTO guided_demo_houses(session_id, house_id)
SELECT id, house_id FROM guided_demo_sessions;

INSERT OR IGNORE INTO house_memberships(house_id, user_id, role, verification_status, access_status, source_organization_id)
SELECT house_id, user_id, 'HOUSE_ADMIN', 'VERIFIED', 'ACTIVE', organization_id
FROM guided_demo_sessions WHERE active = 1;

INSERT OR IGNORE INTO organization_memberships(organization_id, user_id, role, house_scope, access_status)
SELECT organization_id, user_id, 'UK_ADMIN', NULL, 'ACTIVE'
FROM guided_demo_sessions WHERE active = 1;

INSERT OR IGNORE INTO uk_admin_houses(organization_id, user_id, house_id, access_status)
SELECT organization_id, user_id, house_id, 'ACTIVE'
FROM guided_demo_sessions WHERE active = 1;
