package com.bank.core.infrastructure.web;

import com.bank.core.domain.AccountInactiveException;
import com.bank.core.domain.IllegalStatusTransitionException;
import com.bank.core.domain.InsufficientFundsException;
import com.bank.core.domain.InvalidAmountException;
import com.bank.core.domain.ResourceNotFoundException;
import com.bank.core.domain.SameAccountTransferException;
import com.bank.core.dto.ErrorEnvelope;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.OffsetDateTime;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getField)
                .map(field -> "Field '" + field + "' is invalid or missing")
                .collect(Collectors.joining(", "));
        if (message.isEmpty()) {
            message = "Validation failed for request payload";
        }
        
        ErrorEnvelope envelope = new ErrorEnvelope(
                ErrorEnvelope.CodeEnum.BAD_REQUEST_PAYLOAD,
                message,
                OffsetDateTime.now()
        );
        return new ResponseEntity<>(envelope, HttpStatus.BAD_REQUEST);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ErrorEnvelope envelope = new ErrorEnvelope(
                ErrorEnvelope.CodeEnum.BAD_REQUEST_PAYLOAD,
                "Malformed JSON request body",
                OffsetDateTime.now()
        );
        return new ResponseEntity<>(envelope, HttpStatus.BAD_REQUEST);
    }

    @Override
    protected ResponseEntity<Object> handleNoHandlerFoundException(
            NoHandlerFoundException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ErrorEnvelope envelope = new ErrorEnvelope(
                ErrorEnvelope.CodeEnum.RESOURCE_NOT_FOUND,
                String.format("Resource not found: %s %s", ex.getHttpMethod(), ex.getRequestURL()),
                OffsetDateTime.now()
        );
        return new ResponseEntity<>(envelope, HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<ErrorEnvelope> handleInsufficientFunds(InsufficientFundsException ex) {
        log.info("Insufficient funds exception: {}", ex.getMessage());
        ErrorEnvelope envelope = new ErrorEnvelope(
                ErrorEnvelope.CodeEnum.INSUFFICIENT_FUNDS,
                ex.getMessage(),
                OffsetDateTime.now()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(envelope);
    }

    @ExceptionHandler(AccountInactiveException.class)
    public ResponseEntity<ErrorEnvelope> handleAccountInactive(AccountInactiveException ex) {
        log.info("Account inactive exception: {}", ex.getMessage());
        ErrorEnvelope envelope = new ErrorEnvelope(
                ErrorEnvelope.CodeEnum.ACCOUNT_INACTIVE,
                ex.getMessage(),
                OffsetDateTime.now()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(envelope);
    }

    @ExceptionHandler(SameAccountTransferException.class)
    public ResponseEntity<ErrorEnvelope> handleSameAccountTransfer(SameAccountTransferException ex) {
        log.info("Same account transfer exception: {}", ex.getMessage());
        ErrorEnvelope envelope = new ErrorEnvelope(
                ErrorEnvelope.CodeEnum.BAD_REQUEST_PAYLOAD,
                ex.getMessage(),
                OffsetDateTime.now()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(envelope);
    }

    @ExceptionHandler(InvalidAmountException.class)
    public ResponseEntity<ErrorEnvelope> handleInvalidAmount(InvalidAmountException ex) {
        ErrorEnvelope envelope = new ErrorEnvelope(
                ErrorEnvelope.CodeEnum.BAD_REQUEST_PAYLOAD,
                ex.getMessage(),
                OffsetDateTime.now()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(envelope);
    }

    @ExceptionHandler(IllegalStatusTransitionException.class)
    public ResponseEntity<ErrorEnvelope> handleIllegalStatusTransition(IllegalStatusTransitionException ex) {
        ErrorEnvelope envelope = new ErrorEnvelope(
                ErrorEnvelope.CodeEnum.BAD_REQUEST_PAYLOAD,
                ex.getMessage(),
                OffsetDateTime.now()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(envelope);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorEnvelope> handleResourceNotFound(ResourceNotFoundException ex) {
        log.info("Resource not found: type={}, identifier={}", ex.resourceType(), ex.identifier());

        ErrorEnvelope envelope = new ErrorEnvelope(
                ErrorEnvelope.CodeEnum.RESOURCE_NOT_FOUND,
                ex.getMessage(),
                OffsetDateTime.now()
        );
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(envelope);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorEnvelope> handleGenericException(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception occurred during request: {} {}", request.getMethod(), request.getRequestURI(), ex);

        ErrorEnvelope envelope = new ErrorEnvelope(
                ErrorEnvelope.CodeEnum.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Please contact support.",
                OffsetDateTime.now()
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(envelope);
    }
}
