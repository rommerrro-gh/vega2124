CREATE TABLE shared_requests (
 id TEXT PRIMARY KEY, house_id TEXT NOT NULL REFERENCES houses(id),
 root_case_id TEXT NOT NULL UNIQUE REFERENCES resident_cases(id),
 title TEXT NOT NULL, category TEXT NOT NULL, location TEXT, location_features TEXT,
 normalized_text TEXT NOT NULL, status TEXT NOT NULL, next_step TEXT NOT NULL, due_at TEXT, responsible_user_id TEXT REFERENCES users(id), merged_into TEXT REFERENCES shared_requests(id),
 revision INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL, updated_at TEXT NOT NULL
);
CREATE INDEX shared_requests_house ON shared_requests(house_id,updated_at);
CREATE TABLE shared_request_members (
 case_id TEXT PRIMARY KEY REFERENCES resident_cases(id), request_id TEXT NOT NULL REFERENCES shared_requests(id),
 user_id TEXT NOT NULL REFERENCES users(id), withdrawn_at TEXT, confirmed_at TEXT, joined_at TEXT NOT NULL
);
CREATE INDEX active_shared_membership ON shared_request_members(request_id,user_id) WHERE withdrawn_at IS NULL;
CREATE TABLE shared_request_history (
 id TEXT PRIMARY KEY, request_id TEXT NOT NULL REFERENCES shared_requests(id), actor_id TEXT NOT NULL REFERENCES users(id),
 action TEXT NOT NULL, note TEXT NOT NULL, created_at TEXT NOT NULL
);
CREATE TABLE shared_request_commands (
 user_id TEXT NOT NULL REFERENCES users(id), client_request_id TEXT NOT NULL,
 request_body TEXT NOT NULL, case_id TEXT NOT NULL REFERENCES resident_cases(id), PRIMARY KEY(user_id,client_request_id)
);
-- Existing free questions become problems in Other; original texts, history and documents stay in place.
INSERT OR IGNORE INTO case_problem_details(case_id,category,location_features)
 SELECT id,'OTHER',NULL FROM resident_cases WHERE kind IN ('GENERAL','PROBLEM','WARRANTY');
INSERT INTO shared_requests(id,house_id,root_case_id,title,category,location,location_features,normalized_text,status,next_step,due_at,responsible_user_id,created_at,updated_at)
 SELECT c.id,c.house_id,c.id,'Обращение по дому — требуется уточнить общее описание',coalesce(d.category,'OTHER'),NULL,NULL,'',c.status,'Сотрудник уточняет общий план работ',NULL,NULL,c.created_at,c.updated_at
 FROM resident_cases c LEFT JOIN case_problem_details d ON d.case_id=c.id WHERE c.kind IN ('GENERAL','PROBLEM','WARRANTY');
INSERT INTO shared_request_members(case_id,request_id,user_id,confirmed_at,joined_at)
 SELECT id,id,author_id,CASE WHEN status='CLOSED' THEN updated_at END,created_at FROM resident_cases WHERE kind IN ('GENERAL','PROBLEM','WARRANTY');
CREATE TABLE demo_scenario_sets(house_id TEXT PRIMARY KEY REFERENCES houses(id),version TEXT NOT NULL,refreshed_at TEXT NOT NULL);
CREATE TABLE max_bot_problem_categories(user_id TEXT PRIMARY KEY REFERENCES users(id),draft_id TEXT NOT NULL,category TEXT NOT NULL);
CREATE TABLE max_bot_problem_matches(user_id TEXT NOT NULL REFERENCES users(id),draft_id TEXT NOT NULL,position INTEGER NOT NULL,request_id TEXT NOT NULL REFERENCES shared_requests(id),PRIMARY KEY(user_id,draft_id,position));
