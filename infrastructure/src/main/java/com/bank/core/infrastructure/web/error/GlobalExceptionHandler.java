package com.bank.core.infrastructure.web.error;

import com.bank.core.domain.AccountInactiveException;
import com.bank.core.domain.InsufficientFundsException;
import com.bank.core.domain.InvalidAmountException;
import com.bank.core.domain.ResourceNotFoundException;
import com.bank.core.domain.SameAccountTransferException;
import com.bank.core.dto.ErrorEnvelope;
import com.bank.core.dto.ErrorEnvelope.CodeEnum;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Single source for converting Java exceptions into the canonical error envelope
 * defined by the api-error-contract capability. Every {@code 4xx}/{@code 5xx}
 * response from this service flows through here.
 *
 * F05 wired {@link ResourceNotFoundException} → 404 {@code RESOURCE_NOT_FOUND};
 * any new capability that surfaces a "missing X" condition can throw the same
 * {@link ResourceNotFoundException} (with its own {@code resourceType}) and
 * reuse the existing handler entry — no per-resource handler is needed.
 *
 * F06 wired four business-rule mappings:
 * <ul>
 *   <li>{@link InsufficientFundsException}      → 400 {@code INSUFFICIENT_FUNDS}</li>
 *   <li>{@link AccountInactiveException}        → 400 {@code ACCOUNT_INACTIVE}</li>
 *   <li>{@link InvalidAmountException}          → 400 {@code BAD_REQUEST_PAYLOAD}</li>
 *   <li>{@link SameAccountTransferException}    → 400 {@code BAD_REQUEST_PAYLOAD}</li>
 * </ul>
 * Each handler logs at INFO — these are expected business-rule rejections,
 * not faults; operators should not be paged. Any future business-rule
 * exception that extends {@link com.bank.core.domain.DomainException} can be
 * added the same way: one method per exception so the per-type message
 * stays explicit.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    static final String GENERIC_500_MESSAGE = "An unexpected error occurred. Please contact support.";
    static final String GENERIC_PARSE_MESSAGE = "Malformed request body.";
    private static final int MAX_VALIDATION_FIELDS = 3;

    @ExceptionHandler({
            MethodArgumentNotValidException.class,
            BindException.class,
            ConstraintViolationException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ErrorEnvelope> handleValidation(Exception ex, HttpServletRequest request) {
        String message = buildValidationMessage(ex);
        log.info("Validation failure on {} {}: {}", request.getMethod(), request.getRequestURI(), message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(envelope(CodeEnum.BAD_REQUEST_PAYLOAD, message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorEnvelope> handleParse(HttpMessageNotReadableException ex, HttpServletRequest request) {
        log.info("Parse failure on {} {}: {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(envelope(CodeEnum.BAD_REQUEST_PAYLOAD, GENERIC_PARSE_MESSAGE));
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ErrorEnvelope> handleNotFound(Exception ex, HttpServletRequest request) {
        String message = "No handler found for " + request.getMethod() + " " + request.getRequestURI();
        log.info("{}", message);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(envelope(CodeEnum.RESOURCE_NOT_FOUND, message));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorEnvelope> handleResourceNotFound(ResourceNotFoundException ex,
                                                                 HttpServletRequest request) {
        log.info("Resource lookup miss: {} '{}' on {} {}",
                ex.resourceType(), ex.identifier(), request.getMethod(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(envelope(CodeEnum.RESOURCE_NOT_FOUND, ex.getMessage()));
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<ErrorEnvelope> handleInsufficientFunds(InsufficientFundsException ex,
                                                                 HttpServletRequest request) {
        log.info("Insufficient funds on {} {}: account={} attempted={} available={}",
                request.getMethod(), request.getRequestURI(),
                ex.accountId(), ex.attempted(), ex.available());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(envelope(CodeEnum.INSUFFICIENT_FUNDS,
                        "Source account has insufficient funds for the requested transfer."));
    }

    @ExceptionHandler(AccountInactiveException.class)
    public ResponseEntity<ErrorEnvelope> handleAccountInactive(AccountInactiveException ex,
                                                                HttpServletRequest request) {
        log.info("Account inactive on {} {}: account={} status={}",
                request.getMethod(), request.getRequestURI(),
                ex.accountId(), ex.status());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(envelope(CodeEnum.ACCOUNT_INACTIVE,
                        "Account " + ex.accountId() + " is not Active (status: " + ex.status() + ")."));
    }

    @ExceptionHandler(InvalidAmountException.class)
    public ResponseEntity<ErrorEnvelope> handleInvalidAmount(InvalidAmountException ex,
                                                              HttpServletRequest request) {
        log.info("Invalid amount on {} {}: {}",
                request.getMethod(), request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(envelope(CodeEnum.BAD_REQUEST_PAYLOAD, ex.getMessage()));
    }

    @ExceptionHandler(SameAccountTransferException.class)
    public ResponseEntity<ErrorEnvelope> handleSameAccountTransfer(SameAccountTransferException ex,
                                                                    HttpServletRequest request) {
        log.info("Same-account transfer rejected on {} {}: account={}",
                request.getMethod(), request.getRequestURI(), ex.account());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(envelope(CodeEnum.BAD_REQUEST_PAYLOAD, ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorEnvelope> handleCatchAll(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception while serving {} {}: ", request.getMethod(), request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(envelope(CodeEnum.INTERNAL_SERVER_ERROR, GENERIC_500_MESSAGE));
    }

    private static ErrorEnvelope envelope(CodeEnum code, String message) {
        ErrorEnvelope envelope = new ErrorEnvelope();
        envelope.setCode(code);
        envelope.setMessage(message);
        envelope.setTimestamp(OffsetDateTime.now(ZoneOffset.UTC));
        return envelope;
    }

    private static String buildValidationMessage(Exception ex) {
        List<String> fragments;
        if (ex instanceof MethodArgumentNotValidException manve) {
            fragments = manve.getBindingResult().getFieldErrors().stream()
                    .limit(MAX_VALIDATION_FIELDS)
                    .map(GlobalExceptionHandler::describeFieldError)
                    .toList();
        } else if (ex instanceof BindException be) {
            fragments = be.getBindingResult().getFieldErrors().stream()
                    .limit(MAX_VALIDATION_FIELDS)
                    .map(GlobalExceptionHandler::describeFieldError)
                    .toList();
        } else if (ex instanceof ConstraintViolationException cve) {
            fragments = cve.getConstraintViolations().stream()
                    .limit(MAX_VALIDATION_FIELDS)
                    .map(GlobalExceptionHandler::describeConstraint)
                    .toList();
        } else if (ex instanceof MissingServletRequestParameterException msrpe) {
            fragments = List.of("field '" + msrpe.getParameterName() + "' is required");
        } else if (ex instanceof MethodArgumentTypeMismatchException matme) {
            fragments = List.of("field '" + matme.getName() + "' has wrong type");
        } else {
            fragments = List.of();
        }
        if (fragments.isEmpty()) {
            return "Validation failed.";
        }
        return "Validation failed: " + fragments.stream().collect(Collectors.joining(", ")) + ".";
    }

    private static String describeFieldError(FieldError error) {
        String detail = error.getDefaultMessage();
        if (detail == null || detail.isBlank()) {
            detail = "is invalid";
        }
        return "field '" + error.getField() + "' " + detail;
    }

    private static String describeConstraint(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath().toString();
        if (path.isBlank()) {
            path = "request";
        }
        String detail = violation.getMessage();
        if (detail == null || detail.isBlank()) {
            detail = "is invalid";
        }
        return "field '" + path + "' " + detail;
    }
}
