-- Keep original point codes, role grants and permission codes; both entry grants lead to one application page.
UPDATE sys_function_point SET name='入驻申请',href='/merchants',group_name='入驻',sort=10,updated_at=NOW()
WHERE point_code IN ('OPS_MERCHANT','OPS_MERCHANT__TAB_ON_BEHALF') AND point_type='MENU';
UPDATE sys_function_point SET name='自营建档',group_name='入驻',sort=20,updated_at=NOW()
WHERE point_code='OPS_MERCHANT__TAB_SELF_OPERATED' AND point_type='MENU';
UPDATE sys_function_point SET name='支付开通',group_name='入驻',sort=30,updated_at=NOW()
WHERE point_code='OPS_MERCHANT__TAB_ONBOARDING' AND point_type='MENU';
