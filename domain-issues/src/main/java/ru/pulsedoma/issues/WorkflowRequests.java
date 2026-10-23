package ru.pulsedoma.issues;

import static ru.pulsedoma.issues.WorkflowTypes.*;

import jakarta.validation.constraints.*;
import java.time.*;

public final class WorkflowRequests {
    private WorkflowRequests() {}

    public record Warranty(
            @NotBlank @Size(max = 160) String title,
            @NotNull WarrantyObject objectType,
            @NotNull WarrantyState state,
            @Size(max = 250) String developer,
            @Size(max = 250) String contractor,
            @Size(max = 500) String contractReference,
            @NotBlank @Size(max = 3000) String basis,
            LocalDate transferredOn,
            LocalDate startsOn,
            LocalDate endsOn,
            @NotNull @Min(0) Integer revision,
            @NotBlank @Size(max = 1000) String reason) {}

    public record Invite(
            @NotNull CaseFunction function,
            @NotBlank @Size(max = 250) String organizationLabel,
            @NotBlank @Size(max = 1000) String authority,
            @Min(1) @Max(14) int days) {}

    public record Accept(@NotBlank @Size(max = 120) String token) {}

    public record Review(
            @Size(max = 80) String warrantyId,
            @NotNull ReviewOutcome outcome,
            RefusalReason refusalReason,
            @NotBlank @Size(max = 3000) String basis,
            @NotBlank @Size(max = 2000) String explanation,
            @Size(max = 80) String evidenceVersionId,
            @NotNull @Min(0) Integer revision) {}

    public record Handoff(
            @NotBlank @Size(max = 250) String recipient,
            @NotNull RecipientType recipientType,
            @NotBlank @Size(max = 500) String channel,
            @NotBlank @Size(max = 3000) String basis,
            @NotBlank @Size(max = 2000) String authority,
            @NotBlank @Size(max = 4000) String statement,
            Instant dueAt,
            @NotNull @Min(0) Integer revision) {}

    public record HandoffUpdate(
            @NotNull HandoffState state,
            @NotBlank @Size(max = 2000) String note,
            @Size(max = 80) String evidenceVersionId,
            @NotNull @Min(0) Integer revision,
            @NotNull @Min(0) Integer caseRevision) {}

    public record Inspection(
            @NotBlank @Size(max = 80) String inspectorId,
            @NotBlank @Size(max = 300) String location,
            @NotBlank @Size(max = 1500) String participants,
            @NotNull Instant startsAt,
            @NotNull Instant endsAt,
            @NotNull @Min(0) Integer revision,
            @NotNull @Min(0) Integer caseRevision,
            @Size(max = 1000) String reason) {}

    public record InspectionUpdate(
            @NotNull InspectionState state,
            @NotBlank @Size(max = 1500) String reason,
            @NotNull @Min(0) Integer revision,
            @NotNull @Min(0) Integer caseRevision) {}

    public record Act(
            @NotNull Instant inspectedAt,
            @NotBlank @Size(max = 2000) String participants,
            @NotBlank @Size(max = 4000) String findings,
            @NotBlank @Size(max = 4000) String defects,
            @Size(max = 2000) String preliminaryCause,
            @Size(max = 80) String evidenceVersionId,
            @Size(max = 1000) String correctionReason,
            @NotNull @Min(0) Integer revision,
            @NotNull @Min(0) Integer caseRevision) {}

    public record Revision(
            @NotNull @Min(0) Integer revision, @NotNull @Min(0) Integer caseRevision) {}

    public record InspectionResponse(
            @NotNull ResidentAction action,
            @Size(max = 80) String actId,
            @Size(max = 2000) String comment,
            @NotNull @Min(0) Integer revision,
            @NotNull @Min(0) Integer caseRevision) {}

    public record Work(
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 4000) String scope,
            @NotBlank @Size(max = 80) String assigneeId,
            @NotNull WorkBasis basisType,
            @NotBlank @Size(max = 3000) String basis,
            Instant dueAt,
            @NotBlank @Size(max = 1000) String dueBasis,
            @Size(max = 80) String actId,
            @NotNull @Min(0) Integer revision) {}

    public record WorkUpdate(
            @NotNull WorkState state,
            @NotBlank @Size(max = 2000) String note,
            @Size(max = 80) String assigneeId,
            Instant dueAt,
            @Size(max = 1000) String dueBasis,
            @Size(max = 80) String evidenceVersionId,
            @NotNull @Min(0) Integer revision,
            @NotNull @Min(0) Integer caseRevision) {}
}
