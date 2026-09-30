ALTER TABLE reports ADD COLUMN correlation_id TEXT;
ALTER TABLE issues ADD COLUMN normalized_text TEXT;
ALTER TABLE issues ADD COLUMN search_text TEXT;

UPDATE issues SET search_text = (
    SELECT r.search_text FROM issue_reports ir JOIN reports r ON r.id = ir.report_id
    WHERE ir.issue_id = issues.id AND ir.unlinked_at IS NULL
    ORDER BY ir.linked_at LIMIT 1
);
UPDATE issues SET normalized_text = search_text WHERE search_text IS NOT NULL;

CREATE INDEX idx_issues_category ON issues(category);
CREATE INDEX idx_issues_normalized_text ON issues(normalized_text);
CREATE INDEX idx_issues_zone_json ON issues(zone_json);
CREATE INDEX idx_issues_search_text ON issues(search_text);
CREATE INDEX idx_issues_candidate_scope ON issues(house_id, status, created_at);
CREATE UNIQUE INDEX idx_reports_correlation_id ON reports(correlation_id);

CREATE VIRTUAL TABLE issues_fts USING fts5(
    search_text, content='issues', content_rowid='rowid', tokenize='unicode61'
);
INSERT INTO issues_fts(issues_fts) VALUES ('rebuild');

CREATE TRIGGER issues_fts_insert AFTER INSERT ON issues BEGIN
    INSERT INTO issues_fts(rowid, search_text) VALUES (new.rowid, new.search_text);
END;
CREATE TRIGGER issues_fts_delete AFTER DELETE ON issues BEGIN
    INSERT INTO issues_fts(issues_fts, rowid, search_text)
    VALUES ('delete', old.rowid, old.search_text);
END;
CREATE TRIGGER issues_fts_update AFTER UPDATE OF search_text ON issues BEGIN
    INSERT INTO issues_fts(issues_fts, rowid, search_text)
    VALUES ('delete', old.rowid, old.search_text);
    INSERT INTO issues_fts(rowid, search_text) VALUES (new.rowid, new.search_text);
END;
