package ru.pulsedoma.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.pulsedoma.duplicates.DuplicateCandidate;
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
    public CreateReportResponse create(@Valid @RequestBody CreateReportRequest request, Principal principal) {
        Report report = reports.createReport(new CreateReportCommand(
                request.houseId(), principal.getName(), request.text(), request.category().name(),
                request.location(), request.occurredAt()));
        return new CreateReportResponse(report.id, report.correlationId, report.candidates);
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
                                      @NotBlank @Size(max = 160) String location,
                                      Instant occurredAt) {}
    public record CreateReportResponse(String reportId, String correlationId,
                                       List<DuplicateCandidate> candidates) {}
    public record WithdrawReportRequest(@NotBlank @Size(max = 1000) String reason) {}
}
