package ru.pulsedoma.issues;

import static ru.pulsedoma.issues.BillingTypes.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class BillingRequests {
    private BillingRequests() {}

    public record Line(
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Pattern(regexp = "[12]\\d{3}-(0[1-9]|1[0-2])") String period,
            @NotBlank @Size(max = 60) String unit,
            @NotNull Calculation mode,
            @NotNull @Digits(integer = 10, fraction = 2) BigDecimal charged,
            @DecimalMin("0") @Digits(integer = 10, fraction = 6) BigDecimal quantity,
            @DecimalMin("0") @Digits(integer = 10, fraction = 6) BigDecimal rate,
            @NotNull @Digits(integer = 10, fraction = 2) BigDecimal adjustment,
            @NotBlank @Size(max = 2000) String sourceReference,
            @Size(max = 80) String evidenceVersionId,
            @Size(max = 80) String tariffId) {}

    public record Review(
            @NotBlank @Pattern(regexp = "[12]\\d{3}-(0[1-9]|1[0-2])") String period,
            @NotNull Outcome outcome,
            @NotBlank @Size(max = 3000) String findings,
            @NotBlank @Size(max = 3000) String explanation,
            @NotBlank @Size(max = 2000) String limitations,
            @NotBlank @Size(max = 2000) String sourceReference,
            @Size(max = 80) String evidenceVersionId,
            @NotNull @Size(max = 50) List<@NotNull @Valid Line> lines,
            @NotNull @Min(0) Integer revision,
            @NotBlank @Size(max = 1000) String reason) {}

    public record Decision(
            @NotNull @Min(0) Integer revision, @NotBlank @Size(max = 2000) String note) {}

    public record Question(
            @NotBlank @Size(max = 1500) String question,
            @NotBlank @Size(max = 1500) String purpose,
            @NotNull @Min(0) Integer revision) {}

    public record Answer(
            @NotBlank @Size(max = 2000) String response,
            @Size(max = 80) String evidenceVersionId,
            @NotNull @Min(0) Integer revision) {}

    public record Correction(
            @NotBlank @Size(max = 80) String reviewId,
            @NotBlank @Pattern(regexp = "[12]\\d{3}-(0[1-9]|1[0-2])") String period,
            @NotBlank @Size(max = 1000) String scope,
            @NotNull @Digits(integer = 10, fraction = 2) BigDecimal beforeAmount,
            @NotNull @Digits(integer = 10, fraction = 2) BigDecimal afterAmount,
            @NotBlank @Size(max = 3000) String basis,
            @NotBlank @Size(max = 250) String recipient,
            @NotBlank @Size(max = 500) String channel,
            @NotBlank @Size(max = 2000) String authority,
            Instant dueAt,
            @NotBlank @Size(max = 1000) String dueBasis,
            @NotNull @Min(0) Integer revision) {}

    public record Schedule(
            Instant dueAt,
            @NotBlank @Size(max = 1000) String dueBasis,
            @NotBlank @Size(max = 1000) String note,
            @NotNull @Min(0) Integer revision) {}

    public record CorrectionMove(
            @NotNull CorrectionState state,
            @NotBlank @Size(max = 2000) String note,
            @Size(max = 80) String evidenceVersionId,
            @Digits(integer = 10, fraction = 2) BigDecimal actualAfter,
            @NotNull @Min(0) Integer revision) {}
}
