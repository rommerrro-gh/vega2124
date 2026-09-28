package ru.pulsedoma.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.security.Principal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.pulsedoma.house.HousePassportService;

@Validated
@RestController
@RequestMapping("/v1/houses")
public class HousesController {
    private final HousePassportService passports;

    public HousesController(HousePassportService passports) {
        this.passports = passports;
    }

    @GetMapping("/{id}")
    public HousePassportService.HousePassport getHouse(@PathVariable @NotBlank String id, Principal principal) {
        return passports.getPassport(id, principal.getName());
    }

    @GetMapping("/{id}/events")
    public void getEvents(@PathVariable @NotBlank String id) {
        throw PendingOperation.notImplemented();
    }

    @PostMapping("/{id}/polls")
    public void createPoll(@PathVariable @NotBlank String id, @Valid @RequestBody CreatePollRequest request) {
        throw PendingOperation.notImplemented();
    }

    public record CreatePollRequest(@NotBlank @Size(max = 500) String question,
                                    @NotEmpty @Size(min = 2, max = 20) List<@NotBlank String> options,
                                    @Future Instant closesAt) {}
}
