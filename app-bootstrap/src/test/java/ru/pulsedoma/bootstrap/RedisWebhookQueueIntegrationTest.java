package ru.pulsedoma.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.pulsedoma.max.MaxApiClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

class RedisWebhookQueueIntegrationTest {
    @Test
    @EnabledIfSystemProperty(named = "webhook.redis.port", matches = "[0-9]+")
    void recoversClaimedUpdatesAndMovesRetriesAtomically() {
        int port = Integer.getInteger("webhook.redis.port");
        LettuceConnectionFactory connection = new LettuceConnectionFactory("127.0.0.1", port);
        connection.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(connection);
        redis.afterPropertiesSet();
        String key = "test:max:webhook:" + UUID.randomUUID();
        try {
            RedisWebhookQueue queue = new RedisWebhookQueue(redis, key);
            queue.enqueue("{\"update_type\":\"message_created\"}");
            var claimed = queue.claim();
            assertEquals("{\"update_type\":\"message_created\"}", claimed.payload());
            assertNull(queue.claim());

            RedisWebhookQueue restarted = new RedisWebhookQueue(redis, key);
            assertEquals(1, restarted.recoverInFlight());
            var recovered = restarted.claim();
            assertEquals(claimed.receipt(), recovered.receipt());
            restarted.retry(recovered);
            var retried = restarted.claim();
            assertEquals(claimed.receipt(), retried.receipt());
            restarted.acknowledge(retried);
            assertNull(restarted.claim());

            redis.opsForList().rightPush(key, "{\"update_type\":\"legacy\"}");
            var legacy = restarted.claim();
            assertEquals("{\"update_type\":\"legacy\"}", legacy.payload());
            restarted.deadLetter(legacy);
            assertEquals(1L, redis.opsForList().size(key + ":dead"));
            assertNull(restarted.claim());

            restarted.enqueue("{\"update_type\":\"ignored\"}");
            restarted.claim(); // The previous process stopped before acknowledging this delivery.
            new MaxWebhookWorker(restarted, redis, new ObjectMapper(), mock(JdbcTemplate.class),
                    mock(MaxMessageReportHandler.class), mock(MaxApiClient.class), "").poll();
            assertNull(restarted.claim());
            assertEquals(0L, redis.opsForList().size(key + ":inflight"));
        } finally {
            redis.delete(key);
            redis.delete(key + ":inflight");
            redis.delete(key + ":dead");
            connection.destroy();
        }
    }
}
