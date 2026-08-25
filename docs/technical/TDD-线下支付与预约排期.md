# TDD 线下支付与预约排期（一期）

状态：**草稿 · 待确认**（本册是实现细节；五个域怎么串见 [TDD-支付与积分总体方案](TDD-支付与积分总体方案.md)）
关联需求：[PRD-支付方式与预约预定](../requirements/PRD-支付方式与预约预定.md)（第 7 节建议已获确认：**线下支付不抽佣**）
关联决策：[ADR-002 结算走微信支付分账](ADR/ADR-002-结算走微信支付分账.md) · [ADR-005 履约方式与自提点模型](ADR/ADR-005-履约方式与自提点模型.md)
创建：2026-08-25

---

## 1. 需求摘要

一期做两件互不依赖的事：

- **线下支付**：买家下单时选「到店/当面付」，商家收到钱后在 b-app 点「确认收款」，订单才进入履约。
  **平台不碰这笔钱、不抽佣、不给补贴**（已拍板）。
- **预约排期**：商家维护可约时段与容量，买家只能选可约且未满的时段，并发下单不得超约。

二期的「预定（定金＋尾款）」不在本方案内 —— 它依赖本期的线下支付。

---

## 2. 当前架构分析

### 2.1 相关现有模块

| 模块 | 路径 | 与本期的关系 |
|---|---|---|
| 履约方式取值域 | `shop-base/common/Fulfillments.java` | **不动**。线下支付是「钱怎么付」，与履约方式正交 |
| 下单 | `shop-core/trade/…/OrderServiceImpl#create` | 加一处支付方式校验；状态分支加一支 |
| 状态机 | `shop-core/trade/service/OrderStateMachine` | 加 `WAIT_OFFLINE_PAY` 及其两条出边 |
| 支付成功 | 同上 `markPaid` | 复用。线下确认收款走**同一个方法**，只是 `payChannel` 不同 |
| 结算 | `shop-settle/…/SettleServiceImpl` | **关键复用点，见 2.3** |
| 商品 | `prd_goods.fulfillments` | 新增一列「支持的支付方式」，与 fulfillments 同构 |
| 门店 | `mch_store.open_hours` | 预约排期的**默认值**来源，不是排期本身 |

### 2.2 影响范围

- C 端：结算页多一个「支付方式」选择；预约类商品的时段选择从「随便填」变成「从可约时段里选」。
- B 端：新增「待收款」订单分组 + 「确认收款」动作；新增「预约排期」维护页。
- 运营端：线下支付按「商家 × 类目」授权的开关；线下单在结算单里单独可见。
- 结算：线下单产生的账单**不进分账链路**。

### 2.3 三个可以直接复用的现成能力（这一节决定了方案规模）

**① 结算域早就有一条不走分账的路。**
`SettleServiceImpl` 按 `business_mode` 分两条状态机：

```
自营   PENDING_RECON → CONFIRMED → PAID     ← 对账、确认、财务付款，本来就不分账
第三方 PENDING → SPLITTABLE → SPLIT          ← 走微信分账
```

线下支付订单**天然属于第一类形态**：钱已经在商家手里，平台要做的只是「记一笔、标记已收讫」。
所以**不需要为线下支付新造一条结算链路**，只需要一个新的终态。

**② `markPaid` 已经是幂等的、且不关心钱从哪来。**
它接 `(orderNo, payChannel, payTradeNo)`，做的事是：置 PAID → 锁定转实扣 → 按履约方式分派子单状态 → 发 `OrderPaid` 事件。
微信回调和线下确认收款**可以是同一个方法的两个调用方**。
—— 这一点很重要：**不复用它就要把库存转实扣、入会、发分、结算触发全部重写一遍**。

**③ 支付通道是主数据表，不是枚举。**
`sys_pay_channel` 有 `supports_subsidy` / `supports_split` / `supports_payout` 三个开关。
线下支付加一条 `OFFLINE` 记录、三个开关全 false 即可，**代码里不需要 if (线下)** 到处判。

### 2.4 一处必须先纠正的既有问题

`Fulfillments` 的注释说得很清楚：商品侧的 `prd_goods.fulfillments` 曾经是「**无取值域的自由 JSON 数组，且建商品时被写死**」。
本期新增的「支持的支付方式」若照抄这个形态，会重蹈覆辙。
**方案：从第一天起就有取值域常量类 + 建品时可选 + 下单时校验**，三件事一起做（见 3.2）。

---

## 3. 方案设计

### 3.1 方案选型：线下支付订单走哪条路

| 方案 | 做法 | 结论 |
|---|---|---|
| **A. 新增 `WAIT_OFFLINE_PAY` 状态，确认收款后复用 `markPaid`**（推荐） | 下单落新状态；商家确认后调 `markPaid(orderNo, "OFFLINE", 确认人+时间)` | ✅ **采用**。库存、入会、发分、结算全部复用；新增面仅限「新状态 + 一个动作 + 一条通道记录」 |
| B. 线下单直接落 `PAID` | 下单即视为已付 | ❌ 钱还没收到就是已支付，商家一旦收不到钱，退款链路要退平台没收过的钱 |
| C. 线下单独立一套订单类型 | 与线上单分表分流程 | ❌ 报表、售后、评价、积分全部要写第二遍。**这是最贵的路** |

### 3.2 模块设计

**新增**

| 模块 | 路径 | 职责 |
|---|---|---|
| `PayModes` | `shop-base/common/PayModes.java` | 支付方式取值域：`ONLINE` / `OFFLINE`。与 `Fulfillments` 同构、同理由（商品域与交易域都要用，取值域属于两者之上） |
| `AppointmentSlotService` | `shop-core/product/service/` | 可约时段的维护与占用 |
| `BizOfflinePayController` 动作 | `shop-core/trade/api/biz/` | 「确认收款」端点 |

**修改**

| 模块 | 变更 |
|---|---|
| `OrderStateMachine` | 主单 `WAIT_PAY → WAIT_OFFLINE_PAY`；`WAIT_OFFLINE_PAY → PAID / CANCELLED`。**子单不加新状态** —— 子单在确认收款前保持 `WAIT_PAY`，与线上单一致 |
| `OrderServiceImpl#create` | ① 校验所选支付方式该商品是否支持 ② 校验组合合法性（见 3.4）③ 线下单落 `WAIT_OFFLINE_PAY` |
| `OrderServiceImpl#markPaid` | **签名不变**。仅在设置 `payChannel` 时接受 `OFFLINE` |
| `SettleServiceImpl` | 线下单账单落新终态 `OFFLINE_SETTLED`，不进 `SPLITTABLE` |
| `prd_goods` | 加 `pay_modes` 列（JSON 数组，取值域受 `PayModes` 约束） |

**不动**：`Fulfillments`、库存锁定、入会、发分、售后状态机。

### 3.3 数据模型

```sql
-- 支付方式（商品侧）
ALTER TABLE prd_goods ADD COLUMN pay_modes VARCHAR(128) NOT NULL DEFAULT '["ONLINE"]';

-- 线下支付通道（主数据，三个开关全关）
INSERT INTO sys_pay_channel (pay_channel, name, enabled,
    supports_subsidy, supports_split, supports_payout, ...)
VALUES ('OFFLINE', '线下收款', 1, 0, 0, 0, ...);

-- 线下支付的可用性判定改为**四层取交集**，见总纲 §3.1。
-- 短期主力是「主体资质」这一层（读 mch_qualification 的当前状态，不是审核时写死的码）；
-- 类目/门店/商品三层一期默认放行，但位置一开始就留出来。
ALTER TABLE mch_store ADD COLUMN offline_pay_enabled TINYINT NOT NULL DEFAULT 0;  -- ③ 门店
CREATE TABLE prd_category_pay_mode (...)   -- ① 平台 × 类目，没有行 = 放行

-- 订单上的线下收款留痕
ALTER TABLE ord_order ADD COLUMN offline_confirmed_by VARCHAR(64) NULL;
ALTER TABLE ord_order ADD COLUMN offline_confirmed_at BIGINT NULL;
-- 让掉的佣金：只记不扣（PRD 7.1）。不是为了将来去收，是为了知道让了多少
ALTER TABLE stl_bill ADD COLUMN waived_commission_minor BIGINT NOT NULL DEFAULT 0;

-- 预约排期
CREATE TABLE prd_appointment_slot (...)   -- entity_no + store_no + 星期几 + 起止 + 容量
CREATE TABLE prd_appointment_exception (...) -- 具体日期的停约/加开
```

> **`waived_commission_minor` 放 `stl_bill` 不放订单**：它是结算口径的数，
> 与 `commission_minor` 并列才能一眼看出「这单本该收多少、实际收了 0」。

### 3.4 组合合法性（PRD 第 5 节的落地）

校验点在 `OrderServiceImpl#create`，与 `requireFulfillmentSupported` 并列：

```java
// 线下支付 × 履约方式：只有「当面能收到钱」的方式才允许
private static final Set<String> OFFLINE_PAYABLE = Set.of(
        STORE_PICKUP,      // 到店自提：当面
        MERCHANT_DELIVERY, // 商家自送：货到付款 —— 但要商家单独开（见下）
        STORE_VERIFY,      // 到店核销：当面
        APPOINTMENT);      // 上门服务：服务完成后收
// 明确排除：EXPRESS（货已寄出）、NEIGHBOR_PICKUP（自提点不是卖家，不能替卖家收钱）
```

**货到付款单独一个开关**（PRD 7.6）：`MERCHANT_DELIVERY + OFFLINE` 需要商家在门店维度显式开启，
对新入驻商家默认关闭 —— 它是整张表里风险最高的一格，损失全在商家。

### 3.5 预约排期的并发

**与库存锁定同一条口径**，不另造机制：占用时走带条件的 `UPDATE`

```sql
UPDATE prd_appointment_slot
   SET booked = booked + 1
 WHERE slot_id = ? AND booked < capacity
```

影响 0 行即已约满，抛 `APPOINTMENT_SLOT_FULL`。
—— 与「到店核销扣次数靠带条件的 UPDATE」（5969d869）是同一个做法，不引入新的并发模型。

### 3.6 配置项（零硬编码）

| 配置 | 位置 | 默认 |
|---|---|---|
| 线下单未确认收款的自动取消时长 | `shop.order.offline-pay-hours` | 24 |
| 定金比例上限（二期用，先占位不实现） | — | — |
| 可约时段的最小粒度 | `shop.appointment.slot-minutes` | 30 |

---

## 4. 测试策略

**必测场景**

1. 线下单下单后是 `WAIT_OFFLINE_PAY`，**库存已锁**；确认收款后转 `PAID`，库存转实扣。
2. **买家侧没有确认收款的入口** —— 用买家 token 调该端点应当 403。
3. 线下单可以用**积分**与**商家券**；用**平台券**时下单被拒（下单时即拒，不是事后回滚）。
   ⚠️ 本条 2026-08-25 订正过 —— 原写「不参与优惠券与积分抵扣」是错的：
   积分成本本来就在商家，线下时商家当面少收即可，平台零动作。见
   [TDD-支付与积分总体方案](TDD-支付与积分总体方案.md) §7。
4. 线下单的结算账单落 `OFFLINE_SETTLED`，**永远不进 `SPLITTABLE`**；`waived_commission_minor` > 0 而 `commission_minor` = 0。
5. `EXPRESS + OFFLINE` 与 `NEIGHBOR_PICKUP + OFFLINE` 下单被拒。
6. 未授权的商家选线下支付被拒（平台开关生效）。
7. 超时未确认收款 → 自动取消 → 库存释放。
8. 预约：两个买家并发抢同一时段最后一个名额，**只有一个成功**（与库存超卖同一条判据）。
9. 预约：选一个未开放/已停约的时段被拒。

**回归**：线上支付全链路必须一条不红 —— 本期动了 `create` 与状态机，
而那是 1205 条测试里最要害的一段（`M3TradeFlowTest` / `ConsumerOrderFlowTest`）。

---

## 5. 风险与注意事项

| 风险 | 应对 |
|---|---|
| **动了下单主流程** | `create` 是全站最要害的方法。新增校验一律**前置且只读**，不改已有分支的顺序 |
| **`markPaid` 被两个调用方共用** | 幂等已有（重复回调直接 return）。线下确认要加**操作人留痕**，否则出纠纷时说不清是谁点的 |
| 商家点了确认收款但实际没收到钱 | 平台不介入 —— 这是 PRD 已确认的边界（不碰钱）。但要在 b-app 的确认弹窗里写明「确认后订单进入履约，平台不代收此款」 |
| 线下单混进分账报表 | 靠 4.4 那条测试钉死。**报表按 `pay_channel` 分组时必须排除 OFFLINE** |
| `pay_modes` 重蹈 `fulfillments` 覆辙 | 取值域常量 + 建品可选 + 下单校验，三件事同一个 PR 做完（2.4） |

---

## 6. 实现任务

**线下支付**
- [ ] T1 `PayModes` 取值域常量类
- [ ] T2 迁移：`prd_goods.pay_modes`、`sys_pay_channel` 加 OFFLINE、`mch_offline_pay_grant`、订单留痕两列、`stl_bill.waived_commission_minor`
- [ ] T3 状态机加 `WAIT_OFFLINE_PAY` 及两条出边
- [ ] T4 `create`：支付方式校验 + 组合合法性 + 平台授权校验 + 落新状态
- [ ] T5 「确认收款」端点（B 端权限，带操作人留痕）→ 复用 `markPaid`
- [ ] T6 结算：线下单落 `OFFLINE_SETTLED`，记 `waived_commission_minor`
- [ ] T7 超时自动取消（复用现有关单任务，加一个状态分支）
- [ ] T8 b-app：待收款分组 + 确认收款动作 + 弹窗说明
- [ ] T9 c-app：结算页支付方式选择 + 线下选平台券时的不可用提示（积分与商家券照常可用）
- [ ] T10 ops-web：线下支付授权页（商家 × 类目）

**预约排期**
- [ ] T11 迁移：`prd_appointment_slot` + `prd_appointment_exception`
- [ ] T12 `AppointmentSlotService`：维护、查询可约、带条件 UPDATE 占用
- [ ] T13 `create`：预约类履约改为「从可约时段选」，占用失败即拒
- [ ] T14 b-app：排期维护页（默认值取 `mch_store.open_hours`）
- [ ] T15 c-app：时段选择器

**收尾**
- [ ] T16 订正 PRD 2.3 列的三处文档漂移
- [ ] T17 全量测试，与 `known-failures.txt` 比对，新增失败为 0

---

## 7. 明确不做

- 不做平台代收代付、不做资金池（ADR-002）。
- 不做线下单抽佣（已拍板）。`waived_commission_minor` **只记不扣**。
- 不做定金/尾款（二期）。
- 不做「指定服务人员」的排期（PRD 7.5）。
- 不碰预售那套字段，也不给它做入口（PRD 7.4）。

---

确认记录：待确认
