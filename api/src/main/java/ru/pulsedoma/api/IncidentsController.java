package ru.pulsedoma.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/incidents")
public final class IncidentsController {
    @PostMapping
    public void create(@Valid @RequestBody CreateIncidentRequest request) {
        throw PendingOperation.notImplemented();
    }

    public record CreateIncidentRequest(@NotBlank String houseId, @NotBlank String type,
                                        @NotBlank @Size(max = 4000) String description) {}
}
