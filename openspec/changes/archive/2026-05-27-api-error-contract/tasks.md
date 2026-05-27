## 1. Web Infrastructure Setup

- [x] 1.1 Create the GlobalExceptionHandler class in com.bank.core.infrastructure.web extending ResponseEntityExceptionHandler.
- [x] 1.2 Enable Spring MVC configuration to throw NoHandlerFoundException and disable static mappings in application.yaml to ensure unmapped paths trigger exceptions.

## 2. Spring MVC Exception Handlers

- [x] 2.1 Override handleMethodArgumentNotValid to map payload validation errors to BAD_REQUEST_PAYLOAD (HTTP 400) and name the offending fields.
- [x] 2.2 Override handleHttpMessageNotReadable to map malformed JSON bodies to BAD_REQUEST_PAYLOAD (HTTP 400), returning a clean human-readable message without Jackson internal traces.
- [x] 2.3 Override handleNoHandlerFoundException to map unrecognized URL paths to RESOURCE_NOT_FOUND (HTTP 404).

## 3. Custom Domain Exception Mappings

- [x] 3.1 Implement exception handler for InsufficientFundsException mapping to INSUFFICIENT_FUNDS (HTTP 400) and including attempted details in message.
- [x] 3.2 Implement exception handler for AccountInactiveException mapping to ACCOUNT_INACTIVE (HTTP 400).
- [x] 3.3 Implement exception handlers for InvalidAmountException and IllegalStatusTransitionException mapping to BAD_REQUEST_PAYLOAD (HTTP 400).

## 4. Catch-All and Server Logging

- [x] 4.1 Implement general catch-all Exception handler returning INTERNAL_SERVER_ERROR (HTTP 500), generic message, and full internal keyword/SQL string redaction.
- [x] 4.2 Integrate robust logging in the unhandled handler, writing complete request method, request path, and stack trace to server logs at ERROR level.

## 5. Integration Verification

- [x] 5.1 Write comprehensive integration test suite to verify every scenario: envelope fields, timestamp formatting, validation messages, and generic HTTP 500 payloads.
