package ru.pulsedoma.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.security.Principal;
import java.time.*;
import java.util.List;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import ru.pulsedoma.issues.*;

@RestController
@Validated
public class HouseTariffController {
    private final HouseTariffService service;

    public HouseTariffController(HouseTariffService service) {
        this.service = service;
    }

    @GetMapping("/v1/houses/{house}/tariffs")
    public HouseTariffService.Catalogue catalogue(
            @PathVariable @NotBlank String house,
            @RequestParam(required = false) LocalDate date,
            Principal p) {
        return service.catalogue(
                house,
                date == null ? LocalDate.now(ZoneId.of("Europe/Moscow")) : date,
                p.getName());
    }

    @GetMapping("/v1/houses/{house}/tariffs/access")
    public HouseTariffService.Rights rights(@PathVariable @NotBlank String house, Principal p) {
        return service.rights(house, p.getName());
    }

    @GetMapping("/v1/houses/{house}/tariffs/staff")
    public List<HouseTariffService.Staff> staff(@PathVariable @NotBlank String house, Principal p) {
        return service.staff(house, p.getName());
    }

    @PutMapping("/v1/houses/{house}/tariffs/staff/{user}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void grant(
            @PathVariable @NotBlank String house,
            @PathVariable @NotBlank String user,
            @Valid @RequestBody TariffRequests.Grant r,
            Principal p) {
        service.grant(house, user, r, p.getName());
    }

    @GetMapping("/v1/houses/{house}/tariffs/registry")
    public List<HouseTariffService.Rate> registry(
            @PathVariable @NotBlank String house, Principal p) {
        return service.listRegistry(house, p.getName());
    }

    @PostMapping("/v1/houses/{house}/tariffs/registry")
    @ResponseStatus(HttpStatus.CREATED)
    public HouseTariffService.Rate create(
            @PathVariable @NotBlank String house,
            @Valid @RequestBody TariffRequests.Draft r,
            Principal p) {
        return service.save(house, null, r, p.getName());
    }

    @PutMapping("/v1/houses/{house}/tariffs/registry/{id}")
    public HouseTariffService.Rate save(
            @PathVariable @NotBlank String house,
            @PathVariable @NotBlank String id,
            @Valid @RequestBody TariffRequests.Draft r,
            Principal p) {
        return service.save(house, id, r, p.getName());
    }

    @PostMapping("/v1/tariffs/{id}/copy")
    @ResponseStatus(HttpStatus.CREATED)
    public HouseTariffService.Rate copy(
            @PathVariable @NotBlank String id,
            @Valid @RequestBody TariffRequests.Copy r,
            Principal p) {
        return service.copy(id, r, p.getName());
    }

    @PostMapping("/v1/tariffs/{id}/publish")
    public HouseTariffService.Rate publish(
            @PathVariable @NotBlank String id,
            @Valid @RequestBody TariffRequests.Decision r,
            Principal p) {
        return service.publish(id, r, p.getName());
    }

    @PostMapping("/v1/tariffs/{id}/withdraw")
    public HouseTariffService.Rate withdraw(
            @PathVariable @NotBlank String id,
            @Valid @RequestBody TariffRequests.Decision r,
            Principal p) {
        return service.withdraw(id, r, p.getName());
    }

    @PostMapping(
            value = "/v1/tariffs/{id}/documents",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public HouseTariffService.Rate upload(
            @PathVariable @NotBlank String id,
            @RequestPart("file") MultipartFile file,
            @RequestParam @NotBlank @Size(max = 160) String title,
            @RequestParam @Min(0) int revision,
            Principal p)
            throws IOException {
        return service.upload(
                id, title, file.getBytes(), file.getContentType(), revision, p.getName());
    }

    @DeleteMapping("/v1/tariffs/{id}/documents/{file}")
    public HouseTariffService.Rate remove(
            @PathVariable @NotBlank String id,
            @PathVariable @NotBlank String file,
            @RequestParam @Min(0) int revision,
            Principal p) {
        return service.removeFile(id, file, revision, p.getName());
    }

    @GetMapping("/v1/tariff-documents/{id}")
    public ResponseEntity<byte[]> download(@PathVariable @NotBlank String id, Principal p) {
        var file = service.file(id, p.getName());
        String ext =
                file.mime().equals("application/pdf")
                        ? "pdf"
                        : file.mime().equals("image/png") ? "png" : "jpg";
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(file.mime()))
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Disposition", "attachment; filename=\"tariff-source." + ext + "\"")
                .body(file.bytes());
    }
}
