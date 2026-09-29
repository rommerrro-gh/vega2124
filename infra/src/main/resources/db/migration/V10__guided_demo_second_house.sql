-- Extend active, previously created judge sessions without resetting their work.
INSERT INTO houses(id, address)
SELECT 'demo-second-' || s.id, 'Казань, второй демонстрационный дом ' || substr(s.id, 1, 8)
FROM guided_demo_sessions s WHERE s.active = 1
  AND NOT EXISTS (SELECT 1 FROM houses h WHERE h.id = 'demo-second-' || s.id);

INSERT INTO organization_houses(organization_id, house_id, status)
SELECT s.organization_id, 'demo-second-' || s.id, 'ACTIVE'
FROM guided_demo_sessions s JOIN houses h ON h.id = 'demo-second-' || s.id WHERE s.active = 1;

INSERT INTO guided_demo_houses(session_id, house_id)
SELECT s.id, 'demo-second-' || s.id
FROM guided_demo_sessions s JOIN houses h ON h.id = 'demo-second-' || s.id WHERE s.active = 1;

INSERT OR IGNORE INTO house_memberships(house_id, user_id, role, verification_status, access_status, source_organization_id)
SELECT dh.house_id, s.user_id, r.role, 'VERIFIED', 'ACTIVE', s.organization_id
FROM guided_demo_sessions s JOIN guided_demo_houses dh ON dh.session_id = s.id
JOIN houses h ON h.id = dh.house_id AND h.id = 'demo-second-' || s.id
JOIN (SELECT 'RESIDENT' AS role UNION ALL SELECT 'DISPATCHER' UNION ALL SELECT 'HOUSE_ADMIN') r
WHERE s.active = 1;

INSERT OR IGNORE INTO uk_admin_houses(organization_id, user_id, house_id, access_status)
SELECT s.organization_id, s.user_id, 'demo-second-' || s.id, 'ACTIVE'
FROM guided_demo_sessions s JOIN houses h ON h.id = 'demo-second-' || s.id WHERE s.active = 1;

INSERT INTO house_fields(house_id, key, value_json, source, fetched_at, valid_at, confidence)
SELECT 'demo-second-' || s.id, f.key, f.value_json, 'Тестовые данные', s.created_at, s.created_at, 1.0
FROM guided_demo_sessions s JOIN houses h ON h.id = 'demo-second-' || s.id
JOIN (
  SELECT 'management_company' AS key, '"Демонстрационная УК"' AS value_json
  UNION ALL SELECT 'building_year', '2006'
  UNION ALL SELECT 'floor_count', '5'
  UNION ALL SELECT 'wall_material', '"панель"'
  UNION ALL SELECT 'entrance_count', '2'
  UNION ALL SELECT 'apartment_count', '80'
  UNION ALL SELECT 'total_area_sqm', '5200'
  UNION ALL SELECT 'registered_residents_count', '174'
  UNION ALL SELECT 'capital_repairs', '["2022 — ремонт фасада"]'
  UNION ALL SELECT 'emergency_contact', '"+7 800 000-00-00"'
) f WHERE s.active = 1;

INSERT INTO house_contacts(id, house_id, type, title, phone, details, updated_at, updated_by)
SELECT 'demo-contact-' || s.id, 'demo-second-' || s.id, 'EMERGENCY',
       'Демонстрационная аварийная служба', '+7 800 000-00-00',
       'Тестовый номер, звонить не нужно', s.created_at, s.user_id
FROM guided_demo_sessions s JOIN houses h ON h.id = 'demo-second-' || s.id WHERE s.active = 1;

INSERT INTO polls(id, house_id, question, is_official, results_hidden_until_close, closes_at, created_at)
SELECT 'demo-poll-' || s.id, 'demo-second-' || s.id,
       'Нужна ли велопарковка у второго дома?', 0, 0,
       strftime('%Y-%m-%dT%H:%M:%fZ', 'now', '+30 days'), strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
FROM guided_demo_sessions s JOIN houses h ON h.id = 'demo-second-' || s.id WHERE s.active = 1;

INSERT INTO poll_options(id, poll_id, label, position)
SELECT 'demo-poll-yes-' || s.id, 'demo-poll-' || s.id, 'Да', 0
FROM guided_demo_sessions s JOIN houses h ON h.id = 'demo-second-' || s.id WHERE s.active = 1;
INSERT INTO poll_options(id, poll_id, label, position)
SELECT 'demo-poll-no-' || s.id, 'demo-poll-' || s.id, 'Нет', 1
FROM guided_demo_sessions s JOIN houses h ON h.id = 'demo-second-' || s.id WHERE s.active = 1;
