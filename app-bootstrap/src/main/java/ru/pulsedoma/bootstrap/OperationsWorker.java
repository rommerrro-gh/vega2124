package ru.pulsedoma.bootstrap;

import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.pulsedoma.issues.*;
import ru.pulsedoma.max.MaxApiClient;

@Component
@Profile("!demo")
public class OperationsWorker {
    private final OperationsMonitoringService monitoring;
    private final OperationsNotifications notifications;
    private final MaxApiClient max;
    private final String token;
    private final MaxBotMessages messages;

    public OperationsWorker(
            OperationsMonitoringService monitoring,
            OperationsNotifications notifications,
            MaxApiClient max,
            MaxBotMessages messages,
            @Value("${max.api.token:}") String token) {
        this.monitoring = monitoring;
        this.notifications = notifications;
        this.max = max;
        this.token = token;
        this.messages = messages;
    }

    @Scheduled(fixedDelayString = "${operations.delivery-ms:10000}")
    public void deliver() {
        if (token.isBlank()) return;
        Set<String> users = new HashSet<>();
        for (var n : notifications.claim()) {
            String id = n.get("id").toString(), lease = n.get("lease_token").toString();
            int attempts = ((Number) n.get("attempts")).intValue();
            try {
                if (!users.add(n.get("user_id").toString()) || !notifications.allowed(n)) {
                    notifications.retry(id, lease, attempts, "Deferred");
                    continue;
                }
                max.sendMessage(
                                n.get("max_user_id").toString(),
                                messages.body(n.get("body").toString(), List.of(), null))
                        .block(Duration.ofSeconds(10));
                notifications.delivered(id, lease);
            } catch (Exception error) {
                notifications.retry(id, lease, attempts + 1, error.getClass().getSimpleName());
            }
        }
    }
}
