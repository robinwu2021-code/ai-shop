-- Preserve all three existing authorization points and role grants; unify their destination.
UPDATE sys_function_point SET name='入驻管理',href='/merchants',group_name='入驻',sort=10,updated_at=NOW()
WHERE point_code IN ('OPS_MERCHANT','OPS_MERCHANT__TAB_ON_BEHALF','OPS_MERCHANT__TAB_SELF_OPERATED') AND point_type='MENU';
