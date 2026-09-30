package ru.pulsedoma.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.pulsedoma.issues.MiniAppService;
import ru.pulsedoma.max.MaxApiClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
public class VerificationJobs {
    private static final Logger log = LoggerFactory.getLogger(VerificationJobs.class);
    private final MiniAppService issues;

    public VerificationJobs(MiniAppService issues) {
        this.issues = issues;
    }

    @Scheduled(fixedDelayString = "${verification.expiry-poll-ms:60000}")
    public void closeExpired() {
        int closed = issues.closeExpiredVerifications();
        if (closed > 0) log.info("Closed {} unconfirmed issues after verification deadline", closed);
    }

    @Component
    @Profile("!demo")
    public static class Notifications {
        private final JdbcTemplate jdbc;
        private final MaxApiClient max;
        private final String token;

        public Notifications(JdbcTemplate jdbc, MaxApiClient max, @Value("${max.api.token:}") String token) {
            this.jdbc = jdbc;
            this.max = max;
            this.token = token;
        }

        @Scheduled(fixedDelayString = "${verification.notification-poll-ms:5000}")
        public void sendPending() {
            if (token.isBlank()) return;
            List<Map<String, Object>> pending = jdbc.queryForList("""
                    SELECT o.id, o.body, u.max_user_id FROM notification_outbox o
                    JOIN users u ON u.id = o.recipient_user_id
                    JOIN issues i ON i.id = o.issue_id
                    WHERE o.sent_at IS NULL AND o.cancelled_at IS NULL
                      AND (
                        (o.kind = 'VERIFY_RESULT' AND i.verification_round = o.verification_round
                            AND i.status = 'VERIFICATION_72H')
                        OR (o.kind GLOB 'PLANNED_DATE_*' AND i.status IN
                            ('DRAFT', 'OPEN', 'ASSIGNED', 'IN_PROGRESS', 'REOPENED', 'REVIEW_REQUIRED'))
                        OR o.kind GLOB 'STATUS_CHANGE_*'
                      )
                      AND EXISTS (SELECT 1 FROM house_memberships m
                          WHERE m.house_id = i.house_id AND m.user_id = o.recipient_user_id
                            AND m.role = 'RESIDENT' AND m.verification_status = 'VERIFIED' AND m.access_status = 'ACTIVE')
                      AND datetime(o.next_attempt_at) <= datetime('now')
                    ORDER BY o.next_attempt_at LIMIT 10
                    """);
            for (Map<String, Object> item : pending) {
                String id = (String) item.get("id");
                try {
                    max.sendText((String) item.get("max_user_id"), (String) item.get("body"))
                            .block(Duration.ofSeconds(10));
                    jdbc.update("UPDATE notification_outbox SET sent_at = ?, last_error = NULL WHERE id = ?",
                            Instant.now().toString(), id);
                } catch (Exception e) {
                    Integer attempts = jdbc.queryForObject(
                            "SELECT attempts FROM notification_outbox WHERE id = ?", Integer.class, id);
                    int next = (attempts == null ? 0 : attempts) + 1;
                    long delaySeconds = Math.min(3600, 1L << Math.min(next, 10));
                    jdbc.update("""
                            UPDATE notification_outbox SET attempts = ?, next_attempt_at = ?, last_error = ?
                            WHERE id = ?
                            """, next, Instant.now().plusSeconds(delaySeconds).toString(),
                            e.getClass().getSimpleName(), id);
                    log.warn("MAX notification retry scheduled for outbox entry {}", id, e);
                }
            }
        }
    }
}
