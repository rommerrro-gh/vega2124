package ru.pulsedoma.bootstrap;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import ru.pulsedoma.common.WebhookQueue;

/** Atomic claims with leases and per-delivery delayed retries, shared by all workers. */
@Component
public final class RedisWebhookQueue implements WebhookQueue {
    private static final String ENVELOPE_PREFIX = "v2:";
    private static final DefaultRedisScript<String> CLAIM =
            new DefaultRedisScript<>(
                    """
        local receipt = redis.call('LPOP', KEYS[1])
        if not receipt then return nil end
        redis.call('RPUSH', KEYS[2], receipt)
        redis.call('ZADD', KEYS[3], ARGV[1], receipt)
        redis.call('HSET', KEYS[4], receipt, ARGV[2])
        return receipt
        """,
                    String.class);
    private static final DefaultRedisScript<Long> RECOVER =
            new DefaultRedisScript<>(
                    """
        local recovered = 0
        -- Import unleased deliveries left by the previous queue implementation.
        for _, receipt in ipairs(redis.call('LRANGE', KEYS[2], 0, 99)) do
            if not redis.call('ZSCORE', KEYS[3], receipt) then
                redis.call('ZADD', KEYS[3], ARGV[1], receipt)
            end
        end
        for _, receipt in ipairs(redis.call('ZRANGEBYSCORE', KEYS[3], '-inf', ARGV[1], 'LIMIT', 0, 100)) do
            if redis.call('LREM', KEYS[2], 1, receipt) == 1 then
                redis.call('RPUSH', KEYS[1], receipt)
                recovered = recovered + 1
            end
            redis.call('ZREM', KEYS[3], receipt)
            redis.call('HDEL', KEYS[5], receipt)
        end
        for _, receipt in ipairs(redis.call('ZRANGEBYSCORE', KEYS[4], '-inf', ARGV[1], 'LIMIT', 0, 100)) do
            redis.call('ZREM', KEYS[4], receipt)
            redis.call('RPUSH', KEYS[1], receipt)
        end
        return recovered
        """,
                    Long.class);
    private static final DefaultRedisScript<Long> FINISH =
            new DefaultRedisScript<>(
                    """
        if redis.call('HGET', KEYS[5], ARGV[1]) ~= ARGV[4] then return 0 end
        if redis.call('LREM', KEYS[1], 1, ARGV[1]) ~= 1 then return 0 end
        redis.call('HDEL', KEYS[5], ARGV[1])
        redis.call('ZREM', KEYS[2], ARGV[1])
        if ARGV[2] == 'retry' then redis.call('ZADD', KEYS[3], ARGV[3], ARGV[1]) end
        if ARGV[2] == 'dead' then redis.call('RPUSH', KEYS[4], ARGV[1]) end
        return 1
        """,
                    Long.class);
    private final StringRedisTemplate redis;
    private final String queueKey;
    private final Clock clock;
    private final Duration lease;

    @Autowired
    public RedisWebhookQueue(
            StringRedisTemplate redis, @Value("${max.webhook.queue-key}") String queueKey) {
        this(redis, queueKey, Clock.systemUTC(), Duration.ofMinutes(5));
    }

    RedisWebhookQueue(StringRedisTemplate redis, String queueKey, Clock clock, Duration lease) {
        this.redis = redis;
        this.queueKey = queueKey;
        this.clock = clock;
        this.lease = lease;
    }

    @Override
    public void enqueue(String payload) {
        redis.opsForList().rightPush(queueKey, ENVELOPE_PREFIX + UUID.randomUUID() + ":" + payload);
    }

    public record Delivery(String receipt, String payload, String leaseToken) {
        public String retryId() {
            return UUID.nameUUIDFromBytes(receipt.getBytes(StandardCharsets.UTF_8)).toString();
        }
    }

    public String key() {
        return queueKey;
    }

    public Delivery claim() {
        String token = UUID.randomUUID().toString();
        String receipt =
                redis.execute(
                        CLAIM,
                        List.of(
                                queueKey,
                                queueKey + ":inflight",
                                queueKey + ":leases",
                                queueKey + ":owners"),
                        Long.toString(clock.millis() + lease.toMillis()),
                        token);
        if (receipt == null) return null;
        String payload =
                receipt.startsWith(ENVELOPE_PREFIX)
                                && receipt.length() > 40
                                && receipt.charAt(39) == ':'
                        ? receipt.substring(40)
                        : receipt;
        return new Delivery(receipt, payload, token);
    }

    public int recoverInFlight() {
        Long count =
                redis.execute(
                        RECOVER,
                        List.of(
                                queueKey,
                                queueKey + ":inflight",
                                queueKey + ":leases",
                                queueKey + ":delayed",
                                queueKey + ":owners"),
                        Long.toString(clock.millis()));
        return count == null ? 0 : count.intValue();
    }

    public void acknowledge(Delivery delivery) {
        finish(delivery, "ack", Duration.ZERO);
    }

    public void retry(Delivery delivery) {
        retry(delivery, Duration.ZERO);
    }

    public void retry(Delivery delivery, Duration delay) {
        finish(delivery, "retry", delay);
    }

    public void deadLetter(Delivery delivery) {
        finish(delivery, "dead", Duration.ZERO);
    }

    private void finish(Delivery delivery, String action, Duration delay) {
        Long removed =
                redis.execute(
                        FINISH,
                        List.of(
                                queueKey + ":inflight",
                                queueKey + ":leases",
                                queueKey + ":delayed",
                                queueKey + ":dead",
                                queueKey + ":owners"),
                        delivery.receipt(),
                        action,
                        Long.toString(clock.millis() + delay.toMillis()),
                        delivery.leaseToken());
        if (removed == null || removed != 1)
            throw new IllegalStateException("MAX delivery lease is no longer owned");
    }
}
