# F05 — Account Lookup API

## Summary

Read-only endpoint that returns the current state of a single account, addressed by its account number.

## User story

As a client (or as a support agent through a thin UI), I want to fetch an account by its account number and see its current balance and status, so that I can show or use that information without having to derive it from the ledger.

## In scope

- HTTP endpoint definition (path, method, request/response shape).
- The fields exposed to the caller.
- Behaviour when the account does not exist.

## Out of scope

- Listing accounts.
- Filtering by status, balance ranges, etc.
- Returning the account's transaction history (could be a later feature).
- Mutation of the account.

## Functional requirements

- Endpoint: `GET /api/v1/accounts/{accountNumber}`.
- On success: returns HTTP 200 with a body containing the account number, the current balance, and the current status.
- On missing account: returns HTTP 404 with the standard error envelope (F03), code `RESOURCE_NOT_FOUND`.
- The endpoint is read-only and idempotent.
- The endpoint does not require authentication in the first iteration.
- The exposed status values must include every status the system can actually return for a non-deleted account.

## Acceptance criteria

1. `GET /api/v1/accounts/<existing>` returns 200 with the expected account number, balance, and status.
2. The returned balance reflects any committed transfer at the moment of the read — there is no staleness window relative to completed transactions.
3. `GET /api/v1/accounts/<missing>` returns 404 with `code = RESOURCE_NOT_FOUND` and a message naming the missing account number.
4. The response schema matches the OpenAPI contract exactly: no extra fields, no missing required fields.
5. The status enum in the response contract is wide enough to represent every status the lookup could ever return. (See open questions.)
6. Repeatedly reading the same account in succession returns identical bodies (within a single committed state).

## Dependencies

- F01 (Account domain).
- F03 (API error contract).
- F04 (Contract-first API).

## Open questions

- The current OpenAPI enum for account status omits `CLOSED`. **Decision required:** either widen the enum to include `CLOSED`, or guarantee that closed accounts are never returnable from this endpoint (e.g. by filtering them out and returning 404). Today the system would 500 on a closed account.
- Should balance be returned as a string (precise decimal) rather than a JSON number, to avoid client-side float drift? Today it is a number. Recommended to revisit before integrating with any client that does monetary math.
