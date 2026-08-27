# TDD 美业包（BEAUTY）—— 业务场景与工作流

状态：**草稿 · 待确认** · 创建 2026-08-27
关联：[TDD-行业包-机制与装配](./TDD-行业包-机制与装配.md) · [TDD-餐饮包](./TDD-餐饮包-场景与工作流.md)（**本册章节与它逐节对齐，便于 P3 逐条比对**） · [核心能力清单](../reference/核心能力清单.md) · [方案梳理清单](./方案梳理清单-行业化接口盘点与终局选型.md) P1

> 与餐饮那份同一条规矩：**基座已有的一律不重做。**
> 项目 = 基座 SKU，钱 = 基座订单与支付，卡 = 基座会员资产，退 = 基座售后，账 = 基座结算。
> 美业包只负责基座没有的那三样：**预约单、工单、提成**。

---

# 一 · 业务场景

同样四问：**谁在操作 / 在哪个端 / 钱什么时候收 / 服务什么时候做。**

## 1.0 先说一处与餐饮的根本不同

餐饮只有两种付款时点：**先付**、**后付**。
美业有**第三种**：**钱在很久以前就付过了** —— 卖卡那天收的钱，今天来做的是第 7 次。

这一条决定了后面所有分叉都比餐饮多一维：
**不是「钱收没收」，而是「钱收没收 × 次数扣没扣」**（见 §五）。
它同时决定了收入确认与提成基数不能是同一个数（见 §4.6）。

## 1.1 七个场景

### S1 · 线上预约到店（主流量入口）
c-app 选项目 → 选技师（可不指定）→ 选时段 → 付款或选到店付 → 到店核销 → 服务 → 完成。
- 钱：**下单即收**（线上付）或**服务后收**（到店付）。服务：按约定时段。
- 失败面：**爽约**（no-show）。名额被占了一小时而没人来 —— 这是美业最贵的损失。

### S2 · 到店直客（walk-in）
客人直接进店 → 前台看今日排程 → 有空档就开单 → 服务 → 结账。
- **没有预约单**，直接开工单。
- 高峰期要排队等位（`QUEUE_CALL`，基座候选能力，与餐饮共用）。

### S3 · 电话 / 微信预约（前台代客）
客人不在系统里，前台按姓名手机建约。
- **顾客可能没有会员账号** —— 这是与餐饮扫码点单最大的操作差异。
- 建约时不产生订单（钱一分没收），到店才下单。**这一条决定了预约单必须能脱离订单存在**（§7.1）。

### S4 · 卡客到店（老客主力）
客人早就充了钱或买了疗程 → 到店做 → 扣次或扣余额 → 走人。
- 钱：**早就收过了**。今天这笔订单实收 0 元。
- 报表陷阱：实收 0 不等于这次服务没有价值 —— **提成、业绩、成本都要按分摊价算**（§4.6）。

### S5 · 疗程包分次履约
买 10 次脸部护理，跨三个月做完。
- **一张订单对应十次服务** —— 这个形态餐饮完全没有，它挑战「一张订单 = 一次消费」的隐含假设。
- 落法：卖卡是一张订单（收钱），每次到店是**另一张订单**（实收 0，次卡抵扣）。**不是同一张订单履约十次。**

### S6 · 上门服务
美甲、美睫上门。走基座 `APPOINTMENT` 履约（必须有时间 + 必须有地址）。
- 占位要含**路途缓冲**：一个师傅 14:00 的城东单和 15:00 的城西单不能都接。

### S7 · 双人 / 团体同时服务
一次预约占**多个资源**（两位技师、两张床）。
- 它是资源占位模型的压力测试：**一次 `tryBook` 变成 N 次，且要么全成要么全退**。

## 1.2 场景 → 模式矩阵

| 场景 | 付款时点 | 履约方式 | 预约单 | 工单 | 资源 |
|---|---|---|---|---|---|
| S1 线上预约 | 先付 / 到店付 | `STORE_VERIFY` | ✅ | ✅ | 技师/床位 |
| S2 直客 | 服务后 | `STORE_VERIFY` | ❌ | ✅ | 技师/床位 |
| S3 电话预约 | 服务后 | `STORE_VERIFY` | ✅（**无订单**） | ✅ | 技师/床位 |
| S4 卡客 | **早已付** | `STORE_VERIFY` | 可有可无 | ✅ | 技师/床位 |
| S5 疗程分次 | **早已付** | `STORE_VERIFY` | 每次一张 | 每次一张 | 技师/床位 |
| S6 上门 | 先付居多 | `APPOINTMENT` | ✅ | ✅ | 技师 |
| S7 团体 | 先付 | `STORE_VERIFY` | ✅ | **一张多行** | **多资源** |

**与餐饮的对照**：餐饮的台账（`fnb_check`）在美业分裂成了**两个对象** ——
**预约单**（约定，可能没有订单）与**工单**（执行）。
硬凑成一个的话，"约了但没来"和"来了但没约"这两种最常见的情况都表达不了。

## 1.3 从场景里抽出来的七个动作

| 动作 | 一句话 | 分叉点 |
|---|---|---|
| **改约** | 换时间/换技师 | 钱收没收（要不要退改） |
| **取消** | 客人主动取消 | 提前多久（是否扣违约） |
| **爽约** | 到点没来 | **是否扣次/扣定金** —— 规则问题不是技术问题 |
| **换人** | 服务中换技师 | **提成归属随之变更** |
| **加项** | 做着做着加一个项目 | 新订单，不改旧订单 |
| **退卡 / 转卡** | 剩余次数退钱或转让 | 已耗次数按**原价**还是**卡价**核销 |
| **补差 / 升级** | 从基础款升级到高级款 | 补款走新订单行 |

---

# 二 · 服务项目建模（基础 + 服务）

**一个项目都不新建成"服务实体"。** 项目就是基座的 `prd_goods` / `prd_sku`。
美业特有的东西用下面八种方式表达。

## 2.1 服务时长与占用格数
「面部护理 60 分钟」。

- **落法**：`bty_goods_ext.duration_min` + 占位时按 `ceil(duration / slot_granularity)` **连占 N 个时段格**。
- **为什么不把时长做成时段本身**：时段粒度是门店的排班配置（15/30 分钟一格），
  项目时长是商品属性。合并的话，改一次粒度所有项目都要重配。
- **连占 N 格必须原子**：与 S7 的多资源同理，**要么全成要么全退**（§4.2）。

## 2.2 指定技师加价
「总监 +50」。

- **落法**：加价是**一个真实 SKU**（"指定总监"），下单时作为**独立订单行**，
  从属关系记在 `bty_order_item_ext.parent_item_id`。
- **与餐饮的加料是同一手法、同一理由**：不动基座 `ord_item` 的金额结构，
  金额、退款、结算、发票、积分五处一行不改。
- **为什么不做成"技师等级价格系数"**：那会造出第二个价格真源，
  而它与 `prd_store_price` 在做活动、打折、退款时必然分叉。

## 2.3 耗材与附加项
「加一次精油 +30」——同 2.2，独立订单行。不做"服务内耗材成本"（那是进销存，基座 `shop-inventory`）。

## 2.4 疗程包 / 次卡
- **卖**：基座组合商品 `prd_sku_bundle`（`verify_mode = 逐项核销`）+ 基座会员资产 `mbr_card`；
- **用**：每次到店生成一张新订单，用次卡抵扣（§4.6）。
- **美业包不建卡表、不算余次** —— 那是基座 F2。

## 2.5 谁能做这个项目
基座 `mch_staff_skill(staff_no, goods_no)` 已有，直接用。
选技师时先按技能过滤，再看排班，最后看时段余量。**三个条件缺一都会让客人约到做不了的师傅。**

## 2.6 上门的路途缓冲
`bty_goods_ext.buffer_before_min` / `buffer_after_min`：占位时前后各多占几格。
**缓冲进的是时段占用，不是订单时间** —— 客人看到的仍是 14:00，师傅的表上是 13:30–15:30。

## 2.7 客史档案
会员在基座（`mbr_member`）。客史（到店记录、偏好、过敏史、护理照片）是**会员的美业附属**：`bty_customer_profile`。
- **不新建会员表** —— 这是"一套商户系统"最值钱的一条。

## 2.8 预约购物车？—— 不需要
餐饮必须有台账维度购物车（一桌多人同时点）。
美业**一次预约通常就是一到三个项目、一个人选**，用不上共享购物车。
- **落法**：预约单本身携带项目行（`bty_appointment_item`），提交即成约。
- 一家人分别约不同项目 = **多张预约单**，各自占位。合并结账在 §4.7 解决。
> 这是与餐饮的第一处实质性结构差异，P3 对比时记为 `X`。

---

# 三 · 工作流主干

```
                       ┌────────── 基座（一行不改） ──────────┐
项目维护 ──────────────>  prd_goods / prd_sku / prd_store_price
（基座入口）                        │
                                    │
排班 ──> mch_schedule_rule ──生成──> mch_appointment_slot（基座）
                                    │
线上/线下预约 ──占位──> bty_appointment ──tryBook──> slot.booked++
        │                   │
        │            （可有订单，也可以没有）
        │                   ▼
        │              基座订单 WAIT_PAY / WAIT_OFFLINE_PAY
        ▼
     到店核销 ──> bty_work_order（工单）──> 服务中 ──> 完成
                        │                              │
                        │                              ▼
                        │                    核销 / 耗卡（基座 F2）
                        ▼                              │
                  服务单打印（基座 I）                   ▼
                                            结账 bty_payment ──> markPaid（基座）
                                                       │
                                                       ▼
                                            提成计提 bty_commission_record
```

**三处必须记住的**：
1. **预约单可以没有订单**（S3 电话预约）。占位由预约单持有，不是由订单持有。
2. **一张卖卡订单 ≠ 十次服务**。每次服务是**新订单**，实收 0，次卡抵扣。
3. **金额只有一个真源：基座订单。** 预约单、工单都不存金额（与餐饮 §9.3 同一条）。

---

# 四 · 各阶段详细

## 4.1 项目与排班维护

| 动作 | 入口 | 落表 |
|---|---|---|
| 建项目、改名、改价、上下架 | **基座**（`/biz/goods/**`） | `prd_goods` / `prd_sku` / `prd_store_price` |
| 配时长、缓冲、可上门、需要哪类资源 | **美业**（`/biz/x/beauty/project/{no}/ext`） | `bty_goods_ext` |
| 配谁能做（技能） | **基座**（`STAFF_PROFILE` 能力） | `mch_staff_skill` |
| 建技师档案、等级 | **基座** | `mch_staff` |
| 排班规则 | **基座**（`STAFF_SCHEDULE`） | `mch_schedule_rule` → 生成 `mch_appointment_slot` |
| 临时调班、请假 | **美业**（`/biz/x/beauty/schedule/adjust`） | 改基座 slot 的 `status`，**留痕在 `bty_schedule_log`** |

> 与餐饮同一条判据：**「建一个项目」走基座商品入口，「给这个项目配时长」走美业入口。**
> 界面上是同一页两个区块。

## 4.2 预约（线上 c-app / 线下前台）

```
POST /mp/x/beauty/appointment        （线上）
POST /biz/x/beauty/appointment       （前台代客，可无会员账号）
  │
  ├─ 1. 校验能力 RESOURCE_BOOKING / STAFF_SCHEDULE
  ├─ 2. 按技能 ∩ 排班 ∩ 余量 求可选时段
  ├─ 3. 计算要占的格数：ceil(duration/粒度) + 前后缓冲
  ├─ 4. **原子占位**：N 格 × M 资源，全成才算成功，任一失败全部回退
  ├─ 5. 建 bty_appointment（PENDING）
  ├─ 6. 需要付款/定金 → 建基座订单；到店付 → 不建单
  └─ 7. 预约确认单打印/推送
```

**第 4 步是本包最难的一处。** 基座 `AppointmentSlotPort.tryBook` 一次占一格，
连占与多资源要在美业包里循环调用并自己回退。
**回退必须幂等**（复用基座「先标记后释放」的手法），否则失败一半会永久漏占名额。

**改约**：先占新的、再放旧的（**先占后放**，与餐饮转台同理）。占不上则整体失败，不能出现两头都空着而约在半路。

**爽约**：定时任务扫过点未到的预约（`JobHandler`），置 `NO_SHOW`、释放资源、按门店规则决定是否扣次/扣定金。
**规则来自 `bty_store_config`，不写死在代码里。**

## 4.3 下单（三条链）

| 链 | 场景 | 订单状态 | 说明 |
|---|---|---|---|
| **先付** | S1 线上付、S6 上门 | `WAIT_PAY` → `PAID` | 与零售完全相同 |
| **到店付** | S2 直客、S3 电话约 | `WAIT_OFFLINE_PAY` → 结账 `markPaid` | 复用基座 D3 |
| **资产付**（耗卡/储值） | S4、S5 | 见 §4.6 | **实收 0，但订单已付** |

**下单时机**：先付链在预约时下单；后两条在**到店开工单时**下单。
理由与餐饮相反：餐饮"下达即下单"是因为菜要立刻做；美业到店前不下单，是为了**不产生幽灵订单**（§7.1）。

## 4.4 到店与开工单

```
POST /biz/x/beauty/work-order        （前台）
  ├─ 有预约：核销预约单 → 生成工单，继承技师/项目/时段
  ├─ 无预约（walk-in）：现选项目、现派技师、现占资源
  ├─ 无订单：此时建订单（到店付 / 资产付）
  └─ 打印服务单（scene = SERVICE_START）
```

工单一旦开出，**资源占用从"预约占位"转为"工单占用"** —— 两者不能同时计数，否则一个技师看起来被占了两次。

## 4.5 服务中

| 动作 | 落法 |
|---|---|
| 开始 / 完成 | `bty_work_order.status` 流转 + 时间戳 |
| **换人** | 改 `bty_wo_item.staff_no` + 写 `bty_wo_staff_log`；**提成归属随之变更** |
| **加项** | 新订单 + 新工单行；**不改已有订单** |
| 超时 | 占用超出预定时长 → 告警，后续预约受影响时前台可主动改约 |

## 4.6 完成与核销 / 耗卡 —— 本册最关键的一节

**耗卡算不算一次支付？答：算。**

理由：不算的话，一次消费在订单侧就没有"已付"这个状态，
于是结算、报表、售后、评价四条链路都要为耗卡写第二条路 —— 而它们本来只有一条。

**落法**（全部用基座既有字段）：

```
ord_item.price          = 项目原价（快照）        ← 提成与业绩的基数
ord_sub_order.discount* = 次卡/储值抵扣额          ← 见下方 ⚠️
ord_order.payAmount     = 0（全额抵扣）或差额
ord_order.payChannel    = TIMES_CARD / STORED_VALUE   ← sys_pay_channel 加行数据，不是加代码
```

于是 `markPaid` 照常调用，**它不需要认识次卡** —— 与线下支付、微信支付是同一个方法的第三个调用方。

**收入不重复计**：钱在**卖卡那张订单**上已经确认过；耗卡这张订单实收 0。
**提成有基数**：按 `ord_item.price`（原价）算，不是按实收 0 算。
> 这两句必须同时成立。只顾其一的做法是：耗卡订单记原价 → 收入翻倍；耗卡订单记 0 → 技师这个月业绩为 0。

⚠️ **一条对基座的依赖**：基座 `ord_sub_order` 现有 `discountPlatform` / `discountMerchant` 两列，
次卡抵扣**两者都不是**（它既不是平台补贴也不是商家让利，是**预收款核销**）。
需要基座加一列 `assetDeductMinor`（与既有 `pointsDeductMinor` 同构）。
**这是美业包 `requiredCapabilities()` 里的一条硬依赖，基座没做就拒绝启动。**

## 4.7 结账

```
发起结账 → 汇总该工单（或该客人今日多张工单）名下的订单
        → 应收 = Σ 基座订单 payAmount（美业包不自己算）
        → 收款：可多笔（储值 + 微信 + 现金），每笔一条 bty_payment
        → 足额 → 逐单 confirmOfflinePaid / markPaid
        → 工单 SETTLED → 打结账小票 + 次卡余次条
        → 计提提成
```

**一家人合并结账**（§2.8 的遗留）：勾选多张工单一起收款，**订单仍各是各的**。
与餐饮的拆单是同一条硬限制：**基座一张订单只能整单支付，不做事后劈账。**

## 4.8 打印
见 §十三。

---

# 五 · 七个动作的精确逻辑

分叉判据是**二维**的：**钱收没收 × 次数扣没扣**。
（餐饮只有一维 —— 这是 P3 对比时最该记下的一处结构差异。）

| 动作 | 未付 · 未扣次 | 已付 · 未扣次 | 已付 · 已扣次 |
|---|---|---|---|
| **改约** | 改预约单，重占位 | 同左，订单不动 | 不适用（已服务） |
| **取消** | 删预约单，释放 | 基座售后退款 | 不适用 |
| **爽约** | 置 `NO_SHOW`，释放 | 按规则：退 / 不退 / 扣定金 | **按规则决定扣不扣次** |
| **换人** | 改预约单 | 改工单行 + 留痕 | 改工单行 + **提成重算** |
| **加项** | 加预约行 | **新订单**（不改旧单） | **新订单** |
| **退卡** | — | 基座售后 + 资产冲正 | **已耗次数按原价核销**，余次退钱 |
| **补差** | 改预约行 | **新订单行** | **新订单行** |

**两条写死的**：
1. **永远不改已支付订单的金额。** 加项、补差一律新订单。
2. **次数的扣与还只走基座会员资产流水**（`mbr_asset_txn`），美业包不许自己算余次。

## 5.1 退卡的原价核销
买 10 次 ¥1000（单次 ¥100），已做 3 次，卡面价 ¥120。
- 按卡价核销：退 ¥700 —— 客人赚了；
- **按原价核销**：3 × ¥120 = ¥360，退 ¥640。
**必须在 PRD 里定死用哪个口径**，两种都合法，但不能由代码默认决定（§十六）。

---

# 六 · 入口与调用方向

## 6.1 方向
与餐饮完全一致：**美业的请求进美业的门，由美业包编排、调基座能力；基座回头只走事件。**
判据同一条：**看这个动作的主语是不是美业对象。**

| 请求 | 入口 | 主语 |
|---|---|---|
| 建项目、改价、上下架 | **基座** | 商品 |
| 配时长/缓冲/可上门 | **美业** | 项目的美业附属 |
| 建技师、配技能、排班规则 | **基座** | 员工 / 排班（都是基座能力） |
| 临时调班、请假 | **美业** | 排班的美业动作（要留痕与影响面提示） |
| 预约、改约、取消、爽约 | **美业** | 预约单 |
| 开工单、换人、加项、完成 | **美业** | 工单 |
| 支付回调 | **基座** | 订单（外部系统打进来，只能一个门） |
| 售后退款、退卡 | **基座** | 订单 / 会员资产 |
| 充值、办卡 | **基座** | 会员资产 |
| 结账收款 | **美业** | 工单（编排多单收款） |

## 6.2 读写不对称（沿用上一轮的结论）
**写按主语分门；读允许美业提供聚合端点。**
例：`GET /biz/x/beauty/project/list` 一次返回基座商品 + `bty_goods_ext` + 技能 + 今日可约余量，
免掉前端三次请求自己拼。**写仍各归各的主语。**

## 6.3 端点清单

```
# 顾客侧 /mp/x/beauty/**
GET    /project/list                     可约项目（含时长、起价、可选技师数）
GET    /staff?goodsNo=                   能做这个项目的技师
GET    /slot?goodsNo=&staffNo=&date=     可约时段（已扣时长与缓冲）
POST   /appointment                      提交预约（含是否线上付）
GET    /appointment/{no}                 预约详情
POST   /appointment/{no}/change          改约
POST   /appointment/{no}/cancel          取消
GET    /card/list                        我的卡与余次（**基座数据，美业只做视图**）
GET    /work-order/{no}                  服务进度

# 商家侧 /biz/x/beauty/**
GET    /schedule/day?date=               今日排程（技师 × 时段 × 预约/工单）
POST   /schedule/adjust                  临时调班/请假（留痕）
POST   /appointment                      前台代客建约（可无会员账号）
POST   /appointment/{no}/checkin         到店核销 → 生成工单
POST   /work-order                       直客开单（walk-in）
POST   /work-order/{no}/start|finish     开始 / 完成
POST   /work-order/{no}/item             加项
PUT    /work-order/{no}/item/{id}/staff  换人（留痕）
POST   /work-order/{no}/verify           核销 / 耗卡
POST   /checkout/begin|pay|close         结账（可多工单合并收款）
PUT    /project/{goodsNo}/ext            配时长/缓冲/可上门
GET    /customer/{memberNo}/profile      客史档案
GET    /commission/list                  提成账

# 运营侧 /ops/x/beauty/**
GET/PUT /store/{storeNo}/config          门店美业配置（爽约规则、提前预约天数…）
GET/PUT /template/**                     打印模板与路由默认值
```

---

# 七 · 数据模型：三类表

分类规矩与餐饮完全相同（判据：**脱离基座对象还有没有意义**）。

| 类 | 美业的例子 |
|---|---|
| **A · 专属** | 预约单、工单、工单行、换人留痕、调班留痕、收款流水、提成规则与记录 |
| **B · 附属** | 项目的时长与缓冲、订单的预约/工单归属、订单行的技师与耗卡、会员的客史、门店的美业配置 |
| **C · 纯基座** | 订单、订单行、商品、价格、时段、员工与技能、会员资产、售后、打印、结算 |

## 7.1 一处必须单独论证的：预约单为什么是一等对象

餐饮开台即台账，台账下必有订单。美业不是：**S3 电话预约时钱一分没收、单一张没有**。

两条路：

| 方案 | 做法 | 结论 |
|---|---|---|
| **A. 预约单持有占位**（推荐） | `bty_appointment` 记 `slot_no`，可有订单也可以没有 | ✅ **采用** |
| B. 预约即下单 | 一律建 `WAIT_OFFLINE_PAY` 订单，复用基座 `appointment_slot_no` 与释放机制 | ❌ 产生大量**幽灵订单**：电话约十个来三个，订单量、转化率、GMV 全被污染。**报表口径的污染是不可逆的** |

采用 A 的代价：**"没有订单的占位"在基座看不见**，超卖的防线由美业包守。
对策：占位一律走基座 `tryBook`（计数在基座），美业包只持有"谁占的"。
**计数的真源仍然只有一个。**

## 7.2 附属表六条规矩
与餐饮 §7.2 逐条相同，不重复。特别重申第 2 条：
**只存基座没有的列，绝不复制基座已有的值** —— 项目名、单价、余次一律回基座取。

---

# 八 · 数据库（`db/industry/beauty`，历史表 `bty_flyway_history`）

表全部 `bty_` 前缀。**金额只出现在 `bty_payment`**；**余次一列都不存**（在基座 `mbr_card`）。

## 8.1 A 类 · 美业专属表

```sql
-- ── 预约单 ────────────────────────────────────────────
CREATE TABLE bty_appointment (
  appt_no      VARCHAR(32) NOT NULL PRIMARY KEY,
  store_no     VARCHAR(32) NOT NULL,
  member_no    VARCHAR(32) NULL,          -- ⚠️ 可空：电话预约的客人不在系统里
  guest_name   VARCHAR(32) NULL,          -- 无会员时的姓名
  guest_phone  VARCHAR(32) NULL,          -- 无会员时的手机（脱敏存储，复用基座 Masks）
  order_no     VARCHAR(32) NULL,          -- ⚠️ 可空：到店付的预约此时还没有订单
  status       VARCHAR(16) NOT NULL,      -- PENDING/CONFIRMED/CHECKED_IN/NO_SHOW/CANCELLED
  start_at     DATETIME NOT NULL,
  end_at       DATETIME NOT NULL,
  source       VARCHAR(16) NOT NULL,      -- ONLINE/PHONE/WALK_IN/STAFF
  created_by   VARCHAR(64) NOT NULL,
  created_at   DATETIME NOT NULL,
  KEY idx_bty_appt_store_time (store_no, start_at, status),
  KEY idx_bty_appt_member (member_no)
);

-- 预约项目行
CREATE TABLE bty_appointment_item (
  line_no    VARCHAR(32) NOT NULL PRIMARY KEY,
  appt_no    VARCHAR(32) NOT NULL,
  goods_no   VARCHAR(32) NOT NULL,
  sku_no     VARCHAR(32) NOT NULL,
  staff_no   VARCHAR(32) NULL,            -- 不指定则为空
  addon_of   VARCHAR(32) NULL,            -- 指定技师加价/耗材，挂主行
  KEY idx_bty_appt_item (appt_no)
);

-- 占位明细：一次预约可能占 N 格 × M 资源（§4.2），回退要按这张表逐条还
CREATE TABLE bty_appointment_slot (
  appt_no      VARCHAR(32) NOT NULL,
  slot_no      VARCHAR(32) NOT NULL,      -- → 基座 mch_appointment_slot
  resource_no  VARCHAR(32) NOT NULL,      -- → 基座 mch_resource
  released_at  DATETIME NULL,             -- 先标记后释放，保证幂等
  PRIMARY KEY (appt_no, slot_no, resource_no)
);

-- ── 工单 ──────────────────────────────────────────────
CREATE TABLE bty_work_order (
  wo_no        VARCHAR(32) NOT NULL PRIMARY KEY,
  store_no     VARCHAR(32) NOT NULL,
  appt_no      VARCHAR(32) NULL,          -- walk-in 为空
  member_no    VARCHAR(32) NULL,
  order_no     VARCHAR(32) NULL,          -- 到店才建单时，开工单后回填
  status       VARCHAR(16) NOT NULL,      -- CREATED/SERVING/FINISHED/SETTLED/CANCELLED
  started_at   DATETIME NULL,
  finished_at  DATETIME NULL,
  settled_at   DATETIME NULL,
  remark       VARCHAR(255) NULL,
  KEY idx_bty_wo_store_status (store_no, status),
  KEY idx_bty_wo_order (order_no)
);

CREATE TABLE bty_wo_item (
  wo_item_no    VARCHAR(32) NOT NULL PRIMARY KEY,
  wo_no         VARCHAR(32) NOT NULL,
  order_item_id BIGINT NULL,              -- → 基座 ord_item.id（金额与名称的唯一来源）
  goods_no      VARCHAR(32) NOT NULL,
  staff_no      VARCHAR(32) NULL,         -- 归属技师，提成按它算
  resource_no   VARCHAR(32) NULL,         -- 床位/工位
  card_no       VARCHAR(32) NULL,         -- 耗卡时记哪张卡（**余次仍在基座**）
  status        VARCHAR(16) NOT NULL,     -- PENDING/SERVING/DONE/VOIDED
  KEY idx_bty_wo_item (wo_no)
);

CREATE TABLE bty_wo_staff_log (
  log_no      VARCHAR(32) NOT NULL PRIMARY KEY,
  wo_item_no  VARCHAR(32) NOT NULL,
  from_staff  VARCHAR(32) NULL,
  to_staff    VARCHAR(32) NOT NULL,
  reason      VARCHAR(128) NULL,
  operator    VARCHAR(64) NOT NULL,
  created_at  DATETIME NOT NULL
);

-- ── 调班留痕 ──────────────────────────────────────────
CREATE TABLE bty_schedule_log (
  log_no      VARCHAR(32) NOT NULL PRIMARY KEY,
  store_no    VARCHAR(32) NOT NULL,
  staff_no    VARCHAR(32) NOT NULL,
  slot_no     VARCHAR(32) NULL,
  action      VARCHAR(16) NOT NULL,       -- LEAVE/ADD/CLOSE/REOPEN
  reason      VARCHAR(128) NULL,
  affected    INT NOT NULL DEFAULT 0,     -- 影响了几个已有预约（供前台处理）
  operator    VARCHAR(64) NOT NULL,
  created_at  DATETIME NOT NULL
);

-- ── 收款流水（美业包唯一存金额的表）──────────────────
CREATE TABLE bty_payment (
  payment_no  VARCHAR(32) NOT NULL PRIMARY KEY,
  wo_no       VARCHAR(32) NOT NULL,
  order_no    VARCHAR(32) NULL,
  pay_channel VARCHAR(24) NOT NULL,       -- → 基座 sys_pay_channel（含 TIMES_CARD/STORED_VALUE）
  amount      BIGINT NOT NULL,
  trade_no    VARCHAR(64) NULL,
  status      VARCHAR(16) NOT NULL,
  operator    VARCHAR(64) NOT NULL,
  created_at  DATETIME NOT NULL,
  KEY idx_bty_payment_wo (wo_no, status)
);

-- ── 提成 ──────────────────────────────────────────────
CREATE TABLE bty_commission_rule (
  rule_no   VARCHAR(32) NOT NULL PRIMARY KEY,
  store_no  VARCHAR(32) NOT NULL,
  scope     VARCHAR(16) NOT NULL,         -- STORE/STAFF/GOODS/LEVEL
  target_no VARCHAR(32) NULL,
  mode      VARCHAR(16) NOT NULL,         -- RATE(比例) / FIXED(定额)
  value     BIGINT NOT NULL,
  priority  INT NOT NULL DEFAULT 0        -- 从具体到一般，第一条命中生效
);

CREATE TABLE bty_commission_record (
  rec_no      VARCHAR(32) NOT NULL PRIMARY KEY,
  wo_item_no  VARCHAR(32) NOT NULL,
  staff_no    VARCHAR(32) NOT NULL,
  base_amount BIGINT NOT NULL,            -- ⚠️ 分摊原价，不是实收（§4.6）
  amount      BIGINT NOT NULL,
  status      VARCHAR(16) NOT NULL,       -- ACCRUED/REVERSED/PAID
  created_at  DATETIME NOT NULL,
  KEY idx_bty_comm_staff (staff_no, status)
);
```

## 8.2 B 类 · 基座表的美业附属表

```sql
-- 商品 ← 基座 prd_goods（按门店：时长与缓冲是门店的经营选择）
CREATE TABLE bty_goods_ext (
  store_no          VARCHAR(32) NOT NULL,
  goods_no          VARCHAR(32) NOT NULL,
  duration_min      INT NOT NULL DEFAULT 60,
  buffer_before_min INT NOT NULL DEFAULT 0,
  buffer_after_min  INT NOT NULL DEFAULT 0,
  resource_type     VARCHAR(16) NULL,     -- STAFF / SEAT / ROOM，为空 = 只占技师
  home_service      TINYINT NOT NULL DEFAULT 0,
  need_staff_pick   TINYINT NOT NULL DEFAULT 1,   -- 是否必须选技师
  PRIMARY KEY (store_no, goods_no)
);
-- 缺行 = 60 分钟、无缓冲、只占技师、可不指定。存量门店一行都没有照样能跑。

-- 订单 ← 基座 ord_order
CREATE TABLE bty_order_ext (
  order_no VARCHAR(32) NOT NULL PRIMARY KEY,
  appt_no  VARCHAR(32) NULL,
  wo_no    VARCHAR(32) NULL,
  KEY idx_bty_order_ext_wo (wo_no)
);

-- 订单行 ← 基座 ord_item
CREATE TABLE bty_order_item_ext (
  order_item_id  BIGINT NOT NULL PRIMARY KEY,
  order_no       VARCHAR(32) NOT NULL,
  parent_item_id BIGINT NULL,             -- 指定技师加价/耗材挂主行
  staff_no       VARCHAR(32) NULL,        -- 提成归属
  card_no        VARCHAR(32) NULL,        -- 本行由哪张卡抵扣
  times_used     INT NULL                 -- 本行耗了几次（**余次仍在基座**）
);

-- 会员 ← 基座 mbr_member
CREATE TABLE bty_customer_profile (
  member_no    VARCHAR(32) NOT NULL,
  entity_no    VARCHAR(32) NOT NULL,
  skin_type    VARCHAR(32) NULL,
  allergy      VARCHAR(255) NULL,
  preference   VARCHAR(255) NULL,
  photos       JSON NULL,                 -- 媒体键，走基座媒体域
  last_visit_at DATETIME NULL,
  PRIMARY KEY (member_no, entity_no)
);

-- 门店 ← 基座 mch_store
CREATE TABLE bty_store_config (
  store_no              VARCHAR(32) NOT NULL PRIMARY KEY,
  slot_granularity_min  INT NOT NULL DEFAULT 30,
  book_ahead_days       INT NOT NULL DEFAULT 30,
  cancel_free_hours     INT NOT NULL DEFAULT 4,     -- 提前多久取消不罚
  no_show_policy        VARCHAR(16) NOT NULL DEFAULT 'NONE',  -- NONE/DEDUCT_TIMES/FORFEIT_DEPOSIT
  refund_card_basis     VARCHAR(16) NOT NULL DEFAULT 'LIST',  -- 退卡核销口径：LIST(原价)/CARD(卡价)，见 §5.1
  allow_walk_in         TINYINT NOT NULL DEFAULT 1
);
```

## 8.3 C 类 · 直接用的基座表

| 表 | 美业怎么用 |
|---|---|
| `ord_order` / `ord_sub_order` / `ord_item` | 钱、状态、行与金额的**唯一**真源 |
| `prd_goods` / `prd_sku` / `prd_store_price` / `prd_sku_bundle` | 项目与疗程包 |
| `mch_resource` / `mch_appointment_slot` / `mch_schedule_rule` | 资源、时段、排班 |
| `mch_staff` / `mch_staff_skill` | 技师档案与技能 |
| `mbr_member` / `mbr_asset_account` / `mbr_asset_txn` / `mbr_card` | 会员与卡、余次、储值 |
| `ord_after_sale` | 退款、退卡 |
| `prn_*` / `sys_outbox` / `sys_idem_record` | 打印、事件、幂等 |

## 8.4 一次「卡客到店」到底写了哪些表

```
1. 基座 ord_order/ord_sub_order/ord_item     ← CoreOrderApi.place（原价快照）
2. 附属 bty_order_ext / bty_order_item_ext   ← 挂工单、技师、卡号
3. 专属 bty_work_order / bty_wo_item         ← 工单
4. 基座 mbr_asset_txn（扣次）                 ← CoreMemberApi（★不许自己算）
5. 基座 markPaid(payChannel=TIMES_CARD)      ← 实收 0，但订单已付
6. 专属 bty_commission_record                ← 基数 = 原价，不是实收
7. 基座 prn_job                              ← 服务单/余次条（事务外）
```
1–6 同一个事务。**7 在事务外**。

---

# 九 · 对象

## 9.1 领域对象

| 对象 | 说明 |
|---|---|
| `Appointment` | 预约单聚合根。占位、改约、取消、核销四个动作的入口 |
| `SlotHold` | 一次占位的 N×M 明细，**回退的单位** |
| `WorkOrder` | 工单聚合根。开始/换人/加项/完成/结账 |
| `DaySchedule` | 今日排程读模型（技师 × 时段 × 预约/工单/请假）|
| `ProjectView` | 项目读模型：基座商品 + `bty_goods_ext` + 技能 + 余量（§6.2 的聚合读）|
| `CheckoutBill` | 结账单，**从基座订单聚合，不落库** |
| `CommissionEntry` | 一条提成计提 |

## 9.2 状态机（两个，本包私有）

```java
// 预约单
PENDING    → CONFIRMED, CANCELLED
CONFIRMED  → CHECKED_IN, NO_SHOW, CANCELLED
CHECKED_IN → (终，后续在工单上)
NO_SHOW    → (终)
CANCELLED  → (终)

// 工单
CREATED  → SERVING, CANCELLED
SERVING  → FINISHED, CANCELLED
FINISHED → SETTLED
SETTLED  → (终)
CANCELLED→ (终)
```
写法照基座 `OrderStateMachine`：**唯一一处允许判断"能不能变"的地方。**

## 9.3 两条写死的规矩

1. **预约单与工单都不存金额**，应收永远由基座订单现算（与餐饮 §9.3 同一条）。
2. **余次一列都不存。** `bty_wo_item.card_no` 只记"用了哪张卡"，
   剩几次永远问基座 `mbr_card`。存一份就会在退卡、过期、转让三处分叉，且两边都不报错。

---

# 十 · 服务（接口签名）

```java
public interface ProjectService {                 // 项目的美业附属（不代理基座商品）
    List<ProjectView> list(String storeNo, ProjectQuery q);   // 聚合读
    void saveExt(String storeNo, String goodsNo, GoodsExtCmd cmd);
}

public interface ScheduleService {
    DayScheduleVO day(String storeNo, LocalDate date);
    List<SlotVO> availableSlots(SlotQuery q);      // 技能 ∩ 排班 ∩ 余量 ∩ 时长与缓冲
    void adjust(ScheduleAdjustCmd cmd);            // 请假/加班，返回受影响预约数
}

public interface AppointmentService {
    ApptVO create(CreateApptCmd cmd);              // 原子占位 N×M，全成才算成功
    ApptVO change(String apptNo, ChangeApptCmd cmd);   // 先占后放
    void cancel(String apptNo, String reason, String operator);
    void markNoShow(String apptNo);                // 由 JobHandler 调
    WorkOrderVO checkIn(String apptNo, String operator);   // 核销 → 生成工单
}

public interface WorkOrderService {
    WorkOrderVO open(OpenWoCmd cmd);               // walk-in 直接开单
    void start(String woNo, String operator);
    void finish(String woNo, String operator);
    void changeStaff(String woItemNo, String toStaffNo, String reason, String operator);
    WorkOrderVO addItem(String woNo, AddItemCmd cmd);      // 加项 = 新订单
    void verify(String woNo, VerifyCmd cmd);       // 核销 / 耗卡（走基座资产）
    void cancel(String woNo, String reason, String operator);
}

public interface BeautyCheckoutService {
    CheckoutBill bill(List<String> woNos);         // 可多工单合并
    PaymentVO pay(String woNo, PayCmd cmd);        // 一笔收款，可多次、可混合
    void close(List<String> woNos, String operator);   // 足额 → 逐单 markPaid → 计提提成
}

public interface CommissionService {
    List<CommissionEntry> accrue(String woNo);     // 基数 = 原价
    void reverse(String orderNo, String reason);   // 退款倒扣，挂 onRefunded 事件
    PageData<CommissionVO> list(CommissionQuery q);
}

public interface CustomerProfileService {
    ProfileVO get(String memberNo);
    void save(String memberNo, ProfileCmd cmd);
}
```

---

# 十一 · 与基座的交互点

**调基座（出）**

| 时机 | 调用 |
|---|---|
| 求可约时段 / 占位 / 释放 | `CoreSlotApi.tryBook/release` + `CoreResourceApi` |
| 取技师与技能 | `CoreStaffApi`（`STAFF_PROFILE` 能力） |
| 下单 | `CoreOrderApi.place` |
| 结账 | `CoreOrderApi.confirmOfflinePaid` / `markPaid` |
| 耗卡 / 储值扣减 | `CoreMemberApi.deductAsset(...)` — **不许自己算余次** |
| 退款 / 退卡 | `CoreAfterSaleApi` + `CoreMemberApi.reverseAsset` |
| 打印 | `CorePrintApi.submit` |
| 能力判定 | `CoreCapabilityApi.enabled` |

**被基座调（入）**

| 扩展点 | 用途 |
|---|---|
| `IndustryPackage` | 声明 `BEAUTY`、能力、`requiredCapabilities`、迁移位置 |
| `OrderLifecycleListener.onPaid` | 线上付 → 预约置 `CONFIRMED`、发确认 |
| `.onRefunded` | **提成倒扣**（只计提不管退款，第一次退单就多发一笔而没人发现） |
| `.onCancelled` | 释放占位 |
| `PrintPayloadProvider` | `APPT_CONFIRM` / `SERVICE_START` / `CHECKOUT` / `CARD_BALANCE` |
| `ResourceTypeProvider(STAFF/SEAT/ROOM)` | 资源语义 |
| `CheckoutContributor` | 下单前校验（技能、排班、卡是否可用于该项目） |
| `JobHandler` | 爽约扫描、次日排程生成校验、卡到期提醒 |

**对基座的四条硬依赖**（`requiredCapabilities()`，缺一拒绝启动）：
1. `RESOURCE_BOOKING`（资源级时段）；
2. `STAFF_PROFILE` + `STAFF_SCHEDULE`；
3. `TIMES_CARD` + `STORED_VALUE`（会员资产）；
4. **`ord_sub_order.assetDeductMinor` 列**（§4.6 的预收款核销口径）。

---

# 十二 · 权限码

权限码段 `beauty:*`（沿用基座「读写分开」的规矩）：

| 码 | 用途 |
|---|---|
| `beauty:schedule:read` / `beauty:schedule:adjust` | 看排程 / 调班请假 |
| `beauty:appt:read` / `beauty:appt:write` / `beauty:appt:cancel` | 预约查看 / 建改 / 取消 |
| `beauty:wo:read` / `beauty:wo:operate` | 工单查看 / 开始完成 |
| `beauty:wo:staff-change` | **换人单独一个码** —— 它改的是提成归属 |
| `beauty:verify:card` | **耗卡单独一个码** —— 内控要害，等同动钱 |
| `beauty:checkout:pay` / `beauty:checkout:discount` | 收款 / 折扣减免 |
| `beauty:customer:read` / `beauty:customer:write` | 客史（含过敏史，属敏感信息） |
| `beauty:commission:read` / `beauty:commission:settle` | 提成查看 / 发放 |
| `beauty:project:ext` | 配项目时长与缓冲 |

**必须登记进 `scripts/perm-endpoint-map.mjs` 并重跑生成器**，否则 pre-push 矩阵测试会红。

---

# 十三 · 打印场景

| scene | 触发 | 内容 |
|---|---|---|
| `APPT_CONFIRM` | 预约成功 | 时间、项目、技师、门店地址、改约须知 |
| `SERVICE_START` | 开工单 | 顾客、项目、技师、床位、**过敏史提醒** |
| `CHECKOUT` | 结账 | 逐行原价、卡抵扣、实收、多笔明细 |
| `CARD_BALANCE` | 耗卡后 | **本次耗几次、还剩几次、有效期** |
| `HANDOVER` | 交班 | 当日工单、收款、提成汇总 |

**`CARD_BALANCE` 是美业特有的一张**：卡客最常见的纠纷就是"我还剩几次"，
一张当场出的余次条比事后查系统有用得多。**余次数字必须取自基座 `mbr_card`。**

---

# 十四 · 本包明确不做

会员与标签（基座）· 储值与次卡的账（基座 F2）· 优惠券与活动（基座）· 订单与退款（基座）·
结算分账（基座）· 排队叫号（**基座候选能力，与餐饮共用**）· 员工排班规则引擎（基座 E4）·
进销存与耗材成本（基座 `shop-inventory`）· 上门服务的路径规划与派单（那是将来的家政包）·
美业的连锁跨店通兑（§十六 待定）。

---

# 十五 · 测试策略与闸门

**单元**
- 预约状态机与工单状态机全图（照基座 `OrderStateMachineTest` 的写法）；
- **原子占位**：N 格 × M 资源，第 k 个失败时前 k−1 个必须全部还回去；
- **改约先占后放**：新时段占不上时，旧时段必须仍被占着；
- 时长与缓冲换算：60 分钟项目 + 30 分钟粒度 = 占 2 格；带 15 分钟缓冲 = 占 3 格；
- 耗卡金额链：`price=原价`、`assetDeduct=抵扣`、`payAmount=0`、提成基数 = 原价；
- 退卡两种口径（原价 / 卡价）各一条，**默认值不得由代码决定**。

**场景测试**
1. S1 线上预约到店：约 → 付 → 到店 → 服务 → 完成 → 结账；
2. S3 电话预约（**无会员、无订单**）→ 到店建单 → 到店付；
3. S4 卡客：耗卡订单实收 0 但状态为已付，**结算与报表都取到原价**；
4. S5 疗程：卖卡一张单，十次服务十张单，**收入不重复计**；
5. S7 团体：一次占两个技师，其中一个满 → 整体失败且零残留；
6. 爽约：过点未到 → 释放资源 → 按门店规则扣或不扣；
7. **退款倒扣提成**：退单后 `bty_commission_record` 必须出现 `REVERSED`。

**闸门**
- 剔除美业包（`-Pcore-only`）后，零售与餐饮全量测试仍全绿；
- ArchUnit：`bty` 包不许 import 基座实体；美业迁移不许写非 `bty_` 表；
- 撤掉"提成基数取原价"的实现，用例 3 必须变红且点名正确；
- 撤掉占位回退，用例 5 必须变红。

---

# 十六 · 待确认（PRD 必须回答）

1. **退卡核销口径**：原价还是卡价（§5.1）？两种都合法，但不能由代码默认决定。
2. **爽约规则**：扣次 / 扣定金 / 不罚？提前多久取消免责？
3. **指定技师加价**：进商品价还是独立订单行（本册选后者）？加价归不归技师提成？
4. **提成基数**：原价 / 实收 / 卡价？换人后归谁？退款倒扣是否追已发放的？
5. **疗程包跨店通兑**：连锁店之间能不能互相核销？（涉及结算跨主体，成本高）
6. **无会员顾客的手机号**：存不存？存则受个人信息保护约束，要走基座脱敏与人档 pepper。
7. **超时占用**：客人做超时导致后续预约受影响，是自动改约还是人工处理？
8. **`assetDeductMinor` 这一列**：基座加列的排期（§4.6，本包的硬依赖）。
9. **客史照片**：保存期限、谁能看、离职员工的访问回收 —— 属敏感数据治理，越早定越好。
