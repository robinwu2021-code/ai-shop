# TDD-支付域 · 领域对象与关联

> 状态：**方案待评审** · 创建 2026-09-01
> 上游：[TDD-支付域 · 三端需求矩阵](./TDD-支付域-三端需求矩阵.md)
> 下游：数据库设计 → API → Controller/Service/Mapper → 代码对齐
> 参考：[TDD-支付域 · 多区域通道](./TDD-支付域-多区域通道.md) · [TDD-支付域 · 数据库设计](./TDD-支付域-数据库设计.md)

---

## L1 · 定位

**支付域有哪些领域对象、它们之间是什么关系、各自的状态机。**

两条写法上的规矩：

1. **领域对象 ≠ 表。**「账户 + 流水」是<b>同一个对象的两面</b>，
   不是两个对象 —— 账户是当前值，流水是它怎么变成这个值的。
   按表数会数出 22 张，按对象数是 <b>13 个</b>（§二 逐个列了，可以自己数）。
2. **写现状，不写愿望。**下面每一处「今天没有」都是 2026-09-01
   当场验过的，不是估计。三个空洞见 §五。

---

## L2 · 一、四个横切概念 —— 先把它们分开

需求矩阵里反复出现四个词。它们**不是四根平行的轴**，
搞混任意两个，多区域就做不下去：

| 概念 | 是什么 | 例子 | 今天在哪 |
|---|---|---|---|
| **市场 market** | 在哪个法域经营。决定币种、法规、能用哪些通道 | `CN` `TW` `HK` `AE` `SA` `SG` | ❌ 只有 `sys_pay_channel.markets` 一个字符串列 |
| **币种 currency** | 这笔钱是什么钱。**决定金额能不能相加** | `CNY` `TWD` `AED` | 🟡 只有 `stl_payment.currency`；结算侧三张表都没有 |
| **通道 channel** | 钱从哪家机构走。**决定进件、费率、对账口径** | `WECHAT` `ALIPAY` `STRIPE` | ✅ `sys_pay_channel` |
| **支付方式 method** | 用户看到并点的那个按钮 | 微信、支付宝、刷卡、Apple Pay | ❌ 今天不存在；C 端只有一个写死的 `STUB` |

关系：

```
市场 ─1:N─→ 通道           一个市场有多个可用通道
市场 ─1:1─→ 币种           一个市场一种记账币种（展示折算另说）
通道 ─1:N─→ 支付方式       一个通道可以提供多种方式（微信下面有 JSAPI / 小程序 / H5）
通道 ─1:N─→ 费率版本        按 (market × legal_form) 分档，只增不改
```

**最容易犯的错**是把「支付方式」当成「通道」。
用户点的是「微信支付」，而钱走的可能是微信直连、也可能是某个聚合网关 ——
海湾与东南亚的现实是后者。两者合成一个的话，
换网关就要改 C 端的按钮，而那本该是一个后台配置。

---

## L2 · 二、13 个领域对象

按**谁的钱**分四组。分组不是分类癖：<b>组内可以互相引用，跨组只能通过号引用</b>，
D2 拆库时这条决定哪些表能一起搬。

### 组 A · 买家付的钱

| 对象 | 表 | 说明 |
|---|---|---|
| **A1 支付流水** `Payment` | `stl_payment` | 一笔订单**一笔或多笔**（失败重试换商户单号）。五个方向：收款/退款/补差/补差回退/打款 |
| **A2 渠道报文** `ChannelMessage` | `stl_channel_message` | 一笔流水**多条报文**（发送 + 回调）。V286 |

### 组 B · 商家收的钱

| 对象 | 表 | 说明 |
|---|---|---|
| **B1 结算单** `Bill` | `stl_bill` | **一子单一张**。它是「这一单该给商家多少」 |
| **B2 账期批次** `SettleBatch` | `stl_settle_batch` | 按 (主体 × 通道 × 应结日) 成批。放款的单位 |
| **B3 分账流水** `SplitLog` | `stl_split_log` | append-only。分账/回退/补差/补差回退四种指令 |
| **B4 提现单** `Withdraw` | `stl_withdraw` | **审批与留痕，不打款**（见 §五·3） |

### 组 C · 平台的账

| 对象 | 表 | 说明 |
|---|---|---|
| **C1 对账差异** `ReconDiff` | `stl_recon_diff` | 四条轴的产出。一条差异一个结论，裁完是终态 |
| **C2 发票** `Invoice` | `stl_settle_invoice`（销项）<br>`stl_purchase_invoice`（进项） | 两个方向，两张表，同一族 |
| **C3 通道主数据** `PayChannel` | `sys_pay_channel` + `sys_pay_channel_rate` | **账户 + 时间轴**：通道属性，加只增不改的费率版本 |
| **C4 平台佣金费率** `FeeRule` | `stl_fee_rule` | 只增不改。**与 C3 的通道费率是两回事**：这是平台向商家收的佣金，那是通道向平台收的手续费。两者都影响结算净额，混为一谈会让账差一层 |

### 组 D · 积分与商家资金

| 对象 | 表 | 说明 |
|---|---|---|
| **D1 积分账户** `PointsAccount` | `pts_user_account` + `pts_user_ledger` | 账户 + 流水两面 |
| **D2 积分资金池** `PointsPool` | `stl_points_pool` | 预付费模型的钱那一侧 |
| **D3 商家资金账户** `MerchantFund` | `mch_deposit`+`_txn`、`mch_debt`+`_txn` | 保证金与欠款。**两个账户，四张表** |

> 13 个对象共占 19 张表；另外 3 张见下。19 + 3 = 22，与库里的支付域表数对得上。

> 另有三张不是领域对象：`mch_payment_merchant`（进件档案，属于商家域）、
> `pay_setting`（支付域设置）、`pay_risk_shadow_log`（风控影子日志）。

---

## L2 · 三、关联全图

```
                        ┌──────────────── 通道主数据 (C3) ────────────────┐
                        │  sys_pay_channel ──1:N── sys_pay_channel_rate  │
                        │   markets / currency        费率版本 只增不改   │
                        └───────────────┬────────────────────────────────┘
                                        │ pay_channel
     订单域                             ▼
   ord_order ──1:N── ord_sub_order      │
        │                  │            │
   order_no │        sub_order_no       │
        ▼                  │            │
  ┌─ A1 支付流水 ───────────┼────────────┘         out_trade_no = 订单号[-尝试序号]
  │  stl_payment           │                      direction: PAY/REFUND/SUBSIDY/
  │   ▲ payment_no         │                                 SUBSIDY_REVERSE/PAYOUT
  │   │ 1:N                │                      status: INIT→PENDING→SUCCESS/FAILED/CLOSED
  │  A2 渠道报文            │
  │  stl_channel_message   │  ← 发送与回调各一条，独立事务
  │                        │
  │  C1 对账差异 ◄──────────┘  按 payment_no 关联；四条轴
  │  stl_recon_diff            PENDING → RESOLVED / IGNORED（终态）
  └────────────────────────────────────────────────────────────
                             │
                    sub_order_no
                             ▼
  ┌─ B1 结算单 ──────────────────────────────── B2 账期批次 ─────┐
  │  stl_bill （一子单一张）        batch_no    stl_settle_batch  │
  │  PENDING→SPLITTING→SPLIT→SPLIT_CONFIRMED   DRAFT→COLLECTED→ │
  │       →PAID / REVERSED / OFFLINE_SETTLED    RECONCILING→     │
  │   │                                          RECONCILED/     │
  │   │ settle_no                                BLOCKED→RELEASED│
  │   ▼                                                          │
  │  B3 分账流水 stl_split_log （append-only）                     │
  │     SPLIT / REVERSE / SUBSIDY / SUBSIDY_RETURN               │
  │   │                                                          │
  │   └─ purchase_invoice_no ─→ C2 进项票 stl_purchase_invoice    │
  └──────────────────────────────────────────────────────────────┘
                             │ entity_no
                             ▼
  ┌─ D3 商家资金 ────────────────────────┐   ┌─ B4 提现单 ────────┐
  │  mch_deposit ──1:N── mch_deposit_txn │   │  stl_withdraw     │
  │  mch_debt    ──1:N── mch_debt_txn    │   │  PENDING→APPROVED │
  │   （保证金）      （欠款）             │   │   →PAID / REJECTED│
  └──────────────────────────────────────┘   └───────────────────┘
        ▲ 风控读这两个数（fundRiskFacts）

  ┌─ D1 积分账户 ───────────────── D2 积分资金池 ──────┐
  │  pts_user_account            stl_points_pool     │
  │   balance / pending_balance   IN / OUT           │
  │    ──1:N── pts_user_ledger                       │
  │      EARN/USE/REFUND/EXPIRE/REVOKE               │
  │      × PENDING/CONFIRMED/REVERSED                │
  │  恒等式：流通中的积分 ≡ 池子里的钱                  │
  └──────────────────────────────────────────────────┘
```

---

## L3 · 四、跨对象的不变式

已经在跑的巡检（I1–I8，见实施排期 §二·五）落到对象上是：

| # | 不变式 | 涉及对象 |
|---|---|---|
| I1 | 已支付子单**必须有**结算单 | A1 ⇄ B1 |
| I2 | 结算单的子单**必须真的付了**（无孤儿账） | B1 ⇄ 订单域 |
| I3 | 标着已发积分的子单**必须有**发分流水 | 订单域 ⇄ D1 |
| I6 | 滞留的积分预占要释放 | D1 ⇄ 订单域 |
| I8 | 流水 SUCCESS 的，其订单**必须是** PAID | A1 ⇄ 订单域 |
| — | 流通中的积分 ≡ 池子里的钱（恒等式 2） | D1 ⇄ D2 |
| — | 批次合计 ≡ 其下结算单之和 | B1 ⇄ B2 |

**多区域会加一条**：批次合计只能加**同币种**的结算单。
今天不成立不是因为写错了，是因为 `stl_bill` 根本没有币种 —— 见 §五·1。

---

## L4 · 五、量出来的三个空洞

这三条都是 2026-09-01 当场验的，不是推测。

### 1. `stl_payment` 五个方向，只有 `PAY` 在写

`setDirection(...)` 在整个后端生产代码里只有**两处**：
一处写 `PAY`，一处是积分池的方向（不同的表）。

`REFUND` / `SUBSIDY` / `SUBSIDY_REVERSE` / `PAYOUT` 四个常量都定义了，
类注释还写着「五种方向同一张表：它们的通道回执结构一样、
幂等要求一样、**对账要一起做**」—— 而后三句话从来没有兑现过。

**后果**：退款、补差、打款这三类资金动作**没有任何流水行**，
因而也<b>不在对账范围内</b>（对账扫的是 `direction = PAY`）。
「对账覆盖范围」那句话今天说的是「渠道账单未接入」，
而它没说的是「另外四个方向也没有账可对」。

### 2. 退款没有领域对象

`SettleServiceImpl.refund(...)` 的全部实现是：

```java
log.info("refund subOrder={} amount={} reason={}", subOrderNo, amountMinor, reason);
return "REFUND-" + subOrderNo;
```

一行日志，加一个**编造的单号**。而调用方
（`AfterSaleServiceImpl:327`）把返回值**丢弃了** —— 所以那个假单号
没有污染数据，但也意味着：<b>一笔退款发生过之后，
资金侧没有任何东西记得它。</b>

退款今天真正动到的是这三处，各自独立：
`pts_user_ledger.REFUND`（退积分）、`stl_split_log.REVERSE`（回退分账）、
`mch_debt_txn.SRC_REFUND`（记欠款）。
**三条腿，没有身体** —— 没有一个「退款单」把它们串起来，
所以「这笔退款做完了没有」这个问题今天没有地方可以问。

### 3. 提现单不打款（这一条是刻意的，不是洞）

`StlWithdraw` 的类注释明确写着「这张表不打款」，
`APPROVED → PAID` 刻意不给人工入口 —— 打款结果只能来自渠道回执。
列在这里是为了**不要把它误当成缺口去"修"**：
它等的是分账参数的书面口径，不是等代码。

---

## L4 · 六、这一步引出的决定

1. **退款要不要建成一个领域对象**（`Refund`，或者就用 `stl_payment` 的 `REFUND` 方向）。
   建议用方向而不是新表：类注释里「五种方向同一张表」的三条理由今天依然成立，
   缺的只是**去写它**。
2. **市场要不要成为一张表**。
   建议是：`sys_market`（市场 → 币种 → 时区 → 启用），
   而不是继续用字符串列 —— 因为「一个市场一种记账币种」这条规则
   需要有地方存，散在各表的 `market_code` 上没法约束。
3. **支付方式要不要独立于通道**。
   建议是要（`sys_pay_method`，挂在通道下），理由见 §一 最后一段。
   不做的话，接聚合网关那天 C 端按钮要跟着改。

三条都影响下一步的数据库设计，**要先定**。
