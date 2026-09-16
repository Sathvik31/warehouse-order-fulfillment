package com.warehouse.inventory.web;

import com.warehouse.inventory.service.InventoryService.InsufficientStockException;
import com.warehouse.inventory.service.InventoryService.ItemNotFoundException;
import com.warehouse.inventory.service.InventoryService.StockNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import com.warehouse.inventory.service.InventoryService;

import java.net.URI;
import java.util.UUID;
import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(ItemNotFoundException.class)
    public ProblemDetail handleItemNotFound(ItemNotFoundException ex, HttpServletRequest request) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setType(URI.create("https://api.warehouse/errors/item-not-found"));
        pd.setTitle("Item not found");
        pd.setInstance(URI.create(request.getRequestURI()));
        pd.setProperty("correlationId", UUID.randomUUID().toString());
        return pd;
    }

    @ExceptionHandler(StockNotFoundException.class)
    public ProblemDetail handleStockNotFound(StockNotFoundException ex, HttpServletRequest request) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setType(URI.create("https://api.warehouse/errors/stock-not-found"));
        pd.setTitle("Stock not found");
        pd.setInstance(URI.create(request.getRequestURI()));
        pd.setProperty("correlationId", UUID.randomUUID().toString());
        return pd;
    }

    @ExceptionHandler(InsufficientStockException.class)
    public ProblemDetail handleInsufficientStock(InsufficientStockException ex, HttpServletRequest request) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setType(URI.create("https://api.warehouse/errors/insufficient-stock"));
        pd.setTitle("Insufficient stock");
        pd.setInstance(URI.create(request.getRequestURI()));
        pd.setProperty("requested", ex.getRequested());
        pd.setProperty("available", ex.getAvailable());
        pd.setProperty("correlationId", UUID.randomUUID().toString());
        return pd;
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ProblemDetail handleMissingHeader(MissingRequestHeaderException ex, HttpServletRequest request) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Missing required header: " + ex.getHeaderName());
        pd.setType(URI.create("https://api.warehouse/errors/missing-header"));
        pd.setTitle("Missing required header");
        pd.setInstance(URI.create(request.getRequestURI()));
        pd.setProperty("correlationId", UUID.randomUUID().toString());
        return pd;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
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

    @ExceptionHandler(InventoryService.ReservationNotFoundException.class)
    public ProblemDetail handleReservationNotFound(
            InventoryService.ReservationNotFoundException ex, HttpServletRequest request) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setType(URI.create("https://api.warehouse/errors/reservation-not-found"));
        pd.setTitle("Reservation not found");
        pd.setInstance(URI.create(request.getRequestURI()));
        pd.setProperty("correlationId", UUID.randomUUID().toString());
        return pd;
    }

    @ExceptionHandler(InventoryService.InvalidReservationStateException.class)
    public ProblemDetail handleInvalidReservationState(
            InventoryService.InvalidReservationStateException ex, HttpServletRequest request) {
        var pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setType(URI.create("https://api.warehouse/errors/invalid-reservation-state"));
        pd.setTitle("Invalid reservation state");
        pd.setInstance(URI.create(request.getRequestURI()));
        pd.setProperty("correlationId", UUID.randomUUID().toString());
        return pd;
    }
}