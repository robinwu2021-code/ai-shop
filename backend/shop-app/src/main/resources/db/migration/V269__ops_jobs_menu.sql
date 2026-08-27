-- 运营端「定时任务」菜单。
--
-- **独立成一个入口，不做成系统配置的一个 tab。**
-- 系统配置那七个 tab 回答的是「平台怎么配」，而这一页回答的是「后台此刻在不在跑」——
-- 一个是配置，一个是运行时监控。塞进那七个 tab 里，出事时没人会想到去那儿翻。
--
-- **运营端菜单不是 nav.ts 说了算**：标签、分组、可见性都来自
-- sys_function_point / sys_role_point。只改 nav.ts 的话本地 mock 下看得见，
-- 接上真实后端就没有。
--
-- ⚠️ **sort 插空位，既有行一行不动。** 生成器按 nav.ts 顺序全量重排会给 200，
-- 而那可能是别人的位置。这里取 195，落在系统配置（200）之前 ——
-- 它固定在 Rail 底部，与系统配置相邻是有意的：都是「平台自己的事」。
--
-- ui_perm_code 与 perm_code 都用 system:job:read：叶子的 perm 决定能不能**看见入口**，
-- 而「能看任务跑没跑」的人比「能停任务」的人多得多。
-- 开关/改频率/立即执行由页面内部按 system:job:manage 显隐（V268 已建那个码的点位）。

INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at)
VALUES ('OPS_JOBS', '定时任务', 'OPS', 'Timer', '/jobs', 195, 1, NOW(), NOW());

INSERT INTO sys_function_point
    (point_code, function_code, name, group_name, href, ui_perm_code, perm_code,
     backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
VALUES ('OPS_JOBS', 'OPS_JOBS', '任务与执行日志', '运行配置', '/jobs',
        'system:job:read', 'system:job:read', 'IMPLEMENTED', 1, 'P-17.1', 'MENU', 10, NOW(), NOW());

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
VALUES ('SUPER_ADMIN', 'OPS_JOBS', 'OPS', NOW(), NOW()),
       ('TECH_OPS', 'OPS_JOBS', 'OPS', NOW(), NOW());
