ALTER TABLE issues ADD COLUMN verification_round INTEGER NOT NULL DEFAULT 0;
ALTER TABLE issues ADD COLUMN verification_due_at TEXT;

CREATE TABLE issue_verification_responses (
    issue_id TEXT NOT NULL REFERENCES issues(id),
    verification_round INTEGER NOT NULL,
    user_id TEXT NOT NULL REFERENCES users(id),
    confirmed INTEGER NOT NULL CHECK (confirmed IN (0, 1)),
    comment TEXT,
    created_at TEXT NOT NULL,
    PRIMARY KEY (issue_id, verification_round, user_id)
);

CREATE TABLE notification_outbox (
    id TEXT PRIMARY KEY,
    issue_id TEXT NOT NULL REFERENCES issues(id),
    verification_round INTEGER NOT NULL,
    recipient_user_id TEXT NOT NULL REFERENCES users(id),
    kind TEXT NOT NULL,
    body TEXT NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TEXT NOT NULL,
    sent_at TEXT,
    cancelled_at TEXT,
    last_error TEXT,
    UNIQUE (issue_id, verification_round, recipient_user_id, kind)
);
CREATE INDEX ix_notification_outbox_pending ON notification_outbox(next_attempt_at)
    WHERE sent_at IS NULL AND cancelled_at IS NULL;
