package com.openenv.tickettriage.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Global exception handler — returns structured JSON errors
 * so agents can parse failure reasons programmatically.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        List<String> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.toList());

        return ResponseEntity.badRequest().body(errorBody(
                "VALIDATION_ERROR",
                "Action validation failed: " + String.join("; ", errors),
                errors
        ));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleMalformedJson(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(errorBody(
                "MALFORMED_JSON",
                "Request body is not valid JSON. Check action field names and types.",
                List.of(ex.getMessage())
        ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArg(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(errorBody(
                "INVALID_ARGUMENT",
                ex.getMessage(),
                List.of()
        ));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException ex) {
        log.warn("Illegal state: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(errorBody(
                "ILLEGAL_STATE",
                ex.getMessage(),
                List.of("Call POST /api/v1/reset to start or restart an episode.")
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.internalServerError().body(errorBody(
                "INTERNAL_ERROR",
                "An unexpected error occurred. See server logs for details.",
                List.of(ex.getClass().getSimpleName() + ": " + ex.getMessage())
        ));
    }

    private Map<String, Object> errorBody(String code, String message, List<String> details) {
        return Map.of(
                "error", code,
                "message", message,
                "details", details,
                "timestamp", Instant.now().toString(),
                "hint", "Visit /swagger-ui for full API documentation."
        );
    }
}
