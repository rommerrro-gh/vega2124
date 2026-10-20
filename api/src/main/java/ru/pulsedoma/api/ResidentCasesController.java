package ru.pulsedoma.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import ru.pulsedoma.issues.*;

import java.io.IOException;
import java.security.Principal;
import java.time.Instant;
import java.util.List;

@Validated
@RestController
public class ResidentCasesController {
    private final ResidentCaseService service;
    public ResidentCasesController(ResidentCaseService service) { this.service=service; }

    @GetMapping("/v1/cases")
    public List<ResidentCaseService.CaseView> mine(Principal user) { return service.mine(user.getName()); }

    @PostMapping("/v1/cases")
    @ResponseStatus(HttpStatus.CREATED)
    public ResidentCaseService.CaseView create(@Valid @RequestBody Create request,Principal user) {
        return service.create(request.houseId(),request.kind(),request.description(),request.location(),request.clientRequestId(),user.getName());
    }
    @GetMapping("/v1/cases/{id}")
    public ResidentCaseService.CaseView get(@PathVariable @NotBlank String id,Principal user) { return service.get(id,user.getName()); }

    @GetMapping("/v1/houses/{houseId}/case-context")
    public ResidentCaseService.Context context(@PathVariable @NotBlank String houseId,Principal user) { return service.context(houseId,user.getName()); }

    @GetMapping("/v1/houses/{houseId}/case-queue")
    public List<ResidentCaseService.CaseView> queue(@PathVariable @NotBlank String houseId,Principal user) { return service.queue(houseId,user.getName()); }

    @PutMapping("/v1/houses/{houseId}/case-billing-permissions/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void permission(@PathVariable @NotBlank String houseId,@PathVariable @NotBlank String userId,
                           @Valid @RequestBody Permission request,Principal user) {
        service.permission(houseId,userId,request.enabled(),user.getName());
    }
    @PatchMapping("/v1/cases/{id}")
    public ResidentCaseService.CaseView update(@PathVariable @NotBlank String id,@Valid @RequestBody Update request,Principal user) {
        return service.update(id,request.kind(),request.status(),request.nextStep(),request.dueAt(),request.responsibleUserId(),request.revision(),request.reason(),user.getName());
    }
    @PutMapping("/v1/cases/{id}/collaborators/{userId}")
    public ResidentCaseService.CaseView assign(@PathVariable @NotBlank String id,@PathVariable @NotBlank String userId,
                                             @Valid @RequestBody Assignment request,Principal user) {
        return service.assign(id,userId,request.function(),request.enabled(),request.revision(),user.getName());
    }
    @PostMapping("/v1/cases/{id}/messages")
    public ResidentCaseService.CaseView message(@PathVariable @NotBlank String id,@Valid @RequestBody Message request,Principal user) {
        return service.message(id,request.text(),request.clientRequestId(),user.getName());
    }
    @PostMapping("/v1/cases/{id}/response")
    public ResidentCaseService.CaseView respond(@PathVariable @NotBlank String id,@Valid @RequestBody Response request,Principal user) {
        return service.respond(id,request.confirmed(),request.comment(),request.revision(),user.getName());
    }
    @PostMapping(value="/v1/cases/{id}/documents",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResidentCaseService.CaseView upload(@PathVariable @NotBlank String id,@RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue="Документ") @NotBlank @Size(max=160) String title,
            @RequestParam(required=false) @Size(max=80) String documentId,
            @RequestParam(defaultValue="0") @Min(0) int expectedVersion,Principal user) throws IOException {
        return service.upload(id,documentId,title,file.getBytes(),file.getContentType(),expectedVersion,user.getName());
    }
    @GetMapping("/v1/case-document-versions/{id}")
    public ResponseEntity<byte[]> download(@PathVariable @NotBlank String id,Principal user) {
        var file=service.download(id,user.getName());
        String ext=switch(file.mime()) {case "application/pdf"->"pdf";case "image/png"->"png";default->"jpg";};
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(file.mime()))
            .header("X-Content-Type-Options","nosniff")
            .header("Content-Disposition",ContentDisposition.attachment().filename("document."+ext).build().toString())
            .body(file.bytes());
    }
    public record Create(@NotBlank String houseId,CaseKind kind,@NotBlank @Size(max=4000) String description,
                         @Size(max=160) String location,@NotBlank @Size(max=80) String clientRequestId) {}
    public record Update(@NotNull CaseKind kind,@NotNull CaseStatus status,@NotBlank @Size(max=600) String nextStep,
                         Instant dueAt,@Size(max=80) String responsibleUserId,@NotNull @Min(0) Integer revision,@Size(max=1000) String reason) {}
    public record Message(@NotBlank @Size(max=4000) String text,@NotBlank @Size(max=80) String clientRequestId) {}
    public record Response(@NotNull Boolean confirmed,@Size(max=2000) String comment,@NotNull @Min(0) Integer revision) {}
    public record Permission(@NotNull Boolean enabled) {}
    public record Assignment(@NotNull CaseFunction function,@NotNull Boolean enabled,@NotNull @Min(0) Integer revision) {}
}
