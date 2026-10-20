package ru.pulsedoma.issues;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import static ru.pulsedoma.issues.TariffTypes.*;
public final class TariffRequests {
    private TariffRequests() {}
    public record Draft(@NotBlank @Size(max=160) String title, @NotNull Category category,
        @NotBlank @Size(max=250) String provider,@NotBlank @Size(max=500) String applicability,
        @NotNull Unit unit,@NotBlank @Size(max=60) String unitLabel,
        @NotNull @DecimalMin("0") @Digits(integer=10,fraction=6) BigDecimal amount,
        @NotNull LocalDate validFrom,LocalDate validUntil,
        @NotBlank @Size(max=2000) String sourceReference,@Size(max=1500) String sourceUrl,
        @NotBlank @Size(max=3000) String explanation,@NotNull @Min(0) Integer revision,
        @NotBlank @Size(max=1000) String reason) {}
    public record Decision(@NotNull @Min(0) Integer revision,@NotBlank @Size(max=1000) String reason) {}
    public record Grant(@NotNull Permission role,boolean enabled,@NotBlank @Size(max=1000) String reason) {}
    public record Copy(@NotNull @Min(0) Integer revision,@NotNull LocalDate validFrom,boolean correction,
        @NotBlank @Size(max=1000) String reason) {}
}
