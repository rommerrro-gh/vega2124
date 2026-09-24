package ru.pulsedoma.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class PendingOperation {
    private PendingOperation() {}

    static ResponseStatusException notImplemented() {
        return new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Workflow is not implemented");
    }
}
