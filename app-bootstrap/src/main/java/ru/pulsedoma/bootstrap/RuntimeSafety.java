package ru.pulsedoma.bootstrap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** Refuse accidentally public development credentials on a working MAX installation. */
@Component
public final class RuntimeSafety {
    public RuntimeSafety(
            Environment environment,
            @Value("${max.webhook.secret:}") String webhook,
            @Value("${storage.minio.access-key:}") String access,
            @Value("${storage.minio.secret-key:}") String secret) {
        if (environment.acceptsProfiles(Profiles.of("demo"))) return;
        if (webhook.isBlank() || webhook.length() < 32 || webhook.equals("local-dev-secret")) {
            throw new IllegalStateException(
                    "Set MAX_WEBHOOK_SECRET to a private value of at least 32 characters");
        }
        if (access.isBlank() || secret.length() < 16 || secret.equals("minioadmin")) {
            throw new IllegalStateException(
                    "Set private MINIO_ACCESS_KEY and MINIO_SECRET_KEY (at least 16 characters)");
        }
    }
}
