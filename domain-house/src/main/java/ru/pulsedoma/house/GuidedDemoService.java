package ru.pulsedoma.house;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.pulsedoma.common.BusinessException;

@Service
public class GuidedDemoService {
    private final JdbcTemplate jdbc;
    private final String codeHash;

    public GuidedDemoService(JdbcTemplate jdbc,
                             @Value("${guided-demo.code-sha256:}") String codeHash) {
        this.jdbc = jdbc;
        this.codeHash = codeHash.trim().toLowerCase();
    }

    public record DemoView(boolean enabled, boolean active, String houseId, String address) {}

    public DemoView status(String userId) {
        List<DemoView> current = jdbc.query("""
                SELECT s.house_id, h.address FROM guided_demo_sessions s
                JOIN houses h ON h.id = s.house_id WHERE s.user_id = ? AND s.active = 1
                """, (rs, row) -> new DemoView(true, true, rs.getString(1), rs.getString(2)), userId);
        return current.isEmpty() ? new DemoView(!codeHash.isBlank(), false, null, null) : current.get(0);
    }

    @Transactional
    public DemoView activate(String userId, String code) {
        if (codeHash.isBlank()) throw new BusinessException("DEMO_UNAVAILABLE", "Демонстрация пока недоступна");
        byte[] supplied = sha256(code.strip());
        byte[] expected;
        try { expected = HexFormat.of().parseHex(codeHash); }
        catch (IllegalArgumentException e) { throw new IllegalStateException("Invalid guided demo code hash", e); }
        if (!MessageDigest.isEqual(supplied, expected)) {
            throw new BusinessException("INVALID_DEMO_CODE", "Неверный код демонстрации");
        }
        DemoView existing = status(userId);
        return existing.active() ? existing : create(userId);
    }

    @Transactional
    public DemoView restart(String userId) {
        DemoView current = status(userId);
        if (!current.active()) throw new BusinessException("DEMO_NOT_ACTIVE", "Сначала активируйте демонстрацию");
        deactivate(userId, current.houseId());
        return create(userId);
    }

    @Transactional
    public DemoView exit(String userId) {
        DemoView current = status(userId);
        if (current.active()) deactivate(userId, current.houseId());
        return status(userId);
    }

    private void deactivate(String userId, String houseId) {
        jdbc.update("""
                UPDATE notification_outbox SET cancelled_at = ?
                WHERE recipient_user_id = ? AND sent_at IS NULL AND cancelled_at IS NULL
                  AND issue_id IN (SELECT id FROM issues WHERE house_id = ?)
                """, Instant.now().toString(), userId, houseId);
        jdbc.update("UPDATE guided_demo_sessions SET active = 0 WHERE user_id = ? AND house_id = ? AND active = 1", userId, houseId);
        jdbc.update("UPDATE house_memberships SET access_status = 'REVOKED' WHERE user_id = ? AND house_id = ? AND role IN ('RESIDENT', 'DISPATCHER')", userId, houseId);
        jdbc.update("""
                UPDATE users SET active_house_id = (
                    SELECT previous_house_id FROM guided_demo_sessions WHERE user_id = ? AND house_id = ?
                ) WHERE id = ? AND active_house_id = ?
                """, userId, houseId, userId, houseId);
    }

    private DemoView create(String userId) {
        String org = UUID.randomUUID().toString();
        String house = UUID.randomUUID().toString();
        String session = UUID.randomUUID().toString();
        String neighbor = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        String address = "Казань, демонстрационный дом " + session.substring(0, 8);
        String previousHouse = jdbc.queryForObject("SELECT active_house_id FROM users WHERE id = ?", String.class, userId);
        jdbc.update("INSERT INTO organizations(id, type, name) VALUES (?, 'MANAGEMENT_COMPANY', 'Демонстрационная УК')", org);
        jdbc.update("INSERT INTO houses(id, address) VALUES (?, ?)", house, address);
        jdbc.update("INSERT INTO organization_houses(organization_id, house_id, status) VALUES (?, ?, 'ACTIVE')", org, house);
        for (String role : List.of("RESIDENT", "DISPATCHER")) {
            jdbc.update("""
                    INSERT INTO house_memberships(house_id, user_id, role, verification_status, access_status, source_organization_id)
                    VALUES (?, ?, ?, 'VERIFIED', 'ACTIVE', ?)
                    """, house, userId, role, org);
        }
        jdbc.update("INSERT INTO users(id, display_name, status) VALUES (?, 'Сосед из демонстрационного дома', 'ACTIVE')", neighbor);
        jdbc.update("""
                INSERT INTO house_memberships(house_id, user_id, role, verification_status, access_status, source_organization_id)
                VALUES (?, ?, 'RESIDENT', 'VERIFIED', 'ACTIVE', ?)
                """, house, neighbor, org);
        jdbc.update("UPDATE users SET active_house_id = ? WHERE id = ?", house, userId);
        jdbc.update("INSERT INTO guided_demo_sessions(id, user_id, organization_id, house_id, previous_house_id, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                session, userId, org, house, previousHouse, now);
        field(house, "management_company", "\"Демонстрационная УК\"", now);
        field(house, "building_year", "2018", now);
        field(house, "emergency_contact", "\"+7 800 000-00-00\"", now);
        jdbc.update("""
                INSERT INTO house_contacts(id, house_id, type, title, phone, details, updated_at, updated_by)
                VALUES (?, ?, 'EMERGENCY', 'Демонстрационная аварийная служба', '+7 800 000-00-00',
                        'Тестовый номер, звонить не нужно', ?, ?)
                """, UUID.randomUUID().toString(), house, now, userId);
        String poll = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO polls(id, house_id, question, is_official, results_hidden_until_close, closes_at, created_at)
                VALUES (?, ?, 'Нужно ли улучшить освещение в подъезде?', 0, 0, ?, ?)
                """, poll, house, Instant.now().plusSeconds(30L * 86400).toString(), now);
        jdbc.update("INSERT INTO poll_options(id, poll_id, label, position) VALUES (?, ?, 'Да', 0)", UUID.randomUUID().toString(), poll);
        jdbc.update("INSERT INTO poll_options(id, poll_id, label, position) VALUES (?, ?, 'Нет', 1)", UUID.randomUUID().toString(), poll);
        String report = UUID.randomUUID().toString();
        String issue = UUID.randomUUID().toString();
        String description = "Не работает свет в подъезде";
        String normalized = "не работает свет в подъезд";
        String zone = "{\"label\":\"Подъезд 1, этаж 2\",\"key\":\"подъезд 1 этаж 2\"}";
        jdbc.update("""
                INSERT INTO reports(id, house_id, author_id, raw_text, search_text, category, zone_json, occurred_at, correlation_id, created_at)
                VALUES (?, ?, ?, ?, ?, 'LIGHTING', ?, ?, ?, ?)
                """, report, house, neighbor, description, normalized, zone, now, UUID.randomUUID().toString(), now);
        jdbc.update("""
                INSERT INTO issues(id, house_id, category, zone_json, status, priority, organization_id, normalized_text, search_text, created_at, updated_at)
                VALUES (?, ?, 'LIGHTING', ?, 'OPEN', 'NORMAL', ?, ?, ?, ?, ?)
                """, issue, house, zone, org, normalized, normalized, now, now);
        jdbc.update("INSERT INTO issue_reports(id, issue_id, report_id, link_reason, score) VALUES (?, ?, ?, 'demo-seed', 1.0)",
                UUID.randomUUID().toString(), issue, report);
        jdbc.update("INSERT INTO issue_participants(issue_id, user_id) VALUES (?, ?)", issue, neighbor);
        jdbc.update("INSERT INTO status_events(id, issue_id, to_status, actor_id, reason, created_at) VALUES (?, ?, 'OPEN', ?, 'Демонстрационная заявка', ?)",
                UUID.randomUUID().toString(), issue, userId, now);
        jdbc.update("INSERT INTO audit_events(id, actor_id, action, entity, entity_id, created_at) VALUES (?, ?, 'GUIDED_DEMO_STARTED', 'guided_demo_session', ?, ?)",
                UUID.randomUUID().toString(), userId, session, now);
        return new DemoView(true, true, house, address);
    }

    private void field(String house, String key, String value, String now) {
        jdbc.update("INSERT INTO house_fields(house_id, key, value_json, source, fetched_at, valid_at, confidence) VALUES (?, ?, ?, 'Тестовые данные', ?, ?, 1.0)",
                house, key, value, now, now);
    }

    private static byte[] sha256(String text) {
        try { return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
