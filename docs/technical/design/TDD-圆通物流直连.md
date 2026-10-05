# TDD-物流轨迹（圆通先行，多方式并存）

状态：方案（2026-10-05）
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

## 3 圆通接口（查证过，实现对官方文档校准）

- **查轨迹**：`{timestamp, param(JSON), sign, format:"JSON"}` + `method`/`v`；`param` 带 `Number`=运单号，一次一个。
- **签名**：`MD5(param + method + v + 客户密钥)` → Base64。
- **返回**：运单号、扫描时间、状态（`GOT`揽收/`DEPARTURE`发出/`ARRIVAL`到达/`SIGNED`签收…）、处理信息、城市区县、重量。
- **无订阅推送**（公开文档未给）→ 轮询。你账号若能开推送，Y2 改回调更省额度（见 §9）。

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
| **Y4** | C 端 / B 端订单详情显示轨迹 | 否（读缓存） |

> Y1–Y2 不要凭据就能做完（架构 + 圆通 provider + 测试全走 stub/样例签名）。真正等你圆通账号的是 **Y3 上生产**那一刻。

**Y2 实现对账（2026-10-05）** —— `git diff --stat`：
- 新增 `shop-channel/.../express/trace/YtoTraceProvider.java`：`@Component implements TraceProvider`，`name()=yto`、`covers()=YTO`、`available()=凭据非空（缺则 false 不抛，路由回落）`；签名 `Base64(MD5bytes(param+method+v+密钥))`（**与快递100 的十六进制大写不是一套**）、状态映射、响应解析都是 `static` 可测方法。
- 新增 `YtoTraceProviderTest.java`：5 用例（签名=Base64 非十六进制且解出 16 字节、签名确定性、状态映射、解析倒序取最新、空轨迹=UNKNOWN）。**消融**：签名改十六进制 → 1 红。
- 改 `shop-app/.../application.yml`：加 `shop.express.yto.{app-key,secret,host,trace-method,trace-version}`，全部 env 兜底、默认空。
- **路由接 yto 不需改码**：`YtoTraceProvider` 作为 `@Component` 自动进 `List<TraceProvider>`，把 `SHOP_EXPRESS_TRACE_DEFAULT=yto`（或 `store-route` 单店指 yto）即生效。
- ⚠️ 响应字段名（`result.traces[].{opCode,opTime,opName,city}`）按文档写，**Y3 拿到真账号对一条真实响应校准**，校准点集中在 `parse()` 一处。

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
