package ru.pulsedoma.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

import java.time.Instant;

@Component
@Profile("!demo")
public final class MaxWebhookWorker {
    private static final Logger log = LoggerFactory.getLogger(MaxWebhookWorker.class);
    private final RedisWebhookQueue queue;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final MaxResidentBot bot;
    private Instant retryAfter = Instant.MIN;

    public MaxWebhookWorker(RedisWebhookQueue queue, StringRedisTemplate redis, ObjectMapper mapper, MaxResidentBot bot) {
        this.queue=queue;this.redis=redis;this.mapper=mapper;this.bot=bot;
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
        bot.handle(mapper.readTree(payload));
    }
}
