package ru.pulsedoma.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ru.pulsedoma.issues.AttachmentService;

import java.io.IOException;
import java.security.Principal;

@Validated
@RestController
public class AttachmentsController {
    private final AttachmentService attachments;

    public AttachmentsController(AttachmentService attachments) {
        this.attachments = attachments;
    }

    @PostMapping(value = "/v1/reports/{reportId}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AttachmentService.AttachmentView upload(@PathVariable @NotBlank String reportId,
                                                    @RequestPart("file") @NotNull MultipartFile file,
                                                    Principal principal) throws IOException {
        return attachments.upload(reportId, principal.getName(), file.getBytes(), file.getContentType());
    }

    @GetMapping("/v1/attachments/{id}")
    public ResponseEntity<byte[]> read(@PathVariable @NotBlank String id, Principal principal) {
        AttachmentService.AttachmentData file = attachments.read(id, principal.getName());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(file.mime())).body(file.bytes());
    }
}
