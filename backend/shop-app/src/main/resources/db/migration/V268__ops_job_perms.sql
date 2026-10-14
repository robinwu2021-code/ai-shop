-- 定时任务的两个运营权限码：system:job:read / system:job:manage。
--
-- **只加两个功能点与四条授权，不重跑全量种子。**
-- gen-perm-seed.mjs 输出的是完整快照（357 条 role_point）——
-- 全量重灌会把运营在界面上做过的调整一并抹掉，而那种抹掉不报错。
-- 这里只取它为这两个码生成的那几行。
--
-- **读与管分成两个码**：一个任务出事时，先来看的往往是被它影响到的那条业务线的人，
-- 而他们不该有权把它停掉 —— 关掉关单任务，库存就从那一刻起不再释放。
--
-- 只授 TECH_OPS 与超管。group_name = '仅后端'：这两个码今天没有菜单项，
-- 页面落地后再由生成器把它们挪到「运行配置」下面。

INSERT INTO sys_function_point
    (point_code, function_code, name, group_name, href, ui_perm_code, perm_code,
     backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
VALUES
    ('ACT__SYSTEM_JOB_READ', 'OPS_SYSTEM', 'system:job:read', '仅后端', NULL, NULL,
     'system:job:read', 'IMPLEMENTED', 0, NULL, 'ACTION', 940, NOW(), NOW()),
    ('ACT__SYSTEM_JOB_MANAGE', 'OPS_SYSTEM', 'system:job:manage', '仅后端', NULL, NULL,
     'system:job:manage', 'IMPLEMENTED', 0, NULL, 'ACTION', 939, NOW(), NOW());

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
VALUES
    ('SUPER_ADMIN', 'ACT__SYSTEM_JOB_READ', 'OPS', NOW(), NOW()),
    ('SUPER_ADMIN', 'ACT__SYSTEM_JOB_MANAGE', 'OPS', NOW(), NOW()),
    ('TECH_OPS', 'ACT__SYSTEM_JOB_READ', 'OPS', NOW(), NOW()),
    ('TECH_OPS', 'ACT__SYSTEM_JOB_MANAGE', 'OPS', NOW(), NOW());
