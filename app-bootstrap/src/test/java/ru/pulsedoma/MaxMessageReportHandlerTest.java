package ru.pulsedoma;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import ru.pulsedoma.bootstrap.MaxMessageReportHandler;
import ru.pulsedoma.common.BusinessException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@ActiveProfiles("demo")
class MaxMessageReportHandlerTest {
    private static final Path DB = database();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB);
    }

    private static Path database() {
        try { return Files.createTempFile("pulse-max-message-", ".db"); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    @Autowired MaxMessageReportHandler handler;
    @Autowired JdbcTemplate jdbc;

    @Test
    void retryDoesNotCreateSecondReportAndFailedTransactionCanBeRetried() {
        handler.createOnce("mid-1", "demo-house-1", "demo-resident-1", "Не работает свет в подъезде");
        handler.createOnce("mid-1", "demo-house-1", "demo-resident-1", "Повторная доставка");
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM reports WHERE author_id = 'demo-resident-1'", Integer.class));

        assertThrows(BusinessException.class,
                () -> handler.createOnce("mid-2", "unknown-house", "demo-resident-1", "Новая проблема"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM max_processed_messages WHERE message_id = 'mid-2'", Integer.class));
        handler.createOnce("mid-2", "demo-house-1", "demo-resident-1", "Новая проблема");
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM reports WHERE author_id = 'demo-resident-1'", Integer.class));
    }
}
