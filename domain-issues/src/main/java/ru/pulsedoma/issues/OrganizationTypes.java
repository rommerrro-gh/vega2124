package ru.pulsedoma.issues;

public final class OrganizationTypes {
    private OrganizationTypes() {}

    public enum Kind {
        MANAGEMENT_COMPANY,
        DEVELOPER,
        GENERAL_CONTRACTOR,
        CONTRACTOR,
        WARRANTY_SERVICE
    }

    public enum TeamRole {
        ADMIN,
        COORDINATOR,
        SPECIALIST
    }

    public enum Function {
        ENGINEER,
        WARRANTY,
        CONTRACTOR
    }

    public enum TransferState {
        OFFERED,
        ACCEPTED,
        RETURNED,
        REFUSED
    }
}
