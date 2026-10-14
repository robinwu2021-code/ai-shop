-- 员工与授权的操作日志（B-11.10.3）。
--
-- ─────────────────────────────────────────────────────────────────────────────
-- 为什么要它
-- ─────────────────────────────────────────────────────────────────────────────
-- **授权变更是权限扩散的唯一入口，却是全链路里唯一不留痕的一环。**
-- 加人、停用、给角色、撤角色 —— 这四个动作决定了「谁能碰这家店的什么」，
-- 而在这张表之前，它们做完就没了：三个月后问「谁把张三提成了店长」，
-- 库里只有一行当前状态，答不出是谁、什么时候、原来是什么。
--
-- 其余动作（改商品、发货、核销）都有各自的业务单据兜底，唯独授权没有。
--
-- ─────────────────────────────────────────────────────────────────────────────
-- 为什么不复用 sys_audit_log
-- ─────────────────────────────────────────────────────────────────────────────
-- 那张表的 staff_no / staff_name 语义是**平台运营员工**，读它的是运营端的审计列表。
-- 把商家的动作塞进去，一是混进运营的列表里，二是**商家自己看不到**——
-- 而 B-11.10.3 要的正是「商家能查自己店里的授权变更」。
--
-- 同名不同义是本项目反复在治的病（B 端的 CS 与运营端的 Role.CS 就为此在注释里点过名）。
-- 一张表被两个 realm 共用，迟早有人写出「按 staff_no 查运营员工姓名」的联表。
--
-- ─────────────────────────────────────────────────────────────────────────────
-- 为什么不注册数据域（DataScopeRegistration）
-- ─────────────────────────────────────────────────────────────────────────────
-- 与 mch_account / mch_store_role 同一处理：员工域的读写一律
-- DataScopeContext.executeWithoutScope + 显式按 entity_no 过滤，
-- 因为「当前门店」这个维度对员工表没有意义（一个员工跨多家店）。
-- ⚠️ 将来加读端点时，**必须显式带 entity_no 条件** —— 未注册的表不会被自动收窄。
--
-- ─────────────────────────────────────────────────────────────────────────────
-- 为什么记 role 与 store_no 两列，而不是只塞进 detail
-- ─────────────────────────────────────────────────────────────────────────────
-- 「这家店的授权都动过谁」是最常问的一句，塞进文本就只能 LIKE。
-- detail 留给人读，列留给查。
CREATE TABLE IF NOT EXISTS mch_staff_log
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    log_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL COMMENT '商家主体号。查询一律按它过滤 —— 这张表没有注册数据域',
    actor_account_no VARCHAR(64) DEFAULT NULL COMMENT '谁做的（mch_account_no）。取不到当前身份时为 NULL，不编一个值',
    target_account_no VARCHAR(64) NOT NULL COMMENT '被操作的员工',
    action VARCHAR(24) NOT NULL COMMENT 'STAFF_ADD 加员工 / STAFF_ENABLE 启用 / STAFF_DISABLE 停用 / ROLE_GRANT 授予角色 / ROLE_REVOKE 撤销角色',
    store_no VARCHAR(64) DEFAULT NULL COMMENT '涉及的门店。加人与启停不涉及门店，为 NULL',
    role VARCHAR(16) DEFAULT NULL COMMENT '涉及的角色，取值域同 mch_store_role.role。加人与启停为 NULL',
    detail VARCHAR(512) DEFAULT NULL COMMENT '人能读的一句话，直接展示',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_staff_log_no (log_no),
    KEY idx_staff_log_entity (entity_no, id),
    KEY idx_staff_log_target (target_account_no, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='员工与授权的操作日志：谁在什么时候把谁的角色改成了什么';
