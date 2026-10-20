-- Remove the Ops-web entry and its live grants; keep the Elec service and all historical records.
UPDATE sys_function
SET enabled=0, updated_at=CURRENT_TIMESTAMP
WHERE function_code='OPS_ELEC' AND end_code='OPS' AND deleted=0;

UPDATE sys_function_point
SET deleted=1, ui_ready=0, updated_at=CURRENT_TIMESTAMP
WHERE function_code='OPS_ELEC' AND deleted=0;

UPDATE sys_role_point
SET deleted=1, updated_at=CURRENT_TIMESTAMP
WHERE end_code='OPS' AND point_code IN (
  'OPS_ELEC', 'OPS_ELEC__TAB_SUPPLIER', 'OPS_ELEC__TAB_PART', 'OPS_ELEC__TAB_BASE',
  'ACT__ELEC_RFQ_QUOTE', 'ACT__ELEC_SUPPLIER_MANAGE'
) AND deleted=0;
