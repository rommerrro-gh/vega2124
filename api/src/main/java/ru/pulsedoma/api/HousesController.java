package ru.pulsedoma.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import ru.pulsedoma.house.HousePassportService;
import ru.pulsedoma.house.HousePollService;

@Validated
@RestController
@RequestMapping("/v1/houses")
public class HousesController {
    private final HousePassportService passports;
    private final HousePollService polls;

    public HousesController(HousePassportService passports, HousePollService polls) {
        this.passports = passports;
        this.polls = polls;
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
    public ResponseEntity<HousePollService.PollView> createPoll(@PathVariable @NotBlank String id,
            @Valid @RequestBody CreatePollRequest request, Principal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(polls.create(id, principal.getName(),
                request.question(), request.options(), request.closesAt(), request.resultsHiddenUntilClose()));
    }

    @GetMapping("/{id}/polls")
    public List<HousePollService.PollView> listPolls(@PathVariable @NotBlank String id, Principal principal) {
        return polls.list(id, principal.getName());
    }

    @PostMapping("/{id}/polls/{pollId}/votes")
    public HousePollService.PollView vote(@PathVariable @NotBlank String id,
            @PathVariable @NotBlank String pollId, @Valid @RequestBody VoteRequest request, Principal principal) {
        return polls.vote(id, pollId, request.optionId(), principal.getName());
    }

    public record CreatePollRequest(@NotBlank @Size(max = 500) String question,
                                    @NotEmpty @Size(min = 2, max = 20) List<@NotBlank @Size(max = 200) String> options,
                                    @NotNull @Future Instant closesAt,
                                    boolean resultsHiddenUntilClose) {}
    public record VoteRequest(@NotBlank String optionId) {}
}
