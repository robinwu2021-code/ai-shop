-- 进销存换成独立的权限命名空间 inventory:*，并把菜单翻亮。
--
-- **为什么要独立命名空间。** 此前 7 个 /ops/inventory/** 端点整体寄在别人名下：
-- 只读那几个挂 product:sku:read，签发/吊销凭证挂 merchant:mode:update。
-- 两处都是授权面比意图大：
--
--   · 授予商品运营看 SKU 的权，就一并授出了全平台库存台账与对账；
--   · 授予商家运营改经营模式的权，就一并授出了「给外部系统开 API 钥匙」的权力。
--     BD 恰好持有它 —— 于是 BD 事实上能发钥匙，却看不见那个页面
--     （页面按 product:sku:read 可见，他没有）。V271 的注释记下了这处错位，
--     并写明「补它是权限模型的改动，不该顺手塞进那条迁移里」。**这条就是那笔。**
--
-- 还有一处只有独立命名空间能修好：ops-web/lib/nav.ts 的 module 字段是**权限码前缀**，
-- canModule 按它过滤整段。进销存那一段此前只能填 product —— 填 inventory 的话
-- canModule 会走「这个模块不受权限约束」那条分支返回 true，**整个 section 对所有人可见**，
-- 靠叶子逐条兜底。有了 inventory: 开头的码，那一层空掉的闸门才补得回来。
--
-- ⚠️ **这是一次行为变化，不是纯重命名**：BD 从此发不了凭证（只有超管能）。
-- 那个能力它本来就不是被「授予」的，是通过 merchant:mode:update 顺带得到的。
-- 要恢复给 BD，在运营端的角色配置里勾 ACT__INVENTORY_CREDENTIAL_GRANT 即可 ——
-- **那正是这次改动的意义：从「顺带得到」变成「显式授予」。**

-- ── ① 三页只读：翻亮 + 换码 ──────────────────────────────────────────────
--
-- V263 把它们置成 NOT_IMPLEMENTED（菜单灰显不可点），理由是端点挂着
-- @ConditionalOnProperty(shop.inventory.enabled)。**那个开关在生产是 true**
-- （2026-08-29 实测 shop-app.env 里 SHOP_INVENTORY_ENABLED=true），
-- 测试库的 application-h2db.yml 里也是 true。所以 NOT_IMPLEMENTED 今天是错的：
-- 功能开着，而运营端菜单还灰着，没有人点得进去。
--
-- V267 写着「翻亮那天必须连 perm_code 一起还原，否则三个端点在权限矩阵里就成了无码的」——
-- 这里照做，只是还原成新命名空间的码。
UPDATE sys_function_point
SET backend_status = 'IMPLEMENTED',
    perm_code      = 'inventory:stock:read',
    ui_perm_code   = 'inventory:stock:read',
    updated_at     = NOW()
WHERE point_code IN ('OPS_INVENTORY', 'OPS_INVENTORY__TAB_LEDGER', 'OPS_INVENTORY__TAB_RECON');

-- ── ② 开放对接那一页：可见性判「读」 ─────────────────────────────────────
--
-- 只读视图给审计看：有哪些钥匙发出去过、谁在用、哪些已吊销 ——
-- 密钥泄露要查时第一个要打开的就是这一页。
UPDATE sys_function_point
SET perm_code    = 'inventory:credential:read',
    ui_perm_code = 'inventory:credential:read',
    updated_at   = NOW()
WHERE point_code = 'OPS_INVENTORY__TAB_CREDENTIALS';

-- ── ③ 签发与吊销：页面内动作，单独一个点 ─────────────────────────────────
--
-- 写成 ACTION 而不是 MENU：它没有自己的 href，是 credentials-tab 上的两个按钮。
-- V62 的表注释写明两类都要收 —— 只收菜单的话，按钮用的码在库里没有落点，
-- 角色映射就与代码对不上。
INSERT INTO sys_function_point
    (point_code, function_code, name, group_name, href, ui_perm_code, perm_code,
     backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'ACT__INVENTORY_CREDENTIAL_GRANT', 'OPS_INVENTORY', 'inventory:credential:grant',
       '页面内操作', NULL, 'inventory:credential:grant', 'inventory:credential:grant',
       'IMPLEMENTED', 1, NULL, 'ACTION', 901, NOW(), NOW()
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x
                    WHERE x.point_code = 'ACT__INVENTORY_CREDENTIAL_GRANT');

-- 超管：通配角色，但库里仍逐点关联（sys_role.wildcard 只是短路，配置表要能审）。
-- **只给超管** —— 见文件头那段：发钥匙从「顺带得到」变成「显式授予」，
-- 要给谁，在运营端勾。
INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'ACT__INVENTORY_CREDENTIAL_GRANT', 'OPS', NOW(), NOW()
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x
                    WHERE x.role_code = 'SUPER_ADMIN'
                      AND x.point_code = 'ACT__INVENTORY_CREDENTIAL_GRANT');

-- ── 四页的角色关联一行不动 ───────────────────────────────────────────────
-- V261 已给 SUPER_ADMIN / GOODS_OPS 关联了前三页，V271 给
-- SUPER_ADMIN / GOODS_OPS / AUDITOR 关联了开放对接页。point_code 没变，
-- 所以这些关联原样有效：换 perm_code 之后，他们自动改持 inventory:* 的对应码。
-- **可达面因此不变** —— 商品运营不会在上线那一刻静默失去进销存。
