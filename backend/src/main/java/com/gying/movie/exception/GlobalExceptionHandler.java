package com.gying.movie.exception;

import com.gying.movie.dto.ApiResponse;
import com.gying.movie.security.ApiSecurityFilter.BodyTooLargeException;
import com.gying.movie.security.RedisRateLimiter.RateLimitExceededException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ApiResponse<Void>> rateLimited(RateLimitExceededException error) {
        return ResponseEntity.status(429).header("Retry-After", Long.toString(error.retryAfterSeconds()))
                .body(ApiResponse.error("TOO_MANY_REQUESTS", "Too many requests; try again later"));
    }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> status(ResponseStatusException error) {
        int code = error.getStatusCode().value();
        // Upstream exceptions can contain URLs, response bodies or credentials. Never echo 5xx details.
        String message = code >= 500 ? "Service temporarily unavailable" : error.getReason();
        HttpStatus status = HttpStatus.resolve(code);
        return ResponseEntity.status(code).body(ApiResponse.error(status == null ? "REQUEST_FAILED" : status.name(),
                message == null ? "Request rejected" : message));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class,
            MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class,
            MissingRequestHeaderException.class, IllegalArgumentException.class})
    public ResponseEntity<ApiResponse<Void>> invalid(Exception error) {
        return ResponseEntity.badRequest().body(ApiResponse.error("BAD_REQUEST", "Invalid request parameters"));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> malformed(HttpMessageNotReadableException error) {
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof BodyTooLargeException) return tooLarge();
            cause = cause.getCause();
        }
        return ResponseEntity.badRequest().body(ApiResponse.error("BAD_REQUEST", "Malformed request body"));
    }
    @ExceptionHandler({BodyTooLargeException.class, MaxUploadSizeExceededException.class})
    public ResponseEntity<ApiResponse<Void>> tooLarge() {
        return ResponseEntity.status(413).body(ApiResponse.error("PAYLOAD_TOO_LARGE", "Request body is too large"));
    }
    @ExceptionHandler({org.springframework.web.servlet.resource.NoResourceFoundException.class,
            org.springframework.web.servlet.NoHandlerFoundException.class})
    public ResponseEntity<ApiResponse<Void>> notFound(Exception error) {
        return ResponseEntity.status(404).body(ApiResponse.error("NOT_FOUND", "Not found"));
    }
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> methodNotAllowed(Exception error) {
        return ResponseEntity.status(405).body(ApiResponse.error("METHOD_NOT_ALLOWED", "Method not allowed"));
    }
    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> unsupportedMedia(Exception error) {
        return ResponseEntity.status(415).body(ApiResponse.error("UNSUPPORTED_MEDIA_TYPE", "Unsupported media type"));
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unexpected(Exception error) {
        // Exception messages/stack causes can contain SQL bindings or provider tokens.
        log.error("security_event=unhandled_exception type={}", error.getClass().getSimpleName());
        return ResponseEntity.status(500).body(ApiResponse.error("INTERNAL_SERVER_ERROR", "Internal server error"));
    }
}
