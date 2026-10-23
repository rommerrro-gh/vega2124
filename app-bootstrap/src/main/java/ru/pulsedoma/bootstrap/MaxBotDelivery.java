package ru.pulsedoma.bootstrap;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.pulsedoma.common.BusinessException;
import ru.pulsedoma.issues.ResidentCaseService;
import ru.pulsedoma.max.MaxApiClient;

/** Durable delivery: transient failures never repeat a business operation. */
@Component
@Profile("!demo")
public class MaxBotDelivery {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ResidentCaseService cases;
    private final MaxBotMessages messages;
    private final MaxApiClient max;
    private final String token;

    public MaxBotDelivery(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            ResidentCaseService cases,
            MaxBotMessages messages,
            MaxApiClient max,
            @Value("${max.api.token:}") String token) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.cases = cases;
        this.messages = messages;
        this.max = max;
        this.token = token;
    }

    @Scheduled(fixedDelayString = "${max.bot.delivery-poll-ms:1000}")
    public void sendPending() {
        if (token.isBlank()) return;
        var pending =
                jdbc.queryForList(
                        """
            SELECT o.*,u.max_user_id FROM max_bot_outbox o JOIN users u ON u.id=o.user_id
            WHERE o.sent_at IS NULL AND o.cancelled_at IS NULL AND datetime(o.next_attempt_at)<=datetime('now')
            ORDER BY o.created_at,o.rowid LIMIT 30
            """);
        Set<String> sentUsers = new HashSet<>();
        for (var item : pending) {
            String id = item.get("id").toString(), user = item.get("user_id").toString();
            if (!sentUsers.add(user))
                continue; // one message per dialog per poll, below MAX's 2/s limit
            try {
                if (!allowed(item)) {
                    cancel(id);
                    continue;
                }
                Map<String, Object> body;
                if ("CASE_EVENT".equals(item.get("kind")))
                    body = messages.card(cases.get(item.get("case_id").toString(), user), true);
                else
                    body =
                            mapper.readValue(
                                    item.get("body_json").toString(),
                                    new TypeReference<Map<String, Object>>() {});
                max.sendMessage(item.get("max_user_id").toString(), body)
                        .block(Duration.ofSeconds(10));
                jdbc.update(
                        "UPDATE max_bot_outbox SET sent_at=?,last_error=NULL WHERE id=?",
                        Instant.now().toString(),
                        id);
            } catch (BusinessException denied) {
                cancel(id);
            } catch (Exception error) {
                int attempts = ((Number) item.get("attempts")).intValue() + 1;
                jdbc.update(
                        "UPDATE max_bot_outbox SET attempts=?,next_attempt_at=?,last_error=? WHERE id=?",
                        attempts,
                        Instant.now()
                                .plusSeconds(Math.min(3600, 1L << Math.min(attempts, 11)))
                                .toString(),
                        error.getClass().getSimpleName(),
                        id);
            }
        }
    }

    boolean allowed(Map<String, Object> item) {
        String user = item.get("user_id").toString();
        if (jdbc.queryForObject(
                        "SELECT count(*) FROM users WHERE id=? AND status='ACTIVE' AND max_user_id IS NOT NULL AND trim(max_user_id)!=''",
                        Integer.class,
                        user)
                != 1) return false;
        if ("CASE_EVENT".equals(item.get("kind"))
                && jdbc.queryForObject(
                                "SELECT coalesce((SELECT case_updates FROM user_notification_preferences WHERE user_id=?),1)",
                                Integer.class,
                                user)
                        == 0) return false;
        Object house = item.get("house_id");
        if (house != null
                && jdbc.queryForObject(
                                "SELECT count(*) FROM house_memberships WHERE house_id=? AND user_id=? AND role='RESIDENT' AND verification_status='VERIFIED' AND access_status='ACTIVE'",
                                Integer.class,
                                house,
                                user)
                        == 0) return false;
        Object caseId = item.get("case_id");
        return caseId == null || cases.get(caseId.toString(), user).isAuthor();
    }

    private void cancel(String id) {
        jdbc.update(
                "UPDATE max_bot_outbox SET cancelled_at=? WHERE id=?",
                Instant.now().toString(),
                id);
    }

    @Scheduled(
            initialDelayString = "${max.bot.cleanup-poll-ms:3600000}",
            fixedDelayString = "${max.bot.cleanup-poll-ms:3600000}")
    public void cleanup() {
        String expired = Instant.now().minusSeconds(86400).toString();
        jdbc.update(
                "DELETE FROM max_bot_draft_files WHERE user_id IN (SELECT user_id FROM max_bot_sessions WHERE updated_at<?) OR user_id NOT IN (SELECT user_id FROM max_bot_sessions)",
                expired);
        jdbc.update("DELETE FROM max_bot_sessions WHERE updated_at<?", expired);
        jdbc.update(
                "UPDATE max_bot_outbox SET cancelled_at=? WHERE sent_at IS NULL AND cancelled_at IS NULL AND kind='REPLY' AND created_at<?",
                Instant.now().toString(),
                expired);
        // Keep inbox keys so delayed MAX redeliveries cannot create another case.
        jdbc.update(
                "DELETE FROM max_bot_outbox WHERE (sent_at IS NOT NULL OR cancelled_at IS NOT NULL) AND created_at<?",
                Instant.now().minusSeconds(30L * 86400).toString());
    }
}
