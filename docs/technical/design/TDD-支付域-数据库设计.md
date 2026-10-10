# TDD-支付域 · 数据库设计

> 状态：**方案待评审** · 创建 2026-08-30
> 决策：[ADR-021](../ADR/ADR-021-支付域独立为服务与独立库.md)（15 张表 · 只存业务键 + 快照列）
> 上游：[架构与拆分路径](./TDD-支付域-架构与拆分路径.md) · [账期与对账放款-方案](./账期与对账放款-方案.md)
> ⚠️ 本文**修正**账期方案 §五 的一处：`settle_cycle` 落在 `mch_payment_merchant`，**不落 `mch_entity`** ——
> 理由见 §3.1（`mch_entity` 留在主库，把账期放那儿等于每次截批都跨库读配置）

---

## L1 · 定位

支付库是一个**只增不改**的账本库：
费率是加版本、状态是加流水、金额一旦落单就不再重算。
它与主库之间**没有外键、没有 JOIN**，只有业务键与快照。

---

## L2 · 一、15 张表与它们的关系

```
                    ┌─ sys_pay_channel ──── sys_pay_channel_rate
                    │        （通道开关）        （费率版本 · 只增不改）
   mch_payment_merchant
      （进件档案：钱打给谁）
                    │
   stl_payment ─────┼───── stl_bill ────── stl_settle_batch 🆕
   （支付流水）      │    （结算单 · 一子单一张）  （账期批次）
                    │           │
   stl_recon_diff ──┘           ├── stl_split_log（分账指令流水 · append-only）
   （对账差异）                  ├── stl_settle_invoice（销项票）
                                └── stl_purchase_invoice（进项票）
   stl_withdraw（过渡账本 · 不是出款路径）
   stl_fee_rule（平台佣金费率 · 只增不改）

   pts_user_account ── pts_user_ledger        stl_points_pool
      （积分账户）        （积分流水）            （积分资金池）

   mch_debt 🆕 ── mch_debt_txn 🆕
      （商家欠款）    （欠款流水）
```

**三张新表**：`stl_settle_batch`（账期批次）、`mch_debt` / `mch_debt_txn`（欠款）。

---

## L2 · 二、新增表

### 2.1 `stl_settle_batch` · 账期批次

没有它，「这个账期对完了没有」无处安放；「这单卡在哪一批」也查不出来。

```sql
CREATE TABLE IF NOT EXISTS stl_settle_batch
(
    id                BIGINT(20)   NOT NULL AUTO_INCREMENT,
    batch_no          VARCHAR(64)  NOT NULL,
    entity_no         VARCHAR(64)  NOT NULL COMMENT '主体业务键。不存 id -- 跨库引用只认业务键',
    pay_channel       VARCHAR(16)  NOT NULL COMMENT '一个主体在不同通道各自成批：账期与费率都按通道走',
    settle_cycle      VARCHAR(16)  NOT NULL COMMENT '本批采用的账期规则快照 T+1/T+N/WEEKLY/SEMI_MONTHLY/MONTHLY',
    -- 三个时点。due_at 是「应该放款的那一天」，released_at 是「实际放了的那一刻」，
    -- 两者分开才答得出「晚了几天、晚在哪一段」
    period_from       BIGINT(20)   NOT NULL COMMENT '收单区间起（含）',
    period_to         BIGINT(20)   NOT NULL COMMENT '收单区间止（不含）',
    due_at            BIGINT(20)   NOT NULL COMMENT 'T3 应结日',
    released_at       BIGINT(20)   DEFAULT NULL COMMENT '实际放行时刻',
    -- 冻结窗口的到期时刻。取本批最早一单的 T0 + 通道冻结窗口 --
    -- 取最早的那一笔而不是平均或最晚：整批一起放，最早的那笔先到期
    freeze_expire_at  BIGINT(20)   DEFAULT NULL COMMENT 'Tmax。到期未放行则本批必然产生 FROZEN_BACK',
    status            VARCHAR(24)  NOT NULL DEFAULT 'DRAFT'
        COMMENT 'DRAFT/COLLECTED/RECONCILING/BLOCKED/RECONCILED/RELEASED',
    bill_count        INT(11)      NOT NULL DEFAULT 0,
    gross_minor       BIGINT(20)   NOT NULL DEFAULT 0 COMMENT '本批结算基数合计（分）',
    net_minor         BIGINT(20)   NOT NULL DEFAULT 0 COMMENT '本批应放款合计（分）',
    -- 对账覆盖面。**过渡期只有 A 侧**，界面据此如实标注，不显示成「已对账」
    recon_scope       VARCHAR(16)  NOT NULL DEFAULT 'SELF_ONLY' COMMENT 'SELF_ONLY 仅我方自查 / BOTH 含对方账单',
    blocked_reason    VARCHAR(512) DEFAULT NULL COMMENT '挂起原因。要能对商家说人话，含具体数字与阈值',
    blocked_at        BIGINT(20)   DEFAULT NULL,
    -- 挂起时限。没有时限的挂起等于永久冻结，而它会以「还在排查」的形式一直存在
    block_expire_at   BIGINT(20)   DEFAULT NULL COMMENT '超时自动放行并告警',
    decided_by        VARCHAR(64)  DEFAULT NULL,
    decide_remark     VARCHAR(512) DEFAULT NULL COMMENT '人工放行/继续挂起都必须写原因',
    tenant_no         VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at        DATETIME     NOT NULL,
    created_by        VARCHAR(64)  DEFAULT NULL,
    updated_at        DATETIME     NOT NULL,
    updated_by        VARCHAR(64)  DEFAULT NULL,
    version           BIGINT(20)   NOT NULL DEFAULT 0,
    deleted           TINYINT(4)   NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_stl_batch_no (batch_no, tenant_no),
    -- 同一主体 × 通道 × 区间只能有一批：重复开批 = 同一笔钱进两批 = 放两次
    UNIQUE KEY uk_stl_batch_period (entity_no, pay_channel, period_from, tenant_no, deleted),
    KEY idx_stl_batch_due (status, due_at),
    KEY idx_stl_batch_freeze (status, freeze_expire_at)
) COMMENT='账期批次：一个主体一个通道一个账期一批';
```

> `uk_stl_batch_period` 是**防重复开批**的那道锁。
> 没有它，截批任务重跑一次就会把同一区间的单分进两批，
> 而两批各自放行 —— 那是给商家打两次钱。

### 2.2 `mch_debt` / `mch_debt_txn` · 商家欠款

**不并进保证金表。** 保证金是商家的钱（平台代管、将来要退还），
欠款是商家欠平台的 —— 方向相反。合在一张表上用正负号表达，
「应退还多少保证金」就永远算不清了，而那是退店结账时要给出的数。

```sql
CREATE TABLE IF NOT EXISTS mch_debt
(
    id             BIGINT(20)  NOT NULL AUTO_INCREMENT,
    entity_no      VARCHAR(64) NOT NULL,
    -- 只记余额，方向单一：欠款只会是 >= 0。负数说明有 bug，不是「预付」
    balance_minor  BIGINT(20)  NOT NULL DEFAULT 0 COMMENT '当前欠款（分），恒 >= 0',
    total_incurred_minor BIGINT(20) NOT NULL DEFAULT 0 COMMENT '累计产生（分），只增',
    total_repaid_minor   BIGINT(20) NOT NULL DEFAULT 0 COMMENT '累计已偿（分），只增',
    last_incurred_at BIGINT(20) DEFAULT NULL,
    tenant_no      VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at     DATETIME    NOT NULL,
    created_by     VARCHAR(64) DEFAULT NULL,
    updated_at     DATETIME    NOT NULL,
    updated_by     VARCHAR(64) DEFAULT NULL,
    version        BIGINT(20)  NOT NULL DEFAULT 0,
    deleted        TINYINT(4)  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mch_debt_entity (entity_no, tenant_no)
) COMMENT='商家欠款账户，一主体一行。与保证金方向相反，不合表';

CREATE TABLE IF NOT EXISTS mch_debt_txn
(
    id             BIGINT(20)  NOT NULL AUTO_INCREMENT,
    txn_no         VARCHAR(64) NOT NULL,
    entity_no      VARCHAR(64) NOT NULL,
    txn_type       VARCHAR(16) NOT NULL
        COMMENT 'INCUR 产生 / OFFSET 货款抵扣 / DEPOSIT 保证金抵扣 / WRITE_OFF 核销',
    -- 有符号：产生为正、偿还为负。靠 txn_type 推方向等于把方向表达两遍
    amount_minor   BIGINT(20)  NOT NULL COMMENT '变动额（分），可为负',
    balance_after_minor BIGINT(20) NOT NULL COMMENT '变动后欠款余额（分）',
    -- 每一笔欠款都要指得出源头。指不出源头的欠款是没法向商家解释的
    source_type    VARCHAR(16) DEFAULT NULL COMMENT 'REFUND 退款追偿 / OTHER',
    source_no      VARCHAR(64) DEFAULT NULL COMMENT '源单号：售后单号 / 结算单号',
    batch_no       VARCHAR(64) DEFAULT NULL COMMENT 'OFFSET 时记从哪一批扣的',
    reason         VARCHAR(512) DEFAULT NULL,
    operator       VARCHAR(64) DEFAULT NULL,
    tenant_no      VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at     DATETIME    NOT NULL,
    created_by     VARCHAR(64) DEFAULT NULL,
    updated_at     DATETIME    NOT NULL,
    updated_by     VARCHAR(64) DEFAULT NULL,
    version        BIGINT(20)  NOT NULL DEFAULT 0,
    deleted        TINYINT(4)  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_mch_debt_txn_no (txn_no, tenant_no),
    -- 同一个源单只能产生一次欠款：售后重投会重复记，而重复记等于凭空多欠
    UNIQUE KEY uk_mch_debt_txn_source (entity_no, source_type, source_no, tenant_no, deleted),
    KEY idx_mch_debt_txn_entity (entity_no, tenant_no)
) COMMENT='欠款流水。只有余额字段的账户是不可审计的';
```

> `uk_mch_debt_txn_source` 与保证金流水的差别在这里：
> 保证金的变动是人发起的（缴纳、扣划），欠款的变动是**事件发起的**（退款追偿），
> 而事件会重投。没有这道唯一键，一次重投就让商家凭空多欠一笔。

---

## L2 · 三、新增列

### 3.1 `mch_payment_merchant` 加账期

```sql
ALTER TABLE mch_payment_merchant
  ADD COLUMN settle_cycle VARCHAR(16) NOT NULL DEFAULT 'T+1'
      COMMENT '本主体在本通道的账期规则。上限受通道 sys_pay_channel.settle_cycle 约束，取更短的那个';
```

**为什么不放 `mch_entity`**（账期方案 §五原本写的位置）：
`mch_entity` 留在主库，而截批任务每次都要读账期 —— 放那儿等于**每次截批都跨库读配置**。
而且账期天然是「主体 × 通道」二维的：一家同时开微信和支付宝，两边的账期可以不同。
`mch_payment_merchant` 正好是这个粒度，而且它本来就要进支付库。

### 3.2 `stl_bill` 加两列

```sql
ALTER TABLE stl_bill
  ADD COLUMN settleable_at BIGINT(20) DEFAULT NULL
      COMMENT 'T2 可结算时刻 = 履约完成 + 售后期。为空表示还不可结算（未履约或售后未闭环）',
  ADD COLUMN batch_no VARCHAR(64) DEFAULT NULL
      COMMENT '归属账期批次。为空 = 还没入批；查「这单卡在哪」全靠它';
ALTER TABLE stl_bill ADD KEY idx_stl_bill_settleable (status, settleable_at);
ALTER TABLE stl_bill ADD KEY idx_stl_bill_batch (batch_no);
```

### 3.3 `stl_bill.status` 加取值 `FROZEN_BACK`

不是新列，是新取值：**冻结窗口到期、资金已自动解冻回商家、佣金收不到**。

它不是异常分支，是[文档说的](./收款与分账-总体逻辑.md)「必然会发生的分支」。
**必须与分账下发同批上线** —— 分账一接通这个分支就开始有流量，
而那时没有任何地方会报警。

---

## L2 · 四、跨库引用：逐字段定

规则见 [ADR-021 §3.3](../ADR/ADR-021-支付域独立为服务与独立库.md)。落到字段：

| 字段 | 存哪 | 快照 / 回查 | 理由 |
|---|---|---|---|
| `entity_no` `order_no` `sub_order_no` `store_no` | 支付库 | 业务键 | 跨库引用只认业务键，不认自增 id |
| 商家名 | 支付库（快照列） | **快照** | 商家改了名，去年那张单该显示当时那个名字 |
| 主体形态 `legal_form` | 支付库（快照列） | **快照** | 它决定费率档，改了不能重算历史 |
| 收款号 `pay_merchant_no` | 支付库（快照列） | **快照** | 改号之后未打的历史流水仍要打进当初那个账户 |
| 经营模式 `business_mode` | 支付库（快照列） | **快照** | 它决定这张单走哪条状态机 |
| 资金路径 `funds_mode` | 支付库（快照列） | **快照** | 决定要不要补差 |
| 费率 `commission_rate` `channel_fee_rate` | 支付库（快照列） | **快照** | 「当时按什么算的」必须答得出 |
| 「这家现在能不能收钱」 | 主库 | **回查** | 判断要用最新事实 |
| 商家头像 / 联系方式 | 主库 | **回查** | 展示，不进账 |
| 主体是否封禁 | 主库 | **回查** | 放款前判，必须是最新的 |

**混用是必然的，所以逐字段写清楚。**
不写清楚的后果不是报错 —— 是某一天有人「顺手改成实时查」，
历史账从那天起开始跟着主库变，而**没有任何测试会红**。

### 快照陈旧的处理

不做定期刷新（那等于放弃快照的意义），**在界面上说清楚**：
`主体名（成交时）`。这一条要进 UI 规范，否则运营会以为那是当前名字。

---

## L3 · 五、迁移路径（阶段 2 怎么搬）

```
① 建库与建表      在新库跑一遍 db/pay/V1__baseline.sql（从主库现有 DDL 导出）
② 双写观察        暂不双写 —— 见下方「为什么不双写」
③ 停机搬迁        低峰期停支付相关写入，导数据，切数据源，验对账恒等式
④ 主库清理        主库那 15 张表**改名保留**（_archived 后缀），不 DROP
```

### 为什么不双写

双写要解决「两边不一致时以谁为准」，而这恰恰是**资金数据最不能有的状态**。
支付域的写入量不大（结算单按子单生成，不是高频），
一次低峰期停机的代价远小于双写期间任何一次不一致。

### 为什么主库的表改名而不删

删了之后如果发现漏迁一处读取，**症状是查不到数据而不是报错** ——
某个报表变成空的，而没有人立刻知道。改名保留则会直接抛表不存在，
在第一次被读到时就暴露。**保留期建议一个完整账期 + 一次月结**。

---

## L4 · 六、约束与不变式

| # | 不变式 | 靠什么保证 |
|---|---|---|
| 1 | 一子单一张结算单 | `uk_sub_order_no` 唯一索引 |
| 2 | 一主体一通道一区间一批 | `uk_stl_batch_period` |
| 3 | 一源单一笔欠款 | `uk_mch_debt_txn_source` |
| 4 | 欠款余额恒 >= 0 | 应用层 + 对账轴（负数说明有 bug，不是「预付」） |
| 5 | `基数 = 佣金 + 服务费 + 积分费 + 手续费 + 实得` | 门 1 自查 |
| 6 | 池子余额 == 流通积分 × 汇率 + 已扣未兑付 | 第四对账轴 |
| 7 | 费率只增不改 | 无 UPDATE 路径 + 只增接口 |
| 8 | `SPLIT → SPLIT_CONFIRMED` 只由回执推进 | 无其他调用方（含运营端） |

---

## L4 · 七、待确认

1. 通道冻结窗口 `Tmax`（决定 `freeze_expire_at` 怎么算）
2. 挂起时限 `block_expire_at` 定多久（要与运营处置能力对齐）
3. 欠款核销（`WRITE_OFF`）的审批流与止损线
4. 支付库的备份策略、保留期限、写权限（审计）
5. 主库归档表的保留期（建议一个完整账期 + 一次月结）
