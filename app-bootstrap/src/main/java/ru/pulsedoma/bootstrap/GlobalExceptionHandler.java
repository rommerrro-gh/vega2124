package ru.pulsedoma.bootstrap;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import ru.pulsedoma.api.ErrorResponse;
import ru.pulsedoma.common.BusinessException;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ErrorResponse> business(BusinessException e) {
        return ResponseEntity.badRequest().body(error(e.getCode(), e.getMessage()));
    }

    @ExceptionHandler(JsonProcessingException.class)
    ResponseEntity<ErrorResponse> json(JsonProcessingException e) {
        return ResponseEntity.badRequest().body(error("INVALID_JSON", "Invalid JSON body"));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ErrorResponse> status(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(error(e.getStatusCode().value() == 501 ? "NOT_IMPLEMENTED" : "HTTP_ERROR", e.getReason()));
    }

    private ErrorResponse error(String code, String message) {
        return new ErrorResponse(code, message, Map.of(), MDC.get("correlationId"));
    }
}
