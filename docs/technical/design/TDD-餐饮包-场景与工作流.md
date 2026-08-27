# TDD 餐饮包（FOOD）—— 业务场景与工作流

状态：**草稿 · 待确认** · 创建 2026-08-27
关联：[TDD-行业包-机制与装配](./TDD-行业包-机制与装配.md) · [核心能力清单](../reference/核心能力清单.md) · [行业包功能清单](../reference/行业包功能清单.md) · [ADR-019](../ADR/ADR-019-行业工作流做成独立行业包.md)

> 贯穿全册的一条：**基座已有的一律不重做。**
> 菜 = 基座 SKU，钱 = 基座订单与支付，退 = 基座售后，账 = 基座结算。
> 餐饮包只负责基座没有的那三样：**台账、制作、桌台**。

---

# 一 · 业务场景

先把场景摊开，再谈流程。每个场景问同样四件事：**谁在操作 / 在哪个端 / 钱什么时候收 / 菜什么时候做**。
这四件事的组合，就是后面所有分叉的来源。

## 1.1 六个场景

### S1 · 快餐先付（茶饮、快餐、档口）
顾客扫台卡或柜台码 → 自助点单 → **线上支付** → 出票制作 → 叫号取餐。
- 钱：**下单即收**。菜：付款后才做。
- 失败面：支付超时 → 订单自动关闭，**后厨从未收到票**，无损。
- 特点：**没有桌台**（或桌台只是取餐号）。`TABLE_ORDERING` 可以不开。

### S2 · 正餐后付（中餐、火锅）
开台 → 多次点单加菜 → 边点边做 → 吃完到前台或扫码结账。
- 钱：**结账才收**。菜：下单即做（与支付无关）。
- 失败面：**跑单**（吃完不付）。系统只能留痕，不能阻止。
- 特点：台账是主角，订单是它的账页。

### S3 · 扫码自助（S1/S2 皆可）
顾客手机扫桌码，自己点。**一桌多人同时点** —— 这一条决定了购物车不能用基座的（§2.7）。

### S4 · 服务员点单
店员在 b-app 上按桌点单，可代客改单、赠菜、折扣。
- 与 S3 共用同一套下单逻辑，只是**操作人不同、权限不同**。

### S5 · 外卖 / 自取
顾客在 c-app 下单，选自取或配送。
- 钱：**必然先付**（没有先吃后付的外卖）。菜：付款后做。
- 履约走基座既有的 `STORE_PICKUP` / `MERCHANT_DELIVERY` / `EXPRESS`，**餐饮包一行不写**。
- 餐饮包只做两件事：**出品分单打印**和**制作状态**（与堂食共用）。

### S6 · 预定包间
提前占资源（`ROOM`）→ 到店转开台 → 之后同 S2。
- 走基座预约（`mch_resource` + `mch_appointment_slot`），餐饮包只做「预约转开台」这一步。

## 1.2 场景 → 模式矩阵

| 场景 | 付款顺序 | 履约方式 | 点单入口 | 桌台 | 台账 |
|---|---|---|---|---|---|
| S1 快餐先付 | 先付 | `DINE_IN` / `STORE_PICKUP` | 顾客 | 可无 | 有（单次） |
| S2 正餐后付 | **后付** | `DINE_IN` | 顾客/店员 | 有 | 有（多单） |
| S3 扫码自助 | 两者皆可 | `DINE_IN` | 顾客 | 有 | 有 |
| S4 店员点单 | 两者皆可 | `DINE_IN` | 店员 | 有 | 有 |
| S5 外卖/自取 | 先付 | `MERCHANT_DELIVERY`/`EXPRESS`/`STORE_PICKUP` | 顾客 | 无 | **无** |
| S6 包间预定 | 后付居多 | `DINE_IN` | 顾客/店员 | 有（ROOM） | 有 |

**S5 没有台账** —— 外卖单就是一张普通订单，套台账反而多一层没用的间接。
制作状态挂在**订单行**上，堂食走台账行、外卖直接走订单行，两者共用同一个制作状态机（§4.3）。

## 1.3 从场景里抽出来的七个动作

| 动作 | 一句话 | 分叉点 |
|---|---|---|
| **加菜** | 同一台账追加 | 无（永远是新订单） |
| **减菜** | 少点一份 | **钱收没收**（§5） |
| **退菜** | 已做好的退掉 | **钱收没收** + 是否已出品 |
| **并台** | 两桌合一起结 | 台账层，订单不动 |
| **拆单** | 一桌分开结 | **必须在下单时分**（§5.5，这是硬限制） |
| **转台** | 换一张桌子 | 资源释放与占用 |
| **催 / 叫起** | 调制作顺序 | 纯制作队列，不碰钱 |

---

# 二 · 餐品建模（基础 + 餐品）

**一条菜都不新建成"菜品实体"。** 菜就是基座的 `prd_goods` / `prd_sku`。
餐饮特有的东西用下面七种方式表达，每一种都说明**为什么不能用别的办法**。

## 2.1 做法 / 口味（不加价）
「不要辣、少冰、免葱」。

- **落法**：下单时拼进基座 `ord_item.spec`（那本来就是**快照字符串**），
  同时在台账行留结构化明细 `fnb_check_item.modifiers`（JSON）。
- **为什么不做成 SKU**：三个二选一的做法就是 8 个 SKU，六个就是 64 个。SKU 会爆炸，而它们价格完全一样。
- **为什么快照要写进 `ord_item.spec`**：厨房票、小票、售后单都读订单行；只存台账的话，**外卖单没有台账，做法就丢了**。

## 2.2 加料（加价）
「加珍珠 +2、加面 +3」。

- **落法**：加料是**一个真实 SKU**，下单时作为**独立订单行**；从属关系记在台账 `fnb_check_item.parent_line_no`。
- **为什么不做成订单行上的"加价项"**：基座 `ord_item` 没有加价字段，加一个就要动金额计算、退款、结算、发票、积分五处。
  做成独立行则**金额天然正确、按行退款天然正确**，基座一行不改。
- **代价**：小票上是两行。用台账的父子关系在**渲染时合并显示**，不改数据。

## 2.3 套餐
基座组合商品（`prd_sku_bundle`）。`verify_mode` 用「一次性核销」。餐饮包不建套餐表。
**套餐换菜（可选组）** 一期不做，见 §14。

## 2.4 称重菜（海鲜、烤鱼）
基座 `ord_item` 已有 `nominalGram` / `weighed` / `weighAdjustMinor`（正=补款、负=退款），**直接复用**。
餐饮包只提供「称重录入」的界面与端点。

## 2.5 沽清（估清 / 86）
- **落法**：**基座门店库存 `prd_store_stock` 是唯一真源**。沽清 = 置 0，恢复 = 置回。
- 餐饮包提供：一键沽清/恢复、当日沽清清单、**每日重置任务**（`JobHandler`，营业日切换时恢复）。
- **为什么不另建"沽清表"**：那会造出第二个"还能不能点"的真源，
  于是「库存有、沽清了」和「沽清没有、库存 0」两种状态在系统里长得不一样而结果一样，排查时没人说得清。

## 2.6 时段菜单（早市 / 午市 / 晚市）
- **落法**：`fnb_menu` + `fnb_menu_item` + 时段规则。它**只回答"这个时刻能不能点"**，不碰价格、不碰上下架。
- 上架仍是基座 `prd_store_goods`：**没上架的菜，任何菜单都点不了**。菜单是在已上架集合上再做一次时间过滤。

## 2.7 堂食价 vs 外卖价
- **一期不做双价体系**。外卖差价用**打包费**表达：餐盒是一个 SKU，按份数下单成独立订单行。
- 要真正的双价，得给基座 `prd_store_price` 加渠道维度 —— **那是基座的事，不是餐饮包的**，也不该由餐饮包偷偷用一张自己的价格表实现（那会造出第二个价格真源）。

## 2.8 点单购物车 —— 必须是台账的，不能是基座的
基座 `trd_cart_item` 是 **userNo 维度**。而扫码点餐是**一桌多人同时点**：
用基座购物车，两个人各自的车互相看不见，「一起下单」根本表达不了。

- **落法**：`fnb_check_cart`（台账维度，记 who 加的，用于分单结账 §5.5）。
- 下单时把台账购物车转成基座下单请求，**购物车不进基座**。

---

# 三 · 工作流主干

```
                    ┌──────── 基座（一行不改） ────────┐
菜单 ──选择──> 台账购物车 ──下达──> 订单(WAIT_PAY | WAIT_OFFLINE_PAY)
 │              (fnb_check_cart)         │            │
 │                                   先付│            │后付
 │                                    支付│            │
 └─ fnb_menu                            ▼            │
    prd_store_goods              markPaid(基座)       │
    prd_store_stock                     │            │
                                        ▼            ▼
                         ┌────── 制作队列 fnb_check_item ──────┐
                         │  QUEUED → MAKING → READY → SERVED   │
                         └──────────────┬──────────────────────┘
                                        │ 出品分单打印(基座 I3/I4)
                                        ▼
                         交付：上菜 / 叫号取餐 / 外卖发货(基座履约)
                                        │
                                        ▼
                              结账 fnb_payment ──足额──> markPaid(基座)
                                        │
                                        ▼
                                   结台 CLOSED
```

**两处必须记住的**：
1. **制作与支付无关**。后付时钱还没收，菜必须已经在做了 —— 所以制作由**下达事件**触发，不由 `OrderPaid` 触发。
2. **金额只有一个真源：基座订单。** 台账只做聚合展示，绝不自己算钱（§7.3）。

---

# 四 · 各阶段详细

## 4.1 选择
1. 取当前营业时段 → 取菜单 → 与门店已上架集合求交 → 过滤沽清（库存 0）；
2. 加入台账购物车（记 `added_by`：哪个手机/哪个店员，分单结账要用）；
3. 做法与加料在加购时选定；加料展开为附加行。

**并发**：同桌多人同时加购，台账购物车用乐观锁；冲突重试，不做全局锁 —— 加购冲突的正确处理是"再试一次"，不是"排队"。

## 4.2 下达（两条链）

**先付后吃（S1/S3/S5）**
```
下单 → 基座订单 WAIT_PAY → 支付 → markPaid
     → OrderLifecycleListener.onPaid → 生成制作行 → 打印后厨票
```
**先吃后付（S2/S4/S6）**
```
下单 → 基座订单 WAIT_OFFLINE_PAY
     → 餐饮包自己发「已下达」事件 → 生成制作行 → 打印后厨票
     （不等支付。钱在结账时一次性收）
```

**这里有一处对基座的硬依赖**：`WAIT_OFFLINE_PAY` 的超时取消会把正在吃的桌子取消掉。
基座已按核心能力 D3 改成「按门店取配置 + 台账未结账不取消」。
餐饮包把 `OFFLINE_PAY` 写进 `requiredCapabilities()`，基座没这条就**拒绝启动**。

**幂等**：下达接幂等键（基座 J5）。重复提交只产生一张订单、一份后厨票。
**后厨票重复是最贵的一种重复** —— 会真的多做一份菜。

## 4.3 制作（KDS）

制作状态挂在 `fnb_check_item`（堂食）或直接挂订单行（外卖，`fnb_kitchen_item` 同表 `check_no` 为空）。

```
QUEUED ──开始制作──> MAKING ──出品──> READY ──上菜/交付──> SERVED
QUEUED ──等叫──────> HELD ──叫起──> QUEUED
任意 ──退菜──> VOIDED（见 §5.3）
```

- **叫起 / 等叫**：`HELD` 不占制作队列位置，叫起后重新入队并**重打后厨票**（票上标「叫起」）。
- **催菜**：不改状态，只记 `fnb_check_item_log` 并打催菜条 —— 催菜改状态的话，"催过的菜"和"正在做的菜"就分不开了。
- **划菜**：`READY → SERVED`，可整单划、可按行划。
- **外卖**：`READY` 后走基座履约发货，`SERVED` 由基座履约完成回写。

## 4.4 交付

| 场景 | 交付动作 | 谁记 |
|---|---|---|
| 堂食 | 上菜划菜 | 餐饮包 `SERVED` |
| 自取 | 叫号 → 核销 | 基座 `STORE_PICKUP` 核销 |
| 外卖 | 发货 → 配送 → 签收 | **基座履约全程**，餐饮包只监听 |

**外卖一行履约代码都不写**，这是行业包边界的试金石：写了就说明边界画错了。

## 4.5 结账

```
发起结账 → 台账 OPEN → SETTLING（此后不许加菜）
        → 计算应收 = Σ 台账下所有订单的 payAmount（基座算的，不是台账算的）
        → 收款：可多笔（现金 + 微信 + 储值），每笔一条 fnb_payment
        → 足额 → 对台账下每张订单调 CoreOrderApi.confirmOfflinePaid
        → 台账 CLOSED → 释放桌台资源 → 打结账小票
```

**`SETTLING` 这一档是必要的**：结账过程中不许再加菜，否则"合计多少"这件事没有确定的时刻。

**混合支付**：基座 `markPaid(orderNo, payChannel, tradeNo)` 只接受一个通道。
混合时 `payChannel` 记 `MIXED`（**`sys_pay_channel` 加一行数据，不是加代码**），
明细留在 `fnb_payment`，`tradeNo` 记餐饮包的收款单号。

---

# 五 · 七个动作的精确逻辑

**唯一的分叉判据是「这笔钱收没收」。** 所有动作按同一张表分叉，不许有第三条路。

## 5.1 加菜
| | 先付后吃 | 先吃后付 |
|---|---|---|
| 做法 | 新开一张订单，付款后下达 | 新开一张订单（`WAIT_OFFLINE_PAY`），立即下达 |
| 台账 | `fnb_check_order` 追加一行 | 同左 |
**永远不改已有订单。** 改已支付订单的金额，退款、结算、发票、积分四处全部对不上。

## 5.2 减菜（还没做）
| | 先付后吃（钱已收） | 先吃后付（钱未收） |
|---|---|---|
| 做法 | **走基座售后**：整行退款（`ord_after_sale`） | 台账直接撤行 + **订单作废或改单** |
| 订单 | 不动，退款单在旁边 | 未支付订单可整单取消后重下（**不做部分改单**） |
| 后厨 | 打撤单条 | 打撤单条 |

> **为什么不做"部分改单"**：基座订单的行是下单时的快照，改行金额要连带改子单汇总、优惠分摊、积分预估。
> 一张未支付订单重下一遍是**零风险**的，而改单是四处联动。**先吃后付的减菜 = 取消 + 重下。**

## 5.3 退菜（已做好）
| | 先付后吃 | 先吃后付 |
|---|---|---|
| 制作行 | `VOIDED` + 原因 + 操作人 | 同左 |
| 钱 | **基座售后退款** | 结账时不计入（订单取消重下） |
| 留痕 | `fnb_check_item_log` 必填原因 | 同左 |
**退菜必须有原因和操作人。** 没有原因的退菜是餐饮最大的内控漏洞（员工吃菜、送人情）。

## 5.4 并台
- 台账 A `merged_into = B`，A 置 `MERGED`；A 的 `fnb_check_order` 行**改挂 B**；
- **订单一张都不动**；
- A 的桌台资源立即释放（人已经并到 B 桌了）；
- 反向拆开：**不支持**。并台是不可逆动作，界面上要明确提示。

## 5.5 拆单（分开结账）—— 这里有一条硬限制，必须写在需求里
基座的一张订单只能整单支付。所以：

| 拆法 | 能不能做 | 怎么做 |
|---|---|---|
| **按订单拆**（这一轮谁点的谁付） | ✅ | 结账时勾选台账下的订单子集，分别收款、分别 `confirmOfflinePaid` |
| **按人拆**（谁点的谁付） | ✅ **但必须在点单时分** | 台账购物车记 `added_by`，下达时**按人各生成一张订单** |
| **按菜拆**（这道菜他付） | ⚠️ 同上，取决于点单时是否分开下达 | 同上 |
| **AA 均分**（总额除以 N） | ✅ | 一张订单、多笔 `fnb_payment`，凑够总额后一次 `confirmOfflinePaid` |
| **把一张已存在的订单拆成两张** | ❌ **不做** | 退款、结算、发票、积分全要跟着拆，且基座不支持 |

**结论写进设计**：「拆单」在实现上是**下单时分车** + **结账时选单**，
不是"事后把账单劈开"。这一条如果 PRD 想要事后劈账，得先改基座订单模型 —— **成本不在餐饮包**。

## 5.6 转台
释放旧资源 → 占新资源 → 改 `fnb_check.table_no`。
资源占用/释放走基座 `CoreResourceApi`（带条件 UPDATE，与库存、时段同一套并发手法）。
**先占后放**：新桌占不上就整体失败，不能出现"两桌都空着而账在半路"。

## 5.7 催 / 叫起
纯制作队列动作，不碰订单、不碰钱、不碰资源。见 §4.3。

---

# 六 · 数据库（`db/industry/food`，历史表 `fnb_flyway_history`）

> 表全部 `fnb_` 前缀。**餐饮包的迁移不许写基座的表**（有闸门）。
> 金额列只出现在 `fnb_payment`（收款流水）—— 其余表一分钱都不存，见 §7.3。

```sql
-- ── 桌台 ──────────────────────────────────────────────
CREATE TABLE fnb_table (
  table_no     VARCHAR(32) NOT NULL PRIMARY KEY,
  store_no     VARCHAR(32) NOT NULL,
  resource_no  VARCHAR(32) NOT NULL,          -- → 基座 mch_resource，占用/释放在那边
  area_no      VARCHAR(32) NULL,
  name         VARCHAR(32) NOT NULL,          -- 「A3」「包间·竹」
  seats        INT NOT NULL DEFAULT 4,
  qr_token     VARCHAR(64) NOT NULL,          -- 桌码。可重置（换桌牌、防串桌）
  status       VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  UNIQUE KEY uk_fnb_table_qr (qr_token),
  KEY idx_fnb_table_store (store_no, status)
);

CREATE TABLE fnb_area (
  area_no  VARCHAR(32) NOT NULL PRIMARY KEY,
  store_no VARCHAR(32) NOT NULL,
  name     VARCHAR(32) NOT NULL,
  sort     INT NOT NULL DEFAULT 0
);

-- ── 台账 ──────────────────────────────────────────────
CREATE TABLE fnb_check (
  check_no     VARCHAR(32) NOT NULL PRIMARY KEY,
  store_no     VARCHAR(32) NOT NULL,
  table_no     VARCHAR(32) NULL,              -- 外卖/自取无桌
  status       VARCHAR(16) NOT NULL,          -- OPEN/SETTLING/CLOSED/MERGED/VOIDED
  people       INT NULL,
  pay_mode     VARCHAR(16) NOT NULL,          -- PRE(先付) / POST(后付)，开台时定，不许中途改
  merged_into  VARCHAR(32) NULL,              -- 并台目标
  opened_by    VARCHAR(64) NOT NULL,
  opened_at    DATETIME NOT NULL,
  settling_at  DATETIME NULL,
  closed_at    DATETIME NULL,
  KEY idx_fnb_check_store_status (store_no, status),
  KEY idx_fnb_check_table (table_no, status)
);

CREATE TABLE fnb_check_order (
  check_no  VARCHAR(32) NOT NULL,
  order_no  VARCHAR(32) NOT NULL,             -- → 基座 ord_order
  seq       INT NOT NULL,                     -- 第几轮点单
  added_by  VARCHAR(64) NULL,                 -- 谁点的，按人拆单要用
  PRIMARY KEY (check_no, order_no),
  KEY idx_fnb_check_order_order (order_no)
);

-- ── 点单购物车（台账维度，不是 userNo 维度，见 §2.8）──
CREATE TABLE fnb_check_cart (
  line_no    VARCHAR(32) NOT NULL PRIMARY KEY,
  check_no   VARCHAR(32) NOT NULL,
  sku_no     VARCHAR(32) NOT NULL,
  qty        INT NOT NULL,
  modifiers  JSON NULL,                       -- 做法/口味（不加价）
  addon_of   VARCHAR(32) NULL,                -- 加料：指向主行 line_no
  added_by   VARCHAR(64) NOT NULL,
  version    BIGINT NOT NULL DEFAULT 0,       -- 乐观锁：同桌多人并发加购
  KEY idx_fnb_cart_check (check_no)
);

-- ── 制作行（KDS）。堂食走 check_no，外卖 check_no 为空 ──
CREATE TABLE fnb_kitchen_item (
  item_no        VARCHAR(32) NOT NULL PRIMARY KEY,
  store_no       VARCHAR(32) NOT NULL,
  check_no       VARCHAR(32) NULL,
  order_no       VARCHAR(32) NOT NULL,
  order_item_id  BIGINT NOT NULL,             -- → 基座 ord_item.id，金额与名称的唯一来源
  parent_item_no VARCHAR(32) NULL,            -- 加料挂主菜，仅用于显示合并
  dept_no        VARCHAR(32) NULL,            -- 出品部门，分单路由的依据
  title_snapshot VARCHAR(128) NOT NULL,       -- 冗余一份菜名：后厨票要打，不该为了打票去连表
  qty            INT NOT NULL,
  status         VARCHAR(16) NOT NULL,        -- QUEUED/HELD/MAKING/READY/SERVED/VOIDED
  queued_at      DATETIME NOT NULL,
  ready_at       DATETIME NULL,
  served_at      DATETIME NULL,
  KEY idx_fnb_kitchen_store_status (store_no, status, queued_at),
  KEY idx_fnb_kitchen_check (check_no)
);

CREATE TABLE fnb_check_item_log (
  log_no     VARCHAR(32) NOT NULL PRIMARY KEY,
  check_no   VARCHAR(32) NULL,
  item_no    VARCHAR(32) NULL,
  action     VARCHAR(16) NOT NULL,            -- URGE/HOLD/CALL/SERVE/VOID/GIFT
  qty        INT NULL,
  reason     VARCHAR(128) NULL,               -- VOID 必填
  operator   VARCHAR(64) NOT NULL,
  created_at DATETIME NOT NULL,
  KEY idx_fnb_item_log_check (check_no, created_at)
);

-- ── 出品部门 ───────────────────────────────────────────
CREATE TABLE fnb_dept (
  dept_no  VARCHAR(32) NOT NULL PRIMARY KEY,
  store_no VARCHAR(32) NOT NULL,
  name     VARCHAR(32) NOT NULL               -- 热菜/凉菜/水吧/烧烤
);
CREATE TABLE fnb_goods_dept (
  store_no VARCHAR(32) NOT NULL,
  goods_no VARCHAR(32) NOT NULL,
  dept_no  VARCHAR(32) NOT NULL,
  PRIMARY KEY (store_no, goods_no)
);

-- ── 菜单（只管"这个时刻能不能点"，见 §2.6）────────────
CREATE TABLE fnb_menu (
  menu_no    VARCHAR(32) NOT NULL PRIMARY KEY,
  store_no   VARCHAR(32) NOT NULL,
  name       VARCHAR(32) NOT NULL,            -- 早市/午市/晚市/夜宵
  time_rule  VARCHAR(128) NOT NULL,           -- 受限表达式：周几 + 时段
  channels   VARCHAR(64) NOT NULL,            -- DINE_IN,TAKEOUT —— 哪些渠道可见
  enabled    TINYINT NOT NULL DEFAULT 1,
  sort       INT NOT NULL DEFAULT 0
);
CREATE TABLE fnb_menu_item (
  menu_no  VARCHAR(32) NOT NULL,
  goods_no VARCHAR(32) NOT NULL,
  sort     INT NOT NULL DEFAULT 0,
  PRIMARY KEY (menu_no, goods_no)
);

-- ── 收款流水（餐饮包唯一存金额的表）────────────────────
CREATE TABLE fnb_payment (
  payment_no   VARCHAR(32) NOT NULL PRIMARY KEY,
  check_no     VARCHAR(32) NOT NULL,
  order_no     VARCHAR(32) NULL,              -- 按单拆时指定；AA 时为空（对台账收）
  pay_channel  VARCHAR(24) NOT NULL,          -- → 基座 sys_pay_channel
  amount       BIGINT NOT NULL,               -- 分
  trade_no     VARCHAR(64) NULL,
  status       VARCHAR(16) NOT NULL,          -- PENDING/SUCCESS/FAILED/REVERSED
  operator     VARCHAR(64) NOT NULL,
  created_at   DATETIME NOT NULL,
  KEY idx_fnb_payment_check (check_no, status)
);
```

**为什么 `fnb_kitchen_item` 要冗余 `title_snapshot`**：
后厨票每天要打几千张，为了打一行字去连基座订单行是无谓的耦合与延迟；
而菜名在下单那一刻就已经是快照了（基座 `ord_item.title` 同理），冗余不会分叉。

---

# 七 · 对象

## 7.1 领域对象（餐饮包内部）

| 对象 | 说明 |
|---|---|
| `DiningCheck` | 台账聚合根。持有 `checkNo/tableNo/status/payMode`；**并台、结账、结台三个动作的入口** |
| `CheckCart` | 点单购物车。加购/改量/删行/清空；转下单请求 |
| `KitchenItem` | 制作行。状态机的宿主 |
| `KitchenQueue` | 出品队列的读模型（按部门、按状态、按等待时长排序）|
| `TableMap` | 桌台图读模型（桌 + 台账 + 已点金额 + 等待时长）|
| `CheckoutBill` | 结账单：**从基座订单聚合出来的只读对象**，不落库 |
| `PaymentAttempt` | 一次收款尝试 |

## 7.2 状态机（两个，都是本包私有）

```java
// 台账
OPEN     → SETTLING, MERGED, VOIDED
SETTLING → CLOSED, OPEN            // 取消结账退回 OPEN
MERGED   → (终)
CLOSED   → (终)
VOIDED   → (终)                    // 仅限无已支付订单

// 制作行
QUEUED → MAKING, HELD, VOIDED
HELD   → QUEUED, VOIDED
MAKING → READY, VOIDED
READY  → SERVED, VOIDED
SERVED → VOIDED                    // 已上菜也可能退（走 §5.3）
```
写法照基座 `OrderStateMachine`：**唯一一处允许判断"能不能变"的地方**，
不许 `if (status == ...)` 散落在 Service 里。

## 7.3 一条写死的规矩：台账不存金额

`fnb_check` 上**没有** `total_amount`。应收永远是 `Σ 台账下订单的 payAmount`，现算。

理由不是性能洁癖：存一份就有两个真源，而它们会在**优惠、退款、称重差价**三处分叉，
且分叉时**两边都不报错** —— 只有顾客在结账时看出金额不对。
台账要展示合计，就走 `CheckoutBill` 这个读模型现聚合。

---

# 八 · 服务（接口签名）

```java
// ── 桌台与台账 ──
public interface TableService {
    List<TableMapVO> map(String storeNo);                 // 桌台图
    TableVO byQrToken(String token);                      // 扫码进店
    void resetQr(String tableNo, String operator);
}

public interface CheckService {
    CheckVO open(OpenCheckCmd cmd);          // 开台：占资源 + 建台账（幂等）
    CheckVO get(String checkNo);
    void merge(String fromCheckNo, String toCheckNo, String operator);   // 并台，不可逆
    void transfer(String checkNo, String toTableNo, String operator);    // 转台：先占后放
    void voidCheck(String checkNo, String reason, String operator);      // 作废（无已付订单）
}

// ── 点单 ──
public interface CartService {
    CartVO add(String checkNo, AddLineCmd cmd);      // 乐观锁，冲突重试
    CartVO change(String checkNo, String lineNo, int qty);
    CartVO remove(String checkNo, String lineNo);
    CartVO view(String checkNo);
}

public interface PlaceService {
    /** 下达。先付 → WAIT_PAY；后付 → WAIT_OFFLINE_PAY 并立即入厨。必接幂等键。 */
    PlaceResult place(String checkNo, PlaceCmd cmd);
    /** 按人下达：购物车按 added_by 分组，各生成一张订单（拆单的唯一实现路径，§5.5）。 */
    List<PlaceResult> placeSplitByPerson(String checkNo, PlaceCmd cmd);
}

// ── 制作 ──
public interface KitchenService {
    List<KitchenItemVO> queue(String storeNo, String deptNo, Set<String> statuses);
    void start(String itemNo, String operator);
    void ready(String itemNo, String operator);
    void serve(String itemNo, String operator);        // 划菜
    void serveAll(String checkNo, String operator);
    void hold(String itemNo, String operator);         // 等叫
    void call(String itemNo, String operator);         // 叫起，重打后厨票
    void urge(String itemNo, String operator);         // 催菜，不改状态
    void voidItem(String itemNo, String reason, String operator);   // 退菜，reason 必填
}

// ── 结账 ──
public interface CheckoutService {
    CheckoutBill bill(String checkNo);                 // 现聚合，不落库
    void beginSettle(String checkNo, String operator); // OPEN → SETTLING
    void cancelSettle(String checkNo, String operator);
    PaymentVO pay(String checkNo, PayCmd cmd);         // 一笔收款（可多次，支持混合）
    void close(String checkNo, String operator);       // 足额校验 → 逐单 confirmOfflinePaid → 结台 → 打小票
}

// ── 菜单与沽清 ──
public interface MenuService {
    List<MenuGoodsVO> current(String storeNo, String channel);   // 时段 ∩ 上架 ∩ 未沽清
    void soldOut(String storeNo, String goodsNo, String operator);
    void restore(String storeNo, String goodsNo, String operator);
}
```

---

# 九 · 与基座的交互点（全清单）

**调基座（出）**

| 时机 | 调用 | 说明 |
|---|---|---|
| 开台 / 转台 | `CoreResourceApi.occupy/release` | 并发安全在基座 |
| 下达 | `CoreOrderApi.place(...)` | 生成基座订单，**餐饮包不建单** |
| 结账 | `CoreOrderApi.confirmOfflinePaid(...)` | 逐单确认收款 |
| 储值支付 | `CoreMemberApi.deduct(...)` | **不许自己扣余额** |
| 沽清 | `CoreStockApi.setStock(...)` | 门店库存是唯一真源 |
| 打印 | `CorePrintApi.submit(scene, ctx)` | 设备/路由/重试全在基座 |
| 退菜退款 | `CoreAfterSaleApi.refundLine(...)` | 走基座售后状态机 |
| 能力判定 | `CoreCapabilityApi.enabled(storeNo, cap)` | 唯一判定处 |

**被基座调（入）**

| 扩展点 | 用途 |
|---|---|
| `IndustryPackage` | 声明 `FOOD`、能力、`requiredCapabilities`、迁移位置 |
| `OrderLifecycleListener.onPaid` | 先付后吃：付款后入厨、打票 |
| `OrderLifecycleListener.onCancelled/onRefunded` | 撤制作行、打撤单条 |
| `OrderLifecycleListener.onFulfilled` | 外卖签收 → 制作行 `SERVED` |
| `PrintPayloadProvider` | `KITCHEN` / `CHECKOUT` / `URGE` / `VOID` 四个场景的内容 |
| `ResourceTypeProvider(TABLE/ROOM)` | 桌台占用释放的语义回调 |
| `CheckoutContributor` | 下单前校验（桌台状态、菜单时段、沽清） |
| `JobHandler` | 沽清每日重置、超时未结台巡检 |

**对基座的三条硬依赖**（写进 `requiredCapabilities()`，缺一拒绝启动）：
1. `DINE_IN` 履约档；
2. `OFFLINE_PAY` **且**其超时规则已按门店配置化（否则吃到一半单被取消）；
3. `PRINT_ROUTE_SPLIT` 分单路由。

---

# 十 · 端点与权限码

```
/mp/x/food/**        买家：扫码进店、看菜单、加购、下单、看进度、结账
/biz/x/food/**       商家：桌台图、开台、代客点单、KDS、划菜、退菜、结账、沽清
/ops/x/food/**       运营：行业配置、模板与路由默认值
```

权限码段 `food:*`（按基座「读写分开」的规矩）：

| 码 | 用途 |
|---|---|
| `food:table:read` / `food:table:write` | 桌台图 / 建桌改桌 |
| `food:check:open` / `food:check:merge` / `food:check:transfer` / `food:check:void` | 台账动作**逐个分码** —— 并台与作废是内控敏感动作 |
| `food:order:place` | 代客点单 |
| `food:kitchen:read` / `food:kitchen:operate` | KDS 看 / 操作 |
| `food:item:void` | **退菜单独一个码**（内控要害，不与划菜合并） |
| `food:checkout:pay` / `food:checkout:discount` | 收款 / 折扣赠菜 |
| `food:menu:write` / `food:stock:soldout` | 菜单维护 / 沽清 |

**必须同步登记进 `scripts/perm-endpoint-map.mjs` 并重跑生成器**，否则 pre-push 的矩阵测试会红。

---

# 十一 · 打印场景（内容在包内，通道在基座）

| scene | 触发 | 路由 | 内容 |
|---|---|---|---|
| `KITCHEN` | 下达 / 叫起 | **按 `fnb_goods_dept` 分单**到各部门设备 | 桌号、轮次、菜名、做法、加料、数量 |
| `KITCHEN_VOID` | 退菜 | 同上 | 撤单标记、原因、操作人 |
| `URGE` | 催菜 | 同上 | 催菜条 |
| `CHECKOUT` | 结台 | 前台小票机 | 逐行、优惠、实收、多笔收款明细 |
| `PRE_BILL` | 结账前预览 | 前台小票机 | 「预结单」，**必须与小票视觉可区分** |

**预结单与正式小票必须一眼能分**，否则顾客拿预结单当收据、店员拿它当已收款。

---

# 十二 · 本包明确不做

外卖配送与运力调度（基座履约）· 三方外卖平台对接（另立议题，属渠道接入）·
进销存与成本卡（基座 `shop-inventory`）· 发票 · 会员与储值的账（基座）·
优惠券与活动（基座）· 结算分账（基座）· 排队叫号（**基座候选能力，两个行业都要**）·
套餐可选组换菜（§14）· 事后劈账（§5.5，需先改基座订单模型）。

---

# 十三 · 测试策略与闸门

**单元**
- 台账状态机与制作状态机的全图（照基座 `OrderStateMachineTest` 的写法：图完整性 + 非法迁移必拒）；
- 同桌并发加购（乐观锁冲突重试）；
- 转台「先占后放」：新桌占不上时旧桌必须仍被占着；
- 结账足额校验：少一分不许结台；多笔收款求和边界；
- 退菜必填原因：缺原因必拒。

**场景测试**（`shop-app/src/test/.../scenario/`）
1. S1 先付后吃：下单 → 支付 → 入厨 → 出票 → 取餐 → 完成；
2. S2 先吃后付：开台 → 三轮加菜 → 划菜 → 结账（混合支付）→ 结台，**核对每张订单都被 `confirmOfflinePaid`**；
3. 减菜两条链各一条（钱已收走售后、钱未收取消重下）；
4. 并台后结账金额 = 两台账订单之和；
5. 按人拆单：两人各下一单、各自结账；
6. **未结账不许自动取消**：把超时时钟推过阈值，订单必须还在。

**闸门**
- 剔除餐饮包（`-Pcore-only`）后，零售与美业全量测试仍全绿；
- ArchUnit：`fnb` 包不许 import 基座实体；餐饮迁移不许写非 `fnb_` 表；
- **真机出票人工验收** —— 分单路由、中文字符、切纸，假打印机测不出来；
- 撤掉「未结账不取消」的判定，用例 6 必须变红。

---

# 十四 · 待确认（PRD 必须回答）

1. **跑单**（吃完不付）：挂账给谁？系统只留痕还是要生成一笔应收？平台是否介入？
2. **折扣与赠菜**：谁有权限？是否需要二次审批？赠菜是否计入成本报表？（基座 `ord_item.isGift` 已有，口径要定）
3. **事后劈账**：真的需要吗？需要的话要改基座订单模型，成本与排期另计。
4. **套餐换菜**（可选组）：一期不做，二期要的话是基座 `prd_sku_bundle` 扩展，不是餐饮包。
5. **堂食价 ≠ 外卖价**：一期用打包费近似。真要双价，是基座 `prd_store_price` 加渠道维度。
6. **三方外卖平台**（美团/饿了么）：接不接？接的话订单来源、对账、抽佣口径全部另立。
7. **开台后能否改付款顺序**（POST → PRE）：建议**不许**，`fnb_check.pay_mode` 开台即定。
8. **服务费 / 茶位费**：按人头自动加行，还是店员手动加？是否可减免？
