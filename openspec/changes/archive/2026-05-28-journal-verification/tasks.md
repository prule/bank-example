## 1. Domain and Application Use Case

- [x] 1.1 Implement the `SweepReport` record in `:application` package `com.bank.core.application.ledger` with core processed counter validation.
- [x] 1.2 Implement the `VerifyPendingJournals` plain-Java use case under `:application` package `com.bank.core.application.ledger` executing database-side checks, journal marking, and deduplicated suspend cascades inside independent try-catches.

## 2. Infrastructure Wiring and Background Scheduler

- [x] 2.1 Implement the `JournalVerificationScheduler` Spring component in the `:bootstrap` module package `com.bank.core.bootstrap` scheduling bounded, non-overlapping sweeps via `@Scheduled`.
- [x] 2.2 Wire the `VerifyPendingJournals` use case bean inside `BankCoreApplication`.

## 3. Verification and Testing

- [x] 3.1 Write comprehensive unit tests covering `VerifyPendingJournals` normal sweep flows, unbalanced failures, cascade suspensions, and per-journal error recovery using Mockito-free test fakes.
- [x] 3.2 Write end-to-end integration tests in `:bootstrap` to verify scheduler scheduled delay configuration, Awaitility-backed promotions, page size boundaries, and error logging.
