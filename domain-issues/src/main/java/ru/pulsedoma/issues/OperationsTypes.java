package ru.pulsedoma.issues;

public final class OperationsTypes {
    private OperationsTypes() {}

    public enum VisitState {
        OFFERED,
        CONFIRMED,
        CHANGE_REQUESTED,
        COMPLETED,
        CANCELLED
    }

    public enum MassState {
        DRAFT,
        ACTIVE,
        RESOLVED,
        CANCELLED
    }

    public enum WorkState {
        ASSIGNED,
        IN_PROGRESS,
        BLOCKED,
        REPORTED,
        ACCEPTED,
        REWORK,
        CANCELLED
    }

    public enum NoticeKind {
        PLANNED_WORK,
        OUTAGE
    }

    public enum Resource {
        WATER,
        HOT_WATER,
        ELECTRICITY,
        HEATING,
        GAS,
        LIFT,
        OTHER
    }

    public enum NoticeState {
        DRAFT,
        PUBLISHED,
        IN_PROGRESS,
        COMPLETED,
        CANCELLED,
        WITHDRAWN
    }

    public enum NotificationKind {
        VISIT,
        NOTICE,
        PAYMENT,
        MASS,
        REMINDER,
        ESCALATION
    }

    public enum DeadlineKind {
        CASE_STEP,
        WORK_ORDER,
        EXCHANGE_PACKET,
        LEGACY_HANDOFF,
        BILLING_CORRECTION,
        VISIT,
        SHARED_WORK
    }

    public enum Action {
        VISIT_OFFERED,
        VISIT_CHOSEN,
        VISIT_CHANGE_REQUESTED,
        VISIT_COMPLETED,
        VISIT_CANCELLED,
        MASS_SAVED,
        MASS_LINKED,
        MASS_UNLINKED,
        WORK_ASSIGNED,
        WORK_UPDATED,
        NOTICE_SAVED,
        SUBSCRIPTION_CHANGED,
        POLICY_CHANGED,
        AUTOMATIC_ESCALATION
    }
}
