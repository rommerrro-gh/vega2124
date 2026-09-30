package ru.pulsedoma.bootstrap;

import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
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

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class})
    ResponseEntity<ErrorResponse> validation(Exception e) {
        return ResponseEntity.badRequest().body(error("VALIDATION_ERROR", "Invalid request data"));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorResponse> malformed(Exception e) {
        return ResponseEntity.badRequest().body(error("INVALID_REQUEST", "Invalid request body or parameter"));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ErrorResponse> status(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(error(e.getStatusCode().value() == 501 ? "NOT_IMPLEMENTED" : "HTTP_ERROR", e.getReason()));
    }

    private ErrorResponse error(String code, String message) {
        return new ErrorResponse(code, message, Map.of(), MDC.get("correlationId"));
    }
}
