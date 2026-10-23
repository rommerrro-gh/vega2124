package ru.pulsedoma.issues;

public final class WorkflowTypes {
    private WorkflowTypes() {}

    public enum WarrantyObject {
        CONSTRUCTION,
        EQUIPMENT,
        FINISHING,
        OTHER
    }

    public enum WarrantyState {
        DRAFT,
        VERIFIED,
        SUPERSEDED
    }

    public enum ReviewOutcome {
        NEED_INFORMATION,
        POSSIBLE_WARRANTY,
        WARRANTY_CONFIRMED,
        NON_WARRANTY,
        DISPUTED
    }

    public enum RefusalReason {
        NORMAL_WEAR,
        MISUSE,
        THIRD_PARTY_WORK,
        OUTSIDE_TERMS,
        OTHER
    }

    public enum RecipientType {
        DEVELOPER,
        GENERAL_CONTRACTOR,
        EXPERT,
        OTHER
    }

    public enum HandoffState {
        PREPARED,
        SENT,
        RECEIVED,
        RESPONDED,
        CANCELLED
    }

    public enum InspectionState {
        PROPOSED,
        SCHEDULED,
        COMPLETED,
        NO_ACCESS,
        CANCELLED
    }

    public enum Availability {
        PENDING,
        CONFIRMED,
        REQUEST_OTHER_TIME
    }

    public enum ResidentAction {
        CONFIRM_TIME,
        REQUEST_OTHER_TIME,
        COMMENT_ACT
    }

    public enum ActState {
        DRAFT,
        PUBLISHED
    }

    public enum WorkState {
        ASSIGNED,
        IN_PROGRESS,
        BLOCKED,
        REPORTED,
        NEED_REWORK,
        ACCEPTED,
        CANCELLED
    }

    public enum WorkBasis {
        WARRANTY,
        MAINTENANCE,
        OTHER
    }
}
