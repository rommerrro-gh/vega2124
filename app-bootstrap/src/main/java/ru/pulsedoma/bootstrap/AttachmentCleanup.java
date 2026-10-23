package ru.pulsedoma.bootstrap;

import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import ru.pulsedoma.issues.AttachmentMaintenance;

@Component
@Order(900)
@ConditionalOnProperty(name = "maintenance.prune-files", havingValue = "true")
public class AttachmentCleanup implements ApplicationRunner {
    private final AttachmentMaintenance maintenance;
    private final org.springframework.context.ConfigurableApplicationContext context;

    public AttachmentCleanup(
            AttachmentMaintenance maintenance,
            org.springframework.context.ConfigurableApplicationContext context) {
        this.maintenance = maintenance;
        this.context = context;
    }

    public void run(ApplicationArguments arguments) {
        var result = maintenance.prune();
        System.out.println(
                "ATTACHMENTS_SCANNED="
                        + result.stored()
                        + "; REFERENCED="
                        + result.referenced()
                        + "; REMOVED="
                        + result.removed());
        System.exit(SpringApplication.exit(context, () -> 0));
    }
}
