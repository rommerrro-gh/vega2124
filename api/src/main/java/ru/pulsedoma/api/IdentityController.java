package ru.pulsedoma.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
public final class IdentityController {
    @PostMapping("/v1/invitations/{token}/accept")
    public void acceptInvitation(@PathVariable @NotBlank String token,
                                 @Valid @RequestBody AcceptInvitationRequest request) {
        throw PendingOperation.notImplemented();
    }

    @GetMapping("/v1/me/houses")
    public void getMyHouses() {
        throw PendingOperation.notImplemented();
    }

    @PutMapping("/v1/me/active-house")
    public void setActiveHouse(@Valid @RequestBody SetActiveHouseRequest request) {
        throw PendingOperation.notImplemented();
    }

    public record AcceptInvitationRequest(@NotBlank String maxUserId) {}
    public record SetActiveHouseRequest(@NotBlank String houseId) {}
}
