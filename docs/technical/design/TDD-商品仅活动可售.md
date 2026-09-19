# TDD · 商品仅活动可售

> 状态：**已实现（后端 · C 端 · B 端三步）** · 2026-09-19
> 起因：店主「商品要支持只给活动用，但是单品不销售；目前既有团购又销售单品，展示在一个 C 端页面」
> 原型：[仅活动可售](https://claude.ai/artifact/NSX5VN9PecbYqJD22KBDmn)（C 端详情底栏四态 · 货架 · B 端编辑与列表）
> 相关：[TDD-C端商品详情页重排](../TDD-C端商品详情页重排.md)（拼团底栏「单买 / 开团」的来处）、
> [TDD-券与活动模型](TDD-券与活动模型.md)、`OrderServiceImpl.split()`、`PeriodPort`、`GroupRulePort`

---

## 1 要解决的是什么

一件货挂了拼团，C 端详情页底栏就是「单买 ¥50 ｜ 开团 ¥38」——这是
[TDD-C端商品详情页重排](../TDD-C端商品详情页重排.md)（第 41 行）**有意保留**的，不是缺陷。
店主要的是另一种货：**只在活动里卖，平时不单卖**（团购专供、集单预售、特价专供）。

今天做不到，而且没有绕法：

| 想到的绕法 | 为什么不行 |
|---|---|
| 把商品下架 | 单买 / 开团 / 参团三条路共用 `OrderServiceImpl.split()`，L1622 一律 `!s.onSale() → NOT_FOUND`。下架了团也开不了 |
| 复制一件「团购专用」商品 | 库存拆两份、图文维护两遍；卖完一边另一边还显示有货 |
| 在活动上加「活动期间停售单品」 | 活动一结束又能单买 —— 与「单品不销售」相反。以后要可以再加，与本方案不冲突 |

## 2 现状（每条都查过源码）

- `prd_goods` **没有**能表达这件事的列。`sellable_override` 是「按端屏蔽品类」（iOS 不卖某些类），不是一回事。
- 下单：`split()`（L1593）是**预览、下单、代客下单**的唯一共用入口，逐 SKU 取 `GoodsQueryPort.SkuSnapshot`。
- 拼团：`CreateOrderCommand.grouped()` = 带 `groupNo`（参团）或 `openGroup`（开团）。
- 集单：**没有下单标记**。普通下单时若这件货有进行中的一期，L795 自动挂上 `PeriodTicket`（`group == null` 才挂）。
  所以对集单来说，**普通下单本身就是活动路径**。
- 特价 / 买赠：活动点名商品（`pmt_activity_goods`），走的也是普通下单，价格由 `ActivityPricingServiceImpl` 覆盖。
- 购物车在后端：`CartServiceImpl.add()`（L62）；列表 L56 已有「下架 → 失效行」。
- C 端货架的在售过滤，逐个方法：

  | 方法 | 服务的页面 |
  |---|---|
  | `GoodsServiceImpl.list` L184 | 商品列表、分类、**店铺页**（`StoreServiceImpl` L73 也走它） |
  | `GoodsServiceImpl.promoted` L125 / `byGoodsNos` L165 | 首页推荐位 |
  | `GoodsServiceImpl.suggest` L301 / `hotWords` L315 | 搜索联想、热词 |
  | `StoreServiceImpl` L178 / L210 | 常买、再来一单（按快照 `onSale` 判失效） |

- C 端入口：团购有独立列表（`pages/groups`）与首页「进行中的团」一栏；**集单没有列表页**，集单商品只在普通货架上被看到。
- B 端草稿：`prd_goods_draft.payload` 是整份 `SaveCommand` 的 JSON，新字段加进 `SaveCommand` 就自然跟草稿走。

## 3 概念

商品多一个属性 **销售方式** `sale_mode`：

- `NORMAL` 正常售卖 —— **默认**，存量全部是它，行为逐字不变
- `ACTIVITY_ONLY` 仅活动

判定只有一句：**此刻有点名这件货、正在进行的活动，才能买；且只能按那个活动的路径买。**

拆成两个布尔，全文只用这两个：

- **`directOpen`**（普通下单开着）= 这件货此刻有进行中的 **集单**，或有进行中的、点名它的 **特价 / 买赠**
- **`anyLive`**（有活动在跑）= `directOpen` ∨ 此刻有点名它的 **拼团**

| 下单方式 | NORMAL | ACTIVITY_ONLY |
|---|---|---|
| 开团 / 参团（`grouped()`） | 照旧 | 照旧（拼团规则自己会判活动在不在） |
| 普通下单 / 加购物车 | 照旧 | **`directOpen` 才放行** |

「只有拼团在跑、没有集单特价」时普通下单被拒 —— **这正是要拦的「单买」**。

**不算活动的**：满减、无门槛立减、满件减（整单级，不点名商品）。它们照常叠加在活动单上，但不能单独让一件仅活动的货变得可买。
**平台活动**（P3a 报名制）点名了这件货，同样算。

## 4 设计

### 4.1 数据

```sql
-- V3xx（落地当天对号：09-19 时 HEAD 到 V339，隔壁会话手上还有未提交的）
ALTER TABLE prd_goods ADD COLUMN sale_mode VARCHAR(16) NOT NULL DEFAULT 'NORMAL';
```

- `PrdGoods.saleMode` 实体字段 **同一提交里加**（只加列不加字段 → 永远读出 null，不报错）
- `SaveCommand.saleMode`：随草稿走；发布编译点落到列上
- **不新增审核项**。它不改变「卖的是什么」，只改变「在哪儿卖」
- 枚举而不是布尔：下一个很可能是「仅会员」，别再加一列

### 4.2 判定口（跨域走 SPI）

`shop-base/spi/marketing/SaleGatePort`，实现在 promotion 域：

```java
Live live(Collection<String> goodsNos, long now);
record Live(Set<String> direct, Set<String> any) {}
```

- **一个方法返回两个集合**（实现时并成一个）：两个集合读的是同一批活动行，分开问要查两遍
- 集单用 `PeriodPort.viewFor`（此刻真有一期可下），不看活动行本身：当天截了单、下一期未开时活动仍是 RUNNING
- 组合活动（`COMBO`，P3b）与满减同类，按整单级处理、不算

- 批量接口：列表一页几十件，逐件问会打几十条查询
- 读 `pmt_activity` / `pmt_activity_goods` / 期表时 **`DataScopeContext.executeWithoutScope`**
  （B 端直查带域表：SELECT 变 404、结果静默为空 —— 本域已栽过一次）
- 活动「进行中」一律用 `PmtActivity.isActiveAt(now, ZONE)`，与算价同一把尺；集单用 `PeriodPort.batchOfGoods`

### 4.3 闸：后端才是真的闸

| 位置 | 改动 |
|---|---|
| `GoodsQueryPort.SkuSnapshot` | 多带 `saleMode` |
| `OrderServiceImpl.split()` L1622 之后 | `ACTIVITY_ONLY ∧ ¬grouped() ∧ goodsNo ∉ directOpen` → `GOODS_ACTIVITY_ONLY` |
| `CartServiceImpl.add()` L62 | 仅活动且 **`goodsNo ∉ any`**（什么活动都没在跑）→ 拒 |
| `CartServiceImpl` 列表 L56 | 仅活动且 `goodsNo ∉ any` → 失效行，与下架同一处理（活动全结束后车里那件） |
| `StoreServiceImpl` L178 / L210 | 常买、再来一单：仅活动且 `∉ direct` → 失效 / 不加入（它们就是单买的路） |

> ⚠️ **购物车看 `any` 不看 `direct`**（实现时发现，初稿写错了）：C 端的「开团」「立即购买」
> **都是先加购、再带 skus 进确认页**（`goods` 页 `openGroupBuy` / `buyNow`）—— 购物车是所有下单的运输通道。
> 初稿写的「购物车里没有拼团，同上」会让只有拼团在跑时，仅活动商品的开团在加购那一步就断掉。
> 单买真正被拦在 `split()`。
>
> 复购写车走 `CartWritePort`（直写表），**不经过** `CartServiceImpl.add` —— 所以常买 / 再来一单要自己判，
> 不然仅活动的货会被悄悄塞进购物车、再显示成失效行。

- 新错误码 `GOODS_ACTIVITY_ONLY`（700xx，落地时取下一个空号）+ `err.goods.activity_only` 三语：
  「该商品仅限活动购买」。**不复用 NOT_FOUND**：顾客看到「商品不存在」会以为链接坏了
- 代客下单 `createFor` 走同一个 `split()`，自动被覆盖
- 已下的单不追溯：判定只在下单那一刻

### 4.4 货架

5 个方法的过滤从 `on_sale = 1` 变成：

```
on_sale = 1 AND (sale_mode = 'NORMAL' OR goods_no IN 此刻有活动在跑的仅活动商品)
```

- **有活动时照常上架、没活动时消失**。不是「永远不上货架」—— 集单没有列表页，
  仅活动的集单货若不上货架，顾客没有任何地方能看到它（§2 最后一条）
- **先算集合、再进 SQL**（实现时改的，初稿是「取完一页再剔」）：先查出在售的仅活动商品（通常个位数），
  问一次 `live().any()`，把结果放进 `IN`。剔除法会让一页少于请求数、分页游标错位；这样分页是准的

### 4.5 详情接口

`GoodsVO`（C 端详情 `GET /mp/goods/{goodsNo}` 的返回，`MpCatalogController` L215）多两个字段，**由后端算，前端不推**：

- `saleMode`
- `directBuyable` = `NORMAL ∨ directOpen`

前端已经并发拿着拼团与集单信息，但特价、买赠、平台活动它不知道 —— 让前端自己拼「能不能单买」，
就是第二个判定入口，迟早与后端的闸不一致。

## 5 界面（详见原型）

### C 端 · 详情底栏

**唯一规则：`directBuyable` 决定「加入购物车 / 立即购买 / 单买」出不出现。** 其余照旧。

| 商品 | 此刻在跑 | 底栏 |
|---|---|---|
| NORMAL | 拼团 | 单买 ｜ 开团（**现状，不变**） |
| NORMAL | 其他或无 | 加入购物车 ｜ 立即购买（**现状，不变**） |
| ACTIVITY_ONLY | 只有拼团 | **开团 ¥38**（整宽，唯一按钮） |
| ACTIVITY_ONLY | 集单 / 特价 / 买赠（可同时有拼团） | 与 NORMAL 同形 —— 路径本来就是普通下单 |
| ACTIVITY_ONLY | 什么都没有 | **暂不可购买**（整宽、压暗）；只会从旧分享链接、历史订单进来 |

规格面板里的两对按钮同一条规则。

### B 端

- **编辑商品**「基本信息」末尾一行：`销售方式  [正常售卖][仅活动]`（`sh-seg`）。
  选「仅活动」时下面一行 `sh-hint`：**仅在活动进行中可购买**。只此一句，不解释原因
- **商品列表**：价格库存那一行末尾加 `· 仅活动`（`txt-sub`）；此刻没活动在跑时换成
  `· 仅活动 · 未在活动中`（`--sh-warning`）—— 不然商家不懂为什么顾客看不到它
- **建活动选商品**：仅活动的货照常可选，行尾标 `仅活动`
- B 端商品列表接口的行 VO 多一个 `activityLive` 布尔（服务端用 `anyLive` 批量算）

## 6 边界

| 情形 | 处理 |
|---|---|
| 活动进行中加了购物车，结账时活动结束 | 购物车失效行 + 下单被拒（`split()` 按下单那一刻判） |
| 仅活动商品本身下架 | 下架优先：任何路径都不可买，与今天一样 |
| 同时有拼团与特价 | `directOpen` 为真 → 底栏与 NORMAL 同形（单买 ｜ 开团），单买按特价 |
| 从 NORMAL 改成 ACTIVITY_ONLY（在售） | 走草稿、发布后生效，与改价同一套 |
| 满减 / 券 | 不改变可买性；活动单上照常叠加 |
| 库存 | 不拆，与单卖同一份 |
| 只有拼团在跑，顾客开团途中退出 | 货留在购物车里、显示正常；在购物车直接结算时 `split()` 拒并提示「该商品仅限活动购买」。有意接受：给购物车行加「来意」标记是更大的改动 |

## 7 验证

场景测试 `GoodsActivityOnlyFlowTest`，**§3 那张表每一行一条**，外加：

1. 仅活动 + 只有拼团：普通下单拒（`GOODS_ACTIVITY_ONLY`）、开团放行
2. 仅活动 + 集单：普通下单放行且挂上期
3. 仅活动 + 特价：普通下单放行、按特价收
4. 仅活动 + 无活动：普通下单拒、加购拒、货架上没有、`directBuyable=false`
5. 活动结束的那一刻：同一件货从可买变不可买（造一个 `endAt = now + 1s` 的活动）
6. NORMAL 的一切行为逐字不变（存量回归）
7. 满减单独在跑 **不** 让仅活动商品可买

**消融**：删掉 `split()` 里那一行判定，必须变红的是「普通下单拒」那几条断言。
**实测（2026-09-19）**：7 条里恰好 2 条变红 —— `groupOnlyBlocksSingleBuy:94`（只有拼团在跑，普通下单必须拒）
与 `endingFlipsBack:168`（活动结束后结账按下单那一刻判）；其余 5 条不受影响（它们测的是购物车与货架那几道）。

## 8 不做什么

- 不做「活动期间暂停单卖」（活动级开关）
- 不做「仅会员」（枚举留了位）
- 不改拼团、集单自己的规则
- 不做购物车行的「来意」标记（见 §6 最后一行）

## 9 落地顺序

每一步单独可上线，**默认 NORMAL 保证前一步上了、后一步没上时什么都不变**：

1. 后端：迁移 + 实体 + `SaleGatePort` + 四处闸 + 货架过滤 + 详情字段 + 错误码 + 场景测试
2. C 端：详情底栏与规格面板按 `directBuyable`
3. B 端：编辑页一行、列表标注、建活动选商品的标注
