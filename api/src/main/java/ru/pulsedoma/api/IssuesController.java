package ru.pulsedoma.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.pulsedoma.issues.IssueStatus;
import ru.pulsedoma.issues.MiniAppService;
import java.security.Principal;

@Validated
@RestController
@RequestMapping("/v1/issues")
public class IssuesController {
    private final MiniAppService miniApp;

    public IssuesController(MiniAppService miniApp) {
        this.miniApp = miniApp;
    }
    @GetMapping("/candidates")
    public void candidates(@RequestParam @NotBlank String houseId,
                           @RequestParam @NotBlank String query) {
        throw PendingOperation.notImplemented();
    }

    @PostMapping
    public MiniAppService.IssueView create(@Valid @RequestBody CreateIssueRequest request, Principal principal) {
        return miniApp.createIssue(request.reportId(), principal.getName());
    }

    @GetMapping("/{id}")
    public MiniAppService.IssueView get(@PathVariable @NotBlank String id, Principal principal) {
        return miniApp.issue(id, principal.getName());
    }

    @PostMapping("/{id}/join")
    public MiniAppService.IssueView join(@PathVariable @NotBlank String id,
                                          @Valid @RequestBody JoinIssueRequest request, Principal principal) {
        return miniApp.joinIssue(id, request.reportId(), principal.getName());
    }

    @PostMapping("/{id}/merge")
    public MiniAppService.IssueView merge(@PathVariable @NotBlank String id,
                                          @Valid @RequestBody MergeIssueRequest request, Principal principal) {
        return miniApp.mergeIssues(id, request.targetIssueId(), principal.getName());
    }

    @PostMapping("/{id}/split")
    public MiniAppService.IssueView split(@PathVariable @NotBlank String id,
                                          @Valid @RequestBody SplitIssueRequest request, Principal principal) {
        return miniApp.splitIssue(id, request.reportId(), request.reason(), principal.getName());
    }

    @PatchMapping("/{id}/status")
    public MiniAppService.IssueView changeStatus(@PathVariable @NotBlank String id,
                                                  @Valid @RequestBody ChangeStatusRequest request,
                                                  Principal principal) {
        return miniApp.changeStatus(id, principal.getName(), request.status(), request.reason());
    }

    @PostMapping("/{id}/verify")
    public MiniAppService.IssueView verify(@PathVariable @NotBlank String id,
                                           @Valid @RequestBody VerifyIssueRequest request,
                                           Principal principal) {
        return miniApp.verify(id, principal.getName(), request.confirmed(), request.comment());
    }

    @PostMapping("/{id}/comments")
    public void addComment(@PathVariable @NotBlank String id, @Valid @RequestBody AddCommentRequest request) {
        throw PendingOperation.notImplemented();
    }

    public record CreateIssueRequest(@NotBlank String reportId) {}
    public record JoinIssueRequest(@NotBlank String reportId) {}
    public record MergeIssueRequest(@NotBlank String targetIssueId) {}
    public record SplitIssueRequest(@NotBlank String reportId, @NotBlank String reason) {}
    public record ChangeStatusRequest(@NotNull IssueStatus status, @NotBlank String reason) {}
    public record VerifyIssueRequest(@NotNull Boolean confirmed, @Size(max = 2000) String comment) {}
    public record AddCommentRequest(@NotBlank @Size(max = 2000) String text) {}
}
