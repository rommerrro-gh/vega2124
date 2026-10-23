package ru.pulsedoma.issues;

import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Written in the case transaction. Delivery rechecks access and uses the current case view. */
@Service
public class CaseBotNotifications {
    private final JdbcTemplate jdbc;

    public CaseBotNotifications(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void enqueue(String id, String actor, CaseEventType type) {
        if (type != CaseEventType.UPDATED
                && type != CaseEventType.ASSIGNED
                && type != CaseEventType.MESSAGE
                && type != CaseEventType.DOCUMENT) return;
        String now = Instant.now().toString();
        jdbc.update(
                """
            INSERT INTO max_bot_outbox(id,user_id,house_id,case_id,kind,body_json,next_attempt_at,created_at)
            SELECT ?,c.author_id,c.house_id,c.id,'CASE_EVENT','{}',?,? FROM resident_cases c
            JOIN users u ON u.id=c.author_id WHERE c.id=? AND c.internal_work=0 AND c.author_id!=?
            AND coalesce((SELECT p.case_updates FROM user_notification_preferences p WHERE p.user_id=c.author_id),1)=1
            AND u.status='ACTIVE' AND u.max_user_id IS NOT NULL AND trim(u.max_user_id)!=''
            AND NOT EXISTS (SELECT 1 FROM max_bot_outbox o WHERE o.case_id=c.id AND o.kind='CASE_EVENT'
                AND o.sent_at IS NULL AND o.cancelled_at IS NULL)
            """,
                UUID.randomUUID().toString(),
                now,
                now,
                id,
                actor);
    }
}
