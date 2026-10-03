-- 店铺码生成导出：从「后端没有」改成「已实现」，并把菜单真的放出来。
--
-- 与 V291（获客看板）是同一件事的另一半：V292 把 GET /ops/stores/qrcodes 与
-- 印刷量登记做出来了，但这一项在库里仍是 perm_code=NULL / NOT_IMPLEMENTED，
-- ops-web 侧的 ui 码 store:qrcode:export 也还是 UNIMPLEMENTED ——
-- **判所有人无权限，超管也不例外**。接口在、页面在、菜单里没有，而且不报错。
--
-- 后端没有 store:qrcode:export 这个码，也不新增：发店铺码的与审店招的是同一拨人
-- （BD 已持有 store:page:audit），端点判的就是它。新增权限码要连带动 ROLE_PERMS
-- 与权限种子，为一个「同一拨人做的同一类事」付那个代价不划算。
--
-- ⚠️ 撞号风险：并行会话同一目录，H2 测试不跑 Flyway，撞号只在下次真库启动才暴露。

UPDATE sys_function_point
   SET ui_perm_code = 'store:page:audit',
       perm_code = 'store:page:audit',
       backend_status = 'IMPLEMENTED',
       updated_at = NOW()
 WHERE point_code IN ('OPS_STORE__TAB_QRCODE', 'OPS_STORE_03');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'BD', 'OPS_STORE__TAB_QRCODE', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x
                    WHERE x.role_code = 'BD' AND x.point_code = 'OPS_STORE__TAB_QRCODE');
