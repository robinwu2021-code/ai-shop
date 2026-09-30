# B 端报表清单 · 由浅入深

> 状态：**整理 + 三张报表口径已明确** · 2026-09-30 —— 全部字段与查询条件**取自代码实况**
> （`b-app/src/api/contract.ts` 的方法签名 + `packages/shared/src/types` 的接口），
> 不是照需求文档抄的。带 ⬜ 的是今天没有、且写明了判据的缺口。

- 上游功能点：[B端功能清单](B端功能清单.md) B-2 工作台 · B-11 客户 · B-13.1 经营数据 · B-14.9 跨店 · 进销存报表
- 真源：`b-app/src/api/endpoints.ts`（路径）· `contract.ts`（查询条件）· `shared/src/types`（字段）

---

## 〇、两条不能绕的既有约定

**① 不做折线图。** [B-13.1](B端功能清单.md) 写着「**不做图表**——一天几十单的折线图读不出东西」。
这是按业务体量做的决定，本清单**不推翻它**：下面每一层的产出形态都是
**数字卡 + 排行榜 + 明细表**，需要趋势时用「本期 vs 上期」两个数字并排，不画线。

**② 钱一律最小货币单位。** 字段名带 `Minor` 的都是「分」，端上除以 100 再显示；
金额永远跟着 `currency`（`CurrencyCode`）走，不假设人民币。

---

## 一、四层结构

商家打开 App 的路径是**一眼 → 概览 → 对比 → 明细**，四层各自回答一个不同的问题：

| 层 | 回答什么 | 页面 | 查询条件 | 今天的状态 |
|---|---|---|---|---|
| **L0 一眼** | 现在有什么活要干？ | `home` 工作台 | **无** | ✅ |
| **L1 概览** | 最近做得怎么样？ | `stats` 经营数据 · 进销存总览 | **无** | ✅ |
| **L2 对比** | 跟谁比、比哪一段？ | 跨店对比 · 对账单 · 月度收发存 | 一个（`days`/`period`/`month`） | ✅ |
| **L3 明细** | 具体是哪一笔、哪一件？ | 对账明细 · 库存流水 · 客户列表 · 库存余额 | 多条件 + 分页 | ✅ |

**层与层之间要能点进去**（L0 的「待发货 12」点开就是筛好的订单列表）。
这一点今天在订单侧是通的，在**金额侧不通** —— 见 §六 ⬜1。

---

## 二、L0 一眼 · 工作台

`GET /biz/dashboard/todo` → `MerchantTodo` ｜ `GET /biz/dashboard/stats` → `MerchantStats`

**查询条件：无。** 它就是「当前门店 + 此刻」，多一个条件都是负担。

### 待办数字（`MerchantTodo` 有 8 个字段，工作台显示 5 + 2）

| 字段 | 含义 | 显示条件 | 点开去哪 |
|---|---|---|---|
| `toShip` | 待发货 | 常显 | 订单列表 |
| `toDeliver` | 待自送 | 常显 | 自送页 |
| `toStock` | 待到货 | 常显 | 订单列表 |
| `afterSale` | 待售后 | 常显 | 售后待处理 |
| `toReply` | 待回评 | 常显 | 评价列表 |
| `toVerify` | 待核销 | **仅承接自提点**（`merchant.isPickupPoint`） | 核销页 |
| `toPick` | 待分拣 | **仅承接自提点** | 分拣单 |
| `quotable` | 可报价 | **不在工作台** | 邻里求团 |

每一个都带权限码（`biz:ship` / `biz:verify` / `biz:receive` / `biz:aftersale` / `biz:review`），
没权限的那一格不显示 —— 所以同一家店不同员工看到的格子数不一样。

> 核销/分拣对不承接自提点的商家隐藏，依据是 ADR-005：**那是自提点承接方的活**
> （`home/index.vue:262-267`）。
>
> ⚠️ **需求文档原先写的是「六个待办数字」并漏了 `待到货`**，也没提条件显示与 `quotable`。
> 按「代码是事实」已改（[B-2.1](B端功能清单.md)）—— 起草本文时我第一版照着
> `MerchantTodo` 的 8 个字段平铺，也是错的：**类型有 8 个 ≠ 工作台显示 8 个**。

### 今日经营（`MerchantStats` 的前半）

`todayOrders` 今日单量 · `todayGmvMinor` 今日成交额 · `rating` 评分 · `ratingCount` 评价数

**这一层只放「今日」**。本月那两个数留给 L1 —— 一眼要看的是「今天还有什么没干完」，
不是月度业绩。

---

## 三、L1 概览 · 经营数据与库存总览

### 3.1 经营数据 `GET /biz/dashboard/stats` → `MerchantStats`

**查询条件：无**（口径写死「今日 + 本月」）。

| 字段 | 含义 | 维度 |
|---|---|---|
| `todayOrders` / `todayGmvMinor` | 今日单量 / 成交额 | 时间=今日 |
| `monthOrders` / `monthGmvMinor` | 本月单量 / 成交额 | 时间=本月 |
| `rating` / `ratingCount` | 评分 / 评价数 | 全期累计 |
| `ownedTrafficRate` | **自带客流占比** | 来源（`TrafficSource`） |
| `currency` | 币种 | — |

> `ownedTrafficRate` 值得单独一张卡：自带客流的费率是 0%，
> 这个数字**直接对应店主少付的钱**（见 B-2.3）。它是这一层里唯一能引出「我该做什么」的数。

### 3.2 库存总览 `GET /biz/inventory/summary` → `StockSummary`

**查询条件：无。**

| 字段 | 含义 | 点开去哪 |
|---|---|---|
| `itemCount` | 在管品数 | 库存列表 |
| `shortageCount` | 低于安全库存 | 库存列表（筛缺货） |
| `staleCount` | 长期未动 | 滞销榜 |
| `inTransitCount` | 在途 | 在途库位 |
| `openCountNo` | 有没有未完成的盘点单 | 那张盘点单 |

> 这一层的四个数**全是「要不要管」而不是「做得好不好」** —— 它的下一步是动作，不是分析。

---

## 四、L2 对比 · 一个参数换一个视角

### 4.1 跨店对比 `GET /biz/dashboard/cross-store/compare` → `CrossStoreCompare`

**查询条件：`days`**（一个数，回看天数）。多门店商家才有意义。

顶层：`days` · `currency` · `rating` · `ratingCount`
逐店行（`CrossStoreCompareRow`）：

| 字段 | 含义 |
|---|---|
| `storeNo` / `storeName` / `isDefault` / `status` | 门店身份与启停 |
| `orders` / `gmvMinor` | 单量 / 销售额 |
| `buyers` / `repeatBuyers` / `repeatRate` | 买家数 / 复购买家 / **复购率** |
| `rating` / `ratingCount` | 该店评分 |
| `outOfStockSkus` | 缺货 SKU 数 |

**维度：门店 × 时间段。** 这是 b-app 最大的一页（630 行），因为它把五类指标并排放在一屏。

另有 `GET /biz/dashboard/cross-store/overview` → `CrossStoreOverview`（**无查询条件**），
逐店给 `todayOrders/todayGmvMinor/monthOrders/monthGmvMinor` + `toShip/toDeliver/toStock`
—— 它其实是 **L0 的跨店版**（待办 + 今日），不是对比。

### 4.2 对账单 `GET /biz/settle/statement` → `Statement`

**查询条件：`period`**（账期，如 `2026-09`）。

| 字段 | 含义 |
|---|---|
| `period` / `businessMode` | 账期 / 经营模式 |
| `grossMinor` | 毛收入 |
| `commissionMinor` | 佣金（按客流档） |
| `serviceFeeMinor` | 履约服务费（按件） |
| `freightIncomeMinor` / `freightCostMinor` | 运费收入 / 运费成本 |
| `netMinor` | **应结** |
| `billCount` / `voucherNos` | 笔数 / 凭证号 |
| `lines` | 明细 → L3 |

**这是唯一一张「钱从哪来、被扣了什么、剩多少」说得完整的报表。**
`grossMinor − commissionMinor − serviceFeeMinor ± 运费 = netMinor` 这条等式
是它的全部价值；规则常量的唯一事实源在[需求矩阵](需求矩阵-三端.md#七之二交易规则常量唯一事实源)。

### 4.3 月度收发存 `GET /biz/inventory/report/monthly` → `StockMonthly`

**查询条件：`month`**（如 `2026-09`）。

`opening` 期初 · `purchased` 入 · `sold` 出（销）· `lost` 损 · `adjusted` 调 · `closing` 期末
· `balanced` **平不平** · `soldCostMinor` 销货成本 · `lostCostMinor` 损耗成本

> `balanced` 是这张表的主角：**期初 + 入 − 出 − 损 ± 调 = 期末**，
> 不平就说明有一笔没记账。数字再漂亮，`balanced=false` 时整张表都不能用。

### 4.4 动销排行 `GET /biz/inventory/report/ranking` → `StockRank[]`

**查询条件：`type`（`fast` 畅销 / `slow` 滞销）+ `size`。**
字段：`itemId` / `name` / `specText` / `qty` / `costAmountMinor`

> ⚠️ **`type` 是裸字符串**：`contract.ts` 写的是 `type?: string`，而端上实际只传
> `"fast"` / `"slow"`（`stock-report/index.vue:55-56`）。拼错一个字母不报错、
> 榜单静默变空 —— 已记入 §七。
>
> 另外它排的是**件数（`qty`）**，不是金额或毛利。「卖得最多」与「赚得最多」
> 在生鲜上经常不是同一件商品 —— 见 §六 ⬜2。

---

## 五、L3 明细 · 多条件 + 分页

| 报表 | 端点 | 查询条件 | 维度 |
|---|---|---|---|
| 对账明细 | `Statement.lines` | 随 `period` 一起返回 | 订单 × 子单 × 结算单 |
| 库存流水 | `/biz/inventory/ledger` | `itemId` · `docNo` · `cursor` · `size` | 商品 / 单据 × 时间 |
| 库存余额 | `/biz/inventory/balances` | `filter` · `locationId` · `size` | 商品 × 库位 |
| 跨店库存 | `/biz/inventory/cross-store` | `filter` · `size` | 商品 × 门店 × 库位 |
| 可挑拣 | `/biz/inventory/pickable` | `q` · `size` | 商品 |
| 单据列表 | `/biz/inventory/documents` | `kind` · `no` · `size` | 单据类型 × 单号 |
| 客户 | `/biz/customers` | **无** | 买家 × 来源 |
| 线下销售 | `/biz/inventory/offline-sales` | `storeNo` · `date` | 门店 × 日期 × 商品 |

### 5.1 对账明细（`StatementLine`）

`settleNo` / `orderNo` / `subOrderNo` 三个号 · `grossMinor` / `commissionMinor` /
`serviceFeeMinor` / `freightIncomeMinor` / `freightCostMinor` / `netMinor` 六笔钱 ·
`commissionRate` 费率 · `status` / `invoiceStatus` 两个状态 · `settledAt` · `voucherNo`

**逐笔能还原到订单号**，这是对账争议时唯一说得清的东西。

### 5.2 库存余额（`StockBalance`）

`itemId` / `skuNo` / `name` / `specText` / `baseUom` ·
`onHand` 在手 / `reserved` 已占 / `available` 可用 · `safetyStock` 安全库存 ·
`lastMovedAt` 最后动过 · `flags` 标记

> `onHand − reserved = available` —— 三个数要一起看。只看 `onHand` 会把已经卖出去
> 还没出库的那部分当成可卖。

### 5.3 线下销售（`OfflineSaleRow` / `OfflineSaleItem`）

`GET /biz/inventory/offline-sales`，**查询条件 `storeNo` + `date`** ——
是这批报表里**唯一按「天」取数**的一张。

单头：`docNo` / `occurredAt` / `totalQty` / `revoked`
行：`skuNo` / `title` / `spec` / `qty`

> 它记的是当面卖掉的货（对应 §〇 之外的线下收款链路）。
> **只有件数，没有金额** —— 与动销排行同一个缺口，见 §六 ⬜2。

### 5.4 客户（`MerchantCustomer`）

`nickname` / `avatar` · `orderCount` 单数 / `totalSpentMinor` 累计消费 ·
`lastOrderAt` / `daysSinceLast` · `silent` **沉默客户** · `source` 来源

**查询条件：无** —— 今天是一次拉全量、端上排序。沉默客户（`silent`）是唤回对象，
而「多久算沉默」的阈值在后端，端上只收结果。

---

## 六、要做的三张报表（2026-09-30 明确的口径）

商家真正问的是三件事：**最近几日挣了多少**、**这个月的账**、**哪些商品卖得好**。
下面逐张给查询条件、字段、维度，并标注**今天的代码能供到哪一步** ——
每条「缺」都指到具体的类型或签名，不是推的。

### R1 近几日收入与订单量 ⬜ 没有

| | |
|---|---|
| **查询条件** | `days`（7 / 14 / 30 三档就够）；多门店商家加 `storeNo` / `allStores` |
| **维度** | 时间（**逐日**）× 门店 |
| **字段** | 每天一行：`date` · `orders` · `gmvMinor` · `refundMinor` · `netMinor`；顶部给本期合计与**环比**（上一个同长度区间） |
| **形态** | 顶部数字卡（合计 + 环比箭头）+ 逐日明细表。**不画折线**（§〇①） |

**今天供不上。** 判据：`mStats()` 的签名**一个参数都没有**，返回的是
`todayOrders/todayGmvMinor/monthOrders/monthGmvMinor` 四个**定点数** ——
只有「今日」和「本月」两个时间点，**没有任何逐日序列**。
`mCrossStoreCompare(days)` 有 `days`，但它按门店聚合，出不来逐日。

> **环比是这张表的主角**，不是折线。「本周 1.2 万，上周 0.9 万」这一句
> 比七个点的折线有用，也正好绕开 B-13.1 那条约定。

### R2 按月营收与订单 🟡 一半有

| | |
|---|---|
| **查询条件** | `from` / `to`（月粒度，如 `2026-04`→`2026-09`）；单月时退化成今天的 `period` |
| **维度** | 时间（**逐月**）× 门店 |
| **字段** | 每月一行：`month` · `orders` · `gmvMinor` · `commissionMinor` · `serviceFeeMinor` · `freightIncomeMinor` / `freightCostMinor` · `netMinor` |
| **形态** | 逐月表格，最后一行合计 |

**钱那一半已经有了，缺单量和跨月。** 判据：`mStatement(period)` → `Statement`
已经给出 `grossMinor` / `commissionMinor` / `serviceFeeMinor` / 运费两项 / `netMinor`
**和 `billCount`**，等式也是闭合的；但
① 它**一次只取一个账期**（`period` 是单值），跨月并排要前端发 N 次；
② `billCount` 是**结算笔数**不是**订单数** —— 商家问的「这个月多少单」它答不了。

> 所以 R2 不是新建，是**把 `mStatement` 从「单期」扩成「区间」并补一个订单数**。
> 它的等式（毛 − 佣金 − 服务费 ± 运费 = 应结）本来就是 B 端最值得信的一张表。

### R3 商品销售 TopN 🟡 成本那一半已经有了

| | |
|---|---|
| **查询条件** | `days` · `limit` · `orderBy`（`qty` 件数 / `amount` 销售额 / `profit` 毛利）· `type`（`fast` 畅销 / `slow` 滞销） |
| **维度** | 商品（SKU）× 时间 × 门店 |
| **字段** | `itemId` · `name` · `specText` · `qty` · **`salesAmountMinor`（缺）** · `costAmountMinor` · **`grossProfitMinor`（= 销售额 − 成本额，补了上一格就能算）** |
| **形态** | 榜单，正序畅销 / 倒序滞销各取 N 条 |

**后端已有 `/biz/inventory/report/ranking`，支持 `type` + `days` + `limit`**，
返回 `RankVO(itemId, name, specText, qty, costAmountMinor)`。

缺的与对不上的，三条都核过：

| # | 事 | 判据 |
|---|---|---|
| ① | **没有销售额** → 算不出毛利 | 后端 `RankVO` 与端上 `StockRank` 都只有 `qty` + `costAmountMinor`；`gmvMinor` 一系的粒度到**门店**为止（`MerchantStats` / `CrossStoreCompareRow`） |
| ② | 端上契约**没有 `days`** | `mStockRanking(q?: { type?: string; size?: number })` —— 后端支持的 30 天窗口端上调不到 |
| ③ | 端上传 `size`，后端读 `limit` | `BizStockReportController:70-72` 是 `@RequestParam limit`（默认 10）；`stock-report/index.vue:55` 传 `size: 5`，且 `http.get` 不改名 → **要 5 条、实际拿回 10 条，静默** |

> ③ 这类错位在本仓库另有先例（`mSpuStdSearch` 用的就是 `limit`，`mStockRanking` 是独苗）。
> 它不报错，只是数量不对 —— 而榜单「取前 5」变成前 10，没人会去数。

### 三张表与四层的关系

| 报表 | 落在哪一层 | 为什么 |
|---|---|---|
| R1 近几日 | **L1 概览**（顶部卡）+ **L2**（逐日表） | 合计是概览，逐日是下钻的第一跳 |
| R2 按月 | **L2 对比** | 它天生是「这个月 vs 上个月」 |
| R3 商品 TopN | **L2 对比** | 榜单就是商品之间的对比 |

**三张都要能往 L3 走**：R1 的某一天点进订单列表、R2 的某一月点进对账明细、
R3 的某个商品点进它的库存流水。前两条今天点不进去 —— 见 §六之二。

---

## 六之二、下钻的拦路石：订单列表按时间筛不了

**这是 R1 与 R2 共同的前置。** 判据：`mOrderList` 的契约是
`PageQuery & { status?, fulfillments?, allStores? }`，而 `PageQuery` **只有 `page` / `size`**
—— 没有任何时间参数。

再加一个实测：`stats` 页整页只有**一个**可点元素（跨店对比入口，还限多门店商家），
`todayGmvMinor` / `monthGmvMinor` 那几张金额卡**一个 `@tap` 都没有**；
对照 `home` 页有 **11 处** `@tap`（待办数字都通）。

所以「点开金额看是哪些单」要分两步，**顺序不能反**：

| 步 | 事 | 档位 |
|---|---|---|
| ① | `/biz/order` 加时间区间（`from` / `to`），端上列表加时间筛 | **1（先有 TDD）** |
| ② | R1/R2 的数字接到 ① 上，按与卡片相同的口径带参数跳转 | 0 |

② 单独做不成立 —— 没有 ①，点进去只能给一个没筛过的全量列表，
那比点不动更糟：商家会以为「本月 3 万」对应的就是眼前这一页。

> ⚠️ 带参数跳页有个已知坑：**参数名别用 scaffold 的属性名**（例如 `tab`），
> 否则页面会被当成 tab 页 —— 返回键消失、多一条空菜单。


## 七、顺手查出来的缺陷（与报表本身分开记）

| # | 事 | 判据 | 档位 | 状态 |
|---|---|---|---|---|
| 1 | B-2.1 写「六个待办数字」且漏了「待到货」，也没提条件显示 | 工作台实际常显 5 个 + 承接自提点再加 2 个；`quotable` 不在工作台 | 0 | ✅ 已改 |
| 2 | `mStockRanking` 传 `size`，后端读 `limit` → **要 5 条拿回 10 条** | `BizStockReportController:70-72` 的 `@RequestParam limit`（默认 10）；`http.get` 不改名 | 0 | ⬜ |
| 3 | `mStockRanking` 的 `type` 是裸 `string`，实际只认 `fast` / `slow` | `stock-report/index.vue:55-56`；后端 `@RequestParam(defaultValue = "fast")` | 1（收窄 + 登记取值域） | ⬜ |
| 4 | 端上契约缺 `days` —— 后端支持的时间窗调不到 | `mStockRanking(q?: { type?; size? })` vs 后端 `days` 默认 30 | 1 | ⬜ |

2、3、4 是同一个端点上的三处，**建议一起修**：把契约改成
`{ type?: StockRankType; days?: number; limit?: number }`，`StockRankType` 声明成
`"fast" | "slow"` 并登记进 `enum-registry`（G1 那条闸门会要求登记）。

> 第 3 条与本会话此前修掉的几处同型：**端上按一个拼错的值去筛，筛出来是空列表且不报错**。
> 收窄的时候记得**连调用点的签名一起改** —— 只改声明买不到保护（那次消融验证过）。


## 维护约定

1. **字段一律从代码抄，不从这里抄到代码。** 这份文档的价值全在「说的是真话」。
2. **金额字段名必须带 `Minor`**，否则下一个人会当成元。
3. **新增报表先问它属于哪一层** —— 放错层的报表不是多一个功能，是把上一层变吵。
4. **不加折线图**（§〇①）。要表达趋势就并排放两个数字。
