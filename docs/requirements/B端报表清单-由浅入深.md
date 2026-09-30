# B 端报表清单 · 由浅入深

> 状态：**整理** · 2026-09-30 —— 全部字段与查询条件**取自代码实况**
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

### 待办数字（`MerchantTodo`，8 个）

| 字段 | 含义 | 点开去哪 |
|---|---|---|
| `toShip` | 待发货 | 订单列表（筛已支付待发货） |
| `toDeliver` | 待自送 | 订单列表（筛自送） |
| `toStock` | 待到货 | 履约台 · 待到货 |
| `toVerify` | 待核销 | 核销页 |
| `toPick` | 待分拣 | 分拣单 |
| `afterSale` | 待售后 | 售后待处理 |
| `toReply` | 待回评 | 评价列表 |
| `quotable` | 可报价 | 邻里求团 |

> ⚠️ **与需求文档差两个**：[B-2.1](B端功能清单.md) 写的是「六个待办数字」，
> 而 `MerchantTodo` 实际有 8 个字段（多 `toStock` 与 `quotable`）。
> 按本仓库规矩「**代码是事实**」，要改的是那一行需求文档 —— 已记入 §七。

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

## 六、缺口（每条都写清「我在哪儿查到它不存在」）

**⬜1 经营类报表全部无法选时间段。**
判据：`contract.ts` 里 `mStats()` / `mCustomers()` / `mCrossStoreOverview()` 三个方法
**签名上一个参数都没有**，口径写死「今日 / 本月」。于是店主问「上周怎么样」时，
现有报表答不上来 —— 唯一能选时段的是 `mCrossStoreCompare(days)`，而它只在多门店时有意义。
**建议**：给 `mStats` 加一个与 `days` 同形的参数，不加图表也能回答「上一段 vs 这一段」。

**⬜2 没有「按商品看钱」这一维。**
判据（把 `contract.ts` 里 **278 个 biz 方法**筛过一遍，商品/销售相关的逐个看了返回类型）：
- `StockRank`（动销排行）只有 `qty` 与 `costAmountMinor`，**没有销售额**；
- `OfflineSaleItem`（线下销售）只有 `qty`；
- `FulfillmentImpactItem` 只有 `goodsNo` / `title`，不是报表；
- 金额字段（`gmvMinor` 一系）只出现在 `MerchantStats` 与 `CrossStoreCompareRow`，
  粒度都到**门店**为止。

于是「哪个商品最赚钱」今天无处可答 —— 能排的只有件数。
而**卖得最多 ≠ 赚得最多**，在生鲜上这两个常常不是同一件商品。
**建议**：这是 L2 缺的一层，补一张「商品 × 销售额 / 毛利」的榜即可，不需要图表。

**⬜3 层与层之间在金额侧点不进去。**
判据（数了两页的可点元素）：`home` 有 **11 处** `@tap`/`navigateTo` —— 待办数字都通；
而 `stats` 整页**只有一个**可点元素，是跨店对比入口（`goCompare`，还限多门店商家），
`todayGmvMinor` / `monthGmvMinor` 那几张金额卡**一个 `@tap` 都没有**。
也就是说店主看到「本月 3 万」，点不开「是哪些单」。
**建议**：金额卡接到已有的订单列表上，按同一时间口径筛 —— 这条不用新端点。

> 上面三条都是**从签名和字段里读出来的**，不是我推的：每条都能指到
> `contract.ts` 的哪一行签名缺参数、哪个接口缺字段。

---

## 七、顺手查出来的、该改的两处

| # | 事 | 判据 | 档位 |
|---|---|---|---|
| 1 | [B-2.1](B端功能清单.md) 写「六个待办数字」，`MerchantTodo` 实际 8 个字段 | 多 `toStock` / `quotable` | 0（改文档） |
| 2 | `mStockRanking` 的 `type` 是 `string`，实际只认 `fast` / `slow` | `stock-report/index.vue:55-56` | 1（收窄类型 + 登记取值域） |

第 2 条与本会话此前修掉的几处同型：**端上按一个拼错的值去筛，筛出来是空列表且不报错**。
收窄的时候记得连调用点的签名一起改 —— 只改声明买不到保护。

---

## 维护约定

1. **字段一律从代码抄，不从这里抄到代码。** 这份文档的价值全在「说的是真话」。
2. **金额字段名必须带 `Minor`**，否则下一个人会当成元。
3. **新增报表先问它属于哪一层** —— 放错层的报表不是多一个功能，是把上一层变吵。
4. **不加折线图**（§〇①）。要表达趋势就并排放两个数字。
