package ru.pulsedoma.bootstrap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import ru.pulsedoma.common.WebhookQueue;

@Component
public final class RedisWebhookQueue implements WebhookQueue {
    private final StringRedisTemplate redis;
    private final String queueKey;

    public RedisWebhookQueue(StringRedisTemplate redis, @Value("${max.webhook.queue-key}") String queueKey) {
        this.redis = redis;
        this.queueKey = queueKey;
    }

    @Override
    public void enqueue(String payload) {
        redis.opsForList().rightPush(queueKey, payload);
    }
}
