package ru.pulsedoma.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import ru.pulsedoma.issues.*;

@RestController
@Validated
public class OperationsController {
    private final CaseVisitService visits;
    private final HouseOperationsService houses;
    private final OperationsMonitoringService monitoring;
    private final PaymentComparisonService payments;
    private final OperationsNotifications notifications;

    public OperationsController(
            CaseVisitService visits,
            HouseOperationsService houses,
            OperationsMonitoringService monitoring,
            PaymentComparisonService payments,
            OperationsNotifications notifications) {
        this.visits = visits;
        this.houses = houses;
        this.monitoring = monitoring;
        this.payments = payments;
        this.notifications = notifications;
    }

    @GetMapping("/v1/cases/{id}/visits")
    public CaseVisitService.Workspace visits(@PathVariable @NotBlank String id, Principal p) {
        return visits.workspace(id, p.getName());
    }

    @PostMapping("/v1/cases/{id}/visits")
    public CaseVisitService.Workspace offer(
            @PathVariable @NotBlank String id,
            @Valid @RequestBody OperationsRequests.Visit r,
            Principal p) {
        return visits.offer(id, r, p.getName());
    }

    @PostMapping("/v1/visits/{id}/choose")
    public CaseVisitService.Workspace choose(
            @PathVariable @NotBlank String id,
            @Valid @RequestBody OperationsRequests.VisitChoice r,
            Principal p) {
        return visits.choose(id, r, p.getName());
    }

    @PostMapping("/v1/visits/{id}/change")
    public CaseVisitService.Workspace change(
            @PathVariable @NotBlank String id,
            @Valid @RequestBody OperationsRequests.VisitDecision r,
            Principal p) {
        return visits.decision(id, "change", r, p.getName());
    }

    @PostMapping("/v1/visits/{id}/complete")
    public CaseVisitService.Workspace complete(
            @PathVariable @NotBlank String id,
            @Valid @RequestBody OperationsRequests.VisitDecision r,
            Principal p) {
        return visits.decision(id, "complete", r, p.getName());
    }

    @PostMapping("/v1/visits/{id}/cancel")
    public CaseVisitService.Workspace cancel(
            @PathVariable @NotBlank String id,
            @Valid @RequestBody OperationsRequests.VisitDecision r,
            Principal p) {
        return visits.decision(id, "cancel", r, p.getName());
    }

    @GetMapping("/v1/houses/{house}/operations")
    public HouseOperationsService.Feed feed(@PathVariable @NotBlank String house, Principal p) {
        return houses.feed(house, p.getName());
    }

    @PutMapping("/v1/houses/{house}/operations/subscription")
    public HouseOperationsService.Feed subscribe(
            @PathVariable @NotBlank String house,
            @Valid @RequestBody OperationsRequests.Subscription r,
            Principal p) {
        return houses.subscribe(house, r, p.getName());
    }

    @PostMapping("/v1/houses/{house}/mass-problems")
    public HouseOperationsService.Feed massCreate(
            @PathVariable @NotBlank String house,
            @Valid @RequestBody OperationsRequests.Mass r,
            Principal p) {
        return houses.mass(house, null, r, p.getName());
    }

    @PutMapping("/v1/houses/{house}/mass-problems/{id}")
    public HouseOperationsService.Feed massUpdate(
            @PathVariable @NotBlank String house,
            @PathVariable @NotBlank String id,
            @Valid @RequestBody OperationsRequests.Mass r,
            Principal p) {
        return houses.mass(house, id, r, p.getName());
    }

    @GetMapping("/v1/cases/{id}/mass-problem")
    public Map<String, Object> linked(@PathVariable @NotBlank String id, Principal p) {
        return houses.linked(id, p.getName());
    }

    @PutMapping("/v1/cases/{id}/mass-problem")
    public ResidentCaseService.CaseView link(
            @PathVariable @NotBlank String id,
            @Valid @RequestBody OperationsRequests.Link r,
            Principal p) {
        return houses.link(id, r, p.getName());
    }

    @PostMapping("/v1/cases/{id}/mass-problem/unlink")
    public ResidentCaseService.CaseView unlink(
            @PathVariable @NotBlank String id,
            @Valid @RequestBody OperationsRequests.Unlink r,
            Principal p) {
        return houses.unlink(id, r, p.getName());
    }

    @PostMapping("/v1/house-mass-problems/{id}/join")
    public ResidentCaseService.CaseView join(
            @PathVariable @NotBlank String id,
            @Valid @RequestBody OperationsRequests.Join r,
            Principal p) {
        return houses.join(id, r, p.getName());
    }

    @PostMapping("/v1/house-mass-problems/{id}/work")
    public HouseOperationsService.Feed assignWork(
            @PathVariable @NotBlank String id,
            @Valid @RequestBody OperationsRequests.SharedWork r,
            Principal p) {
        return houses.assignWork(id, r, p.getName());
    }

    @PatchMapping("/v1/shared-work/{id}")
    public HouseOperationsService.Feed work(
            @PathVariable @NotBlank String id,
            @Valid @RequestBody OperationsRequests.WorkDecision r,
            Principal p) {
        return houses.work(id, r, p.getName());
    }

    @PostMapping("/v1/houses/{house}/operations-notices")
    public HouseOperationsService.Feed noticeCreate(
            @PathVariable @NotBlank String house,
            @Valid @RequestBody OperationsRequests.Notice r,
            Principal p) {
        return houses.notice(house, null, r, p.getName());
    }

    @PutMapping("/v1/houses/{house}/operations-notices/{id}")
    public HouseOperationsService.Feed noticeUpdate(
            @PathVariable @NotBlank String house,
            @PathVariable @NotBlank String id,
            @Valid @RequestBody OperationsRequests.Notice r,
            Principal p) {
        return houses.notice(house, id, r, p.getName());
    }

    @GetMapping("/v1/houses/{house}/reminder-policy")
    public Map<String, Object> policy(@PathVariable @NotBlank String house, Principal p) {
        return monitoring.policy(house, p.getName());
    }

    @PutMapping("/v1/houses/{house}/reminder-policy")
    public Map<String, Object> policySave(
            @PathVariable @NotBlank String house,
            @Valid @RequestBody OperationsRequests.Policy r,
            Principal p) {
        return monitoring.policy(house, r, p.getName());
    }

    @GetMapping("/v1/houses/{house}/operations-analytics")
    public Map<String, Object> analytics(@PathVariable @NotBlank String house, Principal p) {
        return monitoring.analytics(house, p.getName());
    }

    @PostMapping("/v1/houses/{house}/payment-comparison")
    public PaymentComparisonService.Comparison comparison(
            @PathVariable @NotBlank String house,
            @Valid @RequestBody OperationsRequests.Comparison r,
            Principal p) {
        return payments.compare(house, r, p.getName());
    }

    @GetMapping("/v1/operations-notifications")
    public List<Map<String, Object>> inbox(Principal p) {
        return notifications.inbox(p.getName());
    }

    @PostMapping("/v1/operations-notifications/{id}/read")
    public void read(@PathVariable @NotBlank String id, Principal p) {
        notifications.read(id, p.getName());
    }
}
