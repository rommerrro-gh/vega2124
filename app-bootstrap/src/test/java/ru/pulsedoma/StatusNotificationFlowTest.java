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
import reactor.core.publisher.Mono;
import ru.pulsedoma.bootstrap.VerificationJobs;
import ru.pulsedoma.common.BusinessException;
import ru.pulsedoma.issues.*;
import ru.pulsedoma.max.MaxApiClient;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("demo")
class StatusNotificationFlowTest {
    private static final Path DB = database();
    private static Path database() {
        try { return Files.createTempFile("pulse-status-messages-", ".db"); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB);
    }
    @Autowired ReportService reports;
    @Autowired MiniAppService issues;
    @Autowired JdbcTemplate jdbc;

    @Test void deliversEveryVisibleTransitionIncludingReopeningAndClosing() {
        jdbc.update("UPDATE users SET max_user_id='notification-test-user' WHERE id='demo-resident-1'");
        String id = issues.createIssue(report().id, "demo-resident-1").id();
        MaxApiClient max = mock(MaxApiClient.class);
        when(max.sendText(anyString(), anyString())).thenReturn(Mono.just("{}"));
        var sender = new VerificationJobs.Notifications(jdbc, max, "test");
        for (IssueStatus status : new IssueStatus[] {IssueStatus.OPEN, IssueStatus.ASSIGNED, IssueStatus.IN_PROGRESS, IssueStatus.RESOLVED}) {
            issues.changeStatus(id, "demo-dispatcher-1", status, "Проверка статуса");
            sender.sendPending();
        }
        assertEquals("Проверка статуса", issues.issue(id, "demo-resident-1").dispatcherComment().text());
        assertEquals(IssueStatus.RESOLVED, issues.issue(id, "demo-resident-1").dispatcherComment().status());
        assertEquals(4, sent(id));
        assertThrows(BusinessException.class, () -> issues.changeStatus(id, "demo-dispatcher-1", IssueStatus.OPEN, "Повтор"));
        assertEquals(4, sent(id));
        issues.verify(id, "demo-resident-1", false, "Лампа всё ещё не работает");
        sender.sendPending();
        assertEquals(5, sent(id));
        for (IssueStatus status : new IssueStatus[] {IssueStatus.ASSIGNED, IssueStatus.IN_PROGRESS, IssueStatus.RESOLVED}) {
            issues.changeStatus(id, "demo-dispatcher-1", status, "Повторная работа");
            sender.sendPending();
        }
        issues.verify(id, "demo-resident-1", true, "");
        sender.sendPending();
        assertEquals(9, sent(id));
        verify(max).sendText(eq("notification-test-user"), contains("→ «Открыта повторно»"));
        verify(max).sendText(eq("notification-test-user"), contains("Закрыта после подтверждения"));
        verify(max, times(2)).sendText(eq("notification-test-user"), contains("подтвердите результат"));
        assertEquals(9, jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE issue_id=? AND body LIKE '%Проверка фото и статусов%'", Integer.class, id));
    }

    @Test void notifiesLastAuthorAfterWithdrawalAndCancelsExpiredConfirmationRequest() {
        jdbc.update("UPDATE users SET max_user_id='notification-test-user' WHERE id='demo-resident-1'");
        Report withdrawn = report();
        String withdrawnId = issues.createIssue(withdrawn.id, "demo-resident-1").id();
        issues.withdrawReport(withdrawn.id, "demo-resident-1", "Решено самостоятельно");
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE issue_id=? AND body LIKE '%Отозвана%'", Integer.class, withdrawnId));
        String expiredId = issues.createIssue(report().id, "demo-resident-1").id();
        for (IssueStatus status : new IssueStatus[] {IssueStatus.OPEN, IssueStatus.ASSIGNED, IssueStatus.IN_PROGRESS, IssueStatus.RESOLVED}) {
            issues.changeStatus(expiredId, "demo-dispatcher-1", status, "Тест срока");
        }
        jdbc.update("UPDATE issues SET verification_due_at='2020-01-01T00:00:00Z' WHERE id=?", expiredId);
        issues.closeExpiredVerifications();
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE issue_id=? AND body LIKE '%Закрыта по истечении срока%'", Integer.class, expiredId));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE issue_id=? AND kind='VERIFY_RESULT' AND cancelled_at IS NOT NULL", Integer.class, expiredId));
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE issue_id=? AND kind GLOB 'STATUS_CHANGE_*' AND cancelled_at IS NULL AND body NOT LIKE '%Закрыта по истечении срока%'", Integer.class, expiredId));
    }

    private int sent(String id) { return jdbc.queryForObject("SELECT count(*) FROM notification_outbox WHERE issue_id=? AND sent_at IS NOT NULL", Integer.class, id); }
    private Report report() { return reports.createReport(new CreateReportCommand("demo-house-1", "demo-resident-1", "Проверка фото и статусов", "OTHER", "Подъезд 8", null)); }
}
