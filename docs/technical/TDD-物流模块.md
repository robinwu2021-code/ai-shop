# TDD-物流模块

状态：已确认（2026-10-09 用户：「方案可行」）· 待实现
档位：2 · 依据：用户 2026-10-09 口头需求（§0.1 补 AC）· 产出：本文 + [ADR-032](ADR/ADR-032-物流独立为模块-先内嵌后可拆.md)
关联：[TDD-物流域-完整方案](design/TDD-物流域-完整方案.md)（**本文取代其 §3.5 作业、§5.4 批 D/F**；§4 微信能力清单、§5.5 签收闭环实现记录继续有效）·
[TDD-快递100轨迹查询](TDD-快递100轨迹查询.md)（主动查询改为默认关，见 §2.5）·
[TDD-物流轨迹多渠道](TDD-物流轨迹多渠道.md)（展示轴保留，数据源轴改为推送）·
[ADR-021](ADR/ADR-021-支付域独立为服务与独立库.md)（四阶段拆分，本文照它的形状走）
创建：2026-10-09 · 最后更新：2026-10-09

---

## L1 一句话

**物流是一个只产出「运单事实」的模块**：商家发货时登记一张运单，此后由订阅渠道（圆通直连 / 快递100 / 将来更多）**推**轨迹、由微信**补**状态，
签收时发一个事件给交易域 —— 它不持有订单状态，也不读写订单的表。
现在集成在主服务里一起部署；数据和依赖按「随时能拆出去」的标准切干净。

三条约束是这份设计的出发点（用户 2026-10-09 定）：

| # | 约束 | 落在哪 |
|---|---|---|
| 1 | 快递100 额度不够，**只做订阅**，不做主动查询 | §2.5 策略默认值；AC1 |
| 2 | 状态**尽量找微信要**，但要**策略模式、可配置** | §2.5 四个策略点 |
| 3 | **job 能不用就不用**；只给「订阅失败 / 推送沉默」这类明确场景兜底 | §2.6；AC8 |
| 4 | **多渠道**：圆通直连正在对接，将来接更多（顺丰、京东、菜鸟……）—— **加一家渠道不改表、不改调用方** | §2.1.1 渠道能力矩阵；AC13 AC14 |

---

## §0 对账一 · 需求 → 设计

### 0.1 验收标准（需求没有现成 PRD，按模板补）

- **AC1** 快递100 只订阅：默认配置下，系统对快递100 **主动查询**的调用次数为 0；每张运单**订阅恰好一次**（重复登记不重复订阅）。
- **AC2** 揽收前不调微信：发货信息未上传微信、或还没收到「已揽收」推送的运单，`trace_waybill` 调用 0 次。
- **AC3** 收到揽收推送 → 换 `waybill_token`；微信回 `9300559`（还没收录）时**等下一条推送再试**，不进任何定时作业。
- **AC4** 签收（推送或微信查询，任一先到）→ `signed_at` 只写一次、**状态只进不退**、发出 `WaybillSigned`；微信支付单据此提醒确认收货，**每个支付单一次**。
- **AC5** 线下付款单（及一切没有微信交易单号的单）：**不调任何微信物流接口**；轨迹照常落库；发货、派件、签收用我们自己的订阅消息通知买家。
- **AC6** 买家打开物流页：同一运单 **10 分钟内**重复打开，不重复调微信 `query_trace`。
- **AC7** 失败分类：可重试的失败自动重试（有上限、有退避）；不可重试的失败**不重试**、原因落在运单上、运营端可见、可手动重放。
- **AC8** 定时作业只剩一个物流补偿作业，**每小时**，只处理「订阅成功但 24 小时没有任何推送」的在途运单。
- **AC9** 策略可配置：状态探测链、快递100 主动查询开关（按界面）、换 token 时机 —— 改配置不改代码。
- **AC10** 模块边界：物流模块不依赖订单/支付/用户域的代码，不读写 `ord_*` / `trd_*` / `pmt_*` 表；反向 Port 至多 1 个。由守卫看住。
- **AC11** B 端 App 能看轨迹（来自推送落库的数据，不额外调快递100）。
- **AC12** 现有缺陷：运单状态被订单状态**来回覆盖**（已签收被打回运输中，每轮重查快递100）—— 修掉，状态单调。
- **AC13** 渠道路由：订阅按「门店 → 承运商 → 默认」三级选一条渠道链（沿用 2026-10-05「按门店路由、默认圆通」的决定）；
  链上逐个试，**不覆盖该承运商、未启用、或返回不可重试失败**的渠道自动让给下一个。默认链 `[yto, kuaidi100]`：圆通单走圆通直连，其余走快递100。
- **AC14** 加一家渠道：只新增 channel 模块里的实现类、`lgs_carrier_code` 的编码行、配置 —— **不改表结构、不改调用方、不改作业**。
  以测试证明：注册一个测试渠道，不动任何既有代码即可被路由选中并收到推送。

### 0.2 AC → 落点

| AC | 落点 |
|---|---|
| AC1 | 订阅链（`ChannelRouter`）；探测链默认不含 `kuaidi100`、`probe-surfaces.kuaidi100=[]`；UK(`biz_type`,`biz_ref`) 幂等 |
| AC2 | `WxBindPolicy.ready()`：`wx_uploaded_at` 非空 ∧ `status ≥ PICKED_UP` 才放行 |
| AC3 | `PushIngestService` 每次推送后调 `WxBindPolicy`；`9300559` → `bind_state=WAITING`，无作业 |
| AC4 | `WaybillStatus.advance()` 单调合并；`signed_at` 只在首次进入 DELIVERED 时写；`LogisticsEvents.WaybillSigned` → `paybridge` 消费 |
| AC5 | `lgs_waybill.profile = SELF`；`WxBindPolicy` 对 SELF 恒 false；`NotificationConsumer` 订阅 `WaybillProgressed` 只对 SELF 发 |
| AC6 | `TrackService.track()` 用 `wx_status_checked_at` 判 10 分钟缓存 |
| AC7 | 外部动作走 `sys_outbox` 重试（退避、上限）；不可重试 → `*_state=FATAL` + `*_error`；`/ops/shipments` 筛选 + 重放端点 |
| AC8 | `LogisticsCompensationJob`（`0 15 * * * *`）；删 `logistics-trace` / `wx-waybill-bind` / `wx-confirm-receive` 三个作业 |
| AC9 | `LogisticsProperties`（`shop.logistics.*`）+ 四个策略 SPI |
| AC10 | `ArchitectureTest` 登记域 `logistics`；新守卫 `LogisticsBoundaryTest`（禁 import / 禁表名 / 反向 Port 预算） |
| AC11 | `GET /biz/order/{subOrderNo}/trace` → `TrackService.track(BIZ)` 读库 |
| AC12 | 读时补齐 `ensureShipments` 整个删除（改为发货登记）；`WaybillStatus.advance()` 不允许降级 |
| AC13 | `ChannelRouter`（门店 → 承运商 → 默认，链式回退）；`lgs_waybill.sub_channel` 记下实际受理的渠道 |
| AC14 | 能力 SPI（§2.1.1）+ `lgs_carrier_code` 映射表 + 通用回调 `/callback/logistics/{channel}` |

**孤立项**：
- 没落点的 AC：无。
- 挂不上 AC 的设计：无。（上一版的 `lgs_carrier` 加两列已改为 `lgs_carrier_code` 映射表，挂 AC14。）

---

## §1 现状与影响面

### 1.1 物流现在散在五个模块

| 位置 | 内容 |
|---|---|
| `shop-base/spi/logistics`、`spi/fulfillment`、`spi/trade` | 轨迹查询/展示 Port、运费 Port、发货上报 Port、`ShipmentTraceQueryPort` |
| `shop-channel/channel/express/**` | 快递100 查询、圆通、微信插件换 token、寄件网关；`notify/port/WxShippingGateway`（发货上报） |
| `shop-core/fulfillment/**` | `LogisticsServiceImpl`（运单读时补齐、轮询、换单号、模板、运力）、轮询作业、运营端 Controller |
| `shop-core/trade/**` | 发货写 `ord_sub_order.express_no`、代下单、快递100 寄件回调、订单详情读轨迹、自动确认收货 |
| `shop-app/paybridge/**` | 换 token 作业、确认收货提醒作业、微信消息推送、发货上报台账 |

### 1.2 两个决定设计形状的事实

1. **`ful_shipment` 不是发货时写的，是「读时补齐」出来的投影**（`LogisticsServiceImpl.ensureShipments:126`）。
   商家发货只写 `ord_sub_order`，运单行要等轮询作业或运营打开列表才补出来。
   独立之后物流不能再去扫订单表 —— **这条线必须改成发货时登记**。
2. **补齐时用订单状态覆盖运单状态**（`:153-157` + `statusOf:211`）。子单还在履约中，状态就被改回 `IN_TRANSIT`，
   于是已签收的单**每轮又被查一次快递100**，直到买家确认收货（最长 7 天）。这就是 AC12。
3. **圆通直连已经在接**（[TDD-圆通物流直连](design/TDD-圆通物流直连.md)，`YtoTraceProvider`），而且它**有订阅也有推送**：

   | 圆通接口 | 状态（2026-10-08 控制台） |
   |---|---|
   | 物流轨迹订阅 `subscribe_adapter` | **调试通过** ✅ |
   | 物流轨迹推送服务 | 待调试 |
   | 物流轨迹查询 `track_query_adapter` | 审核中 |
   | 订单创建 `privacy_create_adapter`（返回运单号 + 三段码） | 待调试 |

   两条约束会直接进设计：**凭据按接口分发**（每个接口各有一组客户编码/密钥，不是账号一把）；**IP 白名单**（只有生产机 `106.55.27.246` 能调）。
   另外 2026-10-05 已定「**按门店路由，默认圆通**」—— 路由维度不能只有承运商。

### 1.3 会被改到的

`MerchantOrderServiceImpl.ship`（只多发一个事件，已有 `SubOrderShipped`）· `OrderServiceImpl.traceOf / signedAtOf 调用点` ·
`MerchantOrderServiceImpl.traceOf` · `WxConfirmReceiveService`（从作业改为事件消费）· `WxShippingUploadService`（成功后发事件）·
`OrderAutoReceiptJob`（读 `signedAtOf` 改走新 Port）· 运营端 `/ops/shipments` 页（加两个筛选列）· c-app 物流页（改调新端点）· b-app 订单详情（加轨迹入口）。

### 1.4 明确不受影响的

- **运费模板**（`ful_freight_template`、`FreightPort`）—— 下单算价走同步调用，ADR-031 刚落地，这期不搬，见 §L4。
- **快递代下单**（`ord_express_pickup`、`ExpressPickupPort`）—— 与商家欠款、结算判运费耦合，这期不搬。
- **自提 / 分拣 / 调度**（`ful_batch`、`ful_group_pickup`、`ful_verify_log`…）—— 那是门店履约，不是物流，**永远不搬**。
- **微信发货信息上报与确认收货提醒本身**（`upload_shipping_info`、`notify_confirm_receive`、`trd_shipping_upload`）——
  它们是**微信支付的合规义务**（自提、虚拟商品也要报），属交易域；物流只提供触发它们的事实（签收）。
- **微信结算事件**（`/mp/wx/callback`）—— 属交易域，批 B 已上线，不动。

---

## §2 方案

### 2.1 整体架构（L2）

![物流模块架构](diagrams/物流模块-架构.svg)

| 层 | 模块 / 包 | 放什么 | 防住什么 |
|---|---|---|---|
| 契约 | `shop-base/spi/logistics` | `LogisticsPort`（交易域读）、`LogisticsAdminPort`（运营端）、`LogisticsEvents`（出）、`ShipmentSourcePort`（唯一反向 Port） | 别的域只依赖这一层；直接 import 物流实现 = 拆分那天编译不过 |
| 领域 | **`backend/logistics/logistics-domain`**（新） | 运单、轨迹、状态机、四个策略 SPI、推送接收、补偿逻辑、`/callback/logistics/**` | 不依赖任何业务域、不依赖 HTTP 客户端 |
| 通道 | **`backend/logistics/logistics-channel`**（新） | 每家渠道一个包：`kuaidi100`、`yto`、`wx`、`stub`；将来 `sf`、`jd`… | 加一家渠道只在这一层加一个包；领域层不知道任何一家的报文 |
| 桥 | `shop-app/logisticsbridge`（新） | `ShipmentSourcePortImpl`（拼发货快照）、补偿作业的 `JobHandler`、事件消费适配 | 跨域拼装只许出现在这里（同 `invbridge` / `paybridge`） |
| 独立形态 | `logistics-svc`（**阶段 3 才建**） | 自己的 jar、`/internal/logistics/**`、`/callback/logistics/**` | — |

**为什么是这个形状**：照 [ADR-021](ADR/ADR-021-支付域独立为服务与独立库.md) 的四阶段走，这次只做**阶段 1（收拢）**，
同库、同 jar、同事务管理器。理由与取舍见 §3 和 ADR-032。

**依赖方向只有三条线，全部经过 `spi`**：

| 方向 | 线 | 形态 |
|---|---|---|
| 交易 → 物流（写） | 发货 | 已有事件 `OrderEvents.SubOrderShipped`（经 `sys_outbox`），物流消费后登记运单 |
| 物流 → 交易（取一次） | 登记那一刻要收件人、微信交易键、商品 | `ShipmentSourcePort.sourceOf(subOrderNo)`，**只在登记时调一次** |
| 物流 → 交易（出） | 揽收 / 派件 / 签收 / 异常 | `LogisticsEvents.*`（经 `sys_outbox`），交易与通知各自消费 |
| 交易 → 物流（读） | 订单详情、物流页、自动确认收货 | `LogisticsPort.track / signedAtOf` |
| 交易 → 物流（写） | 微信发货信息已上传 | 事件 `WxShippingUploaded`（换 token 的前置条件） |

> **为什么登记不用「事件里带全量数据」**：事件走 `sys_outbox`，而 `sys_outbox` **没有任何清理**，投递过的行永久留着。
> 把收件人手机号放进 payload，等于多存一份永久的明文。所以事件只带业务键，物流在登记那一刻拉一次快照，
> 之后再不读交易域。代价是一条反向 Port —— 支付域拆分卡在 11 条反向 Port 上，所以这里**定预算 = 1**，守卫看住。

### 2.1.1 渠道：按「能力」切，不按「哪一家」切

一家物流渠道能做的事不一样：快递100 是聚合（什么承运商都覆盖），圆通直连只覆盖圆通单，微信只能查状态、不给我们轨迹节点。
所以抽象的单位是**能力**，每家渠道实现自己支持的那几个；路由按能力分别选渠道。

| 能力 SPI（`logistics-domain`） | 做什么 | kuaidi100（聚合） | yto（直连） | wx（微信物流） | 将来：顺丰 / 京东 / 菜鸟… |
|---|---|---|---|---|---|
| `TrackingSubscriber` | 订阅一张运单，此后对方推给我们 | ✅ `/poll` | ✅ `subscribe_adapter`（调试通过） | — | 按各家 |
| `PushReceiver` | 验签、解析推送、给对方回执 | ✅ | ⏳ 推送服务待调试 | —（三节点消息直接推买家，不给我们） | 按各家 |
| `StatusProbe` | 主动查一次状态 / 节点 | ✅（**默认关**，额度） | ⏳ `track_query_adapter`（审核中） | ✅ `query_trace`（只给状态） | 按各家 |
| `TraceDisplay` | 给某个界面怎么展示 | — | — | ✅ 插件（`trace_waybill` 换 token） | — |
| `WaybillCreator` | 平台直连下单，拿运单号 | 寄件（已有，在交易域） | ⏳ `privacy_create_adapter` | — | 按各家 |

- 每个实现声明三样：`name()`、`covers(carrier)`（圆通直连只认 `YTO`）、`available()`（凭据没配就是不可用）。
- **订阅可用 ⇔ 同一渠道的推送接收也可用**（`ChannelRouter` 判，不靠各实现自觉）。订阅了却收不到推送，
  订阅接口照样返回成功、运单就此沉默、不报任何错 —— 圆通眼下正是这个状态（订阅调试通过、推送待调试）。
  有了这条，圆通推送调通之前，圆通单会**自动**落到快递100，不需要有人记得先别把 `yto` 配进链里。
- `WaybillCreator` 这期**只定义接口、不搬实现**：快递100 寄件留在交易域，圆通下单（Y6）还没开工。
  它在这里是为了把「运单号的三个来源」（平台直连下单 / 网点回传 / 商家自填）都收口到同一个登记入口 —— 见 §L4 待决。
- `stub` 渠道覆盖全部承运商、什么都不做：开发环境与「一家都没配」时链尾恒真，不白屏。

**路由**（`ChannelRouter`，沿用现有 `LogisticsTraceRouter` 的三级模型）：

```
链 = 门店路由[storeNo] ?: 承运商路由[carrier] ?: 默认链        ← 每种能力各配一套
对链上每个渠道：未启用 / 不覆盖该承运商 / 不可用 → 跳过
               调用 → 成功：停；可重试失败：交 outbox 重试（同一渠道）；不可重试失败：记下原因，换链上下一个
链走完都没成 → FATAL（运单上记最后一个渠道的原因）
```

默认订阅链 `[yto, kuaidi100]`：圆通单先走圆通直连（不占快递100 额度），圆通不可用或回不可重试的错，自动落到快递100；
其余承运商圆通不覆盖，直接走快递100。**同一张运单只订阅在一个渠道上**（`lgs_waybill.sub_channel`），推送也只认那个渠道来的。

**加一家渠道要做的事**（AC14 的验收清单）：

| # | 做什么 | 不用做什么 |
|---|---|---|
| 1 | `logistics-channel` 里加一个包，实现它支持的能力 SPI | 不改领域层、不改调用方 |
| 2 | 状态码映射（对方码 → `TraceStatus`）与失败码分类（可重试 / 不可重试），各一张表一组测试 | — |
| 3 | `lgs_carrier_code` 插它的承运商编码行 | **不改表结构** |
| 4 | 配置 `shop.logistics.channels.<name>` + 把名字放进需要的路由链 | 不改代码 |
| 5 | 有推送的：回调地址就是 `/callback/logistics/<name>`，同一个 Controller 按名字分派 | 不加 Controller、nginx 不改 |
| 6 | 有 IP 白名单 / 按接口分发凭据的：配置里按能力分组给 | — |

### 2.2 数据库

![物流模块数据表（草案）](diagrams/物流模块-数据表.svg)

> 这张图是**草案，手画**。迁移落地后 `db-*.svg` 会从 schema 重新生成，以生成的为准。

**表前缀从 `ful_` 改为 `lgs_`**：`ful_` 同时被留下的自提/分拣表使用（`ful_batch`、`ful_verify_log`…），
阶段 2 切库的闸门是「主库迁移里不再出现 `lgs_*` 建表」—— 前缀不分开，这条闸门写不出来。

| 表 | 来源 | 行的含义 |
|---|---|---|
| `lgs_waybill` | `RENAME ful_shipment` + 加列 | 一张运单 |
| `lgs_waybill_node` | `RENAME ful_shipment_trace` + 加列 | 一个轨迹节点 |
| `lgs_carrier` | `RENAME ful_carrier` | 一家承运商（我方码） |
| `lgs_carrier_code` | **新** | 一家承运商在某个渠道那里叫什么 |

**只新增一张映射表**，为的是 AC14：承运商编码如果按渠道加列（`kd100_code`、`wx_delivery_id`、`yto_code`…），
每接一家渠道就要改一次表。外部动作的状态（订阅、换 token）仍作为运单上的列，不另建台账表 ——
每张运单只有两件外部动作，台账表换来的只是多一次 JOIN；重试交给已有的 `sys_outbox`（§2.6）。

#### `lgs_waybill`（运单）

| 列 | 类型 | 说明 · 防住什么 |
|---|---|---|
| `shipment_no` | varchar(32) UK | 业务键，沿用原值（不重编，外部已引用） |
| `biz_type` | varchar(16) | `SUB_ORDER`（今天唯一值）/ 将来 `RETURN`（售后寄回）—— 不写死「子单」，退货物流接进来不用改表 |
| `biz_ref` | varchar(32) | 原 `sub_order_no` 改名。**UK(`biz_type`,`biz_ref`)** —— 重复的发货事件只登记一次（AC1 幂等的根） |
| `entity_no` / `store_no` | varchar(32) | **快照**：运营筛选用；不回查商家表（ADR-021 §3.3 跨库只存业务键+快照） |
| `carrier` | varchar(16) | 我方承运商码（`lgs_carrier.carrier`） |
| `waybill_no` | varchar(64) | UK(`carrier`,`waybill_no`) 沿用 |
| `profile` | varchar(8) | **`WX`**：有微信交易单号与付款人 openid，可用微信全套能力；**`SELF`**：其余（线下付款、APP 单）。**登记时定死** —— 不让每个调用点各自判断「这单能不能调微信」 |
| `receiver_name` / `region` | 原列 | 快照 |
| `receiver_phone_enc` | varchar(128) | 收件人手机号 **AES-GCM 密文**。订阅（顺丰等必填）与换 token（申通/中通必填）要完整号码。**进入终态（DELIVERED / CANCELLED）后清空** —— 用完就删 |
| `receiver_phone_last4` | char(4) | 展示与排查用 |
| `wx_trans_id` / `wx_openid` / `wx_out_trade_no` | varchar(64) | **快照**：仅 `profile=WX` 有值；`trace_waybill` 与确认收货提醒要用 |
| `goods_brief` | json | 商品名、图（≤3 件）：`trace_waybill` 必填 |
| `status` | varchar(16) | 状态机见 §2.4.5，**单调** |
| `picked_up_at` / `signed_at` | bigint | 首次进入 PICKED_UP / DELIVERED 的时刻（毫秒），**只写一次** |
| `at_locker` | tinyint | 渠道子状态「投柜或驿站」（快递100 `501`）—— 物流页显示「已到驿站 / 快递柜」（功能清单 L-C-05） |
| `carrier_corrected_from` | varchar(16) | 渠道纠正过承运商时（快递100 `autoCheck=1`）记下原值 —— 商家选错快递公司是常态，纠正要留痕，不静默覆盖 |
| `last_event_at` | bigint | 最近一次收到推送或查询有新进展的时刻 —— 补偿作业判「沉默」用 |
| `sub_state` | varchar(12) | 订阅：`PENDING` / `DONE` / `FATAL` / `ENDED`（渠道停止跟踪） |
| `sub_channel` | varchar(16) | **实际受理订阅的渠道**（`yto` / `kuaidi100` / …）。推送只认这个渠道来的 —— 否则换渠道重订阅后，旧渠道迟到的推送会和新渠道的打架 |
| `sub_ref` | varchar(64) | 渠道返回的订阅号（有的渠道给，有的不给） |
| `sub_attempts` / `sub_error` | int / varchar(200) | 链上最后一次失败的渠道、码与原文 |
| `kd100_sub_month` / `kd100_sub_count` | char(6) / int | 快递100 本单号**本自然月**已订阅几次（官方上限每月 4 次）；月份变了计数归零 —— 重放前先看它，不去撞上限 |
| `wx_uploaded_at` | bigint | 微信发货信息上传成功的时刻（来自交易域事件）—— 换 token 的前置条件 |
| `bind_state` | varchar(12) | 微信换 token：`NA`（SELF 单）/ `WAITING`（前置未满足或微信还没收录）/ `DONE` / `FATAL` |
| `bind_error` | varchar(200) | |
| `display_token` | varchar(256) | 原列，存 `waybill_token` |
| `wx_status_checked_at` | bigint | 最近一次调 `query_trace` 的时刻 —— 10 分钟读缓存（AC6） |
| 删除 | | `display_channel`、`display_fail_reason`、`display_prepared_at`、`trace_queried_at` —— 被上面的状态列取代 |

#### `lgs_waybill_node`（轨迹节点）

| 列 | 说明 |
|---|---|
| `shipment_no`, `at`, `text`, `location`, `lat_e6`, `lng_e6`, `status_code` | 原列 |
| `channel` + `mode` | 哪个渠道（`kuaidi100` / `yto` / …）、怎么来的（`PUSH` / `QUERY`）—— 两列而不是一个枚举，加渠道不改枚举 |
| `text_hash` | char(16)；**UK(`shipment_no`,`at`,`text_hash`)** —— 快递100 每次推送是**全量**轨迹，重复节点靠唯一键丢弃，不靠比对 |

> 微信 `query_trace` **只返回状态、不返回节点**。所以 `WX_QUERY` 不产生节点，只推进 `lgs_waybill.status`。

#### `lgs_carrier`（承运商）与 `lgs_carrier_code`（各渠道编码）

`lgs_carrier` 只改名，`carrier` 是**我方码**（`SF` / `STO` / `YTO` …），全系统只认它。

`lgs_carrier_code`：

| 列 | 说明 |
|---|---|
| `carrier` | 我方码 |
| `channel` | `kuaidi100` / `yto` / `wx` / … |
| `code` | 该渠道里的叫法（快递100 `shentong`、微信 `STO`、圆通直连 `YTO`） |
| UK(`carrier`,`channel`) | 一家承运商在一个渠道里只有一个叫法 |

渠道实现的 `covers(carrier)` 默认就是「这张表里有没有我这一行」—— 圆通直连只有 `YTO` 一行，所以只覆盖圆通单。
**它取代两份互不对应的码表**：`ExpressCompanies`（代码常量，微信码）与 `ful_carrier`（库，SF/JD/YTO）；
迁移里按现有 `ExpressCompanies` 与快递100 公司编码表种一份初始数据。

#### 迁移（`V387__logistics_module.sql`，一条）

1. `RENAME TABLE ful_shipment TO lgs_waybill`（另两张同）—— MySQL 原子、不拷数据；建 `lgs_carrier_code` 并种初始编码。
2. 加列；`sub_order_no` 改名 `biz_ref`，补 `biz_type='SUB_ORDER'`。
3. 回填：`profile`（按 `ord_order.pay_trade_no` 是否非空）、`wx_*`、`receiver_phone_enc`（在途单）—— **迁移里只回填在途运单**，已签收的不需要手机号。
4. 删掉 §2.2 列出的四个旧列 —— **放到下一个版本**：先上线一版两套列并存，确认没有读者再删（已应用的迁移不可改，删列只能新开一条）。

> ⚠️ 手机号加密不能在 SQL 里做（密钥在应用配置）。第 3 步的 `receiver_phone_enc` 由**应用启动后的一次性回填**完成
> （`LogisticsBackfillRunner`，跑完落一条标记，再启动不重跑），不放进 Flyway。

### 2.3 API

> 本节只列契约形状与取舍；逐端点的请求 / 响应 / 错误码 / 鉴权见 **[物流-API](物流-API.md)**。

#### 2.3.1 模块契约（`shop-base/spi/logistics`）

```java
/** 交易域读物流。只读，不触发外部调用 —— 除了 track() 按策略可能调一次微信 query_trace */
public interface LogisticsPort {
    Optional<TrackView> track(TrackQuery q);                 // q: bizType, bizRef, surface(MP/APP/H5/BIZ/OPS), refresh
    Map<String, Long> signedAtOf(Collection<String> bizRefs); // 自动确认收货用
}

/** 唯一的反向 Port：登记那一刻向交易域取一次快照（实现在 shop-app/logisticsbridge） */
public interface ShipmentSourcePort {
    Optional<ShipmentSource> sourceOf(String subOrderNo);
    record ShipmentSource(String subOrderNo, String entityNo, String storeNo,
                          String carrier, String waybillNo,
                          String receiverName, String receiverPhone, String region,
                          WxKey wx,                    // 没有微信交易单号 → null → profile=SELF
                          List<GoodsBrief> goods, String orderPath, long shippedAt) { }
    record WxKey(String transId, String outTradeNo, String openid) { }
}

public final class LogisticsEvents {
    record WaybillProgressed(String bizRef, String shipmentNo, String profile, String status, long at) { }  // PICKED_UP / DELIVERING / EXCEPTION
    record WaybillSigned(String bizRef, String shipmentNo, String profile, long signedAt, String source) { }
}

// 交易域发出、物流消费
// 已有：OrderEvents.SubOrderShipped
// 新增：TradeEvents.WxShippingUploaded(String orderNo, List<String> subOrderNos, long at)
```

`TrackView` **就是现有的 `OrderVO.Trace` 加字段**（字段只加不改，老端不受影响）：
现有 `status` · `nodes[]`（倒序，`at` 毫秒）· `displayMode`（`wx-plugin` / `self-map`）· `displayToken` · `route`；
新增 `carrier` · `waybillNo` · `signedAt` · `atLocker` · `freshAt` · `refreshable`。字段表见 [物流-API §3](物流-API.md)。

#### 2.3.2 HTTP 端点

逐端点见 **[物流-API](物流-API.md)**（13 个：C 端 2、B 端 3、运营端 5、回调 3）。这里只记两条形状上的决定：

- **用户与运营端点留在主应用**（C / B 端在交易域的 controller，运营端在 `portal/ops`）：物流模块不认识用户，
  「这张单是不是你的」只有交易域能判 —— 先按当前登录人查到子单（防 IDOR），再拿业务键问 `LogisticsPort`；
  运营端经 `LogisticsAdminPort`。与支付域「领域模块不做 controller」同形。
- **回调放在物流模块里**，路径 `/callback/logistics/{channel}`，一个 Controller 按渠道名分派：
  阶段 3 拆出去时 nginx 按前缀转发一行；加渠道不加 Controller、不改 nginx、不改安全配置（`/callback/**` 已放行）。
  回调**必须返回 `String`** —— 统一信封不排除 `/callback/**`，返回对象会被包成 `{code,msg,data}`，渠道认不出成功会重推。
  它不复用现有 `/callback/express/kuaidi100`（寄件回调）—— 两个产品、两种报文。

### 2.4 核心流程

> 本节是摘要。每个模块的输入、步骤、幂等、失败处理与测试要点见 **[物流-功能模块方案](物流-功能模块方案.md)**（回调 M4、查询 M7、作业 M8）；
> 按场景的调用与推送清单见 **[物流-调用清单](物流-调用清单.md)**。

![物流主链路](diagrams/物流模块-主链路.svg)

#### 2.4.1 发货登记（取代读时补齐）

```
商家发货 → MerchantOrderServiceImpl.ship（只写 ord_sub_order，照旧）
        → sys_outbox: SubOrderShipped
        → [物流] ShipmentRegistrar.onShipped
              EXPRESS 且有单号才继续
              ShipmentSourcePort.sourceOf(sub)          ← 唯一一次读交易域
              INSERT lgs_waybill（UK(biz_type,biz_ref) 冲突 = 已登记，直接返回）
              profile = wx==null ? SELF : WX；bind_state = SELF ? NA : WAITING
              sys_outbox: 内部事件 SubscribeRequested
        → [物流] SubscribeExecutor（outbox 消费）
              链 = ChannelRouter.subscribeChain(storeNo, carrier)      ← 默认 [yto, kuaidi100]
              对链上每个可用且覆盖该承运商的渠道 ch：
                  ch.subscribe(lgs_carrier_code[carrier,ch], waybillNo, phone, /callback/logistics/{ch})
                  成功（含快递100 501 重复订阅）→ sub_state=DONE, sub_channel=ch；停
                  可重试失败 → 抛异常，outbox 退避重试**同一个渠道**（上限见 §2.6）
                  不可重试 → 记 sub_error，换链上下一个
              链走完都不成 → sub_state=FATAL, ERROR 日志；不抛
```

**为什么订阅不在登记的同一个事务里做**：外部调用不该占着数据库事务；而且订阅失败要重试时，
不该把「登记」也一起回滚重来。

#### 2.4.2 推送接收（渠道 → 我们）

```
POST /callback/logistics/{channel}
  PushReceiver[channel] 不存在 → 404
  验签失败 → 回成功 + WARN（回失败会被无限重推；验签失败的报文重推也不会变对）
  receiver.parse → (承运商编码, 单号, 节点[], 渠道状态)；经 lgs_carrier_code 反查我方承运商码，找运单
  找不到运单，或运单的 sub_channel ≠ channel（换过渠道，旧渠道迟到的推送）→ 回成功 + WARN，不入库
  渠道说「停止跟踪」（快递100 abort：如 3 天无记录）→ sub_state=ENDED；在途的交给补偿作业
  落节点（UK 去重）、last_event_at=now
  WaybillStatus.advance(当前, 推送状态) → 进了新阶段：
      PICKED_UP 首次 → picked_up_at；WxBindPolicy 判换 token（2.4.3）
      DELIVERED 首次 → signed_at；发 WaybillSigned（2.4.4）
      其余 → 发 WaybillProgressed（SELF 单的自有通知用）
  每次推送后都再判一次 WxBindPolicy —— 上次 9300559 的，这次自然重试
```

#### 2.4.3 换 token（微信，**前置条件满足才调**）

```
WxBindPolicy.ready(w) = profile==WX ∧ bind_state==WAITING ∧ wx_uploaded_at!=null ∧ status≥PICKED_UP
触发点：① 推送进入 PICKED_UP 或之后任一推送  ② 收到 WxShippingUploaded（两者先后不定，两边都要判）
调 trace_waybill：
  成功 → display_token, bind_state=DONE
  9300559（微信还没收录）→ 保持 WAITING，什么都不做，等下一条推送
  9300561 手机号错 / 9300534 配置错 → bind_state=FATAL + bind_error，不重试
  取 access_token 失败 / 9300513 超限 / 网络 → 交给 outbox 退避重试
```

`trigger=on-ship` 时（策略可切）退化成发货即调，供以后验证「微信收录是否其实很快」。

#### 2.4.4 签收

两个来源，**谁先到谁算，另一个到了是空操作**：快递100 推送里的签收；微信 `query_trace` 返回 4（已签收）/ 6（代签收）。

```
signed_at 首次写入 → sys_outbox: WaybillSigned
  ├─ [交易/paybridge] profile==WX → notify_confirm_receive（每支付单一次，trd_shipping_upload.confirm_notified_at 幂等）
  ├─ [通知] profile==SELF → 自有订阅消息「已签收」
  └─ 自动确认收货：OrderAutoReceiptJob 照旧每天跑，经 LogisticsPort.signedAtOf 取签收时间（批 C 规则不变）
receiver_phone_enc 清空
```

现在的 `wx-confirm-receive` 作业（每 30 分钟扫一次）删除：签收那一刻就触发，失败的由 outbox 重试。

#### 2.4.5 状态机（单调）

```
CREATED → PICKED_UP → IN_TRANSIT → DELIVERING → DELIVERED
   任一非终态 ⇄ EXCEPTION（疑难、退回、拒签；恢复后回到原阶段）
   任一非终态 → CANCELLED（发货撤回、换单号作废旧号）
```

`advance(cur, incoming)`：incoming 的阶段序号 ≤ cur 的 → 不变。**没有任何路径能把 DELIVERED 改回去** —— 这就是 AC12 的修法；
旧代码的问题不是映射错，是「另一个数据源（订单状态）也能写这个字段」。新设计里订单状态**不再写**运单状态。

**运单状态沿用现有 `FulShipment` 的取值**（`CREATED` `PICKED_UP` `IN_TRANSIT` `DELIVERED` `EXCEPTION`，前端类型与存量数据都是这套），
只新增 `DELIVERING`（派件中）与 `CANCELLED`。

**每个渠道自带一张映射表**（对方码 → 渠道统一状态 `TraceStatus`），状态机只认统一状态 —— 加渠道不改状态机。
`TraceStatus` 现在是 `PICKED / IN_TRANSIT / SIGNED / EXCEPTION / UNKNOWN`，派件被并进了运输中；本期**新增 `DELIVERING`**，
并给 `TraceResult` 加一个 `atLocker` 标志（快递100 子状态 `501` 投柜或驿站）。
⚠️ 给枚举加值是有风险的：按它分支的老代码会把新值默默当成别的（`switch` 落进 default、`if` 只判了旧值）。
批 1 要逐个排查 `TraceStatus` 的所有分支点，并加一条守卫：每个分支点都必须显式处理 `DELIVERING`。

| 运单状态 ← 渠道统一状态 | 快递100 | 微信 `query_trace` | 圆通 |
|---|---|---|---|
| PICKED_UP ← PICKED | `1` 揽收 | `1` | 沿用 `YtoTraceProvider.mapStatus`（Y3.1 已按官方文档校准） |
| IN_TRANSIT ← IN_TRANSIT | `0` 在途 / `7` 转投 / `8` 清关 | `2` | 同上 |
| DELIVERING ← DELIVERING（新） | `5` 派件（`501` 投柜或驿站 → `atLocker`） | `3` | 同上 |
| DELIVERED ← SIGNED | `3` 签收（`301`~`304`） | `4` 已签收 / `6` 代签收 | 同上 |
| EXCEPTION ← EXCEPTION | `2` 疑难 / `4` 退签 / `6` 退回 / `14` 拒签 | `5` | 同上 |

> 快递100 订阅时传 `resultv2=4` 才带子状态（要用 `501`）。快递100 与圆通的推送状态码都以**第一条真实推送**为准（圆通推送服务还没调试）；上表是官方文档口径。

#### 2.4.6 查看

```
TrackService.track(q)
  读 lgs_waybill + 节点
  surface==MP ∧ profile==WX ∧ display_token 有：
      display = wx-plugin(token)
      若 now - wx_status_checked_at ≥ 10 分钟 ∧ 状态非终态：调 query_trace → advance（可能触发签收）
  其余：display = self-map(nodes)
  refresh=true：按 StatusProbe 链问一次（链与界面白名单都由配置给；快递100 默认不在任何界面的白名单里）
```

### 2.5 策略与配置（AC9）

四个策略点。前两个是**按能力的路由链**（§2.1.1 的 `ChannelRouter`，门店 → 承运商 → 默认），后两个是开关。
链尾恒有 `stub`（什么都不做）—— 沿用现有 `LogisticsTraceRouter` 的模型。

| 策略点 | 决定什么 | 可选 | 默认 |
|---|---|---|---|
| 订阅链 | 谁把轨迹推给我们 | `yto`、`kuaidi100`、将来的渠道 | `[yto, kuaidi100]` |
| 探测链 | 补偿与读时找谁问状态 | `wx`、`yto`、`kuaidi100`、将来的渠道 | `[wx]`；圆通查询审核通过后 `YTO: [wx, yto]` |
| 探测允许的界面 | 哪些界面的「刷新」能真的去问渠道 | `MP` `APP` `H5` `BIZ` `OPS`，按渠道给 | `kuaidi100: []`（额度不够，一个都不允许） |
| 换 token 时机 | 微信 | `on-picked-up`、`on-ship` | `on-picked-up` |

```yaml
shop:
  logistics:
    routes:
      subscribe:
        by-default: [yto, kuaidi100]  # yto 只覆盖圆通单；其余承运商直接落到 kuaidi100
        by-carrier: {}                # 例：{ SF: [sf, kuaidi100] }  —— 接顺丰直连那天
        by-store: {}                  # 例：{ ST-xxx: [kuaidi100] } —— 沿用 2026-10-05「按门店路由」
      probe:
        by-default: [wx]
        by-carrier: {}                # 圆通查询审核通过后：{ YTO: [wx, yto] }
    probe-surfaces:
      kuaidi100: []
    read-cache-minutes: 10
    wx-bind:
      trigger: on-picked-up
    compensation:
      cron: "0 15 * * * *"
      silent-hours: 24
    phone-key: ${LOGISTICS_PHONE_KEY:}   # 空 → 不存密文（同 PhoneCrypto 的失败方式），订阅不带手机号
    callback-base: ${LOGISTICS_CALLBACK_BASE:https://www.hxmall.top/callback/logistics}
    channels:
      kuaidi100:
        enabled: true                 # 账号凭据仍用 shop.express.kuaidi100.*（与寄件同一账号；阶段 3 再拆）
      yto:
        enabled: true
        host: ${SHOP_EXPRESS_YTO_HOST:https://openapi.yto.net.cn}
        subscribe: { customer-code: ${YTO_SUBSCRIBE_CODE:}, secret: ${YTO_SUBSCRIBE_SECRET:} }
        query:     { customer-code: ${YTO_QUERY_CODE:},     secret: ${YTO_QUERY_SECRET:} }
        push:      { secret: ${YTO_PUSH_SECRET:} }
```

- **圆通凭据按能力分组**：圆通是每个接口一组客户编码/密钥（§1.2-3）。现在的 `shop.express.yto.app-key/secret` 是「一个账号一把」的形状，
  接第二个接口时就装不下 —— 改成 `channels.yto.<能力>.*`。哪个能力没配，那个能力的 `available()=false`，不影响别的能力。
- **不是每家渠道都要配 `channels.<name>`**：没配的渠道不启用，路由链里写了也会被跳过（启动时 WARN 一次）。
- `shop.express.trace.*`（旧数据源链）与 `shop.express.trace.cache-ttl-minutes`、`shop.express.yto.*` 删除；删之前 grep 生产 env 是否在用，
  且**生产 env 的键名同步改**（旧键留着不报错，只是静默不生效）。
- ⚠️ 写 yml 前先 grep `shop:` 下是否已有 `logistics:` —— YAML 重复键让**整个上下文起不来**，2026-10-09 刚出过一次。
- 绑定用 `@ConfigurationProperties` 记录类；List 字段给字段默认值，**不挂 `${ENV:}`**（空串绑不进集合，上下文起不来）。

### 2.6 失败分类与重试（AC7）

| 类 | 例子 | 处理 | 去哪看 |
|---|---|---|---|
| **前置未满足** | 没上传微信、还没揽收 | **不调**。不是失败 | — |
| **还没准备好** | `9300559` 微信未收录 | 保持 WAITING，**下一条推送自然重试**，无作业 | `bind_state=WAITING` |
| **不该调** | SELF 单 | 登记时 `bind_state=NA`，任何路径都不调微信 | — |
| **可重试** | 网络、5xx、取 token 失败、`9300513`、快递100 `500` | 抛异常 → `sys_outbox` 退避重试**同一渠道**，到上限转 FAILED | outbox FAILED + ERROR 日志 |
| **不可重试** | `9300561` 手机号、`9300534` 配置、快递100 `600`/`601`（key/余额）/`700`（不支持的公司）、圆通凭据或白名单错 | 订阅：**换链上下一个渠道**；链走完 → `FATAL` + 码与原文；ERROR | `/ops/shipments?subState=FATAL`，修好后点重放 |
| **认不出的码** | 渠道返回了映射表里没有的错误码 | **按不可重试处理**并落原文 —— 盲目重试会烧额度，而原文就是补映射表的依据 | 同上 |
| **成功的一种** | 快递100 `501` 重复订阅 | 按成功处理 —— 不然重试一次反而记成失败 | — |

**失败码分类是每个渠道自己的一张表**（`logistics-channel/<name>/` 里，与状态映射放一起），领域层只认三种结局：
成功 / 可重试 / 不可重试。圆通订单创建的失败码官方给了「是否可重试」一列（[TDD-圆通物流直连 §11.4](design/TDD-圆通物流直连.md)），订阅与推送的码等调通时补。

**重试用 `sys_outbox`，不另起作业**：它已经有退避（`next_retry_at`）、上限、FAILED 终态。
理论上 FATAL 与 outbox FAILED 都应长期为 0；补偿作业每轮把这两个数报出来（§2.7），不为 0 就是缺陷或配置问题。

### 2.7 定时作业（AC8）

| 作业 | 频率 | 做什么 | 调谁 |
|---|---|---|---|
| **`logistics-compensate`**（新） | 每小时（`0 15`） | 只扫 **沉默运单**：非终态 ∧ `sub_state ∈ {DONE, ENDED}` ∧ `last_event_at < now-24h`。走 `StatusProbe` 链（默认只有微信，需有 token）。没 token 又不许查快递100 的，计入「无法探测」 | 微信 `query_trace` |
| `order-auto-receipt` | 每天 | 不变（时间驱动，本来就是明确场景） | 不调外部 |
| ~~`logistics-trace`~~ | 删 | 被订阅推送取代 | |
| ~~`wx-waybill-bind`~~ | 删 | 被推送触发取代 | |
| ~~`wx-confirm-receive`~~ | 删 | 被 `WaybillSigned` 事件取代 | |
| `wx-shipping-upload` | 不变 | 交易域的发货上报补报 | |

补偿作业每轮的 detail 固定五个数：`扫描 / 探测成功 / 推进 / 无法探测 / FATAL 与 outbox FAILED 合计`。

**调用量**（按一天 100 单在途、平均 3 天签收估）：

| | 现在 | 之后 |
|---|---|---|
| 快递100 | 约 4800 次/天（每 30 分钟查一遍，已签收的也在查） | **≤ 100 次/天**（每单订阅一次；圆通单走圆通直连，不占快递100） |
| 圆通直连 | 0（查询接口还在审核） | 圆通单每单订阅一次 |
| 微信 `trace_waybill` | 每 30 分钟扫一遍在途 | 每单约 1 次 |
| 微信 `query_trace` | 0 | 物流页打开（10 分钟缓存）+ 沉默运单每小时一次；额度 10 万/天 |
| 定时作业 | 3 个，每 30 分钟 | 1 个，每小时，只看沉默运单 |

### 2.8 模块设计

| 动作 | 路径 | 说明 |
|---|---|---|
| 新增 | `backend/logistics/pom.xml`、`logistics-domain/`、`logistics-channel/` | 根 pom 登记 |
| 新增 | `logistics-domain/…/logistics/{service,entity,mapper,policy,spi,api/callback,job}` | 包 `ai.neargo.shop.logistics` |
| 迁入 | `LogisticsServiceImpl`（运单与轨迹部分）、`ShipmentTraceQueryPortImpl`、`FulShipment*`、`FulCarrier` → `logistics-domain` | 运费模板、自提相关**留在原处** |
| 迁入 | `channel/express/trace/**`、`channel/express/display/WxPluginDisplay` → `logistics-channel/{kuaidi100,yto,wx,stub}/` | 寄件网关**留在原处** |
| 新增 | `logistics-domain/…/channel/{TrackingSubscriber,PushReceiver,StatusProbe,TraceDisplay,WaybillCreator,ChannelRouter}` | 能力 SPI + 路由；`LogisticsTraceRouter` 并入 `ChannelRouter` |
| 新增 | `logistics-channel/yto/{YtoSubscriber,YtoPushReceiver}`；`YtoTraceProvider` 改为 `YtoStatusProbe` | 订阅已调试通过；推送待调试；查询审核中 |
| 新增 | `logistics-channel/kuaidi100/{Kuaidi100Subscriber,Kuaidi100PushReceiver}`；`Kuaidi100TraceProvider` 改为 `Kuaidi100StatusProbe` | |
| 新增 | `logistics-domain/…/api/callback/LogisticsCallbackController` | `/callback/logistics/{channel}`，按名字分派 |
| 删除 | `LogisticsServiceImpl.ensureShipments`、`refreshInTransitTraces`、`LogisticsTracePollingJob`、`WxWaybillBind*`、`WxConfirmReceiveJob` | |
| 新增 | `shop-base/spi/logistics/{LogisticsPort,LogisticsAdminPort,ShipmentSourcePort,LogisticsEvents}` | 取代 `ShipmentTraceQueryPort`、`LogisticsTracePort`、`TraceDisplayPort` |
| 移动 | `OpsLogisticsController` 的运单与运力部分 → `shop-app/portal/ops/OpsShipmentController` | 改调 `LogisticsAdminPort`；运费模板部分留原处 |
| 新增 | `shop-app/logisticsbridge/{ShipmentSourcePortImpl,LogisticsJobHandlers}` | |
| 修改 | `WxConfirmReceiveService` | 从扫表改为消费 `WaybillSigned` |
| 修改 | `WxShippingUploadService` | 上传成功后发 `WxShippingUploaded` |
| 修改 | `OrderServiceImpl` / `MerchantOrderServiceImpl` 的 `traceOf`、`OrderAutoReceiptJob` | 改走 `LogisticsPort` |
| 新增 | `V387__logistics_module.sql`、`LogisticsBackfillRunner` | |
| 新增 | 守卫 `LogisticsBoundaryTest`；`ArchitectureTest.DOMAINS` 加 `logistics` | AC10 |
| 修改 | c-app 物流页、b-app 订单详情、ops-web 运单页 | 改调新端点 / 加筛选 |

### 2.9 开发任务（每批单独可上线、可停）

> 每个任务一行：**做什么 · 对应 AC · 怎么验证 · 依赖**。「验证」一栏写的是**能失败的检查**；
> 每条 AC 的测试都要做一次消融（撤实现必须变红），写进 §5。批与批之间各自上线、各自能停。

#### 外部前置（需要有人去服务商控制台或生产环境做）

| ID | 事项 | 谁 | 卡哪一批 | 怎么确认做完了 |
|---|---|---|---|---|
| P1 | 联系快递100 客服**开通 HTTPS 回调**（默认只推 HTTP） | 用户 | 批 2 | 快递100 调试工具对 `https://www.hxmall.top/callback/logistics/kuaidi100` 推一条，我方日志出现 `[lgs-push-first]` |
| P2 | 确认快递100 **订阅产品**有余额（与「实时查询」是两本账） | 用户 / 我实测 | 批 2 | 一张真运单订阅返回 `200`；`600` / `601` 即没开或没钱 |
| P3 | 圆通**订阅接口正式客户编码 / 密钥** | 用户 | 批 2（圆通单） | 生产机上一张真圆通单订阅成功 |
| P4 | 圆通**轨迹推送服务**在控制台调通，拿推送密钥 | 用户 | 批 2b | 生产日志出现圆通的 `[lgs-push-first]` |
| P5 | 生产 env 加键：`LOGISTICS_PHONE_KEY`、`LOGISTICS_CALLBACK_BASE`、`YTO_SUBSCRIBE_*`、`YTO_PUSH_SECRET` | 我（只回读键名，不打印值） | 批 2 | `GET /ops/logistics/channels` 各能力 `available=true` |
| P6 | 圆通**查询接口**审核通过 | 圆通 | 不卡（配置即可加进探测链） | — |
| P7 | 小程序后台**选快递节点的订阅模板**（揽收 / 派件 / 签收，公共模板库里「物流」类），把模板号与字段名给我：后端 `WX_TPL_WAYBILL_{PICKED_UP,DELIVERING,SIGNED}` + `_FIELDS`，c-app `.env` 的 `VITE_WX_TPL_WAYBILL_*` 同值 | 用户选 / 我配 | 批 4 的订阅消息那一路（站内信不卡） | 线下付款快递单提交时弹出授权框；`notify_subscribe` 有这三个模板号的额度 |

#### 批 0 · 止血（可立刻做，不等别的）

| ID | 做什么 | AC | 验证 | 依赖 |
|---|---|---|---|---|
| T0.1 | `LogisticsServiceImpl.ensureShipments` 状态同步改为**只进不退**（已是 `DELIVERED` / `EXCEPTION` 的不被订单状态覆盖） | AC12 | 新用例：轮询推到 `DELIVERED` 后再跑一轮补齐，状态不变；消融：撤掉判断 → 红 | — |
| T0.2 | 作业表 `logistics-trace` 的 cron 改为每小时 | AC1（部分） | 回读作业表；次日看快递100 调用量 | — |
| T0.3 | 部署 + 回读 | — | health 200；线上进程是这一版 | T0.1 |

#### 批 1 · 收拢（行为不变）

| ID | 做什么 | AC | 验证 | 依赖 |
|---|---|---|---|---|
| T1.1 | 建 `backend/logistics/{pom,logistics-domain,logistics-channel}`，根 pom 登记；`shop-app` 依赖它们 | AC10 | `check-head-compiles.sh` 绿 | — |
| T1.2 | `ArchitectureTest.DOMAINS` 加 `logistics`；新守卫 `LogisticsBoundaryTest`：①不 import 业务域 ②源码不出现 `ord_` `trd_` `pmt_` 表名 ③`shop-base/spi/logistics` 里反向 Port ≤ 1 | AC10 | 三条各故意违反一次 → 红 | T1.1 |
| T1.3 | 迁移 `V387`：`RENAME` 三张表 → `lgs_*`；加列（§2.2）；建 `lgs_carrier_code` 并按 `ExpressCompanies` + 快递100 编码表种初始数据 | AC14 | **真库副本带 Flyway 跑一遍**（H2 绿证明不了）；`gen-test-schema.py` 重新生成 | T1.1 |
| T1.4 | 实体 / Mapper 迁入 `logistics-domain`，表名改 `lgs_*`；`LogisticsServiceImpl` 运单与轨迹部分迁入（运费模板留原处） | — | 全量测试绿（行为不变） | T1.3 |
| T1.5 | `channel/express/trace/**`、`WxPluginDisplay` 迁入 `logistics-channel/{kuaidi100,yto,wx,stub}/` | — | 同上 | T1.1 |
| T1.6 | 能力 SPI（`TrackingSubscriber` `PushReceiver` `StatusProbe` `TraceDisplay` `WaybillCreator`）+ `ChannelRouter`（三级链、`covers` 读 `lgs_carrier_code`、订阅可用 ⇔ 推送可用）；`LogisticsTraceRouter` 并入 | AC13 | `ChannelRouterTest` 五条（§5） | T1.4 T1.5 |
| T1.7 | `TraceStatus` 加 `DELIVERING`、`TraceResult` 加 `atLocker`；**逐个排查 `TraceStatus` 的所有分支点**；守卫：每个 `switch` 显式处理 `DELIVERING` | AC4 | 守卫故意漏一处 → 红 | T1.4 |
| T1.8 | 新 Port：`LogisticsPort`、`LogisticsAdminPort`、`ShipmentSourcePort`、`LogisticsEvents`；旧 `ShipmentTraceQueryPort` 等改为委托（过渡） | AC10 | 编译 + 全量 | T1.4 |
| T1.9 | `LogisticsProperties`（`shop.logistics.*`）；**写 yml 前 grep 是否已有 `logistics:`**；Map / List 用字段默认值 | AC9 | `LogisticsPropertiesTest`；全量（重复键会让上下文起不来） | T1.1 |
| T1.10 | 部署 + 回读（迁移在生产跑通、三张表已改名） | — | `flyway_schema_history` 回读 387 成功；health 200 | T1.1–T1.9 |

#### 批 2 · 发货登记 + 订阅 + 推送

| ID | 做什么 | AC | 验证 | 依赖 |
|---|---|---|---|---|
| T2.0 | **实测**：快递100 订阅一张真运单（P2）；圆通订阅一张真圆通单（P3） | — | 返回码记进本 TDD §7 | P2 P3 |
| T2.1 | `ShipmentRegistrar`：消费 `SubOrderShipped` → `sourceOf` → 登记（唯一键幂等）→ 发 `SubscribeRequested` | AC1 AC5 | `ShipmentRegistrarTest` 四条 | T1.8 |
| T2.2 | `logisticsbridge/ShipmentSourcePortImpl`：拼快照（收件人、手机号、微信交易键、商品 ≤3、订单页路径） | AC5 | 快照字段齐；线下付款单 `wx=null` | T1.8 |
| T2.3 | 手机号 AES-GCM（`phone-key`），终态清空，日志只出后四位 | AC4 | 加解密往返；签收后列为空；日志断言无完整号 | T1.9 |
| T2.4 | `SubscribeExecutor`：按链订阅、可重试抛出、不可重试换下一个、链尽 FATAL；快递100 每月 4 次计数 | AC1 AC7 AC13 | `SubscribeExecutorTest` | T1.6 |
| T2.5 | `Kuaidi100Subscriber` + `Kuaidi100Codes`（订阅返回码分类，`resultv2=4`，顺丰 / 中通带手机号） | AC7 | 返回码逐个用例 | T1.6 |
| T2.6 | `YtoSubscriber`（凭据按能力分组，签名沿用已校准的实现） | AC13 | 样例报文单测；生产实测在 T2.0 | T1.6 P3 |
| T2.7 | `LogisticsCallbackController`（`/callback/logistics/{channel}`，返回 `String`）+ `PushIngestService`（找运单、只认 `sub_channel`、节点去重、承运商纠正留痕、`abort`→`ENDED`、到柜标记、第一条原文落日志） | AC3 AC4 | `PushIngestServiceTest`；用快递100 文档样例报文 | T2.1 |
| T2.8 | `Kuaidi100PushReceiver`：验签 `upper(md5(param+salt))`、解析、回执 | AC3 | 验签失败仍回成功且不入库；`abort` 回成功 | T2.7 |
| T2.9 | `WaybillStatus.advance` 单调；`picked_up_at` / `signed_at` 只写一次 | AC4 AC12 | `WaybillStatusTest`；消融：允许降级 → 红 | T1.7 |
| T2.10 | 删 `ensureShipments` 读时补齐；`order-auto-receipt` 改走 `LogisticsPort.signedAtOf`；订单详情 trace 只读库 | AC12 | 全量；详情接口不触发外部调用（mock 断言 0 次） | T2.1 |
| T2.11 | 删 `LogisticsTracePollingJob`；**作业表 `logistics-trace` 停用** | AC8 | 回读作业表；调度器无 HandlerNotFound | T2.7 |
| T2.12 | 上线后：对**所有在途运单**补一次订阅（一次性，约几十单） | AC1 | 在途运单 `sub_state` 全部非 `PENDING` | T2.0–T2.11 |
| T2.13 | 部署 + 回读 + 第一条真推送核对（字段与文档是否一致） | — | 日志 `[lgs-push-first]`；运单节点在增长 | P1 P5 |

#### 批 2b · 圆通推送

| ID | 做什么 | AC | 验证 | 依赖 |
|---|---|---|---|---|
| T2b.1 | `YtoPushReceiver`（以调通后的真实报文为准）；配上推送密钥后 `available=true` → 圆通单自动走圆通 | AC13 | 推送密钥未配时圆通单落到快递100（用例）；配上后走圆通 | P4 |

#### 批 3 · 微信

| ID | 做什么 | AC | 验证 | 依赖 |
|---|---|---|---|---|
| T3.1 | `WxShippingUploadService` 上传成功后发 `WxShippingUploaded`；物流消费记 `wx_uploaded_at` | AC2 | 事件发出且只一次 | 批 2 |
| T3.2 | `WxBindPolicy` + `WxBindExecutor`：两个触发点、`9300559` 保持 WAITING、FATAL 分类 | AC2 AC3 | `WxBindPolicyTest`：未上传或未揽收 → 0 次调用 | T3.1 |
| T3.3 | `WxStatusProbe`（`query_trace`）；`TrackService` 读时校正（10 分钟、超时 2 秒） | AC6 | `TrackServiceTest`：10 分钟内第二次 0 次调用 | 批 2 |
| T3.4 | `WaybillSigned` 事件；`WxConfirmReceiveService` 改为消费事件；删 `WxConfirmReceiveJob`，作业表停用 `wx-confirm-receive` | AC4 | 签收 → 提醒一次；重复签收推送不重复提醒 | T2.9 |
| T3.5 | 删 `WxWaybillBindJob`，作业表停用 `wx-waybill-bind`；access_token 与发货上报**共用一个获取器** | AC8 | 回读作业表 | T3.2 |
| T3.6 | **c-app 发 `X-Client`**（`packages/shared` http 客户端按平台给 `MP` / `APP` / `H5`） | AC6 | H5 下 A2 返回 `self-map`；小程序返回 `wx-plugin` | — |
| T3.7 | `GET /mp/order/{orderNo}/trace`（交易域 controller，先判属主）；登记 `MpEndpointAuthTest.REQUIRES_LOGIN`、c-app `endpoints.ts` + `RESPONSE_TYPES` | AC6 | 别人的子单 → 10404；`gen:api` 绿 | T3.3 |
| T3.8 | c-app「查看物流」改调 A2；`ShipmentStatus` 类型加 `DELIVERING` `CANCELLED`；`sh-trace` 状态分支逐个处理、到柜提示 | AC6 | `vue-tsc`；真机：小程序开插件、H5 展开轨迹 | T3.7 |
| T3.9 | 部署 + 真机验证（一单从揽收到签收：token 在揽收后出现、签收后提醒到达） | — | 真机截图 | T3.1–T3.8 |

#### 批 4 · 线下付款单

| ID | 做什么 | AC | 验证 | 依赖 |
|---|---|---|---|---|
| T4.1 | **先修**：`requestSubscribe` 在两次 await 之后调用、手势失效 → 改到点击回调里同步调用 | AC5 | 真机：授权弹窗出现；订阅授权记录表里有这次授权（表名实现时按 TDD-通知与消息推送 §13 核对） | — |
| T4.2 | `NotificationConsumer` 消费 `WaybillProgressed` / `WaybillSigned`，**只对 SELF 单**发订阅消息（发货 / 派件 / 签收模板） | AC5 | WX 单不发（不重复打扰）；SELF 单发 | T4.1 批 2 |

#### 批 5 · 补偿与运营

| ID | 做什么 | AC | 验证 | 依赖 |
|---|---|---|---|---|
| T5.1 | `LogisticsCompensationJob`（`0 15`、上限 200、单号 6 小时节流、五个数 detail、`@SchedulerLock`、`JobDeclaration` 在 bridge）；**作业表打开** | AC8 | `LogisticsCompensationTest`：正常推送中的单 0 次探测 | 批 3 |
| T5.2 | 运营端：`OpsShipmentController` 移到 `portal/ops` 改调 `LogisticsAdminPort`；O1 筛选 + 数据库分页；O3 重放；O4 渠道总览；新权限码 `fulfillment:logistics:replay`；**/ops 五处登记** | AC7 | 新码三条用例（30013 / 30014 / 30015）；`ops-endpoint-exists` 绿 | 批 2 |
| T5.3 | 新 ErrorCode 30013–30015：**四处登记**（`ErrorCode` + 三语、响应格式规范 §3、`gen-glossary`、`gen-ui-spec`） | AC7 | `BackendI18nParityTest`、`spec-completeness` 绿 | — |
| T5.4 | B 端 `GET /biz/order/{subOrderNo}/trace` + b-app 订单详情展开完整轨迹；**B 端七处登记** | AC11 | 别的门店的子单 → 10404；`gen:api` 绿；`vue-tsc` | 批 2 |
| T5.5 | ops-web：运单页加筛选与重放按钮、渠道总览、承运商编码编辑；从 `displayChannel` 切到 `bindState` | AC7 AC14 | ops-web 自查（mock 与真后端各一遍） | T5.2 |
| T5.6 | 旧配置键清理（`shop.express.trace.*`、`shop.express.yto.*`）；**生产 env 同步改键名**；`withinTtl` 死代码删 | AC9 | 回读生产 env 键名；渠道总览全绿 | 批 2b |
| T5.7 | `V388` 删 `lgs_waybill` 四个旧列（先确认无读者） | — | 真库副本跑一遍 | T5.5 |
| T5.8 | `ChannelExtensibilityTest`：注册一个测试渠道，不动任何既有代码即被路由选中并收到推送 | AC14 | 消融：把渠道名写死进 Controller → 红 | T1.6 T2.7 |

#### 每批收尾（都要做）

`check-head-compiles.sh`（干净副本 + 全量）· 相关前端 `vue-tsc` · 生成物重跑（`gen:api`、`gen-test-schema.py`、改了界面就跑 `gen-ui-catalog.py`）·
`deploy-backend.sh` + 回读 · 新作业注册后**在作业表打开**、删掉的作业**在作业表停用** · 本 TDD §5 / §6 回填。

---

## §3 选型

### 3.1 模块形状

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| A 一个模块（同 `shop-inventory`） | 最简单 | 快递100、微信的 HTTP 客户端与领域逻辑混在一起；ArchitectureTest「领域不得依赖 channel」在模块内看不住 | ❌ |
| **B `logistics-domain` + `logistics-channel`，阶段 3 再加 `-svc`**（同 `pay` 实际落地的形状） | 换服务商只动 channel；与 pay 同形，守卫可照抄 | 多一个 pom | ✅ |
| C api/domain/store/channel/svc 五模块（支付域文档规划的形状） | 分层最细 | **支付域规划了七个、落地了三个** —— 规划了没人用的层只是空壳 | ❌ |

### 3.2 数据放哪

| 方案 | 结论 |
|---|---|
| **同库、`lgs_` 前缀、同事务管理器（阶段 1）** | ✅ 跨域写已经全部经 outbox（发货事件、签收事件），先把「延后」做实，再谈「隔离」 |
| 现在就独立库 / 独立事务管理器 | ❌ 支付域的教训：**先上独立事务管理器会造出孤儿数据** —— 跨域调用还没改成提交后执行时，两个事务各提交各的。顺序反了，中间态比现状更糟 |

### 3.3 运单从哪来

| 方案 | 结论 |
|---|---|
| 读时补齐（现状） | ❌ 物流要扫订单表；且补齐时用订单状态覆盖运单状态，造成 AC12 |
| 事件带全量数据 | ❌ `sys_outbox` 不清理，手机号会永久留一份明文 |
| **事件带键 + 登记时拉一次快照** | ✅ 代价是一条反向 Port，定预算 1 |

### 3.4 重试靠什么

| 方案 | 结论 |
|---|---|
| 外部动作台账表 + 补偿作业扫 | ❌ 用户要求「job 能不用就不用」；而且是在重写 outbox 已有的退避与上限 |
| **`sys_outbox` 退避重试 + 运单上的状态列** | ✅ 零新作业；FATAL 落在运单上，运营可见可重放 |

### 3.5 多渠道按什么切

| 方案 | 结论 |
|---|---|
| 按「哪一家」切：每家一个大 Provider 接口，什么都实现 | ❌ 微信给不了节点、圆通直连只覆盖一家承运商 —— 大接口里一半方法是「不支持」，调用方要逐个判 |
| **按「能力」切：订阅 / 推送 / 探测 / 展示 / 下单 各一个 SPI，每家实现自己会的** | ✅ 路由按能力各配一条链；加一家渠道 = 加几个实现类 |
| 只按承运商路由 | ❌ 2026-10-05 已定「按门店路由、默认圆通」；门店维度要保留 |
| **门店 → 承运商 → 默认 三级，每级是一条链** | ✅ 沿用现有 `LogisticsTraceRouter`；链能表达「圆通单先圆通、不行再快递100」 |
| 承运商编码按渠道加列 | ❌ 每接一家改一次表 |
| **`lgs_carrier_code` 映射表** | ✅ 加一家 = 插几行 |

### 3.6 签收以谁为准

| 方案 | 结论 |
|---|---|
| 只信快递100 推送 | ❌ 推送丢了就永远不签收 |
| 只信微信查询 | ❌ SELF 单查不了；而且需要轮询 |
| **两个来源单调合并，先到者写，后到者空操作** | ✅ 正常情况推送先到；推送沉默时补偿作业去问微信 |

---

## §4 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 快递100 订阅产品没开 / 没余额 | 批 2 整个不成立 | 批 2 第一步**用一张真运单实测**，看返回码再写代码 |
| 快递100 回调默认只支持 HTTP | 我们的回调是 HTTPS，不开通就收不到推送 | **批 2 前置：联系快递100 客服开通 HTTPS 回调**（官方文档原话：HTTPS 需联系客服） |
| 快递100 同一单号每月最多订阅 4 次 | 重放、换渠道重订阅撞上限 | 重放前看本月已订阅次数，超了直接告诉运营 |
| 快递100 回执失败只重试 3 次（每 30 分钟） | 我们发版重启窗口里的推送可能永久丢失 | 补偿作业按「24 小时无推送」去问微信；发版尽量避开整点 |
| 推送报文与文档不符 | 字段取不到、什么都没发生（和没接一样） | 第一条真推送原文整条落 WARN（同微信结算事件的做法）；按字段名找、不赌层级 |
| 回调地址收不到推送 | 全部运单沉默 | 回调用 `https://www.hxmall.top/callback/logistics/{channel}`（与微信回调同域，已验证公网可达）；补偿作业「沉默数」是第一个报警指标 |
| `RENAME TABLE` 在生产出错 | 物流全停 | 在**真库副本**上带 Flyway 跑一遍（H2 全绿证明不了迁移）；MySQL 9.7 下 RENAME 原子 |
| 在途运单在切换那一刻没有订阅 | 切换前发货的单收不到推送 | 批 2 上线后跑一次：对所有在途运单发订阅（约几十单，一次性） |
| 手机号密钥没配 | 顺丰等订阅失败、申通换 token 失败 | 启动告警；失败归入 FATAL，运营可见 |
| `WxShippingUploaded` 与揽收推送先后不定 | 只在一边判前置会漏 | 两个触发点都判 `WxBindPolicy`（§2.4.3） |
| YAML 新增 `shop.logistics` 撞已有键 | 整个上下文起不来 | 写之前 grep；`check-head-compiles.sh` 全量会抓到 |
| 圆通 IP 白名单只放了生产机 | 本机与测试环境调不通圆通，容易误判成「代码不对」 | 圆通的实测只在生产机上做；单测走样例报文 |
| 圆通凭据改键名（`shop.express.yto.*` → `channels.yto.<能力>.*`） | 生产 env 旧键静默不生效，圆通整条不可用 | 上线前回读生产 env 键名；`GET /ops/logistics/channels` 看 `yto` 各能力是否可用 |
| 换渠道重订阅后，旧渠道还在推 | 两家的节点混进同一张运单 | 推送只认 `sub_channel`（§2.4.2） |

---

## §5 对账三 · 实现 → 需求（实现后填）

| AC | 测试方法 | 跑过 | 消融验证 |
|---|---|---|---|
| AC1 | `ShipmentRegistrarTest#duplicateShippedEventSubscribesOnce`；`StatusProbeChainTest#kuaidi100QueryOffByDefault` | | |
| AC2 | `WxBindPolicyTest#notReadyBeforeUploadOrCollected` | | |
| AC3 | `PushIngestServiceTest#collectedTriggersBind` / `#notYetIndexedWaitsForNextPush` | | |
| AC4 | `WaybillStatusTest#neverGoesBackFromSigned`；`PushIngestServiceTest#signedOnceEmitsOnce` | | |
| AC5 | `ShipmentRegistrarTest#noWxKeyMeansSelfProfile`；`WxBindPolicyTest#selfNeverBinds` | | |
| AC6 | `TrackServiceTest#wxQueryCachedTenMinutes` | | |
| AC7 | `SubscribeExecutorTest#fatalCodesAreNotRetried` / `#501IsSuccess` | | |
| AC8 | `LogisticsCompensationTest#onlySilentWaybillsAreProbed` | | |
| AC9 | `LogisticsPropertiesTest#chainOrderFromConfig` | | |
| AC10 | `LogisticsBoundaryTest`（三条） | | |
| AC11 | `BizTraceEndpointTest#merchantSeesPushedNodes` | | |
| AC12 | `WaybillStatusTest#orderStatusNoLongerWritesWaybill` | | |
| AC13 | `ChannelRouterTest#ytoWaybillPrefersYto` / `#otherCarrierSkipsYto` / `#fatalFallsToNext` / `#subscribeUnavailableWithoutPush` / `#storeRouteOverridesCarrier` | | |
| AC14 | `ChannelExtensibilityTest#testChannelRoutedAndReceivesPushWithoutCodeChange` | | |

## §6 对账二 · 设计 → 实现（实现后填）

### 批 0（`ac0274dd6`，2026-10-09 上线）

与 §2.9 一致：T0.1 补齐只进不退（三条用例，修复前两条红）；T0.2 作业表 `logistics-trace` cron 改 `0 0 * * * *`
（作业表的 cron 首次写入后归运营、发版不覆盖，所以改表即可）；T0.3 门禁 3047/0 红，上线回读 health 200。

### 批 1 · 偏差说明

| 计划 | 实际 | 为什么 |
|---|---|---|
| T1.4 把老的运单 / 轨迹实现整体搬进 `logistics-domain` | **只建新模块；老实现原地不动**，三个老实体改 `@TableName` 指向 `lgs_*`（`subOrderNo` 用 `@TableField("biz_ref")`） | 老实现里的读时补齐、轮询、两个微信作业批 2 / 批 3 就要删掉，先搬再删是白干，而且共享工作区里别的会话也在改这些文件。老代码随各批被替换时删除 |
| T1.5 搬渠道代码 | 照做：8 个类 + 3 个测试 `git mv` 到 `logistics-channel/{kuaidi100,yto,stub,wx,selfmap,routing}` | 外部 0 处 import（同 pay-channel 的拆法） |
| T1.6 五个能力 SPI | 建了 `TrackingSubscriber` `PushReceiver` `StatusProbe`；**`WaybillCreator` 没建**；`TraceDisplay` 沿用 `shop-base` 现有的 | 没有实现、也没有调用方的接口是死代码；Y6 开工时再建 |
| T1.6 订阅链尾补 stub | **订阅链不补 stub** | 生产两家凭据都没配时 stub 会让订阅「成功」而什么都没订上；链走完就该 FATAL 让运营看见 |
| §2.5 「没配 `channels.<name>` 的渠道不启用」 | **默认启用**，能不能用由凭据（`available()`）决定；`enabled: false` 只当紧急关闭 | 两套开关（配置 + 凭据）会出现「凭据配了却忘了开」的静默失效 |
| §2.5 路由键 `default` | `by-default` / `by-carrier` / `by-store` | 沿用现有 `shop.express.trace` 的键名；`default` 是 Java 关键字 |
| T1.7 守卫「每个 switch 显式处理 DELIVERING」 | 不另写守卫：消费方唯一的分支点 `LogisticsServiceImpl.mapStatus` 是**不带 default 的 switch 表达式**，加枚举值即编译失败 | 编译器就是守卫，比文本扫描可靠 |
| T1.7 `DELIVERING` 直通运单状态 | 渠道层识别 `DELIVERING`（快递100 `5`/`501`、圆通 `SENT_SCAN`/`INBOUND`），**旧服务仍把它记成运输中** | 运单状态出现「派件中」要和三端前端类型一起改（批 3），否则端上收到不认识的值 |
| T1.8 新 Port | **推迟到实现它们的批次**（批 2 起） | 同 `WaybillCreator`：先建空接口是死代码 |
| §2.2 `lgs_waybill.merchant_no` | **`entity_no`** | 子单上的商家就叫 `entity_no`（主体号），同一个概念不起两个名字。回填 SQL 照 `merchant_no` 写会在生产失败而 H2 跳过 —— 写迁移时对 `schema-test.sql` 核实列名才发现 |
| §2.2 节点表唯一键 `(shipment_no, at, text_hash)` | **不加**；去重仍在代码里 | 存量可能有重复节点，加 UK 会让迁移在生产失败 |
| §2.2 运单唯一键 `(biz_type, biz_ref)` | 仍是原 `uk_shipment_sub_order`（列改名为 `biz_ref`） | 今天只有子单一种业务；接退货运单时再换 |
| — | `sub_state` 多一个取值 `NA`（登记前已签收的存量，不需要订阅） | 存量回填需要 |
| — | 批 1 上 HEAD 后全量 **2250 红**：`CarrierCodeBook` 两个构造器都没标 `@Autowired`，上下文起不来（`f27eac410` 修） | 批 1 只跑了手工 new 的单测，没起过一次上下文；加 bean 后至少跑一条 `@SpringBootTest` |

### 批 2a · 偏差说明

| 计划 | 实际 | 为什么 |
|---|---|---|
| 批 2 一次上线订阅 + 推送 + 删轮询 | **拆成 2a（代码上线、订阅总开关 `shop.logistics.subscribe-enabled` 默认关、旧轮询照跑）与 2b（P1 / P2 到位后打开）** | 快递100 默认只推 HTTP、HTTPS 要先联系客服；开通前订出去推不到，而同一单号跟踪结束前改不了订（501） |
| M1 登记一步完成（取快照 + 订阅） | **两段**：`SUB_ORDER_SHIPPED` 的消费者只登记骨架、几乎不可能失败；取快照与订阅放到物流内部事件 `LGS_WAYBILL_REGISTERED` 上 | 派发器把一条事件依次交给所有消费者、任一抛异常整条重投 —— 物流这边一失败，通知模块会把「已发货」再推一次 |
| — | 补偿作业「待订阅超过 10 分钟补订」**提前到 2a**（`logistics-compensate`，开关关着时不动） | 开关打开那一刻存量待订阅要补订；outbox 重试耗尽的也要有人捡 |
| — | 订阅累计 10 次仍不成 → FATAL | outbox 重试耗尽后补偿作业还会再推，不设上限一张一直 500 的单会被两边接力重试到永远 |
| §2.3 X1 渠道名不存在回 404 | **回 `{"result":false}` + WARN** | 全局异常处理把任何异常（含 `ResponseStatusException`）转成 HTTP 200 + 10500；域里又不许碰 Servlet 响应对象 |
| 回调返回 `ResponseEntity<String>` | **返回类型就是 `String`** | 统一信封只放过返回类型是 String 的方法，`ResponseEntity<String>` 照样被包 → ClassCastException → 渠道收到 10500（场景测试当场抓到） |
| T2.6 `YtoSubscriber` | **推迟到 2b**（等 P3 正式客户编码与订阅报文实测） | 圆通推送没调通前路由本来就会跳过它（订阅可用 ⇔ 推送可用） |
| — | **修了一个现存缺陷**：`FulfillmentStatsPortImpl.regionOf` 按空格切地址，而地址快照是连写的（「浙江省杭州市西湖区…」）—— 运营端运单列表的「地区」一直是错的 | 物流登记快照复用它，场景测试当场抓到；按行政区划后缀切，带空格的老格式仍认 |
| T1.2 反向 Port 守卫 | 两处修正：`getAllSubclasses` 对接口不返回实现类 → 改 `isAssignableTo`；包名前缀不带点时 `logisticsbridge` 被当成物流内部 | 两次都是消融抓到的（预算调成 0 照样绿）；补了扫描面断言 |

### 批 3 · 偏差说明

| 计划 | 实际 | 为什么 |
|---|---|---|
| T3.6 c-app **全局**发 `X-Client` | **只给两个读接口发**（订单详情、物流页），端点表里 `clientTag: true` 声明 | 下单接口里的积分策略也读这个头（禁用名单：没带头一律放行）。全局一发，运营配过的端策略就会突然生效 —— 那是物流之外的行为变化 |
| T3.4 签收 → 提醒确认收货 | **一个支付单的快递子单全签收了才提醒**，签收时间取最晚那张；已取消 / 已退款不挡，已完成不挡 | 微信每个支付单只给一次提醒；子单按门店拆之后一单多包裹，第一个到就提醒、买家还在等第二个。原来的定时作业同样有这个问题，事件这条路一并改对 |
| T3.4 / T3.5 删 `wx-confirm-receive` / `wx-waybill-bind` | **暂不删**，新旧并行，等 2b 订阅打开 | 新路径靠推送触发（揽收 → 换 token、签收 → 提醒）；订阅没开之前没有推送，旧作业还得兜着。两边都以 `confirm_notified_at` / `display_token` 幂等 |
| T3.5 access_token 与发货上报共用获取器 | 物流这边三处（换 token、查状态、插件展示）共用 `WxLogisticsClient`；**发货上报的 `WxShippingGateway` 仍各取各的** | 它在 shop-channel（交易域的通道），要共用就得让交易域依赖物流渠道包或反过来 —— 等阶段 3 拆服务时一起收 |
| T3.8 c-app「查看物流」改调新端点 | **先用详情里的 token 打开插件、再后台调新端点** | 打开插件发生在点击回调里，不能排在 await 后面（同 requestSubscribe 的手势问题）；新端点的作用是那一次 10 分钟一次的状态校正 |
| — | `ChannelOutcome` 多一种 `NOT_READY` | 微信 9300559（还没收录）既不是失败也不该重试 —— 等下一条推送 |
| — | ops-web 的 `ShipmentStatus` 一并加 `DELIVERING` / `CANCELLED`（类型对齐检查要求） | 两套类型系统同名枚举不许分歧 |
| — | `V388` 只改 `lgs_waybill.status` 列注释 | 枚举对账按注释认取值域；V387 已应用不能改 |
| — | `lgs_waybill` 写进「运营端读得到、有意不登记数据域」 | V387 加了归属列后数据域守卫才认出它；物流不装数据域（ADR-032），运营端收窄放批 5 |

### 批 4 · 偏差说明

| 计划 | 实际 | 为什么 |
|---|---|---|
| T4.1 修 `requestSubscribe` 手势 | 支付页把授权挪到回查订单那次 await **之前**；**另在结算页「提交订单」点击当下**问快递三个模板（仅「快递 + 当面付」） | 线下付款单根本不经过微信支付，支付页那一处管不到它；AC5「用我们自己的订阅消息」得先有授权 |
| T4.2 发货 / 派件 / 签收模板 | **三个场景三个模板**（`WxSubscribePort.SCENE_WAYBILL_*`），各自可选、字段名可配（同元器件那条） | 一次授权只够一条；共用一个模板的话揽收那条就把额度用完了。选模板是新的外部前置 **P7** |
| — | 揽收**不发站内信 / 推送**，只走订阅消息；异常（EXCEPTION）一路都不发 | 发货时 `SUB_ORDER_SHIPPED` 已经说过；疑难件多半之后又派成了 |
| — | 派件中途**放进驿站 / 快递柜**也发一次 `WaybillProgressed(DELIVERING, atLocker=true)` | 快递100 的投柜 / 驿站是派件的子状态、主状态不变 —— 不单发的话「去取件」永远到不了买家 |
| — | 物流事件加 `carrier` / `waybillNo`；新增 `SubOrderBuyerPort`（message → trade）查收件人 | 物流不认识买家（ADR-032）；通知要说哪家快递、哪个单号 |
| — | `V389`：两个场景的 INAPP / PUSH / WXSUB 种子全开 | 路由「查不到 = 关」；WXSUB 没配模板时发送端静默跳过，开着无害，关着的话配上模板那天也发不出去 |
| T4.1 验证「真机授权弹窗」 | **没做** | 模板号还没选（P7）：`requestSubscribe` 把 STUB 模板整批剔掉、不弹窗；支付页那一处要有微信支付单才走得到（支付端上 banned） |

### 批 5 · 偏差说明

| 计划 | 实际 | 为什么 |
|---|---|---|
| T5.1 单号 6 小时节流 | **节流列复用 `wx_status_checked_at`**，不加新列 | 作业默认只问微信（探测链默认 `wx`），这一列本来就是「上次问微信的时刻」，物流页 10 分钟的闸也是它 |
| T5.1 探测链 | 微信要「微信支付单 + 已换到 token」；**别的渠道要在 `probe-surfaces` 里放行 `JOB`** 才会被作业用 | 快递100 短期额度只够订阅（默认一个界面都不放行），作业每小时一轮，不能让它悄悄变成轮询 |
| T5.1 | 不包一个大事务：补订与每单探测各自一个事务；读时校正与作业共用 `WaybillProber` | 一轮最多 200 单 × 2 秒超时，包在一起是一把拿几分钟的连接 |
| T5.2 O2「旧运单作废 + 新登记一张」 | **原地换号**：订阅回待订阅、token / 签收 / 到柜清空、状态回 CREATED，轨迹记一条换号，按新号重订 | 运单唯一键在 `biz_ref` 上（V387 刻意没改），同一子单不能有第二张运单；改唯一键要等退货运单那天一起做 |
| T5.2 列表 | 不再「读时补齐」、不再从订单状态推导运单状态 | 批 2a 起发货即登记，状态只由渠道推进。旧的物化在 `logistics-trace` 作业里还跑着（每小时），2b 删 |
| T5.2 数据域 | 主应用把运营会话的商家维度换成 `entityNos` 传给物流；只配了社区 / 自提点维度 → 空集、看不到 | 运单上没有社区 / 自提点两列；与数据域引擎对「表上缺锚点」同口径（fail-closed） |
| T5.2 O4 能力 | 列 SUBSCRIBE / PUSH / PROBE / BIND，**不列 DISPLAY / CREATE** | 那两种不在物流模块的路由里（插件展示走交易域、寄件下单在 shop-channel） |
| T5.4 B2 `refresh` | 对微信放行（探测要 token、不花钱），快递100 仍默认不放行 | `probe-surfaces` 的既定口径是「没列的渠道全部放行」；API 文档「默认一个都不放行」写宽了，以代码为准 |
| T5.5 渠道总览 | 快递页工具栏一个按钮 + 抽屉，不新开菜单 | 运营端菜单在库里，新开要落迁移；它只在订阅判死时才看 |
| T5.5 承运商编码 | 只在编辑抽屉里看与改，列表不加列 | 加一列表格就挤出容器、操作列滚到看不见的地方 |

## §7 确认与完成

| 日期 | 事件 |
|---|---|
| 2026-10-09 | 草稿 |
| 2026-10-09 | 用户确认「方案可行」；补齐功能清单、API、功能模块方案、调用清单与 §2.9 开发任务 |
| 2026-10-09 | 批 0 上线（`ac0274dd6`）：补齐只进不退；`logistics-trace` 改每小时 |
| 2026-10-09 | 批 1 + 2a 上线（`5dd0de633`）：门禁 3084 / 0 红；生产 V387 成功、`ful_` 三表已改名、编码 29 行；公网 `/callback/logistics/*` 可达；订阅总开关关、旧轮询照跑。生产 env 加 `SHOP_LOGISTICS_PHONEKEY`（**不能换**）|
| 2026-10-09 | 批 3 上线（`7e04d5548`）：门禁 3096 / 0 红；生产 V388 成功（`lgs_waybill.status` 注释已含 DELIVERING / CANCELLED）；运行中 jar 含 `/mp/order/{orderNo}/trace` 与 `wxbind`。**T3.9 真机一单走到签收没做**：订阅总开关关着（没有推送就没有揽收 / 签收事件），且微信支付端上 banned、眼下没有微信支付单 —— 换 token、签收提醒两条路要等 2b 打开、且有了微信支付单才走得到 |
| 待 | 2b：等 P1（快递100 HTTPS 回调）、P2（订阅余额）；圆通等 P3 / P4 |

---

## L4 边界

### 待决

| # | 问题 | 卡住谁 |
|---|---|---|
| 1 | B 端「立即刷新」要不要允许现查快递100（默认不允许，配置可开） | 批 5 的 B 端端点行为 |
| 2 | 运费模板什么时候搬进来：它在下单的同步路径上，阶段 3 拆出去后每次算运费多一跳网络 | 阶段 3 之前要定 |
| 3 | 快递代下单什么时候搬进来：它与商家欠款、结算判「运费谁付」耦合 | 同上 |
| 4 | 售后寄回的运单（`biz_type=RETURN`）要不要也订阅 | 表结构已留口，业务未提 |
| 5 | **平台直连下单**（圆通订单创建 Y6、快递100 寄件）何时收进 `WaybillCreator`：运单号三个来源（直连下单 / 网点回传 / 商家自填）今天分散在交易域与运营端 | Y6 开工前要定；接口这期已定义 |
| 6 | 商家自填单号的**存在性校验**（[TDD-圆通物流直连 §10](design/TDD-圆通物流直连.md) 路径 C）：快递100 查询默认关，只能等圆通查询审核通过后对圆通单做 | 圆通查询审核 |
| 7 | 门店路由从 yml 挪到门店设置（运营 / 商家可配、运行时切） | 门店数多起来之后 |

### 取舍记录

- **不新建外部动作台账表**：每单只有两件外部动作，列比表便宜；重试复用 outbox。若以后外部动作超过四五种，再抽表。
- **订单详情不再触发任何外部调用**，只有物流页会 —— 详情页的打开次数是物流页的数倍，读时校正放在详情页等于把 10 分钟缓存的意义打折。
- **微信能力分属两个模块**：查询插件（`trace_waybill`、`query_trace`）进物流；发货上报与确认收货提醒（`upload_shipping_info`、`notify_confirm_receive`）留交易域 —— 后者是支付合规，自提和虚拟商品也要报，与运单无关。
- **无忧退货、同城配送不做**（用户 2026-10-09 定；无忧退货微信已于 2025-09-15 停止新接入）。
