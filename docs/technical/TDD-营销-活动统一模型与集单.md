# TDD · 营销：活动统一模型与社区集单

> 状态：**P1a · P1b 已实现** · 2026-09-19（集单 + 拼团接通下单）
> 档位：**2**（新表族 `pmt_period` · 跨 B/C 端 · 改订单与履约口径）
> 关联需求：[PRD-营销-活动统一模型](../requirements/PRD-营销-活动统一模型.md)
> 决策：[ADR-024 拼团与社区集单并存](ADR/ADR-024-拼团与社区集单并存.md)
> 原型：[营销 · 活动统一模型原型](https://claude.ai/artifact/EeKjhCyJ9P3i5iDVNbhUPt)（`sNN` 指其中一屏）
> 概念：[营销域-概念对齐](design/营销域-概念对齐.md) · [营销域-活动统一模型](design/营销域-活动统一模型.md)
> **上位设计**：[TDD-营销域-详细设计](TDD-营销域-详细设计.md)（六个聚合的领域对象、全部表与三端 API）。本文是其中 P1 的实施切片
> 进度：半成品在分支 `wip/marketing-period-p1`（能编译、未接下单、无测试），等上位设计确认后续写

---

## §0 对账一 · 需求 → 设计

| AC | 需求（一句话） | 落点 | 批 |
|---|---|---|---|
| AC-1 | 新建两步，玩法是字段，满减与拼团同一张表 | b-app `activity-edit` 重排；`ActivityDraft` 不变 | P1 |
| AC-2 | 长期活动须有份数或预算 | `ActivityServiceImpl#save` 既有校验（`alwaysOnNeedsCap`），补测试 | P1 |
| AC-3 | 同一单互斥取最优；券在活动之后 | `ActivityPricingServiceImpl#autoDiscount` 既有逻辑，补测试 | P1 |
| AC-4 | 进行中只能改结束时间与上限 | `ActivityServiceImpl#save` 新增 RUNNING 锁定校验 · 错误码 `ACTIVITY_RULE_LOCKED` | P2 |
| AC-5 | 结束活动不影响已开的团 | `ActivityServiceImpl#setStatus` 不触碰 `mkt_group_buy`；补测试钉住 | P1 |
| AC-6 | 拼团按人计数；到期未成团自动退款 | **新增** `GroupExpireJob` + `GroupService#expireOverdue`；`end_at` 改由活动规则算 | P1 |
| AC-7 | 集单截单前可取消，之后不可 | `OrderServiceImpl#cancel` 调 `PeriodPort#isCutOff` · 错误码 `PERIOD_CUT_OFF` | P1 |
| AC-8 | 提货日取活动规则，不取下单日 | 新列 `ord_sub_order.arrive_date`；`FulfillmentStatsPort` 取 `COALESCE(arrive_date, 下单日)` | P1 |
| AC-9 | 截单后按商品、按自提点汇总 | `PeriodService#summary`（现算，不存计数） | P1 |
| AC-10 | 未达起订：商家决定，超过活动配置的时限自动取消退款 | `pmt_activity.decide_hours` → `pmt_period.decide_deadline`；`PeriodCutoffJob` | P1 |
| AC-11 | 预售中的 SKU 不能进集单 | `ActivityServiceImpl#save` 经 `ProductPort#presaleSkus` 拦 · `GOODS_IN_PRESALE` | P1 |
| AC-12 | 券保存前显示最多支出；折扣券须封顶 | `PmtCouponServiceImpl` 既有预算前置（[TDD-营销预算前置](design/TDD-营销预算前置.md)），界面改版 | P2 |
| AC-13 | 发券跳过的人分项计数 | `CouponAllocServiceImpl` 既有 `skipped` 分项，界面改版 | P2 |
| AC-14 | 平台活动超预算不能通过 | **另起** TDD（P3） | P3 |
| AC-15 | 首页集单排团前，异常用黄标 | `BizDashboardController` 增 `periodToday` 块 | P1 |
| AC-16 | 平台出资随统一结算周期 | 复用 `ord_sub_order.discount_platform` → `SettleSourcePortImpl`（§2.6） | P3 |

**孤立项**

- 没落点的 AC：AC-14 本文只划边界（§2.6），不设计。
- 挂不上 AC 的设计：`GroupExpireJob` 修的是**既有缺陷**（§1.3 第 ①），PRD AC-6 恰好覆盖，不算超范围。

---

## §1 现状与影响面（2026-09-18 读代码、查线上得到）

### 1.1 已经有的，直接复用

| 东西 | 在哪 | 说明 |
|---|---|---|
| 活动模型 | `pmt_activity`（V242）· `PmtActivity` | 触发 × 优惠两列 + 排期 + 限量；已有 `TRIGGER_GROUP` |
| 周期排期 | `RecurringRule` · `schedule_rule` JSON | 集单的「每天」直接用它 |
| 范围 / 受众 | `pmt_activity_goods` · `pmt_activity_audience` | 不动 |
| 算价 | `ActivityPricingServiceImpl`：`autoDiscount`（CUT，同类取最优）· `flashPrices`（PRICE，取最低）· `giftRules` | 集单价走 `flashPrices` |
| 团规则读取 | `GroupRulePortImpl#activeRuleFor` | 市场时区 `Asia/Shanghai`；绕数据域靠显式 `entityNo` |
| 团实体 | `mkt_group_buy`（`min_count` · `end_at` · `pickup_no` · `initiator_user_no`）· `mkt_group_member` | 不迁、不改名 |
| 订单挂团 | `ord_sub_order.group_no` | 集单照此加 `period_no` |
| 履约批次 | `ful_batch`（`pickup_no × arrive_date`）· `DispatchServiceImpl` | 按 `pickupNo|arriveDate` 幂等建批 |
| 关单任务 | `OrderAutoCloseJob` → `OrderService#closeExpiredOrders` | 集单与团的定时任务照它的形状写 |

### 1.2 线上实况（只读查询）

| 表 | 行数 |
|---|---:|
| `pmt_activity` | 1（一条团购） |
| `mkt_group_buy` | 2 |
| `ord_sub_order.group_no` 非空 | 0 |
| `ful_batch` | 0 |
| `prd_sku.presale_quota > 0` | 0 |

**没有存量要迁**：加列用可空列，新表从零开始。

### 1.3 读代码发现的三处缺陷（本 TDD 一并修）

| # | 缺陷 | 证据 | 后果 |
|---|---|---|---|
| ① | **到期的团没有任何处理** | `mkt_group_buy.end_at` 只被写（`GroupServiceImpl:428`、`:1103`），全仓没有任何任务或查询按它把团置为 `FAILED` | 凑不齐的团永远 `OPEN`，已付款的人永远等着，钱不退 |
| ② | 成团时限写死 7 天 | 同上两行：`now + Duration.ofDays(7)` | 活动里配的时限不生效 |
| ③ | 提货日取下单日 | `FulfillmentStatsPort#pickupDays` 的注释：「到货日一期取下单日」 | 集单「今天下单、明天提货」会被算进今天的批次，分拣单与自提点看板全错一天 |

另：`abortGroup` 只改状态、不退款（它自己的注释写明「改状态不会把钱退给任何人」），运营中止团后要另走售后。本 TDD 的 `expireOverdue` 与集单取消**都要真退款**，不能照抄它。

### 1.4 会被改到的已在跑的功能

- 下单：`OrderServiceImpl`（集单商品写 `period_no` / `arrive_date`；截单后下单进下一期）
- 取消：`OrderServiceImpl#cancel`（集单截单后拒绝）
- 履约看板：`FulfillmentStatsPort` 实现（到货日口径）
- 开团：`GroupServiceImpl#createMerchantGroup` / `createGroupBuy`（`end_at` 取规则）
- 算价：`ActivityPricingServiceImpl#flashPrices`（认 `CUTOFF × PRICE`）；`CampaignPortRouter` 取最低价的合并**不变**

### 1.5 明确不受影响

- 券的数据模型与发放逻辑（P2 只改界面）
- 老模型 `mkt_campaign`（退场另见 [TDD-活动与营销活动的合并](design/TDD-活动与营销活动的合并.md)）
- 求团报价 `mkt_quote` / 需求单池
- 快递、预约履约

---

## §2 方案

### 2.1 模型：集单是一种触发，期是它的实例

```
pmt_activity  (trigger=CUTOFF, benefit=PRICE)      ← 规则：哪些货、几点截单、哪天提货、集单价
      │ 1 : N
pmt_period    (activity_no, period_date)           ← 实例：某一天那一期，状态机在这里
      │ 1 : N
ord_sub_order (period_no, arrive_date)             ← 订单挂到期上
      │ N : 1（按 pickup_no × arrive_date）
ful_batch                                          ← 履约批次，不改结构
```

与拼团对照：`TRIGGER_GROUP → mkt_group_buy → ord_sub_order.group_no`，同一个形状。

### 2.2 契约变更

**库表**（迁移 `V335__batch_sale_period.sql`，号在提交前再核一次 —— 并行会话常撞号）

```sql
-- 集单规则：只对 trigger_type = 'CUTOFF' 有意义，其余玩法全为 NULL
ALTER TABLE pmt_activity
    ADD COLUMN cutoff_time   VARCHAR(5)  DEFAULT NULL COMMENT 'HH:mm，市场时区。每期截单时刻',
    ADD COLUMN pickup_offset INT(11)     DEFAULT NULL COMMENT '提货日 = 截单日 + N 天',
    ADD COLUMN pickup_from   VARCHAR(5)  DEFAULT NULL COMMENT 'HH:mm，提货日几点起可取',
    ADD COLUMN min_qty       INT(11)     DEFAULT NULL COMMENT '起订量（份）。NULL = 不设',
    ADD COLUMN period_quota  INT(11)     DEFAULT NULL COMMENT '每期份数上限。NULL = 不限',
    ADD COLUMN decide_hours  INT(11)     DEFAULT NULL COMMENT '集单未达起订量时商家的处理时限（小时）。NULL = 取配置默认',
    ADD COLUMN group_hours   INT(11)     DEFAULT NULL COMMENT '拼团成团时限（小时）。NULL = 24';

CREATE TABLE IF NOT EXISTS pmt_period (
    id              BIGINT(20)  NOT NULL AUTO_INCREMENT,
    period_no       VARCHAR(64) NOT NULL,
    activity_no     VARCHAR(64) NOT NULL,
    entity_no       VARCHAR(64) NOT NULL,
    period_date     VARCHAR(10) NOT NULL COMMENT 'YYYY-MM-DD，截单日（市场时区）',
    cutoff_at       BIGINT(20)  NOT NULL COMMENT '截单时刻；提前截单时改写',
    pickup_date     VARCHAR(10) NOT NULL COMMENT 'YYYY-MM-DD，写进订单的 arrive_date',
    status          VARCHAR(16) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/SHORT/CONFIRMED/CANCELLED',
    decide_deadline BIGINT(20)  DEFAULT NULL COMMENT 'SHORT 时：过了这个点未处理即自动取消',
    decided_by      VARCHAR(64) DEFAULT NULL,
    decided_at      BIGINT(20)  DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL, created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL, updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0, deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pmt_period_no (period_no),
    UNIQUE KEY uk_pmt_period_day (tenant_no, activity_no, period_date),
    KEY idx_pmt_period_due (status, cutoff_at)
) COMMENT='集单的一期。份数与金额不存，从订单现算';

ALTER TABLE ord_sub_order
    ADD COLUMN period_no   VARCHAR(64) DEFAULT NULL COMMENT '集单下单时的期号',
    ADD COLUMN arrive_date VARCHAR(10) DEFAULT NULL COMMENT '提货日。NULL = 沿用下单日（非集单单）';
```

- **份数不存**：与 `FulfillmentStatsPort` 同一条原则 —— 存一份计数，迟早「总览 86、点进去 85」。
- 建表收尾 `) COMMENT=...;` 单行（生成器按这个解析）；不写 `ENGINE/CHARSET/COLLATE`，跟随库默认。
- 加列要同步补实体字段：`PmtActivity` 七个、`OrdSubOrder` 两个；跑 entity-alignment 守卫。

**常量**：`PmtActivity.TRIGGER_CUTOFF = "CUTOFF"`；`PmtPeriod` 四个状态常量。

**端点**（B 端，权限 `biz:campaign`）

| 方法 | 路径 | 原型 | 说明 |
|---|---|---|---|
| GET | `/biz/period?status=` | s31 | 按状态列期 |
| GET | `/biz/period/{no}` | s20 · s33 | 期详情 + 按商品 / 按自提点汇总 |
| POST | `/biz/period/{no}/cutoff` | s20 | 提前截单（只能从 OPEN） |
| POST | `/biz/period/{no}/decision` | s33 | `{action: CANCEL \| PROCEED}`，只能从 SHORT |
| GET | `/biz/period/{no}/purchase-lines` | s20 | 按 SKU 汇总，给进销存进货单预填 |

新增 `/biz` 端点要走七处登记（判权表、两份白名单、生成产物），pre-push 才报 —— 提交前跑一次 `check-shared-guards`。

**C 端**：不加端点。商品详情 `GoodsVO` 增一个可空块 `batchSale {cutoffAt, pickupDate, pickupFrom, orderedQty}`（s26）；订单详情 VO 增 `arriveDate`、`cancellableUntil`（s37）。

**错误码 + 三语文案**（`BackendI18nParityTest` 会查）

| 码 | 何时 |
|---|---|
| `PERIOD_CUT_OFF` | 截单后取消集单订单 |
| `PERIOD_STATE_CONFLICT` | 对非 OPEN 的期提前截单、对非 SHORT 的期做决定 |
| `ACTIVITY_RULE_LOCKED` | 进行中的活动改了规则字段（P2） |
| `GOODS_IN_PRESALE` | 集单活动选了预售中的 SKU |

**数据域**：`pmt_period` 按 `entity_no` 登记 MERCHANT 维度（`DataScopeRegistration`）。
C 端下单、定时任务读它时经 `PeriodPort` 并 `executeWithoutScope`，边界靠显式 `entityNo` / `activityNo` 条件 ——
与 `GroupRulePortImpl` 同一做法。**不登记会过度可见，登记了不绕会 fail-closed 成空页**，两头都零报错，用例必须走真 HTTP。

**配置项**：`shop.period.decide-hours`（默认 14）—— **只是缺省值**：活动上 `decide_hours` 非空时以活动为准（店主 2026-09-18 定：每个活动单独配置）。
进入 SHORT 那一刻把 `decide_deadline = 截单时刻 + 时限` 写进期，之后改活动不影响已进入 SHORT 的期。

### 2.3 状态机

```
            截单时刻到（任务）或 提前截单（商家）
  OPEN ──────────────────────────────────────────┐
                                                  ├─ 份数 ≥ 起订量（或未设） ──→ CONFIRMED
                                                  └─ 份数 < 起订量 ──→ SHORT
  SHORT ── 商家「照常发货」 ──→ CONFIRMED
  SHORT ── 商家「取消本期」或 超过 decide_deadline ──→ CANCELLED（逐单全额退款）
```

- **截单判定看订单创建时间与 `cutoff_at`，不看任务跑没跑到**：19:59:59 下的单属于今天，哪怕任务 20:00:30 才扫到。
- **截单后下的单进下一期**：下单时按「当前时刻所属的期」get-or-create（唯一键 `activity_no × period_date` 保证并发只建一次）。
- `CANCELLED` 的退款走 `OrderService` 既有的已支付退款路径（§4 风险 R3）。

### 2.4 模块设计

| 动作 | 路径 | 说明 |
|---|---|---|
| 新增 | `backend/shop-app/.../db/migration/V335__batch_sale_period.sql` | §2.2 |
| 新增 | `promotion/entity/PmtPeriod.java` · `promotion/mapper/PeriodMapper` | |
| 修改 | `promotion/entity/PmtActivity.java` | `TRIGGER_CUTOFF` + 六个字段 |
| 新增 | `promotion/service/PeriodService.java` + `impl/PeriodServiceImpl.java` | 列表、详情汇总、提前截单、决定、截单推进、到期取消 |
| 新增 | `promotion/api/biz/BizPeriodController.java` | §2.2 五个端点 |
| 新增 | `promotion/job/PeriodCutoffJob.java` | 每分钟：OPEN 且过点 → 推进；SHORT 且过 deadline → 取消 |
| 新增 | `shop-base/.../spi/promotion/PeriodPort.java` + `promotion/port/PeriodPortImpl.java` | 下单取期、判是否已截单；trade 只经此 Port |
| 修改 | `promotion/service/impl/ActivityServiceImpl.java` | 保存 CUTOFF 校验（截单时刻、提货偏移）、预售冲突、P2 的运行中锁定 |
| 修改 | `promotion/service/impl/ActivityPricingServiceImpl.java` | `flashPrices` 认 `CUTOFF × PRICE` |
| 修改 | `shop-base/.../spi/product/ProductPort`（或新 `PresalePort`） | `presaleSkus(entityNo, skuNos)` |
| 修改 | `trade/service/impl/OrderServiceImpl.java` | 下单写 `period_no` / `arrive_date`；取消前问 `PeriodPort` |
| 修改 | `trade/entity/OrdSubOrder.java` | 两个字段 |
| 修改 | `FulfillmentStatsPort` 的实现 | 到货日 `COALESCE(arrive_date, 下单日)` |
| 修改 | `marketing/group/impl/GroupServiceImpl.java` | `end_at` = 开团时刻 + 活动 `group_hours`（缺省 24）；新增 `expireOverdue(now)`（置 FAILED + 退款） |
| 新增 | `marketing/group/job/GroupExpireJob.java` | 每分钟调 `expireOverdue` |
| 修改 | `shop-app/.../config/DataScopeRegistration.java` | 登记 `pmt_period` |
| 修改 | `shop-app/.../portal/biz/BizDashboardController.java` | `periodToday` 块（s01） |
| 修改 | 三语 `messages*.properties` · `ErrorCode.java` | 四个码 |
| 修改 | `packages/shared` 类型与跨域登记表 · `b-app/src/api` · `c-app/src/api` | 契约同步，跑生成器 |
| 新增/修改 | b-app `pages/activity-edit`（两步）· 新页 `periods` / `period` · c-app `goods` / `order` | 界面按原型；改完重跑 `gen-ui-catalog.py` |

### 2.5 关键接口

```java
public interface PeriodPort {
    /** 下单时：这件货此刻属于哪一期；不是集单商品返回 empty。会按需建期。 */
    Optional<PeriodTicket> ticketFor(String entityNo, String goodsNo, long orderAt);
    /** 取消订单前：这一期截单了没有。 */
    boolean isCutOff(String periodNo, long now);

    record PeriodTicket(String periodNo, String activityNo, String pickupDate, long cutoffAt) {}
}

public interface PeriodService {
    List<PeriodVO> list(String entityNo, String status);
    PeriodDetailVO detail(String entityNo, String periodNo);          // 含 byGoods / byPickup
    PeriodVO cutoffNow(String entityNo, String periodNo, String operatorNo);
    PeriodVO decide(String entityNo, String periodNo, Decision action, String operatorNo);
    int advanceDue(long now);        // OPEN → CONFIRMED / SHORT
    int cancelUndecided(long now);   // SHORT 过期 → CANCELLED + 退款
    List<PurchaseLineVO> purchaseLines(String entityNo, String periodNo);
}
```

### 2.6 P2 / P3 的边界（本文不展开设计）

- **P2 活动编辑锁定**：RUNNING 时允许改的字段白名单 = `end_at`、`quota`、`budget_minor`、`period_quota`；其余字段与库里不同即拒。
- **P2 券改版**：只动界面；数据与接口不变。
- **P3 平台活动**：需要新表（报名单）、出资分摊落到 `pmt_apply`。**结算口径已定：统一结算周期**（PRD §4.5.5）——
  不新建打款通道，平台出资写进子单既有的 `discount_platform`，`SettleSourcePortImpl` 已把它带进结算源（`:285`），
  商家在常规结算单里看到这一项。另起 TDD 时要核的只是「商家实收」公式是否已把 `discount_platform` 当作平台应付。
- **「自己组合」（s11）**：一条活动多条件、多利益，现有两列存不下。**P3 与平台活动一起做**，届时把条件与利益拆成子表；P1 在玩法面板里不放这一项。

---

## §3 选型

### 3.1 集单的「期」放在哪

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| **A 新表 `pmt_period`** | 状态机干净（OPEN/SHORT/CONFIRMED/CANCELLED）；与团同构；份数现算 | 多一张表、多一个 Port | ✅ 采用 |
| B 复用 `mkt_group_buy`（`min_count` 当起订量） | 不加表 | 团按**人**计数、有发起人与成员表、`FAILED` 语义是「没凑齐人」；硬塞进去，凡按 `min_count` 判断的地方都会把集单当成团 —— 新值漏进老分支，零报错 | ❌ |
| C 复用 SKU 预售（`prd_sku.cutoff_at` 等） | 截单与到货日字段现成 | 挂在 SKU 上、**一次性**，没有「每天一期」；一个活动多件货要各写一遍；线上 0 行说明也没人在用 | ❌ 只拿来做互斥校验（AC-11） |

### 3.2 条件与利益怎么存

| 方案 | 结论 |
|---|---|
| **A 保留「触发 × 优惠」两列，加 `CUTOFF`** | ✅ P1 采用：九个玩法都是单条件 × 单利益 |
| B 现在就拆成条件 / 利益子表 | ❌ 推迟到 P3：只有「自己组合」与平台活动需要，现在拆会把 `ActivityPricingServiceImpl` 整个重写，而线上只有 1 条活动在用它 |

### 3.3 提货日从哪来

| 方案 | 结论 |
|---|---|
| **A 订单上存 `arrive_date`** | ✅ 履约只读订单，不依赖营销域；非集单单为 NULL，口径不变 |
| B 履约按 `period_no` 反查期 | ❌ 履约 → 营销的反向依赖，`ArchitectureTest` 的域间规则会拦 |

---

## §4 风险

| # | 风险 | 影响 | 缓解 |
|---|---|---|---|
| R1 | 迁移号撞车 | 本地不报、上线起不来 | 提交前 `ls` 再核；撞了改号后 `clean package` |
| R2 | `pmt_period` 数据域 | 不登记 → 过度可见；登记不绕 → C 端下单与任务查成空 | 登记 + Port 内 `executeWithoutScope`；用例走真 HTTP（`OpsDataScopeFlowTest` 的形状） |
| R3 | 批量退款 | 集单取消与团到期都要退已支付订单；`abortGroup` 的先例是「只改状态不退」 | 先核 `OrderService` 已支付取消的退款路径能否批量、是否幂等；不能则 P1 前补齐，**不允许只改状态** |
| R4 | 截单边界并发 | 19:59:59 的单被算到哪一期 | 判定只看订单时间与 `cutoff_at`；期的唯一键兜底并发建期 |
| R5 | 时区 | 服务器时区下「今天 20:00」可能是明天 | 全部按 `Asia/Shanghai`，与 `GroupRulePortImpl.ZONE` 同一常量 |
| R6 | 履约口径变更 | `FulfillmentStatsPort` 的看板与分拣单一起变 | 非集单单 `arrive_date` 为 NULL，行为字节级不变；补一条「非集单单仍按下单日」的用例 |
| R7 | 与老模型并存 | `CampaignPortRouter` 对特价取最低；老模型若也给同 SKU 特价，集单价可能被压低 | 线上 `mkt_campaign` 0 行；老模型退场前在 `ActivityServiceImpl#save` 提示冲突 |

---

## §5 对账三 · 实现 → 需求（测试）

实现后填「跑过」与「消融」两列。**消融那一列不是可选的**：撤掉实现那一行，对应测试必须变红。

| AC | 测试方法 | 跑过 | 消融 |
|---|---|---|---|
| AC-2 | `ActivityServiceTest#alwaysOn_withoutQuotaOrBudget_rejected` | | |
| AC-3 | `ActivityPricingServiceTest#twoCuts_takesLarger_notSum` | | |
| AC-5 | `GroupFlowTest#endingActivity_keepsOpenGroups` | | |
| AC-6 | `GroupFlowTest#overdueGroup_failsAndRefunds` · `#endAt_followsActivityHours` | | |
| AC-7 | `PeriodFlowTest#cancelBeforeCutoff_ok_afterCutoff_rejected` | | |
| AC-8 | `PeriodFlowTest#arriveDate_isPickupDate` · `FulfillmentStatsTest#nonPeriodOrder_keepsOrderDate` | | |
| AC-9 | `PeriodFlowTest#summary_matchesOrderLines` | | |
| AC-10 | `PeriodFlowTest#short_merchantProceeds` · `#short_undecided_autoCancelsAndRefunds` | | |
| AC-11 | `ActivityServiceTest#cutoff_withPresaleSku_rejected` | | |
| 数据域 | `OpsDataScopeFlowTest` 同形：`/biz/period` 跨商家不可见；C 端下单能取到期 | | |
| 截单边界 | `PeriodFlowTest#orderAt_cutoffMinus1s_staysToday` · `#afterCutoff_goesNextPeriod` | | |

AC-1、AC-15 是界面：`vue-tsc` + 按原型在 mock 下自查截图。AC-4、AC-12、AC-13 属 P2；AC-14 属 P3。

---

## §6 对账二 · 设计 → 实现（P1a，2026-09-18）

§2.4 的模块全部落地，另多出四处（见 §7）：

| §2.4 条目 | 实际落点 | 状态 |
|---|---|---|
| V335 迁移 | `V335__batch_sale_period.sql`（号已核，334 之后） | ✅ |
| PmtPeriod / PeriodMapper / PmtActivity 七列 / OrdSubOrder 两列 | 同名 | ✅ |
| PeriodService + Impl · PeriodCutoffJob | 同名；`advanceDue` / `cancelUndecided` 带状态条件更新 | ✅ |
| BizPeriodController | 同名，路径改单数 `/biz/period*`（§7-1） | ✅ |
| PeriodPort + Impl | 同名，另加只读的 `openUntil` / `viewFor`（§7-2） | ✅ |
| PeriodOrderPort + Impl（trade） | 同名 | ✅ |
| ActivityServiceImpl 集单校验 + 预售互斥 | 同名；`GoodsQueryPort#presaleGoods` | ✅ |
| ActivityPricingServiceImpl | **未改**：`CUTOFF × PRICE` 本来就走 `flashPrices`（只排除 GROUP），用例钉住 | ✅ |
| OrderServiceImpl：下单挂期 / 截单前撤单 | `createFor` 落库前取票；`cancel` 对已付款集单单走 `cancelPaidPeriodOrder` | ✅ |
| FulfillmentStatsPort 到货日 | `FulfillmentStatsPortImpl#dayOf` | ✅ |
| GroupServiceImpl end_at 取规则 · GroupExpireJob | 同名；**到期只置 FAILED、不退款**（§7-3） | ✅ |
| DataScopeRegistration | 登记 `pmt_period`（MERCHANT） | ✅ |
| BizDashboardController periodToday | 改为独立的 `GET /biz/marketing/summary`（§7-4） | ✅ |
| 错误码 + 三语 | 40024–40028 | ✅ |
| 共享类型 · 端上契约 · 生成物 | `store.ts` / `trade.ts` · b-app / c-app endpoints-contract-http-mocks · 24 个生成器 | ✅ |
| 界面 | b-app 营销 / 活动列表 / 新建活动（两步）/ 集单列表 / 一期；c-app 商品详情集单块 / 订单详情 | ✅ |

测试：`PeriodFlowTest` 12 条全走真 HTTP 下单付款；五处消融（挂期、到货日、截单判定、取消退款、看板日期）各自变红，已还原。

## §6b 对账二 · 设计 → 实现（P1b 拼团接通下单，2026-09-19）

| 设计条目（详细设计 §1.4 · §2.4 · F3 · D1/D2/D5/D6） | 实际落点 | 状态 |
|---|---|---|
| V336：团挂活动号、成员挂子单号 + 唯一键 | `V336__group_order_link.sql`；另加 `idx_sub_order_group`（退款按团号找子单） | ✅ |
| 下单认 `groupNo` / `openGroup`，按团价算价，子单写 `group_no` | `CreateOrderReq` / `CreateOrderCommand` 两字段；`OrderServiceImpl#groupQuoteOf` + `repriced`（预览与下单同一处）；落库前 `GroupJoinPort#bind` | ✅ |
| 付款成功才落成员（按子单号幂等），够人数 FORMED | `markPaid` → `GroupJoinPort#onPaid`：带状态条件的 `UPDATE` 占人数、再插成员；团已散则提交后系统退款 | ✅ |
| 到期 / 散团 / 中止 → 逐单退款 | `GroupServiceImpl#expireOverdue` / `dissolve` / `abortGroup` / `setGroupStatus(FAILED)` → `GroupOrderPort#refundAll`；近 3 天失败的团补扫 | ✅ |
| 团时限取活动 | 商家团与买家团都取 `GroupRulePort` 的 `groupHours`；买家团的价与人数也改从活动取（D7 的买家团一半提前做了） | ✅ |
| B 端团详情 / 散团 / 开团选活动与自提点 | `GET /biz/group/{groupNo}`、`POST /biz/group/{groupNo}/dissolve`、`POST /biz/groups {goodsNo, activityNo, pickupNo}`、`GET /biz/group/pickups`；页面 s09 / s10 / s34 | ✅ |
| C 端参团改为下单 | 商品详情拼团块 `GET /mp/goods/{goodsNo}/group`（s21）；团页（s22）参团 → 结算带 `groupNo`；商品页开团 → 结算带 `openGroup` | ✅ |
| 旧版 `join` 返回「请升级」 | `GROUP_JOIN_NEEDS_UPGRADE`（40033），仍要求登录 | ✅ |

测试：`GroupOrderFlowTest` 10 条全走真 HTTP（下单、付款回调、查团）；消融：下单即加成员 → 3 条红；到期不退款 → 1 条红。已还原。

**P1b 的偏差**：
- 路径改单数 `/biz/group/{groupNo}`（设计写 `/biz/groups/{no}`），理由同 §7-1；列表与开团沿用存量的 `/biz/groups`。
- 多了 `GET /biz/group/pickups`：开团页要列自提点，而门店送货方式端点要门店权限，开团是营销权限。
- **已成团的团仍收付款**：两个人为最后一个名额同时下单，后付的不退，团价照给、人数照加（`onPaid` 对 FORMED 也加人）。
  下单那一刻仍只认 OPEN（成了就不再接新单）。
- **成团按人算**：同一人在同一团付第二单，货照发、人数不变。
- 团限定自提点时，只有 `NEIGHBOR_PICKUP` 的参团单会被改到团的点；其余履约方式照常。
- MySQL 单表 `UPDATE` 从左往右求值：占人数那条语句 `status` 写在 `joined_count` 前面，否则生产上差一人就成团，而 H2 全绿（代码注释里写着）。

---

## §7 偏差说明

1. **端点改单数 `/biz/period*`**。设计写的是 `/biz/periods`，`api-path-naming` 守卫要求 /biz 单数。
2. **C 端集单块是独立端点 `GET /mp/goods/{goodsNo}/batch`**，没有塞进 `GoodsVO`：
   那个 record 在列表、详情、购物车三处构造，多一个要查库的块会让列表变成 N+1。只读、不建期。
3. ~~团到期只置 FAILED、不退款~~ → **P1b 已补**（见 §6b）：参团接到下单上之后，到期 / 散团 / 中止都逐张退款。
4. **营销首页的数字是独立端点 `/biz/marketing/summary`**，不是工作台上的一块；团的两个数在门户层拼
   （promotion 不直接依赖 marketing 域，`ArchitectureTest` 拦）。
5. **玩法模板只放后端算得出的八种**：满减、满件减、立减、新客立减、特价、买赠、拼团、社区集单。
   原型里的「折扣」「第二件」需要按比例的优惠（`PERCENT`），活动模型今天没有；「秒杀」就是限量的特价。三者随 P3 补。
6. **多规格商品吃不到集单价**：`GoodsQueryPortImpl#snapshot` 对多 SKU 商品不套商品级活动价（防「20 斤装被拉成 10 斤装的价」）。
   集单价同样受此限制 —— 集单请用单规格商品，或 P2 把活动价下沉到 SKU 级。
7. **一张子单里有两个集单活动的货会被拒**（`PERIOD_MIXED`）：一单只能挂一期，否则其中一期的汇总少掉这几件。
8. **期取消时未付款的子单不处理**：它们由超时关单关掉；截单后才付款的单由 `cancelUndecided` 补扫近 3 天已取消的期退掉。


## §8 C06 拼团、集单与求团App正式接线（2026-11-10）

### §0 范围

按用户继续C06零售App补齐，库存最后。本批复用已有拼团/集单/报价服务与v26页面，修正原型合同，不调整活动胜出、退款政策或台湾开放范围；普通活动管理旧ops入口与其余零售页面继续在C06记录，不宣称整项完成。

### §1 正式合同与实现选择

- 活动列表只支持includeEnded，App按triggerType筛GROUP/CUTOFF；活动商品由goodsNos取得，不依赖不存在的goods块。开团选择时经正式商品详情读取名称/价格，读不到不补假值；活动/自提点失败不可按成功默认继续。
- GROUP/CUTOFF均为PRICE，创建带原storeNo、goodsNos、总quota及可选budgetMinor；按市场currencyScale精确转分。新表单明确长期有效，以总份数约束；集单每天一期，不提交被后端拒绝的RECURRING。保留集单minQty/periodQuota和截止/提货时间；非法非空值不能被转成省略。
- 团的need由服务端返回，不从人数重新推断；FAILED不代表渠道退款已完成，正式VO没有refundedMinor/failReason时不显示虚构金额/原因或承诺固定迟到款补退期限。
- 求团读interestedCount；报价读merchant、priceMinor、minCount、desc、revisions/locked/status，mine由原商家号判断，锁价或失效报价不允许改；写仍沿现有QuoteReq，不用读结构反推写字段。
- 页面API冻结原店/主体/用户/会话和campaign权限，异步选择/确认与回执均沿原作用域。商品选择复用同一原店、正式读取权限biz:stock与分页，并继承原营销页campaign守卫。所有金额按原店币种/精度显示，不硬编码TWD×100。
- 原集单备货清单仍可读取；创建库存单仅依既有inventoryOpen门禁，不在本批启用或新增库存接线。

### §2 执行清单

- [x] group_buy_api.dart对齐正式列表/商品/团/报价结构，补作用域守卫。
- [x] group_buy_pages.dart补创建参数、金额/份数校验、开团元数据选择、锁价/退款结果和旧页隔离；mock_v26.dart及既有旅程按正式形状对账。
- [x] 新增正式fixture的API/页面验证，运行受影响旅程、限定analyze；后端仅定向验证已有拼团/期与报价语义。
- [x] 一次只读复核及必要修复，更新原报告/两CSV/App交接，不提交部署。

### §5 验证

| AC/边界 | 验证证据 |
|---|---|
| 正式活动/商品 | `activities filter official mixed array...`、`activity goods load official metadata...`覆盖真实goodsNos、includeEnded和客户端玩法筛选，不依赖mock自造goods块；campaign+stock自定义角色可读，不要求goods编辑权限。 |
| 正式报价/团/日期 | 嵌套merchant/priceMinor/minCount/desc/revisions/interestedCount、mine归属、后端need、退款金额未知及期日期字符串均有断言；锁价按钮不可改，FAILED不显示“已全额退款”。 |
| 创建写体 | GROUP按3位精度提交12345/100500的价格/预算、原storeNo、PRICE/ALWAYS_ON/总quota；CUTOFF非法非空minQty先拦截，修正后提交总quota=50与periodQuota=200、价格1234，二者不混用；0位精度拒绝小数。 |
| 隔离/失败/分页 | 原API失效不发送；旧表单回调在换店后不发请求；商品选择超过50条继续读并在换店后隐藏；仅撤campaign而保留stock时原营销选择页也立即隐藏；自提点查询失败不默认不限点继续开团。 |
| App新增计数 | `group_buy_contract_test.dart`共15个不同场景分组/增量通过：初轮11/11（`/tmp/ai-shop-c06-group-v2.log`）；复核及扩展边界轮6/6中4项为原场景受影响复验、2项新增（`/tmp/ai-shop-c06-group-incremental.log`）；撤campaign选择页1/1（`/tmp/ai-shop-c06-group-scope.log`）。库存未启用时备货可读、创建库存单不可用1/1（`/tmp/ai-shop-c06-group-inventory-gate.log`）。不把重跑相加。 |
| 既有旅程 | `v26_flows_test.dart`5/5：建活动、开/散团、结果、集单决定/备货、报价改价；开/散团使用retailComplete=true，确认完整零售mock路由仍能读取团商品元数据（经原商品读权限）。`v23_flows_test.dart`的M3商品选择1/1，验证复用方兼容。日志`/tmp/ai-shop-c06-group-journeys-v2.log`及`/tmp/ai-shop-c06-group-picker-compat.log`。 |
| 后端既有权威 | `ActivityGroupBuyTest#groupNeedsAtLeastTwo/#groupBenefitMustBePrice`、`PeriodFlowTest#batchParamsValidated`、`M6cGroupFlowTest#quoteAndReviseDifferAfterLock/#priceRevisionIsPublic/#interestIsIntentNotOrder`共6/6。日志`/tmp/ai-shop-c06-group-backend.log`。未改Java生产文件；沿前批冻结运行classpath限定编译/执行3个测试源码，不称当前全仓重编或真实资金验收。 |
| 静态/复核 | 受影响6组Dart路径限定analyze无问题，变更文件空白检查通过；四个语言源的2个新增词条对账通过并重新生成。一次review的商品读取权限、原调用方权限守卫两处P2已修复，对应验证包含在15项内。 |

§6实现对账：group_buy_api/pages、复用goods_picker、mock_v26和mock_onboarding_server的v26原entity参数/已授权mock商品元数据分支；新增group_buy_contract_test并调整v26旅程必填总份数及退款断言；新增groupRefundUnconfirmed/groupQuoteInactive两键与生成物。原mock内部演示台账不当正式退款回执；真实退款金额/进度仍从原订单与渠道取得。

首次既有旅程编译遇到其他会话服务业新增词条尚未生成；相应ARB出现后统一gen-l10n，实际工作区5条旅程通过，未改该服务业业务文件。本批不新增后端端点/DDL、不部署，不改live白名单、台湾开放或库存开关；真实团购资格、订单支付退款、供应商与地区合同仍需单列验收，不能把模拟通过等同开放。普通活动/券管理旧ops入口等C06余项继续保留，库存映射与166启用保持最后。
