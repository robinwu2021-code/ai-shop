# TDD-支付域 · 三端 API ⇄ 功能 ⇄ pay 能力

> 状态：**方案待评审** · 创建 2026-09-01
> 上游：[TDD-支付域 · API设计（收款与多区域）](./TDD-支付域-API设计（收款与多区域）.md) ·
> [TDD-支付域 · 需求代码双向对齐](./TDD-支付域-需求代码双向对齐.md)
> 服务于：[TDD-支付域 · 实施方案与排期](./TDD-支付域-实施方案与排期.md) 的 C4 / D1（pay-svc 接真流量）

---

## L1 · 这份回答什么

**三端所有与钱有关的接口，各自背后是不是 pay 服务的能力。**

```
端 API（C / B / 运营）  →  业务功能域  →  pay 服务能力
```

它直接决定 **pay-svc 独立部署后，哪些端接口走远程、哪些留在主应用**。
今天那条线是模糊的 ——「支付相关」与「pay 域的」被当成一回事，而它们不是。

**覆盖范围**：收款 · 退款与售后 · 进件与渠道 · 结算与分账 · 对账 ·
提现 · 积分 · 发票 · 保证金与欠款 · 设置。

**口径**：路径命中资金语义（含 `after-sale`/`refund` 退款、`setting`/`rule`/`policy`
设置、`balance`/`txn` 流水），排除误伤词。<b>误伤词是量出来的</b> ——
第一版口径把「通知渠道配额」「自提点」「库存余额」「会员设置」都算成了支付接口。

「落到 pay 域」= 从 Controller 逐跳追依赖（最多三跳），最终有一跳落在
`pay/pay-domain` 或 `pay/pay-channel`。<b>C3 之后 controller 都在主应用侧，
只看第一跳会把 42 个端点误判成「不走 pay」</b>。

---

## L2 · 一、全景

### 按端

| 端 | 端点 | 落到 pay 域 | 留主应用 |
|---|---:|---:|---:|
| C 端 `/mp` | 15 | 12 | 3 |
| B 端 `/biz` | 29 | 22 | 7 |
| 运营端 `/ops` | 69 | 50 | 19 |
| 内部 `/internal` | 5 | 5 | 0 |
| **合计** | **118** | **89** | **29** |

### 按功能域

| 功能域 | C | B | 运营 | 内部 | 走 pay |
|---|---:|---:|---:|---:|---|
| 结算与分账 | 0 | 9 | 12 | 5 | **26/26** |
| 退款与售后 | 7 | 4 | 6 | 0 | 16/17 |
| 保证金与欠款 | 0 | 3 | 12 | 0 | **5/15** ← 见 §四·1 |
| 发票 | 3 | 0 | 11 | 0 | 8/14 ← 三张票，见 §四·2 |
| 积分 | 3 | 3 | 5 | 0 | 8/11 |
| 进件与渠道 | 0 | 5 | 3 | 0 | **8/8** |
| 收款 | 2 | 1 | 4 | 0 | 4/7 |
| 设置 | 0 | 2 | 4 | 0 | 3/6 ← **有缺陷，见 §四·4** |
| 对账 | 0 | 0 | 6 | 0 | 5/6 |
| **提现** | **0** | **0** | **2** | 0 | 2/2 ← **功能不存在，见 §四·3** |

**两个格子值得盯着看**：
- **提现的 C 与 B 两列都是 0** —— 商家没有任何地方可以申请提现。
- **收款的 C 端只有 2 个** —— 发起支付、查结果。选支付方式、换方式重付都没有。

---

## L2 · 二、按功能域逐个对齐

### 收款（7）

| 端 | 端点 | pay 能力 |
|---|---|---|
| C | `POST /mp/order/{orderNo}/pay` | ✅ `PaymentLedgerService.open` |
| C | `GET /mp/order/{orderNo}/pay-result` | ✅ 订单状态（由 pay 回调推进） |
| B | `POST /biz/order/{subOrderNo}/confirm-offline-pay` | ✅ `SettleService`（线下已结） |
| 运营 | `GET/PUT /ops/payments/close-rule` | ❌ 关单策略在主应用 |
| 运营 | `GET/POST /ops/category-pay-modes` | ❌ 类目支付方式，**商品域，归属正确** |

**缺**：C 端选支付方式（C-1）、换方式重付入口、按币种显示 —— 见 API 设计 §四。

### 退款与售后（17）

| 端 | 端点 | pay 能力 |
|---|---|---|
| C | `/mp/after-sale/**`（7 个：申请、列表、详情、取消、上报、寄回） | ✅ 经 `AfterSaleService` → 分账回退 |
| B | `/biz/after-sale/**`（4 个：列表、同意、收货、拒绝） | ✅ 同上 |
| 运营 | `/ops/after-sale/**` + `/ops/refund-split-backs/**`（6 个） | ✅ `RefundSplitBackService` |

**退款对象在订单域（售后单），资金动作在 pay 域** —— 这个划分是对的，
详见双向对齐 §三·2。**缺的是支付域那一半**：
`REFUND` 方向的流水从没写过，售后单上的退款流水号字段只声明没赋值。

### 进件与渠道（8，全部走 pay）

| 端 | 端点 | pay 能力 |
|---|---|---|
| B | `GET /biz/merchant/pay-channel` | `PayChannelMasterService.enabled` |
| B | `GET/POST /biz/merchant/payment` | 商家域进件服务 + 通道主数据 |
| B | `POST /biz/merchant/payment/store/{storeNo}` | 按门店开通 |
| B | `POST /biz/merchant/payment/{payChannel}/refresh` | 刷新通道审核状态 |
| 运营 | `GET/PUT /ops/settle/pay-channels/**`（3 个） | `PayChannelMasterService` · `PayChannelRateService` |

**渠道选择今天不按市场筛**：`GET /biz/merchant/pay-channel` 取的是
「不限市场」的通道列表 —— 台湾商家与大陆商家看到同一份。

**缺**：进件驳回原因的中文映射（B-2）、运营端的进件视图。

### 结算与分账（26，全部走 pay）

| 端 | 端点数 | pay 能力 |
|---|---:|---|
| B | 9 | `SettleService`（结算单、收入汇总、对账单、费率卡）· `SettleBatchService`（我的批次） |
| 运营 | 12 | 同上 + `FeeRuleService` · `FundRiskService`（截批时的风控判定） |
| 内部 | 5 | `/internal/pay/fee-rules/**` · `/internal/pay/settle-invoices/**`（已远程化） |

**这是覆盖最完整的一个域** —— 26 个端点全部落到 pay，
且已有 5 个走内部接口。它是 pay-svc 远程化最成熟的部分。

### 对账（6）

| 端点 | pay 能力 |
|---|---|
| `GET /ops/payments/recon-axes` | `ReconService.scanAllAxes`（四条轴） |
| `GET /ops/payments/recon-coverage` | `ReconService.coverage`（**覆盖范围**） |
| `GET /ops/payments/recon-diffs` | `ReconService.diffs` |
| `POST /ops/payments/recon-diffs/{diffNo}/resolve` · `/ignore` | `ReconService.decide` |
| `GET /ops/inventory/recon` | ❌ **库存对账，误伤，归属正确** |

### 积分（11）

C 端 3（账户、可抵扣、明细）· B 端 3（账户、明细、开关）· 运营端 5。
`PointsService` 33 个方法是 pay 域最大的一个接口。

**其中 2 个有缺陷** —— `/ops/points/client-policy` 读写的表与支付域读的不是同一张，见 §四·4。

### 发票（14）—— 三张票

见 §四·2。

### 保证金与欠款（15）

见 §四·1。

### 提现（2）

见 §四·3。

### 设置（6）

| 端点 | 落到哪 |
|---|---|
| `GET/PUT /ops/finance/tax-rule` | ✅ `WithdrawService`（个税代扣规则） |
| `GET/POST /ops/finance/invoice-title` | ✅ 平台开票抬头 |
| `GET/POST /ops/points/client-policy` | ⚠️ **写 `sys_setting`，而支付域读 `pay_setting`** |
| `GET/PUT /biz/member-settings` | ❌ 会员设置，误伤 |

---

## L2 · 三、pay 服务的能力清单（14 个接口）

| 能力 | 方法数 | 谁在用 | 独立后 |
|---|---:|---|---|
| `PointsService` | 33 | C 端 3 · B 端 3 · 运营端 5 | 远程 |
| `SettleService` | 28 | B 端 9 · 运营端 12 | 远程 |
| `ReconService` | 10 | 运营端 5 | 远程 |
| `SettleBatchService` | 8 | B 端 1 · 运营端 6 | 远程 |
| `PayChannelMasterService` | 6 | B 端 1 · 运营端 3 | **已远程** |
| `FundInvariantService` | 6 | 巡检（无端点） | 远程 |
| `PayChannelRateService` | 4 | 运营端 3 | **已远程** |
| `FeeRuleService` | 4 | B 端 1 · 运营端 3 | 远程 |
| `WithdrawService` | 4 | 运营端 2 | 远程。**但没有申请入口，见 §四·3** |
| `SettleInvoiceService` | 3 | B 端 2 · 运营端 3 | **已远程** |
| `PaymentLedgerService` | 2 | C 端 1 · 回调 2 | **回调直接进 pay，不远程** |
| `RefundSplitBackService` | 2 | 运营端 2 | 远程 |
| `PaySettingService` | 2 | pay 内部 | 不出网 |
| `FundRiskService` | 1 | 截批内部调用 | pay 内部 |

### 切分线

```
留在主应用                       走远程调用                  pay 内部
──────────                      ──────────                 ────────
消费者发票（订单域）             结算 · 分账 · 批次           风控判定
保证金与欠款（→ M11 后搬走）      积分 · 对账 · 费率           不变式巡检
类目支付方式（商品域）           提现 · 结算发票              报文落库
关单策略 · 会员设置              通道主数据 · 通道费率         支付域设置
门店收款配置（商家域）
                                ┌──────────────────────┐
       通道回调 ────────────────→│ 直接进 pay，不经主应用  │
                                └──────────────────────┘
```

**通道回调那条线不可协商**：收款这条链<b>不能依赖主应用可用</b>，
否则主应用挂了钱就收不进来。已定（实施排期 §六·1），
`pay-svc` 因此需要验签能力与通道密钥。

---

## L3 · 四、四处要处理的

### 1. 保证金与欠款：15 个端点，只有 5 个走 pay

`mch_deposit` / `mch_deposit_txn` / `mch_debt` / `mch_debt_txn` ——
**带流水表的账户，那是资金**。而它们在商家域，
支付域用（风控判定读保证金与欠款）时反向调过去。

这就是 **M11**：双向对齐里判定「改参数传入」的分类不成立
（截批是 pay 内部的定时链路，没有调用方），正确做法是搬表。

~~**它是 pay-svc 独立后最后一条反向依赖。**~~

⚠️ **2026-09-02 数了一遍，那句话不对。**

pay 域今天引用业务侧 Port **41 处**：`MerchantQueryPort` 27 处 / 6 个文件，
`SettleSourcePort` 14 处 / 5 个文件。而 M11 能消掉的只有 `fundRiskFacts`
**一处**调用 —— 41 分之 1。其余 40 处是主体属性（资金模式、经营模式、
法律形态、市场、积分开关…）与订单来源，**搬四张表一处都动不了**。

（`PointsPort` / `MarketPort` / `PayChannelMasterPort` 也出现在 pay 里，
但那是 pay **实现**给业务用的，方向正确，不算在内。把它们算进来会让这个数
虚高一倍 —— 而虚高的数字会让「还差很多」变成一句无法证伪的话。）

这个差别决定要不要现在做：**M11 不是解锁项**，做完 pay-svc 还是接不了流量。
它是 D2（独立库）之前的归属整理，而 D2 本身是被推后的
——「唯一不可轻易回退的一步」。所以 M11 应当**与 D2 同批做**，
在那之前搬表只是把同一批文件动两遍。

数字钉在闸门里（`PayReverseDependencyBudgetTest` + `backend/known-pay-reverse-deps.txt`，
只准变少），因为这一轮已经有四次「自称数字写错」。

### 2. 三张发票，需求层漏了一张

| 票 | 谁开给谁 | 表 | 域 |
|---|---|---|---|
| 消费者销项票 | 平台 → 消费者 | `ord_invoice_request` | **订单域** |
| 结算凭证 | 平台 → 商家 | `stl_settle_invoice` | pay |
| 进项票 | 商家/供应商 → 平台 | `stl_purchase_invoice` | pay |

代码注释里早写明了「不要和另外两张票混」，而**需求层只写了两个对象**。
消费者票留在订单域是对的：它跟着订单走、由消费者发起，与结算周期无关。

### 3. ⚠️ 提现功能不存在

| 事实 | 依据 |
|---|---|
| C 端与 B 端都**没有**提现端点 | 全仓只有 `/ops/finance/withdrawals` 两个 |
| `WithdrawService` **没有申请方法** | 只有 `list` / `decide` / `taxRule` / `saveTaxRule` |
| 提现单在**生产代码里从没被创建过** | `new StlWithdraw()` 只出现在两个测试文件里 |

**所以运营端的提现审批页永远是空的**，`decide` 永远不会作用于真实数据。

`StlWithdraw` 的类注释说「这张表不打款，记的是审批与留痕」——
那是关于**打款**的设计决定，而这里缺的是**申请**这一步。
两件事不能混为一谈：不打款是刻意的，没有申请入口不是。

**要定**：商家提现走不走产品？如果一期就是「线下结算」，
那这张表与这两个端点<b>应当明确标注为未启用</b>，
而不是留一个看起来能用的空页面。

### 4. ⚠️ 积分端策略：两张表，运营改了支付域读不到

| 谁 | 读写哪张表 |
|---|---|
| 运营端 `POST /ops/points/client-policy` | 写 `sys_setting`（经 `SettingPort`） |
| 支付域 `PointsService.policy()` | 读 `pay_setting`（经 `PaySettingService`） |

**M2 把这个键搬进 pay 库时，漏了运营端那一侧。**

后果：运营在页面上禁用某个端的积分发放，保存成功、页面显示正确，
而<b>支付域完全读不到这个改动</b> —— 积分照发。
一个「改了没生效且不报错」的开关。

这是**当前生产就存在的缺陷**，不是设计问题。修法有两条：
让运营端也写 `pay_setting`（推荐，与 M2 的方向一致），
或者把这个键搬回 `sys_setting`（与 M2 相反，不推荐）。

**补一条闸门**：同一个设置键不能同时出现在两个设置服务的调用里 ——
这可以做成静态检查，扫常量键名的引用点。

---

## L4 · 五、这份对齐引出的四条

1. **C 端收款只有 2 个端点，缺的四条一个都没有。**
   收款上线的工作量在 C 端，服务端能力基本都在了。
2. **提现整个功能不存在**（§四·3），要先定产品口径再谈实现。
3. **积分端策略的双表缺陷**（§四·4）是当前生产问题，优先级高于本轮所有设计工作。
4. **M11 是 pay-svc 独立的最后一条反向依赖**，在它之前独立部署仍要反向调商家域。
