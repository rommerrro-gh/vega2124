package ru.pulsedoma.bootstrap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@EnabledIfSystemProperty(named = "webhook.redis.port", matches = "[0-9]+")
class RedisWebhookQueueIntegrationTest {
    LettuceConnectionFactory connection;
    StringRedisTemplate redis;
    String key;
    final Instant start = Instant.parse("2026-10-23T12:00:00Z");

    RedisWebhookQueue queue(long seconds) {
        return new RedisWebhookQueue(
                redis,
                key,
                Clock.fixed(start.plusSeconds(seconds), ZoneOffset.UTC),
                Duration.ofSeconds(30));
    }

    @BeforeEach
    void open() {
        connection =
                new LettuceConnectionFactory("127.0.0.1", Integer.getInteger("webhook.redis.port"));
        connection.afterPropertiesSet();
        redis = new StringRedisTemplate(connection);
        redis.afterPropertiesSet();
        key = "test:max:webhook:" + UUID.randomUUID();
    }

    @AfterEach
    void close() {
        redis.delete(
                List.of(
                        key,
                        key + ":inflight",
                        key + ":leases",
                        key + ":owners",
                        key + ":delayed",
                        key + ":dead",
                        key + ":attempts"));
        connection.destroy();
    }

    @Test
    void anotherWorkerCannotStealALiveClaimAndExpiredOwnerCannotAcknowledgeIt() {
        var first = queue(0);
        first.enqueue("one");
        var original = first.claim();
        var second = queue(1);
        assertEquals(0, second.recoverInFlight());
        assertNull(second.claim());
        var restarted = queue(31);
        assertEquals(1, restarted.recoverInFlight());
        var recovered = restarted.claim();
        assertEquals(original.receipt(), recovered.receipt());
        assertNotEquals(original.leaseToken(), recovered.leaseToken());
        assertThrows(IllegalStateException.class, () -> first.acknowledge(original));
        restarted.acknowledge(recovered);
        assertNull(restarted.claim());
    }

    @Test
    void delayedRetryDoesNotBlockHealthyMessages() {
        var q = queue(0);
        q.enqueue("broken");
        q.enqueue("healthy");
        var broken = q.claim();
        q.retry(broken, Duration.ofMinutes(1));
        var good = q.claim();
        assertEquals("healthy", good.payload());
        q.acknowledge(good);
        assertNull(q.claim());
        queue(59).recoverInFlight();
        assertNull(queue(59).claim());
        var later = queue(61);
        later.recoverInFlight();
        var retry = later.claim();
        assertEquals(broken.receipt(), retry.receipt());
        later.acknowledge(retry);
    }

    @Test
    void migratesLegacyInFlightAndPreservesIdenticalButIndependentDeliveries() {
        redis.opsForList().rightPush(key + ":inflight", "legacy");
        var q = queue(0);
        assertEquals(1, q.recoverInFlight());
        var legacy = q.claim();
        assertEquals("legacy", legacy.payload());
        q.deadLetter(legacy);
        assertEquals(1L, redis.opsForList().size(key + ":dead"));
        q.enqueue("same");
        q.enqueue("same");
        var a = q.claim();
        var b = q.claim();
        assertNotEquals(a.receipt(), b.receipt());
        q.acknowledge(a);
        q.acknowledge(b);
    }

    @Test
    void workerContinuesImmediatelyAfterMalformedUpdate() throws Exception {
        var q = new RedisWebhookQueue(redis, key);
        var bot = mock(MaxResidentBot.class);
        var healthy = new AtomicInteger();
        doAnswer(
                        invocation -> {
                            var node =
                                    (com.fasterxml.jackson.databind.JsonNode)
                                            invocation.getArgument(0);
                            if (node.path("update_type").asText().equals("broken"))
                                throw new IllegalArgumentException("bad update");
                            healthy.incrementAndGet();
                            return null;
                        })
                .when(bot)
                .handle(any());
        q.enqueue("{\"update_type\":\"broken\"}");
        q.enqueue("{\"update_type\":\"healthy\"}");
        var worker = new MaxWebhookWorker(q, redis, new ObjectMapper(), bot);
        worker.poll();
        worker.poll();
        assertEquals(1, healthy.get());
        assertEquals(1L, redis.opsForZSet().size(key + ":delayed"));
        assertEquals(0L, redis.opsForList().size(key + ":inflight"));
    }
}
