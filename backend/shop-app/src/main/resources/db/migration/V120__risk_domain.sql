-- 风控域（P-16.2）。见 docs/technical/design/TDD-运营端风控域.md。
--
-- **三类风险同表用 type 区分**，不拆三张表：拆了之后「这个主体同时命中几类」
-- 就看不出来了 —— 而那恰恰是最该优先处理的一批。
--
-- 业务键叫 `risk_event_no` 而不是 `event_no`：`sys_outbox.event_no` 已经占了后者，
-- 两者是完全不同的东西（一个是领域事件，一个是风险事件）。同名会让
-- schema-lineage 的按名归属失效，也会让下一个按名字 join 的人连错且不报错。

CREATE TABLE IF NOT EXISTS risk_event (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    risk_event_no VARCHAR(64)  NOT NULL COMMENT '风险事件单号 RE...',
    type          VARCHAR(32)  NOT NULL COMMENT 'FAKE_ORDER / ABNORMAL_FISSION / MALICIOUS_REFUND',
    subject_type  VARCHAR(16)  NOT NULL COMMENT 'USER / MERCHANT / DEVICE',
    -- **存标识不存昵称**：昵称会改、会重名，按昵称拉黑等于按一个随时会变的字符串封人
    subject       VARCHAR(64)  NOT NULL COMMENT '主体标识：userNo / entityNo / 设备号或 IP',
    subject_name  VARCHAR(128)          COMMENT '主体展示名，只用于运营端列表',
    -- 逗号分隔的中文短语（「24 小时内下单 12 单」）。**刻意不给分值** ——
    -- 分值口径要等有真实样本后由风控定，现在编一个看起来很准的分数，
    -- 只会让人照着它做决定
    signals       VARCHAR(512) NOT NULL DEFAULT '' COMMENT '命中信号，逗号分隔',
    refs          VARCHAR(1024) NOT NULL DEFAULT '' COMMENT '证据单号（订单/售后/归因链路），逗号分隔',
    hit_count     INT          NOT NULL DEFAULT 1 COMMENT '本事件累计命中次数',
    status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / CONFIRMED / DISMISSED',
    verdict       VARCHAR(512)          COMMENT '处置结论。确认与排除都必填',
    decided_by    VARCHAR(64)           COMMENT '处置人 staffNo',
    decided_at    DATETIME              COMMENT '处置时刻',
    -- 待处置期间 = `type|subject`，处置之后改写成自己的单号。
    -- 于是「同一主体同类风险在处置完成前只有一张待办」由唯一索引保证，
    -- 而处置完之后再命中还能重新开单。**它同时是 Outbox 至少一次投递的兜底**
    dedup_key     VARCHAR(160) NOT NULL COMMENT '开单去重键',
    tenant_no     VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at    DATETIME     NOT NULL,
    created_by    VARCHAR(64)           DEFAULT NULL,
    updated_at    DATETIME     NOT NULL,
    updated_by    VARCHAR(64)           DEFAULT NULL,
    version       BIGINT       NOT NULL DEFAULT 0,
    deleted       TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_risk_event_no (risk_event_no),
    UNIQUE KEY uk_risk_event_dedup (dedup_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '风险事件（三类同表）';

-- 命中流水。**阈值判定的唯一数据源** —— 不另存「用户画像」表：
-- 画像就是这份流水的聚合，另存一张迟早出现「画像说 7 次、点进去只有 4 次」。
--
-- `ref` 上的唯一索引是**幂等键**：Outbox 是至少一次投递，同一张订单/售后单
-- 重投多少次都只计一次。没有它，投递器重启一次就能把一个正常用户送进黑名单。
CREATE TABLE IF NOT EXISTS risk_signal_hit (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    type         VARCHAR(32)  NOT NULL COMMENT '与 risk_event.type 同口径',
    subject_type VARCHAR(16)  NOT NULL,
    subject      VARCHAR(64)  NOT NULL,
    evidence_ref VARCHAR(64)  NOT NULL COMMENT '证据单号：orderNo / afterSaleNo / traceNo',
    detail       VARCHAR(255)          COMMENT '这一次命中的人话说明',
    hit_at       BIGINT       NOT NULL COMMENT '命中时刻（epoch ms），窗口计算用',
    tenant_no    VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at   DATETIME     NOT NULL,
    created_by   VARCHAR(64)           DEFAULT NULL,
    updated_at   DATETIME     NOT NULL,
    updated_by   VARCHAR(64)           DEFAULT NULL,
    version      BIGINT       NOT NULL DEFAULT 0,
    deleted      TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_risk_hit_ref (type, evidence_ref)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '风控命中流水（append-only，阈值判定的唯一数据源）';

-- 黑名单与申诉。**`until` 必填**：无期限拉黑没有申诉出口，
-- 那是产品事故不是风控严格（ops-web 契约注释的原话）。
CREATE TABLE IF NOT EXISTS risk_blacklist (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    black_no       VARCHAR(64)  NOT NULL COMMENT '黑名单单号 BL...',
    subject_type   VARCHAR(16)  NOT NULL COMMENT 'USER / MERCHANT / DEVICE',
    subject        VARCHAR(64)  NOT NULL,
    subject_name   VARCHAR(128)          COMMENT '展示名',
    reason         VARCHAR(512) NOT NULL COMMENT '拉黑原因。申诉时被拉黑者要能看到自己因为什么被拉黑',
    until_at       DATETIME     NOT NULL COMMENT '到期时间，必填。无期限拉黑没有申诉出口',
    appeal_status  VARCHAR(16)  NOT NULL DEFAULT 'NONE' COMMENT 'NONE / PENDING / UPHELD / REJECTED',
    appeal_reason  VARCHAR(512)          COMMENT '被拉黑者提交的申诉理由',
    appeal_verdict VARCHAR(512)          COMMENT '裁决说明，被拉黑者会看到',
    -- 申诉通过或到期后置 0，**记录保留**：留痕不是删除
    active         TINYINT      NOT NULL DEFAULT 1 COMMENT '是否生效中',
    tenant_no      VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at     DATETIME     NOT NULL,
    created_by     VARCHAR(64)           DEFAULT NULL,
    updated_at     DATETIME     NOT NULL,
    updated_by     VARCHAR(64)           DEFAULT NULL,
    version        BIGINT       NOT NULL DEFAULT 0,
    deleted        TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_risk_black_no (black_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '黑名单与解禁申诉';

-- 拦截规则（P-16.2.5）：一类一条。
--
-- ⚠️ `auto_block` 这一版**不接下单/支付链路**（TDD §二 D3）：它是运营对每类风险的
-- 处置意愿声明，实际拦截点另说。存起来不是装饰 —— 接拦截点时读的就是它，
-- 不必再设计一次配置面。
--
-- **不在这里 INSERT 种子**：gen-test-schema.py 只重放 DDL，迁移里的 INSERT
-- 进不了 schema-test.sql，靠它做种子的话单测库里永远是空表。
-- 三条默认规则由 RiskRuleService 读时自愈。
CREATE TABLE IF NOT EXISTS risk_rule (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    type         VARCHAR(32) NOT NULL COMMENT 'FAKE_ORDER / ABNORMAL_FISSION / MALICIOUS_REFUND',
    threshold    INT         NOT NULL COMMENT '触发阈值，必须 > 0（0 等于全量拦截）',
    window_hours INT         NOT NULL DEFAULT 24 COMMENT '统计窗口（小时）',
    auto_block   TINYINT     NOT NULL DEFAULT 0 COMMENT '命中后是否建议自动拦截',
    tenant_no    VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at   DATETIME    NOT NULL,
    created_by   VARCHAR(64)          DEFAULT NULL,
    updated_at   DATETIME    NOT NULL,
    updated_by   VARCHAR(64)          DEFAULT NULL,
    version      BIGINT      NOT NULL DEFAULT 0,
    deleted      TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_risk_rule_type (type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '风控拦截规则（一类一条）';
