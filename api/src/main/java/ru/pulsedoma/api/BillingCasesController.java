package ru.pulsedoma.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.List;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import ru.pulsedoma.issues.*;

@RestController
@Validated
public class BillingCasesController {
    private final BillingCaseService service;

    public BillingCasesController(BillingCaseService service) {
        this.service = service;
    }

    @GetMapping("/v1/houses/{house}/billing-queue")
    public List<BillingCaseService.QueueItem> queue(
            @PathVariable @NotBlank String house, Principal p) {
        return service.queue(house, p.getName());
    }

    @GetMapping("/v1/cases/{id}/billing")
    public BillingCaseService.Workspace workspace(@PathVariable @NotBlank String id, Principal p) {
        return service.workspace(id, p.getName());
    }

    @PostMapping("/v1/cases/{id}/billing/reviews")
    public BillingCaseService.Workspace create(
            @PathVariable @NotBlank String id,
            @Valid @RequestBody BillingRequests.Review r,
            Principal p) {
        return service.saveReview(id, null, r, p.getName());
    }

    @PutMapping("/v1/cases/{id}/billing/reviews/{review}")
    public BillingCaseService.Workspace save(
            @PathVariable @NotBlank String id,
            @PathVariable @NotBlank String review,
            @Valid @RequestBody BillingRequests.Review r,
            Principal p) {
        return service.saveReview(id, review, r, p.getName());
    }

    @PostMapping("/v1/billing-reviews/{id}/publish")
    public BillingCaseService.Workspace publish(
            @PathVariable @NotBlank String id,
            @Valid @RequestBody BillingRequests.Decision r,
            Principal p) {
        return service.publishReview(id, r, p.getName());
    }

    @PostMapping("/v1/billing-reviews/{id}/cancel")
    public BillingCaseService.Workspace cancelReview(
            @PathVariable @NotBlank String id,
            @Valid @RequestBody BillingRequests.Decision r,
            Principal p) {
        return service.cancelReview(id, r, p.getName());
    }

    @PostMapping("/v1/cases/{id}/billing/questions")
    public BillingCaseService.Workspace ask(
            @PathVariable @NotBlank String id,
            @Valid @RequestBody BillingRequests.Question r,
            Principal p) {
        return service.ask(id, r, p.getName());
    }

    @PostMapping("/v1/billing-questions/{id}/answer")
    public BillingCaseService.Workspace answer(
            @PathVariable @NotBlank String id,
            @Valid @RequestBody BillingRequests.Answer r,
            Principal p) {
        return service.answer(id, r, p.getName());
    }

    @PostMapping("/v1/billing-questions/{id}/cancel")
    public BillingCaseService.Workspace cancelQuestion(
            @PathVariable @NotBlank String id,
            @Valid @RequestBody BillingRequests.Decision r,
            Principal p) {
        return service.cancelQuestion(id, r, p.getName());
    }

    @PostMapping("/v1/cases/{id}/billing/corrections")
    public BillingCaseService.Workspace correction(
            @PathVariable @NotBlank String id,
            @Valid @RequestBody BillingRequests.Correction r,
            Principal p) {
        return service.prepareCorrection(id, r, p.getName());
    }

    @PatchMapping("/v1/billing-corrections/{id}")
    public BillingCaseService.Workspace move(
            @PathVariable @NotBlank String id,
            @Valid @RequestBody BillingRequests.CorrectionMove r,
            Principal p) {
        return service.moveCorrection(id, r, p.getName());
    }

    @PostMapping("/v1/billing-corrections/{id}/schedule")
    public BillingCaseService.Workspace schedule(
            @PathVariable @NotBlank String id,
            @Valid @RequestBody BillingRequests.Schedule r,
            Principal p) {
        return service.reschedule(id, r, p.getName());
    }

    @GetMapping("/v1/billing-corrections/{id}/print")
    public ResponseEntity<byte[]> print(@PathVariable @NotBlank String id, Principal p) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
                .header("Content-Disposition", "attachment; filename=\"billing-correction.html\"")
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .header("X-Content-Type-Options", "nosniff")
                .body(service.printCorrection(id, p.getName()).getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping("/v1/cases/{id}/billing/propose")
    public ResidentCaseService.CaseView propose(
            @PathVariable @NotBlank String id,
            @Valid @RequestBody BillingRequests.Decision r,
            Principal p) {
        return service.propose(id, r, p.getName());
    }
}
