-- 用户要求保存实际发送正文；历史记录不回填、不编造。
ALTER TABLE sys_notify_log ADD COLUMN content TEXT NULL COMMENT '实际提交的发送正文，历史或不可得时为空' AFTER template_no;
