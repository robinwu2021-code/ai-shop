# C 端下单流程 · 待办设计（含 B 端与运营端设置）

状态：已实现并上线（2026-09-21，后端 c43e15e6、小程序 0.1.56 体验版）；P4 线上回补已执行
依据：用户 2026-09-21「以上都按照建议，并补充设计方案，包含 b 端以及运营端的设置」
前情：[待办方案](./C端下单流程-待办方案.md)（实测与取舍）

**本篇回答一个问题：每条规则由谁定、在哪儿改、默认是什么、三端各看到什么。**

---

## 〇、设置放在哪 —— 不新造配置中心

| 层 | 现成的入口 | 适合放什么 |
|---|---|---|
| 运营端 · 功能开关 | `/ops/feature-flags`（后端 `PlatformSwitchPort.bool(key, 默认值)`） | **平台级的开 / 关**：出事时一键关停某条口径 |
| 运营端 · 积分规则 | `sys_setting` 的 `points.config`（`PointsConfig` 一个 JSON） | 积分的**数值口径** |
| B 端 · 商品编辑 | `goods-edit` 里已有「每人限购」 | 商家自己对一件商品定的规则 |
| B 端 · 活动编辑 | `activity-edit` | 商家自己的活动 |

原则：**平台定口径、商家定自己的货**。平台开关只做「开 / 关」，不替商家填数。

新增的开关一律**默认开**（即按本次拍板的口径走），关掉等于回到今天的行为 —— 这样每条都能单独回滚，不用发版。

---

## P1 · 每人限购

### 三端

| 端 | 看到 / 能做什么 |
|---|---|
| **B 端** | 商品编辑「每人限购」输入框（已有）下补一行说明：**「按用户终身累计；已取消、已退款的不计入」**。0 = 不限。 |
| **运营端** | 功能开关 `trade.purchase-limit.enforce`，默认 **开**。关掉 = 回到今天（只显示不拦），用于误伤时紧急放行。 |
| **C 端** | 详情页已有「每人限购 N 件」。下单页步进器上限取 `min(库存, 限购剩余)`，到顶时说清是哪一条：库存挡住说「仅剩 N 件」，限购挡住说「每人限购 5 件，你已买 3 件」。 |

### 后端

- 已买量 = 该用户在该商品上、子单状态**不是** `CANCELLED` / `REFUNDED` 的行数量之和。
- **预览**：`ItemVO.maxQty` 取两者小值，新增 `limitReason`（`STOCK` / `PER_USER`）与 `boughtQty`。
- **建单**：`本单数量 + 已买量 > 限购` → 拒绝，新错误码 `PURCHASE_LIMIT_EXCEEDED`（带剩余可买数）。
- **加购**：同样校验（购物车那条路今天也不拦）。
- 开关关着时：预览照旧给 `maxQty`（只按库存），建单与加购不拦。

### mock

去掉 `mocks/catalog.ts` 里自己写的那段限购拦截 —— 它今天在替一个不存在的后端规则背书。
改成与后端同一口径（已买量从 mock 订单里算），否则本机和线上又是两套答案。

---

## P2 · 退款：券、抵扣积分、已发放积分

### 口径（已拍板）

- **整单退才退，部分退不退**。判据：主单下所有子单都不在履约中（`REFUNDED` / `CANCELLED`）。
- 已发放的购物积分：整单退时收回；**已转正且被花掉的，余额可以扣成负数**，下次获得时先抵（P2c）。

### 三端

| 端 | 看到 / 能做什么 |
|---|---|
| **运营端 · 功能开关** | `refund.return-coupon`（退券，默认开）· `refund.return-points`（退抵扣积分，默认开）· `refund.clawback-earned`（收回已发放积分，默认开）。三个分开，是因为三者影响的账不同，出问题时要能只关其中一个。 |
| **运营端 · 积分规则** | `points.config` 加 `allowNegativeBalance`（默认 `true`）：收回时余额不够是否允许扣成负数。关掉 = 只扣到 0，差额记一笔「未收回」流水供对账。 |
| **B 端 · 售后单详情** | 同意退款前显示一行：「整单退款，将同时退回用户的券「满 30 减 5」、200 积分」。**商家券**的退回会让商家少收一次券核销，所以要他在点同意之前看见；平台券与积分不影响商家结算，只作告知。 |
| **C 端 · 订单详情** | 见 P3：退了什么说什么。 |

### 后端（分三块，风险不同）

- **P2a 分账前**：抵扣流水还是 `PENDING`、资金池没动 —— 整单退款时逐子单调现成的 `pointsPort.reverse`。
- **P2b 分账后**：抵扣已 `CONFIRMED` 且记过 `MERCHANT_PAY` 出池。新开 `refundConfirmed(subOrderNo)`：
  流水 `CONFIRMED → REVERSED`、分退回账户、**资金池记一笔 `MERCHANT_PAY_REVERSE`**（与原出池对冲）。
  开工前与结算域对一次：这笔反向流水与 `reverseSplit` 里的补差回退是不是同一笔钱，别冲两次。
- **P2c 收回已发放**：该子单的 `EARN` 流水 ——
  未转正：从 `pending_balance` 扣回、流水作废；
  已转正：从 `balance` 扣（`allowNegativeBalance` 决定能否为负），记一笔 `CLAWBACK` 流水。

---

## P3 · 已取消 / 已退款说清去向

### 三端

| 端 | 看到 / 能做什么 |
|---|---|
| **C 端 · 订单详情** | 已取消 / 已退款时多一块：「「满 30 减 5」已回到券包」「200 积分已退回」「收回本单赠送的 50 积分」。**有才说，没有不说**。 |
| **B 端 · 订单详情** | 同一块，商家客服接到「我的券呢」时要能看到。 |
| **运营端** | 无设置。 |

### 后端

订单详情（只在详情视角）在 `CANCELLED` / `REFUNDED` 时附 `returned`：
`couponTitle`（这一单用过、现在状态为 `UNUSED` 的券）· `pointsReturned`（该单 `REFUND` 流水之和）·
`pointsClawedBack`（该单 `CLAWBACK` 流水之和）。**从数据查，不从状态推**。

---

## P4 · 回补历史关单占掉的活动配额

### 三端

| 端 | 看到 / 能做什么 |
|---|---|
| **运营端 · 活动敞口** | 每个活动的「已用」旁边补一个数：「其中 N 份来自已关闭的单（已退回）」—— 让运营知道这个活动真实卖了多少。 |
| **B 端 · 活动列表** | 「已用」只算**成交的**（关单退回后自然就是这个数）。不另加字段。 |

### 执行

一次性脚本（不进 Flyway）：
1. **dry-run**：列出所有「属于已关闭主单」的 `pmt_apply` 活动行，按活动汇总「会退回几份、多少预算」→ 给你看；
2. 你确认后执行，逻辑与 B6-1 同一段（退配额、作废那一行）；
3. 回读每个活动的 `quota_used`。

---

## P5 · 活动结束 / 金额变化提示

纯 C 端。预览前后两次的 `discountLines` 相比，上次有、这次没有的活动 → 顶部提示
「「XX」活动已结束，金额已更新」。无设置。

## P6 · 两条异常分支

1. **库存变少**：预览的 `maxQty` 小于当前数量 → 自动压到上限，提示「库存变少了，已调整为 N 件」。纯 C 端。
2. **选地址时就判超出配送范围**：自送超范围今天在建单时才拦。
   改成**预览里给标记**（`OrderVO.outOfRange = true` + 超出的商家名）、建单时才拦 ——
   与自提点「预览不拦、建单才拦」同一口径。C 端选完地址当场给「换地址 / 换配送方式」。无设置。

---

## P7 · 常驻 + 无门槛直减的风险确认

### 三端

| 端 | 看到 / 能做什么 |
|---|---|
| **B 端 · 活动编辑** | 保存时若同时满足：常驻（`ALWAYS_ON`）· 无门槛（`TRIGGER_NONE`）· 直减（`CUT`）→ 弹一次确认：**「这个活动常驻、无门槛，每单减 ¥10，最多 100 单（共 ¥1000）。确认保存？」** 不拦，只让商家看清楚。没设限量时说「不限单数」并把字加重。 |
| **运营端 · 活动敞口** | 这类活动打一个「常驻无门槛」标签并置顶 —— 它们是平台最容易出现「被薅」的那一类，运营要能一眼找到。 |
| **运营端 · 功能开关** | `marketing.always-on-cut.confirm`，默认开。关掉 = 不弹确认（给已经熟悉的大商家）。 |

后端无改动（这是提醒不是规则）。「abc」这一条是**合法配置**，不改它的数据。

---

## P9 · 支付方式记住上次选择

纯 C 端，存在本机。记住在线支付方式；**当面付不记**（受商家与券限制，默认回到微信支付更稳）。无设置。

---

## 新增设置一览

| 键 | 位置 | 默认 | 关掉的效果 |
|---|---|---|---|
| `trade.purchase-limit.enforce` | 运营端 · 功能开关 | 开 | 限购只显示不拦（今天的行为） |
| `refund.return-coupon` | 运营端 · 功能开关 | 开 | 整单退款不退券 |
| `refund.return-points` | 运营端 · 功能开关 | 开 | 整单退款不退抵扣积分 |
| `refund.clawback-earned` | 运营端 · 功能开关 | 开 | 整单退款不收回已发放积分 |
| `marketing.always-on-cut.confirm` | 运营端 · 功能开关 | 开 | B 端保存常驻无门槛直减不弹确认 |
| `allowNegativeBalance` | 运营端 · 积分规则（`points.config`） | true | 收回只扣到 0，差额记「未收回」 |

功能开关要在运营端的开关列表里登记（它读的是一张登记表，没登记的键不会出现在页面上）。

---

## 测试与验收

每条：后端用例 + 消融（撤掉修复必须变红）+ 开关关掉那一支也要有用例（**默认关的那一半最没人测**）。
P4 执行前后各回读一次线上数据。P2b 上线前与结算域对账一次。

---

## 落地记录（2026-09-21）

| # | 落点 | 用例 |
|---|---|---|
| P1 | `PurchaseLimitGuard`（建单 / 加购 / 加量三处共用）；预览 `ItemVO.limitReason / limitPerUser / boughtQty`；错误码 20007 | `PurchaseLimitFlowTest` 6 条（含开关关闭、取消后恢复）；c-app `purchase-limit` |
| P2a | `AfterSaleServiceImpl#returnBenefitsIfWholeOrderRefunded` 逐子单 `reverse` | `M5AfterSaleFlowTest#pointsReturnedOnWholeOrderRefund`、`#pointsKeptWhenSwitchOff` |
| P2b | `PointsService#refundConfirmed`；池子类型 `MERCHANT_PAY_REVERSE`（入池） | `PointsRevokeFlowTest#confirmedRefund*` |
| P2c | `PointsService#revokeEarned`；**用现成的 `REVOKE`（退款扣回）流水**，不另造 CLAWBACK；`points.config.allowNegativeBalance` | `PointsRevokeFlowTest` 5 条 |
| P3 | `OrderVO.returned`（C/B 详情）；`CouponPortRouter` 必须转发 `returnedTitleOf` | `M5AfterSaleFlowTest#cancelledOrderSaysCouponReturned` |
| P4 | 运营端敞口 `quotaReleased`；**线上回补待确认** | `ActivityCutTriggerFlowTest#quotaGoesBackOnRelease` |
| P5 / P6 | 确认页 `endedActivities` / `clampToMax`；预览 `outOfRange`（与建单共用 `outOfRangeMerchants`） | `DeliveryRadiusFlowTest#previewFlagsOutOfRange`；c-app `checkout-notices` |
| P7 | `BizActivityController` 回 40034、B 端确认后带 `riskConfirmed` 重提；运营端 `ALWAYS_ON_FREE_CUT` 置顶 | `AlwaysOnCutConfirmTest` 4 条；`#alwaysOnFreeCutFlaggedAndFirst` |
| P9 | 确认页 `lastPayMode`（本机，当面付不记） | c-app `checkout-notices` |

### 与设计有出入的三处

1. **P2b 的池子入账有条件**：只在「结算单已回退、有补差、补差已收回（`subsidy_at` 清空）」时入池。
   补差回退失败或归集路径已打款时，分照退、池子不入账 —— 差额就是待追回的钱，恒等式巡检会亮出来。
2. **P2c 已结算的单才出池**：发分费是结算时才入池的，没结算的单收回分不动池子，否则池子被扣成负的。
3. **B 端 P7 的开关不在端上读**：B 端没有读平台开关的接口，判据与开关都放在后端（40034），端上只负责把钱数说出来。

### 开发中踩到的

- 新测试的手机号与 `CampaignDiscountFlowTest` 撞号，残留的购物车让那边「买 2 件」凑成「买 3 送 1」—— 单独跑绿、全量红。
  已换独占号段，并只清本用例期间新增的购物车行。
- `CouponPortRouter` 是 @Primary 的分流器，接口上加的默认方法它不转发就永远落到默认值，页面安安静静不显示。

### P4 线上 dry-run（只读，2026-09-21）

| 活动 | 限量 | 已用 | 其中来自已关闭单 | 退回预算 |
|---|---|---|---|---|
| abc（PT202609201328190007701） | 100 | 5 | 5 | ¥40.30 |

**已执行（用户 2026-09-21「继续」确认）。** 执行前复核发现多了 1 份：14:24 新下的一张**待付款**单（新代码上线后），
不属于已关闭单，不动 —— 它若超时关单会由新逻辑自动退。一个事务内：退 5 份 / ¥40.30、作废 5 行。

提交后另开连接回读：已用 **1**、预算已用 **1000**（即那张待付款单）；作废行共 7 条
（本次 5 条 + 上线后关单逻辑已自动退的 2 条），运营端「来自已关闭的单」显示 7。
