-- 站内信收件人泛化（TDD-通知与消息推送 §二期）。
-- msg_message 此前只有 user_no —— B 端员工、平台运营在这套消息体系里根本不存在，
-- 新订单/售后/工单这些「必须有人看见」的事件没有任何落点。
-- 收件人 = (receiver_type, receiver_no)：USER=消费者 userNo；STAFF=商家侧 userNo
-- （B 端与 C 端共用账号池，同一个人的两个收件箱靠 type 分开）；OPS=运营 staffNo。
--
-- ⚠️ 注意上一行 `--` 后面那个空格：MySQL/MariaDB 的行注释**要求 `--` 后跟空白**，
-- 写成 `--（B 端…` 不是注释，会被当成 SQL 解析并报语法错。
-- H2（测试库）对此宽松，所以这个错**测试全绿也照样漏**，只有真库能发现。
ALTER TABLE msg_message
    ADD COLUMN receiver_type VARCHAR(16) NOT NULL DEFAULT 'USER' COMMENT '收件人类型 USER/STAFF/OPS' AFTER message_no;

-- 存量行全是 C 端消息，DEFAULT 'USER' 即正确回填；列名跟着语义走
ALTER TABLE msg_message RENAME COLUMN user_no TO receiver_no;

-- 未读角标是三端最高频的查询，索引按查询形状给
ALTER TABLE msg_message DROP INDEX idx_msg_user_read;
ALTER TABLE msg_message ADD INDEX idx_msg_receiver_read (receiver_type, receiver_no, is_read, at);
