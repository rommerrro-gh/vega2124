package ru.pulsedoma.bootstrap;

import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.pulsedoma.issues.CreateReportCommand;
import ru.pulsedoma.issues.ReportService;

@Service
public class MaxMessageReportHandler {
    private final JdbcTemplate jdbc;
    private final ReportService reports;

    public MaxMessageReportHandler(JdbcTemplate jdbc, ReportService reports) {
        this.jdbc = jdbc;
        this.reports = reports;
    }

    @Transactional
    public void createOnce(String messageId, String houseId, String authorId, String text) {
        int inserted = jdbc.update("""
                INSERT OR IGNORE INTO max_processed_messages(message_id, created_at) VALUES (?, ?)
                """, messageId, Instant.now().toString());
        if (inserted == 0) return;
        reports.createReport(new CreateReportCommand(houseId, authorId, text, null));
    }
}
