ALTER TABLE resident_cases ADD COLUMN internal_work INTEGER NOT NULL DEFAULT 0 CHECK(internal_work IN (0,1));
INSERT INTO resident_cases(id,house_id,author_id,kind,status,description,location,client_request_id,request_hash,responsible_user_id,next_step,due_at,created_at,updated_at,internal_work)
SELECT 'shared-work:'||s.id,s.house_id,c.author_id,c.kind,s.status,s.title,s.location,'shared-work:'||s.id,'shared-work:'||s.id,s.responsible_user_id,s.next_step,s.due_at,s.created_at,s.updated_at,1
FROM shared_requests s JOIN resident_cases c ON c.id=s.root_case_id;
-- Existing materials stay with their original owner. New common work uses the independent service card.
UPDATE shared_requests SET root_case_id='shared-work:'||id;
CREATE TABLE shared_work_document_access(case_id TEXT NOT NULL REFERENCES resident_cases(id),version_id TEXT NOT NULL REFERENCES case_document_versions(id),PRIMARY KEY(case_id,version_id));
