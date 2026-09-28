CREATE TABLE system_administrators (
    user_id TEXT PRIMARY KEY REFERENCES users(id),
    created_at TEXT NOT NULL,
    created_by TEXT REFERENCES users(id)
);

CREATE TABLE organization_houses (
    organization_id TEXT NOT NULL REFERENCES organizations(id),
    house_id TEXT NOT NULL REFERENCES houses(id),
    status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    PRIMARY KEY (organization_id, house_id)
);

ALTER TABLE organization_memberships ADD COLUMN access_status TEXT NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE house_memberships ADD COLUMN access_status TEXT NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE house_memberships ADD COLUMN source_organization_id TEXT REFERENCES organizations(id);

CREATE TABLE uk_admin_houses (
    organization_id TEXT NOT NULL REFERENCES organizations(id),
    user_id TEXT NOT NULL REFERENCES users(id),
    house_id TEXT NOT NULL REFERENCES houses(id),
    access_status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (access_status IN ('ACTIVE', 'SUSPENDED', 'REVOKED')),
    PRIMARY KEY (organization_id, user_id, house_id)
);

CREATE TABLE access_invitations (
    id TEXT PRIMARY KEY,
    token_hash TEXT NOT NULL UNIQUE,
    role TEXT NOT NULL CHECK (role IN ('UK_ADMIN', 'DISPATCHER', 'HOUSE_ADMIN', 'RESIDENT')),
    organization_id TEXT REFERENCES organizations(id),
    created_by TEXT NOT NULL REFERENCES users(id),
    expires_at TEXT NOT NULL,
    activation_limit INTEGER NOT NULL CHECK (activation_limit BETWEEN 1 AND 100),
    activation_count INTEGER NOT NULL DEFAULT 0,
    revoked_at TEXT,
    created_at TEXT NOT NULL
);

CREATE TABLE access_invitation_houses (
    invitation_id TEXT NOT NULL REFERENCES access_invitations(id),
    house_id TEXT NOT NULL REFERENCES houses(id),
    PRIMARY KEY (invitation_id, house_id)
);

CREATE TABLE access_invitation_activations (
    invitation_id TEXT NOT NULL REFERENCES access_invitations(id),
    user_id TEXT NOT NULL REFERENCES users(id),
    accepted_at TEXT NOT NULL,
    PRIMARY KEY (invitation_id, user_id)
);

ALTER TABLE users ADD COLUMN active_house_id TEXT REFERENCES houses(id);
CREATE INDEX access_invitations_creator_idx ON access_invitations(created_by, created_at);
