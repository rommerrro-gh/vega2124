CREATE TABLE case_problem_details (
 case_id TEXT PRIMARY KEY REFERENCES resident_cases(id),
 category TEXT,
 location_features TEXT
);
CREATE TABLE problem_join_requests (
 user_id TEXT NOT NULL REFERENCES users(id),
 request_id TEXT NOT NULL,
 request_body TEXT NOT NULL,
 issue_id TEXT NOT NULL REFERENCES issues(id),
 report_id TEXT NOT NULL REFERENCES reports(id),
 PRIMARY KEY(user_id,request_id)
);
