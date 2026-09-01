# TDD-支付域 · 核心边界与迁移任务

> 状态：**方案待评审** · 创建 2026-09-01
> 上游：[实施方案与排期](./TDD-支付域-实施方案与排期.md)（原按「反向依赖数」排的切换顺序，本篇改掉了它）·
> [双形态部署与装配](./TDD-支付域-双形态部署与装配.md)（支付域不做 controller）·
> [ADR-023 服务发现](../ADR/ADR-023-服务发现先不装中间件.md)（访问层与 InternalClient）

> **原则（2026-09-01 定）**：**除回调外不做反向依赖，pay 只解决 pay 的核心问题。**
>
> 这条原则否掉了之前「把 11 个业务侧 Port 做成 HTTP 客户端」的方案。
> 它逼着回答一个之前绕开的问题：**什么才是 pay 的核心？**

## 一、把 30 个调用点逐个问一遍

之前的做法是数「有几个 Port 要远程化」（答案是 27 个方法），然后按数量排优先级。
**那是把问题当成工作量，而不是当成设计。**

按新原则，每个调用点要回答的是另一个问题：
**这个数据／动作，本来就该在 pay 这边吗？**

答案分成五类，而**真正需要「问别人」的只剩一类**：

| 类别 | 调用点 | 做法 |
|---|---|---|
| **A · 数据存错了地方** | 7 | 搬进 pay 库 |
| **B · 调用方本来就知道** | 9 | 参数传入 + 快照 |
| **C · 动作不属于 pay** | 7 | 逻辑搬到主应用侧 |
| **D · pay 自己就有** | 2 | 读自己的表 |
| **E · 对外通道，不是反向依赖** | 1 | 不动 |

---

## A · 数据存错了地方（7 处）→ 搬进 pay 库

这一类最值得说：它们**从来就是支付域的知识**，只是当初图省事存进了通用表。

### A1 四个设置项（`SettingPort`）

```
POLICY_KEY        端积分策略      ← 积分是资金域的负债
PointsConfig.KEY  积分配置         ← 同上
TAX_RULE_KEY      个税代扣规则     ← 提现要扣的税
TITLE_KEY         平台开票信息     ← 开票抬头
```

四个全是资金域自己的设置，存在 `sys_setting`（平台通用设置表）里。
**搬进 pay 的表之后，`SettingPort` 这个反向依赖直接消失。**

> ⚠️ 搬迁要带数据迁移：现有值在 `sys_setting` 里，且**运营端已经在改它们**。
> 迁移期双写还是切换后一次性搬，单独定。

### A2 三个通道主数据（`MasterDataPort`）

```
channelFeeRate       通道手续费率（按主体形态分档）
supportsSubsidy      这个通道支不支持补贴
channelSettleCycle   通道的结算周期
```

**通道的属性就是支付域的核心知识** —— 没有比 pay 更该知道
「微信收多少手续费」的地方了。今天它们在 `sys_pay_channel`，
搬进 pay 库之后 `MasterDataPort` 这个反向依赖也消失。

---

## B · 调用方本来就知道（9 处）→ 参数传入 + 快照

### ⚠️ 2026-09-01 修正：B1 的分类不成立，M10 也不必要

原文把六个商家属性归为「调用方本来就知道」。**核实下来不成立**：

- `shop-core` **不依赖** `shop-merchant`，`SettleSourcePortImpl` 里也没有
  `MerchantQueryPort` —— <b>trade 拿不到商家属性</b>。
  让它去查等于给 trade 新加一条对 merchant 的依赖，
  那只是把跨域调用挪了个地方，不是消除。

另一个方向的核实（问「商户信息是否就该在支付服务」时做的）：

- 六个属性里，`payMerchantNo` / `feeBearer` / `settleCycle` 来自
  `mch_payment_merchant`（进件表），而那张表整张都是支付的语言 ——
  没有一列是商家档案。按归属该跟 pay 走。
- **但它有 97 处引用、5 个服务在用**（进件、准入、治理、门店管理、Port），
  整表搬迁还会造成 `shop-merchant → pay` 的依赖，要再造一个 spi Port。

**而这件事最后不必做**，因为查到了一个更要紧的事实：

> **`stl_bill` 上已经有 4 个快照列**：`feeBearer`、`payMerchantNo`、
> `businessMode`、`fundsMode`。

快照机制本来就在，只是这些值是「结算时读一次商家表再落库」。
拆库之后唯一的跨库点是 `generateForOrder` 那一次读 ——
**而那一次读的结果本来就要落成快照**。

所以正确的做法既不是「trade 传进来」，也不是「把进件表搬进 pay」，而是：

> **把 `generateForOrder` 的编排搬到 `paybridge`** ——
> 那一层同时够得着 trade 与 merchant，由它组装好「子单构成 + 商家属性快照」
> 一次传给 pay。pay 从此不查任何业务表。

这与 I1–I3/I6/I8 的形状一致：<b>跨域编排属于两边之上的那一层</b>。

**代价要认**：`generateForOrder` 在支付成功的 AfterCommit 里，
是<b>资金主链路</b>。改它要单独评审，不与别的改动混在一起。

---

### B1 六个商家属性（`MerchantQueryPort`）

```
businessModeOf   经营模式（自营/第三方）      payMerchantNoOf  收款商户号
legalFormOf      主体形态                     fundsModeOf      资金模式
feeBearerOf      手续费承担方                 settleCycleOf    结算周期
```

**这些本来就该快照，不该实时查** —— 而且 `stl_bill` 上**已经在快照
`business_mode` 了**，理由写在实体注释里：「口径由 `stl_bill.business_mode`
快照决定」。

同一个道理适用于其余五个：结算单要能回答「**当时**按什么算的」，
而实时查商家表回答的是「**现在**是什么」。商家改了收款号，
历史结算单不该跟着变 —— 这一条 `StoreSettleFlowTest` 里已经有测试守着
（「已生成的流水是快照，不随配置变 —— 钱已经进了旧账户」）。

> 所以这不是「为了拆分而改」，**是把一个本来就存在的正确性问题一起修了**。

### B2 订单构成（`SettleSourcePort.settleSourcesOf`）

`generateForOrder(orderNo)` 进来只有订单号，然后回查子单构成。
改成**调用方把子单快照传进来** —— 主应用在支付成功那一刻本来就持有这些数据。

**顺带解决一个性能问题**：今天这个方法在循环里逐个调 `businessModeOf` 与
`payMerchantNoOf`，一个 3 商家的订单是 7 次调用。做成远程会是 7 次 HTTP，
在支付回调的同步路径上。改成参数传入之后，**这 7 次全部消失**。

### B3 积分规则（`PointsRulePort.ruleFor`）与风控事实（`fundRiskFacts`）

同 B1：调用时传进来，pay 不回查。

---

## C · 动作不属于 pay（7 处）→ 逻辑搬到主应用侧

### C1 不变式巡检（`SettleSourcePort` 5 处，在 `FundInvariantServiceImpl`）

```
paidSubOrdersSince / notPaidAmong / subOrdersNotAlive
pointsGrantedSince / clearPointsGranted
```

五个都是**跨域比对**：拿 pay 的账去比 trade 的单。
跨域比对天然属于两边之上的那一层，不属于任何一边。

**I8 已经是这个形状**（`OrderPaidReconciler` 在 `shop-app/paybridge`，
从 pay 拉「我这边有哪些账」，自己去比订单状态）。
I1–I3 照着搬过去，`FundInvariantService` 只留「pay 这边有哪些账」的只读查询。

### C2 对账修订单（`OrderRepairPort` 2 处，在 `ReconServiceImpl`）

`markPaid` / `closeUnpaid` —— 对账发现掉单后把订单推回正轨。

**对账的产出应该是「差异」，不是「修复」。** 修订单的动作搬到主应用侧的巡检里，
与 C1 同一个位置。pay 侧只负责把差异记进 `stl_recon_diff`。

### C3 改商家开关（`MerchantAdminPort.setPointsEnabled`）

pay 去改商家的「是否启用积分」。**这明显不是 pay 的事** ——
它是运营的一个动作，编排属于主应用。

---

## D · pay 自己就有（2 处）→ 读自己的表

```
orderSceneQueryPort.paySceneOfSubOrder
orderSceneQueryPort.payChannelOfSubOrder
```

支付场景与支付通道 —— **`stl_payment` 上就有 `pay_channel`**。
去问订单域，是因为这张表当初没人写（2026-09-01 补上了写入）。现在读自己的即可。

---

## E · 对外通道（1 处）→ 不动

`PayQueryPort.query` 是**向支付通道查单**，不是向主应用查。
它是 pay 的核心能力，本来就该在这边。

---

## 二、重新梳理后的迁移任务

按新原则，任务从「远程化 27 个方法」变成了另一组事情 ——
**大部分是把数据和逻辑放回它们该在的地方，而不是加网络调用**。

| # | 任务 | 消除的反向依赖 | 备注 |
|---|---|---|---|
| **M1** | 通道主数据搬进 pay 库 | `MasterDataPort`（3） | 纯搬家 |
| **M2** | 四个设置项搬进 pay 库 | `SettingPort`（4） | **带数据迁移 + 运营端改造** |
| **M3** | 支付场景／通道读自己的表 | `OrderSceneQueryPort`（2） | 纯搬家 |
| **M4** | I1–I3 巡检搬到 `paybridge`（照 I8） | `SettleSourcePort`（5） | 纯搬家 |
| **M5** | 对账只产出差异，修订单搬到主应用 | `OrderRepairPort`（2） | 依赖 M4 |
| **M6** | `setPointsEnabled` 的编排搬到主应用 | `MerchantAdminPort`（1） | 纯搬家 |
| **M7** | 商家属性改快照传入（六个） | `MerchantQueryPort`（6） | **唯一涉及数据正确性** |
| **M8** | 订单构成改参数传入 | `SettleSourcePort`（1） | 依赖 M7 |
| **M9** | 积分规则、风控事实改参数传入 | `PointsRulePort` 等（2） | 纯搬家 |

**M1–M6、M9 是纯搬家，不改业务语义**，可以逐个做、逐个验、逐个回滚。
**M7 是唯一涉及数据正确性的一步**（快照 vs 实时查），
而它**顺带修了一个本来就存在的问题**：历史结算单不该跟着商家改动而变。

全部做完之后，pay 与主应用之间**只剩一个方向**：
支付成功后回调订单（今天是 `PaymentLedger` + I8 的形状）。

## 三、这个原则改掉了什么

之前的计划是「按反向依赖数从少到多切」，把 `SettleServiceImpl`（5 个依赖）
和 `PointsServiceImpl`（6 个）排在最后，因为它们最难。

按新原则，它们的依赖**大部分会在 M1–M9 里自己消失**：

| | 原计划视角 | M1–M9 之后 |
|---|---|---|
| `SettleServiceImpl` | 5 个 Port，最难切 | **0** |
| `PointsServiceImpl` | 6 个 Port，最难切 | **0** |
| `SettleBatchServiceImpl` | 3 个 | **0** |
| `WithdrawServiceImpl` | 2 个 | **0** |
| `ReconServiceImpl` | 2 个 | **1**（`PayQueryPort`，对外通道） |

**先做边界，再做切换。** 边界理顺之后，「切到远程」退化成一件机械的事 ——
就像 2026-09-01 费率和结算发票那两刀一样（Local/Remote 双实现 + 一个开关 +
逐字节比对 + 停服证伪）。

反过来先切换再理边界的话，每切一个都要新造一条反向 HTTP 链路，
而那些链路在边界理顺之后**全部要拆掉**。
