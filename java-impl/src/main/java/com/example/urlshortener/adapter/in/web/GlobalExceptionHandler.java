package com.example.urlshortener.adapter.in.web;

import com.example.urlshortener.adapter.in.web.dto.ApiError;
import com.example.urlshortener.adapter.in.web.dto.ApiError.FieldIssue;
import com.example.urlshortener.domain.exception.AliasAlreadyExistsException;
import com.example.urlshortener.domain.exception.CodeAllocationException;
import com.example.urlshortener.domain.exception.DomainException;
import com.example.urlshortener.domain.exception.InvalidAliasException;
import com.example.urlshortener.domain.exception.InvalidUrlException;
import com.example.urlshortener.domain.exception.LinkGoneException;
import com.example.urlshortener.domain.exception.LinkNotFoundException;
import com.example.urlshortener.domain.exception.RequestValidationException;
import com.example.urlshortener.infra.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

/**
 * Turns every exception into the one {@link ApiError} envelope.
 *
 * <p>Design rules:
 * <ul>
 *   <li>expected business failures ({@link DomainException}) carry their own
 *       stable {@code errorCode} and a client-safe message;</li>
 *   <li>framework request errors (bad JSON, wrong method/media-type, missing
 *       params) get precise 4xx codes;</li>
 *   <li>anything else is a 500 whose body is generic — the cause, stack trace,
 *       SQL and class names are logged with the request id and never returned.</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ---------------------------------------------------- domain (expected)

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ApiError> handleDomain(DomainException ex, HttpServletRequest request) {
        HttpStatus status = statusFor(ex);
        if (status.is5xxServerError()) {
            log.error("domain error {} on {} {}", ex.errorCode(), request.getMethod(), request.getRequestURI(), ex);
        } else {
            log.debug("rejected {} {}: {} - {}", request.getMethod(), request.getRequestURI(),
                    ex.errorCode(), ex.getMessage());
        }
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if (ex instanceof CodeAllocationException) {
            builder.header(HttpHeaders.RETRY_AFTER, "1");
        }
        return builder.body(body(ex.errorCode(), ex.getMessage(), request, null));
    }

    private static HttpStatus statusFor(DomainException ex) {
        if (ex instanceof LinkNotFoundException) {
            return HttpStatus.NOT_FOUND;
        }
        if (ex instanceof LinkGoneException) {
            return HttpStatus.GONE;
        }
        if (ex instanceof AliasAlreadyExistsException) {
            return HttpStatus.CONFLICT;
        }
        if (ex instanceof CodeAllocationException) {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
        if (ex instanceof InvalidUrlException
                || ex instanceof InvalidAliasException
                || ex instanceof RequestValidationException) {
            return HttpStatus.BAD_REQUEST;
        }
        return HttpStatus.BAD_REQUEST;
    }

    // ------------------------------------------------- bean / param validation

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleBodyValidation(MethodArgumentNotValidException ex,
                                                         HttpServletRequest request) {
        List<FieldIssue> issues = ex.getBindingResult().getFieldErrors().stream()
                .map(this::toIssue).toList();
        return ResponseEntity.badRequest()
                .body(body("VALIDATION_ERROR", "request validation failed", request, issues));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleParamValidation(ConstraintViolationException ex,
                                                          HttpServletRequest request) {
        List<FieldIssue> issues = ex.getConstraintViolations().stream()
                .map(v -> new FieldIssue(lastNode(v.getPropertyPath().toString()), v.getMessage()))
                .toList();
        return ResponseEntity.badRequest()
                .body(body("VALIDATION_ERROR", "request validation failed", request, issues));
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> handleBadParam(Exception ex, HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(body("BAD_REQUEST", "a request parameter is missing or of the wrong type", request, null));
    }

    // -------------------------------------------------- malformed / protocol

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(body("MALFORMED_BODY", "request body is missing, malformed, or contains unknown fields",
                        request, null));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethod(HttpRequestMethodNotSupportedException ex,
                                                 HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(body("METHOD_NOT_ALLOWED", "HTTP method not supported for this resource", request, null));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> handleMediaType(HttpMediaTypeNotSupportedException ex,
                                                    HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(body("UNSUPPORTED_MEDIA_TYPE", "Content-Type must be application/json", request, null));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleTooLarge(MaxUploadSizeExceededException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(body("PAYLOAD_TOO_LARGE", "request body is too large", request, null));
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ApiError> handleNotFound(Exception ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(body("NOT_FOUND", "no resource at " + request.getMethod() + " " + request.getRequestURI(),
                        request, null));
    }

    // ---------------------------------------------------------- catch-all

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body("INTERNAL", "an unexpected error occurred", request, null));
    }

    // ------------------------------------------------------------- helpers

    private ApiError body(String code, String message, HttpServletRequest request, List<FieldIssue> details) {
        Object rid = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        return ApiError.of(code, message, rid == null ? null : rid.toString(), request.getRequestURI(), details);
    }

    private FieldIssue toIssue(FieldError fe) {
        return new FieldIssue(fe.getField(), fe.getDefaultMessage());
    }

    private static String lastNode(String path) {
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(dot + 1) : path;
    }
}
