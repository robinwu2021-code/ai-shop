-- 运营端「存储空间治理」的菜单叶子与页面内操作点
-- （TDD-图片存储与空间回收 §L3-11）。
--
-- **生成方式**：与 V99 / V103 / V140 同一套写法 —— `ops-web/scripts/gen-perm-seed.mjs`
-- 是全量重生成器（它重造 V62 的整份数据），不适合直接落成增量迁移。
-- 这里跑一遍生成器，从输出里**逐字取出**本次新增的行，其余一行不动。
--
-- ⚠️ **sort 没有照抄生成器**。生成器按 nav.ts 的新顺序重排，给存储空间治理排了 40，
-- 于是它后面的「行业与小微白名单 / 经营授权码 / 经营范围开关」从 40/50/60 变成 50/60/70
-- —— 那正是 V140 记过的「插一个叶子让其后全部右移」的老毛病。
-- 这里取 **31**，插在「开关与灰度」(30) 之后、「行业与小微白名单」(40) 之前，既有行一个不动。
--
-- ⚠️ 这条一度是 ui_ready = 0：后端端点先就绪、页面还没做，解锁就是点进去 404
-- （nav.test.ts 的「已解锁的 ?tab= 叶子，页面必须真的认这个 tab」正是拦这个的）。
-- 页面在同一批改动里做完了，所以直接改成 1 —— 同一次提交里先落 0 再补一条置 1，
-- 只会让读迁移的人以为中间发生过什么。
--
-- ⚠️ 初稿把它设计成子路径 `/system/storage`，被 nav.test.ts 的
-- 「叶子的 href 归属本 section」拦下 —— 这个项目的约定里没有子路径叶子，
-- 叶子要么是 section 自己的 ?tab=，要么是跨 section 深链。
-- 顺带发现子路径会让生成器**静默撞码**（派生出的就是 `OPS_SYSTEM`，与「外观与规则文案」相同），
-- 但既然 nav 守卫在更前面一层就禁止了子路径，那道防御没有任何测试能触发，故未加。
--
-- 可重入形式用 **INSERT IGNORE**（与 V72/V74 灌权限点同一种写法），不用
-- `INSERT … SELECT … WHERE NOT EXISTS`。两者都能重入，但后者会被
-- `gen-test-schema.py` 当成 `INSERT … SELECT` 丢掉 —— 于是这些行进不了 H2 测试库，
-- 表现是 OpsPermConfigFlowTest 报「角色 TECH_OPS 的权限码：库与 Perms.java 不一致」，
-- 而迁移本身看着完全正确。初版就是这么写的，被那条测试逮住。

-- 1) 菜单叶子。perm 用 read 而不是 purge：叶子的 perm 决定**能不能看见这个入口**，
--    而「能看占用」的人比「能删」的人多。删的权限由页面内部按 system:media:purge 判，
--    没有它就隐藏勾选框与批量操作条。
INSERT IGNORE INTO sys_function_point
    (point_code, function_code, name, group_name, href, ui_perm_code, perm_code,
     backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
VALUES ('OPS_SYSTEM__TAB_STORAGE', 'OPS_SYSTEM', '存储空间治理', '运行配置', '/system?tab=storage',
        'system:media:read', 'system:media:read', 'IMPLEMENTED', 1, 'P-17.1', 'MENU', 31, NOW(), NOW());

-- 2) 页面内操作：发起回收。它是不可逆动作，单独成点才能只发给该发的人
INSERT IGNORE INTO sys_function_point
    (point_code, function_code, name, group_name, href, ui_perm_code, perm_code,
     backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
VALUES ('ACT__SYSTEM_MEDIA_PURGE', 'OPS_SYSTEM', 'system:media:purge', '页面内操作', NULL,
        'system:media:purge', 'system:media:purge', 'IMPLEMENTED', 1, NULL, 'ACTION', 910, NOW(), NOW());

-- 3) 角色授权：超管与技术运维。
--    回收给技术运维而不是更广的范围 —— 它删的是磁盘文件，判据是「这张图还有没有人引用」，
--    这是工程问题不是业务问题。业务岗位需要的是看得见占用，不是握着删除按钮。
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
VALUES ('SUPER_ADMIN', 'OPS_SYSTEM__TAB_STORAGE', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
VALUES ('SUPER_ADMIN', 'ACT__SYSTEM_MEDIA_PURGE', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
VALUES ('TECH_OPS', 'OPS_SYSTEM__TAB_STORAGE', 'OPS', NOW(), NOW());
INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
VALUES ('TECH_OPS', 'ACT__SYSTEM_MEDIA_PURGE', 'OPS', NOW(), NOW());
