-- Conforming existing audit_checkpoint table from V1__init to match the capability specification
ALTER TABLE audit_checkpoint RENAME COLUMN id TO audit_name;
ALTER TABLE audit_checkpoint ALTER COLUMN audit_name VARCHAR(64) NOT NULL;
ALTER TABLE audit_checkpoint RENAME COLUMN last_processed_id TO last_movement_id;
ALTER TABLE audit_checkpoint ADD CONSTRAINT chk_last_movement_id CHECK (last_movement_id >= 0);
ALTER TABLE audit_checkpoint DROP COLUMN updated_at;
