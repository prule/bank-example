-- F11 — balance drift detection
-- Single-row-per-audit checkpoint table. Holds the highest ledger_movement.id
-- the named audit has processed; survives restarts. Only F11 writes here today
-- (audit_name = 'balance_drift'); future audits would each pick their own name.
--
-- The checkpoint advance commits in the same transaction as any account
-- suspensions performed by the same tick (BalanceDriftAudit.@Transactional),
-- per the balance-drift-detection spec scenario
-- "Checkpoint advances atomically with suspensions".

CREATE TABLE audit_checkpoint (
    audit_name        VARCHAR(64) NOT NULL PRIMARY KEY,
    last_movement_id  BIGINT      NOT NULL,
    CONSTRAINT last_movement_id_non_negative CHECK (last_movement_id >= 0)
);
