# TDD-物流轨迹（圆通先行，多方式并存）

状态：部分已实现（Y1–Y4、Y3.1 已落地；Y3.2 等凭据）· 2026-10-08 按官方文档重校
关联：`ExpressPickupPort`（寄件的 provider 抽象，参照但不复用）· `ExpressCompanies`（承运商码，圆通=YTO）·
`WxShippingUploadService`（微信发货上报，另一条，不动）· `PlaceResolver`（cache-aside + 后台刷的样板）

> **一句话**：发货填的运单号，**按门店路由**到对应的物流 provider 查轨迹（门店级物流路径，**默认圆通**），缓存后在 C 端/B 端订单详情显示「到哪了」。
> **圆通直连是第一个 provider**；架构从一开始就为**多方式并存**留好口子（将来快递100 聚合、其它直连都只是再加一个 provider）。

---

## 0 为什么是「多 provider」而不是照寄件那样二选一

现有**寄件**（`ExpressPickupPort`）是 `@ConditionalOnProperty stub=false` 在 **Stub / 快递100 之间二选一** ——
同一时刻只有一个活着。而你的要求是**并存**：圆通的单走圆通直连、别家的单将来走快递100 或各自直连。
二选一满足不了，所以轨迹这条用**全部注册 + 按承运商路由**。

> 另：**快递100 目前连寄件都还是 stub（没真接通）**，所以**圆通是平台第一个真·物流连接**。轨迹与寄件是两件事，这份只做轨迹。

## 1 前置（要你先办，Y2 上线那刻才卡）

圆通开放平台（open.yto.net.cn）：注册 → 申请开发者 → 开通**「物流轨迹查询」**接口 →
拿 **客户编码（appKey）+ 客户密钥（secret）** → 把**生产服务器出口 IP 加白名单**（圆通：签名 + IP 白名单双保险）。
凭据只进服务器 env（`YTO_APP_KEY`/`YTO_SECRET`），**不进仓库**（同快递100/微信）。

## 2 现状（查过）

- 圆通在码表里（`ExpressCompanies` YTO），发货选得到；**没有任何轨迹查询**。
- 快递100 只接了**寄件**且是 stub（没接通）；不查轨迹。
- 微信发货上报（报承运商+单号给微信、微信自己推物流卡）**不动** —— 交易组件要求，与自展示轨迹两回事。

## 3 圆通接口（2026-10-08 逐页读过官方文档后重写本节）

- **查轨迹**：`{timestamp, param(JSON), sign, format:"JSON"}` + `method`/`v`；`param` 带 `Number`=运单号，一次一个。✅ 与实现一致
- **签名**：`Base64(MD5bytes(param + method + v + 客户密钥))`。✅ 与实现一致
- **`method` / `v` 是账号级的**：文档写明「通过 控制台——接口管理，添加所需接口，即可得到相应的测试地址、客户编码、客户密钥、方法和版本」。
  **每个接口各一组**，代码里的 `TRACE_QUERY`/`1.0` 只是占位默认值，上线前必须按控制台实际值填。

### 3.1 ⚠️ 返回结构（此前整节是错的，已按官方【成功/查询为空返回格式】改写）

**查到时顶层直接是数组，按时间正序（最早在前）**：

```json
[{"waybill_No":"YT2000000000000","upload_Time":"2023-04-24 20:37:33","infoContent":"GOT",
  "processInfo":"您的快件被【浙江省金华市义乌市上溪镇】揽收","city":"金华市","district":"义乌市","weight":0.68}, …]
```

**查不到时是另一种结构**（对象、不是数组，`success` 还是字符串）：

```json
{"map":{"YT2600205450611":[]},"code":"1001","success":"true","message":"查询结果为空。"}
```

`infoContent` 官方固定取值：`GOT` 已揽收 / `ARRIVAL` 已收入 / `DEPARTURE` 已发出 / `SENT_SCAN` 派件 /
`INBOUND` 自提柜入柜 / `SIGNED` 签收成功 / `FAILED` 签收失败 / `FORWARDING` 转寄 /
`TMS_RETURN` 退回 / `AIRSEND` 航空发货 / `AIRPICK` 航空提货。

### 3.2 ⚠️ 更正：圆通**有**订阅与推送

原文写「无订阅推送（公开文档未给）→ 轮询」，**是错的**。接口文档「物流轨迹」下有三个：
**物流轨迹推送服务 / 物流轨迹查询接口 / 物流轨迹订阅接口**。
轮询（30 分钟一轮）因此只是过渡；**Y5 应改订阅+推送**：实时、且省额度。

### 3.3 其余可用接口（eSeller 方案页「电商类——仓库发货，指定网点揽件」正是本项目的模式）

- **寄件服务**：订单创建 / 订单取消 / 订单修改 / 散单创建 / 散单取消
  - 订单创建返回 **`mailNo`（圆通运单号）+ `shortAddress`（三段码 `800-061-00-005`）+ `secretWaybills[]`（已脱敏面单字段）**；
    入参 `logisticsNo`（我方订单号，可当幂等键）+ `sender*`/`recipient*` + `OrderGoodsDto` + `RealNameInfo`。
  - 失败体：`{"success":false,"code":200010003,"reason":"logisticsNo不能为空; "}`
- **基础服务**：标准运价查询 / **地址是否可达查询** / 电子面单余额查询 / 面单打印
- **工单服务**：拦截件推送/更址/退回/取消

## 4 架构：provider + 路由 + 缓存

```
C端/B端订单详情 ──读──> trd_logistics_trace（缓存：一单一行，最新轨迹+状态+polled_at+signed）
                              ▲
              轮询 Job ───写──┘  只查「在途」的单
                 │
                 └──> TraceRouter ──按门店选──> TraceProvider（多个，全部注册）
                                                   ├─ YtoTraceProvider     covers {YTO}    ← 本期
                                                   ├─ StubTraceProvider    covers {*}      ← 兜底/开发
                                                   └─ (将来) Kuaidi100TraceProvider covers {多家}
```

### 4.1 Provider 抽象（spi）
```java
public interface TraceProvider {
    String name();                 // "yto" / "kuaidi100" / "stub"
    java.util.Set<String> carriers();   // 能查的承运商码；聚合器返回全集
    boolean available();           // 配了凭据/能用（stub 恒 true）
    java.util.Optional<TraceResult> trace(String carrier, String waybillNo);
}
```
- 每个 provider 一个 `@Component`，**全部注册**（不再 @ConditionalOnProperty 二选一）。
- `YtoTraceProvider`（shop-channel，仿 Kuaidi100 网关的签名/HttpClient/env 凭据）：`carriers()={YTO}`；
  缺凭据时 `available()=false`（不抛，让路由回落），而不是启动失败 —— 并存场景里一个 provider 没配不该拖垮全局。

### 4.2 路由（**按门店**，可配，支持并存与覆盖）

**按门店切换物流路径，默认圆通**（2026-10-05 定）：不同门店可走不同 provider。
```yaml
shop.express.trace:
  store-route:          # 门店号 → provider。没列的门店用 default-provider
    ST-xxx: kuaidi100   # 这家店走快递100聚合
  default-provider: yto   # 默认圆通直连（生产）；Y1 开发期是 stub（空轨迹不白屏）
```
```java
Optional<TraceResult> trace(String storeNo, String carrier, String waybill) {
    String name = storeRoute.getOrDefault(storeNo, defaultProvider);   // 门店没配 → 默认（圆通）
    TraceProvider p = byName.get(name);
    if (p == null || !p.available() || !p.covers(carrier)) return Optional.empty();  // 回落，不白屏
    return p.trace(carrier, waybill);
}
```
- **并存就在这张表里**：A 店圆通直连、B 店聚合、其余默认圆通。加 provider + 加一行门店路由即可，调用方不改。
- **覆盖**：门店路由表里写了谁就用谁，不靠 bean 注册顺序（那种顺序依赖最难查）。
- **门店级配置本期走 yml**；将来挪到**门店设置**（运营/商家可配、运行时切），同 `mch_fulfillment_channel` 那种店级配置。

### 4.3 缓存与轮询
- **`LogisticsTracePort`**（spi）：trade/core 查轨迹走它，不直连 channel（ArchUnit 守跨域只走 Port）。
- **`trd_logistics_trace`**（迁移）：`waybill_no`+`carrier`(uk) / `order_no` / `sub_order_no` / `status` / `nodes`(JSON) / `provider` / `polled_at` / `signed`。
  —— 不每次开详情都打圆通（有频控 + IP 白名单额度）；cache-aside：命中新鲜直接给，过期后台刷（照 `PlaceResolver`）。
- **轮询 Job**（`job` 模块）：扫**在途单**（有运单号、未签收、发货在 N 天内），批量经 Router 查、落缓存；签收/超期移出轮询。
  —— provider 无关：Job 只认「在途单」，每条按承运商路由，天然覆盖并存。

### 4.4 端点
优先订单详情内嵌轨迹（读缓存），不新开端点；要「手动刷新」再加
`GET /mp/order/{no}/trace`（买家）/ `GET /biz/order/{no}/trace`（商家），先读缓存、过期触发一次后台刷。

## 5 状态映射
承运商各自的状态 → 端上统一：已揽收 / 运输中 / 已签收 / 异常。**映射在各 provider 内做**（圆通 GOT→已揽收…），
Port 对外只给统一状态 —— 端上永不见承运商原始码（与收件人脱敏同一条教训：口径散出去就分叉）。

## 6 分期（每步独立上线）

| 批 | 内容 | 要凭据？ |
|---|---|---|
| **Y1** ✅ | `TraceProvider`/`LogisticsTracePort`(spi) + `LogisticsTraceRouter`（**按门店**路由/回落）+ `StubTraceProvider` + 路由配置，单测 4+消融 | 否（stub） |
| **Y2** ✅ | `YtoTraceProvider`（签名/HttpClient）+ 路由配 YTO→yto，单测（签名对、stub 可测） | 否（签名/映射用 stub 测；真查要你凭据） |
| **Y3** ✅ | ~~`trd_logistics_trace`~~ **复用 `ful_shipment`/`ful_shipment_trace`** + 轮询 Job（在途→真轨迹，按门店路由） | 真查**是**（圆通凭据+IP 白名单）；代码/测试走 stub |
| **Y3.1** ✅ | **按官方文档校准 `parse()`/`mapStatus`**（见上）+ `post()` 线格式用例 | 否 |
| **Y4** ✅ | C 端 / B 端订单详情显示轨迹（`order.trace.nodes`，两端都已渲染） | 否（读缓存） |
| **Y3.2** | env 配齐 + 白名单 `106.55.27.246` + 用探针对**一条真实响应**复核 Y3.1 | **是**：凭据 + 白名单 + 一个真运单号 |
| **Y6** | **订单创建接口**（路径 A，见 §10）：取 `mailNo`+三段码、下单前查「地址是否可达」 | 月结账号/网点 |
| **Y7** | 路径 B「待网点回传」状态与超时提醒；路径 C 发货页单号**存在性校验** | Y3.2 |
| **Y5** | 轨迹**订阅+推送**回调，轮询降为兜底（见 §3.2） | 推送权限 |

> Y1–Y2 不要凭据就能做完（架构 + 圆通 provider + 测试全走 stub/样例签名）。真正等你圆通账号的是 **Y3.2** 那一刻。

## 10 运单号的三个来源（2026-10-08 需求澄清）

轨迹链路只认运单号，所以三条路径**只做一件事：把运单号拿到手**，拿到之后共用同一条链路。

```
路径A 平台直连下单 ──┐
路径B 合作网点回传 ──┼──→ ful_shipment.waybill_no ──→ 订阅/查询轨迹 ──→ C端 & B端订单详情
路径C 商家自己填单 ──┘
```

| 路径 | 怎么拿到运单号 | 复用什么 | 产品交互要点 |
|---|---|---|---|
| **A 平台直连** | 调圆通**订单创建接口**，返回 `mailNo` + 三段码 | 照 `ExpressPickupServiceImpl`（Port + 带签名回调）的结构写 | 下单前先查**地址是否可达**，不可达当场拦住；失败**原样显示圆通的 `reason`**，别吞成「下单失败」；`logisticsNo` 用子订单号做幂等，重试不产生第二个运单号 |
| **B 网点回传** | 网点把订单号+运单号回传，运营端录入 | **已有 `POST /ops/shipments/{shipmentNo}/waybill`** | 需新增**「待网点回传」状态 + 超时提醒** —— 网点不回传＝发不出去，这是这条路唯一会烂掉的地方 |
| **C 商家自填** | 通知商家发货，商家在 B 端填单号 | b-app 订单详情发货入口 | **提交时立刻查一次轨迹做存在性校验**，查不到就提示「单号可能填错」。错单号一旦入库，买家会盯着一条永远不动的物流 |

共同约定：轨迹**倒置渲染**（最新在上）；文案直接用 `processInfo`（圆通给的人话，与官网一致），不要自己编。

**Y2 实现对账（2026-10-05）** —— `git diff --stat`：
- 新增 `shop-channel/.../express/trace/YtoTraceProvider.java`：`@Component implements TraceProvider`，`name()=yto`、`covers()=YTO`、`available()=凭据非空（缺则 false 不抛，路由回落）`；签名 `Base64(MD5bytes(param+method+v+密钥))`（**与快递100 的十六进制大写不是一套**）、状态映射、响应解析都是 `static` 可测方法。
- 新增 `YtoTraceProviderTest.java`：5 用例（签名=Base64 非十六进制且解出 16 字节、签名确定性、状态映射、解析倒序取最新、空轨迹=UNKNOWN）。**消融**：签名改十六进制 → 1 红。
- 改 `shop-app/.../application.yml`：加 `shop.express.yto.{app-key,secret,host,trace-method,trace-version}`，全部 env 兜底、默认空。
- **路由接 yto 不需改码**：`YtoTraceProvider` 作为 `@Component` 自动进 `List<TraceProvider>`，把 `SHOP_EXPRESS_TRACE_DEFAULT=yto`（或 `store-route` 单店指 yto）即生效。
- ⚠️ 响应字段名（`result.traces[].{opCode,opTime,opName,city}`）按文档写，**Y3 拿到真账号对一条真实响应校准**，校准点集中在 `parse()` 一处。
  - **→ 2026-10-08 Y3.1 已校准，结论是「无一字相符」**，见下方 Y3.1。那几个字段名不是「可能不同」，是**全错**。

**Y3.1 按官方文档校准 `parse()`（2026-10-08）** —— 不需要凭据，照文档即可：

| 原实现 | 官方实际 | 不改的后果 |
|---|---|---|
| `root.result.traces[]` | **顶层就是数组**；查不到时另有 `{"map":{…}}` 结构 | 解出来恒为空 |
| `opCode` / `opTime` / `opName` | **`infoContent`** / **`upload_Time`** / **`processInfo`** | 状态恒 UNKNOWN、时刻恒 0、文案全空 |
| `RETURN`/`REJECT`/`RETENTION` 当异常 | 圆通没有这三个码，退回是 **`TMS_RETURN`** | 退回被当成「运输中」 |

- `parse()` 改为两种结构都认；节点仍按时刻倒排（端上要最新在前，而圆通给的是正序）。
- `location` 取 `city`（与 `TraceNode` 的「城市/网点」同义），空则退 `district`。
- 测试：`YtoTraceProviderTest` 6 条（新增官方数组结构、空结果结构、官方状态码全集；线格式那条的假响应同步换成真结构）。
  **消融**：把结构读法退回 `result.traces` + 把 `TMS_RETURN` 退回 `RETURN` → **3 条红**
  （`TMS_RETURN` 变 IN_TRANSIT、数组结构解出 0 条、整链路 status 变 UNKNOWN），已还原。
- ⚠️ **仍待真实响应复核**：以上全部依据官方文档样例，尚未对真账号的一条真响应验过（缺凭据+白名单+真单号）。
  探针已备好（`scratchpad/yto-probe.py`，服务器上实跑验证过），凭据一到即可复核。

**Y3 实现对账（2026-10-05）——⚠️ 对设计的重大偏差，先看这里：**

设计原定**新建 `trd_logistics_trace`**。落地时发现仓库里早有一对现成表
`ful_shipment`（运单）+ `ful_shipment_trace`（轨迹节点，V132 建，TDD-快递100商家寄件 §9），
状态模型 `CREATED/PICKED_UP/IN_TRANSIT/DELIVERED/EXCEPTION`、`carrier` 含 YTO ——
正是缓存该有的样子，运营端运单列表也在用。**再建一张就是同一个东西的第三份实现。**
故改为**复用这对表**：

- **不新建表、不加迁移** → 撞号风险归零，也不再是第三份实现。
- 新增 `FulfillmentStatsPort.storesOf(子单号)`（`ful_shipment` 没存门店，轮询要按门店路由回子单取）。
- `LogisticsService.refreshInTransitTraces(limit)`：扫在途 `ful_shipment`（含 `EXCEPTION`，它不是终态）、
  按门店经 `LogisticsTracePort` 查、真实节点**追加**进 `ful_shipment_trace`（按时刻+文案去重）、据签收推进状态。
  缺凭据查不到时**不编造推进**（守住 ADR-005 §5「编假轨迹比没有更糟」那条原则）。
- 新增 worker 任务 `LogisticsTracePollingJob`（30 分钟一轮，`shop.job.enabled` 下才装）。
- 单测 `LogisticsTraceRefreshTest` 4 例（追加+推进、去重、缺 provider 不写、UNKNOWN 不动），**消融**：
  `SIGNED→DELIVERED` 改掉 → 1 红。

**与 ADR-005 §5 的关系**：那条记的是「一期只做快递+商家自送、不接承运商**回调/骑手系统**」。
Y3 接的是**轨迹查询**这一半（轮询、只读、不编推进、不是骑手系统），方向与 ADR 的顾虑不冲突；
真正的回调推送仍留给你账号能开订阅推送那天（§9）。

**顺带修 Y1 的一处**：`LogisticsTraceRouter` 实现了 `LogisticsTracePort` 却不在 `..port..` 包，
`ArchitectureTest.implsMustLiveInDedicatedPackage` 会红（Y1 提交在上次全量闸门之后，没被跑到）。
已挪进 `channel.express.trace.port`。

## 7 将来怎么并入别的方式（本架构的验收）
- **某店改走快递100 聚合**：加 `Kuaidi100TraceProvider` + 在 `store-route` 给那家店配 `kuaidi100`；其余门店仍默认圆通。
- **再直连某家（如顺丰）**：加 `SfTraceProvider` + 给用它的门店配 `sf-direct`。
- 调用方（订单详情、轮询 Job、Port）**一行都不用改** —— 这就是「多方式并存」落在架构上的样子。

## 8 测试
- ★ 路由：门店配了→该 provider、没配的门店→default（圆通）、available()=false→回落空。消融：去掉回落 → 没配凭据时白屏/500。
- ★ 圆通签名：固定入参→固定 sign（对圆通样例核）。消融：改拼接顺序 → 红。
- ★ 状态映射：各 provider 的原始码→统一状态。消融：改映射 → 红。
- ★ 缓存 cache-aside：同单第二次不打 provider；签收单不再轮询；在途非圆通单按路由走对 provider。
- provider 不可用/超时：吞掉、不挡订单详情（轨迹段空着，不白屏）。

## 9 不做 / 岔口
- **不做电子面单下单**：发货仍是商家手填运单号。圆通直接下单是另一个方案。
- **轮询 vs 推送**：圆通公开文档只有查询。你账号若能开**轨迹订阅推送**，Y3 改回调（更省额度、更实时），接法同 Kuaidi100 回调控制器。
- **寄件（快递100）**：是另一条线（没接通），这份不碰；将来接通了它与轨迹各管各的。
- **频控/额度**：圆通按客户编码限频 + IP 白名单，轮询间隔/批量按你账号额度调（配置项，不写死）。

## 11 订单创建接口契约（2026-10-08 逐字抄自官方文档，Y6 据此实现，**不要再照骨架猜**）

> 上一次「照文档猜字段」的代价见 §3.1 —— 整节全错且不报错。这一节是把真表抄下来。

### 11.1 请求 `param` = OrderIncrementDto

| 字段 | 必填 | 类型 | 长度 | 说明 |
|---|---|---|---|---|
| `logisticsNo` | Y | String | 64 | 物流单号；与渠道唯一确定一笔订单。**最低长度 7** —— 拿子订单号当幂等键时要保证够长 |
| `senderName` / `senderProvinceName` / `senderCityName` / `senderAddress` / `senderMobile` | Y | String | 96/96/96/768/32 | 寄件人（固定仓库/合作网点那套） |
| `senderCountyName` / `senderTownName` | N | String | 96/200 | 区县 / 乡镇 |
| `recipientName` / `recipientProvinceName` / `recipientCityName` / `recipientAddress` / `recipientMobile` | Y | String | 96/96/96/768/32 | 收件人（**要真实地址，不能传脱敏值**） |
| `recipientCountyName` / `recipientTownName` | N | String | 96/200 | |
| `goods` | N | Set\<OrderGoodsDto\> | | 物品列表，**最多 20 个** |
| `startTime` / `endTime` | N | Date | | 预约上门取件时间窗，`yyyy-MM-dd HH:mm:ss`；**规则：下单当天 00:00:00 ～ 下单当天+6 天 23:59:59** |
| `cstBusinessType` | N | String | 45 | 客户业务类型（可用来区分渠道） |
| `cstOrderNo` | N | String | 100 | 客户的订单号 |
| `realNameInfo` | **N** | RealNameInfo | | 实名信息 —— **非必填**（此前列为「待确认」，已确认） |
| `weight` | N | BigDecimal | (11,3) | 下单总重量，千克 |
| `productCode` | N | String | 32 | `YZD` 圆准达 / `XTCTK` 同城特快 / `HKJ` 航空件 / `PK` 普快，**默认 PK** |

`OrderGoodsDto`：`name`(Y,450) · `weight`/`length`/`width`/`height`/`price`(N, BigDecimal(11,3)，米/千克/元) · `quantity`(N, Integer)

`RealNameInfo`：含 `cerType` 证件类型（11 居民身份证 / 12 临时居民身份证 / 13 户口簿 / … / 101 机构代码 / 102 税务登记号 / 103 统一社会信用代码）等。

### 11.2 返回

| 字段 | 必填 | 类型 | 说明 |
|---|---|---|---|
| `customerCode` | Y | String | 客户编码（K 开头） |
| `logisticsNo` | Y | String | 回传我方物流单号 |
| **`mailNo`** | **N** | String | **运单号** |
| `shortAddress` | N | String | 三段码，如 `800-061-00-005` |
| `secretWaybills` | N | List\<SecretWaybillRo\> | 面单打印用的脱敏字段表：`code` / `name`(描述) / `value`(脱敏值，如 `159****1555`、`测*`) |

### 11.3 ⚠️ 两个容易埋雷的点

1. **`mailNo` 是「非必填」—— 下单成功 ≠ 拿到运单号。**
   代码必须处理「`success` 为真但 `mailNo` 为空」：此时不能把订单标成已发货，要留「待取号」态后续补取，
   否则会出现一张没有运单号的"已发货"单，而买家那边永远查不到轨迹。
2. **失败体的 `code` 是 `Long`，而轨迹接口查空时的 `code` 是字符串 `"1001"` —— 两个接口不一致**，
   别共用一个解析器（这正是 §3.1 那类错误的温床）。

失败体：`{"success":false(Boolean), "code":<Long>, "reason":"<描述>"}`

### 11.4 失败码与**是否可重试**（官方表，重试策略照这一列写，别一律重试）

| 编码 | 信息 | 可重试 |
|---|---|---|
| 200010002 | 系统其它错误信息 | **是** |
| 200010003 | 入参不规范等错误信息 | 否 |
| 200010005 | 重复下单，订单处理中 | **是** |
| 200010013 | 电子面单拉单失败，请重试 | 否 |
| 200017004 | 订单报文不合法，校验不通过 | 否 |
| 200017005 | 系统异常，拉单失败，请联系圆通开放平台技术支持 | —— |

> `200010003` / `200017004` 这类重试多少次都没用，要把 `reason` **原样显示给人**并停止重试；
> `200010005 重复下单` 标为可重试，说明圆通侧按 `logisticsNo` 做了幂等 —— 我方重试要复用同一个 `logisticsNo`。

### 11.5 Y6 的接线：**复用 `ExpressPickupPort`，但要先补两处契约缺口**

先复用再新建 —— spi 里已有 `ExpressPickupPort`（`enabled` / `quote` / `create(CreateCmd)→Booked` /
`cancel` / `parseCallback`），实现有 `Kuaidi100PickupGateway` 与 `StubExpressPickupGateway`。
`Booked.message()` 的注释写着「通道原话」，正好对上「失败要原样显示圆通的 `reason`」。
**圆通版应该是它的第三个实现，不是另起一套抽象。**

但直接复用会卡在两处，Y6 开工前必须先定：

| 缺口 | 现状 | 圆通要什么 | 不补的后果 |
|---|---|---|---|
| **地址粒度** | `Party(name, mobile, address)` —— 地址是**一个扁平串** | `senderProvinceName`/`CityName`/`CountyName` + `senderAddress` **分开且省市必填**（收件人同） | 只能在圆通实现里**拆地址猜省市区**；拆错 → 报文校验不过（`200017004`，且不可重试） |
| **面单信息无处安放** | `Booked(ok, taskId, orderId, trackingNo, message)` | 还会返回 `shortAddress`（三段码）与 `secretWaybills[]`（脱敏面单字段） | 三段码丢掉 = 打不了面单，而 eSeller 模式要求我方自行打印贴标 |

两个选项：

- **A（推荐）扩展 spi**：`Party` 加 `provinceName/cityName/countyName/townName`（可空，老实现不传＝行为不变）；
  `Booked` 加 `shortAddress` 与 `secretWaybills`（可空）。动的是 spi 契约，要同步 `Kuaidi100PickupGateway`
  与 `StubExpressPickupGateway` 两个实现（都只是多传/多填几个空字段）。
- **B 不动 spi**：圆通实现内部拆地址、三段码另找地方存。**不建议** —— 拆地址是猜，
  而 `200017004 订单报文不合法` 恰好是**不可重试**的那一类，错了只能人工介入。

> `mailNo` 非必填这条（§11.3）映射到 `Booked`：允许 `ok=true` 且 `trackingNo=null`，
> 调用方据此进「待取号」，**不能当成已发货**。
