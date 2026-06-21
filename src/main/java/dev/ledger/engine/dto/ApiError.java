package dev.ledger.engine.dto;

import java.time.Instant;
import java.util.List;

/**
 * Uniform error envelope for every 4xx/5xx response. {@code fields} carries
 * per-field validation messages; null for non-validation errors.
 */
public record ApiError(
        boolean success,
        Error error,
        Instant timestamp) {

    public record Error(String code, String message, List<FieldError> fields) {
    }

    public record FieldError(String field, String message) {
    }

    public static ApiError of(String code, String message) {
        return new ApiError(false, new Error(code, message, null), Instant.now());
    }

    public static ApiError of(String code, String message, List<FieldError> fields) {
        return new ApiError(false, new Error(code, message, fields), Instant.now());
    }
}
