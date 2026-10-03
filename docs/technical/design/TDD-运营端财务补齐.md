# TDD-运营端财务补齐（提现与税 · 退款回退分账）

状态：**已确认（2026-08-13 用户授权批量推进）**
关联需求：[需求矩阵-三端](../../requirements/需求矩阵-三端.md) §六 **P-12.2 提现与税**（12.2.1 提现审批 · 12.2.2 限额与频次 · 12.2.3 个税代扣 · 12.2.4 结算凭证/发票）与 **P-12.1.5 退款回退分账**（=[待完成功能清单](../../requirements/待完成功能清单.md) E4，标「高风险」）
上游决策：[ADR-002 结算走微信支付分账](../ADR/ADR-002-结算走微信支付分账.md)（已分账订单退款要先回退分账）· [ADR-017 资金归集与结算方式](../ADR/ADR-017-资金归集与结算方式.md)
创建日期：2026-08-13
覆盖端点：9 条（ops-web `lib/api/https/finance.ts` 里已接、后端 404 的那一批）

---

## 一、一句话

把 ops-web `/finance` 页最后三个 tab（**退款回退分账 · 提现审批 · 发票与个税**）
从「界面画完、点下去 404」变成真链路——**运营端只做审批与留痕，不碰支付通道**。

---

## 二、为什么是这个方案

### 2.1 三个 tab 的后端现状（现扫结论，两条推翻直觉）

| # | 事实 | 对设计的影响 |
|---|---|---|
| 1 | **`/ops/invoice-requests` 已经存在**（`OpsInvoiceRequestController`，V94），但它是**平台开给消费者**的票（`ord_invoice_request`，C 端申请）——与 P-12.2.4「商家结算凭证/发票」不是一回事 | 不能复用。商家结算票另建 `stl_settle_invoice`，走 `/ops/finance/invoices` |
| 2 | **`/ops/purchase-invoices` 也已经存在**（`StlPurchaseInvoice`），那是**供应商开给平台**的进项票（自营应付） | 同上，三张票是三个方向：进项 / 销项（对 C）/ 销项（对商家）。合表会让「谁欠谁」这件事再也说不清 |
| 3 | `AfterSaleServiceImpl.doRefund` **已经**按 ADR-002 的顺序做了「先回退分账再退款」，且失败即抛 `SPLIT_EXPIRED` 中止 | 顺序不需要重做。缺的是**卡住之后的出口** |
| 4 | `AfterSaleServiceImpl.arbitrate(refund=true)` **只把状态改成 `REFUNDING`，不调 `doRefund`** | ⭐ 这才是队列的真正来源：平台裁决支持退款之后，**钱一分没退**，单子停在 `REFUNDING` 等一个不会来的动作 |
| 5 | `Perms.ROLE_PERMS` 里 **FINANCE 角色早就存在**（15 个码），任务书说的「后端未实现 FINANCE 角色」已过期 | 不必新建角色，只补一个码 |

### 2.2 被否掉的选项

**（A）「待回退分账队列」另建一张 `stl_refund_split_back` 表** —— 否。
ops-web mock 里那句注释是对的：*队列直接由售后单派生，不另建实体：另建就有两份真相，且一定会不同步*。
更实际的理由是：写队列行的时机在 `reverseSplit` 失败那一刻，而那一刻整个售后事务会回滚，
队列行跟着一起没了——**这张表在最需要它的路径上恰好是空的**。

**（B）执行回退只做「回退分账」，退款留给别的入口** —— 否。
回退成功后 `stl_bill` 变 `REVERSED`，队列项确实消失了，但**买家的钱一分没回来**。
把「队列空了」当成「事办完了」，是这条链路上最贵的一种假绿。

**（C）在 settle 域里自己写一段 `UPDATE ord_after_sale SET status='REFUNDED'`** —— 否。
`OrderRepairPort` 的类注释已经把代价写清楚了：退款收尾要做的不止改一个字段
（子单转态、发 `AfterSaleRefunded` 事件、下游据此回补库存与评分）。
在别处抄一遍，漏掉的那几件不会报错。

**采用（D）**：加一个**只读 + 一个动作**的 SPI 端口，动作是**既有链路的入口**而不是新写的收尾逻辑——
与 `OrderRepairPort.markPaid` 完全同形。

---

## 三、结构

```
ops-web /finance
   ├─ tab=refund-back ──► GET  /ops/refund-split-backs          ┐
   │                      POST /ops/refund-split-backs/{as}/execute
   ├─ tab=withdraw   ──► GET  /ops/finance/withdrawals          │  shop-settle
   │                      POST /ops/finance/withdrawals/{no}/decide   api/ops
   └─ tab=invoice    ──► GET  /ops/finance/invoices             │
                          POST /ops/finance/invoices/{no}/issue  │
                          POST /ops/finance/invoices/{no}/reject │
                          GET  /ops/finance/tax-rule            │
                          PUT  /ops/finance/tax-rule            ┘

RefundSplitBackServiceImpl（shop-settle）
   ├─ 读：RefundSplitBackPort.pending()   ← ord_after_sale（REFUNDING 且未回退）
   ├─    ∩ stl_bill（该子单的结算单存在且尚未 REVERSED）
   ├─ 写①：SettleService.reverseSplit(subOrderNo)   ← 先回退（ADR-002）
   └─ 写②：RefundSplitBackPort.resumeRefund(asNo)   ← 再走既有 doRefund
```

| 端点 | 权限码 | 复用/新增 |
|---|---|---|
| `GET /ops/refund-split-backs` | `finance:settle:read` | 复用 |
| `POST /ops/refund-split-backs/{asNo}/execute` | `finance:settle:execute` | 复用 |
| `GET /ops/finance/withdrawals` | `finance:withdraw:approve` | **新增码** |
| `POST /ops/finance/withdrawals/{no}/decide` | `finance:withdraw:approve` | 同上 |
| `GET /ops/finance/invoices` | `finance:invoice:read` | 复用 |
| `POST /ops/finance/invoices/{no}/issue` | `finance:invoice:verify` | 复用 |
| `POST /ops/finance/invoices/{no}/reject` | `finance:invoice:verify` | 复用 |
| `GET /ops/finance/tax-rule` | `finance:invoice:read` | 复用 |
| `PUT /ops/finance/tax-rule` | `finance:invoice:verify` | 复用 |

新增表：`stl_withdraw`（V110）、`stl_settle_invoice`（V111）。
个税规则**不建表**：它是一组参数不是一批记录，落 `sys_setting`（与费率之外的其余可调参数同处）。

---

## 四、详细设计（每条写「防住什么」）

### 4.1 退款回退分账（P-12.1.5 / E4）

**队列口径**（三个条件，缺一个队列就是错的）：

1. `ord_after_sale.status = REFUNDING` —— 钱还没退
2. **`liability` 非空** —— 关键判别器
3. `split_reversed` 不为真，且该子单的 `stl_bill` 尚未 `REVERSED`

> **防住什么**：`REFUNDING` 有**两个来源**——平台裁决支持退款（`arbitrate` 强制写责任方），
> 以及退货退款商家已同意（`approve`，等买家寄回，不写责任方）。
> 后者的钱**本来就不该现在退**，货还没回来。只按状态取，
> 财务会在货没收到时就把钱退出去 —— 而那是这条链路上最贵的一种错。
>
> 只按结算单取则拿不到售后单号，而这条链路上运营认的是售后单。

**执行顺序（ADR-002，不可交换）**：

1. `reverseSplit(subOrderNo)` —— 把已分给商家的钱收回来
2. `resumeRefund(afterSaleNo)` —— 走既有 `doRefund`，它内部会**再做一次** ①（幂等，`REVERSED` 直接返回 true），然后退款、落 `REFUNDED`、发事件

> **防住什么**：反过来做的话，钱退给买家了而分账收不回，商家已提现的部分只能人工追。
> ①②之间不做「已经回退过了就跳过 doRefund 的回退」这种优化——
> 那等于把顺序保证从一个地方拆成两个，而它现在**只在 `doRefund` 里有一份**。

**失败怎么办**：

| 失败点 | 行为 | 防住什么 |
|---|---|---|
| ① 回退失败 | `stl_bill → MANUAL`，接口抛 `SPLIT_EXPIRED`，**不退款**、售后状态不动 | 钱没收回来就退款 = 平台垫付 |
| ② 退款失败 | 事务整体回滚（① 的 `REVERSED` 也一起回滚） | 出现「分账退了、买家没收到钱、而单子看起来已处理」的中间态 |
| 重复点执行 | `doRefund` 首行 `REFUNDED` 即返回；`reverseSplit` 对 `REVERSED` 也是直接 true | 运营看到列表没刷新就再点一次，退两次 |

**为什么执行动作要 `finance:settle:execute` 而不是 `aftersale:*`**：
这个按钮动的是**分账**（把钱从商家账户收回），不是裁决。裁决在 `/after-sales` 页，
由售后组做；这里是财务在裁决之后把资金动作补上。两个动作分属两个岗位。

**`share`（赔付出资比例）恒为 `null`**：口径未定（M4），`ord_after_sale` 上没有存它的列。
接口**不假装它已经判过**——ops-web 那一列会显示红字「未判定」。
按比例分摊的回退等 M4 有结论再说；今天的回退是**整单回退**，与 `doRefund` 一致。

### 4.2 提现审批（P-12.2.1 / 12.2.2）

状态机（与 ops-web `WITHDRAW_TRANSITIONS` 逐字一致）：

```
PENDING ─┬─► APPROVED ─┬─► PAID     （渠道回执驱动）
         │             └─► FAILED ──┐
         └─► REJECTED               │
FAILED ──┬─► APPROVED ◄─────────────┘
         └─► REJECTED
```

> **运营端只提供 `PENDING|FAILED → APPROVED|REJECTED` 两条边**。
> **防住什么**：`APPROVED → PAID` 是打款结果，只能来自渠道回执。
> 给人一个「标记已打款」的按钮，等于允许在钱没到账时把单子做平——
> 之后对账差额永远说不清是通道慢了还是有人点早了。

通过时的五道校验，**每一道都对应一种「批了也打不出去」或「不该打」**：

| 校验 | 依据 | 防住什么 |
|---|---|---|
| 状态机 | `WITHDRAW_TRANSITIONS` | 已驳回/已打款的单被二次审批 |
| 金额 ≤ **申请时的可提余额快照** | `stl_withdraw.available_balance_minor` | 用实时值会因期间的新订单而漂移，审批看的是「申请那一刻他能提多少」 |
| 商家 `canReceive`（分账接收方已报备） | `MerchantQueryPort.MerchantBrief` | 没有收款账户，批了钱也打不出去（ADR-002） |
| 商家未封禁（`canSell`） | 同上 | 解封是另一条链路上的决定（P-11.1.4），不在这里绕过去 |
| 金额 ≥ 单笔下限 | `MIN_AMOUNT_MINOR` | 渠道手续费比本金还贵 |
| 金额 ≥ 复核阈值 ⇒ `remark` 必填 | `REVIEW_THRESHOLD_MINOR` | 大额是最容易被冒用的口子 |

驳回时 `remark` 必填——**它原样回商家 B 端**，不写等于让人猜。

**⚠️ 通过 ≠ 打款**（业务口径未定，见 §五 T1）：落 `APPROVED` 之后系统**不调用任何支付通道**。
`stl_withdraw` 只记账与留痕，实际出款由财务线下执行（待完成功能清单 B-12.5「一期只记账，线下结算」）。

**申请入口（B 端）不在本批**：`stl_withdraw` 今天没有生产者。
这是刻意的——加 `/biz` 端点会连带改 `BizEndpointPermTest` 与 b-app 契约，那两处都有并行会话在改。
见 §六「没做的部分」。

### 4.3 发票与个税（P-12.2.3 / 12.2.4）

开票三道校验：

| 校验 | 防住什么 |
|---|---|
| 只有 `PENDING` 能处理 | **重复开票就是重复虚开** |
| 企业抬头必须有税号 | 没有税号的企业票对方入不了账，等于白开 |
| 开票金额 ≤ 该周期已结算金额 | 超出部分没有真实交易对应，**就是虚开** |

个税规则（`sys_setting` 键 `finance.tax-rule`）：

- 只对**个人主体**生效（个体户与企业自行申报，平台不代扣，ADR-002 §4）
- `threshold` 起征点（分）：**不设起征点会给每一笔几块钱的提现都产生一条扣税记录**
- `rate` 万分比，硬上限 `MAX_RATE_BP = 4500`（45%）——**超过一定是配置错误**，
  而一个手滑多打的零会让每一笔提现都扣光
- `updatedAt / updatedBy` 存进同一份 JSON：`SettingPort.get` 只返回值，
  留痕不放进值里就取不回来，而**改税率必须能追到是谁改的**

### 4.4 数据模型

`stl_withdraw`（V110）关键列与理由：

| 列 | 理由 |
|---|---|
| `merchant_name` 快照 | 商家改名不该让历史提现单跟着变 |
| `available_balance_minor` 快照 | 见 §4.2，审批看的是申请那一刻的口径 |
| `bank_account_masked` | **只存掩码**。运营端展示不需要全号，而全号一旦落库就要按敏感信息管 |
| `tax_amount_minor` | 本单代扣个税额，**按申请时的规则算定并落库**——规则会改，历史单不能跟着变 |
| `uk_stl_withdraw (withdraw_no)` | 业务键唯一 |

`stl_settle_invoice`（V111）：`period + entity_no` 唯一（同一周期同一商家一张票，
**重复申请 = 一笔结算两张票**，那是税务问题不是体验问题），
`settled_amount_minor` 落快照（后续退款会改结算额，而已开的票不会跟着变）。

---

## 五、取舍记录

| # | 冲突 | 让了谁 | 为什么 |
|---|---|---|---|
| **T1** | 「提现审批」听起来该真打款 vs B7/B-12.5 口径未取 | 让口径 | 分账参数（比例上限、时限、个人接收方限额）**书面口径未取**，ADR-002 §6 明确「按经验值写进代码是给自己埋雷」。本批实现为**状态机 + 留痕**，打款留给接通道那一批。这条写进接口注释与本节，避免下一个人以为已经打过款 |
| **T2** | 铁律「只碰 settle 域」 vs 队列必须能把售后单推到 `REFUNDED` | 让铁律（最小面积） | 见 §二（C）。新增 `spi/trade/RefundSplitBackPort`（新文件）+ `trade/port/RefundSplitBackPortImpl`（新文件），并在 `AfterSaleService(+Impl)` 上加**一个**方法 `resumeRefund`，它只是 `doRefund` 的入口，不复制收尾逻辑。既有 trade 文件的改动是纯追加、Edit 唯一锚点 |
| **T3** | 时间字段：settle 域既有 VO 用 epoch 毫秒 vs 契约声明 `string` | 让契约 | `Withdrawal.appliedAt / decidedAt`、`TaxRule.updatedAt` 在 `ops-web/lib/types/finance.ts` 里是 `string`，mock 与 `lib/mock/db/payout.test.ts` 都按 ISO 串写死。改成 number 要连带改三处既有绿测，而收益只是「和别的 VO 一致」。**后端这四个新 VO（含退款回退队列的 `createdAt`）返回 ISO-8601 串** |
| **T4** | 权限码读写分开 vs 提现只给一个码 | 让读写分离 | 与 `perm-endpoint-map.mjs` 里 `store:page:audit` 同一个例外理由：**提现队列的「读」就是审批动作的一半**，没有「只看提现不审提现」的岗位。拆出的只读码会是一个没有任何角色单独持有的码，只增加配置负担 |
| **T5** | 提现表没有生产者（B 端申请入口未做） | 让范围 | 见 §4.2。加 `/biz` 端点会连带改 `BizEndpointPermTest`（并行会话在改）与 b-app 契约（同）。本批只做运营侧，场景测试用 mapper 直接种数据 |
| **T6** | `share` 按比例回退 vs M4 口径未定 | 让口径 | 整单回退，`share` 返回 `null`。ops-web 已经为这种情况准备了红字「未判定」的显示 |

---

## 六、没做的部分

| 项 | 原因 |
|---|---|
| 提现**打款**（调支付通道） | B7 分账参数书面口径未取；B-12.5 一期只记账、线下结算 |
| 提现**申请**（B 端入口） | 见 T5。`stl_withdraw` 今天只有运营侧读写 |
| 提现**频次限制**（12.2.2 的「频次」一半） | 频次是**申请侧**的闸门（「本月已提 N 次」），而申请入口还没有。审批侧只做限额与复核阈值 |
| **个税申报**（12.2.3 的「申报」一半） | 申报是报送税局的动作，需要外部通道；本批只做规则配置与单据上的代扣额落库 |
| **一键代办个体工商户**（12.2.5） | ADR-002 §6 待确认第 3 条，接哪家服务商未定 |
| 按 `share` 比例分摊回退 | M4 口径未定，见 T6 |
