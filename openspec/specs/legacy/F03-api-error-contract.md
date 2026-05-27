# F03 — API Error Contract

## Summary

Defines the single shape used for every error returned by the public API and the set of stable error codes callers can rely on. Without this, every endpoint would invent its own error format and no client could handle failures programmatically.

## User story

As a client integrating with this service, I want a predictable error envelope and a stable, documented set of error codes so that I can branch my retry, surfacing, and alerting logic on machine-parseable values rather than guessing from prose.

## In scope

- The wire-format shape of an error response.
- The canonical list of error codes and the HTTP status each maps to.
- The mapping rules from domain errors to HTTP responses.
- Catch-all behaviour for unanticipated failures.

## Out of scope

- Which endpoints emit which errors — that lives in each endpoint's spec.
- Localisation of error messages (English only for now).
- Structured field-level error arrays (a single human-readable message is sufficient for this iteration).

## Functional requirements

- Every error response from the public API has the same shape: a stable machine code, a human-readable message, and a timestamp.
- The error code is a short uppercase identifier with underscores, intended for client-side switch statements. Codes are NEVER renamed; new codes may be added.
- The error message is intended for support and log surfaces. Clients must not pattern-match on its content.
- The timestamp is when the response was generated, in ISO-8601 with timezone.
- The canonical code/status mapping for the first iteration:

| Code                    | HTTP | Cause                                                        |
|-------------------------|------|--------------------------------------------------------------|
| `INSUFFICIENT_FUNDS`    | 400  | Debit would leave source account at or below zero            |
| `ACCOUNT_INACTIVE`      | 400  | A non-Active account was targeted by an operation            |
| `RESOURCE_NOT_FOUND`    | 404  | Account or other addressed resource does not exist           |
| `BAD_REQUEST_PAYLOAD`   | 400  | Incoming payload violated declared constraints (validation)  |
| `INTERNAL_SERVER_ERROR` | 500  | Catch-all for unhandled failures                             |

- A request that fails contract validation (missing required field, value out of range) maps to `BAD_REQUEST_PAYLOAD`. The message should name the offending field and what was wrong with it.
- A `404` is returned only when a resource referenced by the request does not exist; "not enough money" is `400`, not `404`.
- Any unhandled exception in the server returns `500` with `INTERNAL_SERVER_ERROR`. The message must NOT leak stack traces, internal class names, or other implementation detail to the caller; full detail goes to server logs.

## Acceptance criteria

1. Every error response, regardless of endpoint, carries exactly three top-level fields: `code`, `message`, `timestamp`. No extras.
2. The `code` value for each documented failure category matches the table above.
3. The HTTP status for each documented failure category matches the table above.
4. A request missing a required field returns `400` with `BAD_REQUEST_PAYLOAD` and a message that identifies at least one offending field.
5. A request that triggers an unhandled internal failure returns `500` with `INTERNAL_SERVER_ERROR` and a generic message; the original exception detail appears in logs at error level but not in the response body.
6. The error response schema is published as part of the OpenAPI contract (F04).

## Dependencies

- F04 (Contract-first API) for publishing the schema.

## Open questions

- Do we want a correlation/request id field added to the envelope for log correlation? Useful for the first production deployment; not present in the current code.
- Should `ACCOUNT_INACTIVE` be `409 Conflict` rather than `400 Bad Request`? Today it is `400`. Decision pending.
