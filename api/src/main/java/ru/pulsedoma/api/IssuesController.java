package ru.pulsedoma.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController @RequestMapping("/v1/issues")
public final class IssuesController {
    // FR-ISS-003, AC-03: the FTS5 retriever exists; HTTP use needs house-scoped authorization.
    @GetMapping("/candidates")
    public void candidates() { throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED); }

    // FR-ISS-004, FR-ISS-005, FR-ISS-006, FR-ISS-008, FR-ISS-009, FR-ISS-010.
    @PostMapping
    public void create() { throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED); }
}
