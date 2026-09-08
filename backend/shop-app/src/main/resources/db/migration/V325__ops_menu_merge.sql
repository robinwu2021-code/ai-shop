-- 运营端主菜单合并：21 个 L1 → 13 个。
--
-- 方案见 docs/technical/design/ops/TDD-ops-主菜单合并.md。
--
-- 菜单不是 nav.ts 说了算：标签、分组、可见性都来自 sys_function_point / sys_role_point。
-- 只改 nav.ts 的话，本地 mock 下看得到，接上真实后端就没有。
--
-- ⚠️ 这份迁移里**没有一行动 point_code，也没有一行动 sys_role_point**。
-- point_code 是授权的锚：`sys_role_point` 存的就是它。改一个码，
-- 原本授权「认证标管理」的角色会静默变成授权别的功能点 —— 没有报错，且是放宽方向；
-- 运营自建角色同样受影响。所以合并只改「这个功能点属于哪个功能」，不改它是谁。
--
-- 前端那一侧同步做了两件事，缺一不可：
--   · lib/point-codes.ts 把 124 个 point_code 冻结成显式表（此前是从 section key 派生的，
--     section 一合就漂）。nav.test.ts 双向守着：NAV 里的叶子都要在表里，表里不许有陈行。
--   · nav-visibility.baseline.json 存了合并前 11 个角色各自看得见的 href，
--     合并后逐条比 —— 可见性回归在界面上看不出来，少一条菜单人只会以为「我没权限」。
--
-- 顺序不能换：先把功能点改到新归属（①），再删空掉的功能行（③）。
-- ① 功能点改归属（point_code 一个都不动 —— sys_role_point 指的就是它）
UPDATE sys_function_point SET function_code='OPS_MERCHANT', sort=150, updated_at=NOW() WHERE point_code='OPS_STORE';
UPDATE sys_function_point SET function_code='OPS_MERCHANT', sort=160, updated_at=NOW() WHERE point_code='OPS_STORE__TAB_TEMPLATE';
UPDATE sys_function_point SET function_code='OPS_MERCHANT', sort=170, updated_at=NOW() WHERE point_code='OPS_STORE__TAB_QRCODE';
UPDATE sys_function_point SET function_code='OPS_MERCHANT', sort=180, updated_at=NOW() WHERE point_code='OPS_STORE__TAB_EFFECT';
UPDATE sys_function_point SET function_code='OPS_PRODUCT', sort=150, updated_at=NOW() WHERE point_code='OPS_INVENTORY';
UPDATE sys_function_point SET function_code='OPS_PRODUCT', sort=160, updated_at=NOW() WHERE point_code='OPS_INVENTORY__TAB_LEDGER';
UPDATE sys_function_point SET function_code='OPS_PRODUCT', sort=170, updated_at=NOW() WHERE point_code='OPS_INVENTORY__TAB_RECON';
UPDATE sys_function_point SET function_code='OPS_PRODUCT', sort=180, updated_at=NOW() WHERE point_code='OPS_INVENTORY__TAB_LINK_HEALTH';
UPDATE sys_function_point SET function_code='OPS_PRODUCT', sort=190, updated_at=NOW() WHERE point_code='OPS_INVENTORY__TAB_CREDENTIALS';
UPDATE sys_function_point SET function_code='OPS_ORDER', sort=70, updated_at=NOW() WHERE point_code='OPS_FULFILLMENT';
UPDATE sys_function_point SET function_code='OPS_ORDER', sort=80, updated_at=NOW() WHERE point_code='OPS_FULFILLMENT__TAB_SORTING';
UPDATE sys_function_point SET function_code='OPS_ORDER', sort=90, updated_at=NOW() WHERE point_code='OPS_FULFILLMENT__TAB_REDEEM';
UPDATE sys_function_point SET function_code='OPS_ORDER', sort=100, updated_at=NOW() WHERE point_code='OPS_FULFILLMENT__TAB_OVERDUE';
UPDATE sys_function_point SET function_code='OPS_ORDER', sort=110, updated_at=NOW() WHERE point_code='OPS_FULFILLMENT__TAB_EXPRESS';
UPDATE sys_function_point SET function_code='OPS_ORDER', sort=120, updated_at=NOW() WHERE point_code='OPS_FULFILLMENT__TAB_FREIGHT';
UPDATE sys_function_point SET function_code='OPS_ORDER', sort=130, updated_at=NOW() WHERE point_code='OPS_FULFILLMENT__TAB_CARRIER';
UPDATE sys_function_point SET function_code='OPS_MARKETING', sort=80, updated_at=NOW() WHERE point_code='OPS_GROUP';
UPDATE sys_function_point SET function_code='OPS_MARKETING', sort=90, updated_at=NOW() WHERE point_code='OPS_GROUP__TAB_DEMANDS';
UPDATE sys_function_point SET function_code='OPS_MARKETING', sort=100, updated_at=NOW() WHERE point_code='OPS_GROUP__TAB_QUOTES';
UPDATE sys_function_point SET function_code='OPS_MARKETING', sort=110, updated_at=NOW() WHERE point_code='OPS_GROWTH';
UPDATE sys_function_point SET function_code='OPS_MARKETING', sort=120, updated_at=NOW() WHERE point_code='OPS_GROWTH__TAB_TRACES';
UPDATE sys_function_point SET function_code='OPS_MARKETING', sort=130, updated_at=NOW() WHERE point_code='OPS_GROWTH__TAB_FISSION';
UPDATE sys_function_point SET function_code='OPS_REVIEW', sort=40, updated_at=NOW() WHERE point_code='OPS_CONTENT';
UPDATE sys_function_point SET function_code='OPS_REVIEW', sort=50, updated_at=NOW() WHERE point_code='OPS_CONTENT__TAB_AUDIT';
UPDATE sys_function_point SET function_code='OPS_REVIEW', sort=60, updated_at=NOW() WHERE point_code='OPS_CONTENT__TAB_RANK';
UPDATE sys_function_point SET function_code='OPS_IAM', sort=50, updated_at=NOW() WHERE point_code='OPS_JOBS';
UPDATE sys_function_point SET function_code='OPS_IAM', sort=60, updated_at=NOW() WHERE point_code='OPS_SYSTEM';
UPDATE sys_function_point SET function_code='OPS_IAM', sort=70, updated_at=NOW() WHERE point_code='OPS_SYSTEM__TAB_MARKET';
UPDATE sys_function_point SET function_code='OPS_IAM', sort=80, updated_at=NOW() WHERE point_code='OPS_SYSTEM__TAB_FLAGS';
UPDATE sys_function_point SET function_code='OPS_IAM', sort=90, updated_at=NOW() WHERE point_code='OPS_SYSTEM__TAB_STORAGE';
UPDATE sys_function_point SET function_code='OPS_IAM', sort=100, updated_at=NOW() WHERE point_code='OPS_SYSTEM__TAB_INDUSTRY';
UPDATE sys_function_point SET function_code='OPS_IAM', sort=110, updated_at=NOW() WHERE point_code='OPS_SYSTEM__TAB_AUTHCODE';
UPDATE sys_function_point SET function_code='OPS_IAM', sort=120, updated_at=NOW() WHERE point_code='OPS_SYSTEM__TAB_SCOPE';
UPDATE sys_function_point SET function_code='OPS_SYSTEM', updated_at=NOW() WHERE point_code='ACT__INVENTORY_PROJECTION_REPAIR';
UPDATE sys_function_point SET function_code='OPS_SYSTEM', updated_at=NOW() WHERE point_code='ACT__GROUP_DEMAND_ASSIGN';
UPDATE sys_function_point SET function_code='OPS_SYSTEM', updated_at=NOW() WHERE point_code='ACT__INVENTORY_CREDENTIAL_GRANT';
UPDATE sys_function_point SET function_code='OPS_SYSTEM', updated_at=NOW() WHERE point_code='ACT__GROUP_CAMPAIGN_READ';
UPDATE sys_function_point SET function_code='OPS_SYSTEM', updated_at=NOW() WHERE point_code='ACT__GROWTH_ATTRIBUTION_UPDATE';
UPDATE sys_function_point SET function_code='OPS_SYSTEM', updated_at=NOW() WHERE point_code='ACT__GROWTH_FISSION_READ';

-- ② 13 个留下来的功能：改名与重排
UPDATE sys_function SET name='商家与门店', updated_at=NOW() WHERE function_code='OPS_MERCHANT';
UPDATE sys_function SET name='商品与库存', sort=30, updated_at=NOW() WHERE function_code='OPS_PRODUCT';
UPDATE sys_function SET name='交易与履约', sort=40, updated_at=NOW() WHERE function_code='OPS_ORDER';
UPDATE sys_function SET sort=50, updated_at=NOW() WHERE function_code='OPS_AFTERSALE';
UPDATE sys_function SET name='营销与增长', sort=60, updated_at=NOW() WHERE function_code='OPS_MARKETING';
UPDATE sys_function SET sort=70, updated_at=NOW() WHERE function_code='OPS_MEMBER';
UPDATE sys_function SET sort=80, updated_at=NOW() WHERE function_code='OPS_FINANCE';
UPDATE sys_function SET name='内容与口碑', sort=90, updated_at=NOW() WHERE function_code='OPS_REVIEW';
UPDATE sys_function SET sort=100, updated_at=NOW() WHERE function_code='OPS_MESSAGE';
UPDATE sys_function SET sort=110, updated_at=NOW() WHERE function_code='OPS_COMMUNITY';
UPDATE sys_function SET sort=120, updated_at=NOW() WHERE function_code='OPS_RISK';
UPDATE sys_function SET name='平台管理', sort=130, updated_at=NOW() WHERE function_code='OPS_IAM';

-- ③ 被合并掉的功能行（它们的功能点已在 ① 里改到新归属）
DELETE FROM sys_function WHERE function_code='OPS_STORE';
DELETE FROM sys_function WHERE function_code='OPS_INVENTORY';
DELETE FROM sys_function WHERE function_code='OPS_FULFILLMENT';
DELETE FROM sys_function WHERE function_code='OPS_GROUP';
DELETE FROM sys_function WHERE function_code='OPS_GROWTH';
DELETE FROM sys_function WHERE function_code='OPS_CONTENT';
DELETE FROM sys_function WHERE function_code='OPS_JOBS';
DELETE FROM sys_function WHERE function_code='OPS_SYSTEM';