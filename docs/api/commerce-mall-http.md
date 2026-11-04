# 商城 HTTP 与宿主接入（Task4）

本文件与 `commerce-mall-openapi.json` 描述新增 39 个操作。既有 29 个商家注册/门店初始化/基础目录操作及响应不变。HTTP 翻译位于可移植 starter；宿主提供可信会话、权限、信封、平台支付账本和库存适配。没有运营网页或 Flutter 实现。

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
      inventory-mappings: {}
```

使用现有 `api` profile、主库与 commerce 迁移配置。目录管理和零金额来源管理不要求支付 SDK、平台商户号或库存数据库。开启新的商城 HTTP 时，OPS 必须使用 DB 会话并重新登录；需要可用的实时身份/角色权限读取器。未配置或不支持时明确拒绝启动，既有 OPS 默认缓存/内存行为不变。本阶段没有 Redis OPS 适配。

主库包装保持原 DataSource/事务管理器/MyBatis 资源键，额外使用同一 PRIMARY 配置的独立认证读连接池（最大2、最小0、连接等待500ms）。只在自身验证过的 fresh 回调内路由和嵌套复用；业务外层事务被正常挂起。会话、成员、主体状态和 OPS 权限每次读取当前事实，耗尽或数据库不可用返回 CHECK_UNAVAILABLE，不回退缓存。

`accepted-deployments` 是部署所有者提供的验收清单。每项包含 deploymentNo、displayName、code、entitlementCode、allowedBusinessTypes、clientId、routePolicyRef、acceptanceRef。当前可验证的安装路由仅 `commerce-route-v1:BIZ`（END_BIZ_APP）及 `commerce-route-v1:POS`（END_APP_POS）。运营从 deployment-options 选择，不提交自造 client/route/acceptance 字符串。空清单允许草稿配置，不能发布假验收端。

`inventory-mappings` 按硬件 SKU 号映射库存域的 ownerId、itemId、locationId、mappingVersion。必须是真实平台库存主体、商品与库位；`shop.inventory.enabled=true` 才安装库存端口。报价/落单只检查可信本地配置；实际预留在事务外由库存域重新校验。原操作冻结映射，后续查询、释放和发货使用原主体，不随配置重映射，也不使用买家仓库。

支付协调单独启用 `commerce.mall.payment.enabled=true`，并提供 `commerce.mall.payment.platform-merchant-no` 和逗号分隔的 `commerce.mall.payment.routes`（只开放零现金时可配置 `INTERNAL:ZERO`）。收款主体必须与真实网关本地能力一致。当前协议适配仅现有 CNY/WECHAT:JSAPI；并不支持 APP/H5/TWD。商城权威 appId 绑定的 payer 数据源尚不存在，默认 `ShopMallPayerBindings.UNAVAILABLE`，正金额购买返回 PAYER_BINDING_UNPROVEN，不能借用旧用户身份 fallback。测试中的证明绑定和网关均是受控夹具，未验证真实在线支付。台湾 NewebPay、LINE Pay、JKOPay 和线下付款在 Task8 接续，当前不能宣称台湾可付款。

零现金使用内置 `channel=INTERNAL`、`payMethod=ZERO`，走 core 的持久内部结算（正金额禁止此路由）；仍需要协调开关和相应库存映射，客户端不能提交 paid 标记。正金额的 storefront/quote/place 可用性受币种、路由、金额上限及 payer 能力约束。既有 Plan 卡片 purchaseAvailable 通过已授权当前门店的 storefront 精确 SKU 版本与价格计算；无当前门店、无采购权、配置缺失或价格选项不唯一均为 false，价格选项仍明确提供 SKU 版本，不能猜选。

## 请求、身份与金额

使用现有 Bearer 会话与门店上下文。tenant 固定为可信 MAIN。B 端必须有当前门店成员资格，浏览与采购分开，订单/支付/退款恢复要求采购能力；恢复入口不要求尚未购买或已过期业务端的访问证明。路径使用精确 BOOTSTRAP 规则，禁止编码斜线、分号、重复斜线和错误命名空间。

商城买家统一为 `USR:` 或 `MCH:` 加 domain-separated SHA-256 base64url（47字符），原始 user_no 最长64字符仍完整保留于可信身份/付款审计。相同原始号不同身份类型不合并。旧基础契约继续使用原始身份。报价冻结原始 realm/kind/id，仅服务端验证和恢复使用，不输出 ActorContext 或会话指纹。

新接口请求 DTO 不接受 tenant、actor、store、openid、支付成功或供应商事实等未知字段。店号来自路径或 OPS 的明确 storeNo 查询参数，再经公共 facade 校验。金额响应统一 `{ "currency": "TWD", "minor": "69900" }`，minor 为精确十进制整数文本。请求推荐同格式；兼容 0..9007199254740991 的整数数字 token。小数/浮点 token、前导零、负数、long 溢出和不安全数字拒绝。Task4 不重解释 minor，也未加入 TWD quantum 策略；Task8 将显式扩展。Instant 时间为 UTC ISO-8601，毫秒 long 字段保持原 API 标注语义。

成功信封 `{code:0,msg:"success",data:...}`。新增业务错误的 HTTP 400/403/404/409/503 对应 code=10000+HTTP状态，msg 保留机器原因。既有安全过滤器的会话错误仍使用原宿主信封与错误码。常见原因：FORBIDDEN、NOT_FOUND、INVALID_REQUEST、VERSION_CONFLICT、QUOTE_EXPIRED、QUOTE_USED、IDEMPOTENCY_CONFLICT、DIGITAL_CHANGE_PENDING、CHECKOUT_UNAVAILABLE、PAYER_BINDING_UNPROVEN、PAYMENT_UNAVAILABLE、REFUND_UNAVAILABLE、FULFILLMENT_UNAVAILABLE、SOURCE_MANAGEMENT_UNAVAILABLE、DEPLOYMENT_UNAVAILABLE、CHECK_UNAVAILABLE。storefront 的 readiness.reasons 还区分 PAYMENT_COORDINATION_DISABLED、STORE_UNAVAILABLE、PAYMENT_ROUTE_UNAVAILABLE、INVENTORY_MAPPING_UNAVAILABLE。

## 稳定购买与审核流程

1. OPS 读取 `/ops/biz-mall/catalog`，即使空目录也可用。建立行业、端草稿、FREE/PRO/CHAIN 版本、商品与 SKU；发布提交明确乐观锁版本及 operationNo。已发布快照不变，停售不改写已落单快照。
2. B 端读取 `/biz/stores/{storeNo}/mall/products`。只返回当前已发布并在售内容和实际 readiness；不以管理目录接口代替。
3. 普通或升级报价由服务器冻结明细与版本。升级 `creditBreakdown` 区分 newConversion C、historicalAvailable、applied U、reservedHistorical、remainingAfter 与 valuationAt。客户端不把历史余额抵扣误标为旧 Plan 价值。
4. place 绑定路径门店、quoteNo、idempotencyKey、必要配送地址。订单详情提供独立 paymentStatus/fulfillmentStatus/refundStatus、冻结报价、实际付款尝试/发货/退款，客户端不从简化总状态推导商业事实。
5. payment create 返回持久尝试与受限 checkoutParameters，不等于付款。GET payments 读取持久结果；POST payments/refresh（空 `{}`）先授权订单再查询可信原尝试。取消也可能保留 CANCEL_PENDING/UNKNOWN。
6. OPS 发货需要 `commerce:mall:shipment:write` 和订单读权。operationNo、组件、数量、承运/单号不可变；实际 OPS 号冻结到发货操作/库存单。部分发货仅消耗该次数量，未知操作不能重建成另一笔。
7. 买家退款请求保持原始意图不可变。OPS 创建独立 review-quote，用原 requestDigest 和新的 reviewQuoteNo；批准绑定确切 reviewQuoteNo/approvalDigest/证据，限额不超过原请求与当前剩余。旧 fresh approvalDigest 方式保留兼容。REQUESTED 的人工作废为 DECLINED，只改变审核请求；已发出/未知退款不能靠拒绝解除冻结。
8. 来源管理只支持有期限的独立端零金额 GRANT，以及原始 cash/credit/basis 全零且未转换/退款/冻结/撤销的 GRANT/gift 撤销。FREE 受保护；付费来源即使已过期也必须走退款。新 REVOKED 保存实际截断证据、已用历史、独立剩余时长、版本和审计。HTTP 不提供假完整 Plan 赠送。

## 资金与恢复边界

现有 `stl_payment` 是唯一资金账本。PLATFORM_MALL 显式分流 callback、PaymentReconReconciler 与 OrderPaidReconciler；通用 consumer 结算/关闭/退款写入口不得改写平台意图。原支付意图与接收主体、币种、金额、provider号及真实 paidAt 一致才产生成功事实。NOT_FOUND/超时/请求失败不是关闭证明；派发前本地 tombstone 排除迟到 create，派发后需要真实供应商关闭证据。

平台支付与退款的 SUCCESS 和 `platform_handoff_state=PENDING` 在同一账本事务提交。提交后才调用 core，callback ACK 对应现金与交接已持久，而非下游已经发放。下游失败保留 PENDING；重试不新建支付、不替换第一次现金证明。真实关单后晚到现金仍记录并进入异常退款，不补发权益。供应商/库存 IO 均不在商业或宿主账本事务中。

启用 `shop.job.enabled=true`、`commerce.mall.recovery.enabled=true`（且支付协调开启）安装 `commerce-mall-recovery` JobHandler。默认 cron `15 */2 * * * *`，batch-size=50（允许1..100），ShedLock 90秒。账本 handoff 与 core 订单使用各自轮转游标，单个失败不垄断后续批次；游标重启可重扫，幂等事实在 DB。公开 `MallRecoveryFacade` 先读取有界标识，再逐条在事务外 refresh、recover refund/late payment、最后 recover 原库存释放。扫描不是付款或退款最终成功证明。没有支付恢复 facade 时不提供假成功任务。

## 迁移与 Task8 接续

commerce 独立迁移到 V9；Task4 新增 V8 审核报价、V9 零金额来源撤销。宿主到 V556（V555 平台发送/关闭/现金交接字段与索引，V556 九项权限），库存到 V14（原操作映射、释放栅栏、部分发货证据）。生成 schema-test.sql 与 inventory-h2 baseline 同步。旧迁移未改写。

Task8 可复用 public MallFacade/StorefrontFacade/MallQueryFacade、PaymentFacade.refresh、MallRecoveryFacade、RefundFacade 的请求/独立审核/可信恢复，以及原始 PaymentIntentCommand/PaymentQuery、ActorContext 可选 rawIdentity。host 的 PlatformCollectionService/PlatformRefundService/PlatformMoneyHandoffService 继续拥有唯一账本；ShopMallPayerBindings 是未来权威 app-bound 证明扩展点。库存 CoordinatedInventoryService 继续拥有真实库存。Task8 新增线下证据/代客订单/渠道设置及金额策略，不使用零金额 GRANT 假装实收，不直接由 host 写商业表；迁移从 commerce V10、宿主 V557、库存 V15 起按实际需要追加。
