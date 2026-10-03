-- 统一重命名 msg_* → notify_*（设计：触达推送中台-模块抽象 · N7；执行 checklist 见同目录）。
--
-- **前向 RENAME，不编辑任何已应用迁移** —— 与 V152 那类「改已应用迁移内容」不同：
-- 旧迁移一个字不改，Flyway 校验和不变，无需 repair。ddl.mjs 会重放 `ALTER…RENAME TO`，
-- 守卫看到的是重命名后的名字。
--
-- 用 `ALTER TABLE … RENAME TO`（不是 `RENAME TABLE`）：MariaDB 与 ddl.mjs 都认这一种。
-- sys_notify_log 刻意不改：sys_ 是基础设施记录表的约定（同 sys_outbox/sys_job_run），
-- 与业务表 msg_→notify_ 不是一回事。
ALTER TABLE msg_message       RENAME TO notify_message;
ALTER TABLE msg_template      RENAME TO notify_template;
ALTER TABLE msg_scene_channel RENAME TO notify_scene_channel;
ALTER TABLE msg_push_token    RENAME TO notify_push_token;
ALTER TABLE msg_subscribe     RENAME TO notify_subscribe;
ALTER TABLE msg_ticket        RENAME TO notify_ticket;
