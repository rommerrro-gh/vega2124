package ru.pulsedoma.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.pulsedoma.house.AccessActivationService;
import ru.pulsedoma.identity.AccessRole;

import java.security.Principal;
import java.util.List;

@Validated
@RestController
@RequestMapping("/v1/access")
public class AccessActivationController {
    private final AccessActivationService service;
    private final String botUsername;
    private final boolean demoMode;

    public AccessActivationController(AccessActivationService service,
                                      @Value("${max.bot.username:}") String botUsername,
                                      Environment environment) {
        this.service = service;
        this.botUsername = botUsername;
        this.demoMode = environment.acceptsProfiles(Profiles.of("demo"));
    }

    @GetMapping("/config")
    public ConfigView config() {
        return new ConfigView(botUsername, demoMode);
    }

    @GetMapping("/me")
    public List<AccessActivationService.AccessView> myAccess(Principal principal) {
        return service.myAccess(principal.getName());
    }

    @GetMapping("/organizations")
    public List<AccessActivationService.OrganizationView> organizations(Principal principal) {
        return service.organizations(principal.getName());
    }

    @GetMapping("/my-organizations")
    public List<AccessActivationService.OrganizationView> myOrganizations(Principal principal) {
        return service.myOrganizations(principal.getName());
    }

    @PostMapping("/organizations")
    public ResponseEntity<AccessActivationService.OrganizationView> createOrganization(
            @Valid @RequestBody OrganizationRequest request, Principal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.createOrganization(request.name(), principal.getName()));
    }

    @GetMapping("/houses")
    public List<AccessActivationService.HouseView> houses(Principal principal) {
        return service.allHouses(principal.getName());
    }

    @PostMapping("/houses")
    public ResponseEntity<AccessActivationService.HouseView> createHouse(
            @Valid @RequestBody HouseRequest request, Principal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createHouse(request.address(), principal.getName()));
    }

    @PutMapping("/organizations/{organizationId}/houses/{houseId}")
    public AccessActivationService.OrganizationView setOrganizationHouse(
            @PathVariable @NotBlank String organizationId, @PathVariable @NotBlank String houseId,
            @Valid @RequestBody HouseLinkRequest request, Principal principal) {
        return service.setOrganizationHouse(organizationId, houseId, request.active(), principal.getName());
    }

    @PostMapping("/invitations")
    public ResponseEntity<AccessActivationService.CreatedInvitation> createInvitation(
            @Valid @RequestBody InvitationRequest request, Principal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createInvitation(request.role(),
                request.organizationId(), request.houseIds(), request.days(), request.activationLimit(), principal.getName()));
    }

    @GetMapping("/invitations/{token}")
    public AccessActivationService.InvitationView preview(@PathVariable @NotBlank String token, Principal principal) {
        return service.preview(token, principal.getName());
    }

    @PostMapping("/invitations/{token}/accept")
    public AccessActivationService.InvitationView accept(@PathVariable @NotBlank String token, Principal principal) {
        return service.accept(token, principal.getName());
    }

    @GetMapping("/invitations")
    public List<AccessActivationService.InvitationView> invitations(Principal principal) {
        return service.createdInvitations(principal.getName());
    }

    @PostMapping("/invitations/{id}/revoke")
    public AccessActivationService.InvitationView revokeInvitation(@PathVariable @NotBlank String id, Principal principal) {
        return service.revokeInvitation(id, principal.getName());
    }

    @GetMapping("/organizations/{organizationId}/assignments")
    public List<AccessActivationService.AccessView> assignments(@PathVariable @NotBlank String organizationId,
                                                                 Principal principal) {
        return service.managedAccess(organizationId, principal.getName());
    }

    @GetMapping("/organizations/{organizationId}/admins")
    public List<AccessActivationService.AccessView> organizationAdmins(@PathVariable @NotBlank String organizationId,
                                                                        Principal principal) {
        return service.organizationAdmins(organizationId, principal.getName());
    }

    @GetMapping("/houses/{houseId}/residents")
    public List<AccessActivationService.AccessView> houseResidents(@PathVariable @NotBlank String houseId,
                                                                    Principal principal) {
        return service.houseResidents(houseId, principal.getName());
    }

    @PostMapping("/assignments/revoke")
    public ResponseEntity<Void> revokeAccess(@Valid @RequestBody RevokeRequest request, Principal principal) {
        service.revokeAccess(request.role(), request.userId(), request.organizationId(), request.houseId(), principal.getName());
        return ResponseEntity.noContent().build();
    }

    public record OrganizationRequest(@NotBlank @Size(max = 200) String name) {}
    public record HouseRequest(@NotBlank @Size(max = 300) String address) {}
    public record HouseLinkRequest(boolean active) {}
    public record InvitationRequest(@NotNull AccessRole role, String organizationId,
                                    @NotEmpty List<@NotBlank String> houseIds,
                                    @Min(1) @Max(30) int days,
                                    @Min(1) @Max(100) int activationLimit) {}
    public record RevokeRequest(@NotNull AccessRole role, @NotBlank String userId,
                                String organizationId, String houseId) {}
    public record ConfigView(String botUsername, boolean demoMode) {}
}
