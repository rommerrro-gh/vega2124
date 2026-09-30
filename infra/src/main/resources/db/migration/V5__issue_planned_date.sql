ALTER TABLE issues ADD COLUMN planned_date TEXT;

CREATE TABLE issue_planned_date_events (
    id TEXT PRIMARY KEY,
    issue_id TEXT NOT NULL REFERENCES issues(id),
    previous_date TEXT,
    new_date TEXT NOT NULL,
    previous_date_missed INTEGER NOT NULL DEFAULT 0,
    actor_id TEXT NOT NULL REFERENCES users(id),
    reason TEXT,
    created_at TEXT NOT NULL
);
CREATE INDEX ix_issue_planned_date_events_issue ON issue_planned_date_events(issue_id, created_at, id);
