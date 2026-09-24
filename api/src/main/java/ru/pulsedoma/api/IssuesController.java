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
import ru.pulsedoma.issues.IssuePriority;
import ru.pulsedoma.issues.IssueStatus;

@Validated
@RestController
@RequestMapping("/v1/issues")
public final class IssuesController {
    @GetMapping("/candidates")
    public void candidates(@RequestParam @NotBlank String houseId,
                           @RequestParam @NotBlank String query) {
        throw PendingOperation.notImplemented();
    }

    @PostMapping
    public void create(@Valid @RequestBody CreateIssueRequest request) {
        throw PendingOperation.notImplemented();
    }

    @PostMapping("/{id}/join")
    public void join(@PathVariable @NotBlank String id, @Valid @RequestBody JoinIssueRequest request) {
        throw PendingOperation.notImplemented();
    }

    @PostMapping("/{id}/merge")
    public void merge(@PathVariable @NotBlank String id, @Valid @RequestBody MergeIssueRequest request) {
        throw PendingOperation.notImplemented();
    }

    @PostMapping("/{id}/split")
    public void split(@PathVariable @NotBlank String id, @Valid @RequestBody SplitIssueRequest request) {
        throw PendingOperation.notImplemented();
    }

    @PatchMapping("/{id}/status")
    public void changeStatus(@PathVariable @NotBlank String id, @Valid @RequestBody ChangeStatusRequest request) {
        throw PendingOperation.notImplemented();
    }

    @PostMapping("/{id}/verify")
    public void verify(@PathVariable @NotBlank String id, @Valid @RequestBody VerifyIssueRequest request) {
        throw PendingOperation.notImplemented();
    }

    @PostMapping("/{id}/comments")
    public void addComment(@PathVariable @NotBlank String id, @Valid @RequestBody AddCommentRequest request) {
        throw PendingOperation.notImplemented();
    }

    public record CreateIssueRequest(@NotBlank String houseId, @NotBlank String category,
                                     @NotBlank String description, @NotNull IssuePriority priority) {}
    public record JoinIssueRequest(@NotBlank String reportId) {}
    public record MergeIssueRequest(@NotBlank String targetIssueId) {}
    public record SplitIssueRequest(@NotBlank String reportId, @NotBlank String reason) {}
    public record ChangeStatusRequest(@NotNull IssueStatus status, @NotBlank String reason) {}
    public record VerifyIssueRequest(@NotNull Boolean confirmed, @Size(max = 2000) String comment) {}
    public record AddCommentRequest(@NotBlank @Size(max = 2000) String text) {}
}
