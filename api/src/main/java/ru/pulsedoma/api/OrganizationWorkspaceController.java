package ru.pulsedoma.api;

import static ru.pulsedoma.issues.OrganizationTypes.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import ru.pulsedoma.issues.*;

@RestController
@Validated
@RequestMapping("/v1/organization-workspaces")
public class OrganizationWorkspaceController {
    private final OrganizationWorkspaceService service;

    public OrganizationWorkspaceController(OrganizationWorkspaceService service) {
        this.service = service;
    }

    public record OrganizationRequest(@NotBlank @Size(max = 160) String name, @NotNull Kind kind) {}

    public record LinkRequest(boolean active) {}

    public record InviteRequest(
            @NotNull TeamRole role,
            @NotNull @Size(max = 160) String department,
            @NotNull @Size(max = 3) List<@NotNull Function> functions) {}

    public record AcceptRequest(@NotBlank @Size(max = 200) String token) {}

    public record MemberRequest(@NotNull TeamRole role, boolean active) {}

    public record OfferRequest(
            @NotBlank String caseId,
            @NotBlank String target,
            @NotBlank @Size(max = 3000) String reason,
            @Min(0) int revision) {}

    public record ReplyRequest(
            @NotNull TransferState state,
            @NotBlank @Size(max = 3000) String note,
            @Min(0) int revision) {}

    public record AssignmentRequest(
            @NotBlank String userId,
            @NotNull CaseFunction function,
            @Min(0) int revision,
            boolean enabled) {}

    @GetMapping
    public Object mine(Principal p) {
        return service.mine(p.getName());
    }

    @GetMapping("/organizations")
    public Object organizations(Principal p) {
        return service.organizations(p.getName());
    }

    @PostMapping("/organizations")
    public Object create(@Valid @RequestBody OrganizationRequest r, Principal p) {
        return service.create(r.name(), r.kind(), p.getName());
    }

    @GetMapping("/{org}/houses")
    public Object houses(@PathVariable @NotBlank String org, Principal p) {
        return service.houses(org, p.getName());
    }

    @PutMapping("/{org}/houses/{house}")
    public void link(
            @PathVariable @NotBlank String org,
            @PathVariable @NotBlank String house,
            @Valid @RequestBody LinkRequest r,
            Principal p) {
        service.link(org, house, r.active(), p.getName());
    }

    @GetMapping("/{org}/members")
    public Object members(@PathVariable @NotBlank String org, Principal p) {
        return service.members(org, p.getName());
    }

    @PutMapping("/{org}/members/{user}")
    public void member(
            @PathVariable @NotBlank String org,
            @PathVariable @NotBlank String user,
            @Valid @RequestBody MemberRequest r,
            Principal p) {
        service.memberRole(org, user, r.role(), r.active(), p.getName());
    }

    public record FunctionsRequest(@NotEmpty @Size(max = 3) List<@NotNull Function> functions) {}

    @PutMapping("/{org}/members/{user}/functions")
    public void functions(
            @PathVariable @NotBlank String org,
            @PathVariable @NotBlank String user,
            @Valid @RequestBody FunctionsRequest r,
            Principal p) {
        service.functions(org, user, r.functions(), p.getName());
    }

    @PutMapping("/{org}/coordinator")
    public void coordinator(
            @PathVariable @NotBlank String org, @Valid @RequestBody LinkRequest r, Principal p) {
        service.selfCoordinator(org, r.active(), p.getName());
    }

    @GetMapping("/{org}/invitations")
    public Object invitations(@PathVariable @NotBlank String org, Principal p) {
        return service.invitations(org, p.getName());
    }

    @PostMapping("/{org}/invitations")
    public Object invite(
            @PathVariable @NotBlank String org, @Valid @RequestBody InviteRequest r, Principal p) {
        return service.invite(org, r.role(), r.department(), r.functions(), p.getName());
    }

    @DeleteMapping("/{org}/invitations/{key}")
    public void revoke(
            @PathVariable @NotBlank String org, @PathVariable @NotBlank String key, Principal p) {
        service.revokeInvitation(org, key, p.getName());
    }

    @GetMapping("/invitations/preview")
    public Object preview(@RequestParam @NotBlank @Size(max = 200) String token) {
        return service.invitation(token);
    }

    @PostMapping("/accept")
    public void accept(@Valid @RequestBody AcceptRequest r, Principal p) {
        service.accept(r.token(), p.getName());
    }

    @GetMapping("/{org}/cases")
    public Object cases(
            @PathVariable @NotBlank String org, @RequestParam TeamRole mode, Principal p) {
        return service.assignedCases(org, mode, p.getName());
    }

    @GetMapping("/{org}/tasks")
    public Object tasks(
            @PathVariable @NotBlank String org, @RequestParam TeamRole mode, Principal p) {
        return service.tasks(org, mode, p.getName());
    }

    public record ReassignRequest(@NotBlank String fromUserId, @NotBlank String toUserId) {}

    @PostMapping("/{org}/coordinators/reassign")
    public Object reassign(
            @PathVariable @NotBlank String org,
            @Valid @RequestBody ReassignRequest r,
            Principal p) {
        return java.util.Map.of(
                "reassigned",
                service.reassignCoordinator(org, r.fromUserId(), r.toUserId(), p.getName()));
    }

    @GetMapping("/{org}/partners")
    public Object partners(@PathVariable @NotBlank String org, Principal p) {
        return service.partners(org, p.getName());
    }

    @GetMapping("/{org}/transfers")
    public Object transfers(@PathVariable @NotBlank String org, Principal p) {
        return service.transfers(org, p.getName());
    }

    @PostMapping("/{org}/transfers")
    public Object offer(
            @PathVariable @NotBlank String org, @Valid @RequestBody OfferRequest r, Principal p) {
        return service.offer(org, r.caseId(), r.target(), r.reason(), r.revision(), p.getName());
    }

    @PostMapping("/transfers/{key}/reply")
    public Object reply(
            @PathVariable @NotBlank String key, @Valid @RequestBody ReplyRequest r, Principal p) {
        return service.reply(key, r.state(), r.note(), r.revision(), p.getName());
    }

    @PutMapping("/{org}/cases/{caseId}/assignments")
    public void assign(
            @PathVariable @NotBlank String org,
            @PathVariable @NotBlank String caseId,
            @Valid @RequestBody AssignmentRequest r,
            Principal p) {
        service.assign(
                org, caseId, r.userId(), r.function(), r.revision(), r.enabled(), p.getName());
    }
}
