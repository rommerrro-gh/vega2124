-- Demo-only seed for local SQLite after V1 migration. No personal data.
INSERT OR IGNORE INTO users (id, display_name) VALUES
  ('demo-resident-1', 'Demo resident'),
  ('demo-dispatcher-1', 'Demo dispatcher');
INSERT OR IGNORE INTO houses (id, address) VALUES
  ('demo-house-1', 'Казань, демонстрационный дом 1'),
  ('demo-house-2', 'Казань, демонстрационный дом 2'),
  ('demo-house-3', 'Казань, демонстрационный дом 3');
INSERT OR IGNORE INTO house_memberships (house_id, user_id, role, verification_status) VALUES
  ('demo-house-1', 'demo-resident-1', 'RESIDENT', 'INVITED'),
  ('demo-house-2', 'demo-resident-1', 'HOUSE_ADMIN', 'INVITED');
INSERT OR IGNORE INTO reports (id, house_id, author_id, raw_text, search_text, category) VALUES
  ('demo-report-1', 'demo-house-1', 'demo-resident-1', 'Не работает свет в подъезде', 'не работает свет подъезд', 'LIGHTING');
INSERT OR IGNORE INTO issues (id, house_id, category, status, priority) VALUES
  ('demo-issue-1', 'demo-house-1', 'LIGHTING', 'OPEN', 'NORMAL');
INSERT OR IGNORE INTO issue_reports (id, issue_id, report_id, link_reason, score) VALUES
  ('demo-link-1', 'demo-issue-1', 'demo-report-1', 'initial', 1.0);
