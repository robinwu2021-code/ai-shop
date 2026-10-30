-- Beauty V7: preserve each capacity demand unit as a distinct planned assignment.
-- V1-V6 and all existing rows remain unchanged; planned_assignment_no stays unique.

ALTER TABLE `bty_appointment_assignment`
    DROP INDEX `uk_bty_appointment_assignment_2`,
    ADD INDEX `idx_bty_appointment_assignment_1` (`tenant_no`, `entity_no`, `appointment_item_no`, `appointment_revision`, `requirement_no`, `resource_no`);
