# TDD-商户App外部记账

状态：草稿（契约待后端与 App 确认；未实现，§5 的测试都还没写、没跑）
档位：1 · 依据：[交接-商户App功能矩阵与API](../交接-商户App功能矩阵与API.md) §7（7-20～7-23、接口表「外部收款」四行、待决第 1、5 条）、附录 B · 产出：本文
（若「待决」D1 定成放进支付域 / pay-svc，或 D6 定成进报表与看板口径，本方案升为 2 档，要另开 ADR，见文末「升档条件」）
关联需求：本仓库没有外部记账的 PRD；AC 由交接文档整理（§0），PRD-支付域 AC-3.6 / AC-6.3 与 PRD-商家资金到账与对账 AC-5 约束「线下的钱不进通道对账」
创建：2026-11-08 · 最后更新：2026-11-08

---

## L1 定位

商家在 App 外收到的钱（现金、外部刷卡机、银行转账）在 App 里**记一笔账**，只是给商家自己看、留痕的流水：
**平台不经手、不进拨款、不进通道对账、不能退款、不改任何订单状态、不动库存**。
接口族 `/biz/payment/offline-records*`（App 已按 mock 做好，正式包在 `features/v5/contracts.dart` 里拦着）。

## L2 结构

| 部分 | 是什么 | 落在哪 |
|---|---|---|
| 记一笔 | 门店当前成员新建一条外部收款记录，幂等 | `POST /biz/payment/offline-records` |
| 列表 | 按日期 / 方式 / 状态看本店外部记录 | `GET /biz/payment/offline-records` |
| 更正 | 改金额、方式，原因必填，旧值留痕 | `POST /biz/payment/offline-records/{recordNo}/correct` |
| 作废 | 置为已作废，原因必填，不删行 | `POST /biz/payment/offline-records/{recordNo}/void` |
| 存储 | 一张记录表 + 一张事件（留痕）表 | `ord_offline_record`、`ord_offline_record_event`（trade 域，主库） |
| 权限 | 记账 / 管理两个新码，读复用收款查看 | `mch:pay:offline:record`、`mch:pay:offline:manage`、`mch:pay:order:view` |

它和三样「看起来像」的东西**不是一回事**（§1 详述）：
线下卖出（`/biz/store/{storeNo}/offline-sale`，只减库存、不记钱）、订单线下实收（`confirm-offline-pay` / `offline-receipts` / `manual-receipts`，绑订单、把订单推成已付）、
商城线下转账（`mall/offline/*`，商家付钱给平台，方向相反）。

---

## §0 对账一 · 需求 → 设计

需求来源是交接文档 §7 的四行功能矩阵与接口表（标「建议」），整理成 AC：

| AC | 需求（Given / When / Then） | 出处 | 落点 |
|---|---|---|---|
| AC1 | Given 门店成员有记账权限 When 提交金额、方式（现金 / 外部 POS / 转账 / 其他）、可选订单号与备注 Then 生成一条 `RECORDED` 记录并返回整条记录 | 7-20 | `POST /biz/payment/offline-records` → `OfflineRecordService#record` → `ord_offline_record` + 事件 `CREATE` |
| AC2 | Given 同一个幂等键重送 When 请求体相同 Then 返回第一次那条，不新建；请求体不同 Then 10409 | 7-20「`requestId` 防重复提交」、§3 幂等 | 请求头 `Idempotency-Key`；唯一键 `uk_offline_record_idem` |
| AC3 | 外部记录不进拨款、不能退款：不出现在 `/biz/payment/orders`、`/orders/summary`、`/statement`，不能对它调 `/biz/payment/refund` | 7-20「不进拨款、不能退款」、7-21「收款列表不混入外部记录」；PRD-支付域 AC-6.3 | 独立表，不写 pay 域任何表；`recordNo` 前缀 `OFR` 与支付单号空间不重叠，退款接口按 `payOrderNo` 查不到即 10404 |
| AC4 | Given 门店成员有收款查看权限 When 按日期（可选方式、状态）查 Then 只看到本店记录，新的在前，可翻页 | 7-21 | `GET /biz/payment/offline-records` → `OfflineRecordService#list` |
| AC5 | Given 店主 / 店长 When 更正金额或方式并填原因 Then 记录改为新值，事件表追加一条 `CORRECT`（含旧值、新值、原因、操作人） | 7-22 | `POST …/{recordNo}/correct` → `OfflineRecordService#correct` |
| AC6 | Given 店主 / 店长 When 作废并填原因 Then 状态 `VOIDED`，行不删，事件表追加 `VOID` | 7-23 | `POST …/{recordNo}/void` → `OfflineRecordService#voidRecord` |
| AC7 | Given 记录已作废 When 再更正或作废 Then 10409；Given 记录不存在或不是本店的 Then 10404 | 7-22、7-23 接口表错误列 | 服务层状态校验 + 门店范围查询 |
| AC8 | 店员能记、能看，不能改、不能作废；店长四个都能；店主全有 | 7-20～7-23「角色」列 | `BizPerms` 两个新码 + 角色模板 |
| AC9 | 新模型门店不被 70006 `CONTACT_ADMIN` 拦 | §3 常用错误码、附录 B | `ShopStoreMemberRoutes` 已有 `/biz/payment(?:/.*)?`，**不改**；`StoreScopedDailyRoutesFlowTest` 加用例锁住 |
| AC10 | 金额用最小单位整数 + 币种，币种跟门店市场 | §3 金额约定、§7 待决第 1 条 | `amountMinor`(long) + `currency`；服务端按主体市场取币种校验 |

**孤立项**：
- 没落点的 AC：无。
- 挂不上 AC 的设计：
  - `receivedAt`（实际收款时间，可补记过去的）—— App 建议契约里没有，是本文加的，挂在 AC1 下；要不要见待决 D4。
  - `expectedVersion`（更正 / 作废的并发保护）—— App 没有，挂在 AC5 / AC6 / AC7 下；理由见 §2 差异表。
  - 列表的日期 / 方式 / 状态筛选与游标分页 —— App mock 一次回全部数组，本文按同 Tab 的 `/biz/payment/orders` 补上，挂 AC4。

---

## §1 现状与影响面

### 现有能复用什么 —— 逐个看过，结论是「新建一张轻量账，不套已有单据」

| 现有能力 | 出处 | 做什么 | 能否承载外部记账 | 结论 |
|---|---|---|---|---|
| 线下卖出 | `backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizOfflineSaleController.java`（`/biz/store/{storeNo}/offline-sale`）、`backend/shop-app/src/main/java/ai/neargo/shop/invbridge/OfflineSaleService.java` | 一笔 = 一张 `OFFLINE_SALE` 出库单直接过账；撤销 = `OFFLINE_RETURN` 入库单 | **不能**。类注释写明「不记售价、不收款……这里只回答货少了几件」；单据在进销存独立库（`@ConditionalOnInventory`），按 SKU 行记件数，没有金额字段；进销存没开的门店整个控制器不存在 | 不复用。可在待决 D5 里决定是否让外部记录**引用**一张线下卖出单号 |
| 订单线下实收（商城订单） | `backend/shop-core/src/main/java/ai/neargo/shop/trade/api/biz/BizOrderController.java` `POST /biz/order/{subOrderNo}/confirm-offline-pay`（权限 `BizPerms.RECEIVE`） | 把 `WAIT_OFFLINE_PAY` 子单推成已付 | **不能**。必须有一张待线下付款的订单；外部记账大多没有订单 | 不复用；`orderNo` 只作引用，不触发它（待决 D3） |
| 收银台线下实收（服务业） | `backend/shop-core/src/main/java/ai/neargo/shop/trade/api/biz/BizOfflineReceiptController.java` `POST /biz/checkout/{operationNo}/offline-receipts`、`backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/OfflineReceiptServiceImpl.java` | 绑结账操作单，校验金额等于原报价，经 `PaymentOperationPort#confirmOffline` 写支付台账（`ledger.settle(... "OFFLINE" ...)`） | **不能**。绑订单与报价；会进 pay 域台账；`OfflineReceiptReq` 只收 `CASH` / `BANK_TRANSFER` 且**写死 `CNY`**（`backend/shop-base/src/main/java/ai/neargo/shop/spi/trade/OfflineReceiptPort.java` 第 46–48 行），台湾 TWD 直接 10400 | 不复用。**借用**它的两样做法：`Idempotency-Key` 头 + 「同键不同体 → 冲突」；方式码 `CASH` / `BANK_TRANSFER` 命名 |
| 人工确认服务收款 | `backend/shop-core/src/main/java/ai/neargo/shop/trade/api/biz/BizManualServiceAdmissionController.java`（`/biz/service-orders/{orderNo}/manual-receipts` 等） | 柜台服务单的人工收款准入（`admission_kind=MANUAL_OFFLINE`） | **不能**。同样绑服务订单 | 不复用 |
| 储值线下实收 | `backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/BizStoredValuePlanRechargeController.java` `/biz/stored-value/plan-recharges/confirm-offline` | 储值充值的线下到账 | **不能**。储值专用 | 不复用 |
| 商城线下转账 | `backend/commerce/commerce-spring-boot-starter/src/main/java/ai/neargo/commerce/starter/http/CommerceOfflineBuyerHttpController.java`（`/biz/stores/{storeNo}/mall/offline/*`）、`CommerceOfflineOpsHttpController.java`（`/ops/biz-mall/offline/*`） | 商家向平台买东西、银行转账付款，运营确认 | **不能**。钱的方向是商家 → 平台 | 不复用 |
| 收款 Tab / 收银台 | `backend/shop-app/src/main/java/ai/neargo/shop/portal/biz/pay/BizPaymentController.java` 经 `backend/shop-app/src/main/java/ai/neargo/shop/payclient/BizPaymentAppService.java` 调支付域（内嵌直调，独立形态走 `/internal/pay/v1/biz/**`） | 渠道收款的支付单、链接、退款 | **不应该**。外部记录不是支付单；塞进支付域要改 pay-domain、pay-svc 内部端点、payclient 两种形态，且违背 PRD-支付域 AC-6.3「线下单排除在通道对账之外」 | 只共用 URL 前缀 `/biz/payment`（App 已定、路由已登记），实现不进支付域 |
| 「外部收款」一词 | `docs/technical/design/TDD-B端报表库与日结.md` 第 881、1124 行 `EXTERNAL_NET_CASHFLOW_AMOUNT` | 报表里的「外部」指**经渠道确认的真实现金流入**（相对储值、积分等内部抵用） | — | **名词撞车**：本文的记录在代码与表名里一律叫 `offline record`，不叫 external receipt；报表口径见待决 D6 |

**取舍**：外部记账是「商家自报的、平台无从核实的流水」。挂到任何一种已有单据上，都会把它带进那种单据的后果
（减库存、推订单状态、进支付台账、进通道对账）。所以新建一张只属于它的表，放在 trade 域
（与 `BizOfflineReceiptController` 同域，主库、随 shop-app 部署，不涉及 pay-svc 拆分）。

### 相关现有模块

- 路由放行：`backend/shop-app/src/main/java/ai/neargo/shop/commercebridge/ShopStoreMemberRoutes.java` 第 28 行
  `HostRoute.of("GET,POST,PUT,DELETE", "/biz/payment(?:/.*)?")`；匹配用 `Pattern#matches` 全路径匹配
  （`backend/commerce/commerce-spring-boot-starter/src/main/java/ai/neargo/commerce/starter/security/CommerceRoutePolicy.java` 第 54–56 行），
  四个新接口（`GET`、`POST`）都被它覆盖 —— **不需要登记，不改这个文件**。
- 门店与操作人：`BizContext.requireStoreNo()`、`BizContext.current().requireMerchantNo()`、`SecurityUtils.currentUserNo()`（用法同 `BizOfflineReceiptController#scope`）。
- 币种：`MerchantQueryPort#marketOf(merchantNo)`（`backend/shop-base/src/main/java/ai/neargo/shop/spi/user/MerchantQueryPort.java` 第 475 行）→ `MarketPort#find(market).currency()`（`backend/shop-base/src/main/java/ai/neargo/shop/spi/pay/MarketPort.java`）。
- 编号：`BizKey`（`backend/shop-base/src/main/java/ai/neargo/shop/common/BizKey.java`），现无 `OFR` 前缀。
- 权限码与角色模板：`backend/shop-base-auth/src/main/java/ai/neargo/shop/auth/BizPerms.java`（`PAY_*` 第 171–180 行；`ROLE_PERMS` 第 192–203 行；`LABELS` 第 215–273 行）。
  现状：`MANAGER` 有 `PAY_ORDER_VIEW`、`PAY_COLLECT`；`CLERK` 只有 `PAY_ORDER_VIEW`；`CASH_RECEIVE`（`pay:cash:receive`「确认线下实收」）只在 `OWNER` 的 `*` 里。
- 错误码：`ErrorCode.BAD_REQUEST(10400)`、`FORBIDDEN(10403)`、`NOT_FOUND(10404)`、`CONFLICT(10409)` 已有，带三语文案。

### 会被改到的（已在跑的）

- `BizPerms`：加两个码、两条说明、`MANAGER` / `CLERK` 模板各加码。**影响**：所有用模板的店长、店员下次读权限时多出这两项；
  自定义角色的勾选列表（`/biz/roles` 下发 `LABELS`）多两项。`BizRoleSeedTest` 会盯「每个码都有说明」。
- `BizEndpointPermTest` 判权表：加四行。

### 明确不受影响的

- 支付域（`backend/pay/**`）、payclient、pay-svc：一行不动。`/biz/payment/orders`、`/summary`、`/statement`、`/refund` 的口径不变。
- 订单状态机、`confirm-offline-pay`、`offline-receipts`、`manual-receipts`：不动；外部记录带了 `orderNo` 也不推订单。
- 进销存与线下卖出：不动。
- `ShopStoreMemberRoutes`、`CommerceRoutePolicy`：不动。
- 本仓库 `b-app/`（uni-app 商家端）：不加页面；外部记账只在 Flutter 商户 App（ai-shop-bapp 仓库）里。所以 `ui-catalog.json` 不用重生成。
- 后端 i18n：不加新错误码，复用 10400 / 10404 / 10409。

---

## §2 方案

### 契约变更

- 端点（4 条新，全部在 trade 域 `BizOfflineRecordController`）：见下方「关键接口」。
- 库表：新增 `ord_offline_record`、`ord_offline_record_event`；迁移 `V6xx__ord_offline_record.sql`（号待定，当前最大 `V635`）。
- 权限码：新增 `mch:pay:offline:record`（记一笔）、`mch:pay:offline:manage`（更正 / 作废）；读复用 `mch:pay:order:view`。
- i18n：后端无；`BizPerms.LABELS` 两条中文说明（随 `/biz/roles` 下发）。
- 配置项：无。
- 编号前缀：`BizKey` 加 `OFFLINE_RECORD = "OFR"`。

### 通用约定（四个接口都适用）

- 返回包 `{code, msg, data}`，鉴权 `Authorization: Bearer`，门店上下文 `X-Store-No`（+ `X-Entity-No`），与交接文档 §3 一致。
- 门店只认 `BizContext.requireStoreNo()`，**请求体与查询串里不收 `storeNo` / `entityNo` / 操作人**；未知字段 10400（同 `OfflineReceiptReq#unknown` 的做法）。
- 金额 `amountMinor`：`long`，`> 0`，`≤ 99_999_999_999`；JSON 里是数字，不是字符串。`currency`：大写 ISO 4217，必须等于门店所属主体市场的币种（台湾 `TWD`），否则 10400。
- 时间：响应里一律 ISO 8601 带偏移（`2026-11-08T14:03:00+08:00`），按门店市场时区；列表的 `from` / `to` 是门店市场的 `yyyy-MM-dd`（同 `/biz/payment/orders`）。
- 方式 `method`：`CASH | BANK_TRANSFER | EXTERNAL_POS | OTHER`。
- 记录行（下称 `OfflineRecordView`）四个接口共用：

```json
{
  "recordNo": "OFR2611081234567890",
  "amountMinor": 35000,
  "currency": "TWD",
  "method": "CASH",
  "orderNo": null,
  "note": "早餐 3 份",
  "receivedAt": "2026-11-08T08:12:00+08:00",
  "recordedBy": "U2610010000000001",
  "recordedByName": "王小明",
  "recordedAt": "2026-11-08T08:12:31+08:00",
  "status": "RECORDED",
  "corrected": false,
  "version": 1,
  "history": [
    {"at": "2026-11-08T08:12:31+08:00", "by": "U2610010000000001", "byName": "王小明",
     "action": "CREATE", "reason": null,
     "fromAmountMinor": null, "toAmountMinor": 35000, "fromMethod": null, "toMethod": "CASH"}
  ]
}
```

  - `history[].action`：`CREATE | CORRECT | VOID`，按时间正序；App 按 `action` 与前后值自己拼文字（同 `/biz/payment/orders/{payOrderNo}` 的 `timeline.kind` 做法）。
  - `version`：每次更正 / 作废 +1，更正与作废要带回来（`expectedVersion`）。
  - `recordedByName`：取不到时为 `null`，App 退回显示 `recordedBy`。

### 关键接口

#### 1. `POST /biz/payment/offline-records` —— 记一笔

| 项 | 内容 |
|---|---|
| 权限 | `@PreAuthorize("@perm.canBiz('mch:pay:offline:record')")` |
| 请求头 | `X-Store-No`；`Idempotency-Key`（必填，1–128 字符，App 每次打开记账表单生成一个，重送沿用） |
| 请求体 | `amountMinor`(long, 必填) · `currency`(必填) · `method`(必填) · `orderNo?`(≤64) · `note?`(≤100 字) · `receivedAt?`(ISO 8601；不传 = 服务器当前时间；不得晚于现在 +2 分钟、不得早于 31 天前) |
| 响应 | `OfflineRecordView` |
| 错误 | 10400 参数不合法 / 币种不是门店币种 / `receivedAt` 越界 / 未知字段；10404 `orderNo` 不是本店的订单；10409 同一幂等键但请求体不同；70006 无权限 |
| 幂等 | 唯一键 `(tenant_no, entity_no, store_no, idem_key_hash)`；命中且请求摘要相同 → 原样返回第一次那条（HTTP 200、`code=0`）；摘要不同 → 10409。并发双写靠唯一键兜底：`DuplicateKeyException` 后重查一次（同 `OfflineReceiptServiceImpl#confirm`） |

#### 2. `GET /biz/payment/offline-records` —— 列表

| 项 | 内容 |
|---|---|
| 权限 | `mch:pay:order:view`（`BizPerms.PAY_ORDER_VIEW`，店长、店员模板已有） |
| 查询串 | `from?`、`to?`（`yyyy-MM-dd`，`to` 含当天，不传 = 今天；跨度 ≤ 92 天）· `method[]?` · `status[]?`（`RECORDED` / `VOIDED`，不传 = 全部）· `cursor?` · `size?`（1–50，默认 20） |
| 响应 | `{ items: OfflineRecordView[], nextCursor: string \| null }`；按 `receivedAt` 倒序、同时刻按 `id` 倒序；按 `receivedAt` 落日期 |
| 错误 | 10400 日期格式错 / 跨度超限 / 枚举值未知；70006 |
| 说明 | 列表里的 `history` 照带（单条事件少，不另开详情接口）；没有汇总字段（见待决 D6） |

#### 3. `POST /biz/payment/offline-records/{recordNo}/correct` —— 更正

| 项 | 内容 |
|---|---|
| 权限 | `mch:pay:offline:manage` |
| 请求体 | `amountMinor`(必填) · `currency`(必填，必须等于原记录币种) · `method`(必填) · `reason`(必填，1–100 字) · `expectedVersion`(必填) |
| 响应 | `OfflineRecordView`（`corrected=true`，`history` 追加 `CORRECT`） |
| 错误 | 10400 参数 / 新值与旧值完全相同；10404 不存在或不是本店；10409 已作废，或 `expectedVersion` 与当前不符；70006 |
| 并发 | `SELECT … FOR UPDATE` 锁记录行再比版本，与事件插入同一事务 |

#### 4. `POST /biz/payment/offline-records/{recordNo}/void` —— 作废

| 项 | 内容 |
|---|---|
| 权限 | `mch:pay:offline:manage` |
| 请求体 | `reason`(必填，1–100 字) · `expectedVersion`(必填) |
| 响应 | `OfflineRecordView`（`status=VOIDED`，`history` 追加 `VOID`） |
| 错误 | 10404 不存在或不是本店；10409 已作废，或版本不符；70006 |

**不提供**：删除接口（作废即终态，行不删）；详情接口（App 没调，列表带全）；「把外部记录转成支付单 / 退款」的任何入口。

### 与 App 建议契约的差异

| 项 | App 建议（mock） | 本文最终建议 | 理由 | App 要改什么 |
|---|---|---|---|---|
| 金额 | `amount{currency, minor}`，`minor` 是字符串 | `amountMinor`(数字) + `currency` 平铺 | 与同一 Tab 的 `/biz/payment/*` 全部接口一致（交接文档 §7 待决第 1 条要求定稿时统一）；台湾 TWD 用 `long` 足够，不需要字符串 | 改序列化与模型 |
| 幂等 | 请求体 `requestId` | 请求头 `Idempotency-Key` | 仓库线下实收接口（`BizOfflineReceiptController`、`BizStoredValuePlanRechargeController`）都用这个头；§3 两种都允许，选已有的那种 | 把 `requestId` 挪到头里 |
| 方式枚举 | `CASH / POS / TRANSFER / OTHER` | `CASH / BANK_TRANSFER / EXTERNAL_POS / OTHER` | `POS` 在 `/biz/payment/orders` 的 `source` 里已表示平台自己的 POS 收款，同名会混；`BANK_TRANSFER` 与 `OfflineReceiptReq` 的已有码一致 | 改两个枚举值 |
| 列表返回 | 直接数组，无筛选 | `{items, nextCursor}` + `from/to/method/status/cursor/size` | 与 `/biz/payment/orders`、`/biz/payment/links` 同形（App 已有解析）；不分页的话一家现金店一个月就上千行 | 改列表读取与分页 |
| 留痕 | `history[{at, by, text}]`，文字由服务端给 | `history[{at, by, byName, action, reason, from*/to*}]` | 服务端拼文字就得按 `Accept-Language` 本地化三语；给结构化事件由 App 渲染，与 `timeline.kind` 一致 | 按 `action` 渲染 |
| 记录人 | `recordedBy`（显示用） | `recordedBy`（用户号）+ `recordedByName` | 号是稳定身份，名字会变；与 `/biz/payment/links` 的 `operatorNo` 同做法 | 显示改读 `recordedByName` |
| 实收时间 | 无（只有 `recordedAt`） | 加 `receivedAt?` | 「昨天的现金今天补记」很常见，不加的话按日期看的列表就错天；待决 D4 | 表单加一个可选时间 |
| 并发保护 | 无 | 更正 / 作废带 `expectedVersion` | 店主与店长同时改同一条，后到的会盖掉先到的而不自知；10409 后 App 已有「重读再试」 | 带上列表里的 `version` |
| 更正的币种 | 随 `amount` 一起 | 必传且必须等于原记录币种 | 不允许借更正换币种 | 无（照传） |

### 库表与迁移

`backend/shop-app/src/main/resources/db/migration/V6xx__ord_offline_record.sql`（号待定）：

```sql
CREATE TABLE ord_offline_record (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_no        VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    record_no        VARCHAR(64)  COLLATE utf8mb4_bin NOT NULL,
    entity_no        VARCHAR(32)  COLLATE utf8mb4_bin NOT NULL,
    store_no         VARCHAR(32)  COLLATE utf8mb4_bin NOT NULL,
    amount_minor     BIGINT       NOT NULL,
    currency         CHAR(3)      NOT NULL,
    method           VARCHAR(16)  NOT NULL,             -- CASH / BANK_TRANSFER / EXTERNAL_POS / OTHER
    order_no         VARCHAR(64)  COLLATE utf8mb4_bin NULL,
    note             VARCHAR(400) NULL,
    received_at      DATETIME(3)  NOT NULL,             -- UTC
    status           VARCHAR(16)  NOT NULL,             -- RECORDED / VOIDED
    corrected        TINYINT      NOT NULL DEFAULT 0,
    recorded_by      VARCHAR(64)  COLLATE utf8mb4_bin NOT NULL,
    idem_key_hash    CHAR(64)     NOT NULL,             -- SHA-256(Idempotency-Key)，原文不落库
    request_hash     CHAR(64)     NOT NULL,             -- 首次请求体摘要，判「同键不同体」
    created_at       DATETIME(3)  NOT NULL,
    created_by       VARCHAR(64)  NULL,
    updated_at       DATETIME(3)  NOT NULL,
    updated_by       VARCHAR(64)  NULL,
    version          BIGINT       NOT NULL DEFAULT 1,
    deleted          INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_offline_record_no   (tenant_no, record_no),
    UNIQUE KEY uk_offline_record_idem (tenant_no, entity_no, store_no, idem_key_hash),
    KEY idx_offline_record_store_time (tenant_no, store_no, received_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商家自报的 App 外收款：不进支付台账、不进通道对账、不可退款';

CREATE TABLE ord_offline_record_event (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_no         VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    record_no         VARCHAR(64)  COLLATE utf8mb4_bin NOT NULL,
    action            VARCHAR(16)  NOT NULL,            -- CREATE / CORRECT / VOID
    from_amount_minor BIGINT       NULL,
    to_amount_minor   BIGINT       NULL,
    from_method       VARCHAR(16)  NULL,
    to_method         VARCHAR(16)  NULL,
    reason            VARCHAR(400) NULL,
    actor_no          VARCHAR(64)  COLLATE utf8mb4_bin NOT NULL,
    occurred_at       DATETIME(3)  NOT NULL,
    created_at        DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_offline_record_event (tenant_no, record_no, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='外部收款记录的留痕：只增不改';
```

- 事件表**只插不改**：记录表保存当前值，事件表保存每一步的前后值；`deleted` 列只为与仓库实体基类对齐，业务上不软删。
- 写迁移前查 `backend/known-sql-dialect.txt` 的守卫规则（pre-push 的 SQL 方言闸门），场景测试跑在 H2 上，`COLLATE` / `COMMENT` 写法照 `V600__checkout_payment_operation.sql`。
- 166 应用库账号无建表权限：交接时标明「带 DDL，需 root 先执行」。

### 模块设计

| 动作 | 路径 | 说明 |
|---|---|---|
| 新增 | `backend/shop-core/src/main/java/ai/neargo/shop/trade/api/biz/BizOfflineRecordController.java` | 四个端点；`@Profile("api")`；请求 record 带 `@JsonAnySetter` 拒未知字段；只取 `BizContext` 的门店与主体 |
| 新增 | `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/OfflineRecordService.java` | 接口 + `RecordCmd` / `CorrectCmd` / `VoidCmd` / `ListQuery` / `OfflineRecordView` / `HistoryView` |
| 新增 | `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/impl/OfflineRecordServiceImpl.java` | 校验、取币种、幂等、锁行比版本、写事件；`orderNo` 只校验归属本店 |
| 新增 | `backend/shop-core/src/main/java/ai/neargo/shop/trade/entity/OrdOfflineRecord.java`、`OrdOfflineRecordEvent.java` | 两张表的实体 |
| 新增 | `backend/shop-core/src/main/java/ai/neargo/shop/trade/mapper/OfflineRecordMappers.java` | `RecordMapper`（含 `lockByNo` 的 `FOR UPDATE`）、`EventMapper`；照 `CheckoutMappers` 的收拢写法 |
| 修改 | `backend/shop-base/src/main/java/ai/neargo/shop/common/BizKey.java` | 加 `OFFLINE_RECORD = "OFR"`（前缀唯一性有守卫） |
| 修改 | `backend/shop-base-auth/src/main/java/ai/neargo/shop/auth/BizPerms.java` | 加 `PAY_OFFLINE_RECORD`、`PAY_OFFLINE_MANAGE`；`LABELS` 加「记录 App 外收款」「更正与作废 App 外收款」；`MANAGER` 加两个，`CLERK` 加 `PAY_OFFLINE_RECORD` |
| 新增 | `backend/shop-app/src/main/resources/db/migration/V6xx__ord_offline_record.sql` | 上面两张表 |
| 修改 | `backend/shop-app/src/test/java/ai/neargo/shop/arch/BizEndpointPermTest.java` | 判权表加四行 |
| 修改 | `backend/shop-app/src/test/java/ai/neargo/shop/commercebridge/StoreScopedDailyRoutesFlowTest.java` | 加新模型门店访问外部记录的用例（锁住路由覆盖，见 AC9） |
| 新增 | `backend/shop-app/src/test/java/ai/neargo/shop/scenario/BizOfflineRecordFlowTest.java` | §5 的场景用例 |
| 生成 | `docs/api/openapi-b.yaml`、`docs/api/API清单.md`、`docs/api/API详情-B端.md` | 用仓库生成器重新生成（`b-app/scripts/gen-openapi.mjs` 那一套），不手改 |
| 不改 | `ShopStoreMemberRoutes.java`、`CommerceRoutePolicy.java`、`backend/pay/**`、`payclient/**` | 见 §1 |

App 侧（ai-shop-bapp，不在本仓库）：按「差异」表改 `features/collect/offline_records.dart`，冻结后从 `features/v5/contracts.dart` 删 `^/biz/payment/offline-records`，交接文档附录 B 删一行。

---

## §5 对账三 · 实现 → 需求（测试）

**计划的方法名，未写、未跑。** 实现时逐条落地并贴真实输出；消融一列实现后再填。

| AC | 测试方法 | 跑过 | 消融验证 |
|---|---|---|---|
| AC1 | `BizOfflineRecordFlowTest#clerk_records_cash_receipt_and_gets_full_record` | 未跑 | — |
| AC1 | `BizOfflineRecordFlowTest#record_rejects_currency_other_than_store_market` | 未跑 | — |
| AC1 | `BizOfflineRecordFlowTest#record_rejects_unknown_field_and_non_positive_amount` | 未跑 | — |
| AC1 | `BizOfflineRecordFlowTest#record_rejects_received_at_in_future_or_older_than_31_days` | 未跑 | — |
| AC1 | `BizOfflineRecordFlowTest#record_with_order_of_another_store_is_not_found` | 未跑 | — |
| AC2 | `BizOfflineRecordFlowTest#same_idempotency_key_same_body_returns_first_record` | 未跑 | — |
| AC2 | `BizOfflineRecordFlowTest#same_idempotency_key_different_body_conflicts` | 未跑 | — |
| AC2 | `BizOfflineRecordFlowTest#concurrent_same_key_creates_one_row` | 未跑 | — |
| AC3 | `BizOfflineRecordFlowTest#offline_record_absent_from_payment_orders_summary_and_statement` | 未跑 | — |
| AC3 | `BizOfflineRecordFlowTest#refund_by_offline_record_no_is_not_found` | 未跑 | — |
| AC3 | `BizOfflineRecordFlowTest#record_with_order_no_leaves_order_status_unchanged` | 未跑 | — |
| AC4 | `BizOfflineRecordFlowTest#list_shows_only_current_store_newest_first_with_cursor` | 未跑 | — |
| AC4 | `BizOfflineRecordFlowTest#list_filters_by_received_date_method_and_status` | 未跑 | — |
| AC5 | `BizOfflineRecordFlowTest#manager_corrects_amount_and_method_history_keeps_old_values` | 未跑 | — |
| AC5 | `BizOfflineRecordFlowTest#correct_requires_reason_and_a_real_change` | 未跑 | — |
| AC6 | `BizOfflineRecordFlowTest#manager_voids_record_row_kept_with_void_event` | 未跑 | — |
| AC7 | `BizOfflineRecordFlowTest#correct_or_void_after_void_conflicts` | 未跑 | — |
| AC7 | `BizOfflineRecordFlowTest#stale_expected_version_conflicts` | 未跑 | — |
| AC7 | `BizOfflineRecordFlowTest#record_of_another_store_is_not_found` | 未跑 | — |
| AC8 | `BizOfflineRecordFlowTest#clerk_cannot_correct_or_void` | 未跑 | — |
| AC8 | `BizEndpointPermTest`（判权表四行，既有测试方法） | 未跑 | — |
| AC8 | `BizRoleSeedTest`（新码都有 `LABELS`，既有测试方法） | 未跑 | — |
| AC9 | `StoreScopedDailyRoutesFlowTest#new_model_store_member_reaches_offline_records` | 未跑 | — |
| AC10 | 同 AC1 第 2 条 `record_rejects_currency_other_than_store_market` | 未跑 | — |

另需跑的闸门：`ArchitectureTest`（Controller 在 `trade.api`、Service 接口化、Controller 不碰 Mapper）、`BizKeyConventionTest`（新前缀）、pre-push 的 SQL 方言与后端编译。

---

## 待决（需要产品 / 用户拍板）

| # | 问题 | 选项 | 建议 |
|---|---|---|---|
| D1 | 外部记录放哪个域？ | A. trade 域、主库（本文）；B. 支付域、随 pay-svc | **A**。钱不经平台，放进支付域要动 pay-domain、pay-svc 内部端点和 payclient 两种形态，还会撞上「线下不进通道对账」（PRD-支付域 AC-6.3）。选 B 则升 2 档、要 ADR |
| D2 | 权限码新建还是复用？ | A. 新建 `mch:pay:offline:record` / `mch:pay:offline:manage`（本文）；B. 记账复用 `PAY_COLLECT`、更正作废复用 `PAY_REFUND`；C. 复用 `CASH_RECEIVE` | **A**。App 要「店员能记」：店员模板只有只读的 `PAY_ORDER_VIEW`，给只读码写权限是仓库明确反对的做法（`BizOrderController#confirmOfflinePay` 注释）；`PAY_COLLECT` 店员默认没有；`CASH_RECEIVE` 会把订单推成已付，给店员范围太大；`PAY_REFUND` 店长默认没有，和 App「店长能作废」不符 |
| D3 | 带了 `orderNo`，且那张订单正等线下付款（`WAIT_OFFLINE_PAY`），要不要顺手把订单推成已付？ | A. 不推，只作引用（本文）；B. 推 | **A**。确认订单收款已有 `confirm-offline-pay`（权限 `RECEIVE`）和收银台实收（`CASH_RECEIVE`，要金额等于报价），一笔自报流水顺手改订单等于绕开这两道。App 若要「一键」，前端连调两个接口 |
| D4 | 要不要允许补记过去的收款（`receivedAt`），最多往前多少天？ | A. 允许，31 天（本文）；B. 不允许，以记账时间为准；C. 允许但天数另定 | **A**。现金店常有隔天补记；31 天覆盖月结对账。越界回 10400 |
| D5 | 进销存开了的店，外部记录要不要关联一张线下卖出单（`offline-sale` 的 `docNo`），实现「卖了货、收了钱」一次记完？ | A. 本期不做；B. 加可选 `saleDocNo` 引用；C. 一个接口同时减库存和记钱 | **A**。两件事的权限（`biz:stock` 与收款）、所在库（进销存独立库）都不同，合成一个接口要跨库事务；需要时再走 B |
| D6 | 外部记录进不进收款汇总、首页看板、经营报表？ | A. 都不进，只在外部记录列表看（本文）；B. 汇总接口加一段 `offline{count, amountMinor}` 单列；C. 并入营业额 | **A**，后续可做 B。App 当前「收款列表不混入外部记录」；报表里的「外部收款」是另一口径（渠道确认的流入）。选 C 等于让自报数字进经营报表，要先定口径，升 2 档 |
| D7 | 更正 / 作废的时间窗：任何时候都能改，还是只能改当天 / 本月？ | A. 不限（本文）；B. 限当天；C. 限 31 天内 | **C**，与 D4 补记窗口一致。实现上加一行校验，不影响契约形状；拍板前按 A 写 |
| D8 | 店主能否看到全部门店的外部记录（主体级列表）？ | A. 只按当前门店（本文）；B. 加主体级汇总 | **A**。与 `/biz/payment/orders` 一致按 `X-Store-No`；连锁需求出来再说 |
| D9 | 备注、原因的长度上限 | 本文 100 字 | 按 App 表单实际上限定，App 确认即可 |

## 升档条件

出现以下任一情况，本方案改为 2 档：补 §3 选型、§4 风险，并另开 `docs/technical/ADR/ADR-xxx-外部记账归属.md`（本文不写 ADR）。

- D1 选 B（进支付域 / pay-svc，牵涉独立部署的服务边界）；
- D6 选 C（自报金额进经营报表与日结口径，牵涉报表库）；
- D5 选 C（跨进销存独立库的写事务）。

## §6 / §7

实现后再填（`git show --stat` 对账、偏差说明、确认与完成）。
