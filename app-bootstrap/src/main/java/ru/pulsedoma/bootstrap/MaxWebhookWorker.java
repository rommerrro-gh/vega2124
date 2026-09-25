package ru.pulsedoma.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.pulsedoma.issues.CreateReportCommand;
import ru.pulsedoma.issues.ReportService;

import java.time.Duration;
import java.time.Instant;

@Component
public final class MaxWebhookWorker {
    private static final Logger log = LoggerFactory.getLogger(MaxWebhookWorker.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final ReportService reports;
    private final String queueKey;
    private Instant retryAfter = Instant.MIN;

    public MaxWebhookWorker(StringRedisTemplate redis, ObjectMapper mapper, JdbcTemplate jdbc,
                            ReportService reports, @Value("${max.webhook.queue-key}") String queueKey) {
        this.redis = redis;
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.reports = reports;
        this.queueKey = queueKey;
    }

    @Scheduled(fixedDelayString = "${max.webhook.poll-ms:1000}")
    public void poll() {
        if (Instant.now().isBefore(retryAfter)) return;
        String payload;
        try {
            payload = redis.opsForList().leftPop(queueKey);
        } catch (RuntimeException e) {
            retryAfter = Instant.now().plusSeconds(2);
            log.warn("MAX queue unavailable; retry scheduled", e);
            return;
        }
        if (payload == null) return;
        String messageId = null;
        String lockKey = null;
        boolean locked = false;
        try {
            JsonNode update = mapper.readTree(payload);
            if (!"message_created".equals(update.path("update_type").asText())) return;
            JsonNode message = update.path("message");
            messageId = required(message.path("body").path("mid"), "body.mid");
            String doneKey = queueKey + ":processed";
            if (Boolean.TRUE.equals(redis.opsForSet().isMember(doneKey, messageId))) return;
            lockKey = queueKey + ":processing:" + messageId;
            if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lockKey, "1", Duration.ofMinutes(5)))) {
                redis.opsForList().rightPush(queueKey, payload);
                return;
            }
            locked = true;
            String text = required(message.path("body").path("text"), "text");
            String maxUserId = required(message.path("sender").path("user_id"), "sender.user_id");
            String chatId = required(message.path("recipient").path("chat_id"), "recipient.chat_id");
            String authorId = jdbc.queryForObject("SELECT id FROM users WHERE max_user_id = ?", String.class, maxUserId);
            String houseId = jdbc.queryForObject("SELECT house_id FROM chat_bindings WHERE chat_id = ? AND active = 1",
                    String.class, chatId);
            reports.createReport(new CreateReportCommand(houseId, authorId, text, null));
            redis.opsForSet().add(doneKey, messageId);
            redis.opsForHash().delete(queueKey + ":attempts", messageId);
        } catch (Exception e) {
            String retryId = messageId == null ? Integer.toHexString(payload.hashCode()) : messageId;
            Long attempts = redis.opsForHash().increment(queueKey + ":attempts", retryId, 1);
            if (attempts != null && attempts >= 5) {
                redis.opsForList().rightPush(queueKey + ":dead", payload);
                redis.opsForHash().delete(queueKey + ":attempts", retryId);
                log.error("MAX update sent to dead-letter queue after {} attempts: {}", attempts, retryId, e);
            } else {
                redis.opsForList().rightPush(queueKey, payload);
                retryAfter = Instant.now().plusSeconds(Math.min(60, 1L << Math.min(attempts == null ? 1 : attempts, 6)));
                log.warn("MAX update failed; retry scheduled: {}", retryId, e);
            }
        } finally {
            if (locked) redis.delete(lockKey);
        }
    }

    private static String required(JsonNode node, String field) {
        String value = node.isMissingNode() || node.isNull() ? "" : node.asText();
        if (value.isBlank()) throw new IllegalArgumentException("Missing MAX field: " + field);
        return value;
    }
}
