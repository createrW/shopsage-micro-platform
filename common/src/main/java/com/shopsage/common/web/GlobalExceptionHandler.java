package com.shopsage.common.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every exception escaping a controller into an RFC 7807 {@link ProblemDetail}
 * ({@code application/problem+json}).
 *
 * <p>Extends {@link ResponseEntityExceptionHandler} so Spring MVC's own exceptions (unknown route 404, 405, 415,
 * malformed body 400, ...) keep their correct status codes instead of falling into the catch-all 500. Lowest
 * precedence lets a service add a more specific advice without fighting this one. Registered by
 * {@link CommonWebAutoConfiguration}, not by component scanning.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    public static final URI NOT_FOUND_TYPE = URI.create("urn:shopsage:problem:not-found");
    public static final URI BUSINESS_RULE_TYPE = URI.create("urn:shopsage:problem:business-rule");
    public static final URI VALIDATION_TYPE = URI.create("urn:shopsage:problem:validation");

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** One bean-validation violation, rendered in the ProblemDetail's {@code errors} array. */
    public record FieldViolation(String field, String message) {
    }

    /**
     * Overridden rather than declared via {@code @ExceptionHandler(MethodArgumentNotValidException.class)}:
     * the base class already maps that exception, and two mappings make the context fail on startup.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ex.getBody();
        problem.setType(VALIDATION_TYPE);
        problem.setDetail("Request validation failed");
        List<FieldViolation> errors = Stream.concat(
                        ex.getBindingResult().getFieldErrors().stream()
                                .map(e -> new FieldViolation(e.getField(), e.getDefaultMessage())),
                        ex.getBindingResult().getGlobalErrors().stream()
                                .map(e -> new FieldViolation(e.getObjectName(), e.getDefaultMessage())))
                .toList();
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    /**
     * Method-level validation ({@code @Validated} bean with constrained {@code @RequestParam}/{@code @PathVariable}
     * arguments) throws this from the AOP proxy, outside the base class's mappings; without it a client error would
     * fall into the catch-all 500. Same shape as {@link #handleMethodArgumentNotValid}.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
        problem.setType(VALIDATION_TYPE);
        List<FieldViolation> errors = ex.getConstraintViolations().stream()
                .map(v -> new FieldViolation(v.getPropertyPath().toString(), v.getMessage()))
                .toList();
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleNotFound(ResourceNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setType(NOT_FOUND_TYPE);
        return problem;
    }

    @ExceptionHandler(BusinessRuleException.class)
    public ProblemDetail handleBusinessRule(BusinessRuleException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setType(BUSINESS_RULE_TYPE);
        return problem;
    }

    // IllegalArgumentException is intentionally NOT mapped to 400: it usually signals a bug in our code, and
    // reporting it as a client error would hide it from 5xx alerts.

    /**
     * Catch-all: log with stack trace, answer with a generic message so internals never leak to clients.
     *
     * <p>Spring Security exceptions (Day 2+) are rethrown: an {@code @ExceptionHandler} runs inside the
     * DispatcherServlet, before Security's {@code ExceptionTranslationFilter} sees the exception, so swallowing
     * an {@code AccessDeniedException} here would turn a 401/403 into a 500. Matched by class name because
     * common does not (and should not) depend on spring-security.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex, HttpServletRequest request) throws Exception {
        if (ex.getClass().getName().startsWith("org.springframework.security.")) {
            throw ex;
        }
        log.error("Unhandled exception for {} {}", request.getMethod(), request.getRequestURI(), ex);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected server error");
    }
}
