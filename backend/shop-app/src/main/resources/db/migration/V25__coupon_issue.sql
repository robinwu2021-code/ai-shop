-- 券的**主动发放**留痕（P-7.1.2）。
--
-- ─────────────────────────────────────────────────────────────────────────────
-- 为什么需要这张表
-- ─────────────────────────────────────────────────────────────────────────────
-- 领券中心是「用户自己来领」，而这张表记的是「平台/客服主动发给谁」。
-- 两者的风险完全不同：前者由券模板的库存与预算自然封顶，
-- 后者是**有人按了按钮**，而按按钮的人里包含客服（矩阵 §2.3 补偿券）。
--
-- 客服发补偿券是日常动作，一天几十次；没有留痕的话，
-- 「这张 50 元券是谁发的、为什么发」在事后完全查不出来 ——
-- 而这正是内部套现最省事的路径。
--
-- ─────────────────────────────────────────────────────────────────────────────
-- target_desc 为什么是自由文本
-- ─────────────────────────────────────────────────────────────────────────────
-- 它记的是**当时那个人写下的理由**（「海棠（售后补偿）」「锦绣花园」），
-- 不是外键。事后审计要看的就是这句话，把它规范成社区号反而丢信息。
--
-- 精确到人的那种发放（SINGLE_USER）另有 user_no 一列，它才是外键语义。
CREATE TABLE IF NOT EXISTS mkt_coupon_issue
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    issue_no VARCHAR(64) NOT NULL,
    coupon_no VARCHAR(64) NOT NULL,
    coupon_name VARCHAR(128) NOT NULL COMMENT '券名快照：券改名或归档之后，这条记录仍要读得懂',
    target VARCHAR(16) NOT NULL COMMENT 'ALL / NEW_USER / COMMUNITY / SINGLE_USER',
    target_desc VARCHAR(255) DEFAULT NULL COMMENT '当时写下的定向说明，自由文本，审计要看的就是它',
    user_no VARCHAR(64) DEFAULT NULL COMMENT 'SINGLE_USER 时的收券人；其余目标类型为空',
    issued_count INT(11) NOT NULL DEFAULT 0,
    amount_minor BIGINT(20) NOT NULL DEFAULT 0 COMMENT '本次占用的预算（分）= 张数 × 面额',
    operator_no VARCHAR(64) DEFAULT NULL COMMENT '操作人。客服也持有发券权限，这一列不能省',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_coupon_issue_no UNIQUE (issue_no),
    KEY idx_issue_coupon (coupon_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='券的主动发放留痕（P-7.1.2）';
