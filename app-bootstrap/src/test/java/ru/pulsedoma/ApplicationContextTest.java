package ru.pulsedoma;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite::memory:",
        "spring.flyway.enabled=false"
})
class ApplicationContextTest {
    @Test
    void contextLoads() {
    }
}
