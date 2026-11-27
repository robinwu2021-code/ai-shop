-- Beauty V9.1: the appointment records the work order it opened (ADR-067: appointments in the store trade
-- database, service execution in the industry database). Appointment guards read this column instead of
-- joining work orders. V1-V11 remain frozen; numbered before the optional V10-V11 extensions; existing one-per-appointment work orders are copied over.

ALTER TABLE `bty_appointment`
    ADD COLUMN `work_order_no` VARCHAR(32) COLLATE utf8mb4_bin NULL COMMENT 'work_order_no',
    ADD UNIQUE KEY `uk_bty_appointment_work_order` (`tenant_no`, `entity_no`, `work_order_no`);

UPDATE `bty_appointment` p
    JOIN `bty_work_order` w ON w.`tenant_no` = p.`tenant_no` AND w.`entity_no` = p.`entity_no`
        AND w.`appointment_no` = p.`appointment_no`
SET p.`work_order_no` = w.`work_order_no`
WHERE p.`work_order_no` IS NULL;
