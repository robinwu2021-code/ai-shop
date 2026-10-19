-- The prelaunch category-points UI/API belonged to the superseded points model.
-- Keep its historical schema/table untouched; remove only its obsolete ops grant/menu row.
DELETE FROM sys_role_point
WHERE point_code = 'OPS_PRODUCT__TAB_CATEGORY_POINTS';

DELETE FROM sys_function_point
WHERE point_code = 'OPS_PRODUCT__TAB_CATEGORY_POINTS'
   OR href = '/products?tab=category-points';
