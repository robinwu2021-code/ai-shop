-- 功能点：登记「这一页被哪个运行时开关门着」。
--
-- 起因：`/merchants?tab=chain`（链条画像）在菜单里是个正常项，点进去 404。
-- 不是缺陷 —— 整个进销存域被 `shop.inventory.enabled=false` 关着，关着时一个 Bean 都不装，
-- 控制器根本没注册。问题在于**菜单说了假话**：这些点标着 IMPLEMENTED。
--
-- 库里此前只有两种状态：NOT_IMPLEMENTED（后端整块没开工）与 IMPLEMENTED（源码里有这个端点）。
-- 缺的是第三种：**端点在这版构建里存在，但这个部署把它关着**。
--
-- ⚠️ 这一列存的是**源码事实**（哪个开关门着哪一页），跨部署稳定，所以进种子；
-- 而「现在开着没开着」是**部署状态**，每个环境不同，只能运行时算
-- （见 PermConfigServiceImpl.build）。两者混进一列的话，
-- 同一行在不同环境的正确值就不一样了。
--
-- 设计见 docs/technical/design/ops/TDD-ops-功能开关与菜单状态.md

ALTER TABLE sys_function_point
    ADD COLUMN gated_by VARCHAR(64) NULL COMMENT '运行时开关属性名；该属性不为 true 时这一项按未实现渲染';

-- 进销存域：关着时这 6 个页面的端点全都不注册
UPDATE sys_function_point SET gated_by='shop.inventory.enabled', updated_at=NOW()
 WHERE point_code IN (
   'OPS_MERCHANT__TAB_CHAIN',
   'OPS_INVENTORY',
   'OPS_INVENTORY__TAB_LEDGER',
   'OPS_INVENTORY__TAB_RECON',
   'OPS_INVENTORY__TAB_LINK_HEALTH',
   'OPS_INVENTORY__TAB_CREDENTIALS');

-- 定时任务：OpsJobController 由它门着（本地与线上通常开着，但口径要一致）
UPDATE sys_function_point SET gated_by='shop.job.enabled', updated_at=NOW()
 WHERE point_code = 'OPS_JOBS';
