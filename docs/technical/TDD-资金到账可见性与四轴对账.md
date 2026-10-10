# TDD · 资金到账的可见性，与四轴对账

状态：草稿（待确认）
关联需求：[PRD-商家资金到账与对账](../requirements/PRD-商家资金到账与对账.md)
创建日期：2026-08-26

---

## 1. 需求摘要

分账主线被商务前置卡住（B7 分账参数书面口径未取），本方案**只做不依赖它的那部分**：

1. 让「分账指令已发出」与「资金已划转」在状态上分得开 —— 今天 `SPLIT` 同时表示两者，而底下是桩
2. 让商家看得出「这笔钱还没到你账上」
3. 把对账从一条轴扩到四条，并让每一条都说得清自己覆盖不到什么

> **刻意不做**：`stl_withdraw` 的生产者。理由见 PRD §2 —— 补完就等于把一条二清链路做成正式功能。

---

## 2. 当前架构分析

| 模块 | 现状 |
|---|---|
| `SettleServiceImpl.executeSplit` | 状态推到 `SPLIT`，底下调 `StubSplitGateway` |
| `StubSplitGateway` | 类注释自己写着「S4 接微信支付分账前的临时物」 |
| `stl_split_log` | 只记**我方发了什么**，不记对方做了什么 |
| `ReconServiceImpl` | 一条轴：扫我方 PENDING 的收款流水逐笔查单 |
| `ReconService.coverage()` | **已有的好做法**：明说渠道侧那一类看不见，页面照它显示提示条 |
| `PointsService.checkIdentity` | 恒等式算得出来，但没有任何地方会主动告警 |

**可复用的**：`coverage()` 那套「说明覆盖范围」的形态，四条轴都照抄。

---

## 3. 方案设计

### 3.1 把「发出」与「到账」拆开

**问题**：`SPLIT` 今天同时表示两件事，而底下是桩 —— 账面显示已分账，资金没动。

| 方案 | 结论 |
|---|---|
| A. 新增终态 `SPLIT_CONFIRMED`，`SPLIT` 退回「指令已发出」 | ✅ 采用 |
| B. 加一列 `split_confirmed_at`，状态不动 | ❌ 状态是运营与商家共同的语言，让它继续说谎，加多少列都没用 |
| C. 等接通道时一起做 | ❌ 那之前每一天都在给商家看假象 |

**A 的代价**：`SPLIT` 是既有终态，改语义要扫所有读它的地方。
已确认的读取点：`executeSplit` 的幂等判断、`reverseSplit`、ops 结算列表筛选、b-app 结算页。

> ⚠️ **桩网关必须明确返回「未确认」而不是「成功」。**
> 今天它返回成功，于是状态被推到 `SPLIT`。改完之后桩要落在 `SPLIT`（已发出）
> 而**永远不进** `SPLIT_CONFIRMED` —— 那一步只能由通道回执产生。
> 与提现表 `APPROVED → PAID` 刻意不给人工入口是同一条规矩：
> **打款结果只能来自回执**，让人手动做平，之后对账差额永远说不清。

### 3.2 商家侧的措辞

b-app 结算页今天显示 `netMinor`（商家实得），读起来像已到手。

- `SPLIT_CONFIRMED` → 「已到账」
- `SPLIT` → **「已发起，等通道确认」**，不是「已结算」
- `PENDING` → 「待结算」
- `OFFLINE_SETTLED` → **「当面收款，无需结算」** —— 这笔钱他早就拿到了

> 措辞不是文案问题。商家按「已结算」去对自己的银行流水，对不上就会来问客服，
> 而客服看到状态也是 `SPLIT`，两边一起卡住。

### 3.3 四轴对账

统一形态：每条轴一个 `ReconAxis`，各自给出 `scan()` 与 `coverage()`。

| 轴 | 数据源 A | 数据源 B | 本期能做到 |
|---|---|---|---|
| 收款 | `stl_payment` | 通道查单 | 已有；渠道账单仍缺 |
| **分账** | `stl_bill`（`SPLIT`）| 通道分账查询 | **接通道后才有 B 侧** —— 本期只做 A 侧：列出「发出超过 N 小时仍未确认」 |
| **出款** | `stl_bill`（自营 `PAID`）| 银行流水 | 无 B 侧；本期只做 A 侧：`payment_ref` 缺失或重复 |
| **积分池** | `checkIdentity` | — | 本期加**定时校验 + 失衡告警** |

> **A 侧自查也有价值**，正是收款那条轴的既有经验：
> 「我方发出了而迟迟没有终态」这一类，不需要对方的账单就能发现。

⚠️ **线下单必须排除在通道类对账之外。**
`OFFLINE_SETTLED` 的钱从没进过通道，混进去就是永久的无解差异 ——
而无解差异一多，对账页就没人看了，真差异跟着一起被埋掉。
判据用状态不用 `pay_channel`：后者是本批才开始写的，存量行为 null。

---

## 4. 模块设计

**新增**
```
shop-settle/service/recon/ReconAxis.java          轴的统一接口：scan() + coverage()
shop-settle/service/recon/SplitReconAxis.java     分账轴（A 侧）
shop-settle/service/recon/PayoutReconAxis.java    出款轴（A 侧）
shop-settle/service/recon/PointsPoolReconAxis.java 积分池恒等式
shop-settle/job/PointsIdentityJob.java            定时校验 + 失衡告警
```

**修改**
```
StlBill                       加 SPLIT_CONFIRMED 常量
SettleServiceImpl             executeSplit 落 SPLIT（已发出）；新增回执入口置 SPLIT_CONFIRMED
StubSplitGateway              明确返回「已受理未确认」
ReconServiceImpl              收敛成「跑所有轴」
ops-web / b-app               状态措辞与筛选项
```

**复用**：`coverage()` 的形态、`StlReconDiff` 表（加 `axis` 列区分来源）。

### 4.1 数据库

| 表 | 变更 | 理由 |
|---|---|---|
| `stl_recon_diff` | 加 `axis VARCHAR(16) NOT NULL DEFAULT 'PAYMENT'` | 四条轴共用一张差异表；不加的话运营分不出「这条差异该找谁处置」 |
| `stl_bill` | 加 `split_confirmed_at BIGINT` | 回执时刻。与 `split_at`（指令发出时刻）**分开** —— 两个时点之间的间隔正是这条轴要盯的 |
| `stl_withdraw` | **只改表注释**，不加列 | 写明「不是出款路径，见 PRD §2」 |

> 迁移号落号前扫一眼工作区，别撞上别人未提交的。

---

## 5. 测试策略

| 场景 | 判据 |
|---|---|
| ★★★ 桩网关不许把单子推到已到账 | 跑完 `executeSplit`，状态是 `SPLIT` 而**不是** `SPLIT_CONFIRMED` |
| ★★★ 线下单不进通道对账 | `OFFLINE_SETTLED` 的单跑四轴，一条差异都不产生 |
| ★★ 分账轴逮得住「发出很久没确认」 | 造一条 `split_at` 在 N 小时前的 `SPLIT` 单 → 出一条差异 |
| ★★ 积分池失衡会告警 | 手工制造一笔不平的池子流水 → 定时任务报出来 |
| ★★ 每条轴都说得清覆盖范围 | `coverage()` 返回值非空，且含「查不了什么」 |
| ★ 已确认的单不再被扫出来 | 幂等：连跑两轮，差异不翻倍 |

**消融**：每条都要撤掉被测那行确认它变红，且红在预期那条上。
上一批在这上面栽过两次假绿（详见 `scenario-test-false-greens` 的记录）。

---

## 6. 风险

- **改 `SPLIT` 语义会碰到既有读取点。** 已列出四处，但读取点是靠 grep 找的，
  可能有漏。落地时先跑一次全量拿基线。
- **告警发到哪还没定。** 没有接收方的告警等于没有告警 —— 这一条要先有答案再写代码。
- **本方案不能替代分账接通。** 它让假象消失，不让钱到账。
  第三方商家的货款问题仍然悬着，见 PRD §5 待确认第 2 条。

---

## 7. 实现任务

- [ ] 迁移：`stl_recon_diff.axis` / `stl_bill.split_confirmed_at` / `stl_withdraw` 表注释
- [ ] `SPLIT` 语义拆分 + 桩网关改为「已受理未确认」
- [ ] `ReconAxis` 抽象，收款轴迁进去（**不改行为**，纯搬家，单独一次提交）
- [ ] 分账轴、出款轴、积分池轴
- [ ] 积分池定时校验与告警（**先定告警接收方**）
- [ ] 三端状态措辞
- [ ] `gen-ui-catalog.py` / 端点登记 / 权限矩阵

---
确认记录：（待用户确认）
