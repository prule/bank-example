```text
the openapi example parameters should match the seeded data
```

Now let me check for tests that snapshot the OpenAPI contract or assert these example strings.

All tests still pass. OpenAPI examples now match the seed data:

- `account-response.yaml` — `accountNumber: "CUST-1001"`, `balance: "500.00"` (matches CUST-1001's seeded balance)
- `transfer-request.yaml` — `sourceAccountNumber: "CUST-1001"`, `destinationAccountNumber: "CUST-1002"`, `amount: 50.00` (well within both customers' seeded balances)

Want me to commit this and then resume the archive flow, or roll the OpenAPI fix into the archive commit?
