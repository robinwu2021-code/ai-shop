-- 预置角色补授（TDD-B端门店功能矩阵与方案准入 §4-1）。只改平台预置角色（entity_no='*'），不动商家自定义角色；
-- 与 BizPerms.ROLE_PERMS 由 BizRoleSeedTest 对账。
--
-- 1. 预约：店长看与管、店员只看。此前 biz:booking:* 没有预置授予，服务业门店的预约默认只有店主能用。
UPDATE mch_role SET perms=JSON_ARRAY_APPEND(perms,'$','biz:booking:view'),updated_at=CURRENT_TIMESTAMP,version=version+1 WHERE entity_no='*' AND builtin=1 AND role_code='MANAGER' AND deleted=0 AND JSON_CONTAINS(perms,JSON_QUOTE('biz:booking:view'))=0;
UPDATE mch_role SET perms=JSON_ARRAY_APPEND(perms,'$','biz:booking:manage'),updated_at=CURRENT_TIMESTAMP,version=version+1 WHERE entity_no='*' AND builtin=1 AND role_code='MANAGER' AND deleted=0 AND JSON_CONTAINS(perms,JSON_QUOTE('biz:booking:manage'))=0;
UPDATE mch_role SET perms=JSON_ARRAY_APPEND(perms,'$','biz:booking:view'),updated_at=CURRENT_TIMESTAMP,version=version+1 WHERE entity_no='*' AND builtin=1 AND role_code='CLERK' AND deleted=0 AND JSON_CONTAINS(perms,JSON_QUOTE('biz:booking:view'))=0;
--
-- 2. 收款（补漏）：BizPerms 早已写明店长、店员默认能看收款记录（mch:pay:order:view，TDD-B端收款订单与商城接口对齐 P1）、
--    店长默认能用收银台（mch:pay:collect，TDD-门店收银台与付款链接 L7），但一直没有迁移写进库 ——
--    判权读的是库，于是店长店员实际看不到「收款」Tab。这里补齐，与 Java 表一致。
UPDATE mch_role SET perms=JSON_ARRAY_APPEND(perms,'$','mch:pay:order:view'),updated_at=CURRENT_TIMESTAMP,version=version+1 WHERE entity_no='*' AND builtin=1 AND role_code='MANAGER' AND deleted=0 AND JSON_CONTAINS(perms,JSON_QUOTE('mch:pay:order:view'))=0;
UPDATE mch_role SET perms=JSON_ARRAY_APPEND(perms,'$','mch:pay:collect'),updated_at=CURRENT_TIMESTAMP,version=version+1 WHERE entity_no='*' AND builtin=1 AND role_code='MANAGER' AND deleted=0 AND JSON_CONTAINS(perms,JSON_QUOTE('mch:pay:collect'))=0;
UPDATE mch_role SET perms=JSON_ARRAY_APPEND(perms,'$','mch:pay:order:view'),updated_at=CURRENT_TIMESTAMP,version=version+1 WHERE entity_no='*' AND builtin=1 AND role_code='CLERK' AND deleted=0 AND JSON_CONTAINS(perms,JSON_QUOTE('mch:pay:order:view'))=0;
