package fatum.controller;

import fatum.dto.ApiError;
import fatum.exception.FatumUserException;
import fatum.storage.StorageException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
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

    @ExceptionHandler(FatumUserException.class)
    public ResponseEntity<ApiError> handleFatumUserException(
            FatumUserException exception,
            HttpServletRequest request) {
        HttpStatus status = switch (exception.getMessage()) {
            case FatumUserException.USER_NOT_FOUND,
                 FatumUserException.FILE_NOT_FOUND,
                 FatumUserException.ADDRESS_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case FatumUserException.INACTIVE,
                 FatumUserException.FORBIDDEN-> HttpStatus.FORBIDDEN;
            case FatumUserException.COGNITO_GROUP_FAILURE -> HttpStatus.BAD_GATEWAY;
            case FatumUserException.USER_ALREADY_EXISTS,
                 FatumUserException.EMAIL_EXISTS,
                 FatumUserException.USERNAME_EXISTS,
                 FatumUserException.PHONE_EXISTS,
                 FatumUserException.DOCUMENT_EXISTS,
                 FatumUserException.ADDRESS_EXISTS -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
        return buildError(status, exception.getMessage(), request.getRequestURI(), Map.of());
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

    /**
     * Failures of the shared storage service.
     *
     * <p>A rejected request keeps its 4xx, because the caller can fix it (wrong content type, file too
     * large, unknown route). An outage of the storage backend is a 502: this service is healthy, its
     * dependency is not.</p>
     */
    @ExceptionHandler(StorageException.class)
    public ResponseEntity<ApiError> handleStorageFailure(
            StorageException exception,
            HttpServletRequest request) {
        HttpStatus status = exception.isClientError()
                ? HttpStatus.BAD_REQUEST
                : HttpStatus.BAD_GATEWAY;
        return buildError(status, exception.getMessage(), request.getRequestURI(), Map.of());
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<ApiError> handleIoError(
            IOException exception,
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
