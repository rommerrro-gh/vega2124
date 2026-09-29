package ru.pulsedoma.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.AssertTrue;
import ru.pulsedoma.duplicates.LocationFeatures;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;
import ru.pulsedoma.issues.CreateReportCommand;
import ru.pulsedoma.issues.Report;
import ru.pulsedoma.issues.ReportService;
import ru.pulsedoma.issues.ReportCategory;
import ru.pulsedoma.issues.MiniAppService;

import java.util.List;
import java.security.Principal;
import java.time.Instant;

@Validated
@RestController
@RequestMapping("/v1/reports")
public class ReportsController {
    private final ReportService reports;
    private final MiniAppService issues;

    public ReportsController(ReportService reports, MiniAppService issues) {
        this.reports = reports;
        this.issues = issues;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public CreateReportResponse create(@Valid @RequestBody CreateReportRequest request, Principal principal) {
        Report report = reports.createReport(new CreateReportCommand(
                request.houseId(), principal.getName(), request.text(), request.category().name(),
                request.location(), request.occurredAt(), request.locationFeatures() == null ? null : request.locationFeatures().features()));
        return new CreateReportResponse(report.id, report.correlationId, report.candidates.stream().map(candidate ->
                new CandidateResponse(candidate.issueId(), candidate.score(), candidate.reasons(), issues.candidateSummary(candidate.issueId(), principal.getName()))).toList());
    }

    @PostMapping("/{id}/withdraw")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void withdraw(@PathVariable @NotBlank String id, @Valid @RequestBody WithdrawReportRequest request,
                         Principal principal) {
        issues.withdrawReport(id, principal.getName(), request.reason());
    }

    public record CreateReportRequest(@NotBlank String houseId,
                                      @NotBlank @Size(max = 4000) String text,
                                      @NotNull ReportCategory category,
                                      @Size(max = 160) String location,
                                      Instant occurredAt, @Valid LocationRequest locationFeatures) {
        @AssertTrue(message = "Укажите место проблемы")
        public boolean isLocationPresent() { return locationFeatures != null || location != null && !location.isBlank(); }
    }
    public record LocationRequest(@NotNull LocationFeatures.Area area,
                                  @Min(1) @Max(100) Integer entrance, @Min(1) @Max(200) Integer floor,
                                  LocationFeatures.LiftType liftType, @Min(1) @Max(100) Integer liftNumber,
                                  LocationFeatures.Coverage coverage, LocationFeatures.OutdoorObject object,
                                  @Min(1) @Max(100) Integer site, @Size(max = 160) String details) {
        LocationFeatures features() { return new LocationFeatures(area, entrance, floor, liftType, liftNumber, coverage, object, site, details); }
    }
    public record CreateReportResponse(String reportId, String correlationId,
                                       List<CandidateResponse> candidates) {}
    public record CandidateResponse(String issueId, double score, List<String> reasons, MiniAppService.CandidateSummary issue) {}
    public record WithdrawReportRequest(@NotBlank @Size(max = 1000) String reason) {}
}
