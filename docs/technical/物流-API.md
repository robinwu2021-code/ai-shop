# 物流 API

状态：草稿（随 [TDD-物流模块](TDD-物流模块.md) 一起确认，2026-10-09）· 实现后以三端 `gen:api` 生成的 OpenAPI 为准
关联：[TDD-物流模块](TDD-物流模块.md) §2.3 · [物流-功能模块方案](物流-功能模块方案.md) · [物流-调用清单](物流-调用清单.md) ·
[物流-功能清单](../requirements/物流-功能清单.md) · [响应格式规范](../api/响应格式规范.md)

---

## L1 一句话

物流对外的 HTTP 只有两类：**给人看的读端点**（C 端、B 端、运营端，全部挂在主应用里、先判属主再问物流）
与**给渠道推送的回调**（物流模块自己的唯一 HTTP 入口）。写操作只有运营端的「换单号」与「重放」。

## L2 总表

| # | 方法 · 路径 | 端 | 状态 | 放在哪 | 功能清单 |
|---|---|---|---|---|---|
| A1 | `GET /mp/order/{orderNo}` | C | 改：trace 只读库 | 交易域 `MpTradeController` | L-C-01 |
| A2 | `GET /mp/order/{orderNo}/trace` | C | **新** | 交易域 `MpTradeController` | L-C-02 L-C-05 |
| B1 | `GET /biz/order/{subOrderNo}` | B | 改：trace 只读库 | 交易域 `BizOrderController` | L-B-02 |
| B2 | `GET /biz/order/{subOrderNo}/trace` | B | **新** | 交易域 `BizOrderController` | L-B-03 |
| B3 | `POST /biz/order/{subOrderNo}/ship` | B | 不变（发货后发事件） | 交易域 | L-B-01 |
| O1 | `GET /ops/shipments` | 运营 | 改：加筛选与字段 | 主应用 `portal/ops` | L-O-01 |
| O2 | `POST /ops/shipments/{shipmentNo}/waybill` | 运营 | 改：换号后重新订阅 | 主应用 `portal/ops` | L-O-02 |
| O3 | `POST /ops/shipments/{shipmentNo}/replay` | 运营 | **新** | 主应用 `portal/ops` | L-O-03 |
| O4 | `GET /ops/logistics/channels` | 运营 | **新** | 主应用 `portal/ops` | L-O-05 |
| O5 | `GET /ops/fulfillment/carriers` · `PUT …/{carrier}` · `POST …/{carrier}/enabled` | 运营 | 改：多 `codes` | 主应用 `portal/ops` | L-O-04 |
| X1 | `POST /callback/logistics/{channel}` | 渠道 | **新** | `logistics-domain` | L-S-03 |
| X2 | `POST /callback/express/kuaidi100` | 快递100 寄件 | 不变（寄件回调，不是轨迹） | 交易域 | — |
| X3 | `POST /mp/wx/callback` | 微信 | 不变（结算事件） | 主应用 | L-C-07 |

**为什么用户端点留在交易域 / 主应用**：物流模块不认识用户。「这张单是不是你的」只有交易域能判 ——
先由交易域按当前登录人查到子单（属主条件写在查询里，防 IDOR），再拿业务键问 `LogisticsPort`。
物流模块要是自己开 C 端端点，就得反过来读订单表判属主，独立那天这条线拆不掉。运营端同理（鉴权、判权都在主应用），
经 `LogisticsAdminPort` 调物流 —— 与支付域「领域模块不做 controller」同一个形状。

---

## 1 通用约定

| 项 | 约定 | 来源 |
|---|---|---|
| 响应信封 | `{"code":0,"msg":"success","data":…}`；业务错误也是 HTTP 200 + 非 0 `code`，只有未登录用 401 | `ApiResult`、[响应格式规范](../api/响应格式规范.md) §2 |
| 令牌 | `Authorization: Bearer <token>`；C 端 `ctk_`、B 端 `btk_`、运营 `otk_` | `AbstractTokenAuthFilter` |
| B 端门店 | `X-Store-No`；不传用默认店；传了无权的门店会**静默回落默认店** | `BizContextFilter` |
| C 端端标识 | `X-Client`：`MP`（默认）/ `APP*` / `H5*` / `WEB*` → surface `MP` / `APP` / `H5` | `OrderServiceImpl.surfaceOf` |
| 分页 | 入参 `page`（从 1）`size`；出参 `{records,total,page,size}` | `PageData` |
| 时间 | 轨迹节点 `at` 为**毫秒时间戳**；运营端列表沿用 ISO-8601 字符串 | 现状两套，见 §5 |
| 回调 | **不走信封**：返回 `String`，各渠道要求的回执原样输出 | `ApiResponseWrapper` 不排除 `/callback/**`，只排除 `String` |

> ⚠️ **`X-Client` 今天没有任何一端在发**（c-app、b-app、`packages/shared` 的 http 客户端都没有），所以所有 C 端请求都被当成小程序。
> A2 要按端决定给微信插件还是自建轨迹 —— **c-app 必须先把 `X-Client` 发起来**（开发任务 T3.6）。不发的后果是：
> App 里点「查看物流」拿到 `wx-plugin`，而 App 里没有微信插件。

## 2 错误码

沿用：

| 码 | 枚举 | 文案 | 用在 |
|---|---|---|---|
| 10403 | `FORBIDDEN` | — | 无权限 |
| 10404 | `NOT_FOUND` | — | 子单不存在或不是你的（**不区分**，防探测） |
| 30006 | `WAYBILL_LOCKED` | 已签收的快递单不能改运单号 | O2 |
| 30007 | `WAYBILL_DUPLICATED` | 该承运商下这个运单号已被别的单占用 | O2 |

**新增**（3 段履约，接在 30012 之后；登记四处：`ErrorCode.java` + 三语文案、响应格式规范 §3、`gen-glossary.mjs`、改了 .vue 时重跑 `gen-ui-spec.py`）：

| 码 | 枚举 | 文案 | 用在 | 运营的下一步 |
|---|---|---|---|---|
| 30013 | `WAYBILL_SUBSCRIBE_LIMIT` | 这个运单号本月在快递100 已订阅 4 次，不能再订阅；可换其他渠道或下月再试 | O3 | 指定别的渠道重放 |
| 30014 | `WAYBILL_TERMINAL` | 运单已签收或已作废，不需要重放 | O3 | 无 |
| 30015 | `LOGISTICS_CHANNEL_UNAVAILABLE` | 这个物流渠道没有启用或凭据没配，不能用它订阅：{0} | O3（指定渠道时） | 去看渠道总览 O4 |

## 3 数据结构

### 3.1 `Trace`（C / B 端）

**就是现有的 `OrderVO.Trace` 加字段**（前端类型 `packages/shared/src/types/trade.ts` 的 `ShipmentTrace`）。字段只加不改。

| 字段 | 类型 | 现有 / 新增 | 说明 |
|---|---|---|---|
| `status` | string | 现有 | `CREATED` `PICKED_UP` `IN_TRANSIT` **`DELIVERING`** `DELIVERED` `EXCEPTION` **`CANCELLED`**（加粗为新增值） |
| `nodes` | `Node[]` | 现有 | **倒序**（最新在上） |
| `displayMode` | string \| null | 现有 | `wx-plugin` / `self-map`；null 表示整块不显示 |
| `displayToken` | string \| null | 现有 | 微信插件的 `waybillToken`；只在 `displayMode=wx-plugin` 时有值 |
| `route` | `Route` \| null | 现有 | `{from, cur, to}` |
| `carrier` | string | 新增 | 我方承运商码（`SF` / `STO` / `YTO` …） |
| `carrierName` | string | 新增 | 展示用名称 |
| `waybillNo` | string | 新增 | 运单号 |
| `signedAt` | number \| null | 新增 | 签收时间，毫秒 |
| `atLocker` | boolean | 新增 | 已到驿站 / 快递柜（L-C-05） |
| `freshAt` | number \| null | 新增 | 最近一次有新进展的时刻，毫秒 —— 前端显示「X 分钟前更新」 |
| `refreshable` | boolean | 新增 | 这个界面的「刷新」能不能真的去问渠道（B 端默认 false） |

`Node`（现有）：`at` number（毫秒）· `text` string · `location` string \| null · `latE6` / `lngE6` number \| null（城市中心 ×1e6）。

> ⚠️ 前端 `ShipmentStatus` 类型要加 `DELIVERING`、`CANCELLED` 两个值。`sh-trace` 组件里凡是按状态分支的地方逐个看：
> 新值落进 `default` 分支时显示成什么，要显式决定，不能靠默认。

### 3.2 `Shipment`（运营端，扩展现有 `ShipmentVO`）

| 字段 | 现有 / 新增 | 说明 |
|---|---|---|
| `shipmentNo` `orderNo` `carrier` `waybillNo` `status` `receiver` `region` `createdAt` `updatedAt` `traces[]` | 现有 | `traces` 正序、`at` 为 ISO 字符串（沿用，不改） |
| `displayChannel` `displayFailReason` | 现有 → **弃用** | 批 5 删；过渡期由 `bindState` / `bindError` 推导 |
| `profile` | 新增 | `WX` / `SELF` |
| `storeNo` `entityNo` | 新增 | 快照 |
| `subState` `subChannel` `subError` `subAttempts` | 新增 | 订阅：`PENDING` / `DONE` / `FATAL` / `ENDED`；渠道；最后一次失败（渠道 + 码 + 原文） |
| `bindState` `bindError` | 新增 | 微信换 token：`NA` / `WAITING` / `DONE` / `FATAL` |
| `signedAt` `lastEventAt` | 新增 | ISO 字符串 |
| `atLocker` | 新增 | boolean |
| `carrierCorrectedFrom` | 新增 | 渠道纠正过承运商时的原值 |
| `receiverPhoneLast4` | 新增 | 只给后四位 |

### 3.3 `Channel`（运营端）

| 字段 | 说明 |
|---|---|
| `name` | `kuaidi100` / `yto` / `wx` / `stub` |
| `enabled` | 配置里启用 |
| `capabilities` | `[{capability: SUBSCRIBE|PUSH|PROBE|DISPLAY|CREATE, available: bool, reason: string|null}]` —— `reason` 如「推送密钥未配」「推送不可用，订阅随之不可用」 |
| `carriers` | 覆盖的我方承运商码（来自 `lgs_carrier_code`） |
| `routes` | 它出现在哪些路由链里（`subscribe.default#1`、`by-store.ST-xxx#0`…） |

### 3.4 `Carrier`（运营端，扩展现有 `CarrierConfigVO`）

现有字段不变（`carrier` `name` `enabled` `priority` `accountMasked` `apiKeyConfigured` `pickupCutoff` `slaHours` `updatedAt` `updatedBy`），
新增 `codes: {channel: code}`，如 `{"kuaidi100":"shentong","wx":"STO"}`。`PUT` 时一并保存，写 `lgs_carrier_code`。

## 4 C 端

### A1 `GET /mp/order/{orderNo}` —— 改

`orderNo` 是**子单号**（C 端订单视角就是子单）。返回体不变；`data.trace` 改为**只读库，不触发任何外部调用**。
`trace` 里 `displayToken` 仍然给（老版本小程序直接用它开插件）。

### A2 `GET /mp/order/{orderNo}/trace` —— 新

点「查看物流」时调。

| 项 | 值 |
|---|---|
| 鉴权 | 需登录（登记进 `MpEndpointAuthTest.REQUIRES_LOGIN`：`"GET /mp/order/{orderNo}/trace"`） |
| 请求头 | `X-Client` |
| 路径参数 | `orderNo` 子单号 |
| 属主 | 按当前登录人查子单，查不到 → `10404`（不区分「不存在」与「不是你的」） |
| 不是快递 / 还没发货 | `data = null`（不是错误） |
| 外部调用 | 微信支付单 ∧ 有 token ∧ 未终态 ∧ 距上次 ≥ 10 分钟 → 微信 `query_trace`（超时 2 秒，失败用库里的） |
| 返回 | `Trace`（§3.1） |

```json
{"code":0,"msg":"success","data":{
  "status":"DELIVERING","carrier":"STO","carrierName":"申通快递","waybillNo":"773012345678",
  "displayMode":"wx-plugin","displayToken":"wbt_…","signedAt":null,"atLocker":true,
  "freshAt":1791520000000,"refreshable":false,
  "nodes":[{"at":1791520000000,"text":"您的快件已存放至【XX驿站】，请凭取件码 6-2-3011 领取","location":"深圳市","latE6":22543099,"lngE6":114057868}],
  "route":{"from":"东莞市","cur":"深圳市","to":"深圳市"}}}
```

c-app 拿到 `wx-plugin` → `openWaybillTracking(displayToken)`；`self-map` → 展开 `sh-trace`。

## 5 B 端

### B1 `GET /biz/order/{subOrderNo}` —— 改

返回体不变；`data.trace` 只读库。

### B2 `GET /biz/order/{subOrderNo}/trace` —— 新

| 项 | 值 |
|---|---|
| 鉴权 | 需登录；权限码与 B1 相同（看订单的人就能看物流）；门店由 `X-Store-No` 决定，子单不在该门店 → `10404` |
| 查询参数 | `refresh`（bool，默认 false） |
| 外部调用 | `refresh=true` 且 `shop.logistics.probe-surfaces` 对某渠道放行 `BIZ` → 按探测链问一次；默认一个都不放行，`refreshable=false` |
| 返回 | `Trace`（§3.1），`displayMode` 恒为 `self-map` |

登记（B 端七处）：`b-app/src/api/endpoints.ts` + `RESPONSE_TYPES` 等，见开发任务 T5.4。

## 6 运营端

权限：读沿用 `fulfillment:logistics:read`，换单号沿用 `fulfillment:rule:update`，**重放新增 `fulfillment:logistics:replay`**
（注解 `@PreAuthorize("@perm.can('" + Perms.X + "')")`；角色授权照 `Perms.java` 现有物流码给 `COMMUNITY_OPS`；/ops 五处登记见 T5.2）。

### O1 `GET /ops/shipments` —— 改

| 参数 | 现有 / 新增 | 说明 |
|---|---|---|
| `page` `size` | 现有 | 默认 1 / 20 |
| `status` `carrier` `keyword` | 现有 | `keyword` 匹配运单号、子单号 |
| `subState` | 新增 | 看 `FATAL` 用 |
| `bindState` | 新增 | 同上 |
| `subChannel` | 新增 | 这家渠道受理的 |
| `profile` | 新增 | `WX` / `SELF` |

返回 `PageData<Shipment>`（§3.2）。**改为数据库分页**（现在是查全量后在内存里分页，`PageData.ofAll`）。

### O2 `POST /ops/shipments/{shipmentNo}/waybill` —— 改

请求体不变 `{"waybillNo":"…","carrier":"…"}`。行为变化：旧运单 `CANCELLED`，按新单号**新登记一张**并订阅；
旧单号此后的推送因状态与 `sub_channel` 对不上被丢弃。错误：`30006` 已签收不能换、`30007` 单号被占。

### O3 `POST /ops/shipments/{shipmentNo}/replay` —— 新

```json
{"action":"SUBSCRIBE","channel":"kuaidi100"}   // channel 可省：省略则重走路由链
{"action":"WX_BIND"}
```

| 情况 | 结果 |
|---|---|
| 受理 | `data = {"accepted":true}`；异步执行，结果看 O1 |
| 运单已终态 | `30014` |
| 指定渠道不可用 | `30015`（`{0}` = 不可用原因，同 O4 的 `reason`） |
| 快递100 本月已订阅 4 次 | `30013` |
| `WX_BIND` 但是 SELF 单 | `10400` |

### O4 `GET /ops/logistics/channels` —— 新

返回 `Channel[]`（§3.3），不分页。权限 `fulfillment:logistics:read`。

### O5 承运商 —— 改

见 §3.4。`PUT /ops/fulfillment/carriers/{carrier}` 请求体多 `codes`；不传 `codes` = 不改编码（**空 ≠ 清空**）。

## 7 回调

### X1 `POST /callback/logistics/{channel}` —— 新

| 项 | 值 |
|---|---|
| 鉴权 | 无令牌（`/callback/**` 已放行）；靠各渠道验签 |
| `channel` | `kuaidi100`（批 2）、`yto`（批 2b）；未知 → HTTP 404 |
| 返回 | `String`，`produces=application/json`，内容由渠道决定 |
| 原则 | 只要渠道名存在，**一律回成功**（验签失败、找不到运单、入库异常都回成功，各自记 WARN / ERROR）—— 理由见 [功能模块方案 M4](物流-功能模块方案.md) |

**`kuaidi100`**（官方文档 2026-10-09 核对）：

请求：`application/x-www-form-urlencoded`，字段 `param`（JSON 字符串）、`sign`（`upper(md5(param + salt))`）。

```json
// param 解开后（节选）
{"status":"polling","message":"","autoCheck":"0",
 "lastResult":{"state":"5","status":"200","ischeck":"0","com":"shentong","nu":"773012345678",
   "data":[{"context":"您的快件已存放至【XX驿站】…","time":"2026-10-12 09:30:00","ftime":"2026-10-12 09:30:00",
            "status":"投柜或驿站","areaCode":"440300000000","areaName":"广东,深圳市"}]}}
```

回执（成功，含 `abort`）：

```json
{"result":true,"returnCode":"200","message":"成功"}
```

**`yto`**：推送服务待调试；报文与回执以调通后的真实报文为准（[TDD-圆通物流直连](design/TDD-圆通物流直连.md)）。

## 8 模块内契约（非 HTTP）

| 契约 | 方向 | 定义处 |
|---|---|---|
| `LogisticsPort.track(TrackQuery)` / `signedAtOf(Collection<String>)` | 交易域 → 物流 | `shop-base/spi/logistics` |
| `LogisticsAdminPort.list / changeWaybill / replay / channels / carriers…` | 主应用运营端 → 物流 | `shop-base/spi/logistics` |
| `ShipmentSourcePort.sourceOf(subOrderNo)` | 物流 → 交易域（唯一反向，预算 1） | `shop-base/spi/logistics`，实现在 `logisticsbridge` |
| `OrderEvents.SubOrderShipped` | 交易域 → 物流 | 已有 |
| `TradeEvents.WxShippingUploaded` | 交易域 → 物流 | 新 |
| `LogisticsEvents.WaybillProgressed` / `WaybillSigned` | 物流 → 交易域、通知 | 新 |
| `/internal/logistics/**`（`@HttpExchange` 镜像上面两个 Port） | 主应用 → logistics-svc | **阶段 3 才有** |

## L4 边界

- **时间格式两套**：C / B 端节点用毫秒、运营端用 ISO 字符串，是现状；本期不统一（统一要同时改 ops-web 与两个前端类型）。
- **A1 仍返回 `displayToken`**：老版本小程序靠它开插件，新版本改走 A2；等小程序最低版本覆盖后再从 A1 去掉。
- **`displayChannel` / `displayFailReason` 的弃用**：批 5 删字段前，ops-web 要先切到 `bindState` / `bindError`。
