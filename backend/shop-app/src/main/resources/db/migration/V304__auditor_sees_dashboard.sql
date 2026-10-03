-- 审核员看得到经营看板。
--
-- 起因：看板上新增了「待审商品」这张待办卡（194 件、最早等了 14 天），
-- 而它要找的正是审核员 —— 结果 `AUDITOR` 打开 /ops/dashboard/kpi 是 Access Denied。
-- 这一格是加给他看的，他偏偏是唯一看不见的那个人。
--
-- 症状极其温和：审核员登录后侧边栏没有「经营看板」这一项，
-- 而他不会知道自己少了什么 —— 少一个菜单看起来就像这个系统本来就没有这个功能。
--
-- 授的是 `ACT__DASHBOARD_OVERVIEW_READ`（V74 建的仅后端动作点，
-- 承载 dashboard:overview:read）。同时 Perms.java 的 ROLE_PERMS 也要跟上：
-- **OpsPermConfigFlowTest 逐条比对库与代码两侧，少一头就红**，
-- 而少的那一头决定了症状是「点不进去」还是「看不见」。
--
-- ⚠️ 只加 AUDITOR。`ANALYST` 同样没有这个码（它名下只有 community:read，
-- 也就是说「数据分析」这个角色看不到数据看板）—— 那是另一个缺口，
-- 且给一个角色开平台 GMV 的可见性该由人来定，不在这条迁移的范围里。

INSERT IGNORE INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
VALUES ('AUDITOR', 'ACT__DASHBOARD_OVERVIEW_READ', 'OPS', NOW(), NOW());
