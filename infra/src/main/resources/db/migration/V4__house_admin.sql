CREATE TABLE house_contacts
(
    id         TEXT PRIMARY KEY,
    house_id   TEXT NOT NULL REFERENCES houses (id),
    type       TEXT NOT NULL CHECK (type IN ('EMERGENCY', 'LOCAL')),
    title      TEXT NOT NULL,
    phone      TEXT NOT NULL,
    details    TEXT,
    updated_at TEXT NOT NULL,
    updated_by TEXT NOT NULL REFERENCES users (id)
);
CREATE INDEX house_contacts_house_id_idx ON house_contacts (house_id, type);

ALTER TABLE house_invitations ADD COLUMN created_at TEXT;
ALTER TABLE house_invitations ADD COLUMN created_by TEXT REFERENCES users (id);
UPDATE house_invitations SET created_at = CURRENT_TIMESTAMP WHERE created_at IS NULL;
