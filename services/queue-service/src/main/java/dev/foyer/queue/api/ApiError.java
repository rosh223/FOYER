package dev.foyer.queue.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/** Errors as {@code {"error": code, "message": text}} with a status and, for rate limits, Retry-After. */
public class ApiError extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final long retryAfter;

    public ApiError(HttpStatus status, String code, String message) {
        this(status, code, message, 0);
    }

    public ApiError(HttpStatus status, String code, String message, long retryAfter) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryAfter = retryAfter;
    }

    public static ApiError notFound(String what) {
        return new ApiError(HttpStatus.NOT_FOUND, "not_found", what + " not found");
    }

    @RestControllerAdvice
    static class Handler {
        @ExceptionHandler(ApiError.class)
        ResponseEntity<Map<String, Object>> handle(ApiError e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", e.code);
            body.put("message", e.getMessage());
            var b = ResponseEntity.status(e.status);
            if (e.retryAfter > 0) b.header("Retry-After", String.valueOf(e.retryAfter));
            return b.body(body);
        }
    }
}
