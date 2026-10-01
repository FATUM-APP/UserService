package fatum.controller;

import software.amazon.awssdk.core.exception.SdkException;
import fatum.dto.ApiError;
import fatum.exception.FatumUserException;
import fatum.storage.StorageException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(FatumUserException.class)
    public ResponseEntity<ApiError> handleFatumUserException(
            FatumUserException exception,
            HttpServletRequest request) {
        HttpStatus status = switch (exception.getMessage()) {
            case FatumUserException.USER_NOT_FOUND,
                 FatumUserException.FILE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case FatumUserException.INACTIVE,
                 FatumUserException.FORBIDDEN -> HttpStatus.FORBIDDEN;
            case FatumUserException.USER_ALREADY_EXISTS,
                 FatumUserException.EMAIL_EXISTS,
                 FatumUserException.USERNAME_EXISTS,
                 FatumUserException.PHONE_EXISTS,
                 FatumUserException.DOCUMENT_EXISTS,
                 FatumUserException.VERIFICATION_ALREADY_COMPLETED,
                 FatumUserException.NO_ATTEMPTS_LEFT,
                 FatumUserException.LIVENESS_NOT_REQUIRED,
                 FatumUserException.LIVENESS_SESSION_NOT_FOUND,
                 FatumUserException.PROFILE_REFERENCE_MISSING -> HttpStatus.CONFLICT;
            case FatumUserException.PROFILE_PHOTO_TOO_MANY_CHANGES,
                 FatumUserException.LIVENESS_TOO_MANY_SESSIONS -> HttpStatus.TOO_MANY_REQUESTS;
            case FatumUserException.VERIFICATION_DISABLED,
                 FatumUserException.LIVENESS_DISABLED,
                 FatumUserException.LIVENESS_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_REQUEST;
        };
        return buildError(status, exception.getMessage(), request.getRequestURI(), Map.of());
    }

    /**
     * The shared storage service rejected the request (4xx, the message is useful to the caller) or
     * failed (anything else, which is a 502 for this service).
     */
    @ExceptionHandler(StorageException.class)
    public ResponseEntity<ApiError> handleStorageException(
            StorageException exception,
            HttpServletRequest request) {
        HttpStatus status = exception.isClientError() ? HttpStatus.BAD_REQUEST : HttpStatus.BAD_GATEWAY;
        if (status.is5xxServerError()) {
            log.error("The storage service failed handling {}", request.getRequestURI(), exception);
        }
        return buildError(status, exception.getMessage(), request.getRequestURI(), Map.of());
    }

    /** Structural guard of the domain objects; it means the caller sent an unusable value. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(
            IllegalArgumentException exception,
            HttpServletRequest request) {
        return buildError(
                HttpStatus.BAD_REQUEST,
                exception.getMessage() == null ? "Invalid request" : exception.getMessage(),
                request.getRequestURI(),
                Map.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return buildError(
                HttpStatus.BAD_REQUEST,
                "Request validation failed",
                request.getRequestURI(),
                fieldErrors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(
            ConstraintViolationException exception,
            HttpServletRequest request) {
        Map<String, String> violations = new LinkedHashMap<>();
        exception.getConstraintViolations().forEach(violation ->
                violations.put(violation.getPropertyPath().toString(), violation.getMessage()));
        return buildError(
                HttpStatus.BAD_REQUEST,
                "Request validation failed",
                request.getRequestURI(),
                violations);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(
            DataIntegrityViolationException exception,
            HttpServletRequest request) {
        return buildError(
                HttpStatus.CONFLICT,
                "The operation conflicts with an existing user value",
                request.getRequestURI(),
                Map.of());
    }

    @ExceptionHandler({IOException.class, SdkException.class})
    public ResponseEntity<ApiError> handleStorageError(
            Exception exception,
            HttpServletRequest request) {
        return buildError(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Error processing the stored file",
                request.getRequestURI(),
                Map.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpectedError(
            Exception exception,
            HttpServletRequest request) {
        return buildError(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Unexpected server error",
                request.getRequestURI(),
                Map.of());
    }

    private ResponseEntity<ApiError> buildError(
            HttpStatus status,
            String message,
            String path,
            Map<String, String> validationErrors) {
        ApiError body = new ApiError(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                path,
                validationErrors);
        return ResponseEntity.status(status).body(body);
    }
}
