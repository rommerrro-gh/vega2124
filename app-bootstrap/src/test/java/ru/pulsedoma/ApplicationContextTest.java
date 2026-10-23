package ru.pulsedoma;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:sqlite::memory:",
            "spring.flyway.enabled=false",
            "max.webhook.secret=private-test-webhook-secret-value-12345678",
            "storage.minio.access-key=test-user",
            "storage.minio.secret-key=private-test-storage-secret-123456"
        })
class ApplicationContextTest {
    @Test
    void contextLoads() {}
}
