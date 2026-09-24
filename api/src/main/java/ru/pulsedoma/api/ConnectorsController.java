package ru.pulsedoma.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/connectors")
public final class ConnectorsController {
    @PostMapping("/{type}/sync")
    public void sync(@PathVariable ConnectorType type, @Valid @RequestBody SyncRequest request) {
        throw PendingOperation.notImplemented();
    }

    public enum ConnectorType { FIAS, GIS, UK }
    public record SyncRequest(@NotBlank String houseId) {}
}
