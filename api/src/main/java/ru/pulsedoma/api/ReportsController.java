package ru.pulsedoma.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/v1/reports")
public class ReportsController {
    @PostMapping
    public void create(@Valid @RequestBody CreateReportRequest request) {
        throw PendingOperation.notImplemented();
    }

    @PostMapping("/{id}/withdraw")
    public void withdraw(@PathVariable @NotBlank String id, @Valid @RequestBody WithdrawReportRequest request) {
        throw PendingOperation.notImplemented();
    }

    public record CreateReportRequest(@NotBlank String houseId, @NotBlank @Size(max = 4000) String text) {}
    public record WithdrawReportRequest(@NotBlank @Size(max = 1000) String reason) {}
}
