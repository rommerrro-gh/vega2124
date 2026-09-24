package ru.pulsedoma.external;

import org.springframework.stereotype.Component;
import ru.pulsedoma.issues.ExternalTicketPort;
import ru.pulsedoma.issues.ExternalTicketStatus;

@Component
public final class ContractTicketAdapter implements ExternalTicketPort {
    public String createTicket(String issueId) {
        return "contract-" + issueId;
    }

    public void updateTicket(String issueId) {
    }

    public ExternalTicketStatus getTicket(String externalId) {
        return ExternalTicketStatus.REGISTERED;
    }

    public void addComment(String externalId, String comment) {
    }

    public void attachEvidence(String externalId, String storageKey) {
    }

    public boolean healthCheck() {
        return true;
    }
}
