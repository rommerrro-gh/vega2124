package ru.pulsedoma.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.pulsedoma.house.GuidedDemoService;

@RestController
@RequestMapping("/v1/guided-demo")
public class GuidedDemoController {
    private final GuidedDemoService service;

    public GuidedDemoController(GuidedDemoService service) { this.service = service; }

    @GetMapping
    public GuidedDemoService.DemoView status(Principal principal) {
        return service.status(principal.getName());
    }

    @PostMapping("/activate")
    public GuidedDemoService.DemoView activate(@Valid @RequestBody CodeRequest request, Principal principal) {
        return service.activate(principal.getName(), request.code());
    }

    @PostMapping("/restart")
    public GuidedDemoService.DemoView restart(Principal principal) {
        return service.restart(principal.getName());
    }

    @PostMapping("/exit")
    public GuidedDemoService.DemoView exit(Principal principal) {
        return service.exit(principal.getName());
    }

    public record CodeRequest(@NotBlank @Size(max = 128) String code) {}
}
