package ru.pulsedoma.issues;

// FR-ISS-014, AC-10: domain port for a contract or live UK connector.
public interface ExternalTicketPort {
    String createTicket(String issueId);

    void updateTicket(String issueId);

    ExternalTicketStatus getTicket(String externalId);

    void addComment(String externalId, String comment);

    void attachEvidence(String externalId, String storageKey);

    boolean healthCheck();
}
