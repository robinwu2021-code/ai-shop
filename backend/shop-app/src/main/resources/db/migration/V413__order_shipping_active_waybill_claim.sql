-- Concurrent orders must not both claim one active carrier/waybill after a null lookup.
-- A voided package releases the nullable unique key; historical package identity remains unchanged.
ALTER TABLE ord_shipment
    ADD COLUMN active_waybill_no VARCHAR(64)
        GENERATED ALWAYS AS (CASE WHEN status <> 'VOIDED' AND deleted = 0 THEN waybill_no ELSE NULL END) STORED;

ALTER TABLE ord_shipment
    ADD UNIQUE KEY uk_ord_shipment_active_waybill (carrier, active_waybill_no, tenant_no);
