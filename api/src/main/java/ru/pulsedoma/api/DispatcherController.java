package ru.pulsedoma.api;

import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.pulsedoma.issues.MiniAppService;

import java.security.Principal;
import java.util.List;

@Validated
@RestController
@RequestMapping("/v1/dispatcher/issues")
public class DispatcherController {
    private final MiniAppService service;

    public DispatcherController(MiniAppService service) {
        this.service = service;
    }

    @GetMapping
    public List<MiniAppService.IssueView> queue(@RequestParam @NotBlank String houseId, Principal principal) {
        return service.dispatcherQueue(houseId, principal.getName());
    }

    @GetMapping("/{id}")
    public MiniAppService.IssueView issue(@PathVariable @NotBlank String id, Principal principal) {
        return service.dispatcherIssue(id, principal.getName());
    }
}
