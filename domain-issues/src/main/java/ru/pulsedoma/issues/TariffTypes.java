package ru.pulsedoma.issues;

public final class TariffTypes {
    private TariffTypes() {}

    public enum State {
        DRAFT,
        PUBLISHED,
        SUPERSEDED,
        WITHDRAWN
    }

    public enum Permission {
        EDITOR,
        PUBLISHER
    }

    public enum Category {
        UTILITY,
        HOUSING,
        CAPITAL_REPAIR,
        OTHER
    }

    public enum Unit {
        M3,
        KWH,
        GCAL,
        M2,
        PERSON,
        MONTH,
        OTHER
    }

    public enum Action {
        CREATED,
        EDITED,
        DOCUMENT_ADDED,
        DOCUMENT_REMOVED,
        PUBLISHED,
        SUPERSEDED,
        WITHDRAWN
    }
}
