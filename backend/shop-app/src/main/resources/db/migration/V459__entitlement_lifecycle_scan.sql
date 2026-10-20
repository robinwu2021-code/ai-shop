-- Expiry and reminder scans share a bounded tenant/status/time window; no balance or state is changed.
CREATE INDEX ix_instance_expiry ON ent_instance(tenant_no,status,effective_until,id);
