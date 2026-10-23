package ru.pulsedoma.issues;

public final class ExchangeTypes {
    private ExchangeTypes() {}

    public enum Participant {
        UK,
        WARRANTY,
        CONTRACTOR,
        BILLING,
        OTHER
    }

    public enum Mode {
        MANUAL,
        PORTAL
    }

    public enum ChannelState {
        DRAFT,
        VERIFIED,
        RETIRED
    }

    public enum PacketState {
        DRAFT,
        READY,
        SENT,
        RECEIVED,
        ANSWERED,
        REVIEWED,
        CANCELLED
    }

    public enum ReplyState {
        DRAFT,
        PUBLISHED,
        RETURNED,
        ACCEPTED
    }

    public enum Origin {
        RECIPIENT,
        MANUAL
    }

    public enum ReviewDecision {
        ACCEPT,
        CLARIFY
    }

    public enum EscalationState {
        OPEN,
        ACKNOWLEDGED,
        CLOSED
    }

    public enum PilotState {
        PREPARING,
        READY_FOR_ACCEPTANCE,
        PAUSED
    }

    public enum Alert {
        UNASSIGNED,
        OVERDUE,
        OWNER_UNAVAILABLE,
        NO_DEPUTY,
        WAITING_RESIDENT,
        WAITING_RECEIPT,
        WAITING_ANSWER,
        NEEDS_REVIEW,
        WORK_OVERDUE,
        ESCALATED
    }

    public enum Action {
        CHANNEL_SAVED,
        PACKET_DRAFTED,
        PACKET_SEALED,
        PACKET_SENT,
        PACKET_RECEIVED,
        PACKET_CANCELLED,
        DEADLINE_CHANGED,
        INVITATION_CREATED,
        INVITATION_REVOKED,
        INVITATION_ACCEPTED,
        REPLY_DRAFTED,
        REPLY_FILE_ADDED,
        REPLY_PUBLISHED,
        REPLY_REVIEWED,
        ASSIGNMENT_CHANGED,
        DEPUTY_TOOK_OVER,
        ESCALATED,
        ESCALATION_UPDATED,
        PILOT_SAVED
    }
}
