-- 审计日志详情增强：IP / 操作端 / 高危标记 / 结构化前后对比。
--
-- 现状：sys_audit_log 只有一句拼接文本 detail，运营看不出谁在哪台设备、
-- 改之前是什么值、这条算不算高危 —— 三端角色权限对齐 review 时被明确要求补上。
--
-- before_json/after_json 允许为空：多数调用点（发券、切换活动开关等）
-- 旧值本身没有复查意义，这次只给「员工与权限」域接了结构化数据，
-- 其余保持纯文本 detail，前端按有没有值显示，不伪造。
ALTER TABLE sys_audit_log
    ADD COLUMN IF NOT EXISTS ip VARCHAR(64) DEFAULT NULL
        COMMENT '操作者 IP，取 X-Forwarded-For 首个值，取不到退 remote addr',
    ADD COLUMN IF NOT EXISTS client_type VARCHAR(16) DEFAULT NULL
        COMMENT '操作端：WEB_OPS / APP / UNKNOWN，从 User-Agent 粗判',
    ADD COLUMN IF NOT EXISTS critical TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '高危操作标记，供运营审计列表筛选',
    ADD COLUMN IF NOT EXISTS before_json TEXT DEFAULT NULL
        COMMENT '变更前结构化快照（JSON），没有则为空',
    ADD COLUMN IF NOT EXISTS after_json TEXT DEFAULT NULL
        COMMENT '变更后结构化快照（JSON），没有则为空';
