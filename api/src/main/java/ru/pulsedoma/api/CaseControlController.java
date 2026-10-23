package ru.pulsedoma.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import ru.pulsedoma.issues.*;

@RestController
@Validated
public class CaseControlController {
    private final CaseControlService service;

    public CaseControlController(CaseControlService service) {
        this.service = service;
    }

    @GetMapping("/v1/case-control")
    public java.util.List<CaseControlService.HouseSummary> overview(Principal p) {
        return service.overview(p.getName());
    }

    @GetMapping("/v1/houses/{house}/case-control")
    public CaseControlService.Dashboard dashboard(
            @PathVariable @NotBlank String house, Principal p) {
        return service.dashboard(house, p.getName());
    }

    @PutMapping("/v1/cases/{id}/followup")
    public CaseControlService.Dashboard assign(
            @PathVariable @NotBlank String id,
            @Valid @RequestBody ExchangeRequests.Assignment r,
            Principal p) {
        return service.assign(id, r, p.getName());
    }

    @PostMapping("/v1/cases/{id}/takeover")
    public CaseControlService.Dashboard takeover(
            @PathVariable @NotBlank String id,
            @Valid @RequestBody ExchangeRequests.ControlDecision r,
            Principal p) {
        return service.takeover(id, r, p.getName());
    }

    @PostMapping("/v1/cases/{id}/escalations")
    public CaseControlService.Dashboard escalate(
            @PathVariable @NotBlank String id,
            @Valid @RequestBody ExchangeRequests.Escalate r,
            Principal p) {
        return service.escalate(id, r, p.getName());
    }

    @PatchMapping("/v1/case-escalations/{id}")
    public CaseControlService.Dashboard escalation(
            @PathVariable @NotBlank String id,
            @Valid @RequestBody ExchangeRequests.Escalation r,
            Principal p) {
        return service.escalation(id, r, p.getName());
    }

    @GetMapping("/v1/houses/{house}/pilot")
    public CaseControlService.Readiness pilot(@PathVariable @NotBlank String house, Principal p) {
        return service.pilot(house, p.getName());
    }

    @PutMapping("/v1/houses/{house}/pilot")
    public CaseControlService.Readiness savePilot(
            @PathVariable @NotBlank String house,
            @Valid @RequestBody ExchangeRequests.Pilot r,
            Principal p) {
        return service.pilot(house, r, p.getName());
    }
}
