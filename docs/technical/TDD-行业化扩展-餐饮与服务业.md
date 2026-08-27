# TDD 行业化扩展：餐饮 / 服务业（美业）共用一套交易主干

状态：**草稿 · 待确认**
关联需求：**缺**（见 §0，本册先行，PRD 待补）
关联决策：[ADR-019 行业工作流做成独立插件项目](ADR/ADR-019-行业工作流做成独立插件项目.md) · [标准能力基线（零售为默认）](reference/标准能力基线-零售为默认.md) · [ADR-005 履约方式与自提点模型](ADR/ADR-005-履约方式与自提点模型.md) · [ADR-009 商家经营范围三档模型](ADR/ADR-009-商家经营范围三档模型.md) · [ADR-011 经营主体与门店边界](ADR/ADR-011-经营主体与门店边界.md) · [TDD-线下支付与预约排期](TDD-线下支付与预约排期.md)
创建：2026-08-27

---

## 0. 先说一句流程上的话

`docs/requirements/` 里没有「行业化扩展」的 PRD。按项目规范（P0）本该先补需求再出方案；
但这次的问题本身是**架构选型**（要不要为行业分叉），不先把架构判断摆出来，PRD 写不到点上。
所以本册按「技术判断先行」交付，**§13 列出了 PRD 必须回答的问题**，答完再回填本册的验收标准。

---

## 1. 结论先行

**不要为餐饮和美业各做一套系统，也不要在交易主干里写 `if (行业)`。**

盘完现有代码，三端的统一层（商户 / 门店 / 商品 / 订单 / 支付 / 会员 / 营销）**已经具备承接这两个行业的形状**，
真正缺的东西只有三类，而且都不在交易主干上：

| 缺什么 | 归属 | 为什么不在主干 |
|---|---|---|
| **可约资源**（技师 / 桌台 / 工位） | 行业模块 + 一处既有能力泛化 | 现有 `mch_appointment_slot` 的容量是一个整数，表达不了「约的是谁」 |
| **行业台账**（餐桌 check、服务工单） | 行业模块，反向依赖交易域 | 台账状态（开台/出餐/结账）不该混进统一订单状态机 |
| **设备通道**（打印） | `shop-channel`，与 notify 同构 | 打印是「把一条消息送到一台设备」，和送到微信是同一类事 |

行业差异用**能力开关（capability）**表达，不用行业枚举：代码问的是
`「这家店开没开桌台点单」`，不是 `「这家店是不是餐饮」`。
行业只是**能力的预设包**，改行业不改代码。

---

## 2. 现状盘点：统一层已经有什么（都是实测的，不是估计）

| 域 | 现有能力 | 路径 | 对新行业够不够 |
|---|---|---|---|
| 主体 / 门店 | 主体—门店两层；门店挂 `businessMode`（自营/第三方）、状态三档 | `shop-merchant/entity/MchEntity, MchStore` | ✅ 直接复用。行业挂门店（与 `businessMode` 同一层级，理由见 §4.2） |
| 商品 | 标品 SPU / 商家 SKU / 门店价 / 门店库存 / 规格模板 / 类目模板 | `shop-core/product/entity/*` | ✅ 菜品、服务项目都是 SKU。**套餐/疗程包是组合商品，属统一层**，不是行业功能 |
| 类目 | 平台主数据，带 `template` / `attrTemplate` / `qualificationRequired`；已有「平台×类目」开关表 `prd_category_pay_mode` | `PrdCategory`, `PrdCategoryPayMode` | ✅ **能力开关的现成范式**，§5 直接照它扩 |
| 订单 | 主单/子单两层；统一状态机是唯一判定处；售后独立状态机 | `shop-core/trade/service/OrderStateMachine` | ✅ 主干不动，见 §6 |
| 履约 | 取值域 `Fulfillments`：自提 / 邻里自提 / 商家自送 / 快递 / **到店核销** / **上门预约** | `shop-base/common/Fulfillments.java` | ⚠️ 差堂食一档，见 §6.1 |
| 支付 | `PayModes{ONLINE, OFFLINE}` + `WAIT_OFFLINE_PAY` 状态 + `markPaid` 幂等且不关心钱从哪来 + `sys_pay_channel` 是主数据不是枚举 | `PayModes`, `OrderStateMachine`, `OrderServiceImpl#markPaid` | ✅ **「先吃后付」不需要新链路**，见 §6.2 |
| 预约 | `mch_appointment_slot`（门店级时段 + 容量），带条件 UPDATE 占位，`AppointmentSlotPort` 三动作，已有场景测试 | `shop-merchant/entity/MchAppointmentSlot`, `spi/user/AppointmentSlotPort` | ⚠️ **只差资源维度**，见 §7.1 —— 这是唯一要动既有已测能力的地方 |
| 会员 | 会员 / 门店会员关系 / 标签 / 分群 / 触达 | `shop-core/member/entity/*` | ⚠️ **没有储值和次卡**，见 §7.3 |
| 营销 | 活动 / 券 / 发放 / 核销 / 适用范围 | `shop-core/promotion/entity/*` | ✅ 够用 |
| 员工 | `mch_account` 是**登录账号**，`mch_store_role` 是权限角色 | `shop-merchant/entity/*` | ⚠️ **没有「服务人员档案」**（技能、等级、提成、排班），见 §7.2 |
| 事件 | outbox + `OutboxDispatchJob`，交易域已发 `OrderPaid` | `shop-base/event/*` | ✅ 行业编排挂事件，不侵入交易 |
| 设备 | **无** | — | ❌ 打印从零起，见 §8 |
| 任务 | 独立 job 域，支持手动触发 | `shop-job*` | ✅ 排班生成、超时未结账巡检直接挂 |

**一句话**：统一层缺口只有四处 —— 堂食履约、可约资源、人员档案、会员资产（储值/次卡）；
其余全是行业模块自己的事。

---

## 3. 两个行业到底差在哪

把「差异」摊开，才看得出哪些是真差异、哪些只是名字不同。

### 3.1 餐饮

| 场景 | 本质 | 落在哪 |
|---|---|---|
| 扫码点餐、堂食 | 一种履约方式（不送、不取、就地消费） | `Fulfillments.DINE_IN`（新增一档） |
| 桌台、开台、并台、转台 | **门店的可约/可占资源**，与美业的工位同构 | 行业模块 `shop-industry-food` |
| 加菜（一桌多次下单） | 一个台账下挂 N 张订单 | 行业台账表持 `order_no`，交易域零改动 |
| **先付后吃** | 就是现有的 `ONLINE` 下单支付流 | 零改动 |
| **先吃后付** | 就是现有的 `WAIT_OFFLINE_PAY` + 结账时 `markPaid` | 见 §6.2，零改动 |
| 后厨打印、分单出品 | 设备通道 + 路由规则 | `shop-channel/print` |
| 催菜 / 退菜 / 划菜 | 台账动作，不是订单状态 | 行业模块 |
| 外卖 / 自取 | 已有 `MERCHANT_DELIVERY` / `STORE_PICKUP` | 零改动 |

### 3.2 服务业 / 美业

| 场景 | 本质 | 落在哪 |
|---|---|---|
| 选项目、选时间 | 已有 `APPOINTMENT` + 时段容量 | 复用 |
| **指定技师** | 时段要按「资源」而不是「门店」计数 | §7.1（改造既有） |
| 排班 | 由排班规则**生成**时段，不是另一套时段 | 行业模块生成 → 写既有 slot 表 |
| 技师档案、等级、擅长项目 | 人员主数据 | §7.2 |
| 到店核销 / 上门服务 | 已有 `STORE_VERIFY` / `APPOINTMENT` | 复用 |
| **办卡、储值、次卡、疗程** | 会员资产 | §7.3（统一层，不是行业功能） |
| 服务工单（开始/结束/换人） | 行业台账 | 行业模块 |
| 提成 | 依赖人员档案 + 订单行归属人 | 阶段 3 |
| 打印服务单/结账单 | 同一套设备通道，模板不同 | `shop-channel/print` |

### 3.3 看穿的两处同构 —— 这决定了方案规模

1. **桌台 ≡ 技师 ≡ 工位**：都是「门店里数量有限、按时间被占用的资源」。
   一套 `资源 × 时段` 模型同时喂饱两个行业，做两套是纯浪费。
2. **先吃后付 ≡ 线下支付**：都是「先履约、后收钱、平台不碰这笔钱」。
   现有的 `WAIT_OFFLINE_PAY` 就是为这件事造的，**餐饮不需要新的支付状态**。

---

## 4. 方案选型

### 4.1 三条路

| 方案 | 做法 | 结论 |
|---|---|---|
| **A. 能力开关 + 行业插件模块**（推荐） | 统一层只加取值域与资源模型；行业逻辑放独立 maven 模块，**反向依赖**交易域，挂事件编排；行业 = 能力预设包 | ✅ **采用**。交易/支付/会员/营销/结算/售后**一行都不用为行业改**；新增行业不动主干 |
| B. 交易主干里按行业分支 | `ord_order.biz_type` + 各处 `if` | ❌ 状态机、结算、售后、报表全部长出行业分支。三个行业之后没人敢改主干 |
| C. 每个行业一套独立系统（复制主干） | 各自建库建服务 | ❌ 商户/会员/营销要跨系统同步；「统一会员」这条最值钱的诉求当场作废 |

### 4.2 行业挂在哪一层

**挂门店**，与 `businessMode` 同一层级。理由与那条注释同源：
「同一主体下旗舰店做自营、加盟店做第三方是常见形态」——
同理，一个主体名下开了餐厅又开了美容店并不罕见，而**能力开关本来就是按店不同的**
（同一家餐厅，A 店上了扫码点餐、B 店还在用纸单）。

主体上只留一个**主营行业**用于展示与入驻引导，不参与任何判定。

### 4.3 为什么代码里不问「行业」而问「能力」

因为行业是个会漂的标签，能力不会。三条具体后果：

- 火锅店和快餐店都是餐饮，一个要桌台一个不要 —— 问行业得到的答案是错的；
- 美甲店也要「排队叫号」，那是餐饮先做的能力 —— 按行业写死就得复制一遍；
- 灰度上线只能按能力，按行业等于一次性给所有餐饮商家换系统。

**代码里唯一允许出现行业码的地方：开店时把预设能力包写进门店能力表。此后无人再读它。**

---

## 5. 能力模型（统一层，最小面）

沿用仓库里已经验证过的两条范式：
① 「平台×类目」开关表（`prd_category_pay_mode`）；② 「一行都没有 = 按旧口径放行」的兼容口径。

```sql
-- 行业主数据。只做预设与展示，不参与判定
CREATE TABLE sys_industry (
  industry_code VARCHAR(32) PRIMARY KEY,   -- RETAIL / FOOD / SERVICE
  name          VARCHAR(64) NOT NULL,
  status        VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
);

-- 行业 → 默认能力包。开店时展开成门店能力行，之后互不影响
CREATE TABLE sys_industry_capability (
  industry_code VARCHAR(32) NOT NULL,
  capability    VARCHAR(48) NOT NULL,
  default_on    TINYINT NOT NULL DEFAULT 1,
  PRIMARY KEY (industry_code, capability)
);

-- 门店实际开通的能力。**这是唯一被代码读的表**
CREATE TABLE mch_store_capability (
  store_no   VARCHAR(32) NOT NULL,
  capability VARCHAR(48) NOT NULL,
  enabled    TINYINT NOT NULL DEFAULT 1,
  PRIMARY KEY (store_no, capability)
);

ALTER TABLE mch_store ADD COLUMN industry_code VARCHAR(32) NOT NULL DEFAULT 'RETAIL';
```

取值域常量类 `Capabilities`（`shop-base/common/`，与 `Fulfillments` / `PayModes` 同构、同理由：
商品域、交易域、行业模块都要用它，取值域属于三者之上）：

```
TABLE_ORDERING     桌台点单
PAY_AFTER_SERVE    先享后付（结账时收款）
KITCHEN_PRINT      出品分单打印
RESOURCE_BOOKING   按资源预约（指定技师/工位/包间）
STAFF_SCHEDULE     排班
STORED_VALUE       储值
TIMES_CARD         次卡 / 疗程
```

**关于默认值的一条硬规矩**：`mch_store_capability` 一行都没有时，
`RESOURCE_BOOKING` 之外的能力一律**默认关**（新能力对存量商家不该自己长出来），
而 `RESOURCE_BOOKING` 沿用 `AppointmentSlotPort` 已有的「一个时段都没开 = 按旧口径放行」。
> 记住：**默认关的那一半才是生产常态**。开关的两个态都必须有测试，
> 只测「开着」的那一态，等于没测（历史上已经吃过一次）。

---

## 6. 交易主干：只动两处，其余零改动

### 6.1 履约取值域加一档 `DINE_IN`（堂食）

```java
/** 堂食：不送、不取，就地消费。与 STORE_VERIFY 的差别只有一条 —— 有占用中的桌台。 */
public static final String DINE_IN = "DINE_IN";
```

- 进 `SERVICE_LIKE`：支付成功后落 `FULFILLING`，不经 `WAIT_FULFILL`
  （堂食没有「待发货」，把它丢进待发货，界面会说待发货而根本没有东西要发）；
- **不进** `PHYSICAL`（不进新建商品的默认集合，须商家显式选）；
- **不进** `NEEDS_APPOINTMENT`（堂食不预定；「预定包间」是另一件事，走 `APPOINTMENT` + 资源）。

改动面：一个常量 + 两个集合 + 一处 C 端文案。状态机不加边。

### 6.2 「先吃后付」不需要新东西

| 模式 | 已有链路 | 需要新增 |
|---|---|---|
| 先付后吃 | 下单 → `WAIT_PAY` → 微信支付 → `markPaid` | **无** |
| 先吃后付 | 下单 → `WAIT_OFFLINE_PAY` → 上菜（台账推进，不改订单）→ 结账 → `markPaid(orderNo, 通道, 流水)` | **无** |

关键在于**不给子单加「制作中 / 上菜中」状态**：那是台账状态，属于行业模块，
一旦进统一状态机，零售和美业也要认它，而它们永远不会用。

**唯一要补的一条口径**：`WAIT_OFFLINE_PAY` 现在的超时规则是「商家一直没确认收款 → 取消」。
堂食下这条会把正在吃的桌子取消掉。→ 超时时长按门店能力取，开了 `PAY_AFTER_SERVE` 的门店给一个长值（配置项，不硬编码），
并且**台账未结账时不许自动取消**。

### 6.3 行业数据怎么挂到订单上

**不往 `ord_order` 加列。** 行业表自己持 `order_no`：

```
fnb_dining_check(check_no, store_no, table_no, status, opened_at, closed_at, ...)
fnb_check_order(check_no, order_no)        -- 一桌多单（加菜）
svc_work_order(work_order_no, order_no, staff_no, slot_no, status, ...)
```

方向是**行业模块依赖交易域，交易域不知道行业存在**。
反过来（交易域留一个 `ext_json` 让行业塞东西）看着省事，实际是把行业耦合藏进主干 ——
半年后没人说得清那个 JSON 里有什么、谁在读。

---

## 7. 统一层的四处缺口

### 7.1 可约资源：把时段从「门店级」泛化到「资源级」⚠️ 改既有已测能力

现状：`mch_appointment_slot(store_no, start_at, end_at, capacity, booked)`，
容量是一个整数 —— 表达得了「这家店 9 点能接 3 单」，表达不了「9 点李师傅能接 1 单」。

方案：**加一列，不改语义**。

```sql
CREATE TABLE mch_resource (          -- 桌台 / 技师 / 工位 / 包间，同一张表
  resource_no   VARCHAR(32) PRIMARY KEY,
  store_no      VARCHAR(32) NOT NULL,
  resource_type VARCHAR(24) NOT NULL,   -- TABLE / STAFF / SEAT / ROOM
  name          VARCHAR(64) NOT NULL,
  capacity      INT NOT NULL DEFAULT 1, -- 桌台=座位数；技师=1
  status        VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  attrs         JSON
);

ALTER TABLE mch_appointment_slot
  ADD COLUMN resource_no VARCHAR(32) NULL;   -- NULL = 门店级时段，旧行为不变
```

`AppointmentSlotPort.tryBook(slotNo, storeNo)` 的**签名不变、语义不变**
（`slotNo` 仍是唯一入口，越权闸仍比对 `storeNo`）；
新增一个 `List<SlotView> listByResource(...)` 供 C 端选人。
带条件 UPDATE 占位的手法**一个字都不改** —— 那正是这套设计能被复用的原因。

> 这一处触及已通过测试的功能（`AppointmentSlotFlowTest`）。按 P6，**需要确认后再动**；
> 已确认无法通过纯扩展绕开：容量的计数口径必须能按资源分组，而那是行上的事。

### 7.2 服务人员档案

现有 `mch_account` 是**登录账号**（可能没有），`mch_store_role` 是**权限角色**。
技师是**被顾客选择的对象**，可能根本不登录系统。两者不能合并。

```sql
CREATE TABLE mch_staff (
  staff_no    VARCHAR(32) PRIMARY KEY,
  entity_no   VARCHAR(32) NOT NULL,
  store_no    VARCHAR(32) NOT NULL,
  user_no     VARCHAR(32) NULL,      -- 有账号才关联，NULL 完全正常
  name        VARCHAR(64) NOT NULL,
  level_code  VARCHAR(24) NULL,      -- 等级（影响加价）
  avatar      VARCHAR(255) NULL,
  status      VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
);
CREATE TABLE mch_staff_skill (staff_no VARCHAR(32), goods_no VARCHAR(32), PRIMARY KEY(staff_no, goods_no));
```
`mch_resource` 里 `resource_type='STAFF'` 的行通过 `attrs.staff_no` 指回来 ——
排期只认资源，不认人是谁；档案只管人，不管排期。

### 7.3 会员资产：储值 + 次卡（**统一层，不是行业功能**）

餐饮要储值、美业要储值和次卡、零售也会要。做进行业模块就得做三遍。
而且它牵动支付与结算 —— 储值是**预收款**，不是营销让利，口径错了是财务问题。

```sql
mbr_asset_account(account_no, member_no, entity_no, asset_type, balance, ...)   -- STORED_VALUE / TIMES
mbr_asset_txn(txn_no, account_no, direction, amount, biz_type, order_no, ...)   -- 唯一的余额变更入口
mbr_card(card_no, member_no, card_def_no, remain_times, expire_at, status)
```

三条必须写死在设计里的规矩：
1. **余额变更只走流水表**，绝不 `UPDATE balance = balance - x` 而无流水；扣减用带条件 UPDATE 判影响行数（与库存、时段同一套手法）；
2. **储值支付是一种 `sys_pay_channel`**（`supports_split=0, supports_payout=0`），
   于是 `markPaid` 不用认识储值 —— 又一次复用；
3. **储值余额归主体（`entity_no`），不归门店**，跨门店通用与否由能力开关决定；退款、闭店清算另立议题（§11）。

### 7.4 组合商品（套餐 / 疗程包）

餐饮套餐和美业疗程包是同一件事：一个 SKU 卖出去，核销 N 个子项。
放商品域（`prd_sku_bundle`），不放行业模块。

---

## 8. 打印：一条设备通道，不是餐饮功能

打印的本质是「把一条结构化消息送到一台设备」，与 `shop-notify` 送微信模板消息同构。
所以放 `shop-channel/print`，复用 outbox（**打印必须可重打、不可丢**）。

```
prn_printer(printer_no, store_no, vendor, device_sn, key, status)     -- 飞鹅/易联云/365 等适配
prn_template(template_code, store_no, scene, content)                 -- 模板存库，不写进代码
prn_route(route_no, store_no, scene, match_expr, printer_no, copies)  -- 路由规则
prn_job(job_no, store_no, printer_no, template_code, payload, status, retry_count)
```

**行业差异全部落在 `prn_route` 的数据里，不落在代码里**：

| 行业 | 场景（scene） | 路由 |
|---|---|---|
| 餐饮 | `ORDER_PLACED` | 按菜品出品部门分单 → 热菜机 / 凉菜机 / 水吧 |
| 餐饮 | `CHECKOUT` | 前台小票机 |
| 美业 | `SERVICE_START` | 前台服务单 |
| 零售 | `ORDER_PAID` | 小票机 |

触发方式：**订阅事件，不在交易域调用打印**。
`OrderPaid` 已有；台账事件（开台、下单、结账）由行业模块自己发。

风险提示：打印机是硬件，自动化测试只能测到「任务生成 + 路由命中 + 重试」这一层，
**真机出票必须人工验收**，且要在方案里就承认这一点，不要用假打印机的绿测掩盖。

---

## 9. 模块设计

**新增**

| 模块 | 路径 | 职责 |
|---|---|---|
| `Capabilities` | `shop-base/common/` | 能力取值域常量 |
| `CapabilityPort` | `shop-base/spi/user/` | 「这家店开没开某能力」——唯一判定入口 |
| 资源与排期 | `shop-merchant/`（扩） | `mch_resource`、slot 加资源维度、`mch_staff` |
| 会员资产 | `shop-core/member/`（扩） | 储值 / 次卡 / 流水 |
| 打印通道 | `shop-channel/print/` | 设备、模板、路由、任务 |
| `shop-plugin-api` | `backend/shop-plugin-api`（新，**零依赖**） | 插件契约：`IndustryPlugin` / `OrderLifecycleListener` / `PrintPayloadProvider` / `ResourceTypeProvider` / `CheckoutContributor` |
| **`shop-industry-food`** | `backend-plugins/shop-industry-food`（**独立 maven 项目**） | 桌台、开台台账、加菜、结账编排、出品分单 |
| **`shop-industry-service`** | `backend-plugins/shop-industry-service`（**独立 maven 项目**） | 服务工单、排班生成、指定技师、耗卡核销 |

> 插件为什么是独立项目、为什么同仓不同 reactor、为什么一期不做热插拔 —— 见 [ADR-019](ADR/ADR-019-行业工作流做成独立插件项目.md)。
> **零售是基座默认形态**：不装任何插件的 jar 必须能起、零售全量场景测试必须全绿（可剔除闸）。

**修改**（面很小，这是方案成立的证据）

| 模块 | 变更 |
|---|---|
| `Fulfillments` | 加 `DINE_IN`，进 `SERVICE_LIKE` |
| `mch_store` | 加 `industry_code` |
| `mch_appointment_slot` | 加 `resource_no`（NULL = 旧行为） |
| `OrderServiceImpl` | 仅 `WAIT_OFFLINE_PAY` 超时时长改为按门店取配置 |
| `sys_pay_channel` | 加 `STORED_VALUE` 一行（数据，非代码） |

**一行不动**：`OrderStateMachine`、售后状态机、库存锁定、结算链路、券与活动、积分。

**依赖方向**（架构守卫要能拦住反向）：
```
shop-industry-food        shop-industry-service      ← 独立 maven 项目，互不认识
        └──────── 只依赖 ────────┘
                    ▼
             shop-plugin-api                          ← 零依赖契约（照 shop-job-api 的写法）
                    ▲
                    └── 实现 ── shop-app / shop-core / shop-merchant / shop-channel
                                        │ 依赖
                                        ▼
                                 shop-base(取值域 / SPI / 事件)
```
三条硬规矩：
1. **插件不许 import 基座的实体**（`OrdOrder` 等）。实体会连同 MyBatis、事务、列名一起漏过去，
   一旦插件里出现 `ord_order` 的列名，「改主干不动插件」就不再成立 —— 只能用契约上的 `OrderView`。
2. **插件之间互不依赖**。共用的东西（资源、人员、打印、叫号）一律下沉到基座，不横向引用 —— 那是插件化最常见的死法。
3. **没有改价、改库存、改订单状态的扩展点**。插件只能 `CheckoutAdvice` 建议，由基座决定采不采纳。

---

## 10. 三条关键流程

**① 餐饮 · 先付后吃（扫码点餐）**
```
扫桌码 → 开台(fnb_dining_check) → 选菜下单(履约=DINE_IN) → 微信支付 → markPaid
      → 事件 OrderPaid → 行业模块：挂到台账 + 出品分单打印 → 出餐 → 结台
```
**② 餐饮 · 先吃后付**
```
开台 → 加菜（每次一张 WAIT_OFFLINE_PAY 订单，挂同一台账）→ 后厨打印（台账事件触发，与支付无关）
     → 结账：台账合计 → 收款(线上/线下/储值) → 对每张订单 markPaid → 结台 → 小票
```
> 交易域看到的只是「几张线下支付订单被确认收款」，与它今天已经在做的事完全一样。

**③ 美业 · 预约到服务**
```
选项目 → 选技师(资源) → 选时段(该资源的 slot) → tryBook 占位 → 下单(履约=APPOINTMENT/STORE_VERIFY)
     → 支付(现金/线上/次卡耗卡) → 到店：开服务工单 → 打印服务单 → 完成 → 核销/耗卡 → 结算
```

---

## 11. 风险与注意事项

1. **能力开关默认关的一态没人测** —— 两个态都要有用例，且撤掉开关判定后测试必须变红。
2. **迁移号撞车** —— 本方案 20+ 张表，多会话并行时必然撞 Flyway 号；分批提交、改号后 `clean package`。
3. **H2 与 MariaDB 方言差异** —— 新表大量用 JSON 列与条件 UPDATE，测试绿 ≠ 生产对。
4. **加列必须补实体字段** —— `mch_store.industry_code`、`slot.resource_no` 各有一个实体要同步，否则那列永远读出 null。
5. **储值的财务口径** —— 预收款、跨门店通用、闭店清算、退卡，这四条**属于业务政策不属于技术**，必须在 PRD 里定死再开工。
6. **打印靠真机验收**，不接受假打印机的绿测。
7. **B 端工作台会分叉** —— 后端不分叉不代表前端不分叉：收银台、桌台图、排班表是三套完全不同的界面。前端按能力条件渲染，并且**改完要重跑 `gen-ui-catalog.py`**。
8. **多行业后台的权限矩阵** —— 新能力要同步进权限码与「角色×端点矩阵」生成物，生成物不重跑等于没改。

---

## 12. 分期实施

| 阶段 | 内容 | 产出闸门 |
|---|---|---|
| **0 · 基座** | 能力模型 + 打印通道 + `shop-plugin-api` + `DINE_IN` + `mch_resource` + slot 资源维度 + `mch_staff`；**零售全能力跑通** | 存量商家行为零变化（回归全绿）；开关两态各有用例；`-Pcore-only` 可构建可启动 |
| **1 · 美业插件** | `shop-industry-service`：排班生成、指定技师预约、服务工单、耗卡核销、服务单打印（储值/次卡在基座） | 一家试点店跑通「约—到店—服务—耗卡—结账」；剔除该插件后零售仍全绿 |
| **2 · 餐饮插件** | `shop-industry-food`：桌台、开台台账、加菜、先吃后付结账、后厨分单打印 | 一家试点店跑通两种付款顺序 + 真机出票；剔除该插件后零售与美业仍全绿 |
| **3 · 收口** | 提成、行业报表、B 端工作台按能力分叉、运营端行业配置 | 三行业共用一份订单/会员/结算报表 |

阶段 0 不交付任何面向商家的新功能，**它的验收标准就是「什么都没变」** —— 这是它最容易被跳过、也最不能跳过的地方。

---

## 13. PRD 必须回答的问题（本册无法自答）

1. 储值：预收款还是充值即确认收入？跨门店通用？可退否？闭店怎么清算？
2. 次卡/疗程：过期作废还是顺延？可转让否？未耗完退款怎么算？
3. 先吃后付：跑单（吃完不付）怎么处理？平台是否兜底？超时多久算跑单？
4. 提成：按订单行还是按服务工单？换技师后归谁？退款是否倒扣？
5. 桌台：并台/转台后账怎么并？分单结账（AA）一期做不做？
6. 指定技师是否加价？加价进商品价还是单独一行？
7. 打印机采购模型：商家自购还是平台配？换机怎么迁移配置？
8. 行业上线范围：先做哪个行业、试点几家店、灰度按什么维度切？

---

## 14. 测试策略

- **单元**：`Capabilities` 取值域、能力判定（开/关/无行三态）、资源级 slot 并发占位（两人抢最后一个名额必须只成一个）、余额扣减并发、打印路由命中。
- **场景**（`shop-app/src/test/.../scenario/`）：三条流程各一条端到端；先吃后付的「未结账不得自动取消」；储值支付走 `markPaid` 与线上单产生同样的结算与积分结果。
- **回归闸门**：阶段 0 落地后，现有全量测试**一条都不许改**。改了就说明主干被侵入了 —— 这是本方案唯一的硬指标。
- **反向验证**：撤掉能力开关判定、撤掉资源维度占位，对应用例必须变红且点名正确；恒绿的守卫等于没有守卫。

---
确认记录：待确认
