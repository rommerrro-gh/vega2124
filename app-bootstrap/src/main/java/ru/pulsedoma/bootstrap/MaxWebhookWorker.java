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
import org.springframework.context.annotation.Profile;
import ru.pulsedoma.issues.CreateReportCommand;
import ru.pulsedoma.issues.ReportService;
import ru.pulsedoma.max.MaxApiClient;

import java.time.Duration;
import java.time.Instant;

@Component
@Profile("!demo")
public final class MaxWebhookWorker {
    private static final Logger log = LoggerFactory.getLogger(MaxWebhookWorker.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final ReportService reports;
    private final MaxApiClient max;
    private final String botUsername;
    private final String queueKey;
    private Instant retryAfter = Instant.MIN;

    public MaxWebhookWorker(StringRedisTemplate redis, ObjectMapper mapper, JdbcTemplate jdbc,
                            ReportService reports, MaxApiClient max,
                            @Value("${max.bot.username:}") String botUsername,
                            @Value("${max.webhook.queue-key}") String queueKey) {
        this.redis = redis;
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.reports = reports;
        this.max = max;
        this.botUsername = botUsername;
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
            if ("bot_started".equals(update.path("update_type").asText())) {
                String maxUserId = required(update.path("user").path("user_id"), "user.user_id");
                String key = queueKey + ":welcome:" + maxUserId + ":" + update.path("timestamp").asText();
                if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, "1", Duration.ofDays(1)))) return;
                try { welcome(maxUserId); }
                catch (Exception e) { redis.delete(key); throw e; }
                return;
            }
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
            String text = message.path("body").path("text").asText("").strip();
            String maxUserId = required(message.path("sender").path("user_id"), "sender.user_id");
            String chatId = required(message.path("recipient").path("chat_id"), "recipient.chat_id");
            if (text.strip().equalsIgnoreCase("/start") || text.strip().equalsIgnoreCase("/help")
                    || text.strip().equalsIgnoreCase("помощь")) {
                welcome(maxUserId);
            } else if (text.isBlank()) {
                welcome(maxUserId);
            } else {
                var houses = jdbc.query("SELECT house_id FROM chat_bindings WHERE chat_id = ? AND active = 1",
                        (rs, row) -> rs.getString(1), chatId);
                if (houses.isEmpty()) welcome(maxUserId);
                else {
                    String authorId = jdbc.queryForObject("SELECT id FROM users WHERE max_user_id = ?", String.class, maxUserId);
                    reports.createReport(new CreateReportCommand(houses.get(0), authorId, text, null));
                }
            }
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

    private void welcome(String userId) {
        max.sendWelcome(userId, botUsername, "Пульс дома — сервис вашего многоквартирного дома в MAX.\n\n" +
                "Здесь можно посмотреть паспорт дома и полезные контакты, участвовать в опросах, " +
                "сообщить о проблеме или присоединиться к похожему обращению, следить за работой " +
                "и подтвердить результат после уведомления. Диспетчеры и администраторы работают в своих кабинетах.\n\n" +
                "Откройте мини-приложение кнопкой ниже. Если вы проверяете проект, введите там код демонстрации.")
                .block(Duration.ofSeconds(10));
    }

    private static String required(JsonNode node, String field) {
        String value = node.isMissingNode() || node.isNull() ? "" : node.asText();
        if (value.isBlank()) throw new IllegalArgumentException("Missing MAX field: " + field);
        return value;
    }
}
