package ru.pulsedoma.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotBlank;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import ru.pulsedoma.common.WebhookQueue;

@Validated
@RestController
public final class MaxWebhookController {
    private final ObjectMapper mapper;
    private final WebhookQueue queue;
    private final String secret;

    public MaxWebhookController(ObjectMapper mapper, WebhookQueue queue,
                                @Value("${max.webhook.secret}") String secret) {
        this.mapper = mapper;
        this.queue = queue;
        this.secret = secret;
    }

    // FR-ISS-002: acknowledge only after authentication, validation and durable enqueue.
    @PostMapping("/webhooks/max")
    public ResponseEntity<Void> receive(@RequestHeader(value = "X-Max-Bot-Api-Secret", required = false) String supplied,
                                        @NotBlank @RequestBody String body) throws Exception {
        if (supplied == null || !MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        JsonNode update = mapper.readTree(body);
        if (!update.isObject() || !update.hasNonNull("update_type")
                || !update.get("update_type").isTextual() || update.get("update_type").asText().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        queue.enqueue(body);
        return ResponseEntity.ok().build();
    }
}
