package ru.pulsedoma.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;
import ru.pulsedoma.max.MaxApiClient;

import java.time.Duration;
import java.time.Instant;

@Component
@Profile("!demo")
public final class MaxWebhookWorker {
    private static final Logger log = LoggerFactory.getLogger(MaxWebhookWorker.class);
    private final RedisWebhookQueue queue;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final MaxMessageReportHandler reports;
    private final MaxApiClient max;
    private final String botUsername;
    private Instant retryAfter = Instant.MIN;

    public MaxWebhookWorker(RedisWebhookQueue queue, StringRedisTemplate redis, ObjectMapper mapper, JdbcTemplate jdbc,
                            MaxMessageReportHandler reports, MaxApiClient max,
                            @Value("${max.bot.username:}") String botUsername) {
        this.queue = queue;
        this.redis = redis;
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.reports = reports;
        this.max = max;
        this.botUsername = botUsername;
    }

    @Scheduled(fixedDelayString = "${max.webhook.poll-ms:1000}")
    public void poll() {
        if (Instant.now().isBefore(retryAfter)) return;
        RedisWebhookQueue.Delivery delivery;
        try {
            int recovered = queue.recoverInFlight();
            if (recovered > 0) log.warn("Recovered {} unfinished MAX updates", recovered);
            delivery = queue.claim();
        } catch (RuntimeException e) {
            retryAfter = Instant.now().plusSeconds(2);
            log.warn("MAX queue unavailable; retry scheduled", e);
            return;
        }
        if (delivery == null) return;
        try {
            process(delivery.payload());
            queue.acknowledge(delivery);
            try { redis.opsForHash().delete(queue.key() + ":attempts", delivery.retryId()); }
            catch (RuntimeException cleanupError) { log.warn("Could not clear MAX retry counter", cleanupError); }
        } catch (Exception e) {
            try {
                String retryId = delivery.retryId();
                Long attempts = redis.opsForHash().increment(queue.key() + ":attempts", retryId, 1);
                if (attempts != null && attempts >= 5) {
                    queue.deadLetter(delivery);
                    redis.opsForHash().delete(queue.key() + ":attempts", retryId);
                    log.error("MAX update sent to dead-letter queue after {} attempts: {}", attempts, retryId, e);
                } else {
                    queue.retry(delivery);
                    retryAfter = Instant.now().plusSeconds(Math.min(60, 1L << Math.min(attempts == null ? 1 : attempts, 6)));
                    log.warn("MAX update failed; retry scheduled: {}", retryId, e);
                }
            } catch (RuntimeException redisError) {
                retryAfter = Instant.now().plusSeconds(2);
                log.error("MAX update remains in the in-flight queue; recovery scheduled", redisError);
            }
        }
    }

    private void process(String payload) throws Exception {
        JsonNode update = mapper.readTree(payload);
        if ("bot_started".equals(update.path("update_type").asText())) {
            String maxUserId = required(update.path("user").path("user_id"), "user.user_id");
            String key = queue.key() + ":welcome:" + maxUserId + ":" + update.path("timestamp").asText();
            if (Boolean.TRUE.equals(redis.hasKey(key))) return;
            welcome(maxUserId);
            redis.opsForValue().set(key, "1", Duration.ofDays(1));
            return;
        }
        if (!"message_created".equals(update.path("update_type").asText())) return;
        JsonNode message = update.path("message");
        String messageId = required(message.path("body").path("mid"), "body.mid");
        String doneKey = queue.key() + ":processed";
        if (Boolean.TRUE.equals(redis.opsForSet().isMember(doneKey, messageId))) return;
        String text = message.path("body").path("text").asText("").strip();
        String maxUserId = required(message.path("sender").path("user_id"), "sender.user_id");
        String chatId = required(message.path("recipient").path("chat_id"), "recipient.chat_id");
        if (text.equalsIgnoreCase("/start") || text.equalsIgnoreCase("/help")
                || text.equalsIgnoreCase("помощь") || text.isBlank()) {
            welcome(maxUserId);
        } else {
            var houses = jdbc.query("SELECT house_id FROM chat_bindings WHERE chat_id = ? AND active = 1",
                    (rs, row) -> rs.getString(1), chatId);
            if (houses.isEmpty()) welcome(maxUserId);
            else {
                String authorId = jdbc.queryForObject("SELECT id FROM users WHERE max_user_id = ?", String.class, maxUserId);
                reports.createOnce(messageId, houses.get(0), authorId, text);
            }
        }
        redis.opsForSet().add(doneKey, messageId);
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
