package ru.pulsedoma.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.List;
import org.springframework.web.bind.annotation.*;
import ru.pulsedoma.issues.*;

@RestController
@org.springframework.validation.annotation.Validated
public class SharedRequestsController {
    private final SharedRequestService shared;
    private final ResidentCaseService cases;
    private final ProblemIntakeService intake;

    public SharedRequestsController(
            SharedRequestService shared, ResidentCaseService cases, ProblemIntakeService intake) {
        this.shared = shared;
        this.cases = cases;
        this.intake = intake;
    }

    @GetMapping("/v1/houses/{house}/shared-problems")
    public List<SharedRequestService.PublicView> publicList(
            @PathVariable @NotBlank String house, Principal user) {
        return shared.publicList(house, user.getName());
    }

    @GetMapping("/v1/shared-problems/{id}")
    public SharedRequestService.PublicView publicView(
            @PathVariable @NotBlank String id, Principal user) {
        return shared.publicView(id, user.getName());
    }

    @GetMapping("/v1/shared-requests/{id}")
    public SharedRequestService.View get(@PathVariable String id, Principal user) {
        return shared.get(id, user.getName());
    }

    @GetMapping("/v1/houses/{house}/shared-requests")
    public List<SharedRequestService.View> queue(@PathVariable String house, Principal user) {
        return shared.queue(house, user.getName());
    }

    @PostMapping("/v1/shared-requests/{id}/join")
    public ResidentCaseService.CaseView join(
            @PathVariable String id, @Valid @RequestBody Join request, Principal user) {
        return intake.joinShared(
                id, request.problem().input(), request.clientRequestId(), user.getName());
    }

    @PostMapping("/v1/cases/{id}/withdraw")
    public ResidentCaseService.CaseView withdraw(
            @PathVariable String id, @Valid @RequestBody Withdraw request, Principal user) {
        return shared.withdraw(id, request.revision(), request.reason(), user.getName());
    }

    @PatchMapping("/v1/shared-requests/{id}")
    public SharedRequestService.View classify(
            @PathVariable String id, @Valid @RequestBody Classification request, Principal user) {
        return shared.classify(
                id,
                request.title(),
                request.category(),
                request.location(),
                request.revision(),
                request.reason(),
                user.getName());
    }

    @PostMapping("/v1/shared-requests/{id}/merge")
    public SharedRequestService.View merge(
            @PathVariable String id, @Valid @RequestBody Merge request, Principal user) {
        return shared.merge(
                id,
                request.targetId(),
                request.revision(),
                request.targetRevision(),
                request.reason(),
                user.getName());
    }

    @PostMapping("/v1/shared-requests/{id}/split")
    public SharedRequestService.View split(
            @PathVariable String id, @Valid @RequestBody Split request, Principal user) {
        return shared.split(
                id,
                request.caseId(),
                request.revision(),
                request.reason(),
                request.decisions(),
                user.getName());
    }

    public record Join(
            @NotNull @Valid ResidentCasesController.Search problem,
            @NotBlank @Size(max = 80) String clientRequestId) {}

    public record Withdraw(
            @NotNull @Min(0) Integer revision, @NotBlank @Size(max = 1000) String reason) {}

    public record Classification(
            @NotBlank @Size(max = 160) String title,
            @NotNull ReportCategory category,
            @Size(max = 160) String location,
            @NotNull @Min(0) Integer revision,
            @NotBlank @Size(max = 1000) String reason) {}

    public record Merge(
            @NotBlank String targetId,
            @NotNull @Min(0) Integer revision,
            @NotNull @Min(0) Integer targetRevision,
            @NotBlank @Size(max = 1000) String reason) {}

    public record Split(
            @NotBlank String caseId,
            @NotNull @Min(0) Integer revision,
            @NotBlank @Size(max = 1000) String reason,
            @NotNull @Size(max = 200) List<@Valid WorkAllocation> allocation) {
        public List<SharedWorkRegistry.Allocation> decisions() {
            return allocation.stream()
                    .map(
                            a ->
                                    new SharedWorkRegistry.Allocation(
                                            a.kind(), a.id(), a.revision(), a.move()))
                    .toList();
        }
    }

    public record WorkAllocation(
            @NotNull SharedWorkRegistry.Kind kind,
            @NotBlank String id,
            @NotNull @Min(0) Integer revision,
            @NotNull Boolean move) {}
}
