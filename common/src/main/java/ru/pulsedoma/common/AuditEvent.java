package ru.pulsedoma.common;

import java.time.Instant;

public record AuditEvent(String actor, String action, String entity, String entityId,
                         String beforeJson, String afterJson, Instant createdAt) {}
