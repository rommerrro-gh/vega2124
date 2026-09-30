WITH demo_values(key, value_json) AS (
    VALUES
        ('floor_count', '9'),
        ('wall_material', '"кирпич"'),
        ('entrance_count', '4'),
        ('apartment_count', '144'),
        ('total_area_sqm', '9600'),
        ('registered_residents_count', '312'),
        ('capital_repairs', '["2021 — замена лифтов","2024 — ремонт кровли"]')
)
INSERT OR IGNORE INTO house_fields(house_id, key, value_json, source, fetched_at, valid_at, confidence)
SELECT s.house_id, v.key, v.value_json, 'Тестовые данные', s.created_at, s.created_at, 1.0
FROM guided_demo_sessions AS s CROSS JOIN demo_values AS v
WHERE s.active = 1;
