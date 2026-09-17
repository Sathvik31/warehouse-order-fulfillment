package com.warehouse.fulfillment.web;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.UUID;
import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ProblemDetail handleMissingHeader(
            MissingRequestHeaderException ex, HttpServletRequest request) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Missing required header: " + ex.getHeaderName());
        pd.setType(URI.create("https://api.warehouse/errors/missing-header"));
        pd.setTitle("Missing required header");
        pd.setInstance(URI.create(request.getRequestURI()));
        pd.setProperty("correlationId", UUID.randomUUID().toString());
        return pd;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        var errors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining("; "));
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Request validation failed: " + errors);
        pd.setType(URI.create("https://api.warehouse/errors/validation-failed"));
        pd.setTitle("Validation failed");
        pd.setInstance(URI.create(request.getRequestURI()));
        pd.setProperty("correlationId", UUID.randomUUID().toString());
        return pd;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleMalformedJson(
            HttpMessageNotReadableException ex, HttpServletRequest request) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Malformed request body: " + rootCauseMessage(ex));
        pd.setType(URI.create("https://api.warehouse/errors/malformed-request"));
        pd.setTitle("Malformed request body");
        pd.setInstance(URI.create(request.getRequestURI()));
        pd.setProperty("correlationId", UUID.randomUUID().toString());
        return pd;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex, HttpServletRequest request) {
        var correlationId = UUID.randomUUID().toString();
        log.error("Unexpected error, correlationId={}", correlationId, ex);
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "Unexpected error. See logs with correlationId=" + correlationId);
        pd.setType(URI.create("https://api.warehouse/errors/internal"));
        pd.setTitle("Internal server error");
        pd.setInstance(URI.create(request.getRequestURI()));
        pd.setProperty("correlationId", correlationId);
        return pd;
    }

    private String rootCauseMessage(Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }
}