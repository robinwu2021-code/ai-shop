-- 「区划补录」tab 改名「区划维护」。
--
-- 聚落模型定稿后，商家不再直接往 sys_region 写任何东西（村的提报并入社区提报），
-- 这个 tab 的裁决语义随之消失，改作运营人工维护区划（新增 / 停用 / 改名 ——
-- enabled 那一列此前上线两年没有任何写入口）。
--
-- point_code 不动（sys_role_point 按它关联，动了授权就错位），只改展示名。
-- ⚠ 幂等插入只保证不重复建、不保证值是对的（V151 的教训）—— 改名必须走 UPDATE。
UPDATE sys_function_point
   SET name = '区划维护', updated_at = NOW()
 WHERE point_code = 'OPS_COMMUNITY__TAB_REGIONS';
