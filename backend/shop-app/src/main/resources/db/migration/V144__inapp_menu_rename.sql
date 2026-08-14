-- 站内信菜单改名：「站内信模板与推送任务」→「站内信模板」。
--
-- **为什么改名**：推送任务（群发）后端一条端点都没有。ops-web 此前在调
-- `/ops/push-tasks` 三条 —— mock 下一切正常，接真后端时那一页当场 404。
-- 空壳已摘除（触达能力矩阵 §7.2.1 存着立项时要保留的三条规则），
-- 菜单名再留着「与推送任务」就是在目录上承诺一个不存在的功能。
--
-- **为什么不改 V140**：它已经落过库了，改已应用的迁移会让 Flyway 的
-- checksum 对不上，下一个启动后端的人直接起不来。已应用的迁移是只读的。
--
-- 菜单名以库里这一行为准（`sys_function_point.name`）——
-- 只改 ops-web/lib/nav.ts 的话，接真后端看到的还是旧名字。

-- 列名是 point_code，不是 code（与 V140 的 INSERT 一致）。
UPDATE sys_function_point
   SET name = '站内信模板', updated_at = NOW()
 WHERE point_code = 'OPS_MESSAGE__TAB_INAPP';
