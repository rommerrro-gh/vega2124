package ru.pulsedoma.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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

import java.util.List;

@Validated
@RestController
@RequestMapping("/v1/reports")
public class ReportsController {
    private final ReportService reports;

    public ReportsController(ReportService reports) {
        this.reports = reports;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateReportResponse create(@Valid @RequestBody CreateReportRequest request) {
        Report report = reports.createReport(new CreateReportCommand(
                request.houseId(), request.authorId(), request.text(), request.category()));
        return new CreateReportResponse(report.id, report.correlationId, report.candidates, report.issueId);
    }

    @PostMapping("/{id}/withdraw")
    public void withdraw(@PathVariable @NotBlank String id, @Valid @RequestBody WithdrawReportRequest request) {
        throw PendingOperation.notImplemented();
    }

    public record CreateReportRequest(@NotBlank String houseId, @NotBlank String authorId,
                                      @NotBlank @Size(max = 4000) String text,
                                      @Size(max = 100) String category) {}
    public record CreateReportResponse(String reportId, String correlationId,
                                       List<DuplicateCandidate> candidates, String issueId) {}
    public record WithdrawReportRequest(@NotBlank @Size(max = 1000) String reason) {}
}
