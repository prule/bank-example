package com.bank.core.controller;

import com.bank.core.domain.AccountInactiveException;
import com.bank.core.domain.InsufficientFundsException;
import com.bank.core.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.time.OffsetDateTime;
import java.util.NoSuchElementException;

@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  /** Catches business violations where an account does not have enough liquidity. */
  @ExceptionHandler(InsufficientFundsException.class)
  public ResponseEntity<ErrorResponse> handleInsufficientFunds(InsufficientFundsException ex) {
    log.warn("Transfer failed due to insufficient funds: {}", ex.getMessage());

    ErrorResponse error = new ErrorResponse();
    error.setCode("INSUFFICIENT_FUNDS");
    error.setMessage(ex.getMessage());
    error.setTimestamp(OffsetDateTime.now());

    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
  }

  /** Catches operations attempted on frozen, suspended, or closed accounts. */
  @ExceptionHandler(AccountInactiveException.class)
  public ResponseEntity<ErrorResponse> handleAccountInactive(AccountInactiveException ex) {
    log.warn("Account interaction rejected: {}", ex.getMessage());

    ErrorResponse error = new ErrorResponse();
    error.setCode("ACCOUNT_INACTIVE");
    error.setMessage(ex.getMessage());
    error.setTimestamp(OffsetDateTime.now());

    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
  }

  /** Catches lookup failures when a target account number does not exist in the ledger. */
  @ExceptionHandler(NoSuchElementException.class)
  public ResponseEntity<ErrorResponse> handleNotFound(NoSuchElementException ex) {
    log.warn("Resource lookup failed: {}", ex.getMessage());

    ErrorResponse error = new ErrorResponse();
    error.setCode("RESOURCE_NOT_FOUND");
    error.setMessage(ex.getMessage());
    error.setTimestamp(OffsetDateTime.now());

    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
  }

  /**
   * Catches OpenAPI schema specification validation failures automatically thrown by
   * Spring's @Valid layer.
   */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ErrorResponse> handleValidationFailures(
      MethodArgumentNotValidException ex) {
    log.warn("Incoming payload validation failed across field constraints");

    // Pick the first structural error message or build a concatenated string
    String detailMessage =
        ex.getBindingResult().getFieldErrors().stream()
            .map(err -> err.getField() + " " + err.getDefaultMessage())
            .findFirst()
            .orElse("Invalid request payload parameters.");

    ErrorResponse error = new ErrorResponse();
    error.setCode("BAD_REQUEST_PAYLOAD");
    error.setMessage(detailMessage);
    error.setTimestamp(OffsetDateTime.now());

    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
  }

  /**
   * Catch-all safety net for unexpected runtime failures (e.g. Database connection losses, internal
   * NullPointerExceptions). This hides deep infrastructure configurations from the public network.
   */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleGenericFallback(Exception ex) {
    log.error("An unhandled system exception occurred at the core execution boundary", ex);

    ErrorResponse error = new ErrorResponse();
    error.setCode("INTERNAL_SERVER_ERROR");
    error.setMessage(
        "An unexpected error occurred. Please contact system administrators if issues persist.");
    error.setTimestamp(OffsetDateTime.now());

    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
  }
}
