package ru.pulsedoma.bootstrap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisListCommands.Direction;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import ru.pulsedoma.common.WebhookQueue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@Component
public final class RedisWebhookQueue implements WebhookQueue {
    private static final String ENVELOPE_PREFIX = "v2:";
    private static final DefaultRedisScript<Long> TRANSFER = new DefaultRedisScript<>("""
            local removed = redis.call('LREM', KEYS[1], 1, ARGV[1])
            if removed == 1 then redis.call('RPUSH', KEYS[2], ARGV[1]) end
            return removed
            """, Long.class);
    private final StringRedisTemplate redis;
    private final String queueKey;
    private final String inFlightKey;

    public RedisWebhookQueue(StringRedisTemplate redis, @Value("${max.webhook.queue-key}") String queueKey) {
        this.redis = redis;
        this.queueKey = queueKey;
        this.inFlightKey = queueKey + ":inflight";
    }

    @Override
    public void enqueue(String payload) {
        redis.opsForList().rightPush(queueKey, ENVELOPE_PREFIX + UUID.randomUUID() + ":" + payload);
    }

    public record Delivery(String receipt, String payload) {
        public String retryId() {
            return UUID.nameUUIDFromBytes(receipt.getBytes(StandardCharsets.UTF_8)).toString();
        }
    }

    public String key() { return queueKey; }

    public Delivery claim() {
        String receipt = redis.opsForList().move(queueKey, Direction.LEFT, inFlightKey, Direction.RIGHT);
        if (receipt == null) return null;
        String payload = receipt.startsWith(ENVELOPE_PREFIX) && receipt.length() > 40
                && receipt.charAt(39) == ':' ? receipt.substring(40) : receipt;
        return new Delivery(receipt, payload);
    }

    public int recoverInFlight() {
        int recovered = 0;
        while (redis.opsForList().move(inFlightKey, Direction.RIGHT, queueKey, Direction.LEFT) != null) {
            recovered++;
        }
        return recovered;
    }

    public void acknowledge(Delivery delivery) {
        Long removed = redis.opsForList().remove(inFlightKey, 1, delivery.receipt());
        if (removed == null || removed != 1) throw new IllegalStateException("MAX delivery is not in flight");
    }

    public void retry(Delivery delivery) { transfer(delivery, queueKey); }

    public void deadLetter(Delivery delivery) { transfer(delivery, queueKey + ":dead"); }

    private void transfer(Delivery delivery, String destination) {
        Long moved = redis.execute(TRANSFER, List.of(inFlightKey, destination), delivery.receipt());
        if (moved == null || moved != 1) throw new IllegalStateException("MAX delivery is not in flight");
    }
}
