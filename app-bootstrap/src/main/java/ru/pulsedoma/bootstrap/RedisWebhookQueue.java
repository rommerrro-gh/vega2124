package ru.pulsedoma.bootstrap;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import ru.pulsedoma.common.WebhookQueue;

@Component
public final class RedisWebhookQueue implements WebhookQueue {
    private final StringRedisTemplate redis;

    public RedisWebhookQueue(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void enqueue(String payload) {
        redis.opsForList().rightPush("max:webhook:updates", payload);
    }
}
