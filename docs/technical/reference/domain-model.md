# A2 · 领域对象模型

> 状态：草稿（**待确认**）· 创建 2026-08-06
> 任务：[后端实施任务清单 A2](../archive/后端实施任务清单.md) · 上游：[openapi.yaml](../../api/openapi.yaml)（A1）· [页面-端点映射](./页面-端点映射.md)（A4）
> 定位：回答「系统里有哪些领域对象、边界画在哪、哪些规则任何时候都必须成立」。
> 字段级 DDL 在 A3（`db-design.md`）；本文只到**聚合与不变量**这一层。

---

## 〇、已决议事项（本文按此写）

| # | 决议 | 影响 |
|---|------|------|
| **Q6** | **C 端「订单」= 子订单**（方案乙）。内部仍是主单-子单两级，主单只承担「一次支付」 | §3 订单聚合 |
| Q1 | 统一为 `userNo`；过渡期后端**同时输出** `userNo` 与 `cUserNo`（后者标 deprecated），前端改完删 | §2 |
| Q5 | 采用 `POST /mp/order/{orderNo}/rebuy`（前端改掉复数 `orders/reorder`） | 契约已冻结 |
| Q7 | 随前端命名：`pickupCode` → **`verifyCode`**、`expireAt` → **`payDeadlineAt`** | §3 |
| Q8 | 订单时间线一期做（矩阵 C-OC-03 是 P0），建 `ord_status_log` | §3 |

> Q1/Q5 需要 c-app 侧配合改动，已登记为前端变更单（§7）。**后端不等前端**：
> `userNo`/`cUserNo` 双写是过渡手段，避免两端互相等待。

---

## 一、聚合总览（17 个业务域 → 14 个聚合）

| # | 聚合根 | 边界内实体/值对象 | 归属键 | 关键不变量 | 模块 |
|:--:|--------|------------------|--------|-----------|------|
| 1 | **User** | 归属(Belonging VO)、Identity(openid/phone/appleSub) | `userNo` | 一个 openid 只能对应一个 User | user |
| 2 | **Address** | — | `userNo` | 默认地址至多一条 | user |
| 3 | **Merchant** | 资质、结算账户、评分(Score VO) | `merchantNo` | 非 ACTIVE 不可上架、不可收款 | user |
| 4 | **Community** | — | `communityNo` | CLOSED 社区不出现在选点 | user |
| 5 | **PickupPoint** | — | `pickupNo` | **`type=NEIGHBOR` ⟹ `serviceFeeRate=0`** 且 `scope=GROUP_INSTANCE` | user |
| 6 | **Goods**(SPU) | Sku、SpecGroup、Price(VO) | `merchantNo` | 价格唯一键 `(merchantNo, skuNo, market)`；SPU 上无价 | product |
| 7 | **CommunityPool** | — | `communityNo` | 只决定可见性，**不存价** | product |
| 8 | **StockLock** | — | `lockNo`(=订单号) | `locked ≤ stock`；释放/确认只作用于 LOCKED 行 | product |
| 9 | **Cart** | CartItem | `userNo` | 不存价与标题（读时实时算） | trade |
| 10 | **Order**(主单) | SubOrder、OrderItem、StatusLog | `userNo` | **Σ子单.payAmount = 主单.payAmount**；回调只推进不回退 | trade |
| 11 | **AfterSale** | 凭证、协商记录 | `subOrderNo` | 退款前必须先回退分账 | trade |
| 12 | **FulfillmentTask** | 核销记录、批次 | `pickupNo`(→`/biz`) / `groupNo`(→**`/mp/groups`**) | 核销码全局唯一且一次性；发起人是普通用户，不走 `/biz` | fulfillment |
| 13 | **SettleBill** | SplitOrder、SplitLog | `merchantNo` | 分账金额 ≤ 子单实付；超时解冻回平台 | settle |
| 14 | **Attribution** | 关系链、判定日志 | `userNo` | 优先级唯一：店铺码 > 邀请人 > 渠道 | marketing |

> **为什么是 14 个而不是 17 个**：营销的券/活动/团购三者共享「投放-领取-核销」骨架，
> 一期先归到 marketing 域内的三个独立实体，等 M6 真正做的时候再判断要不要拆成独立聚合。
> 过早拆聚合的代价是跨聚合事务，比合着写大得多。

---

## 二、User / Merchant / PickupPoint（M1）

### 2.1 User 聚合

```
User(root)
├─ userNo            ← 唯一标识（Q1：对外字段名统一 userNo）
├─ Identity          ← openid | unionid | phone | appleSub，三选一即可建户
├─ Belonging(VO)     ← communityNo + pickupNo，**整体替换，不单独改一个**
└─ merchantNo        ← 我的常去店（进店归因写入，非「我开的店」）
```

**不变量**
1. 三种登录标识**各自唯一**（DB 三个唯一索引）—— 少一个，并发首登会建出两个账号。
2. `Belonging` 是值对象，整体替换：`communityNo` 与 `pickupNo` 必须**同属**（自提点属于该社区），
   分开改必然出现「A 社区 + B 社区的自提点」这种永远到不了货的组合。
3. `merchantNo`（常去店）与 `Merchant.ownerUserNo`（我开的店）是**两个概念**，
   共用一个字段名会在 B 端权限上出大事。

### 2.2 PickupPoint 聚合

**唯一一条不变量，但它是合规级的**：

```
type = NEIGHBOR  ⟹  serviceFeeRate = 0  ∧  scope = GROUP_INSTANCE  ∧  groupNo ≠ null
type = STORE     ⟹  scope = PERMANENT   ∧  ownerRef 指向 ACTIVE 商家
```

邻里自提一旦能收钱，就是团长招募换了个名字，ADR-004 消掉的合规问题会原样回来。
因此这条**在三个地方各拦一次**：DB CHECK 约束、聚合工厂方法、`NeighborPickupZeroFeeTest`。

---

## 三、Order 聚合（M3 · Q6 决议后的最终形态）

### 3.1 内部结构不变，对外视角改变

```
内部（DB / 分账 / 结算）              对外（C 端看到的）
Order 主单                            ─┐
├─ payAmount = Σ子单                   │ 「支付单」：只在结算→收银台这一段出现
├─ payDeadlineAt                       │  orderNo = 主单号
└─ SubOrder × N   ←────────────────────┘
   ├─ subOrderNo                      ─┐
   ├─ merchantNo / merchantName        │ 「订单」：订单列表/详情/售后/评价/核销
   ├─ fulfillment / verifyCode         │  orderNo = **子单号**
   ├─ trafficSource                    │  ← 前端 Order 平铺模型可直接渲染
   ├─ OrderItem × N                    │
   └─ StatusLog × N（timeline）       ─┘
```

**`GET /mp/order/{no}` 同时接受主单号与子单号**，返回两种视角：

| 传入 | 返回 | 用在哪 |
|------|------|--------|
| 主单号 | 支付视角：合计金额、`payDeadlineAt`、跨商家平铺的 items、`verifyCode` 为空 | `pay` 页 |
| 子单号 | 订单视角：单商家、`fulfillment`、`verifyCode`、`timeline` | `orders` `order` `after-sale` `review-write` 页 |

`GET /mp/order`（列表）**只返回子单粒度** —— 用户心智里「订单」就是按店分的。

> **为什么这样切**：履约、售后、评价、核销**全部是子单粒度**。让 C 端订单 = 子单，
> 这四条链路的 ID 一以贯之，不必到处传两个号；而分账语义完全不受影响（分账本来就按子单）。
> 代价只有一处：支付要能「一次覆盖多单」，落在主单上正好。

### 3.2 不变量

1. **`Σ 子单.payAmount = 主单.payAmount`**（含运费与优惠分摊）—— 对账的基准，任何写路径后都要成立。
2. 子单的 `merchantNo` 在创建后**不可变** —— 它是分账收款方。
3. `trafficSource` 在**下单那一刻**由 `AttributionPort` 固化写入子单；结算时只读不查。
4. `verifyCode` 仅在支付成功后生成，**全局唯一**、一次性。
5. 状态迁移只能经状态机（§3.3），回调**只推进不回退**。

### 3.3 状态机

**主单**（只管钱）
```
WAIT_PAY ──支付成功──→ PAID
    ├──用户取消──→ CANCELLED
    └──超时关单──→ CLOSED
PAID / CANCELLED / CLOSED 为终态（后续变化全在子单）
```

**子单**（管货）
```
WAIT_PAY ──主单支付成功──→ WAIT_FULFILL ──商家接单/发货──→ FULFILLING
                              │                                │
                              ├──────核销/确认收货──────────────┤
                              ↓                                ↓
                          COMPLETED ←──────────────────────────┘
                              │
WAIT_PAY ──取消──→ CANCELLED  └──售后成功──→ REFUNDED
```
> `COMPLETED → REFUNDED` 保留：售后可以发生在完成之后（收货后 7 天内）。

**售后**
```
APPLIED ──极速退命中──→ REFUNDING ──→ REFUNDED
   ├──商家同意──→ REFUNDING
   ├──商家驳回──→ REJECTED ──用户申诉──→ ARBITRATING ──平台裁决──→ REFUNDING | CLOSED
   └──用户撤销──→ CLOSED
```

**结算**（M7）
```
PENDING ──生成结算单──→ SPLITTING ──分账回执成功──→ SPLIT
             │                        │
             │                        └──失败──→ RETRYING ──超限──→ MANUAL
             └──退款回退──→ REVERSING ──→ REVERSED
```

---

## 四、Goods 聚合（M2）

```
Goods(SPU, root)
├─ specGroups: SpecGroup[]   ← 规格维度定义
├─ skus: Sku[]               ← 每个 SKU 一行价格
│   └─ Price(VO): (merchantNo, skuNo, market) → amount + currency
└─ fulfillments: 该商品支持的履约方式
```

**不变量**
1. **SPU 上没有价格字段**。展示价 = `min(skus.price)`，由领域服务计算。
   这是双入口同源（R17/B11）的结构性保证：没有第二个地方存价，就不可能不一致。
2. 多市场**分别定价**，不做汇率换算（B6）——换算出的价格随汇率跳动，商家不接受。
3. `Sku.available = stock - lockedStock`，对外只暴露 `available`。
   暴露总库存会让端上出现「显示有货、下单说没货」。
4. `CommunityPool` 只决定可见性。空池 = 该社区未铺货，返回空列表（不是全量）。

---

## 五、领域事件（Outbox 投递）

| 事件 | 发布方 | 消费方 | 为什么必须是事件而非直接调用 |
|------|--------|--------|---------------------------|
| `OrderCreated` | trade | marketing(锁券)、message(提醒) | 下单主链路不能被发消息拖慢或拖垮 |
| `OrderPaid` | trade | settle(生成结算单)、fulfillment(建履约任务)、message | **重复投递 = 重复分账**，消费端按 `eventNo` 去重 |
| `OrderCompleted` | fulfillment | product(评价开放)、settle(解冻计时)、marketing(积分) | 核销是订单走到终态的唯一出口 |
| `AfterSaleRefunded` | trade | settle(回退分账)、product(库存回补) | 顺序敏感：先回退再退款 |
| `MerchantApproved` | user | product(开放上架)、settle(报备分账接收方) | 入驻审核通过的连锁反应 |
| `AttributionResolved` | marketing | — (审计) | 归因判定必须可回放，争议时是唯一举证材料 |

---

## 六、跨模块 Port（同步调用）

| Port | 方向 | 用途 |
|------|------|------|
| `MerchantQueryPort` | trade/product → user | 商家名、是否可收款 |
| `GoodsQueryPort` | trade → product | SKU 快照（下单/购物车） |
| `StockPort` | trade → product | 锁定 / 释放 / 确认 |
| `AttributionPort` | trade → marketing | 下单时解析 `trafficSource` |
| `SettlePort` | trade → settle | 触发结算单生成（M7） |
| `FulfillmentPort` | trade → fulfillment | 建履约任务、查核销状态（M4） |

> 原则：Port 返回**调用方需要的最小结构**，不是领域实体。
> 返回实体会让交易域顺手用上商品域的字段，商品域改一列交易域就炸。

---

## 七、由本文产生的变更单

| # | 对象 | 变更 | 归属 |
|:--:|------|------|------|
| C1 | 后端 `UserVO` | 增 `userNo`，`cUserNo` 保留并标 deprecated | 后端（M1） |
| C2 | c-app | `cUserNo` → `userNo`（45 处） | **前端** |
| C3 | c-app | `/mp/orders/{no}/reorder` → `/mp/order/{no}/rebuy` | **前端** |
| C4 | 后端 `OrderVO` | 按 §3.1 重构为双视角；`pickupCode`→`verifyCode`、`expireAt`→`payDeadlineAt` | 后端（M3 回填） |
| C5 | 后端 | 新增 `ord_status_log` 表与 `timeline` 字段（Q8） | 后端（M3） |
| C6 | 后端 | 子单快照增 `pickupName`（A4 §2.3） | 后端（M3） |

---

## 八、待确认

| # | 事项 | 阻塞 |
|---|------|------|
| Q2 | 运费模型（按商家/按履约/满额免运）—— 影响不变量①的分摊算法 | M3.2 |
| Q3 | 优惠券是否进一期 —— 影响优惠分摊到子单的规则 | M3.2 |
| Q9 | **优惠与运费如何分摊到子单**：跨商家满减券按金额比例分摊？分摊后的尾数归谁？ | M3.2，且直接影响分账金额 |

---
确认记录：待用户确认
