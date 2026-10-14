# TDD-物流轨迹多渠道

状态：实现中
关联：`TDD-快递100轨迹查询.md`（数据源这一轴的第一个真实 provider）、`TDD-快递100商家寄件.md`（同一个快递100 账号）
原型：`prototypes/logistics-trace.html`
创建：2026-10-08

## §0 对账一 · 需求 → 设计

需求原文（用户，2026-10-08）：「还要增加地图轨迹，同时完善小程序端的展示，如果微信的插件免费使用，优先使用微信的插件展示轨迹」
→ 「微信的部分，已经开通查询组件和物流消息功能……给出完整的方案，同时可以做到两种方案的切换」
→ 「1，支付也已经通了，系统里有支付订单号 2，物流要支持多渠道，不只是双渠道」

| AC | 一句话 | 落点 |
|---|---|---|
| AC1 | 买家在小程序里能看到物流：有微信支付单号的走微信官方物流页，其余走自建（地图 + 时间线） | 展示渠道链 `mp: [wx-plugin, self-map]` |
| AC2 | 自建展示有城市级地图轨迹：起点 → 中转 → 当前/终点的折线与标记 | 快递100 `resultv2=4` 的 `areaCenter` + 小程序原生 `<map>` |
| AC3 | 自建展示有状态步骤条、最新一条加重、长轨迹折叠、单号可复制、快递员电话可拨 | c-app / b-app 共用展示件 |
| AC4 | B 端 App 与运营端也能看轨迹（它们用不了微信插件） | 展示渠道链 `app`/`ops: [self-map]` |
| AC5 | **数据源可多渠道**：圆通单优先走直连、不可用回退快递100；将来加快递鸟/菜鸟只加一个实现类加一行配置 | 数据源优先级链 + 回退 |
| AC6 | **展示也可多渠道**：将来加第三个展示渠道，调用方与端上一行不改 | `TraceDisplay` 注册表 + 优先级链 |
| AC7 | 任何一环失败（微信超配额、运单不存在、数据源查不到）自动落到链上下一个，并记下原因；全失败时这一块不显示，不白屏 | 两条链共用同一套回退语义 |

**孤立项**：无。

## §1 现状与查到的约束

### 仓库现状
- 数据源这一轴**已经是注册表**：`TraceProvider`（`name/covers/available/trace`）+ `LogisticsTraceRouter`。已注册 `stub`、`yto`、`kuaidi100`。
  但路由是「按门店挑一个，挑中谁就是谁，不可用就空着」—— **没有回退链**。
- 轮询：`LogisticsTracePollingJob` 每 30 分钟、每轮 ≤300 单 → `refreshInTransitTraces` → 节点按（时刻+文案）去重追加进 `ful_shipment_trace`。
- c-app 订单页已有纯文字时间线；b-app、ops-web 各有一份；三处各写各的。

### 外部约束（2026-10-08 实测，不是推测）
| 事实 | 怎么知道的 |
|---|---|
| 微信查询组件、物流消息**已开通** | 同一调用从 `48001 api unauthorized` 变成参数错误（`waybill_token参数错误`/`openid错误`） |
| `trace_waybill` 的 **`trans_id` 必填**，要微信支付交易单号 | 官方接口文档 |
| 线上有 4 笔微信支付成功单，交易号 `4500` 开头 28 位 | 生产库 `stl_payment` |
| 微信 `query_trace` **不返回轨迹明细**，只给状态（0–6）+ 承运商 + 商品 | 官方接口文档 |
| 插件 `openWaybillTracking` **只在小程序内**可用 | 官方文档 |
| 快递100 `resultv2=4` 才给 `areaCenter`（经纬度）、`routeInfo`（出发/当前/目的）、`statusCode` | 快递100 文档 |
| 快递100 的 `areaCenter` 是**行政区中心点**，不是快件 GPS | 同上；所以地图只能画城市级 |
| 快递100 同一单查询间隔 ≥30 分钟、24 小时 ≤48 次，否则锁单 | 同上；轮询本来就是 30 分钟一轮 |

## §2 方案

### 2.1 两条正交的渠道轴

**轴一 · 数据源**（谁去查轨迹）：沿用 `TraceProvider`，把路由升级成**优先级链 + 回退**。
**轴二 · 展示渠道**（用什么界面呈现）：新增 `TraceDisplay` 注册表，同样是优先级链 + 回退。

两轴**互不知道对方存在**：微信插件展示时不需要我们查轨迹（微信自己查），自建展示时不关心轨迹是谁查来的。

```yaml
shop.express.trace:
  source:                       # 轴一：数据源优先级链
    default: [kuaidi100]
    by-carrier:
      YTO: [yto, kuaidi100]     # 圆通优先直连，不可用/查不到回退聚合
    by-store:
      ST-xxx: [kuaidi100]       # 门店级覆盖（优先级高于 by-carrier）
  display:                      # 轴二：展示渠道优先级链，按端
    mp:  [wx-plugin, self-map]
    app: [self-map]
    ops: [self-map]
```

**不给 Map 字段挂 `${ENV}` 占位**：空串绑不进 Map 会让整个 context 起不来（已踩过，见 `configprops-map-empty-env-crashes-context`）。用字段默认值。

### 2.2 轴一：数据源链

`TraceSourceRouter`（即现有 `LogisticsTraceRouter` 升级）：
1. 按 `by-store` → `by-carrier` → `default` 取出链（第一个命中的键胜出，不叠加）
2. 顺着链找第一个 `available() && covers(carrier)` 的 provider，查；
3. 查到 → 返回；查不到（empty）→ **继续链上下一个**；
4. 全链走完仍空 → 返回 empty，调用方保持原状（不编造推进）。
5. 每次回退写一行 info 日志（哪个 provider、为什么）。

**与现状的行为差**：现在挑中谁就是谁，不可用直接空着；改后会回退。这是 AC5 要的，但也意味着**一单可能查两家、花两份钱** —— 所以链默认只有一个（`default: [kuaidi100]`），多级链只给确有直连账号的承运商配。

### 2.3 轴二：展示渠道

```java
public interface TraceDisplay {
    String name();                                  // wx-plugin / self-map
    boolean supports(Surface surface, ShipmentCtx ctx);  // 这个端 + 这一单，我能不能呈现
    Optional<DisplayPayload> prepare(ShipmentCtx ctx);   // 备好载荷；备不出返回 empty
}
```
- `Surface`：`MP`（C 端小程序）/ `APP`（B 端 App）/ `OPS`（运营端）/ `H5`
- `ShipmentCtx`：运单号、承运商、收件人手机号、买家 openid、微信支付交易单号、已存的渠道载荷
- `DisplayPayload`：`channel` + `token`（微信存 `waybill_token`）+ `nodes`（自建的轨迹节点）+ `route`（城市路线）

**`WxPluginDisplay`**
- `supports`：`surface == MP` 且 `ctx.transId != null` 且 `ctx.openid != null` 且承运商在微信运力表里
- `prepare`：库里已有未过期的 `display_token` 直接用；没有就调 `trace_waybill` 换一个并落库。
  **必须落库**：微信文档要求开发者自己存，且该接口有调用次数上限（`9300513`）。
- 失败（`9300559` 运单不存在、`9300513` 超配额、`40003` openid 不合法）→ 返回 empty，记 `display_fail_reason`，链上落到 `self-map`。

**`SelfMapDisplay`**
- `supports`：恒 true（兜底渠道，永远在链尾）
- `prepare`：读 `ful_shipment_trace` 的节点（含坐标）+ 整单 `route`

`TraceDisplayRouter` 按配置链逐个 `supports` + `prepare`，第一个成功的胜出；全失败返回 empty（端上整块不显示）。

### 2.4 库表

**`ful_shipment` 加三列**（通用，不写死微信 —— 加渠道不用再加列）：
| 列 | 说明 |
|---|---|
| `display_channel` VARCHAR(32) | 已备好载荷的渠道名（`wx-plugin`…）。空 = 还没备过 |
| `display_token` VARCHAR(512) | 该渠道的载荷。微信存 `waybill_token` |
| `display_fail_reason` VARCHAR(255) | 最近一次备载荷失败的原因（给运营看，不给买家看） |

**`ful_shipment_trace` 加三列**：
| 列 | 说明 |
|---|---|
| `lat_e6` INT | 城市中心纬度 ×1e6。**要存**：不存的话轮询存了轨迹、坐标丢了，下次展示还得再花钱查一遍 |
| `lng_e6` INT | 经度 ×1e6 |
| `status_code` VARCHAR(16) | 快递100 高级状态码（步骤条区分「派送中」用） |

迁移号取提交时的下一个，**当场查撞号**；上线前在真库副本上实跑（`zz_tmp_*`）。

### 2.5 后端改动清单

| 动作 | 路径 | 说明 |
|---|---|---|
| 改 | `TraceRoutingProperties` | 两轴配置：`source.{default,by-carrier,by-store}`、`display.{mp,app,ops,h5}` |
| 改 | `LogisticsTraceRouter` | 单选 → 优先级链 + 回退 |
| 改 | `Kuaidi100TraceProvider` | `resultv2` 1→4；解析 `areaCenter`→lat/lng、`routeInfo`→route、`statusCode` |
| 改 | `TraceResult` | 节点加 `latE6/lngE6/statusCode`；整单加 `route(from/cur/to)` |
| 加 | `spi/logistics/TraceDisplay` + `Surface`/`ShipmentCtx`/`DisplayPayload` | 轴二接口（shop-base） |
| 加 | `channel/express/display/WxPluginDisplay` | 调 `trace_waybill`，落 token |
| 加 | `channel/express/display/SelfMapDisplay` | 读本地节点 |
| 加 | `channel/express/display/TraceDisplayRouter` | 链路由 |
| 加 | `channel/express/port/WxWaybillGateway` | 微信两个接口的网关 |
| 改 | `LogisticsServiceImpl` | 轮询落坐标；订单详情组装 `displayMode` + 载荷 |
| 改 | 订单详情 VO / 三端契约 | 加 `trace.displayMode`、`trace.token`、节点坐标、`trace.route` |

### 2.6 端上

**C 端小程序**（`c-app/pages/order`）
- `displayMode == "wx-plugin"`：一个「查看物流」按钮 → `requirePlugin("logisticsPlugin").openWaybillTracking({ waybillToken })`。
  插件要在 `manifest.json` 声明 `provider: wx9ad912bf20548d92`（**不是我们自己的 appid**）+ version。
- `displayMode == "self-map"`：地图（原生 `<map>`，免费、不要 key）+ 步骤条 + 折叠时间线 + 复制单号 + 拨号
- 无 `displayMode`：整块不显示

**B 端 App**：复用同一套自建展示件（b-app 也是 uni-app；App 端 `<map>` 走高德 SDK，key 注入已有）。
**运营端**：步骤条 + 折叠时间线；**地图要高德 JS API key，线上只有服务端 Web 服务 key** → 本期运营端不做地图，待申请。

### 2.7 契约变更（1 档，三处登记）
- 端点：无新增（走订单详情）
- 库表：两张表共 6 列 + 一条迁移
- i18n：c-app / b-app 新词条（步骤条四档、展开全部、复制单号、查看物流…）三语
- 对外 JSON：订单详情的 `trace` 结构

## §5 对账三 · 实现 → 需求（测试，实现时填）

## §6 对账二 · 设计 → 实现（实现后填）
