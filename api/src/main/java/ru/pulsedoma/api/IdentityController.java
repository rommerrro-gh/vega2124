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
import ru.pulsedoma.issues.ResidentIdentityService;
import java.security.Principal;
import java.util.List;

@Validated
@RestController
public class IdentityController {
    private final ResidentIdentityService miniApp;

    public IdentityController(ResidentIdentityService miniApp) {
        this.miniApp = miniApp;
    }
    @PostMapping("/v1/invitations/{token}/accept")
    public ResidentIdentityService.HouseView acceptInvitation(@PathVariable @NotBlank String token,
                                                      Principal principal) {
        return miniApp.acceptInvitation(token, principal.getName());
    }

    @GetMapping("/v1/invitations/{token}")
    public ResidentIdentityService.LegacyInvitationView legacyInvitation(@PathVariable @NotBlank String token) {
        return miniApp.legacyInvitation(token);
    }

    @GetMapping("/v1/me/houses")
    public List<ResidentIdentityService.HouseView> getMyHouses(Principal principal) {
        return miniApp.houses(principal.getName());
    }


    @GetMapping("/v1/me/dispatcher-houses")
    public List<ResidentIdentityService.HouseView> getDispatcherHouses(Principal principal) {
        return miniApp.dispatcherHouses(principal.getName());
    }

    @PutMapping("/v1/me/active-house")
    public void setActiveHouse(@Valid @RequestBody SetActiveHouseRequest request, Principal principal) {
        miniApp.setActiveHouse(request.houseId(), principal.getName());
    }

    @GetMapping("/v1/me/active-house")
    public ActiveHouseView activeHouse(Principal principal) {
        return new ActiveHouseView(miniApp.activeHouse(principal.getName()));
    }

    public record SetActiveHouseRequest(@NotBlank String houseId) {}
    public record ActiveHouseView(String houseId) {}
}
