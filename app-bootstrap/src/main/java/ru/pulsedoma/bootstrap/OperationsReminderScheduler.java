package ru.pulsedoma.bootstrap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.pulsedoma.issues.OperationsMonitoringService;

/** Inbox reminders work on the browser demo too; only the MAX delivery worker sends externally. */
@Component
@ConditionalOnProperty(
        name = "operations.scheduler-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class OperationsReminderScheduler {
    private final OperationsMonitoringService monitoring;

    private final ru.pulsedoma.issues.SharedRequestService shared;

    public OperationsReminderScheduler(
            OperationsMonitoringService monitoring,
            ru.pulsedoma.issues.SharedRequestService shared) {
        this.monitoring = monitoring;
        this.shared = shared;
    }

    @Scheduled(
            initialDelayString = "${operations.scan-ms:60000}",
            fixedDelayString = "${operations.scan-ms:60000}")
    public void scan() {
        shared.reconcilePendingConfirmations();
        monitoring.scan();
    }
}
