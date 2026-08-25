-- 补齐九条 ops-web 调用后端一直没有的接口所需的三处 DDL。
--
-- 1. msg_faq      — 运营配置常见问题（原来是硬编码 List.of(...)，无法修改）
-- 2. archived_at  — cmt_community 加归档列，补进 ArchiveService.Kind.COMMUNITY
-- 3. assigned_to  — notify_ticket 加指派列（工单 assign / proxy-actions 端点）
--
-- 工单的「代客操作」(proxy-actions) 只写审计日志，不新增列。

-- ─── 1. FAQ ──────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS msg_faq
(
    id          BIGINT(20)   NOT NULL AUTO_INCREMENT,
    faq_no      VARCHAR(64)  NOT NULL,
    question    VARCHAR(256) NOT NULL,
    answer      TEXT         NOT NULL,
    category    VARCHAR(64)  NOT NULL DEFAULT '',
    sort        INT(11)      NOT NULL DEFAULT 0 COMMENT '展示顺序，小者靠前',
    published   TINYINT(4)   NOT NULL DEFAULT 0 COMMENT '0=草稿 1=已上架；C 端只看已上架',
    tenant_no   VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at  DATETIME     NOT NULL,
    created_by  VARCHAR(64)  DEFAULT NULL,
    updated_at  DATETIME     NOT NULL,
    updated_by  VARCHAR(64)  DEFAULT NULL,
    version     BIGINT(20)   NOT NULL DEFAULT 0,
    deleted     TINYINT(4)   NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_faq_no (faq_no)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_uca1400_ai_ci
  COMMENT = '常见问题（帮助中心）';

-- 原来硬编码的四条作为默认数据迁进来；published=1 = 立即生效
INSERT INTO msg_faq (faq_no, question, answer, category, sort, published,
                     tenant_no, created_at, updated_at)
VALUES ('FAQ-0001', '怎么取货？', '订单支付后会生成取货码，到自提点报码或出示二维码即可。', '履约', 1, 1, 'MAIN', NOW(), NOW()),
       ('FAQ-0002', '能退款吗？', '未取货前可申请仅退款；小额订单支持极速退，立即到账。', '售后', 2, 1, 'MAIN', NOW(), NOW()),
       ('FAQ-0003', '为什么我的券用不了？', '券有使用门槛与有效期，结算页会显示不可用原因。', '优惠', 3, 1, 'MAIN', NOW(), NOW()),
       ('FAQ-0004', '到货时间怎么算？', '自提点页面会写明当日到货时间，一般为每晚 7 点前。', '履约', 4, 1, 'MAIN', NOW(), NOW());

-- ─── 2. 社区归档 ──────────────────────────────────────────────────────────────
ALTER TABLE cmt_community
    ADD COLUMN IF NOT EXISTS archived_at DATETIME DEFAULT NULL
        COMMENT '归档时间。软删除标记，有值即从默认列表消失。与 status 正交';

-- ─── 3. 工单指派 ──────────────────────────────────────────────────────────────
ALTER TABLE notify_ticket
    ADD COLUMN IF NOT EXISTS assigned_to  VARCHAR(64)  DEFAULT NULL COMMENT '指派的客服 staffNo',
    ADD COLUMN IF NOT EXISTS assigned_at  BIGINT(20)   DEFAULT NULL COMMENT '指派时间（ms）';
