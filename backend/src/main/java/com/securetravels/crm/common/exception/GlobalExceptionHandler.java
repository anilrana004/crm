package com.securetravels.crm.common.exception;

import com.securetravels.crm.common.dto.ApiError;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

/**
 * Single error surface: {@link ApiError} with a stable code, never raw
 * stack traces. Field-level problems are collected from Bean Validation.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> onValidation(MethodArgumentNotValidException ex) {
        List<ApiError.FieldError> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiError.FieldError(fe.getField(), fe.getDefaultMessage()))
                .toList();
        return ResponseEntity.badRequest().body(
                new ApiError(HttpStatus.BAD_REQUEST.value(), "VALIDATION_FAILED", "Check your input", errors));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> onConstraint(ConstraintViolationException ex) {
        List<ApiError.FieldError> errors = ex.getConstraintViolations().stream()
                .map(v -> new ApiError.FieldError(v.getPropertyPath().toString(), v.getMessage()))
                .toList();
        return ResponseEntity.badRequest().body(
                new ApiError(HttpStatus.BAD_REQUEST.value(), "VALIDATION_FAILED", "Check your input", errors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> onUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(
                new ApiError(HttpStatus.BAD_REQUEST.value(), "MALFORMED_BODY", "Request body is malformed"));
    }

    /**
     * A query parameter that cannot be converted to its declared type: a bad UUID,
     * a bad date, or an enum name that does not exist.
     *
     * <p>This was unhandled, so bad input reached the catch-all and returned 500.
     * That is wrong twice over: the status misdescribes a caller mistake as a
     * server fault, and it makes every endpoint with a typed query parameter look
     * fragile to a client that fuzzes its inputs. {@code ?season=SUMMER} is a
     * caller error and now says so.
     *
     * <p>Only the parameter NAME is echoed back. The rejected value itself is not,
     * so a malformed parameter cannot be used to reflect content into the error
     * body.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> onTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String name = ex.getName();
        String expected = ex.getRequiredType() == null ? "the expected type" : ex.getRequiredType().getSimpleName();
        return ResponseEntity.badRequest().body(new ApiError(
                HttpStatus.BAD_REQUEST.value(),
                "INVALID_PARAMETER",
                "Parameter '" + name + "' is not a valid " + expected,
                List.of(new ApiError.FieldError(name,
                        "expected a value of type " + expected))));
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiError> onBadRequest(BadRequestException ex) {
        return ResponseEntity.badRequest().body(
                new ApiError(HttpStatus.BAD_REQUEST.value(), "BAD_REQUEST", ex.getMessage()));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> onNotFound(NotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                new ApiError(HttpStatus.NOT_FOUND.value(), "NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> onConflict(ConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
                new ApiError(HttpStatus.CONFLICT.value(), "CONFLICT", ex.getMessage()));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ApiError> onForbidden(ForbiddenException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                new ApiError(HttpStatus.FORBIDDEN.value(), "FORBIDDEN", ex.getMessage()));
    }

    @ExceptionHandler(WebhookSignatureException.class)
    public ResponseEntity<ApiError> onWebhookSignature(WebhookSignatureException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                new ApiError(HttpStatus.UNAUTHORIZED.value(), "INVALID_SIGNATURE", ex.getMessage()));
    }

    @ExceptionHandler(ServiceUnavailableException.class)
    public ResponseEntity<ApiError> onServiceUnavailable(ServiceUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                new ApiError(HttpStatus.SERVICE_UNAVAILABLE.value(), "SERVICE_UNAVAILABLE", ex.getMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> onAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                new ApiError(HttpStatus.FORBIDDEN.value(), "FORBIDDEN", "You do not have permission for this action"));
    }

    @ExceptionHandler({BadCredentialsException.class, AuthenticationException.class})
    public ResponseEntity<ApiError> onAuth(AuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                new ApiError(HttpStatus.UNAUTHORIZED.value(), "UNAUTHORIZED", "Invalid email or password"));
    }

    /**
     * A request to a path that maps to no controller and no static resource is
     * a client error, not a server fault. Spring throws
     * {@link NoResourceFoundException} from the static-resource handler (and
     * {@link NoHandlerFoundException} when no handler is resolvable at all);
     * without these, both fall through to the generic {@code Exception}
     * handler and get reported as 500.
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiError> onNoSuchRoute(Exception ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                new ApiError(HttpStatus.NOT_FOUND.value(), "NOT_FOUND", "No endpoint for this path"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> onUnexpected(Exception ex) {
        log.error("Unhandled error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                new ApiError(HttpStatus.INTERNAL_SERVER_ERROR.value(), "INTERNAL_ERROR",
                        "An unexpected error occurred"));
    }
}