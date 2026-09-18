# TDD · 营销域详细设计：领域对象 · 数据库 · API

> 状态：**草稿 · 待确认** · 2026-09-18（§8 三条已给建议方案）
> 档位：**2**（新表族 · 跨 B / C / 运营三端 · 改订单与结算口径）
> 需求：[PRD-营销-活动统一模型](../requirements/PRD-营销-活动统一模型.md)（AC-1 ～ AC-16）
> 原型：[营销 · 活动统一模型原型](https://claude.ai/artifact/EeKjhCyJ9P3i5iDVNbhUPt)（37 屏，下文 `sNN`）
> 决策：[ADR-024 拼团与社区集单并存](ADR/ADR-024-拼团与社区集单并存.md)
> 实施切片：[TDD-营销-活动统一模型与集单](TDD-营销-活动统一模型与集单.md)（P1 的落点、测试与消融；本文是它的上位设计）

---

## §0 这份文档回答什么

37 屏原型背后只有**六个聚合**。本文按「领域对象 → 数据库 → API」三层把它们写清，
每一层都能对回原型的某一屏、PRD 的某一条 AC。

| 聚合 | 是什么 | 原型 | 表 | 状态 |
|---|---|---|---|---|
| **活动** Activity | 一条下单时自动生效的规则 | s02–s08 · s11 · s19 · s35 | `pmt_activity` + 3 张子表 | 已有，扩列 |
| **集单期** Period | 集单活动在某个截单日的实例 | s20 · s31–s33 · s37 | `pmt_period` | **新** |
| **团** Group | 拼团活动开出来的实例 | s09–s10 · s22 · s34 | `mkt_group_buy` + `mkt_group_member` | 已有，扩列 |
| **券** Coupon | 券模板 + 用户券 + 发放批次 | s12–s18 · s24–s25 · s36 | `pmt_coupon` 等 4 张 | 已有，不改 |
| **平台活动报名** Enrollment | 商家参加平台活动的一张报名单 | s27–s30 | `pmt_enrollment` | **新**（P3） |
| **优惠发生** Apply | 每一次真的减了钱的账 | —（对账与结算） | `pmt_apply` | 已有，不改 |

平台活动本身**不是新聚合**：它就是一条 `owner = PLATFORM` 的活动（§1.6）。

---

## §1 领域对象

### 1.1 总图

```
                     ┌──────────── 规则层 ────────────┐
   玩法模板(配置) ──▶ │ Activity                        │ ◀── Enrollment（平台活动的报名单）
                     │  头 · 条件 · 利益 · 约束        │
                     └───────┬────────────┬────────────┘
                   trigger=CUTOFF    trigger=GROUP
                             │            │
                     ┌───────▼───┐  ┌─────▼──────┐          ┌────────────┐
   实例层            │ Period    │  │ Group      │          │ Coupon     │──▶ UserCoupon
                     └───────┬───┘  └─────┬──────┘          └─────┬──────┘
                             │            │                       │
   订单                ord_sub_order.period_no / group_no      下单抵扣 / 到店核销
                             │            │                       │
   账本                      └──────▶ Apply（pmt_apply）◀─────────┘ ──▶ 结算（discount_platform / merchant）
```

**三层不混**（概念对齐 §三 的硬规定）：规则只说怎么给；实例记录某一次参与走到哪一步；
账本记每一次真的减了多少、谁出钱。活动结束不影响实例，实例取消不改规则，账本只增不改。

### 1.2 活动 Activity（聚合根）

**标识**：`activityNo`（`PT…`）。**归属**：`owner`（MERCHANT / PLATFORM）+ `entityNo`（平台活动为空）。

| 组成 | 值对象 | 字段 | 说明 |
|---|---|---|---|
| **头** | — | `name` · `storeNo` · `goal` | `goal` 只影响新建时的默认值（P2 起停写） |
| | `Schedule` | `scheduleType` · `startAt` · `endAt` · `scheduleRule` | ONE_OFF / ALWAYS_ON / RECURRING |
| **条件** | `Trigger` | `triggerType` + 参数 | 见下表 |
| | `Scope` | 子表 `pmt_activity_goods` | GOODS / CATEGORY / ALL |
| | `Audience` | 子表 `pmt_activity_audience` | 一行都没有 = 所有人 |
| **利益** | `Benefit` | `benefitType` · `benefitAmountMinor` · `benefitQty` · `benefitRef` | 见下表 |
| **约束** | `Cap` | `quota` / `quotaUsed` · `budgetMinor` / `budgetUsedMinor` | 长期活动至少一个 |
| | `BatchRule` | `cutoffTime` · `pickupOffset` · `pickupFrom` · `minQty` · `periodQuota` · `decideHours` | 只对 CUTOFF |
| | `GroupRule` | `triggerQty`（人数）· `groupHours` | 只对 GROUP |
| **出资** | `Funding` | `funder` · `platformShareMinor` | 平台活动才有（§1.6） |
| **状态** | — | `status` · `endedReason` · `archivedAt` | 见 §4 |

**触发 × 利益 = 玩法**：

| 触发 `triggerType` | 参数 | 可配的利益 | 玩法 | 有实例 |
|---|---|---|---|---|
| `AMOUNT` 满额 | `triggerAmountMinor` | CUT · GIFT | 满减、满赠 | — |
| `QTY` 满件 | `triggerQty` | CUT · PRICE · GIFT | 第二件、买赠 | — |
| `GOODS` 命中商品 | 范围子表 | PRICE | 特价、秒杀 | — |
| `NONE` 无条件 | — | COUPON（P3） | 进店送券 | — |
| `GROUP` 凑够人数 | `triggerQty` · `groupHours` | PRICE | **拼团** | **团** |
| `CUTOFF` 到截单时刻 | `BatchRule` | PRICE | **社区集单** | **期** |

**玩法模板**（`PlayTemplate`，配置不是表）：`{key, 名称, 一句话, triggerType, benefitType, 规则组要显示哪几行}`。
新建页（s03 选择面板、s04/s05/s19 的「规则」组）照模板渲染。**加玩法 = 加一条模板**，后端只认触发 × 利益。
模板放 `packages/shared`，B 端与运营端共用一份。

**不变式**（建 / 改活动时在服务端校验，不靠端上）

| # | 不变式 | 错误码 | AC |
|---|---|---|---|
| A1 | 长期活动必须有 `quota` 或 `budgetMinor` | `ACTIVITY_ALWAYS_ON_NEEDS_CAP` | AC-2 |
| A2 | 改单价 / 送商品必须选商品、必须限量 | `ACTIVITY_GOODS_REQUIRED` · `ACTIVITY_QUOTA_REQUIRED` | — |
| A3 | GROUP：人数 ≥ 2，利益只能 PRICE；一件货同时只在一个团购活动里 | `BAD_REQUEST` | — |
| A4 | CUTOFF：截单时刻合法、利益只能 PRICE、不支持 RECURRING；参数为正 | `BAD_REQUEST` | — |
| A5 | CUTOFF 的商品不能在 SKU 预售中 | `GOODS_IN_PRESALE` | AC-11 |
| A6 | 进行中只能改结束时间与上限（`endAt` · `quota` · `budgetMinor` · `periodQuota`） | `ACTIVITY_RULE_LOCKED` | AC-4 |
| A7 | 已结束的不能改、不能复活 | `ACTIVITY_ENDED_IMMUTABLE` | — |
| A8 | 集单 / 拼团参数只在对应触发下落库，其余玩法一律清空 | —（写入时清） | — |

**领域行为**：`isActiveAt(now, zone)`（排期判断唯一一处）· `hasQuotaLeft()` · `consume(amount)`（占量 / 占预算，与订单同事务）· `end(reason)`。

### 1.3 集单期 Period（聚合根，新）

**标识**：`periodNo`（`PD…`）。**唯一**：`(activityNo, periodDate)`。

| 字段 | 说明 |
|---|---|
| `periodDate` | 截单日（市场时区） |
| `cutoffAt` | 截单时刻；提前截单时改写为此刻 |
| `pickupDate` | 提货日 = `periodDate + pickupOffset`；写进订单 `arrive_date` |
| `status` | OPEN / SHORT / CONFIRMED / CANCELLED（§4） |
| `decideDeadline` | 进入 SHORT 那一刻按活动 `decideHours` 算好写死 |
| `decidedBy` · `decidedAt` | 谁处理的；超时自动取消写 `SYSTEM` |

**份数、人数、金额不存**，每次从挂在它上面的订单现算：只算「已付款且未退」的行（`PeriodLine.paidAndKept`）。
占名额则把「待付款」也算上（`holdsQuota`），否则会超卖。

**领域行为**

| 行为 | 触发者 | 规则 |
|---|---|---|
| `ticketFor(entity, goods, qty, orderAt)` | 下单 | 按下单时刻落期：已过当期截单或当期被提前截单 → 下一期；按需建期（唯一键兜并发）；超每期上限拒 |
| `cutoffNow()` | 商家 s20 | 只能从 OPEN；截单时刻改成此刻后立即推进 |
| `advance()` | 任务 | OPEN 且过点 → 份数够（或未设起订）CONFIRMED，否则 SHORT |
| `decide(PROCEED/CANCEL)` | 商家 s33 | 只能从 SHORT；CANCEL 逐单全额退款 |
| `cancelUndecided()` | 任务 | SHORT 且过 `decideDeadline` → CANCELLED + 退款；并补扫近 3 天已取消期里「截单后才付款」的单 |

所有状态迁移用**带状态条件的 UPDATE**：任务与商家同时动同一期，只有先到的一边生效。

### 1.4 团 Group（聚合根，已有）

**标识**：`groupNo`（`GB…`）。

| 字段 | 现状 | 本设计 |
|---|---|---|
| `activityNo` | **没有** —— 团与规则之间无外键，详情页说不出「属于哪个活动」（s10 最后一行） | **新增** |
| `minCount` · `groupPriceMinor` · `originPriceMinor` | 开团时从活动（商家团）或商品（买家团）拷一份 | 一律从活动拷；商品上两列退场（见 TDD-团购从商品挪进活动） |
| `endAt` | 写死 7 天，且**无人读** | 取活动 `groupHours`（缺省 24）；`GroupExpireJob` 每分钟置 FAILED |
| `initiatorUserNo` | 空 = 商家开团 | 不变 |
| `pickupNo` | 团限定一个自提点 | 不变 |

**关键缺口：参团不下单**（2026-09-18 读代码核实）。
`join` 只插一行成员，不产生订单与付款；`ord_sub_order.group_no` 全仓无人写入；
C 端 `CreateOrderReq.groupNo` 的注释自己写着「后端不认这个字段」。
于是成团价从未被收过、到期也无钱可退，而参团成功的提示还写着「先买的邻居也退了差价」。

**本设计把参团接到下单上**（s22「参团 ¥8」）：

```
参团 = 下单(groupNo) ─▶ 按团价算价 ─▶ 子单写 group_no ─▶ 付款成功 ─▶ 成员 +1（幂等）─▶ 够人数 → FORMED
到期未成团 ─▶ FAILED ─▶ 该团所有已付款子单系统全额退款（与集单取消同一条退款路径）
```

- 成员行在**付款成功**时落，不在点按钮时落：没付钱的人不该让「还差 N 人」变少。
- 「退差价」这个玩法**不做**：拼团价在下单时就按团价收，不存在先按原价收、成团后退差价。C 端那句提示删掉。

### 1.5 券 Coupon / UserCoupon / CouponIssue（已有，数据不改）

P2 只改界面（s12–s18）。模型已经覆盖原型全部字段：

| 原型字段 | 表字段 |
|---|---|
| 类型（现金 / 折扣 / 兑换 / 次卡 / 免运费） | `benefit_mode` + `times_total`（次卡 = `times_total > 1`） |
| 面额 · 折扣 · 封顶 | `benefit_value` · `benefit_cap_minor` |
| 门槛 | `min_amount_minor` · `min_qty` |
| 商品范围 | `scope_type` + `pmt_coupon_scope` |
| 有效期（领后 N 天 / 固定） | `validity_mode` · `valid_days` · `start_at` / `end_at` |
| 数量 · 每人 | `total_count` · `per_user_limit` |
| 最多支出 | `budget_minor`（建券时断言 ≥ 数量 × 单张最大优惠） |
| 发放方式 · 核销方式 | `issue_mode` · `redeem_mode` |
| 发放结果的跳过分项（s16） | `pmt_coupon_issue.skipped_count` · `skip_detail` |

「活动发券」（`BENEFIT_COUPON`）今天在建活动时被拒，P3 随「自己组合」放开。

### 1.6 平台活动与报名 Enrollment（P3，新）

**平台活动 = `owner = PLATFORM` 的活动**：规则、排期、玩法与商家活动同一个模型（s29 左栏）。多出来的只有：

| 字段 | 说明 |
|---|---|
| `owner` | MERCHANT（缺省）/ PLATFORM |
| `funder` · `platformShareMinor` | 平台每单补贴多少；商家承担 = 优惠额 − 平台补贴 |
| `enrollDeadline` | 报名截止 |
| `enrollRule` | 报名门槛（评分下限、近 N 天无违规），JSON |
| 范围 | 类目、城市：沿用 `pmt_activity_goods`（`scope_type = CATEGORY`）+ 新 `REGION` |

**报名单 Enrollment**（`pmt_enrollment`，聚合根）：一个商家对一个平台活动的一次报名。

| 字段 | 说明 |
|---|---|
| `enrollmentNo` · `activityNo` · `entityNo` | 标识与归属 |
| `goodsNos` | 子表 `pmt_enrollment_goods` |
| `quota` | 商家报的份数 |
| `platformMaxMinor` · `merchantMaxMinor` | 提交时算定：份数 × 每单补贴 / 承担（s28「最多承担」、s30「平台出资」） |
| `status` | SUBMITTED / APPROVED / REJECTED / WITHDRAWN |
| `reviewedBy` · `reviewedAt` · `rejectReason` | 审核 |

**不变式**：E1 审核通过时 Σ(已通过报名的 `platformMaxMinor`) ≤ 平台预算（AC-14，带条件 UPDATE 占预算）；
E2 过了报名截止不能提交；E3 不满足门槛不能提交。

**算价**：平台活动命中时，优惠照常减给买家；`pmt_apply.funder` 拆成两行（PLATFORM / MERCHANT），
子单 `discount_platform` / `discount_merchant` 分列 —— **结算沿用统一周期**（PRD §4.5.5），
`SettleSourcePortImpl` 已经把 `discount_platform` 带进结算源，不另建打款通道。

### 1.7 优惠发生 Apply（已有，不改）

每一次减钱落一行，只增不改；撤销写 `reverted_at`。平台活动一单可能两行（平台、商家各一行）。
它是对账与结算的唯一依据。

### 1.8 跨聚合规则

| # | 规则 | 落在哪 |
|---|---|---|
| X1 | 算价顺序：**活动 → 券 → 积分**；券作用在活动之后的金额上 | `OrderServiceImpl` 下单 / 预览 |
| X2 | 同一单多个活动命中：同类**取最优**，不相加 | `ActivityPricingServiceImpl` |
| X3 | 商品价：特价 / 集单价**取最低**；拼团价只在团里生效 | `flashPrices`（GROUP 排除） |
| X4 | 活动结束不影响已开的团、已下单的期 | `setStatus` 不触碰实例 |
| X5 | 退款只走售后的一条收尾路径（先回退分账再退款）；**不允许只改状态** | `AfterSaleService#systemRefund` |
| X6 | 集单与 SKU 预售互斥 | A5 |

---

## §2 数据库

### 2.1 表清单

| 表 | 聚合 | 现状 | 本设计 | 迁移 | 批 |
|---|---|---|---|---|---|
| `pmt_activity` | 活动 | 有 | 加 7 列（集单 / 拼团参数） | V335 | P1 |
| `pmt_activity` | 活动 | 有 | 加 5 列（平台活动） | V33x | P3 |
| `pmt_activity_goods` | 活动 | 有 | `scope_type` 增 `REGION` | V33x | P3 |
| `pmt_activity_audience` | 活动 | 有 | 不改 | — | — |
| `pmt_activity_rule` | 活动 | — | **新**：多条件 / 多利益（自己组合） | V33x | P3 |
| `pmt_period` | 集单期 | — | **新** | V335 | P1 |
| `mkt_group_buy` | 团 | 有 | 加 `activity_no` | V336 | P1 |
| `mkt_group_member` | 团 | 有 | 加 `sub_order_no`（付款成功才落） | V336 | P1 |
| `ord_sub_order` | 订单 | 有 | 加 `period_no` · `arrive_date`；`group_no` 已有、开始写入 | V335 | P1 |
| `pmt_coupon` 等 4 张 | 券 | 有 | 不改 | — | — |
| `pmt_enrollment` · `pmt_enrollment_goods` | 报名 | — | **新** | V33x | P3 |
| `pmt_apply` | 账本 | 有 | 不改 | — | — |
| `mkt_campaign` · `mkt_coupon` | 老模型 | 有（线上 0 行） | 退场 | 另见合并 TDD | 并行 |

### 2.2 `pmt_activity`（完整列，含新增）

| 列 | 类型 | 说明 |
|---|---|---|
| `activity_no` | VARCHAR(64) | 唯一 |
| `entity_no` | VARCHAR(64) | 商家主体；**平台活动为空**（P3 放开 NOT NULL 前先核数据域） |
| `store_no` | VARCHAR(64) | 空 = 全部门店 |
| `name` · `goal` | | `goal` P2 起停写 |
| `trigger_type` | VARCHAR(16) | NONE / AMOUNT / QTY / GOODS / GROUP / **CUTOFF** |
| `trigger_amount_minor` · `trigger_qty` | | 满额 / 满件 / 成团人数 |
| `benefit_type` | VARCHAR(16) | CUT / PRICE / GIFT / COUPON |
| `benefit_amount_minor` · `benefit_qty` · `benefit_ref` | | |
| `schedule_type` · `start_at` · `end_at` · `schedule_rule` | | |
| `quota` · `quota_used` · `budget_minor` · `budget_used_minor` | | |
| `status` · `ended_reason` · `archived_at` | | |
| **`cutoff_time`** | VARCHAR(5) | CUTOFF：HH:mm，市场时区（V335） |
| **`pickup_offset`** | INT | CUTOFF：提货日 = 截单日 + N（缺省 1） |
| **`pickup_from`** | VARCHAR(5) | CUTOFF：提货日几点起 |
| **`min_qty`** | INT | CUTOFF：起订量，空 = 不设 |
| **`period_quota`** | INT | CUTOFF：每期上限，空 = 不限 |
| **`decide_hours`** | INT | CUTOFF：未达起订的处理时限，空 = 配置缺省 14 |
| **`group_hours`** | INT | GROUP：成团时限，空 = 24 |
| `owner` | VARCHAR(16) | P3：MERCHANT / PLATFORM |
| `funder` · `platform_share_minor` | | P3 |
| `enroll_deadline` · `enroll_rule` | BIGINT · TEXT | P3 |

### 2.3 `pmt_period`（V335，已写）

见 [V335__batch_sale_period.sql](../../backend/shop-app/src/main/resources/db/migration/V335__batch_sale_period.sql)。
唯一键 `(tenant_no, activity_no, period_date)` 兜并发建期；`idx_pmt_period_due (status, cutoff_at)` 给任务扫。

### 2.4 `mkt_group_buy` / `mkt_group_member`（V336）

```sql
ALTER TABLE mkt_group_buy
    ADD COLUMN activity_no VARCHAR(64) DEFAULT NULL COMMENT '开团时依据的拼团活动。存量 2 行为空';
ALTER TABLE mkt_group_member
    ADD COLUMN sub_order_no VARCHAR(64) DEFAULT NULL COMMENT '参团付款的子单。付款成功才落成员行';
CREATE UNIQUE INDEX uk_group_member_sub ON mkt_group_member (sub_order_no);
```

成员行的唯一键落在子单号上：同一笔付款回调重放，成员只加一次。

### 2.5 `ord_sub_order`（V335）

| 列 | 写入 | 读取 |
|---|---|---|
| `group_no`（已有） | 参团下单（**本设计起开始写**） | 团到期退款、邻里自提核销作用域 |
| `period_no` | 集单下单 | 期汇总、期取消退款、截单前撤单判定 |
| `arrive_date` | 集单下单写提货日；其余为空 | 履约批次与看板：`COALESCE(arrive_date, 下单日)` |

### 2.6 P3 新表

```sql
CREATE TABLE IF NOT EXISTS pmt_enrollment
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    enrollment_no VARCHAR(64) NOT NULL,
    activity_no VARCHAR(64) NOT NULL COMMENT '平台活动',
    entity_no VARCHAR(64) NOT NULL COMMENT '报名商家。数据域锚点',
    quota INT(11) NOT NULL COMMENT '商家报的份数',
    platform_max_minor BIGINT(20) NOT NULL COMMENT '份数 × 每单平台补贴，提交时算定',
    merchant_max_minor BIGINT(20) NOT NULL COMMENT '份数 × 每单商家承担，提交时算定',
    status VARCHAR(16) NOT NULL DEFAULT 'SUBMITTED' COMMENT 'SUBMITTED / APPROVED / REJECTED / WITHDRAWN',
    reviewed_by VARCHAR(64) DEFAULT NULL,
    reviewed_at BIGINT(20) DEFAULT NULL,
    reject_reason VARCHAR(255) DEFAULT NULL,
    -- 审计列同 BaseEntity
    PRIMARY KEY (id),
    UNIQUE KEY uk_enrollment_no (enrollment_no),
    UNIQUE KEY uk_enrollment_once (tenant_no, activity_no, entity_no)
) COMMENT='平台活动报名单';

CREATE TABLE IF NOT EXISTS pmt_enrollment_goods
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    enrollment_no VARCHAR(64) NOT NULL,
    goods_no VARCHAR(64) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_enrollment_goods (enrollment_no, goods_no)
) COMMENT='报名的商品。纯关联集合，物理删';

CREATE TABLE IF NOT EXISTS pmt_activity_rule
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    activity_no VARCHAR(64) NOT NULL,
    kind VARCHAR(16) NOT NULL COMMENT 'CONDITION / BENEFIT',
    seq INT(11) NOT NULL COMMENT '利益按 seq 依次生效',
    rule_type VARCHAR(16) NOT NULL COMMENT '与 trigger_type / benefit_type 同一套取值',
    params TEXT NOT NULL COMMENT 'JSON，按 rule_type 校验',
    PRIMARY KEY (id),
    KEY idx_activity_rule (activity_no, kind, seq)
) COMMENT='自己组合：多条件（全部满足）× 多利益。有行时优先于主表的单条触发 × 利益';
```

### 2.7 数据域登记

| 表 | 维度 | 锚点 | 绕开的地方（必须显式钉死主体） |
|---|---|---|---|
| `pmt_period` | MERCHANT | `entity_no` | 下单取期（买家会话）、定时任务 |
| `pmt_enrollment` | MERCHANT | `entity_no` | 运营审核（运营会话用 ops 维度） |
| `pmt_activity`（平台活动） | — | `entity_no` 为空 | 商家读平台活动列表（s27）走专用查询 |

登记后必须有一条走**真 HTTP** 的跨商家不可见用例（`OpsDataScopeFlowTest` 同形）：
直接调 service 的用例没有请求上下文，数据域根本不生效，绿了也不说明问题。

---

## §3 API

### 3.1 约定

- 金额一律**分**（`*Minor`）；时刻一律**毫秒时间戳**；日期 `YYYY-MM-DD`（市场时区）。
- 响应走全局信封 `{code, msg, data}`；错误码见 §3.6。
- B 端权限码：活动、集单、团、券 = `biz:campaign`；核销 = `biz:verify`；平台活动报名 = `biz:campaign`。
- 新增 `/biz` 端点走七处登记（判权表、两份白名单、生成产物），pre-push 才报。

### 3.2 B 端（商家 App）

| 屏 | 动作 | 方法 · 路径 | 请求 | 响应 | 状态 |
|---|---|---|---|---|---|
| s01 | 营销入口 | GET `/biz/marketing/summary` | — | `{monthDiscountMinor, monthOrders, activityRunning, couponIssuing, periodToday{qty,cutoffAt}, groupsShort, enrollable, quotesPending}` | **新** |
| s02 | 活动列表 | GET `/biz/activities?status=` | — | `ActivityVO[]` | 改：按 status 筛 |
| s03–s06 · s19 | 新建 / 编辑 | POST `/biz/activities` | `ActivityDraft` | `ActivityVO` | 改：加集单 / 拼团参数 |
| s06 | 发布前冲突 | POST `/biz/activity-conflicts` | `{goodsNos}` | `ConflictVO[]` | 有 |
| s07 · s32 | 活动详情 | GET `/biz/activities/{no}` | — | `ActivityVO` + `stats{used, instances, discountMinor}` | 改 |
| s07 · s08 | 暂停 / 结束 | PUT `/biz/activities/{no}/status` | `{status}` | `ActivityVO` | 有 |
| s35 | 编辑进行中 | POST `/biz/activities`（同上） | 只允许改 A6 那几项 | | 改（A6 校验） |
| s09 | 团列表 | GET `/biz/groups?status=` | — | `GroupBuyVO[]` | 改：按 status 筛 |
| s10 | 团详情 | GET `/biz/group/{groupNo}` | — | `GroupBuyVO`（含 `members[]` · `activityName`） | **新（P1b 已实现）** |
| s10 | 散团退款 | POST `/biz/group/{groupNo}/dissolve` | `{reason}` | `GroupBuyVO` | **新（P1b 已实现）**（置 FAILED + 退款） |
| s34 | 开团可选自提点 | GET `/biz/group/pickups` | — | `PickupRef[]` | **新（P1b）** |
| s34 | 开团 | POST `/biz/groups` | `{activityNo, goodsNo, pickupNo}` | `GroupBuyVO` | 改：加 activityNo · pickupNo |
| s31 | 集单列表 | GET `/biz/period?status=` | — | `PeriodVO[]` | **新（已写）** |
| s20 · s33 | 一期详情 | GET `/biz/period/{no}` | — | `PeriodDetailVO` | **新（已写）** |
| s20 | 提前截单 | POST `/biz/period/{no}/cutoff` | — | `PeriodVO` | **新（已写）** |
| s33 | 取消本期 / 照常发货 | POST `/biz/period/{no}/decision` | `{action: CANCEL \| PROCEED}` | `PeriodVO` | **新（已写）** |
| s20 | 去采购 | GET `/biz/period/{no}/purchase-lines` | — | `PurchaseLineVO[]` | **新（已写）** |
| s12 | 券列表 | GET `/biz/coupons?status=` | — | `CouponVO[]` | 有 |
| s13–s14 | 新建券 | POST `/biz/coupons` | `CouponDraft` | `CouponVO` | 有 |
| s15 | 券详情 | GET `/biz/coupons/{no}` | — | `CouponVO` | 有 |
| s15 | 停发 | PUT `/biz/coupons/{no}/status` | `{status: PAUSED}` | `CouponVO` | 有 |
| s18 | 发放预览 | POST `/biz/member-segments/preview` | `{segment}` | `{count}` | 有 |
| s18 → s16 | 发放 | POST `/biz/coupons/{no}/issue` | `{segmentNo}` | `IssueVO{issued, skipped, skipDetail, amountMinor}` | 有 |
| s16 | 发放记录 | GET `/biz/coupon-issues?couponNo=` | — | `IssueVO[]` | 有 |
| s17 | 核销查码 | GET `/biz/coupon-redeem/{code}` | — | `RedeemPreviewVO` | 有 |
| s17 | 核销 | POST `/biz/coupon-redeem` | `{code}` | `RedeemResultVO` | 有 |
| s27 | 平台活动列表 | GET `/biz/platform-activities?tab=` | — | `PlatformActivityVO[]`（含我的报名状态） | P3 新 |
| s28 | 报名 | POST `/biz/platform-activities/{no}/enrollment` | `{goodsNos, quota}` | `EnrollmentVO` | P3 新 |

### 3.3 C 端（买家小程序）

| 屏 | 动作 | 方法 · 路径 | 变化 |
|---|---|---|---|
| s21 · s26 | 商品详情 | GET `/mp/goods/{no}` | `GoodsVO` 增 `promo{label, groupPrice, groupSize, batch{cutoffAt, pickupDate, pickupFrom, orderedQty}}` 与 `openGroups[]`（最多 3 个） |
| s36 | 领券 | GET `/mp/coupon?goodsNo=` · POST `/mp/coupon/{no}/receive` | 有 |
| s22 | 团详情 | GET `/mp/group-buy/{no}` | 有；增 `activityName` |
| s22 | 参团 / 开团 | POST `/mp/order`，body 带 `groupNo`（参团）或 `openGroup: true`（开团） | **改**：`CreateOrderReq` 开始认这两个字段 |
| s23 | 确认订单 | POST `/mp/order/preview` | 响应的优惠明细按「活动 → 券」排序，活动行 `auto: true` |
| s37 | 集单订单详情 | GET `/mp/order/{no}` | `OrderVO` 增 `arriveDate` · `cancellableUntil` |
| s37 | 截单前撤单 | POST `/mp/order/{no}/cancel` | **改**：已付款的集单单在截单前可撤（走系统全额退款），截单后 `PERIOD_CUT_OFF` |
| s24 · s25 | 我的券 · 出示 | GET `/mp/coupon/mine` | 有 |

C 端**没有**活动接口：活动在算价时自动生效，买家不需要问它（架构对齐 §5.2）。

### 3.4 运营端

| 屏 | 动作 | 方法 · 路径 | 状态 |
|---|---|---|---|
| s29 | 新建平台活动 | POST `/ops/promotion/activities` | P3 新（与商家活动同一 `ActivityDraft`，加平台字段） |
| s30 | 报名审核列表 | GET `/ops/promotion/activities/{no}/enrollments?status=` | P3 新；响应带 `budgetUsedMinor / budgetMinor` |
| s30 | 通过 / 驳回 | POST `/ops/enrollments/{no}/review` | P3 新；`{pass, reason}`，超预算 `ENROLLMENT_OVER_BUDGET` |
| — | 活动总览 / 强停 | GET `/ops/promotion/activities` · POST `…/{no}/stop` | 有 |
| — | 团治理 | GET `/ops/groups` · POST `…/abort` `…/audit` | 有；`abort` 改为走退款（X5） |

### 3.5 主要 DTO

```
ActivityDraft {
  activityNo?, name, storeNo?, triggerType, triggerAmountMinor?, triggerQty?,
  benefitType, benefitAmountMinor?, benefitQty?, benefitRef?,
  scheduleType, startAt?, endAt?, scheduleRule?, quota?, budgetMinor?,
  audiences[{type, value}], goodsNos[],
  // CUTOFF
  cutoffTime?, pickupOffset?, pickupFrom?, minQty?, periodQuota?, decideHours?,
  // GROUP
  groupHours?
}
ActivityVO = ActivityDraft 全部字段 + quotaUsed, quotaLeft, budgetUsedMinor, maxExposureMinor,
             status, endedReason, liveNow

PeriodVO { periodNo, activityNo, activityName, periodDate, cutoffAt, pickupDate, pickupFrom,
           status, qty, customers, amountMinor, minQty?, periodQuota?, decideDeadline? }
PeriodDetailVO { period: PeriodVO, byGoods[{goodsNo, title, qty}], byPickup[{pickupNo, pickupName, qty}] }
PurchaseLineVO { skuNo, goodsNo, title, spec, qty }

GroupBuyVO(增) { …已有字段, activityNo, activityName, members[{nickname, joinedAt}] }

EnrollmentVO { enrollmentNo, activityNo, entityNo, entityName, goodsNos[], quota,
               platformMaxMinor, merchantMaxMinor, status, rejectReason? }
```

### 3.6 错误码

| 码 | 名 | 场景 |
|---|---|---|
| 40018 | `ACTIVITY_ALWAYS_ON_NEEDS_CAP` | 有 |
| 40019–40023 | 活动既有五个 | 有 |
| 40024 | `PERIOD_CUT_OFF` | 截单后撤单（已写） |
| 40025 | `PERIOD_STATE_CONFLICT` | 期状态已变（已写） |
| 40026 | `PERIOD_MIXED` | 一张子单命中两个集单活动（已写） |
| 40027 | `PERIOD_FULL` | 本期份数已满（已写） |
| 40028 | `GOODS_IN_PRESALE` | 集单选了预售商品（已写） |
| 40029 | `ACTIVITY_RULE_LOCKED` | 进行中改规则（P2） |
| 40030 | `GROUP_CLOSED` | 参团时团已成 / 已散 / 已过期（P1b 已实现） |
| 40031 | `ENROLLMENT_CLOSED` | 过了报名截止或不满足门槛（P3） |
| 40032 | `ENROLLMENT_OVER_BUDGET` | 通过后超出平台预算（P3） |
| 40033 | `GROUP_JOIN_NEEDS_UPGRADE` | 旧版 C 端调「直接参团」：参团已改为下单付款，提示升级（P1b） |

---

## §4 状态机汇总

| 对象 | 状态与迁移 | 终态 |
|---|---|---|
| 活动 | DRAFT →（发布）RUNNING ⇄ PAUSED；RUNNING / PAUSED →（到期 / 到量 / 预算用完 / 手动）ENDED | ENDED |
| 集单期 | OPEN →（截单）CONFIRMED 或 SHORT；SHORT →（照常）CONFIRMED 或（取消 / 超时）CANCELLED | CONFIRMED · CANCELLED |
| 团 | （审核开关开时）PENDING → OPEN；OPEN →（够人数）FORMED 或（到期 / 散团）FAILED | FORMED · FAILED |
| 券模板 | ACTIVE ⇄ PAUSED → ENDED | ENDED |
| 用户券 | UNUSED → USED / EXPIRED / REVOKED | 三者 |
| 报名单 | SUBMITTED → APPROVED / REJECTED；SUBMITTED → WITHDRAWN | 三者 |
| 系统退款（售后单） | APPLIED → REFUNDING → REFUNDED；分账回退失败停在 REFUNDING 由重试任务接手 | REFUNDED |

---

## §5 关键流程

**F1 下单算价**（所有玩法同一条路）
1. 取商品快照 → `flashPrices`：特价 / 集单价取最低；参团单按团价
2. 集单商品：`PeriodPort.ticketFor` 取期 → 子单写 `period_no` · `arrive_date`
3. `autoDiscount`：满减类同类取最优
4. 券 → 积分
5. 落 `pmt_apply`；活动占量 / 占预算与订单同事务

**F2 集单截单**
1. 任务每分钟：OPEN 且过 `cutoff_at` 的期 → 数已付款份数 → CONFIRMED 或 SHORT（写死 `decide_deadline`）
2. 商家 s20 看汇总、去采购；s33 处理 SHORT
3. SHORT 超时 → CANCELLED → `PeriodOrderPort.refundAll` 逐单系统全额退款

**F3 参团**
1. 买家 s22 点「参团」→ `POST /mp/order {groupNo}` → 团必须 OPEN 且未过期
2. 付款成功回调 → 落成员行（按子单号幂等）→ 够人数 FORMED
3. 到期 `GroupExpireJob` → FAILED → 该团已付款子单逐单系统全额退款

**F4 平台活动**（P3）
1. 运营 s29 建 PLATFORM 活动并发布报名
2. 商家 s27 看到、s28 报名（提交时算定两份最多金额）
3. 运营 s30 审核：带条件 UPDATE 占平台预算，超了拒
4. 活动时间到自动生效；每单 `pmt_apply` 两行、子单两列出资；随统一结算周期结给商家

---

## §6 与现状的差异

| # | 现状 | 本设计 | 批 |
|---|---|---|---|
| D1 | 参团不下单、不付款；成团价从未收过 | 参团 = 带 `groupNo` 下单；付款成功才算成员 | P1 |
| D2 | 团 `end_at` 写死 7 天且无人读 | 取活动配置；任务到期置 FAILED 并退款 | P1（置 FAILED 已写，退款随 D1） |
| D3 | 提货日 = 下单日 | 集单单存 `arrive_date` | P1 |
| D4 | 已付款的单没有任何系统退款入口 | `AfterSaleService#systemRefund`（已写） | P1 |
| D5 | 运营中止团只改状态不退款 | 改走 X5 | P1 |
| D6 | 团与活动无关联 | `mkt_group_buy.activity_no` | P1 |
| D7 | 买家团的价从商品读 | 一律从活动读；商品两列退场 | P2 |
| D8 | 活动编辑无锁定 | A6 | P2 |
| D9 | 两套活动模型同时算价 | 老模型退场 | 并行 |

---

## §7 分批与 AC

| 批 | 内容 | AC |
|---|---|---|
| **P1a** | 集单全链路（期、截单、撤单、退款、提货日）；系统全额退款（D3 · D4）；新建活动两步化 | AC-1 · 2 · 3 · 7 · 8 · 9 · 10 · 11 · 15 |
| **P1b** | 拼团接通下单、付款成功才算成员、到期整单退、团挂活动号；运营中止团改走退款（D1 · D2 · D5 · D6） | AC-5 · 6 |
| P2 | 券界面按新约定；活动编辑锁定；买家团价从活动读 | AC-4 · 12 · 13 |
| P3 | 平台活动（报名、审核、出资、结算）；自己组合（`pmt_activity_rule`） | AC-14 · 16 |

---

## §8 待确认 · 建议方案

### 8.1 参团接通下单：放进 P1，但拆成 P1a / P1b 两个可独立上线的包

| 方案 | 结论 |
|---|---|
| A 放进 P1，集单与拼团一起上 | ❌ 两条链路任一条卡住，另一条也上不了 |
| **B 放进 P1，拆成 P1a 集单、P1b 拼团接通，各自可上线** | ✅ **建议** |
| C 推到 P2 | ❌ P1 做完拼团仍收不到钱；团到期置 FAILED 却没有钱可退，AC-6 验不了 |

理由：两条链路共用的只有「系统全额退款」（D4，已写）与「子单挂实例」这一个形状，
其余互不依赖。先上 P1a：集单是日常经营、价值最直接；P1b 紧随其后。
**P1b 之前，C 端参团按钮先下线**（只保留查看团），避免继续产生「参了团但没付钱」的成员行。

### 8.2 「退差价」：删除，改为下单即收团价、没成团整单退

| 方案 | 结论 |
|---|---|
| A 先按原价收、成团后给每人退差价（现提示的说法） | ❌ 每成一个团就产生 N 笔部分退款，每笔都要先回退分账；买家先看到的是原价，参团意愿更低 |
| **B 下单即按团价收；到期没成团整单全额退** | ✅ **建议**：退款只发生在失败时，且是整单退，与集单取消同一条路径 |

线上 `mkt_group_buy` 2 行、挂团的子单 0 行，没有存量要处理。C 端提示「先买的邻居也退了差价」随 P1b 删掉。

### 8.3 平台活动不属于任何商家：沿用 `pmt_coupon` 的先例，`entity_no` 为空 + 显式 `owner`

| 方案 | 结论 |
|---|---|
| **A `entity_no` 允许为空，另加 `owner = PLATFORM`** | ✅ **建议**：与 `pmt_coupon`（「平台券为空」）同一口径，全仓一种写法 |
| B 填一个固定占位值（如 `PLATFORM`） | ❌ 一个不存在的商家号会被凡是「按商家号查主体」的地方当真，查不到时的表现五花八门 |
| C 平台活动另建一张表 | ❌ 规则、排期、算价要写两份，正是统一模型要消灭的东西 |

读取规则（空锚点在数据域下 fail-closed，对所有带域会话不可见 —— 这正是想要的默认）：

- 商家看平台活动（s27）：专用查询，`executeWithoutScope` + 显式 `owner = PLATFORM`，不经通用列表。
- 买家下单算价：本来就走 Port 且绕域，只加一个 `owner = PLATFORM` 分支。
- 运营端：平台活动是平台级配置，登记进 G4 的「不该登记」说明（与 `sys_*` 配置表同一理由），不做数据域过滤。
- 迁移：`ALTER TABLE pmt_activity MODIFY entity_no VARCHAR(64) DEFAULT NULL` —— 放在 P3，
  且同一条迁移里加 `owner` 列（默认 MERCHANT），存量行字节级不变。
