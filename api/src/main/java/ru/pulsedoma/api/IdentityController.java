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
import ru.pulsedoma.issues.MiniAppService;
import java.security.Principal;
import java.util.List;

@Validated
@RestController
public class IdentityController {
    private final MiniAppService miniApp;

    public IdentityController(MiniAppService miniApp) {
        this.miniApp = miniApp;
    }
    @PostMapping("/v1/invitations/{token}/accept")
    public MiniAppService.HouseView acceptInvitation(@PathVariable @NotBlank String token,
                                                      Principal principal) {
        return miniApp.acceptInvitation(token, principal.getName());
    }

    @GetMapping("/v1/me/houses")
    public List<MiniAppService.HouseView> getMyHouses(Principal principal) {
        return miniApp.houses(principal.getName());
    }

    @PutMapping("/v1/me/active-house")
    public void setActiveHouse(@Valid @RequestBody SetActiveHouseRequest request) {
        throw PendingOperation.notImplemented();
    }

    public record SetActiveHouseRequest(@NotBlank String houseId) {}
}
