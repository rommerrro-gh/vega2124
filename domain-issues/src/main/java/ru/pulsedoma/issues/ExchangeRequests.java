package ru.pulsedoma.issues;

import static ru.pulsedoma.issues.ExchangeTypes.*;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public final class ExchangeRequests {
    private ExchangeRequests() {}

    public record Channel(
            @NotBlank @Size(max = 250) String organizationName,
            @NotNull Participant participantType,
            @NotNull Mode mode,
            @NotBlank @Size(max = 1000) String contact,
            @NotBlank @Size(max = 2000) String sourceReference,
            @NotBlank @Size(max = 2000) String disclosureScope,
            @NotNull ChannelState state,
            @NotNull @Min(0) Integer revision,
            @NotBlank @Size(max = 1000) String reason) {}

    public record Packet(
            @NotBlank @Size(max = 80) String channelId,
            @NotBlank @Size(max = 200) String subject,
            @NotBlank @Size(max = 5000) String statement,
            @NotBlank @Size(max = 3000) String disclosureBasis,
            @NotBlank @Size(max = 2000) String authority,
            @NotBlank @Size(max = 1000) String materialsNote,
            boolean includeAddress,
            @NotNull @Size(max = 20) List<@NotBlank @Size(max = 80) String> versionIds,
            Instant dueAt,
            @NotBlank @Size(max = 1000) String dueBasis,
            @NotNull @Min(0) Integer revision,
            @NotNull @Min(0) Integer caseRevision,
            @NotBlank @Size(max = 1000) String reason) {}

    public record Decision(
            @NotNull @Min(0) Integer revision,
            @NotNull @Min(0) Integer caseRevision,
            @NotBlank @Size(max = 2000) String note,
            @Size(max = 80) String evidenceVersionId) {}

    public record Schedule(
            @NotNull @Min(0) Integer revision,
            @NotNull @Min(0) Integer caseRevision,
            Instant dueAt,
            @NotBlank @Size(max = 1000) String dueBasis,
            @NotBlank @Size(max = 1000) String reason) {}

    public record Invite(
            @NotNull @Min(0) Integer revision,
            @NotNull @Min(0) Integer caseRevision,
            @NotBlank @Size(max = 250) String recipientName,
            @NotBlank @Size(max = 2000) String participationBasis,
            @Min(1) @Max(14) int days) {}

    public record Accept(@NotBlank @Size(max = 120) String token) {}

    public record RecipientAction(
            @NotNull @Min(0) Integer revision, @NotBlank @Size(max = 2000) String note) {}

    public record Reply(
            @NotNull @Min(0) Integer revision, @NotBlank @Size(max = 6000) String answer) {}

    public record ManualAnswer(
            @NotNull @Min(0) Integer revision,
            @NotNull @Min(0) Integer caseRevision,
            @NotBlank @Size(max = 6000) String answer,
            @NotBlank @Size(max = 80) String evidenceVersionId) {}

    public record Review(
            @NotNull @Min(0) Integer revision,
            @NotNull @Min(0) Integer caseRevision,
            @NotNull ReviewDecision decision,
            @NotBlank @Size(max = 3000) String note,
            @NotBlank @Size(max = 3000) String residentSummary) {}

    public record Assignment(
            @NotBlank @Size(max = 80) String ownerId,
            @Size(max = 80) String deputyId,
            @NotNull @Min(0) Integer revision,
            @NotBlank @Size(max = 1000) String reason) {}

    public record ControlDecision(
            @NotNull @Min(0) Integer revision, @NotBlank @Size(max = 2000) String note) {}

    public record Escalate(
            @NotBlank @Size(max = 80) String targetId,
            @NotNull @Min(0) Integer revision,
            @NotBlank @Size(max = 2000) String reason) {}

    public record Escalation(
            @NotNull EscalationState state,
            @NotNull @Min(0) Integer revision,
            @NotBlank @Size(max = 2000) String note) {}

    public record Pilot(
            @NotNull PilotState state,
            @NotBlank @Size(max = 3000) String scope,
            @NotBlank @Size(max = 2000) String acceptanceReference,
            @NotBlank @Size(max = 2000) String restoreReference,
            @NotBlank @Size(max = 2000) String contactReference,
            @NotBlank @Size(max = 2000) String trainingReference,
            @NotBlank @Size(max = 3000) String rollbackPlan,
            @NotNull @Min(0) Integer revision) {}
}
