-- 增长与归因（P-9.1 / P-9.2）。见 docs/technical/design/TDD-运营端增长与归因.md。
--
-- **归因规则直接决定商家付多少佣金**（ADR-004 §6：STORE_CODE → MERCHANT_OWNED 低费率，
-- 其余 → PLATFORM 正常费率）。此前优先级写死在 `MktAttribution.weightOf()`、
-- 窗口期写死在 `@Value("${shop.attribution.window-days:30}")` —— 运营端有页面、
-- 改完什么都不会发生。这批把它们搬到库里，`AttributionServiceImpl` 直接读。
--
-- 单行配置表（`rule_key` 恒为 MAIN）而不是塞 `sys_setting`：
-- 四个字段各有类型与校验（全序、1–90、枚举、非空集合），
-- 塞进一个 KV 的 value 里，校验就只能写在读的那一侧，而读的地方会有很多处。

CREATE TABLE IF NOT EXISTS mkt_attribution_rule (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    rule_key         VARCHAR(32) NOT NULL DEFAULT 'MAIN' COMMENT '预留多套规则，一期恒 MAIN',
    -- 全序，逗号分隔。**不重不漏** —— 半个优先级表在冲突时会随机裁决
    priority         VARCHAR(64) NOT NULL DEFAULT 'STORE_CODE,INVITER,CHANNEL' COMMENT '归因优先级，高→低',
    window_days      INT         NOT NULL DEFAULT 30 COMMENT '归因窗口期（天），1–90。0 等于关掉归因',
    -- ⚠️ 默认 OVERWRITE 而不是 ops-web mock 里的 KEEP_FIRST：
    -- 后端已上线并被 M6aStoreAttributionFlowTest 钉住的行为是覆盖
    -- （「已归属 A 店的用户扫 B 店码：覆盖，且留痕可回放」）。
    -- 矩阵 B1 本来就写着「未拍板」，可配是它的解 —— 不是拿 mock 的占位值当决议
    conflict_policy  VARCHAR(16) NOT NULL DEFAULT 'OVERWRITE' COMMENT 'KEEP_FIRST / OVERWRITE / ASK_USER',
    -- 一个因子都不选 = 所有人都是新客，新人券会被无限领
    new_user_factors VARCHAR(32) NOT NULL DEFAULT 'DEVICE,PHONE' COMMENT '新客判定因子，逗号分隔：DEVICE / PHONE',
    tenant_no        VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at       DATETIME    NOT NULL,
    created_by       VARCHAR(64)          DEFAULT NULL,
    updated_at       DATETIME    NOT NULL,
    updated_by       VARCHAR(64)          DEFAULT NULL,
    version          BIGINT      NOT NULL DEFAULT 0,
    deleted          TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_attr_rule_key (rule_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '归因规则（单行配置，驱动归因引擎）';

-- 邀请有礼 / 老带新（P-9.2.1 / 9.2.2）。
-- **奖励只能是券**：ADR-004 去团长化后不存在现金激励 —— 一旦发现金，
-- 职业薅羊毛立刻回来，且归因作弊有了直接变现路径。
CREATE TABLE IF NOT EXISTS mkt_fission_campaign (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    fission_no      VARCHAR(64) NOT NULL COMMENT '裂变活动单号 FS...',
    name            VARCHAR(128) NOT NULL,
    reward_type     VARCHAR(16) NOT NULL DEFAULT 'COUPON' COMMENT '只能是 COUPON',
    coupon_no       VARCHAR(64) NOT NULL COMMENT '奖励券模板（mkt_coupon.coupon_no）',
    inviter_count   INT         NOT NULL DEFAULT 0 COMMENT '邀请人得几张',
    invitee_count   INT         NOT NULL DEFAULT 0 COMMENT '被邀请人得几张',
    enabled         TINYINT     NOT NULL DEFAULT 0,
    -- 计数是**台账的聚合**（mkt_fission_invite），写在这里只是给列表页省一次 count；
    -- 真值以台账为准，对不上时以台账重算
    invited_count   INT         NOT NULL DEFAULT 0 COMMENT '累计邀请人数',
    converted_count INT         NOT NULL DEFAULT 0 COMMENT '其中完成首单的人数',
    tenant_no       VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at      DATETIME    NOT NULL,
    created_by      VARCHAR(64)          DEFAULT NULL,
    updated_at      DATETIME    NOT NULL,
    updated_by      VARCHAR(64)          DEFAULT NULL,
    version         BIGINT      NOT NULL DEFAULT 0,
    deleted         TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fission_no (fission_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '裂变活动（邀请有礼 / 老带新）';

-- 邀请台账：一行 = 一次「在某活动生效期间、由某人邀来某人」。
--
-- 为什么必须有它（不按「全部 INVITER 归因」现算）：多个活动并存时，
-- 现算出来的是同一个数 —— 页面上两个活动的数据一模一样，而没有任何报错。
--
-- 它同时是另外两件事的落点：**新客判定**（同设备/同手机号只算一次）、
-- **发奖幂等**（uk_fission_invitee）。
CREATE TABLE IF NOT EXISTS mkt_fission_invite (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    fission_no   VARCHAR(64) NOT NULL,
    inviter_no   VARCHAR(64) NOT NULL COMMENT '邀请人 userNo',
    invitee_no   VARCHAR(64) NOT NULL COMMENT '被邀请人 userNo',
    device_id    VARCHAR(64)          COMMENT '被邀请人设备号，新客判定用',
    phone_tail   VARCHAR(8)           COMMENT '手机号后四位。完整号码永远不出 UserQueryPort（B12）',
    -- 非新客照样落行、但不发奖：不落行的话，运营只会看到一个莫名其妙偏低的 invitedCount，
    -- 而「邀了 100 个只有 3 个算数」这件事在数据里看不见
    is_new_user  TINYINT     NOT NULL DEFAULT 1,
    rewarded     TINYINT     NOT NULL DEFAULT 0 COMMENT '奖励是否已发出',
    reward_error VARCHAR(255)         COMMENT '发奖失败原因（券停用/预算耗尽）。发奖失败不打断归因主流程',
    order_no     VARCHAR(64)          COMMENT '被邀请人首单，由 ORDER_CREATED 事件回填 = 转化',
    tenant_no    VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at   DATETIME    NOT NULL,
    created_by   VARCHAR(64)          DEFAULT NULL,
    updated_at   DATETIME    NOT NULL,
    updated_by   VARCHAR(64)          DEFAULT NULL,
    version      BIGINT      NOT NULL DEFAULT 0,
    deleted      TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_fission_invitee (fission_no, invitee_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '裂变邀请台账（新客判定 + 发奖幂等 + 真计数）';

-- 归因留痕补列：它是 P-9.1.3「归因链路查询与审计」的**唯一数据源**，
-- 不新造一份平行数据（同一件事记两处，迟早只剩一处在维护）。
--
-- device_id / ip：没有这两列，P-16.2.2「异常裂变（同设备/同 IP）」
-- 只能退化成「某人邀请人数多」—— 那不是需求写的那件事，而看起来很像。
ALTER TABLE mkt_attribution_log ADD COLUMN trace_no VARCHAR(64) DEFAULT NULL COMMENT '归因链路单号 AT...；老行为空时回落成 AT{id}';
ALTER TABLE mkt_attribution_log ADD COLUMN device_id VARCHAR(64) DEFAULT NULL COMMENT '上报设备号';
ALTER TABLE mkt_attribution_log ADD COLUMN ip VARCHAR(64) DEFAULT NULL COMMENT '上报 IP，取自 X-Forwarded-For / RemoteAddr';
ALTER TABLE mkt_attribution_log ADD COLUMN order_no VARCHAR(64) DEFAULT NULL COMMENT '该用户首单，由 ORDER_CREATED 事件回填';
ALTER TABLE mkt_attribution_log ADD COLUMN risk_signals VARCHAR(255) DEFAULT NULL COMMENT '判定时算出的风控信号，与风险事件同一套口径';
