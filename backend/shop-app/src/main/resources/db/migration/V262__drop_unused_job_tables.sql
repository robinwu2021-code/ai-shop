-- 废掉平台库里的 sys_job_def / sys_job_log。
--
-- 它们是 V260 建的，当时的方案把定时任务的三张表放在平台库。
-- 后来改成**独立库**（ai_shop_job，见 db/job/V1__job_baseline.sql），这两张就没有读写方了。
--
-- 为什么是新写一条 DROP，而不是把 V260 改掉：
--   V260 已经提交，本地与 CI 都跑过它。改动一条已应用的迁移会让 checksum 对不上，
--   跑过的人下次启动直接失败。生产没跑过（Flyway 停在 V255，已实测确认），
--   但"生产没跑过"不构成改历史的理由 —— 本地跑过的人也是人。
--
-- sys_job_run **不在这里删**：它今天还有 JobSupport 在写。
-- 等 14 个任务都改成 JobHandler、worker 观察期结束之后再单独一条（交付计划 J4 之后）。
-- 提前删掉，现有代码会在下一次任务触发时炸，而那时候没人会想到是这条迁移。

DROP TABLE IF EXISTS sys_job_def;
DROP TABLE IF EXISTS sys_job_log;
