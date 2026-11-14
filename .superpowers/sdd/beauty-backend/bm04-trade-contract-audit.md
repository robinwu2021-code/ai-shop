# BM04 / SRC-01 共用交易合同只读审计

日期：2026-10-22。基线：`f7b382754`，`codex/beauty-foundation`。唯一读取工作树：`/Users/robin/.codex/worktrees/beauty-data/ai-shop-codex`。本报告路径以下相对路径均以该目录为根；`:行号` 是实际源码证据。未改工作树、未提交、未跑测试、未访问服务/网络；部署场景为待实施验证，不能视为已通过。

## 1. 结论与范围

BM04可以在现有普通 `ord_order/ord_sub_order/ord_item` 和支付账上实现真实购服务，但不能只写美业适配器就宣称SRC-01闭环。最小必补是 **共享交易所有者的版本化报价、本人确认、持久操作原号查询、受管理服务来源及付款事实投影**。美业只存意图、版本化来源绑定和数量分配；不得复制报价计算器、订单账、支付账。跨域唯一接口/DTO放 `shop-base/ai.neargo.shop.spi.<owner>`。

建议主线：B端提交冻结范围和真实buyerRef → trade owner准备报价/接管凭据 → 真实C身份确认相同报价 → owner原子消费授权并创建普通订单 → 原支付所有者发起/查询 → 美业原号读取并绑定来源。也可由B提交消费已签发的本人确认ticket，但不能由B签发、代确认或设置当前LoginUser。权益服务单只属于已有权益/储值消费，不替代普通买服务。

计划依据：TDD仅读取§21（1232–1330）和§31（1637起）。§21:1269明确BM04包含真实报价/来源/逐来源账单/恢复；1294要求先核对owner合同；1296后明确BM05负责开工与履约。任务JSON `beauty-development-plan.json:208/376/395`（SHR-03/WO-01/SRC-01）；方法JSON `beauty-java-method-design.json:5372/5593/5619/5959` 指定submissionWrite/bill/recovery/trace。原任务NOT_STARTED是索引，不能由本审计改成完成。

## 2. 精确既有合同与复用判定

### 2.1 商品目录：可复用事实，不是成交报价

* `ai.neargo.shop.spi.product.GoodsQueryPort#snapshot(List<String> skuNos): Map<String,SkuSnapshot>`（shop-base/.../spi/product/GoodsQueryPort.java:19）；重载 `snapshot(List<String>, Map<String,String> storeByEntity)`（:31）。实际owner `ai.neargo.shop.product.port.GoodsQueryPortImpl#snapshot(List<String>,Map<String,String>)`（shop-core/.../product/port/GoodsQueryPortImpl.java:223）；门店价/活动覆盖应由此owner提供，行业不改写金额。
* `ai.neargo.shop.spi.product.ServiceCatalogFactsPort#store(String storeNo): Store`（:6）；`#service(String storeNo,String skuNo): Service`（:8）。`Service`含priceMinor、durationMin、goodsRevision、skuRevision（:13），但:12明确主档revision不覆盖门店/活动价、不承诺原子报价。owner `ai.neargo.shop.product.port.ServiceCatalogFactsPortImpl#service`（:43–60）组合门店、SERVICE主档、目录可见、GoodsQueryPort价格；是BM01可复用能力。
* `ai.neargo.shop.spi.product.ServiceProductFactsPort`真实owner `ai.neargo.shop.product.port.ServiceProductFactsPortImpl#get(String entityNo,String storeNo,String skuNo)`（:29）检查MAIN/CN、同主体同店、`PrdGoods.type == SERVICE`（:34–45）。现有范围就是MAIN/CN，不能声称自动多币种。
* `ai.neargo.shop.spi.product.StoreSalesCatalogPort#read(String entityNo,String storeNo,Instant at): Snapshot`（:16）；Snapshot有catalog/head/effective revision/hash（:104）。它是可见性、分组、销售窗口，不存另一套价格。

### 2.2 普通订单：核心创建可复用，授权/报价/查询边界需增量

`ai.neargo.shop.trade.service.OrderService`，路径 `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/OrderService.java`：

| 实际签名 | 行 | 判定 |
|---|---:|---|
| `OrderVO preview(CreateOrderCommand cmd)` | 18 | 只读试算，不持久报价；实现642–673取currentUser，不能B伪装buyer调用 |
| `PointsQuote quotePoints(CreateOrderCommand cmd)` | 21 | 积分可用报价，非普通报价版本合同；实现677取currentUser |
| `OrderVO create(CreateOrderCommand cmd,String idempotencyKey)` | 36 | C当前买家创建，内部转createFor |
| `OrderVO createFor(String userNo,CreateOrderCommand cmd,String idempotencyKey)` | 49 | 显式买家、可由owner复用；46–47明确调用者负责代客权限 |
| `OrderVO createFor(String userNo,CreateOrderCommand cmd,String idempotencyKey,Integer payMinutes)` | 62 | 同一路径，不开放payMinutes任意输入 |
| `OrderPayMethodVO payMethods(String orderNo)` | 79 | C属主读 |
| `PayResult pay(String orderNo,String payChannel)` | 81 | C属主发起支付，不是B付款授权合同 |
| `OrderVO payResult(String orderNo)` | 84 | 实现2291只是detail，非原支付号query |
| `void markPaid(String orderNo,String payChannel,String payTradeNo)` | 87 | 权威回执之后的投影方法，禁止行业任意调用 |
| `OrderVO detail(String orderNo,String client)` | 97 | 实现2483取currentUser；私有detailOf(2495)不是跨域已可调用接口 |

嵌套命令完整主签名（:269–290）：
`CreateOrderCommand(List<Item> items,String fulfillment,String pickupNo,String addressId,String couponNo,Long usePoints,String remark,Long appointmentAt,String payMode,String payScene,String appointmentSlotNo,String groupNo,boolean openGroup,Map<String,String> activityChoices,Map<String,String> storeChoices,Map<String,String> addressChoices)`；`Item(String goodsNo,String skuNo,int qty)`（:365）。没有quoteRef/quoteVersion/confirmation/owner source；`PayResult(String orderNo,String payChannel,Map<String,String> payParams,boolean settled)`（:380）没有outTradeNo。

实现证据 `OrderServiceImpl.java`：1502–1532为统一createFor/幂等路径；1519哈希命令，1521以`POST /mp/order`和buyer记录；1567起doCreate，1586落店、1588履约、1610付款方式、1816创建意图、1827预留、1831普通订单插入。`OrderCreateRequestHasher#hash(CreateOrderCommand)`（.../order/service/create/OrderCreateRequestHasher.java:26）哈希输入，不是有效价格版本。

必须注意：createFor不是彻底无登录上下文的能力。`OrderServiceImpl:3037`空items会读当前人的购物车（3326）；`storesOfEntities:1029`还取当前人的community，非法storeChoices在1047–1063被忽略而回落。新owner合同必须要求非空显式行、单主体单店、强制同店、禁空值回落/购物车分支；必要时将显式buyer路由参数下沉共用私有计算方法，不能伪造LoginUser。

已有显式买家价格算法入口 `ai.neargo.shop.trade.service.LegacyCouponOrderPricingService#snapshot(String userNo,OrderService.CreateOrderCommand command): Snapshot`（接口:11；实现OrderServiceImpl:116–140），返回`Snapshot(String currency,List<MerchantGroup> groups,String commandHash)`。仅为历史券checkout经济计算；可提炼内部共用计算，不应将该legacy协议冒充通用报价、更不能假设包括所有优惠路径。

### 2.3 原键建单恢复：内部事实已有，公共read缺失

* `ai.neargo.shop.idem.IdempotencyService#execute(String idemKey,String endpoint,String userNo,String requestHash,Class<T> type,Supplier<T> action): T`（shop-store-mybatis/.../IdempotencyService.java:45）。:29 TTL为24h；:47空key放行；:69私有replay读取并FOR UPDATE；:76买家、:79摘要检查；空result抛inflight。不是跨服务可公开的无副作用lookup，也不能把幂等缓存等同长期来源账。
* `ai.neargo.shop.trade.order.service.create.OrderCreationRecoveryService#register(String proposedPayNo,String userNo,String idemKey,String inputHash,List<StockPort.SkuQty> stockNeeds): String`（接口:8）；`void enlist(String payNo)`（:10）；`int recoverAbandoned(long now)`（:11）。实现 `...impl.OrderCreationRecoveryServiceImpl:55–82` 独立事务持久OrdCreateIntent，以endpoint/key去重，核buyer/hash后返原payNo；142–152查原订单确认COMMITTED；其它路径是库存补偿。**没有public lookup/resolveByRequestKey方法**。
* 即使OrdCreateIntent已有原号，createFor重放先要重走部分报价/校验；旧缓存过期、商品后来下架/变价时不应以再次create来当查询。原intent已RELEASED也不应任意复活。响应丢失后服务端可确认原no，新增共享read应直接查owner操作事实与原订单，区分NOT_ACCEPTED/PENDING/COMMITTED/REJECTED/UNKNOWN。
* `OrderServiceImpl#readCreated(EntitlementContext ctx,String checkoutNo,String userNo)`（:1543）仅实现LegacyCouponBoundOrderService，核legacy journal COMMIT和selectionActor；不是普通下单原键查询。

### 2.4 支付receipt：真实owner已有，但安全原号流程需增量

`ai.neargo.shop.spi.settle.SettlePort`（shop-base/.../spi/settle/SettlePort.java）：

| 方法/DTO | 行 | 现状 |
|---|---:|---|
| `String openPayment(PaymentOpen cmd)` | 91 | owner生成实际outTradeNo |
| `PayInitResult initPayment(PaymentOpen cmd)` | 111 | 持久流水+通道发起 |
| `PayInitResult(boolean success,String outTradeNo,String payChannel,Map<String,String> payParams,String message)` | 120 | 原号在这里有，OrderService.PayResult丢掉了 |
| `String settlePayment(PaymentSettled cmd)` | 148 | 资金owner确认；非买家/B手工成功API |
| `PaymentReceipt successPayment(String orderNo)` | 151 | 资金事实，可复用；取最新成功不适合精确attempt恢复 |
| `PaymentReceipt successPayment(String orderNo,String channel,String tradeNo)` | 154 | 精确已成功attempt，可复用 |
| `PaymentReceipt paymentByOutTradeNo(String outTradeNo)` | 157 | 本地资金事实查原支付号，未必主动向通道查询 |
| `PaymentReceipt(String paymentNo,String orderNo,String userNo,String payChannel,String tradeNo,long amountMinor,String currency,String status)` | 159 | 无store/attempt request hash/outTradeNo/observedAt；需受权包装核对，非公开数据API |
| `PaymentOpen(String orderNo,String userNo,String entityNo,String payChannel,long amountMinor,String subject,String currency,List<PaymentStore> stores)` | 185 | 可信交易owner生成金额与路由，不能美业自报金额 |

真实bridge `ai.neargo.shop.paybridge.port.SettlePortImpl`在shop-app（:168/173/263/268/273），调用真实pay `ai.neargo.shop.pay.service.PaymentLedgerService`；其实现 `PaymentLedgerServiceImpl:498–525` 明确读stl_payment（successPayment仅SUCCESS，paymentByOutTradeNo原号不只SUCCESS）。目前普通收款bridge是本地owner组合，不应声称全部SettlePort已经支持独立PAY进程。

`OrderServiceImpl#pay`（2189–2287）通过resolveOrder（3487，当前买家）鉴权，从订单取金额，调用SettlePort；payResult仅订单投影。`SettlePortImpl#initPayment:178`先持久原号，:206取真实buyer openid，:210 prepay；:224非success统一close后可新号重试，异常/timeout没有完整显式UNKNOWN协议。需要核验隔离mockchannel是否能明确区分拒绝与结果未知；**UNKNOWN不得因boolean false关闭原号再新支付**。新attempt查询必须保持原request→outTradeNo映射，包括响应丢失前owner已open但调用方未收到outTradeNo的窗口。

`ai.neargo.shop.trade.order.service.lifecycle.PaymentLifecycleService#requireConfirmed(OrdOrder order,String channel,String tradeNo): ConfirmedPayment`（接口:7，impl:25–52）核对资金receipt的订单/buyer/金额/CNY/通道/交易号，不匹配写resolution拒绝。OrderServiceImpl:2331先锁order，再2336调用该方法；其impl:29调用SettlePort。若改成远程PAY，这就是**锁内RPC风险**，不能简单换bean。须锁外读真实资金proof，锁内核对不可变版本/唯一终态，远程部分不放DB锁内。

### 2.5 线下实收：已有有限订单确认，不能冒充完整BAPI-070

`ai.neargo.shop.trade.service.MerchantOrderService#confirmOfflinePay(String merchantNo,String storeNo,String subOrderNo,String operator): OrderVO`（接口:34）。impl:329–370：NOT_SUPPORTED外层、同店同主体整单、WAIT_OFFLINE_PAY、事务内记录offlineConfirmedBy/At并markPaid(OFFLINE,"OFFLINE:"+operator)。`PaymentLifecycleServiceImpl:26–27`对该路径直接接受，不要求stl_payment receipt。

现有 `/biz/order/{subOrderNo}/confirm-offline-pay`（BizOrderController:249–254）有BizPerms.RECEIVE；但operator传的是ctx.requireMerchantNo（主体号），不是实际actor account。该合同没有method/amount/evidence/idempotency key/receiptNo，不可直接宣称满足现金/转账正式凭证与原号恢复。要么按获准范围先门控BAPI-070并列为阻塞，要么在pay/交易owner补正式离线receipt命令、凭证、操作人、原号查询及幂等后开放；不能在美业补一张实收账。

### 2.6 权益消费：可借鉴授权模式，不复用业务身份

`ai.neargo.shop.trade.service.EntitlementServiceOrderService#create/createMonetary/createIdentified/requireMerchant/findOperation/source/bindPreview/stage/accept/cancel/acknowledge`接口:19–38。关键精确签名：
`ServiceOrderSnapshot create(EntitlementContext context,String entityNo,ServiceOrderCreate command,String key)`（:20）；
`ServiceOrderSnapshot createMonetary(EntitlementContext context,String entityNo,ServiceOrderCreate command,String key)`（:21）；
`ServiceOrderSnapshot findOperation(String tenantNo,String entityNo,String operationNo)`（:31）。

实体 `ai.neargo.shop.trade.entitlement.entity.OrdEntServiceOrder`注释（:9）明确不是普通cash order/payment receipt。impl:99–108普通create为次数权益语义、createMonetary为STORED_VALUE；:139–145要求SERVICE+STORE_VERIFY，非monetary行金额为0。将其用于新购服务，会把商品成交与已有资产消费混淆。

`ai.neargo.shop.trade.service.EntitlementAuthorizationService#stageTrustedIntent(String tenantNo,String orderNo,SourceRef source,String quoteRef,String selectionDigest,long amountMinor,String currency,Instant quoteExpiresAt): void`（:10）；`#confirm(String tenantNo,String userNo,String orderNo,AuthorizationCommand command): AuthorizationView`（:13）。impl:62–84要求USE来源；130–173校验本人、pending quote、snapshot/revision/expiry并持久AUTHORIZED。可借鉴“owner冻结→真实C确认→事务消费”的模式，但不得复用USE source或权益表承载普通购服务。controller MpEntitlementAuthorizationController:31–35取真实SecurityUtils.requireUser。

## 3. SERVICE与履约必须分清

`SERVICE`是商品类型（ServiceProductFactsPortImpl:42、OrdItem.categoryType:34），**不是既有履约枚举**。`ai.neargo.shop.common.Fulfillments`合法值:52–53；STORE_VERIFY:40为到店消费，APPOINTMENT:50是上门地址/预约时间，SERVICE_LIKE仅二者（:61）。

* `APPOINTMENT`不用于BM03到店预约：OrderServiceImpl:3973–3987旧时段校验，1826调用bookAppointmentSlot，4027起实际预订；将BM03预约再转这一履约会产生旧slot第二容量权威。
* `STORE_PICKUP`技术上仅需要SKU supported列表及门店渠道允许（OrderServiceImpl:4068–4082），未见仅凭SERVICE类型就强制拒绝；但它语义是取实物。付款分支2384–2387按fulfillment而非categoryType判服务，STORE_PICKUP会进入待备货/实物履约。不能为绕开slot而硬用此值。
* `STORE_VERIFY`可避免旧slot，但付款2388–2394生成核销码并按服务发货通知；旧核销完成路径 `ai.neargo.shop.trade.port.FulfillmentQueryPortImpl#complete(String subOrderNo,String operatorNo,String label): boolean`（139–180）检查状态机后可VERIFIED完成，未见工单完成proof。BM04不得把签到映射该核销。

推荐最小owner增量：普通STORE_VERIFY保留商品能力，但在**建单同一原子提交时**持久中立`fulfillmentAuthority=SERVICE_EXECUTION`与不可变source/operation归属，交易/履约所有旧核销、签收、送达、平台强制完成等入口统一拒绝受管理行；BM05唯一新owner合同持真实完成证据收敛。不能等美业后绑定才加标志，避免付款/核销竞态窗口；不要把行业名写入核心策略。另一可行方案是公共新增`SERVICE_EXECUTION`履约枚举，语义更清晰但会影响商品支持列表、状态/展示/校验与既有零售，变更面更大。无论选何者，BM04只能标“资金来源就绪”，`canStart`仍false，BM05开工许可与退款竞争未完成不可越界。

## 4. 推荐中立共享合同（提案，不是已存在方法）

建议唯一接口及record位于 `backend/shop-base/src/main/java/ai/neargo/shop/spi/trade/CheckoutPort.java` / `CheckoutInternalApi.java` / `CheckoutSourceFactsPort.java`；支付原号合同扩展既有settle能力或`spi/settle/PaymentAttemptPort.java`，不要再造一套行业PaymentPort和同义DTO。

建议最小方法：

```java
CheckoutQuote prepare(CheckoutContext context, PrepareCheckoutReq request);
CheckoutQuote readQuote(CheckoutContext context, String operationNo);
BuyerConfirmation confirm(BuyerContext buyer, ConfirmCheckoutReq request);
CheckoutResult place(CheckoutContext context, PlaceCheckoutReq request);
CheckoutResult findOperation(CheckoutContext context, CheckoutLookup request);
CheckoutSourceFacts source(CheckoutContext context, SourceLookup request);
PaymentAttemptView findAttempt(PaymentContext context, PaymentAttemptLookup request);
```

* `CheckoutContext`承载可信tenant/entity/store/actor及调用面；跨HTTP由认证HOST当前B/C身份构造并签署内部边界，不能从客户端body信任actor/entity。OWNER重新校验经营范围、当前权限、buyer active与proof归属。原号查询可以由当前另一获权管理者执行，读取授权不依赖发起人的旧会话；worker只持有限恢复范围，不能造用户授权。
* `PrepareCheckoutReq`含caller/source namespace、operationNo、不可变scopeRevision/scopeHash、buyerRef（owner解析），显式lineRef/skuNo/quantity、期望store、允许的优惠选择；绝不收业务可覆盖的price/payAmount。先限制单主体单店、MAIN/CNY、普通SERVICE、整数量单位（现OrdItem qty为Integer），不暗中放开多店混合收款。
* `CheckoutQuote`含owner quoteNo/revision/digest/expiresAt、verifiedBuyerNo、entity/store/currency、逐行稳定lineRef和真实金额、可用优惠/最终优惠、payMode/fulfillmentAuthority、operation绑定、可接管定位号。美业只存quote引用/digest，面向用户展示由owner返回。
* `ConfirmCheckoutReq`含operationNo/quoteNo/revision/digest和C自身幂等键。真正C认证入口（trade owner公开mp facade）先取真实认证buyer，核验quote buyer；再查owner冻结报价/期限/范围，写不可变确认事实后签发opaque随机ticket。member `mc1_/cr1_`仅识别身份；guest认领与身份proof绝不推导支付授权。member proof无需变成第二个报价/付款owner。
* `BuyerConfirmation`签发/消费者均trade owner，绑定tenant/buyer/entity/store/operation/scopeRevision/quote digest/金额币种/用途/expiry；存ticket hash而非明文。不可被B签发；不得把C在body自报userNo当本人。确认完成可直接同一C请求place；B若代执行，只能携带已经由C取得的purpose-bound ticket。票据不能一消费就永久丢失结果：消费、原操作→order映射、order创建在owner可恢复协议内；同operation同digest重放返回原结果，跨operation/更价/更买家拒绝。
* `PlaceCheckoutReq`仅operationNo、quote引用、confirmationTicket/authorizationRef、原始requestHash。writer实际使用的价格快照必须同授权绑定：在最终建单所用同一计算结果上比较完整quote digest/金额/优惠/门店，且quote/授权行锁+expectedRevision CAS保障唯一消费；不能先preview校验再createFor重新取价。若不能冻结旧价格，返回QUOTE_STALE要求重新报价与本人确认；不默默建更贵/不同优惠的单。若商品owner是RPC，须锁外取得有期限不可变owner quote，再在锁内消费该确定快照；不能锁内重新RPC。
* `CheckoutResult`含FOUND/NOT_ACCEPTED/PENDING/COMMITTED/REJECTED/UNKNOWN、原operation/hash、原order/subOrder、source revision/digest和错误码；read绝不创建。已接受后timeout永远同operation恢复；NOT_FOUND不能当成明确未执行，必须区分权威NOT_ACCEPTED与查询不可用/滞后。
* `CheckoutSourceFacts`每source包含buyer/entity/store/currency、逐行稳定**业务**lineRef/sku/qty/uom、真实应收/实收/未结、owner状态/版本/observedAt、支付attempt号/receipt引用及服务许可状态。OrdItem目前业务字段无显式lineNo（OrdItem:17–24），不可对外暴露BaseEntity物理id，建议owner追加稳定orderLineNo或在新checkout映射中永久绑定opaque行号，不能按SKU唯一假设或临时排序生成可漂移编号。
* `PaymentAttemptLookup`支持原checkout/payment operation查owner已生成的outTradeNo，也支持已知原outTradeNo；返回固定attempt引用/status/金额/币种/归属/receipt evidence，原号query不新init。未知与拒绝分开；只有owner明确可重试终态及新的本人支付动作，才允许新的attempt，且仍归同订单。
* 普通线上发起可继续C调用原OrderService.pay，但新共享可见结果必须保留actual outTradeNo和原operation映射。若新增owner startPayment方法，其amount/route从已确认订单读取，不能接收美业金额，必须核真实C或已签发单用途本人支付授权，不能借商家通用operator权限代付。

## 5. owner恢复、事务与美业绑定协议

1. 美业短事务锁工单/范围，验证当前权限、scope revision、真实buyer与任务；写bty_service_submission frozenCommand、固定原operationNo/hash后提交。实体已有requestKeyHash/payloadHash/frozenCommand（BtyServiceSubmission:25–31），用新版本DTO序列化恢复必需数据，不能只留无法反查的hash。
2. 无美业数据库事务/锁调用trade owner prepare/place/findOperation。owner所有写操作固定幂等原键，owner表保存长期映射；现Idempotency24h缓存只能辅助，不能恢复唯一真源。建立唯一约束`tenant + callerNamespace + operationNo`，另对buyer/action键明确作用域，不直接复用全局裸Idempotency-Key造成不同行业碰撞。
3. 得真实source后，美业再短事务按workOrder→task→submission→guard锁序，核expected scope/binding revision和同owner快照，分配qty<=owner可用数量并切换bindingRevision。旧结果晚到不能覆盖新范围；同operation重复结果不得重复allocate。
4. timeout/丢响应保留SOURCE_PENDING/UNKNOWN；恢复worker读原owner操作，再CAS绑定。不能重新编号/重新付款，不能根据TTL释放未知来源；read成功只证明当前事实，不是BM05允许开工的不可撤销许可。
5. 账单BAPI-071逐STORE_ORDER/SERVICE_USE读取owner金额，按币种展示、不用task.qty×目录现价计算应收，也不创建混合付款单。部分owner不可达必须返回不完整/未知，不能把缺失当0实收/已结清。TRACE137返回关联与脱敏状态，不泄露票据/openid/跨店信息。
6. BAPI-072可读本地意图状态，073触发当前权限校验下的原号恢复，不产生新交易。BAPI-068/069/070/089是合同编号，不是已有`/beauty/pay`真实路由；JSON方法清单:7866/7871/7876/7946只标REUSE_CONTRACT，仍需具体owner端点与所有权登记。

## 6. 2–3个可行方案与取舍

| 方案 | 交易/确认路径 | 增量与取舍 |
|---|---|---|
| A 推荐：trade接管checkout | B准备owner报价，真实C确认并建单/支付；美业只查原号绑定 | 身份最清晰，复用C支付；仍必须补持久报价、原号映射、来源facts和managed履约守卫。需要owner C接管端点/前端或可验证API，并非零改动 |
| B：owner ticket授权B执行 | C真实确认签发用途绑定ticket，B place消费ticket，C自己支付 | 支持柜台收银编排；多一个ticket传递与消费竞态，必须owner签发/单用途/同operation恢复。绝不由美业发行confirmation，不能凭cr1_代付 |
| C：先接已有普通订单来源 | C沿现有订单链购买；美业按owner read核买家/店/行/量绑定 | 最小可先行切片，能先验证来源绑定/账单；若缺quoted consent/managed履约/原operation recovery，不能当SRC-01或BM04整体完成。既有非managed订单只能受明确规则接受，不能事后装成受管理服务 |

不选：行业复制订单/报价/支付账；权益服务单冒充普通购买；伪造LoginUser；STORE_PICKUP绕slot；商家调用markPaid当线上付款确认。

## 7. 可分批文件边界与风险

A. 文档/契约冻结：现有TDD§2追加BM04任务、beauty-method/API/project JSON同步；shop-base新Checkout*及来源/attempt DTO，仅共享业务语言。明确C确认端点归trade，B编排不接受可信身份参数；父控制器负责guest，与本审计不重复建Guest表/Port。

B. 交易owner准备/确认/原号读取：shop-core/trade/service + service/impl + dto + entity/mapper + port；普通quote/operation/confirmation新增持久事实（是交易过程事实，不是重复订单账）。按现有迁移最大版本追加，不改已应用迁移。提炼OrderServiceImpl同一价格计算与exact quote writer，明确createFor兼容；长期原号lookup可在OrderCreationRecoveryService增量查询，必要新checkout journal关联OrdCreateIntent。

C. 受管理履约防线：shop-core/trade订单写入和fulfillment/receipt/平台完成入口、公用Fulfillments或owner标志；在普通订单创建时原子登记，关闭提前核销/签收/送达和旧slot双扣。BM05完成合同先fail closed，BM04不开放执行。

D. 资金事实与原号恢复：shop-base/spi/settle、shop-app/paybridge、pay/pay-domain及必要pay-server内部端点；真实PaymentLedger原操作映射/查询，明确UNKNOWN处理；如果本期含BAPI-070，在同资金owner补正式offline receipt。远程PAY不是BM04行业standalone的必然要求：先明确宿主core/pay本地owner、独立beauty通过host ownerHTTP即可，不能误宣称PAY所有动作已remote。

E. 美业编排：beauty-api dto/service、beauty-core submission/binding/guard/bill/recovery/trace、beauty-channel RemoteCheckoutPort/RemoteSourceFactsPort、host internal owner协议与配置；不依赖shop-core的OrderVO/OrderService包，也不在beauty-server复制平台表。

F. 验证与交付：定向unit/真实MySQL/原生owner集成→双部署HTTP→零售/餐饮影响→一次模块审查与BM04关口。支付/第三方money动作仅隔离既有mockchannel；本审计没有调用任何实际通道。

主要风险：quote重读TOCTOU、B identity冒充buyer、token消费后崩溃、原键缓存到期、多来源同SKU行归属、不同币种误加总、请求scope修改后晚回执、原订单已付但投影延迟、cash假receipt、受管理订单被旧核销提前完成、owner调用落美业锁内、旧SettlePort调用迁移成锁内RPC、Service商品在现有StockPort路径实际库存配置不满足（不能把SERVICE名义当自动无限库存；doCreate1827仍reserve）。

## 8. 必须真实验证的场景（待执行）

原生同进程必须用真实OrderServiceImpl/product owner/payment ledger和真实隔离SQL，非仅mock CheckoutPort：

* SERVICE SKU单店报价→真实C确认→普通ord_*建单→mockchannel原attempt→真实receipt→来源facts→美业绑定/账单。确认无ord_ent_service_order伪装、无第二报价/支付账，价格由owner生成。
* 100→120涨价、活动失效、门店价覆盖变化、quote expiry、quote/buyer/store/scope改动：quote stale/拒绝，无静默变贵建单、无扣款，原票据不能改用途。
* B伪造buyer、跨店、C另一买家、cr1_身份票据冒充支付授权、已撤权B；全部拒绝。C真实本人接管可成功。当前另一有权限管理者可以仅原号恢复。
* 同key同内容并发只一order/一ticket消费/一数量分配；同key异内容冲突。owner提交后响应丢失、客户端未拿到order/outTradeNo、进程重启、24h幂等缓存不在时仍以持久操作映射定位原号。
* 支付PENDING/UNKNOWN/拒绝/成功但订单未投影/重复回调；UNKNOWN原号查询不新init；receipt金额/币种/买家/attempt不匹配拒绝；late/duplicate cash进入原resolution不伪造已收款。
* 受管理SERVICE来源不得旧slot预订、不得签到核销、旧验证/收货/平台完成绕路一律拒绝；BM05未完成时canStart保持false。付款不代表服务已完成。
* 范围修订或取消与回执竞争、恢复扫描租约/饥饿/重试、两个任务争同源行数量、重复绑定/跨工单超分配；锁内不发生RPC。
* 账单按真实source逐币种展示，owner断开显示UNKNOWN/incomplete；TRACE脱敏，无ticket明文。

HTTP部署必须两形态均为实际打包构件：embedded host含beauty-core；standalone host只beauty-channel，独立beauty-server仅私有业务表+基础表，经认证内部HTTP请求host真实trade/product/pay owner。证明C认证证据跨边界不被B声明替代；故障注入丢owner响应/断SERVER/断owner，重启后原号恢复；负例内部密钥有效但跨店/不合法actor仍拒绝。禁止直接访问服务端数据库查平台订单绕过合同。默认零售关闭美业回归；餐饮现有功能保持正常。mockchannel必须由明确隔离配置启用，不接真实第三方通道，不以测试Bean充当业务owner。

## 9. 餐饮参考与最终阻塞

当前food-core源码只见StoreOperationServiceImpl和基础StorePolicy/TableArea，未见可复用FoodOrderService。`docs/technical/design/schema/food-catalog.schema.json:1285–1308`是目录/套餐/加料/菜单方案：引用基础SKU价格、菜单不存第二价账；:1297明确核心组合计价接线未完成，不能把设计图当订单实现。`TDD-餐饮系统-三端API与独立服务对齐.md:45/53–54/110–119/128–130`同样强调核心统一建单、库存只执行一次、普通SettlePort全链路远程尚需闭环、锁外网络调用。此方案可指导边界，不能替代普通checkout provider。

阻塞BM04整体准出的真实缺口：普通报价封版与精确写单校验；真实C本人confirmation owner协议；普通原operation read/recovery；稳定源业务行与受管理服务履约防线；保留实际支付号的查询/UNKNOWN恢复；BAPI-070正式offline receipt若承诺本期必须补齐。BAPI-089只能开放owner已实现且纳入相同quote seal的优惠，不能用目录价假装合格券报价。以上可依批次实现，不需要新一轮业务方向授权；必须先写进现有TDD详细任务再编码。本报告本身仅审计，不代表这些能力已经实现或验证。


## 10. 给Task54–59主计划的最终收敛建议

**选A：真实C接管checkout + 既有STORE_VERIFY + owner受管理履约守卫。** 本期实现完整真实quote/create/query/payment/source-bind/bill；不把“Port存在”算能力交付。共享guest/proof由Task54承接，接待工单Task55；下面是Task56明确职责，后续Task57/58/59按父计划组织绑定、协议与部署。仅保留BM05实际开工/完成动作关闭，不用假来源或假支付状态填补BM04。

选STORE_VERIFY+guard，是因它已经表达买服务、付款转FULFILLING、不调用旧slot；所增加的是“哪种权威可以完成它”，适合已有模型。**必须**同步限制目录：Task56只接受真实`SERVICE`且商品owner支持列表包含STORE_VERIFY、属于相同entity/store、目录当前可售的SKU；不自动修改商品fulfillments、不用STORE_PICKUP或APPOINTMENT回落。符合条件的目录夹具必须用真实商品owner创建/配置。

两种履约方案成本：

| 方案 | 具体必改触点 | 取舍 |
|---|---|---|
| STORE_VERIFY + managed ownership（推荐） | 原子创建时在OrdSubOrder或trade-owned source关联持久authority/operation；OrderServiceImpl付款出码/notifyShippingOnPaid要识别managed，不能发出可用的旧核销入口或提前声称实际服务完成；统一状态写门和旧完成入口验证authority；source facts提供该语义；同类旧STORE_VERIFY保持原规则 | 不扩公共履约值；新增owner字段/关联与几个统一防线。有双语义风险，必须由持久owner metadata决定，不能靠美业内存/前端隐藏按钮 |
| 新SERVICE_EXECUTION公共履约值 | shop-base Fulfillments ALL/SERVICE_LIKE等集合；商品创建/编辑校验与支持列表、ServiceProduct/目录投影；OrderServiceImpl requireFulfillment/pay/出码/notify；公共状态机/履约查询与receipt自动完成策略；B/C枚举展示、API/schema/生成清单；旧源恢复序列化与存量兼容；另需同样owner完成proof守卫 | 语义单一但跨端/商品配置改动更广。增加枚举本身不能挡旧通用complete方法，仍要完成authority。可后续独立演进，本期不是最小方案 |

需要实际收口的旧完成入口至少：`FulfillmentQueryPortImpl#complete`（139）；`OrderServiceImpl#confirmReceipt`（2904）→`OrderReceiptServiceImpl`；`OrderReceiptCompleterImpl#completeAuto`（48）、`#completeSigned`（71）；`MerchantOrderServiceImpl#delivered`（375）；通用`TradeStatusWriter/OrderTransitions`最终COMPLETED迁移。扫描实际所有写COMPLETED处并落统一gate，避免只挡某Controller。auto-close未付款可以合法关闭并使source失效，不能把关闭误映射完成；已支付managed单不能由旧自动收货完成。取消/退款照原owner规则执行，BM05以后开工竞争仍需升级许可，不假称本期已做。

### Task56建议固定共享方法（全部提案，编译前冻结DTO）

`ai.neargo.shop.spi.trade.CheckoutPort`：

```java
CheckoutQuote prepare(CheckoutContext context, PrepareCheckoutReq request);
CheckoutQuote readQuote(CheckoutContext context, String operationNo);
BuyerConfirmation confirm(BuyerContext buyer, ConfirmCheckoutReq request);
CheckoutResult place(BuyerContext buyer, PlaceCheckoutReq request);
CheckoutResult findOperation(CheckoutContext context, CheckoutLookup request);
CheckoutSourceFacts source(CheckoutContext context, SourceLookup request);
PaymentAttemptView initiatePayment(BuyerContext buyer, InitiatePaymentReq request);
PaymentAttemptView findPayment(CheckoutContext context, PaymentLookup request);
```

本期A方案将place签名收窄为BuyerContext，B没有place/confirm/initiatePayment权限；前述B票据代提交仅作为备选，不同时实现两套路径。BuyerContext必须是认证边界证明的tenant/user/session主体（server internal接收可信HOST协议的证明），非允许任意业务调用方new record即视为通过。confirm可返owner确认号并由同C继续place；ticket不必通过B流转，仍须持久同operation幂等消费。Payment请求只允许owner订单引用/通道选择/原requestKey，不接金额。

文件职责边界：

* `shop-base/.../spi/trade/CheckoutPort.java`：上述唯一方法及neutral DTO；`CheckoutInternalApi.java`：统一内部wire requests/responses和路径，避免beauty与host各自复制JSON类。
* `shop-core/.../trade/service/CheckoutService.java`、`service/impl/CheckoutServiceImpl.java`：实现owner流程/授权/报价/精确写入/原号lookup，直接用本域Mapper；`trade/port/CheckoutPortImpl.java`适配共享Port。不是再造OrderService，真正成交必须复用原writer且在同一次定价结果上核quote。
* `shop-core/.../trade/dto/`、`trade/entity/`、`trade/mapper/`：owner quote/confirmation/operation持久过程事实与稳定业务行映射，持久引用原ord_*；追加平台迁移，不改beauty V1–V4。
* `shop-core/.../trade/api/mp/MpCheckoutController.java`：真实C quote读取、confirm、place、initiatePayment；新的`InternalCheckoutController`在既有owner内部装配边界，允许已认证美业prepare/read与HOST认证C代理请求，但各方法明确audience，不接受美业B服务自行声明buyer即发授权。实际URL在API真源注册，不把本提案路径当已存在。
* 现有`OrderServiceImpl`/内部报价writer：显式buyer计算、单店强校验、封版比较、业务line reference/managed authority原子创建。不能在CheckoutPortImpl里“preview比较→createFor重新算”完成此事。
* 现有`shop-app/paybridge` + `pay-domain/PaymentLedgerService`：保留实际outTradeNo，固定原payment operation映射和受权查询；资金动作以真实mockchannel测试，不另建业务账。把封装复用SettlePort作为内部实现，返回checkout-neutral PaymentAttemptView。BAPI-070若完整交付，同owner补OfflineReceiptReq/ReceiptView/write/read，不把旧confirmOfflinePay输出命名ReceiptView冒充。
* 通用完成gate与managed-source owner记录：core完成入口fail closed，BM05未来凭不可伪造完成事实接入；BM04不实现实际服务完成方法体。

Task56验证接口应包括：真实C确认产生owner记录、B调用confirm/place/payment拒绝、price变化在最终writer内拒绝、place响应丢失后findOperation原号、payment响应丢失后findPayment原attempt、source能返回真实buyer/store/稳定业务行/quantity/receipt、任意旧完成入口拒绝managed source。部署使用真实HTTP认证/真实owner/native SQL，only mock外部支付通道；这些验证不能用一个手写返回COMMITTED/PAID的Port替身替代。
