package ru.pulsedoma.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.pulsedoma.house.HouseAdminService;
import ru.pulsedoma.house.HouseContactType;

@Validated
@RestController
@RequestMapping("/v1/houses/{houseId}")
public class HouseAdminController {
    private final HouseAdminService service;

    public HouseAdminController(HouseAdminService service) {
        this.service = service;
    }

    @GetMapping("/contacts")
    public List<HouseAdminService.ContactView> contacts(@PathVariable @NotBlank String houseId,
                                                        Principal principal) {
        return service.contacts(houseId, principal.getName());
    }

    @PostMapping("/contacts")
    public ResponseEntity<HouseAdminService.ContactView> createContact(@PathVariable @NotBlank String houseId,
                                                                         @Valid @RequestBody ContactRequest request,
                                                                         Principal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createContact(houseId,
                principal.getName(), request.type(), request.title(), request.phone(), request.details()));
    }

    @PutMapping("/contacts/{contactId}")
    public HouseAdminService.ContactView updateContact(@PathVariable @NotBlank String houseId,
                                                       @PathVariable @NotBlank String contactId,
                                                       @Valid @RequestBody ContactRequest request,
                                                       Principal principal) {
        return service.updateContact(houseId, contactId, principal.getName(), request.type(),
                request.title(), request.phone(), request.details());
    }

    @DeleteMapping("/contacts/{contactId}")
    public ResponseEntity<Void> deleteContact(@PathVariable @NotBlank String houseId,
                                              @PathVariable @NotBlank String contactId,
                                              Principal principal) {
        service.deleteContact(houseId, contactId, principal.getName());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/invitations")
    public List<HouseAdminService.InvitationView> invitations(@PathVariable @NotBlank String houseId,
                                                               Principal principal) {
        return service.invitations(houseId, principal.getName());
    }

    @PostMapping("/invitations")
    public ResponseEntity<HouseAdminService.CreatedInvitation> createInvitation(
            @PathVariable @NotBlank String houseId, @Valid @RequestBody CreateInvitationRequest request,
            Principal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createInvitation(houseId,
                principal.getName(), request.days(), request.activationLimit()));
    }

    @PostMapping("/invitations/{invitationId}/revoke")
    public HouseAdminService.InvitationView revokeInvitation(@PathVariable @NotBlank String houseId,
                                                              @PathVariable @NotBlank String invitationId,
                                                              Principal principal) {
        return service.revokeInvitation(houseId, invitationId, principal.getName());
    }

    public record ContactRequest(@NotNull HouseContactType type, @NotBlank @Size(max = 120) String title,
                                 @NotBlank @Pattern(regexp = "[+0-9() .-]{2,40}") String phone,
                                 @Size(max = 500) String details) {}
    public record CreateInvitationRequest(@Min(1) @Max(30) int days,
                                          @Min(1) @Max(100) int activationLimit) {}
}
