package ru.pulsedoma.external;

import org.springframework.stereotype.Component;
import ru.pulsedoma.issues.ExternalTicketPort;

@Component
public final class ContractTicketAdapter implements ExternalTicketPort {
    public String createTicket(String issueId) {
        return "contract-" + issueId;
    }

    public void updateTicket(String issueId) {
    }

    public String getTicket(String externalId) {
        return "REGISTERED";
    }

    public void addComment(String externalId, String comment) {
    }

    public void attachEvidence(String externalId, String storageKey) {
    }

    public boolean healthCheck() {
        return true;
    }
}
