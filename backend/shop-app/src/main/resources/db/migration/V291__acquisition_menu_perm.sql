-- 获客效果看板：从「后端没有」改成「已实现」，并把菜单真的放出来。
--
-- V290 把 GET /ops/stores/acquisition 做出来了，但这一项在库里仍是
-- perm_code=NULL / backend_status='NOT_IMPLEMENTED'，且 ops-web 侧的
-- ui 码 store:page:read 在 UI_PERM_MAP 里是 UNIMPLEMENTED —— **判所有人无权限，
-- 超管也不例外**。于是接口在、页面在、菜单里没有这一项，而且不报错。
--
-- ⚠️ **不能顺手把 store:page:read 整个放开**：同一个 ui 码还挂着「主页模板配置」
-- (/stores?tab=template)，那一块后端仍然没有。放开的结果是多出一个点进去 404 的死按钮 ——
-- 比藏起来更坏（docs/technical/archive/运营端死按钮实测清单.md）。
-- 所以只把**这一项**换成真码 store:page:audit：审店招的与看获客的本来就是同一拨人
-- （BD），后端端点判的也正是它，菜单可见性与接口鉴权因此完全对齐。
--
-- ⚠️ 撞号风险：并行会话同一目录，H2 测试不跑 Flyway，撞号只在下次真库启动才暴露。

UPDATE sys_function_point
   SET ui_perm_code = 'store:page:audit',
       perm_code = 'store:page:audit',
       backend_status = 'IMPLEMENTED',
       updated_at = NOW()
 WHERE point_code = 'OPS_STORE__TAB_EFFECT';

-- 老基线里还有一条同 href 的 OPS_STORE_04（改名前的点码），一并跟上，
-- 免得两条记录对同一个菜单项给出相反的口径。
UPDATE sys_function_point
   SET ui_perm_code = 'store:page:audit',
       perm_code = 'store:page:audit',
       backend_status = 'IMPLEMENTED',
       updated_at = NOW()
 WHERE point_code = 'OPS_STORE_04';

-- BD 本来就持有 store:page:audit（店招公告审核靠的就是它）。
-- 漏掉这一行的表现是静默降级：权限码够，但菜单里没有这一项。
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'BD', 'OPS_STORE__TAB_EFFECT', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x
                    WHERE x.role_code = 'BD' AND x.point_code = 'OPS_STORE__TAB_EFFECT');
