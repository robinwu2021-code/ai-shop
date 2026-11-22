-- 运营端「电子发票」菜单点归入 main 合并后的分组（V657 / V658 把「应付与发票」改成了「应付发票」）。
-- V10 已在部分环境执行过，不改它的内容；这里只把分组与排序对齐：排在「开票申请」(100) 之后、「发票个税」(110) 之前。
UPDATE sys_function_point SET function_code='OPS_FINANCE',name='电子发票',group_name='应付发票',sort=105,updated_at=NOW() WHERE point_code='OPS_FINANCE__E_INVOICES' AND point_type='MENU';
