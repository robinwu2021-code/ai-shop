# 商城 HTTP 与宿主接入（Task4 + Task8）

本文件与 `commerce-mall-openapi.json`（`task8-v1`）描述新增 62 个操作：Task4 的 39 个，加 Task8 线下购买的 23 个（见文末「线下银行转账购买」）。既有 29 个商家注册/门店初始化/基础目录操作及响应不变。HTTP 翻译位于可移植 starter；宿主提供可信会话、权限、信封、平台支付账本和库存适配。没有运营网页或 Flutter 实现。

## 开关与真实可用性

以下是仅配置目录的本地配置示例，不包含正式价格、支付账号或技术验收证明：

```yaml
commerce:
  enabled: true
  catalog:
    enabled: true
  mall:
    enabled: true
    http:
      enabled: true
    payment:
      enabled: false
    refund:
      enabled: false
    recovery:
      enabled: false
shop:
  auth:
    token-store-by-realm:
      operator: db
  commerce:
    mall:
      accepted-deployments: []
      required-ends: {}
      hardware-stock: untracked   # 默认；接进销存时改 coordinated 并配 inventory-mappings
      inventory-mappings: {}
```

使用现有 `api` profile、主库与 commerce 迁移配置。目录管理和零金额来源管理不要求支付 SDK、平台商户号或库存数据库。开启新的商城 HTTP 时，OPS 必须使用 DB 会话并重新登录；需要可用的实时身份/角色权限读取器。未配置或不支持时明确拒绝启动，既有 OPS 默认缓存/内存行为不变。本阶段没有 Redis OPS 适配。

主库包装保持原 DataSource/事务管理器/MyBatis 资源键，额外使用同一 PRIMARY 配置的独立认证读连接池（最大2、最小0、连接等待500ms）。只在自身验证过的 fresh 回调内路由和嵌套复用；业务外层事务被正常挂起。会话、成员、主体状态和 OPS 权限每次读取当前事实，耗尽或数据库不可用返回 CHECK_UNAVAILABLE，不回退缓存。

`accepted-deployments` 是部署所有者提供的验收清单。每项包含 deploymentNo、displayName、code、entitlementCode、allowedBusinessTypes、clientId、routePolicyRef、acceptanceRef。当前可验证的安装路由仅 `commerce-route-v1:BIZ`（END_BIZ_APP）及 `commerce-route-v1:POS`（END_APP_POS）。运营从 deployment-options 选择，不提交自造 client/route/acceptance 字符串。空清单允许草稿配置，不能发布假验收端。

`hardware-stock` 决定商城硬件是否接进销存（2026-11-05 起默认 `untracked`）：

- `untracked`（默认）：商城不接进销存。含硬件的 SKU（单买硬件或套餐+硬件的组合）不因库存映射被挡，readiness 不再给 `INVENTORY_MAPPING_UNAVAILABLE`；预留 / 发货 / 释放直接成功，不占、不扣库存。操作身份与幂等仍由商城自己的履约操作记录保证；查询回 NOT_FOUND，商城据此重放原命令。是否有货由运营线下把关，`inventory-mappings` 被忽略。
- `coordinated`：经进销存的协同库存操作预留与扣减，需要下面的 `inventory-mappings` 与库存端口；缺任何一个都 fail closed（`INVENTORY_MAPPING_UNAVAILABLE`）。

`inventory-mappings`（仅 `coordinated`）按硬件 SKU 号映射库存域的 ownerId、itemId、locationId、mappingVersion。必须是真实平台库存主体、商品与库位；`shop.inventory.enabled=true` 才安装库存端口。报价/落单只检查可信本地配置；实际预留在事务外由库存域重新校验。原操作冻结映射，后续查询、释放和发货使用原主体，不随配置重映射，也不使用买家仓库。

支付协调单独启用 `commerce.mall.payment.enabled=true`，并提供 `commerce.mall.payment.platform-merchant-no` 和逗号分隔的 `commerce.mall.payment.routes`（只开放零现金时可配置 `INTERNAL:ZERO`）。收款主体必须与真实网关本地能力一致。当前协议适配仅现有 CNY/WECHAT:JSAPI；并不支持 APP/H5/TWD。商城权威 appId 绑定的 payer 数据源尚不存在，默认 `ShopMallPayerBindings.UNAVAILABLE`，正金额购买返回 PAYER_BINDING_UNPROVEN，不能借用旧用户身份 fallback。测试中的证明绑定和网关均是受控夹具，未验证真实在线支付。台湾 NewebPay、LINE Pay、JKOPay 和线下付款在 Task8 接续，当前不能宣称台湾可付款。

零现金使用内置 `channel=INTERNAL`、`payMethod=ZERO`，走 core 的持久内部结算（正金额禁止此路由）；仍需要协调开关；`coordinated` 时还需要相应库存映射，客户端不能提交 paid 标记。正金额的 storefront/quote/place 可用性受币种、路由、金额上限及 payer 能力约束。既有 Plan 卡片 purchaseAvailable 通过已授权当前门店的 storefront 精确 SKU 版本与价格计算；无当前门店、无采购权、配置缺失或价格选项不唯一均为 false，价格选项仍明确提供 SKU 版本，不能猜选。

## 请求、身份与金额

使用现有 Bearer 会话与门店上下文。tenant 固定为可信 MAIN。B 端必须有当前门店成员资格，浏览与采购分开，订单/支付/退款恢复要求采购能力；恢复入口不要求尚未购买或已过期业务端的访问证明。路径使用精确 BOOTSTRAP 规则，禁止编码斜线、分号、重复斜线和错误命名空间。

商城买家统一为 `USR:` 或 `MCH:` 加 domain-separated SHA-256 base64url（47字符），原始 user_no 最长64字符仍完整保留于可信身份/付款审计。相同原始号不同身份类型不合并。旧基础契约继续使用原始身份。报价冻结原始 realm/kind/id，仅服务端验证和恢复使用，不输出 ActorContext 或会话指纹。

新接口请求 DTO 不接受 tenant、actor、store、openid、支付成功或供应商事实等未知字段。店号来自路径或 OPS 的明确 storeNo 查询参数，再经公共 facade 校验。金额响应统一 `{ "currency": "TWD", "minor": "69900" }`，minor 为精确十进制整数文本。请求推荐同格式；兼容 0..9007199254740991 的整数数字 token。小数/浮点 token、前导零、负数、long 溢出和不安全数字拒绝。Task4 不重解释 minor，也未加入 TWD quantum 策略；Task8 将显式扩展。Instant 时间为 UTC ISO-8601，毫秒 long 字段保持原 API 标注语义。

响应保留显式 `null`：未付款的 paymentNo/paidAt/errorCode、数字订单 shippingAddress、无审核报价的 reviewQuoteNo、非版本草稿保存的 draftVersionNo，以及未发布目录当前版本/可选描述和端验收字段。OpenAPI 3.1 以明确 null 分支表达；属性可省略不代表可为 null。精确 Money 约束不变。真实 MVC 响应契约门为 `MallOpenApiResponseTest`，运行后执行 `python3 backend/commerce/commerce-spring-boot-starter/src/test/python/validate_mall_openapi.py`（jsonschema 4.x，Draft 2020-12）。

成功信封 `{code:0,msg:"success",data:...}`。新增业务错误的 HTTP 400/403/404/409/503 对应 code=10000+HTTP状态，msg 保留机器原因。既有安全过滤器的会话错误仍使用原宿主信封与错误码。常见原因：FORBIDDEN、NOT_FOUND、INVALID_REQUEST、VERSION_CONFLICT、QUOTE_EXPIRED、QUOTE_USED、IDEMPOTENCY_CONFLICT、DIGITAL_CHANGE_PENDING、CHECKOUT_UNAVAILABLE、PAYER_BINDING_UNPROVEN、PAYMENT_UNAVAILABLE、REFUND_UNAVAILABLE、FULFILLMENT_UNAVAILABLE、SOURCE_MANAGEMENT_UNAVAILABLE、DEPLOYMENT_UNAVAILABLE、CHECK_UNAVAILABLE。storefront 的 readiness.reasons 还区分 PAYMENT_COORDINATION_DISABLED、STORE_UNAVAILABLE、PAYMENT_ROUTE_UNAVAILABLE、INVENTORY_MAPPING_UNAVAILABLE。

## 稳定购买与审核流程

1. OPS 读取 `/ops/biz-mall/catalog`，即使空目录也可用。建立行业、端草稿、FREE/PRO/CHAIN 版本、商品与 SKU；发布提交明确乐观锁版本及 operationNo。已发布快照不变，停售不改写已落单快照。
1a. 商品分类（2026-11-06，[TDD-商城商品分类](../technical/TDD-商城商品分类.md)，commerce V11 `mall_category`）：`GET /ops/biz-mall/categories` 扁平返回（按 level、sortOrder、code），`POST` 新建（`expectedVersion=0`），`PUT /ops/biz-mall/categories/{categoryCode}` 改上级/名称/图标/排序/启停（路径编码为准，编码不可改）。最多 3 级；`names` 为 zh-CN/zh-TW/en 子集。读 = `commerce:catalog:read`，写 = `commerce:catalog:edit`。拒绝原因：`CATEGORY_UNAVAILABLE`（上级或商品引用的分类不存在/已停用）、`CATEGORY_TOO_DEEP`、`CATEGORY_CYCLE`、`CATEGORY_HAS_CHILDREN`（还有启用的下级）、`CATEGORY_IN_USE`（在售商品引用），均为 400。**保存商品时 `categoryCode` 必须是已启用分类**；V11 已把存量商品的分类编码补成一级分类。`/ops/biz-mall/catalog` 增加 `categories`。
2. B 端读取 `/biz/stores/{storeNo}/mall/products`。只返回当前已发布并在售内容和实际 readiness；不以管理目录接口代替。响应增加 `categories`：本店可见商品用到的启用分类及其全部上级（`code/parentCode/level/names/icon/sortOrder`），客户端按 `parentCode` 组树、按商品 `product.categoryCode` 归类。SKU 组件带可选 `title`（1~64 字，随版本冻结，2026-11-06 commerce V12，见 [TDD-商城SKU组件名称](../technical/TDD-商城SKU组件名称.md)）；为 null 时客户端按 `kind` 兜底显示。
3. 普通或升级报价由服务器冻结明细与版本。升级 `creditBreakdown` 区分 newConversion C、historicalAvailable、applied U、reservedHistorical、remainingAfter 与 valuationAt。客户端不把历史余额抵扣误标为旧 Plan 价值。
4. place 绑定路径门店、quoteNo、idempotencyKey、必要配送地址。订单详情提供独立 paymentStatus/fulfillmentStatus/refundStatus、冻结报价、实际付款尝试/发货/退款，客户端不从简化总状态推导商业事实。
5. payment create 返回持久尝试与受限 checkoutParameters，不等于付款。GET payments 读取持久结果；POST payments/refresh（空 `{}`）先授权订单再查询可信原尝试。取消也可能保留 CANCEL_PENDING/UNKNOWN。
6. OPS 发货需要 `commerce:mall:shipment:write` 和订单读权。operationNo、组件、数量、承运/单号不可变；实际 OPS 号冻结到发货操作/库存单。部分发货仅消耗该次数量，未知操作不能重建成另一笔。
7. 买家退款请求保持原始意图不可变。OPS 创建独立 review-quote，用原 requestDigest 和新的 reviewQuoteNo；批准绑定确切 reviewQuoteNo/approvalDigest/证据，限额不超过原请求与当前剩余。旧 fresh approvalDigest 方式保留兼容。REQUESTED 的人工作废为 DECLINED，只改变审核请求；已发出/未知退款不能靠拒绝解除冻结。
8. 来源管理只支持有期限的独立端零金额 GRANT，以及原始 cash/credit/basis 全零且未转换/退款/冻结/撤销的 GRANT/gift 撤销。FREE 受保护；付费来源即使已过期也必须走退款。新 REVOKED 保存实际截断证据、已用历史、独立剩余时长、版本和审计。HTTP 不提供假完整 Plan 赠送。

## 资金与恢复边界

现有 `stl_payment` 是唯一资金账本。PLATFORM_MALL 显式分流 callback、PaymentReconReconciler 与 OrderPaidReconciler；通用 consumer 结算/关闭/退款写入口不得改写平台意图。原支付意图与接收主体、币种、金额、provider号及真实 paidAt 一致才产生成功事实。NOT_FOUND/超时/请求失败不是关闭证明；派发前本地 tombstone 排除迟到 create，派发后需要真实供应商关闭证据。

可信 `paidAtPrecisionMs` 随 gateway→宿主收据→PaymentOutcome→VerifiedPaymentFact/订单付款明细传递：旧构造器与缺失字段为 1ms，微信 seconds-formatted success_time 为 1000ms；原 paidAt 始终是不变的提供方时间下界，不改为查询时间。按毫秒桶 `[paidAt, paidAt+precision-1]` 检查准备时间是否重叠，拒绝真正早于准备时间的整个桶；桶尾晚于订单截止时间则记录真实现金但进入异常退款、不给权益。1ms 且 paidAt 等于截止时间仍兼容旧规则。精度仅允许 1/1000、算术溢出拒绝；客户端请求不能设置。第一次事实含精度不可变；旧 1ms 事实 digest 不变。

退款审核证据最多 1024 个 Java UTF-16 code unit，核心与宿主接受范围一致，完整证据持久保留且重试不能更换。微信退款的通道描述独立从原证据按 Unicode code point 截取到 UTF-8 80 字节，不把通道限制施加于完整审核记录。[微信退款接口](https://pay.wechatpay.cn/doc/v3/merchant/4012791903) 与 [付款时间定义](https://pay.wechatpay.cn/doc/v3/merchant/4012791858) 是对应协议依据。

生命周期 3 的协调库存只能由 CoordinatedInventoryService 释放/发货；旧 release/cancel/allocated/fulfillment-reference 入口不能修改其库存与证据。查询核对实际生命周期、状态及行数量后才认证预留，异常状态失败关闭。

平台支付与退款的 SUCCESS 和 `platform_handoff_state=PENDING` 在同一账本事务提交。提交后才调用 core，callback ACK 对应现金与交接已持久，而非下游已经发放。下游失败保留 PENDING；重试不新建支付、不替换第一次现金证明。真实关单后晚到现金仍记录并进入异常退款，不补发权益。供应商/库存 IO 均不在商业或宿主账本事务中。

启用 `shop.job.enabled=true`、`commerce.mall.recovery.enabled=true`（且支付协调开启）安装 `commerce-mall-recovery` JobHandler。默认 cron `15 */2 * * * *`，batch-size=50（允许1..100），ShedLock 90秒。账本 handoff 与 core 订单使用各自轮转游标，单个失败不垄断后续批次；游标重启可重扫，幂等事实在 DB。公开 `MallRecoveryFacade` 先读取有界标识，再逐条在事务外 refresh、recover refund/late payment、最后 recover 原库存释放。扫描不是付款或退款最终成功证明。没有支付恢复 facade 时不提供假成功任务。

## 迁移与 Task8 接续

commerce 独立迁移到 V9；Task4 新增 V8 审核报价、V9 零金额来源撤销。宿主到 V625（V624 平台发送/关闭/现金交接字段与索引，V625 九项权限），库存到 V14（原操作映射、释放栅栏、部分发货证据）。生成 schema-test.sql 与 inventory-h2 baseline 同步。旧迁移未改写。

Task8 可复用 public MallFacade/StorefrontFacade/MallQueryFacade、PaymentFacade.refresh、MallRecoveryFacade、RefundFacade 的请求/独立审核/可信恢复，以及原始 PaymentIntentCommand/PaymentQuery、ActorContext 可选 rawIdentity。host 的 PlatformCollectionService/PlatformRefundService/PlatformMoneyHandoffService 继续拥有唯一账本；ShopMallPayerBindings 是未来权威 app-bound 证明扩展点。库存 CoordinatedInventoryService 继续拥有真实库存。Task8 新增线下证据/代客订单/渠道设置及金额策略，不使用零金额 GRANT 假装实收，不直接由 host 写商业表；迁移从 commerce V10、宿主 V626、库存 V15 起按实际需要追加。

## 部署面（2026-11-04 本地联调后修正）

- **商家路由在 api 实例，运营路由在 ops 实例**：`application.yml` 的 api 段设 `commerce.mall.http.operator.enabled=false`，ops 段设 `commerce.mall.http.buyer.enabled=false`；两个实例都带相同的 `commerce.*` 开关与宿主端口（`ShopMallConfiguration` 在两个 profile 都装配）。此前运营路由只装在 api 实例上，而运营鉴权链只在 ops 实例，真实拆分部署下 `/ops/biz-mall/**` 不可用。`ControllerProfileTest.commerceSurfacesFollowProfiles` 守着这两项配置。
- 两个实例都需要 `shop.auth.token-store-by-realm.operator=db`、`merchant=db`，同一主库。
- `CommerceIdGenerator` 默认由 starter 提供（32 位随机十六进制），宿主可覆盖。
- 发布三档前，部署须配置 `shop.commerce.mall.required-ends.<经营类型>`（FREE 必含的端）和 `accepted-deployments`。
- 本地联调已知缺口：`/biz/merchant/quick-start` 没有市场输入，新主体默认 CN、FREE 期按 CNY 冻结，台湾门店看不到 TWD 商品。需由注册进件链路给出可信的市场来源（不在商城范围）。

## 线下银行转账购买（Task8）

**开关**（在 Task4 配置之上追加；任一缺失则不装配，缺端口直接启动失败）：

```yaml
commerce:
  mall:
    payment:
      enabled: true
      platform-merchant-no: <平台收款主体，与收款档案一致>
      routes: OFFLINE:BANK_TRANSFER   # 可与其它路由逗号并列
    offline:
      enabled: true
      profile-no: <收款档案号>
    refund:
      enabled: true                    # 线下退款出款确认需要
    recovery:
      enabled: true                    # 核款/作废中断后的恢复
```

付款窗口来自收款档案（`paymentWindowMs`，开发示例 24 小时），下单时冻结为 `paymentDueAt`，不续期。金额不符的实收只记为 `MALL_UNMATCHED` 待处置，首期不自动补差、拆款或发权益；由运营经「作废」登记处置说明，或经线下退款出款退回。

**B 端**（`/biz/stores/{storeNo}/mall/offline`，均需采购职责）：

| 步骤 | 接口 | 说明 |
| --- | --- | --- |
| 报价 | `POST /quotes` | 条款只取部署配置的收款档案；随后用既有 `POST /biz/stores/{storeNo}/mall/orders` 下单 |
| 发出指引 | `POST /orders/{orderNo}/instructions`（`{}`） | 幂等；返回冻结的收款信息、应付金额、`paymentDueAt` |
| 查看指引 | `GET /orders/{orderNo}/instructions` | 未发出返回 `OFFLINE_INSTRUCTIONS_NOT_ISSUED` |
| 预留凭证 | `POST /orders/{orderNo}/evidence`（`{}`） | 返回 `assetKey`，用途固定为 BUYER_RECEIPT |
| 上传凭证 | `POST /orders/{orderNo}/evidence/content?assetKey=` | 请求体为图片字节，`image/jpeg` 或 `image/png`，≤10MB；每个 key 只能上传一次 |
| 读凭证 | `GET /orders/{orderNo}/evidence/url?assetKey=` | 仅本人凭证，5 分钟签名地址 |
| 付款申报 | `POST /orders/{orderNo}/claims` | 申报**不是**到账，不产生付款或权益 |
| 申报列表 | `GET /orders/{orderNo}/claims` | 仅本人申报；状态 SUBMITTED / REVIEWING / CONFIRMED / MISMATCHED / DECLINED |

申报被拒（DECLINED）后用新的 `claimNo` 重新申报。付款结果一律回读订单详情的 `paymentStatus` / `fulfillmentStatus`。

**运营端**（`/ops/biz-mall/offline`，门店用 `storeNo` 查询参数）：

| 接口 | 权限码 | 说明 |
| --- | --- | --- |
| `GET/PUT /profile` | `commerce:collection:profile:read` / `:write` | 收款档案；每次修改生成新版本，已发出的指引按原版本 |
| `GET /claims` | `commerce:mall:offline:review` | 待审核申报队列，按 (orderNo, claimNo) 翻页 |
| `POST /quotes`、`POST /orders`、`POST /orders/{no}/instructions` | `commerce:mall:assisted:purchase` | 代客：买方为 STORE，发起人为真实运营 |
| `GET /orders/{no}/instructions`、`GET /orders/{no}/claims` | `commerce:mall:order:read` | |
| `POST /orders/{no}/evidence`（`{"purpose":…}`）、`…/evidence/content`、`…/evidence/url` | `commerce:mall:offline:evidence` | 用途 PLATFORM_RECEIPT / RECONCILIATION / REFUND_PROOF |
| `POST /orders/{no}/claims/{claimNo}/decline` | `commerce:mall:offline:review` | 拒绝申报不等于无款，不释放占用 |
| `POST /orders/{no}/confirmations` | `commerce:mall:offline:confirm` | 录入实际到账；金额不符 → UNMATCHED，不发权益 |
| `POST /orders/{no}/voids` | `commerce:mall:offline:void` | 对账作废；之后来款仍入账并走退款 |
| `POST /orders/{no}/refunds/{refundNo}/payouts` | `commerce:mall:offline:refund:confirm` | 录入实际出款；金额须等于退款意图，银行流水唯一 |

动作返回 `ACCEPTED / APPLIED / UNMATCHED / REJECTED`。ACCEPTED 表示已受理、资金端未确认，由恢复任务继续；REJECTED 带原因码返回。权限码由迁移 V627 预置给 SUPER_ADMIN。

常见原因码：`OFFLINE_UNAVAILABLE`、`OFFLINE_INSTRUCTIONS_NOT_ISSUED`、`OFFLINE_INTENT_REQUIRED`、`EVIDENCE_FORBIDDEN`、`EVIDENCE_UNAVAILABLE`、`EVIDENCE_ALREADY_STORED`、`CLAIM_ALREADY_REVIEWED`、`OFFLINE_RECEIVER_MISMATCH`、`OFFLINE_BANK_REFERENCE_CONFLICT`、`OFFLINE_HISTORICAL_MODE_REQUIRED`、`OFFLINE_ALREADY_PAID`、`OFFLINE_UNMATCHED_DISPOSITION_REQUIRED`、`OFFLINE_PAYOUT_AMOUNT_MISMATCH`、`OFFLINE_PROFILE_MERCHANT_MISMATCH`、`STORE_UNAVAILABLE`。

### 2026-11-11 · 线下升级报价

`POST /biz/stores/{storeNo}/mall/offline/upgrade-quotes`：商家采购身份及门店归属沿原线下采购认证。JSON 为 `targetPlanVersionNo`、`targetSkuVersionNo`、`operationNo`、`expectedEntitlementVersion`、`currency`。返回同一 `QuoteView`，后续复用 `POST .../mall/orders` 和原订单线下指引/核款流程。

估值、来源抵扣、精确升级时长、TWD 收付量子、报价过期与版本冲突沿原升级实现。平台收款档案在 commerce 事务外读取并冻结在报价内；币种不符拒绝。申报仍不代表真实收款，不新增自动补差或自动发权益。

运营订阅 GET `/ops/stores/{storeNo}/subscription` 沿 `commerce:subscription:read`，增加 `businessTypeCode`、`currency`、`currentPlanVersionNo`、`serverNow`（毫秒）摘要。当前方案由服务器时间选取生效中的 NORMAL 固定方案，否则取初始 FREE；未来、已撤销和独立端来源不会成为当前方案。代客购买仍由正式报价校验在售、经营类型、币种和订阅资格。


## 销售代收（2026-11-13，实施增量）

沿原线下订单新增 `OFFLINE/SALES_COLLECTION`。银行仍是 `OFFLINE/BANK_TRANSFER`，旧请求不传方式维持银行语义。

- 管理档案 GET `/ops/biz-mall/offline/profile?currency=TWD&collectionMethod=SALES_COLLECTION`；PUT 使用部署的原档案号加 `-sales` 后缀，传 `collectionMethod`、平台主体、币种、付款窗口、`contactInstructions` 与 enabled；银行三字段置 null。档案新版本不改已冻订单。
- 运营及商户 `/offline/quotes`、商户 `/offline/upgrade-quotes` 增加可选 `collectionMethod`；只有启用条款和明确路由 `OFFLINE:SALES_COLLECTION` 才可购买。报价 `collectionMethod`、付款指引 `collectionMethod/platformMerchantNo` 为服务端冻结事实。
- `GET /ops/biz-mall/offline/collectors?storeNo=` 需 `commerce:mall:offline:confirm`，返回 `collectorNo/displayName` 最小字段。销售沿平台启用员工及代购权限；当前支持全量/主体数据域，社区或网点受限员工不扩权为商城销售。
- 原 confirmations 增加 `collectorNo/collectionReference`，销售订单 bankReference、claimNo 为 null；确认人取会话，actualReceiver 仍是平台主体。提交真实金额/币种/秒精度收款时间、私有平台凭证和审计原因。下单、准备指引都不是实收。
- 新增销售资金唯一键为租户、方式、销售、收款凭据号的摘要，与原银行唯一键分开。同凭据跨订单/换操作号仍拒绝；同原键/原内容继续原结果恢复。金额不符不发权益；原晚到、作废、退款和交付恢复规则继续有效。
- App 销售订单显示联系说明与待核款状态，隐藏银行账户/备注及银行申报；订单和订阅共同确认后展示权益。运营代购的 STORE 原单可由该店有采购职责的商户回读。

本地测试不代表目标环境已配置该方式、发布套餐或发生真实实收。部署先应用增量迁移 `V666__platform_sales_collection.sql`，保留旧银行路由后追加 SALES_COLLECTION，配置正式销售条款，再做同店实收验收。
