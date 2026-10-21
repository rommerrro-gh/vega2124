package ru.pulsedoma.issues;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.math.BigDecimal;
import java.util.List;
import static ru.pulsedoma.issues.OperationsTypes.*;
public final class OperationsRequests {
 private OperationsRequests(){}
 public record Slot(@NotNull Instant startsAt,@NotNull Instant endsAt){}
 public record Visit(@NotBlank @Size(max=1000) String purpose,@NotBlank @Size(max=80) String workerId,
   @NotNull @Size(min=1,max=5) List<@NotNull @Valid Slot> slots,@NotNull @Min(0) Integer caseRevision,@NotBlank @Size(max=1000) String reason){}
 public record VisitChoice(@NotBlank @Size(max=80) String slotId,@NotNull @Min(0) Integer revision,@NotNull @Min(0) Integer caseRevision){}
 public record VisitDecision(@NotNull @Min(0) Integer revision,@NotNull @Min(0) Integer caseRevision,@NotBlank @Size(max=2000) String note){}
 public record Mass(@NotBlank @Size(max=200) String title,@NotBlank @Size(max=500) String scope,@NotBlank @Size(max=3000) String summary,
   @NotBlank @Size(max=600) String nextStep,@NotNull MassState state,Instant dueAt,@NotBlank @Size(max=1000) String dueBasis,
   @NotNull @Min(0) Integer revision,@NotBlank @Size(max=1000) String reason){}
 public record Link(@NotBlank @Size(max=80) String problemId,@NotNull @Min(0) Integer caseRevision,@NotBlank @Size(max=1000) String reason){}
 public record Unlink(@NotNull @Min(0) Integer caseRevision,@NotBlank @Size(max=1000) String reason){}
 public record Join(@NotBlank @Size(max=80) String clientRequestId,@Size(max=2000) String description,@Size(max=160) String location){}
 public record SharedWork(@NotBlank @Size(max=200) String title,@NotBlank @Size(max=2000) String scope,@NotBlank @Size(max=80) String workerId,
   Instant dueAt,@NotBlank @Size(max=1000) String dueBasis,@NotNull @Min(0) Integer revision){}
 public record WorkDecision(@NotNull WorkState state,@NotNull @Min(0) Integer revision,@NotBlank @Size(max=2000) String note){}
 public record Notice(@NotBlank @Size(max=200) String title,@NotNull NoticeKind kind,@NotNull Resource resource,
   @NotBlank @Size(max=500) String scope,@NotBlank @Size(max=3000) String summary,@NotBlank @Size(max=2000) String advice,
   @NotBlank @Size(max=500) String contact,@NotBlank @Size(max=2000) String sourceReference,
   @NotNull Instant startsAt,Instant endsAt,@NotBlank @Size(max=1000) String endBasis,@NotNull NoticeState state,
   @NotNull @Min(0) Integer revision,@NotBlank @Size(max=1000) String reason){}
 public record Subscription(boolean notices,boolean payments,boolean massProblems){}
 public record Policy(boolean enabled,@Min(1) @Max(72) int beforeHours,@Min(6) @Max(168) int repeatHours,
   @Min(1) @Max(168) int escalateHours,@NotBlank @Size(max=80) String managerId,@NotNull @Min(0) Integer revision,@NotBlank @Size(max=1000) String reason){}
 public record Comparison(@NotNull LocalDate from,@NotNull LocalDate to,@Size(max=80) String seriesId,
   @DecimalMin("0") @Digits(integer=10,fraction=6) BigDecimal quantity){}
}
