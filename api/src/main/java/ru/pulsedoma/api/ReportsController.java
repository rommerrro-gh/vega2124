package ru.pulsedoma.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/v1/reports")
public final class ReportsController {
    // FR-ISS-001, FR-ISS-013, AC-02, AC-16: service and authorization are pending.
    @PostMapping
    @ResponseStatus(HttpStatus.NOT_IMPLEMENTED)
    public void create(@RequestBody String body) {
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Report workflow is not implemented");
    }
}
