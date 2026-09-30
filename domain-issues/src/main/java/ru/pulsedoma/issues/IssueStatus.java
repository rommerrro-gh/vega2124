package ru.pulsedoma.issues;

// FR-ISS-005, FR-ISS-009, FR-ISS-010, AC-15
public enum IssueStatus {
    DRAFT,
    OPEN,
    ASSIGNED,
    IN_PROGRESS,
    RESOLVED,
    VERIFICATION_72H,
    CLOSED_CONFIRMED,
    CLOSED_UNCONFIRMED,
    REVIEW_REQUIRED,
    REOPENED,
    WITHDRAWN,
    REJECTED;

    public String displayName() {
        return switch (this) {
            case DRAFT -> "Ожидает диспетчера";
            case OPEN -> "Принята";
            case ASSIGNED -> "Назначена";
            case IN_PROGRESS -> "В работе";
            case RESOLVED -> "Выполнена";
            case VERIFICATION_72H -> "Ожидает вашего подтверждения";
            case CLOSED_CONFIRMED -> "Закрыта после подтверждения";
            case CLOSED_UNCONFIRMED -> "Закрыта по истечении срока";
            case REVIEW_REQUIRED -> "Требует решения диспетчера";
            case REOPENED -> "Открыта повторно";
            case WITHDRAWN -> "Отозвана";
            case REJECTED -> "Отклонена";
        };
    }
}
