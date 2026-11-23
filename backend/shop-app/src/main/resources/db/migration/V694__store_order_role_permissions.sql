-- 门店零售单权限码追加到内置角色（不改写已应用的角色种子，不碰商家自建角色）。与 BizPerms.ROLE_PERMS 一致：
-- 店长建单 / 改价 / 自订行选零税率或免税，店员只建单（TDD-统一订单与开票资料 §2）。
UPDATE mch_role SET perms=JSON_ARRAY_APPEND(perms,'$','biz:order:create'),updated_at=CURRENT_TIMESTAMP,version=version+1 WHERE entity_no='*' AND builtin=1 AND role_code='MANAGER' AND deleted=0 AND JSON_CONTAINS(perms,JSON_QUOTE('biz:order:create'))=0;
UPDATE mch_role SET perms=JSON_ARRAY_APPEND(perms,'$','biz:order:reprice'),updated_at=CURRENT_TIMESTAMP,version=version+1 WHERE entity_no='*' AND builtin=1 AND role_code='MANAGER' AND deleted=0 AND JSON_CONTAINS(perms,JSON_QUOTE('biz:order:reprice'))=0;
UPDATE mch_role SET perms=JSON_ARRAY_APPEND(perms,'$','biz:invoice:tax-override'),updated_at=CURRENT_TIMESTAMP,version=version+1 WHERE entity_no='*' AND builtin=1 AND role_code='MANAGER' AND deleted=0 AND JSON_CONTAINS(perms,JSON_QUOTE('biz:invoice:tax-override'))=0;
UPDATE mch_role SET perms=JSON_ARRAY_APPEND(perms,'$','biz:order:create'),updated_at=CURRENT_TIMESTAMP,version=version+1 WHERE entity_no='*' AND builtin=1 AND role_code='CLERK' AND deleted=0 AND JSON_CONTAINS(perms,JSON_QUOTE('biz:order:create'))=0;
