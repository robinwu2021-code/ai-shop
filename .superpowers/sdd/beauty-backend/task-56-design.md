# Task56 共用普通服务 checkout 精确设计

状态：IMPLEMENTED_REVIEW_READY（根技术放行后的实施审查候选，尚未独立验收/提交）。日期：2026-10-23。
设计审计基线：`8946b8be1604dc09f1fb0d69fec22db08b69df27`；实施基线/当前HEAD：`codex/beauty-foundation` / `f33bf12cff693472330aa659b5d1615aee3e9717`。
依据：`task-56-brief.md` 为准确要求；`task-56-preflight-draft.md`；`bm04-checkout-design.md` §1–6/9–12。已批准方案A的实施深化；新增表族及跨域合同按开发规范档位2，复用已有PRD/主TDD。主TDD/进度/整体设计由root维护，不另建计划。适用 ADR-002/031/035/038/043/044/045；root已批准并落盘 `docs/technical/ADR/ADR-046-共用购买确认与受管理服务订单归属.md`，记录当前购买授权、单writer seal、source引用与持久managed边界（root owned，不属于本agent产品提交）。本次不新增财务owner或价格引擎，不构成新的业务审批门。

## 1. 范围与实际源码基线

56实现共享交易owner的六方法、HOST HTTP、普通订单与行映射、受管理完成守卫。57实现支付两方法与offline receipt；58美业来源绑定；59美业适配和公开wire；60双形态模块构件/部署。56不新增未来pay Port/Remote空壳，不声明BM04整体完成。没有新美业订单/价格/应收账；仍写 `ord_order/ord_sub_order/ord_item`。

固定 `MAIN/CNY`，一个entity/store，`SERVICE/STORE_VERIFY/COUNT`，1–20个不重复SKU；每行1–999整数份。行顺序保持请求顺序，`clientLineRef`唯一；COUNT表示1销售数量=1购买服务份，不给OrdItem加uom，不转换EA/分钟/小数/空单位。实际SKU必须明确支持STORE_VERIFY、在售、归属正确，实际有效目录必须配置并包含可见/销售窗口内SKU；不沿老writer空fulfillments即兼容放行，不改商品支持列表。OPEN/ABORT_DECIDED的本地普通金额券和金额活动可用；PAY券、积分/积分赠送、储值、CARD、group/period、赠品和复杂组合在prepare明确拒绝并在writer再次拒绝。不得静默少送/少优惠。

root已经裁决：已知本地food `fbda26d2f8b7fb254e5e43ed8c6d9622f621c44c` 的四个实际writer/router与当前blob相同；新增纯定价/配置代码未接普通writer，不合入479文件或冲突V532/V533。本步复用当前真实算法，不声称整个最新food分支已整合。remote只读结果、13文件差异详见报告。

## 2. 六方法、DTO与HTTP

唯一跨域接口 `backend/shop-base/src/main/java/ai/neargo/shop/spi/trade/CheckoutPort.java`，全部public record嵌套于该接口。实现 `CheckoutPortImpl → CheckoutServiceImpl`，Service接口承接这六个同签名方法，另有下述仅本地C读便利方法（不增加共享SPI方法）：

```java
CheckoutQuote prepare(CheckoutScope scope, PrepareCheckoutReq request);
CheckoutQuote readQuote(CheckoutScope scope, OperationRef operation);
BuyerConfirmation confirm(ConfirmCheckoutReq request);
CheckoutResult place(PlaceCheckoutReq request);
CheckoutResult findOperation(CheckoutScope scope, OperationRef operation);
CheckoutSourceFacts source(CheckoutScope scope, SourceLookup request);

record CheckoutScope(String tenantNo, String entityNo, String storeNo, String actorNo) {}
record OperationRef(String caller, String operationNo) {}
record PrepareCheckoutReq(OperationRef operation, String requestKey, String customerRef,
    long scopeRevision, String scopeHash, List<CheckoutLineReq> lines, String payMode,
    String couponNo, Map<String,String> activityChoices, String remark) {}
record CheckoutLineReq(String clientLineRef, String skuNo, int quantity) {}
record CheckoutQuote(OperationRef operation, String quoteNo, long quoteRevision, String quoteHash,
    Instant expiresAt, String buyerNo, String entityNo, String storeNo, String currency,
    List<QuoteLine> lines, long goodsMinor, long discountMinor, long dueMinor, String payMode,
    String fulfillment, String fulfillmentAuthority, List<PromotionOption> promotions,
    List<DiscountFact> discounts, String phase, long operationRevision) {}
record QuoteLine(String clientLineRef, String skuNo, String goodsNo, int quantity, String uom,
    long unitPriceMinor, long grossMinor, long discountMinor, long dueMinor,
    String productVersionHash) {}
record PromotionOption(String promotionNo, String kind, String title, boolean eligible, String reasonCode) {}
record DiscountFact(String promotionNo, String kind, long discountMinor, String fundingKind,
    String ownerVersion) {}
record ConfirmCheckoutReq(OperationRef operation, String quoteNo, long quoteRevision,
    String quoteHash, String requestKey) {}
record BuyerConfirmation(String confirmationNo, OperationRef operation, String quoteNo,
    long quoteRevision, String quoteHash, Instant expiresAt, String status) {}
record PlaceCheckoutReq(OperationRef operation, String confirmationNo, String requestKey) {}
record CheckoutResult(OperationRef operation, String phase, long revision, String orderNo,
    String subOrderNo, String sourceHash, String reasonCode, Instant observedAt) {}
record SourceLookup(OperationRef operation, String subOrderNo) {}
record CheckoutSourceFacts(OperationRef operation, String orderNo, String subOrderNo, String buyerNo,
    String entityNo, String storeNo, long sourceRevision, String sourceHash, String orderStatus,
    String currency, long dueMinor, Long receivedMinor, Long outstandingMinor, String financialState,
    List<SourceLine> lines, List<PaymentFact> payments, String executionState, Instant observedAt) {}
record SourceLine(String sourceLineNo, String clientLineRef, String goodsNo, String skuNo,
    int orderedQuantity, int refundableQuantity, String uom, long dueMinor) {}
record PaymentFact(String paymentOperationNo, String paymentNo, String outTradeNo, String channel, String tradeNo,
    String status, long amountMinor, String currency, String receiptNo, Instant observedAt) {}
```

不可变copy Lists/Maps；拒null元素/重复ref/SKU、未知枚举、非规范空白、金额溢出。引用长度：entity/store32、actor/buyer64、operation/clientLineRef/sku64、caller固定BEAUTY、key1–128、remark≤500、scopeHash小写64hex、scopeRevision≥1。金额为非负long且Math exact；DTO中不接收客户自行计算的价格。Quote/confirmation/source号分别`CQ/CC/CL`+32位UUID hex（34字符）。报价有效期300秒，毫秒UTC；确认有效期等于报价有效期，不延长授权。`productVersionHash`是当次实际商品经济/能力事实hash，不能宣称是价格版本号；缺真实promotion版本时`ownerVersion=null`，完整实际选择/分摊进内部摘要。

`PrepareCheckoutReq.toString()`只显示operation、数量等非敏感字段，customerRef与requestKey为REDACTED。服务日志/错误正文不打印整个请求/JSON或token。其他请求key也redact；不得将原cr1/mc1/claimToken/contact放入持久命令或返回DTO。

HOST路由：

| 实际controller与方法 | HTTP | body/返回 |
|---|---|---|
| `trade.api.mp.MpCheckoutController#read(String operationNo)` | GET `/mp/checkout/{operationNo}` | 调用本地CheckoutService.readQuoteForCurrentBuyer；无B scope |
| `#confirm(String operationNo, ConfirmBody body, String key)` | POST `/mp/checkout/{operationNo}/confirmations` | `ConfirmBody(quoteNo,quoteRevision,quoteHash)`；返回BuyerConfirmation |
| `#place(String operationNo, PlaceBody body, String key)` | POST `/mp/checkout/{operationNo}/orders` | `PlaceBody(confirmationNo)`；返回CheckoutResult |
| `portal.internal.InternalCheckoutController#prepare/readQuote/findOperation/source` | POST `/internal/trade/checkout/prepare|readQuote|findOperation|source` | `CheckoutInternalApi.Scoped<T>`；各自返回真实结果 |

C GET实际本地签名为 `CheckoutService.readQuoteForCurrentBuyer(OperationRef operation): CheckoutQuote`，由CheckoutServiceImpl真实实现，MpCheckoutController的read直接注入该本地service调用。方法先锁外requireCurrentBuyer，再按MAIN/caller/operationNo/buyerNo精确查询op及关联quote并映射。不存在/foreign buyer统一NOT_FOUND，不接scope、actor参数，不构造B身份、不调用B requireMerchant。共享CheckoutPort.readQuote(CheckoutScope,OperationRef)明确仍是B读，非空scope规则和ORDER_VIEW/dataScope保持；内部HTTP不暴露本地C便利方法。C confirm/place各自由当前buyer精确定位op，不能通过scope绕过。

C写key只从必填`Idempotency-Key`头读；body无key/actor/buyer字段，caller服务端BEAUTY。C写不提供internal RPC。C controller `@Validated`+`@Valid`，拒未知body字段（controller DTO特定配置，不能全局改变旧API）。内部 `CheckoutInternalApi.BASE="/internal/trade/checkout/"`；`record Scoped<T>(int protocolVersion, DelegatedIdentity identity, CheckoutScope scope, T request)`；严格version=1，identity只MERCHANT MCH/USR且tenant/actor一致；InternalServiceResponses负责transport鉴权与裸响应。内部响应类型只`record Result<T>(int protocolVersion,T result)`，禁止回显Scoped请求，避免customerRef原文泄漏。ApiResponseWrapper既有internal排除需实测。无worker新route；56只交这四个实际B方法及三个C路由。59才增加美业client/adapter及行业公开路由。

## 3. 当前身份、权限、重放次序

`ai.neargo.shop.trade.policy.CheckoutAccessPolicy`实际方法：

```java
String requireCurrentBuyer();
MerchantActor requireMerchant(CheckoutScope scope, String permission);
void requireReadable(CheckoutScope scope, OrdCheckoutOperation operation);
record MerchantActor(String tenantNo,String entityNo,String storeNo,String actorNo,
    String realm,String subjectKind) {}
```

C：锁外 `SecurityUtils.requireUser()` 必须CONSUMER/USR/MAIN；`SecurityUtils.currentToken()`只来自认证details，不从header自行取字符串；注入的`TokenStores.of(Realm.CONSUMER).get(token)`须存在且user/tenant/realm/kind完全一致，`UserScopeFactsPort.findActive(MAIN,user)`存在。只有手造LoginUser、B USR、DelegatedIdentity、service key、过期/撤销token全部拒。filter验证和方法检查都要有真实测试。每次confirm/place（包括CREATED重放）重新核当前C；一次请求的授权线性化点为紧邻最终事务前的该检查，不在持锁事务调用可能Redis TokenStore。不得以本地customerRef/guest认领代替C授权。

B：`MerchantOperatorAccessPort.allowed(Query(MAIN,entity,store,actor,MERCHANT,MCH|USR,permission))`必须在任何caller事务外调用（其真实实现明确禁止caller TX）。本地入口从当前真实SecurityUtils身份和BizContext取scope；内部入口先InternalHttp验证transport，校验MERCHANT envelope，再DelegatedIdentityContext.call，恢复真实owner权限。身份完整namespace不能由数字号猜；不接受裸scope授予权限。prepare新增`BizPerms.CHECKOUT_PREPARE="trade:checkout:prepare"`，仅OWNER已有`*`自动可用，默认角色列表不增条目。读三方法使用`BizPerms.ORDER_VIEW`。

B数据域复用现BizOrderController的真实语义：注入`BizIdentityResolver.resolve(actor,scope.storeNo)`在锁外现查；结果entity必须精确等scope.entity，currentStoreNo必须等scope.store，`allowedStoresOrAll()`包含该店（owner null为全部但仍核当前店）；`courierOnlyOrderView()`拒绝这类含完整金额合同，不能通过internal逃过裁剪。明确传entity/store条件查op、created后查真实sub归属；self/pickup-only主体不能因持有ORDER_VIEW扩张到店内全部金额。使用DataScopeContext.executeWithoutScope仅包已完成上述owner校验后的精确mapper，不能对未授权scope先读。此与现订单B端显示域一致，不直接拿B token的SELF当买家SELF，也不将internal构造的空dataScope当ALL。

首次prepare与重放：

1. 校验规范结构，重新核当前B权限/范围；以actor namespace+caller+action+key计算`request_key_hash`，以完整规范请求（customerRef先单向SHA256，其他字段含原行顺序/choices/remark）计算`payload_hash`。cr1为随机高熵凭证，不处理contact；不引入无密钥低熵联系方式摘要。
2. 精确查已有tenant/caller/operation及prepare key；已提交且原actor namespace、payload_hash相同，直接返回原quote/phase，仍核当前读权限；不重新resolve过期cr1，不重新签发identity，不重算价格。changed payload（包括新cr1即使同一buyer）同key冲突。CREATED返回原quote/单的既有事实，不当成新prepare。
3. 首次调用真实`CustomerIdentityPort.resolveClaimReference(new EntitlementContext(MAIN,actor,operationNo,payloadHash),new ReferenceQuery(entity,store,customerRef))`；该共享owner内部还要求CUSTOMER权限，这是既有strict引用合同，不由trade绕过。冻结只留规范buyer、entity/store、actor namespace、安全摘要。原凭证自然过期影响新操作，不影响已提交同请求恢复。
4. 锁外计算有效目录/实际普通报价，再单短TX插op+quote。唯一键冲突后重新读持久相同请求返回，否则冲突。失败不会留下可消费quote；报价并不占券/库存/slot，不建账户会员。

## 4. 单writer、经济seal与锁序

现源码事实：doCreate1567；discounts1678；OFFLINE资金校验1703；`creationRecovery.register`1816（REQUIRES_NEW持久意图）、enlist1824、slot1826、stock1827、主单1831、子单1937、LineShares1942、item1963、coupon2025/campaign2034。准确导航基线见报告，实施前重核。

保留`OrderServiceImpl`唯一普通writer。新core内部`ai.neargo.shop.trade.service.CheckoutOrderWriter`由现类实现：

```java
CheckoutEconomicSnapshot quoteFor(String buyerNo, OrderService.CreateOrderCommand command,
                                 CheckoutPricingScope scope);
OrderVO createConfirmedFor(String buyerNo, OrderService.CreateOrderCommand command,
                           String idempotencyKey, ExpectedCheckoutQuote expected);
```

`trade.order.service.create.ExpectedCheckoutQuote`严格七字段：
`(String operationNo,String quoteNo,long quoteRevision,String quoteHash,String confirmationNo,String scopeHash,Instant expiresAt)`。
仅owner根据持久quote/confirmation创建，不能JSON反序列化成公开请求。`CheckoutPricingScope(tenantNo,entityNo,storeNo,scopeRevision,scopeHash,List<CheckoutLineReq> lines,StoreSalesCatalogPort.Snapshot catalog)`为core内部不可变record。实际 `CheckoutQuoteGuard.requireMatches(ExpectedCheckoutQuote,CheckoutEconomicSnapshot): void` 只读已锁本地证据与比较，不访问Port或重新定价；`context(ExpectedCheckoutQuote): CheckoutPricingScope`从该持久命令和最终目录事实构造，不能信请求造managed标志。

`OrderServiceImpl`内唯一私有计算入口精确为 `CreatePricing checkoutPricing(String buyerNo, CreateOrderCommand cmd, LegacyCouponBoundOrderService.Binding binding, CheckoutPricingScope scope)`；`scope==null`保持旧算法分支，非null才使用显式buyer和ordinary限制。`LegacyCouponBoundOrderService.Binding`即现doCreate第6参数的实际绑定类型，不另造绑定协议。`CreatePricing`是该类私有record，字段为现`Split raw/split`、现group quote、`Map<String,String> stores/pickups`、freightQuotes、payMode/clientScene、现points决策map、gifts、`Discounts discounts`及managed计算的经济行/shares。实施时沿用原字段真实类型，不把这些内部类型变跨域DTO。

准确调用点与数据流：

1. `quoteFor(buyer,cmd,scope)`只调用一次`checkoutPricing(buyer,cmd,null,scope)`，由返回束组装报价；不调用create、doCreate、IdempotencyService或生成订单号。
2. 原doCreate 1568–1706的纯计算段移到该私有方法：原顺序`split → groupQuoteOf/repriced → storesOf → requireFulfillmentSupported → freightQuotes/requireNotRegionRestricted/applyFreight → requirePayModeSupported/points决策 → purchaseLimit/receiver/radius → resolvePickups/requirePickupServed/requireAppointmentWhenNeeded → giftQtyOf → discountsOf → OFFLINE出资限制`。原`orderNoGenerator`及sub号生成移到方法返回后的持久段，不在quote消耗号段。旧路径这些调用次数、次序及输入保留，只有号段生成后移到完整只读校验后。
3. `createConfirmedFor`不预调quoteFor。它进入现`IdempotencyService`回调后只调用一次`doCreate(...,expected)`；doCreate从已锁证据取得scope，**只调用一次**上述函数。managed `split(cmd,buyer)`、`giftQtyOf(split,buyer)`、`discountsOf(cmd,split,buyer,ordinary=true)`使用同一明确buyer；旧split/gift/discount调用不得在managed返回束之后再执行。老coupon binding与managed expected互斥，防双券流程。
4. 返回束即后续OrdOrder/OrdSubOrder/OrdItem所消费的唯一金额、商品和shares来源；guard只比较，不重算/回调Port。原1942 allocate对managed改为取该束，旧路径保留原allocate。既不执行一次完整preview后再旧writer重算，也不把预览标价当最终authority。

仅在现类提炼这一段，不拆出第二价格引擎或独立订单persister。doCreate仍负责原创建恢复/库存/持久写入；新增最后参数expected，其余旧6参数原义，旧create/createCoreFor/createBound传null。

命令固定构造：items为原行序；fulfillment STORE_VERIFY；pickup/address/appointment/slot/group均null；usePoints=0L；payMode明确ONLINE/OFFLINE；payScene=null；openGroup=false；activityChoices原选择；storeChoices=Map.of(entity,store)；addressChoices=Map.of()。payScene null本身不是积分能力证明：有正usePoints、任何pointsStaged/bonus-points活动、权益/card binding、gift、period ticket均拒。显式categoryType=SERVICE且fulfillments包含STORE_VERIFY；普通writer老空值兼容不替新合同放宽。

### 4.1 显式buyer与同次实际摘要

已发现`CampaignPortRouter.candidates/flashPrices/giftRules`取SecurityUtils当前人；B prepare不可沿用该隐式身份。最小现Port增量（全部有真实实现，默认不伪成功）：

* `GoodsQueryPort.snapshotFor(String buyerNo,List<String> skuNos,Map<String,String> storeByEntity)`；`GoodsQueryPortImpl`复用现snapshot组装，只将flash读取传明确buyer。
* `CampaignPort.candidatesFor(String buyerNo,List<MerchantAmount> groups)`、`autoDiscountFor(String buyerNo,List<MerchantAmount> groups,Map<String,String> choices)`、`flashPricesFor(String buyerNo,Collection<String> goodsNos)`、`giftRulesFor(String buyerNo,Collection<String> goodsNos)`。`CampaignPortRouter`合并legacy与现promo同一算法，promo用显式buyer；`CampaignPortImpl`真实legacy计算不依赖buyer。旧签名保留并委托当前语义；quoteFor/managed writer采用新签名，普通零售调用不改语义。不能SecurityContext切换、ThreadLocal buyer或造C token。
* 同类`split(cmd,buyer)`新重载仅managed/quote使用；显式storeChoices避免落店默认读取当前B社区。普通split(cmd)保留路径。

经济摘要内部`CheckoutEconomicSnapshot`字段固定：tenant/buyer/entity/store；scopeRevision/scopeHash；命令规范hash（包含按序clientLineRef/SKU/COUNT qty、couponNo/activityChoices/payMode，remark留请求hash但不影响经济hash）；currency/payMode/fulfillment/authority；catalog真实versionNo/effectiveRevision/snapshotHash；按原行序`EconomicLine(position,clientLineRef,goodsNo,skuNo,quantity,uom,unitPriceMinor,grossMinor,paidShareMinor,productVersionHash)`；一主一子goods/freight/discount/payAmount；子discountPlatform/discountMerchant；完整实际Campaign Discount shares/applied与Coupon Allocation shares/byMerchant/所选券号；pointsDeduct=0/pointsDeductMinor=0、pointsIntegrated=false、gift选择为空、group/period为空。活动积分/复杂组合明确拒，不能遗漏而接受。

规范hash使用typed record、UTF8、SHA256、稳定字段序；map按key排序，**经济行保持writer原顺序**；不含quote/order/confirmation/source随机号、生成时间、标题。有效期单独校验。主子金额及完整出资分摊进hash，等总价换商品、换出资方、换券选择均stale。没有提供revision的商品/活动以实际消耗的事实hash代表证据，不编造owner版本。

在现1706附近构造同次经济摘要与每group shares，先`LineShares.allocate(max(0,group.goods+freight-discount-freight),原g.lines.amount数组)`，使用现最大余数算法和原序；持有不可变数组副本。`requireMatches`须在register1816之前成功。1942处managed消费同一数组，不二次allocate；主/子/行组装后managed-only断言金额/sku/qty/paidShare等于同次已封版snapshot。item insert获id后立即插`ord_checkout_line`（clientLineRef由冻结唯一SKU映射在这次行写入处取，不事后查表按SKU猜），sub insert时写managed metadata。

### 4.2 最终事务与永久幂等

锁外每次重新核当前C，先按tenant/caller/op/buyer读取永久op。已CREATED也必须核原confirmation、首个place key与payload，之后直接返回原号，不再计算价格/目录。首次place锁外做活动能力/券路由预检；这里只早拒，最终writer仍重核。

外部幂等key都按`SHA256(tenant,buyer,action,key)`保存，不落原key；place payload包括caller/op/confirmation，confirm payload包括caller/op/quoteNo/revision/hash。**首个成功登记的key永久绑定，一个op只有一个place key，一个quote只有一个confirm key**：同key同payload返回原事实；同key不同payload或跨op复用冲突；同op/quote不同key也CONFLICT（findOperation仍能读原单）。不承诺未持久alias的异key成功。不新建别名表。

Confirm在一个短TX内按`op FOR UPDATE → quote FOR UPDATE`核当前C buyer、quote hash/revision、未过期，再插confirmation并推进quote CONFIRMED/op CONFIRMED；首key与结果同TX提交，失败全回滚。已确认同key同payload重放返回原confirmation及其当前status，哪怕后来CONSUMED/EXPIRED；重放不延长授权。异key无论并发/成功后都冲突。

Place分成**元数据claim事务**与**最终成交事务**：先短TX锁op→quote→confirmation，核AUTHORIZED和expiry，CAS登记唯一place_key_hash/place_payload_hash后提交；phase仍CONFIRMED，尚无订单/库存/资金事实。竞争不同key只有首个claim成功，另一个明确CONFLICT；同key可继续。这样即使最终TX在独立create intent提交后崩溃，也不会丢失外部key绑定。claim本身失败回滚不占key；claim提交后下游失败不释放/换key。每个永久key都能由其op恢复，不受24h缓存清理影响。

最终平台TX使用READ_COMMITTED，顺序：`operation FOR UPDATE → quote FOR UPDATE → confirmation FOR UPDATE → catalog head FOR UPDATE → local coupon roots(固定PMT/MKT source+template序) → local coupon template/instance → selected campaign rows(固定owner/activityNo序) → existing SysIdempotent row → OrdCreateIntent → stock → order/sub/items/maps`。selected campaign锁发生在实际定价结果产生后、seal前；没有旧流程反向取得checkout锁。catalog与coupon支持性均为当前本地事实；同scope的quote/confirmation/buyer/首key须重新相等且未过期。主/子/行/mapping、quote CONSUMED、confirmation CONSUMED、op CREATED最后同TX提交。

`createConfirmedFor`要求平台TX，并由`CheckoutQuoteGuard.context(expected)`用当前已锁op/quote/confirmation核operation/quote/confirmation/当前C及未消费状态；任意手造expected不能触发managed。以owner稳定key `CHK_`+SHA256(tenant,caller,operationNo)复用`IdempotencyService.execute(key,"POST /mp/order",buyer,commandHash,OrderVO.class,()->doCreate(...,null,expected))`（68字符）。SysIdempotent是caller TX临时排他行；任何失败回滚。普通creationRecovery的REQUIRES_NEW意图仍严格在seal之后，沿既有库存恢复协议。

状态失败处理也锁op并检查尚无order，不能迟到错误覆盖成功。未进入register的seal stale在回滚后短TX将op置REQUOTE_REQUIRED、quote REVOKED、confirmation REVOKED，保留首place key；过期则quote/confirmation EXPIRED。scope/selection/过期需新operation重新prepare，第一期quoteRevision固定1。未进入register的暂时依赖失败可保留CONFIRMED/AUTHORIZED和原key重试，仍不延长期限。

若原稳定key已有OrdCreateIntent而无order，PREPARED/RECOVERING/RECOVERY_REQUIRED只在findOperation响应中派生PLACING（DB phase仍CONFIRMED），本请求不重新进writer；沿原intent恢复。RELEASED时短TX将op置REJECTED/CREATION_ABORTED、quote/confirmation REVOKED；**confirmation没有CONSUMED，但不可再用**，首key保留。绝不新key/新orderNo重建该op。CREATED同key重放返回原订单；不同key冲突。进程崩溃在订单提交后，op与映射必已同TX提交；提交前，所有ord/consumption回滚，claim与原意图供恢复，不返回假CREATED。

## 5. 全链路IO审计与无锁内RPC方案

支持形态是HOST trade+product+merchant+core promotion真实本地owner；beauty embedded/standalone均来HOST C写。以下不是泛称Port本地：表列出实际实现及受控分支。

| 点位 | 实际实现/IO | 最终持锁处理 |
|---|---|---|
| 当前TokenStore、active user、MerchantOperatorAccess、strict customerRef | TokenStores可DB/Redis；UserScopeFacts本地；merchant access强制无callerTX；MemberCustomerIdentity再核CUSTOMER | 全部锁外；不放入writer，不持久token/reference |
| StoreSalesCatalog.read | StoreSalesCatalogServiceImpl.requireExternalScope强制无callerTX，StoreDirectory跨owner | prepare和place早拒锁外；最终新增同owner本地lockForCheckout，见下 |
| 商品snapshot、store价、flash | GoodsQueryPortImpl→本地ProductMappers/StoreStockReader；CampaignPortRouter→core活动+MemberQueryPortImpl本地 | 显式buyer；同次writer实际读；无HTTP；snapshot hash来自真实结果 |
| storesOf/落店、merchant find/status、payCapability | MerchantPortImpl/MerchantAdminPort，本地mch表；准入AdmissionPortImpl本地表+trade paidAmountToday supplier | 明确entity/store；本地当前读；不触发资格写或外部收单 |
| payMode OFFLINE | PayModePortImpl→PayModeServiceImpl→MerchantPayPortImpl与PlatformSwitchPortImpl本地配置 | 保留真实支持校验；无payment API |
| user community/receiver、pickup | UserQueryPortImpl/PickupQueryPortImpl/CommunityQueryPort本地表 | SERVICE地址/pickup空，仍审计真实空值分支；本地读取可保留 |
| freight/receiver region/delivery radius/slot/group | 现分支在STORE_VERIFY、空地址/slot、group=false下不进入远程/slot写 | 用测试证明确实0次；不能凭注释假定 |
| purchaseLimit/closeRule/orderNo | PurchaseLimitGuard和CloseRuleService本地mapper/配置；OrderNoGenerator本地号段 | 读本地；号段消耗明确不计销售/资金事实 |
| gift/auto activity/commit | CampaignPortRouter→CampaignPortImpl+ActivityPricingServiceImpl，后者本地member事实与pmt mapper | buyer明确；unsupported证据早拒；commit复用同Discount原事务 |
| CouponPortRouter.allocate | LegacyCouponRouteResolver可调PAY registry及legacyPay.estimate；LegacyCouponIdentity.ownedOrigins另开RR TX | managed不调用该可能远程路径；使用下面真实local-only增量，既不全禁券也不信预检 |
| period ticket | 本地PeriodPortImpl但产生复杂订单归属 | prepare+final发现任何ticket即UNSUPPORTED；不bind，不遗漏自动period |
| creationRecovery.register/enlist | 当前平台REQUIRES_NEW意图+当前TX持锁；恢复afterCompletion | seal之前绝不register；沿既有恢复，无新saga |
| stock | StockPortImpl本地平台NESTED；DualWriteStockPort=平台+本地outbox；InventoryStockPort=本地invTransactionManager独立库，不是HTTP | PLATFORM/DUAL同平台写；INVENTORY继续既有独立库意图补偿，不能称与ord原子；不把它误认remote。native主验证PLATFORM；两替代形态需针对真实beans验证，未验证不宣称 |
| attribution/card | AttributionPortImpl本地；CardProductSalePortImpl本地商品binding mapper | attribution保留；SERVICE仍查真实binding并拒任何售卡命中，postseal不做capture |
| cart delete/status log/outbox/coupon scope | 本地trade mapper、OutboxEventBus写队列 | 同TX；真实commit失败整体回滚，不删优惠成交 |
| doCreate尾detailOf | 含logisticsPort.summariesOf，物流可能Remote；payView的storeNames为MerchantPortImpl本地；paymentResolution本地mapper | managed用`checkoutCreatedView(orderNo,buyer)`：本地order/sub→已有payView；禁止detailOf扩充物流。旧writer返回不变 |
| source资金查询 | SettlePortImpl经paymentLedger可pay-client；真实receipt | 在source本地短TX外调用，最终锁内只核receipt匹配当前本地事实，不锁着order查支付 |

Catalog实际新增：

```java
// shop-base StoreSalesCatalogPort; core StoreSalesCatalogService/Impl同签名
Snapshot lockForCheckout(String buyerNo, String entityNo, String storeNo, Instant at);
// shop-base StoreDirectoryPort; shop-merchant StoreDirectoryPortImpl真实实现
StoreCard checkoutCardInCurrentTransaction(String tenantNo, String entityNo, String storeNo);
```

授权链是具体入口检查，非通用scope凭证：B prepare按§3 current merchant/permission/dataScope/strict引用授权，仍调用原read且锁外；C place紧邻TX用真实TokenStores核当前session并将规范buyer传writer；product锁方法在调用点再次要求当前SecurityUtils principal为MAIN/CONSUMER/USR、非空validated authentication details token、userNo精确等buyerNo。内部DelegatedIdentity/MERCHANT入口不能满足。它不在TX重查Redis；购买授权仍由锁定op/confirmation和CheckoutAccessPolicy决定，catalog只返回展示事实。

product方法要求`isActualTransactionActive()`且`TransactionSynchronizationManager.hasResource(@Qualifier("dataSource") source)`，使用既有`@Qualifier("transactionManager")`所属的同一平台source连接，禁止另开TX/切库；无TX、仅inv事务绑定或远端adapter均fail-closed。先调用上面的merchant实际本地方法，后者同样注入平台DataSource并核当前同一source resource，精确校验MAIN，复用现`cards`的MchStoreMapper+MchEntityMapper过滤/组装（ACTIVE实体、未删除店）、再核entity/store相等且店ACTIVE；READONLY只可被原read显示，不能成交。它不调Remote、不读取B授权。SPI默认实现直接抛CHECKOUT_DEPENDENCY_UNAVAILABLE，不能fallback cards/RPC；仅HOST现StoreDirectoryPortImpl实现新方法。core product只依赖SPI，不引用merchant具体类。

随后product用**既有**`ProductMappers.StoreCatalogMapper.lockHead(entity,store)`新SELECT FOR UPDATE取得head（MAIN/entity/store/deleted=0），复用既有StoreCatalogVersionMapper有效时间/修订排序与snapshot hash校验。无配置/不可见/销售窗口不符均拒。原publish对同head的CAS UPDATE取得相同行锁，发布必须等checkout提交；读取期间不能插入对同head生效的新版本绕过锁。没有head则拒，不创建空目录。catalog的Definition只有可见性/窗口，**没有售价**；最终商品/store价/flash均来自本次GoodsQueryPort与writer，不能从catalog捏造money。保留原read/write的requireExternalScope禁caller TX和原store核验，不整体移除防线。

本地性防线落在真实SPI能力：新StoreDirectory事务方法和Catalog锁方法默认throw，Remote没有可调用HTTP实现；HOST真实bean事务绑定断言+测试覆盖。GoodsQuery/Campaign/Coupon显式buyer/ordinary新增方法同样默认throw，现HOST逐个提供真实实现；不以类名字符串/instanceof跨域识别。默认失败意味着没有合格本地实现时该checkout不可用，旧read/普通订单不受影响。

券最小增量是`CouponPort.quoteOrdinary(String userNo,String userCouponNo,List<MerchantAmount> groups): Allocation`和`lockOrdinary(String userNo,String userCouponNo): void`。MKT CouponPortImpl实际实现；HOST CouponPortRouter负责本地来源路由；PMT不是CouponPort实现，新增同签名对应方法到现`promotion.service.CouponAllocService/impl.CouponAllocServiceImpl`，直接复用自己的allocate/assertUsable/CouponCalculation。quote不预占；lock要求平台TX与同DataSource绑定。owner检查真实类型与全部约束，不凭Allocation>0推断普通券。

| 实际owner | 56支持的精确条件 | 明确拒绝 |
|---|---|---|
| `marketing.coupon.entity.MktCoupon` | type FULL_CUT/DISCOUNT；现归属/时间/status/金额门槛；funder PLATFORM/MERCHANT；复用现discountFor和分摊 | 其它类型/无owner/外部PAY |
| `promotion.entity.PmtCoupon` | type CASH/PERCENT；redeemMode ORDER；timesTotal为null或1（null沿现timesTotalOrOne=1）；scopeType ALL且无restrictive scope行；minQty为null或0；benefitRef空；现归属/时间/status/金额门槛 | GIFT/FREE_SHIP、STORE_CODE、timesTotal>1或非正异常值、STORE/CATEGORY/GOODS范围、正minQty、benefitRef资产引用 |

PMT当前allocate只按entity/金额过滤，不执行scopeType/minQty，因此不能宣称它已支持这些条件；这一步在owner查`pmt_coupon_scope`真实行并明确UNSUPPORTED，不扩写券算法、不静默忽略条件。增加/复用该owner scope mapper的精确查询即可。无券直接Allocation.none，不执行迁移检查。

router调用既有source-fence owner新增的实际ordinarySource/lockOrdinarySource能力；LegacyCouponSourceFenceServiceImpl内部以既有`LegacyCouponFenceMapper.promoOrigin/legacyOrigin`读本地真实source行（不读PAY registry或projection）。两来源存在仍PMT优先，但所有可见来源都须rootPresent、entity一致、未删除、state为OPEN/ABORT_DECIDED；DRAINING/FROZEN/COMMIT_DECIDED、root缺失/UNRESOLVED、PAY仅原生/projection均UNSUPPORTED。prepare只早拒；place quote锁之后，按source/template固定序用真实`LegacyCouponSourceFenceService.requireWritable`（MANDATORY、root FOR UPDATE）锁全部根，再由各owner锁自己的template/instance、重新核user及上述条件。quoteOrdinary随后直接promo/legacy本地allocate，最终markUsed仍执行原CAS+fence；同次Allocation进入seal。

迁移若先锁root，place见新状态拒且register=0；place先锁则迁移等提交。绝不在自己持root时调用REQUIRES_NEW ownedOrigins，避免自锁。root/schema锁只由原owner持有，trade不直查券表；无锁内PAY请求。旧普通券router不变。

活动另一个必要真实增量：`CampaignPort.requireOrdinarySelection(String buyerNo, Discount selected): void`，由router分发给现MKT CampaignPortImpl及PMT ActivityPricingService/Impl。锁外prepare只读校验；final TX选中活动按owner/no排序当前行锁再校验。MKT只接受实际FULL_CUT；PMT旧活动只接受CUT金额，拒COMBO（含0金额赠积分）、GROUP/CUTOFF/GIFT/COUPON；unified ORDER活动读取真实currentVersion规则并复用现`UnifiedOrderActivityPricingServiceImpl.supportsDefinition`（CNY、无tiers、benefits全CUT、trigger NONE/AMOUNT/QTY）。该支持判断经现service接口暴露给ActivityPricingService，不让trade导入promotion实现。条件不满足即UNSUPPORTED，不删活动后改算。被锁选中行直到commit不能变成带积分类型；commit仍消费同一个Discount。new explicit-buyer flash方法复用现min-price循环，只接受实际普通PRICE来源（GROUP已由原算法排除，复杂CUTOFF/COMBO等若成为最低价命中则拒），legacy FLASH保留；giftFor返回真实结果后非空拒。所有这些检查在prepare和final都执行；unified已支持的固定CUT不重复造引擎。

新接口对旧调用方保持旧方法；实现必须补全新实际方法。可用fail-closed默认异常作为兼容adapter防线，但本HOST真实bean不能走默认值；契约测试逐bean证明真实实现。architecture/test记录此额外现Port合同面，不能只数Checkout六方法而遗漏。

## 6. 持久表、状态与追加SQL提案

当前实际最大平台V538（member_reference_actor_namespace）。建议唯一新迁移 `backend/shop-app/src/main/resources/db/migration/V539__checkout_source_foundation.sql`，执行/落产品前再查最大号，root固定最终SQL SHA256后冻结。以下完整DDL是审阅提案，未创建SQL文件、未执行。无美业V1–V7/ready7/27表改动，无pay表、回填或旧迁移改写。

BaseEntity使用 `id/tenant_no/created_at/created_by/updated_at/updated_by/version/deleted`。引用大小写敏感binary collation；所有查写显式MAIN+caller/op归属。无跨库FK；同owner关联由唯一键、SQL条件和同TX断言。`command_json`是规范buyer+范围+安全命令，不存原身份凭证。`pricing_json`只存此次事实，不是可编辑价格规则。quoteNo等随机号不进quoteHash。

```sql
CREATE TABLE ord_checkout_operation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    operation_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    caller VARCHAR(16) COLLATE utf8mb4_bin NOT NULL,
    entity_no VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
    store_no VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
    buyer_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    actor_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    actor_realm VARCHAR(16) NOT NULL,
    actor_kind VARCHAR(8) NOT NULL,
    request_key_hash CHAR(64) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    scope_revision BIGINT NOT NULL,
    scope_hash CHAR(64) NOT NULL,
    command_json LONGTEXT NOT NULL,
    phase VARCHAR(24) NOT NULL,
    current_quote_no VARCHAR(40) COLLATE utf8mb4_bin NOT NULL,
    confirmation_no VARCHAR(40) COLLATE utf8mb4_bin NULL,
    place_key_hash CHAR(64) NULL,
    place_payload_hash CHAR(64) NULL,
    order_no VARCHAR(64) COLLATE utf8mb4_bin NULL,
    sub_order_no VARCHAR(64) COLLATE utf8mb4_bin NULL,
    source_revision BIGINT NOT NULL DEFAULT 0,
    source_observation_hash CHAR(64) NULL,
    last_error_code VARCHAR(64) NULL,
    next_retry_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL,
    created_by VARCHAR(64) NULL,
    updated_at DATETIME(3) NOT NULL,
    updated_by VARCHAR(64) NULL,
    version BIGINT NOT NULL DEFAULT 1,
    deleted INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_checkout_operation (tenant_no,caller,operation_no),
    UNIQUE KEY uk_checkout_prepare_key (tenant_no,caller,entity_no,store_no,request_key_hash),
    UNIQUE KEY uk_checkout_place_key (tenant_no,buyer_no,place_key_hash),
    UNIQUE KEY uk_checkout_order (tenant_no,order_no),
    UNIQUE KEY uk_checkout_sub_order (tenant_no,sub_order_no),
    CONSTRAINT ck_checkout_operation_scope CHECK (tenant_no='MAIN' AND caller='BEAUTY' AND scope_revision>0),
    CONSTRAINT ck_checkout_operation_actor CHECK (actor_realm='MERCHANT' AND actor_kind IN ('MCH','USR')),
    CONSTRAINT ck_checkout_operation_phase CHECK (phase IN ('QUOTED','CONFIRMED','CREATED','REQUOTE_REQUIRED','REJECTED')),
    CONSTRAINT ck_checkout_operation_created CHECK ((phase='CREATED' AND order_no IS NOT NULL AND sub_order_no IS NOT NULL AND confirmation_no IS NOT NULL AND place_key_hash IS NOT NULL) OR (phase<>'CREATED' AND order_no IS NULL AND sub_order_no IS NULL)),
    CONSTRAINT ck_checkout_operation_confirmation CHECK ((phase='QUOTED' AND confirmation_no IS NULL AND place_key_hash IS NULL) OR (phase IN ('CONFIRMED','CREATED') AND confirmation_no IS NOT NULL) OR phase IN ('REQUOTE_REQUIRED','REJECTED')),
    CONSTRAINT ck_checkout_operation_place CHECK ((place_key_hash IS NULL AND place_payload_hash IS NULL) OR (place_key_hash IS NOT NULL AND place_payload_hash IS NOT NULL)),
    CONSTRAINT ck_checkout_operation_source CHECK (source_revision>=0 AND ((source_revision=0 AND source_observation_hash IS NULL) OR (source_revision>0 AND source_observation_hash IS NOT NULL))),
    CONSTRAINT ck_checkout_operation_json CHECK (JSON_VALID(command_json)),
    CONSTRAINT ck_checkout_operation_deleted CHECK (deleted=0)
) ENGINE=InnoDB COMMENT='Durable ordinary checkout identity and order reference';

CREATE TABLE ord_checkout_quote (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    quote_no VARCHAR(40) COLLATE utf8mb4_bin NOT NULL,
    operation_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    caller VARCHAR(16) COLLATE utf8mb4_bin NOT NULL,
    revision BIGINT NOT NULL,
    quote_hash CHAR(64) NOT NULL,
    pricing_json LONGTEXT NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    created_by VARCHAR(64) NULL,
    updated_at DATETIME(3) NOT NULL,
    updated_by VARCHAR(64) NULL,
    version BIGINT NOT NULL DEFAULT 1,
    deleted INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_checkout_quote (tenant_no,quote_no),
    UNIQUE KEY uk_checkout_quote_revision (tenant_no,caller,operation_no,revision),
    CONSTRAINT ck_checkout_quote_scope CHECK (tenant_no='MAIN' AND caller='BEAUTY' AND revision=1),
    CONSTRAINT ck_checkout_quote_status CHECK (status IN ('OPEN','CONFIRMED','CONSUMED','EXPIRED','REVOKED')),
    CONSTRAINT ck_checkout_quote_json CHECK (JSON_VALID(pricing_json)),
    CONSTRAINT ck_checkout_quote_deleted CHECK (deleted=0)
) ENGINE=InnoDB COMMENT='Immutable observed ordinary economic quote';

CREATE TABLE ord_checkout_confirmation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    confirmation_no VARCHAR(40) COLLATE utf8mb4_bin NOT NULL,
    caller VARCHAR(16) COLLATE utf8mb4_bin NOT NULL,
    operation_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    quote_no VARCHAR(40) COLLATE utf8mb4_bin NOT NULL,
    quote_revision BIGINT NOT NULL,
    quote_hash CHAR(64) NOT NULL,
    buyer_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    request_key_hash CHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    purpose VARCHAR(40) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    status VARCHAR(16) NOT NULL,
    confirmed_at DATETIME(3) NOT NULL,
    consumed_at DATETIME(3) NULL,
    order_no VARCHAR(64) COLLATE utf8mb4_bin NULL,
    created_at DATETIME(3) NOT NULL,
    created_by VARCHAR(64) NULL,
    updated_at DATETIME(3) NOT NULL,
    updated_by VARCHAR(64) NULL,
    version BIGINT NOT NULL DEFAULT 1,
    deleted INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_checkout_confirmation (tenant_no,confirmation_no),
    UNIQUE KEY uk_checkout_confirm_key (tenant_no,buyer_no,request_key_hash),
    UNIQUE KEY uk_checkout_confirm_quote (tenant_no,caller,operation_no,quote_no),
    CONSTRAINT ck_checkout_confirm_scope CHECK (tenant_no='MAIN' AND caller='BEAUTY' AND quote_revision=1),
    CONSTRAINT ck_checkout_confirm_money CHECK (amount_minor>=0 AND currency='CNY' AND purpose='ORDINARY_SERVICE_PURCHASE'),
    CONSTRAINT ck_checkout_confirm_state CHECK ((status='CONSUMED' AND consumed_at IS NOT NULL AND order_no IS NOT NULL) OR (status IN ('AUTHORIZED','REVOKED','EXPIRED') AND consumed_at IS NULL AND order_no IS NULL)),
    CONSTRAINT ck_checkout_confirm_deleted CHECK (deleted=0)
) ENGINE=InnoDB COMMENT='Current consumer consent bound to one quote and purchase';

CREATE TABLE ord_checkout_line (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    source_line_no VARCHAR(40) COLLATE utf8mb4_bin NOT NULL,
    caller VARCHAR(16) COLLATE utf8mb4_bin NOT NULL,
    operation_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    client_line_ref VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    sub_order_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    order_item_id BIGINT NOT NULL,
    goods_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    sku_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    quantity INT NOT NULL,
    uom VARCHAR(8) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    created_by VARCHAR(64) NULL,
    updated_at DATETIME(3) NOT NULL,
    updated_by VARCHAR(64) NULL,
    version BIGINT NOT NULL DEFAULT 1,
    deleted INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_checkout_source_line (tenant_no,source_line_no),
    UNIQUE KEY uk_checkout_client_line (tenant_no,caller,operation_no,client_line_ref),
    UNIQUE KEY uk_checkout_item (tenant_no,order_item_id),
    CONSTRAINT ck_checkout_line_scope CHECK (tenant_no='MAIN' AND caller='BEAUTY'),
    CONSTRAINT ck_checkout_line_count CHECK (quantity BETWEEN 1 AND 999 AND uom='COUNT'),
    CONSTRAINT ck_checkout_line_deleted CHECK (deleted=0)
) ENGINE=InnoDB COMMENT='Stable public source identity written with the actual order item';

ALTER TABLE ord_sub_order
    ADD COLUMN fulfillment_authority VARCHAR(32) NULL COMMENT 'NULL legacy or SERVICE_EXECUTION',
    ADD COLUMN source_operation_no VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT 'Immutable ordinary checkout operation',
    ADD UNIQUE KEY uk_sub_checkout_operation (tenant_no,source_operation_no),
    ADD CONSTRAINT ck_sub_checkout_authority CHECK (
        (fulfillment_authority IS NULL AND source_operation_no IS NULL)
        OR (fulfillment_authority IS NOT NULL AND fulfillment_authority='SERVICE_EXECUTION' AND source_operation_no IS NOT NULL AND fulfillment IS NOT NULL AND fulfillment='STORE_VERIFY'));
```

提交状态对应表（同owner跨表关系由同TX断言与条件UPDATE守护，SQL CHECK不伪跨表约束）：

| op持久phase | quote.status | confirmation.status | key/订单 |
|---|---|---|---|
| QUOTED | OPEN | 不存在 | current_quote_no先生成，op+quote一次TX插入；无PREPARING提交态 |
| CONFIRMED | CONFIRMED | AUTHORIZED | confirm首key已绑定；place首key可null或claim已提交；order/sub均null |
| CREATED | CONSUMED | CONSUMED | 首place key+order/sub+lines同TX提交 |
| REQUOTE_REQUIRED | EXPIRED或REVOKED | 若存在则同原因EXPIRED/REVOKED | 保留原key；order/sub均null，不换quoteRevision |
| REJECTED | REVOKED | 若存在则REVOKED | 保留首key；无订单；包括原intent RELEASED后的终止 |

PREPARING只存在本地调用栈，不是DB/成功DTO phase；失败prepare全回滚。PLACING仅findOperation依据原intent未终结派生的恢复观察，不入op.phase，不能与CREATED并存。current_quote_no在任何op INSERT前已分配，非空不与准备态矛盾。readQuote在事务外发现expiry只可显示EXPIRED不可用，状态物化由锁op→quote→confirmation的短TX完成，不把过期信息覆写已CONSUMED。

实体精确FQN均在`ai.neargo.shop.trade.entity`：OrdCheckoutOperation/Quote/Confirmation/Line；OrdSubOrder加两字段读写。`trade.mapper.CheckoutMappers`嵌套OperationMapper/QuoteMapper/ConfirmationMapper/LineMapper（本域service直接mapper）。锁查询显式tenant/caller/op及deleted=0；所有状态推进用version条件UPDATE，0行冲突。quote经济内容发布后不可UPDATE；confirmation金额/quote/buyer不可改；line只insert。hash用64hex应用校验，DB JSON/check/唯一键须原生验收。

## 7. source真实事实与资金边界

`source`和`findOperation`均从永久op映射定位真实ord，不接受任意sub覆盖operation。line必须连接真实order_item_id，核goods/SKU/qty/sub/tenant与冻结mapping一致；若缺失/矛盾返回冲突/UNKNOWN，不能按SKU/排序修复。SourceLine.dueMinor取真实paidShareMinor；orderedQuantity取真实item.qty，uom取新mapping明确COUNT。`refundableQuantity`第一期表示owner尚未撤销/退款的数量上限，不是自动服务/退款授权：正常为max(0,qty-refundedQty)；主单CANCELLED/CLOSED或子单CANCELLED/REFUNDED为0；有open after-sale/未决退款则executionState BLOCKED，不把原ordered当永久可用。BM04永远NOT_STARTED或BLOCKED；BM05实际完成proof未开放。58消费时仍必须再核单位及数量。

资金读取两阶段：无锁本地预观察→锁外真实`SettlePort.successPayment(orderNo,channel,tradeNo)`（仅已有applied channel/tradeNo才走exact查询）→**新建REPEATABLE_READ平台事务**。该事务第一条DB语句是checkout op `FOR UPDATE`，随后才开始全部业务事实的普通consistent SELECT：order、sub、item、line mapping、退款/售后。sub/items不能FOR UPDATE，不能把当前读与旧snapshot拼接；进入该事务前不运行任何建snapshot的SELECT，也不参加外层已有TX。读取mapper不得内部切REQUIRES_NEW。op当前行是本次观察元数据锁，不当成业务快照的一部分。

同一op的source调用由此串行建立快照：后到者必须先等前者op提交，再建立自己的首个业务consistent read。已等到锁的调用不可能拿锁前快照覆盖较新hash。原退款writer无需取得op锁；它若在本次RR快照后提交，本次得到合法旧一致观察，下次观察看到新退款并增revision。订单/子单/item所有退款累计均来自这个RR快照，禁止逐项current读或多事务拼接。此序列不要求锁住退款writer，不增加source→支付的锁内RPC。

匹配同一快照的parent已应用channel/tradeNo、order、buyer、currency=CNY、原payAmount与真实receipt SUCCESS，才算验证成功；receipt API没有tenant字段，MAIN约束来自受权的本地order及HOST支付owner命名空间，不能声称已检查不存在的receipt.tenant。预取receipt与快照标识不相等则释放TX后重试一次，仍变或owner不可达/无receipt则UNKNOWN。57前PaymentFact的paymentOperationNo/outTradeNo/receiptNo可null；既有PaymentReceipt只填它确有的paymentNo/channel/tradeNo事实，不改名伪造新receipt。WAIT_PAY/OFFLINE无匹配receipt不猜实收0。

有确切receipt时receivedMinor=receipt.amountMinor-同快照真实累计已退款额（非负，越界UNKNOWN）；dueMinor保持历史order payAmount；outstandingMinor=max(0,当前owner尚有效应收-实收净额)，当前有效应收=已取消/关闭/全退时0，否则payAmount-refundedMinor。financialState PAID/PARTIALLY_REFUNDED/REFUNDED依真实资金/退款事实，不改写历史应付。支付列表只含匹配真实事实。

`RefundLedgerMapper.addItemRefund/addSubRefund`明确不增version，故不能用max/sum版本充当变化号。op的source_revision/source_observation_hash只记录最后**已验证一致观察**的指纹，不存金额账；hash包含同快照实际order/sub/items/refund/售后、mapped COUNT行及匹配receipt身份/金额，排除observedAt。原hash为空则写revision=1；hash变化则+1，相同保持；元数据更新仍持本次op锁。旧外部receipt若本地snapshot已换applied标识不会被采纳；如退款已增，同一receipt结合当前同快照退款形成新事实。

UNKNOWN严格不更新该pair，不构造“本次unknown hash”：返回`sourceRevision/sourceHash`为**最后verified引用**（首次未知为`0/null`），`financialState=UNKNOWN`、`executionState=BLOCKED`、`receivedMinor/outstandingMinor=null`、`lines=[]`、`payments=[]`，不暴露可服务数量。这个pair标识上一次验证结果，**不代表本次当前可消费事实**；58必须同时要求本次非UNKNOWN/可执行状态、非空合格行及版本hash条件，不得只看旧pair开工。每个正revision始终只对应一个hash，未知不会制造同revision异hash或把旧ready伪装成当前ready。

## 8. 最低COMPLETED守卫及旧支付入口

`trade.policy.ManagedServiceCompletionPolicy.requireAllowed(String subOrderNo,String targetStatus): void`读取**当前持久**OrdSubOrder.fulfillmentAuthority；target COMPLETED且SERVICE_EXECUTION拒 `MANAGED_SERVICE_COMPLETION_BLOCKED`。`MybatisStatusStore.cas`订单分支在CAS之前调用，TradeStatusWriter装配该policy；直接使用旧MybatisStatusStore构造的路径也必须取得真实policy（旧测试构造可用同mapper默认构造真实policy，不能NONE）。不可在fired.changed之后、markOrderCompleted或outbox之后才查。

最底层StatusCasMapper.casOrder增加：

```sql
AND (#{to} <> 'COMPLETED' OR fulfillment_authority IS NULL
     OR fulfillment_authority <> 'SERVICE_EXECUTION')
```

还加deleted=0；已冻结authority列只有checkout建单赋值，不能清空来绕过。不要boolean override或虚构proof。policy利于错误语义，底层SQL兜住直接CAS；CAS返回0的低层测试必须验证无COMPLETED/完成时间/完成事件。所有已审计入口：Pickup/FulfillmentQueryPort.complete→VERIFIED；MerchantOrderService.delivered；OrderService.confirmReceipt→receipts.confirmCurrent；OrderReceiptCompleterImpl completeAuto/completeSigned/legacy；ReceiptHandler.completeIfAllReceived→OrderTransitions.applySub；平台intervene；TradeStatusWriter与直接StatusCasMapper。Shipment更新不能先伪造完成后期待总单守卫；事务反例核shipment/日志回滚。

`OrderServiceImpl.doMarkPaid`读持久authority：managed支付成功仍按普通订单付款状态迁移，**不生成verifyCode**，不调用notifyShippingOnPaid（付款并不证明服务交付），事件文案不称“凭码使用”。现物流通道的实际交付上报留BM05执行边界。未管理零售/餐饮保持原出码与收货路径。

root已接受这一最小安全裁决：56期间旧`MerchantOrderService.confirmOfflinePay`及`PaymentLifecycleServiceImpl.requireConfirmed`的OFFLINE直接信任分支对managed拒（本地查子单authority）；仅防止56已有单被旧入口伪收款，不实现57的receipt或权限。不新增cash permission。真实取消/未付超时/退款仍走原owner状态、库存恢复和资金事实，不能把取消误映COMPLETED。

新ErrorCode拟20015 CHECKOUT_QUOTE_STALE、20016 CHECKOUT_UNSUPPORTED_SELECTION、20017 CHECKOUT_CONFIRMATION_REQUIRED、20018 MANAGED_SERVICE_COMPLETION_BLOCKED、20019 CHECKOUT_DEPENDENCY_UNAVAILABLE、20020 CHECKOUT_OPERATION_REJECTED；当前trade已到20014，实施前再查占号。三语keys一一对应；安全错误不带原token/ref或任意请求JSON。

## 9. Bean图与文件归属

Bean图：MpCheckoutController的GET→CheckoutService.readQuoteForCurrentBuyer；C写与InternalCheckoutController→CheckoutPortImpl→CheckoutServiceImpl→CheckoutAccessPolicy、CheckoutMappers、CheckoutOrderWriter(唯一OrderServiceImpl)；writer→CheckoutQuoteGuard+现商品/营销/merchant/stock Ports；ManagedServiceCompletionPolicy→StoreOrderMapper/StatusCasMapper；TradeStatusWriter→MybatisStatusStore(policy)→StatusCasMapper。无Repository转发层、C Remote、beauty依赖trade内部类型。

新建（owned候选）：

* `backend/shop-base/src/main/java/ai/neargo/shop/spi/trade/CheckoutPort.java`、`CheckoutInternalApi.java`。
* `backend/shop-core/src/main/java/ai/neargo/shop/trade/service/CheckoutService.java`、`CheckoutOrderWriter.java`，`service/impl/CheckoutServiceImpl.java`，`port/CheckoutPortImpl.java`。
* 同core `trade/policy/CheckoutAccessPolicy.java`、`ManagedServiceCompletionPolicy.java`；`trade/order/service/create/ExpectedCheckoutQuote.java`、`CheckoutPricingScope.java`、`CheckoutEconomicSnapshot.java`、`CheckoutQuoteGuard.java`。
* 同core `trade/entity/OrdCheckoutOperation.java`、`OrdCheckoutQuote.java`、`OrdCheckoutConfirmation.java`、`OrdCheckoutLine.java`；`trade/mapper/CheckoutMappers.java`；`trade/api/mp/MpCheckoutController.java`。
* `backend/shop-app/src/main/java/ai/neargo/shop/portal/internal/InternalCheckoutController.java`；候选V539；七个测试类及原生runner（见§10）。

修改（owned候选，精确到职责）：

* `backend/shop-base/src/main/java/ai/neargo/shop/spi/product/GoodsQueryPort.java`、`StoreSalesCatalogPort.java`；`spi/marketing/CampaignPort.java`、`CouponPort.java`：上述真实新增方法。
* `backend/shop-core/src/main/java/ai/neargo/shop/product/port/GoodsQueryPortImpl.java`、`StoreSalesCatalogPortImpl.java`；`product/service/StoreSalesCatalogService.java`、`product/service/impl/StoreSalesCatalogServiceImpl.java`、`product/mapper/ProductMappers.java`的现nested StoreCatalogMapper/StoreCatalogVersionMapper；不造第二mapper。
* core `marketing/port/CampaignPortImpl.java`、`CouponPortImpl.java`；app `portal/port/CampaignPortRouter.java`、`CouponPortRouter.java`：实际buyer与本地券；app `paybridge/mapper/LegacyCouponFenceMapper.java`仅必要的当前source查询；复用fence不改迁移协议。core `promotion/service/CouponAllocService.java`、`impl/CouponAllocServiceImpl.java`、`promotion/mapper/PromotionMappers.java`（CouponScopeMapper）；`promotion/service/ActivityPricingService.java`、`impl/ActivityPricingServiceImpl.java`、`service/UnifiedOrderActivityPricingService.java`、`impl/UnifiedOrderActivityPricingServiceImpl.java`为真实普通能力判断。
* base `spi/user/StoreDirectoryPort.java`及`backend/shop-merchant/src/main/java/ai/neargo/shop/merchant/port/StoreDirectoryPortImpl.java`：平台同TX门店事实能力，仅复用本地owner mapper。
* core `trade/service/impl/OrderServiceImpl.java`、`MerchantOrderServiceImpl.java`；`trade/order/service/lifecycle/impl/PaymentLifecycleServiceImpl.java`；`trade/entity/OrdSubOrder.java`；`trade/order/transition/TradeStatusWriter.java`、`impl/MybatisStatusStore.java`；`trade/order/mapper/StatusCasMapper.java`。
* `backend/shop-base-auth/src/main/java/ai/neargo/shop/auth/BizPerms.java`；`backend/shop-base/src/main/java/ai/neargo/shop/common/ErrorCode.java`；`backend/shop-app/src/main/resources/i18n/messages.properties`、`messages_en.properties`、`messages_ar.properties`；`b-app/src/i18n/locale/en.ts`、`ar.ts`、`zh-CN.ts`；`packages/shared/src/mock/db.ts`只补权限label，不改默认role grants。
* `backend/shop-app/src/test/resources/schema-test.sql`仅生成器产物；现Java controller/DTO/BizPerms/entity作为API/分层/权限/表清单生成真源。生成器分别为`scripts/gen-backend-layers.mjs`、`gen-table-inventory.mjs`、`gen-biz-role-matrix.mjs`、`gen-biz-feature-perm-matrix.mjs`、`gen-perm-domain-matrix.py`、`gen-glossary.mjs`、`backend/scripts/gen-test-schema.py`；对应docs/technical/reference下同名后端分层/数据库表清单/三份权限矩阵/术语生成物只收本步实际diff。HTTP三条是真实后端owner路由；56不添加c-app行业adapter/endpoints业务使用，59接线。若现API扫描要求登记，限该三条实际路由，不增加未来pay contract。root主TDD/brief/preflight/整体设计/progress/reviews绝不stage。

无支付新表/Port，beauty私表/V1–7/ready7、food迁移、known-* ratchet、Task55文件不动。本节已定位真实nested mapper和权限/生成真源；此处为设计阶段预期清单，实施技术放行已完成；实际85文件owned以report§22及manifest为准。root负责ADR-046，不纳本agent产品提交。

## 10. AC、RED/GREEN和原生执行计划（尚未运行）

七类分别创建于：Contract在`backend/shop-base/src/test/java/ai/neargo/shop/spi/trade/CheckoutContractTest.java`；其余在`backend/shop-app/src/test/java/ai/neargo/shop/scenario/{Class}.java`。测试用真实商品、Catalog、Ord writer、mapper、当前权限owner与真实TokenStore+Consumer/Merchant认证filter+MockMvc HTTP；外部pay/logistics远程通道允许边界fixture/fault代理，不能mock OrderService/product/managed mapper来造绿。

| AC | 精确selector/关键断言 |
|---|---|
| DTO/version/COUNT/限额 | CheckoutContractTest#rejectsInvalidScopeQuantityAndProtocolVersion；#sixMethodsAreRealAndNoPaymentMethodsExist；0/1000/21行/重复SKU/ref/EA等输入失败；反序列化未知协议拒绝 |
| B权限与订单数据域 | CheckoutPermissionFlowTest#pickerAndClerkCannotPrepareCheckout；#explicitGrantAndRevocationTakeEffect；#ownerHasNewPermissionByExistingWildcard；#crossStoreAndCourierCannotReadCheckoutMoney；#internalDelegationRechecksCurrentActorNamespace |
| C真实身份 | CheckoutAuthenticationFlowTest#consumerSessionRequiredForConfirmationAndPlacement；#merchantDelegationCannotImpersonateBuyer；#revokedTokenCannotWrite；#foreignBuyerCannotReadConfirmOrReplayPlace；真实TokenStores签发走filter，不仅WithMockUser |
| 安全引用/恢复 | CheckoutAuthenticationFlowTest#prepareReplayUsesOriginalResolvedBuyerAfterReferenceExpiry；#rawIdentityCredentialsNeverReachRowsOrLogs；changed ref同key拒；首次strict旧无namespace/cr1伪造拒 |
| 实际价格/完整seal | CheckoutPricingFlowTest#priceMutationAfterPrepareIsRejectedAtWriterSealBeforeStock；#sameAmountDifferentDiscountFundingFailsSeal；#couponSelectionChangesRequireNewConsent；观测ord_create_intent、stock、slot、ord全部无新增事实 |
| 纯shares/显式buyer | CheckoutPricingFlowTest#persistedItemSharesComeFromValidatedSnapshot；#merchantPrepareUsesBuyerAudienceForFlashGiftAndDiscount；不均分余数tie保持原序；gross/paidShare/主子funding逐值对账 |
| 目录/能力 | CheckoutPricingFlowTest#catalogMutationIsRecheckedInFinalWriterTransaction；#unsupportedSkuFulfillmentNeverBooksLegacySlot；#giftFallbackAndPointsStagingAreExplicitlyRejected；#ordinaryCreateForStillUsesOriginalWriter；#catalogLockRejectsMissingPlatformTransactionAndForeignConsumer；#remoteDirectoryCannotFallbackInsideCatalogLock |
| local券迁移+IO | CheckoutPricingFlowTest#ordinaryLocalCouponStillUsesOwnerAllocation；#unsupportedCouponScopeOrComboPointsNeverSilentlyDropsBenefit；#couponMigrationWinsBeforeFinalLockAndRejectsBeforeIntent；#checkoutLockWinsAndMigrationWaitsForCommit；#noRemoteOwnerCallOccursWhileCheckoutRowsAreLocked；postseal logistics也设阻断spy证明0次 |
| managed所有完成口 | ManagedServiceCompletionFlowTest#allLegacyCompletionPathsRejectManagedOrder 参数化枚举pickup/merchant/currentC/auto/signed/legacy/intervention/directCas；#legacyUnmanagedServiceStillCompletes；#paymentAndExpiryNeverMarkServiceCompleted；#managedOfflineShortcutCannotInventReceipt |
| source/历史退款 | CheckoutSourceFactsFlowTest#billUsesHistoricalOwnerMoneyAndStableBusinessLines；#mismatchedReceiptDoesNotBecomeReceivedMoney；#ordinaryServiceQuantityHasExplicitCountUnit；#refundWithoutVersionIncrementChangesSourceHashAndAvailableQuantity；#cancelledOrderHasZeroAvailableQuantity；#unknownReceiptNeverBecomesZeroMoney；#unknownKeepsLastVerifiedPairAndReturnsNoConsumableLines；#serializedSnapshotCannotOverwriteNewRefundObservation；#refundBetweenFactReadsNeverProducesMixedSnapshot |
| 原生唯一/并发/持久恢复 | CheckoutNativeMysqlTest#sameKeyCreatesOneOrderAndStableSourceLines；#differentKeysCannotConsumeConfirmationTwice（一个成功、一个CONFLICT）；#confirmationFirstKeyIsPermanent；#placeClaimSurvivesWriterRollbackAndReleasedIntent；#lostResponseAndRestartFindOriginalOrder；#expiredIdempotencyCacheDoesNotCreateAgain；#releasedCreateIntentCannotReviveOperation；#nativeConstraintsRejectCrossBoundAndInvalidFacts（含authority=NULL/sourceOperation非NULL、authority非NULL/sourceOperation=NULL、managed但fulfillment=NULL/非STORE_VERIFY四个拒绝反例）；#writerSealPrecedesDurableIntent；#managedCasBlocksCompletion |
| 最小消融 | 去掉StatusCasMapper managed条件，直接CAS原生断言必须business RED；还原GREEN。另价格seal断言若被前置校验先挡，要改变测试注入时点证明真正到writer seal |

先行为RED，编译失败/fixture未装/条件skip不算RED。RED必须与实际断言挂钩，待骨架编译后在现真实writer观察业务失败，然后最小实现。七类不存在/0测试/skip不能声称完成。root放行SQL/hash后方能新建和运行以下runner。

统一JDK21：`/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`；offline M2=`/tmp/codex-beauty-bm01-m2`。定向命令：

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -o -Dmaven.repo.local=/tmp/codex-beauty-bm01-m2 -f backend/pom.xml -pl shop-app -am test -Dtest=CheckoutContractTest,CheckoutPermissionFlowTest,CheckoutAuthenticationFlowTest,CheckoutPricingFlowTest,ManagedServiceCompletionFlowTest,CheckoutSourceFactsFlowTest -Dsurefire.failIfNoSpecifiedTests=false
```

原生新runner `scripts/verify-checkout-foundation.mjs`：每批先只读Colima MemAvailable≥512MiB；只用cached `mysql:9.7.2`、pull=never、普通DISK/innodb_buffer_pool_size=64M、单自有UUID容器/私有端口/随机schema/临时credential；bind127.0.0.1/readiness/timeout/finally仅清自有容器。禁止128MiB低内存实验、其它容器prune、改VM/远程、`scripts/test-on-mysql.sh`。从root pinSQL提取完整V539，生成可信正式schema-test夹具并显式标注不是全Flyway空库升级；同时验证V538真实基础表→新增V539，冻结旧迁移。必要的历史V506方言转换只在明确隔离fixture，不能称生产迁移全绿。

runner环境 `CHECKOUT_NATIVE_MYSQL_URL=jdbc:mysql://127.0.0.1:<port>/<own_schema>`、`CHECKOUT_NATIVE_MYSQL_USER`、`CHECKOUT_NATIVE_MYSQL_PASSWORD`；system property `checkout.test.native=true`；测试通过DynamicPropertySource导入真实datasource、关闭H2初始化，runner先建fixture再启动真实Spring owner。凭证不打印、不落report。两个上下文独立启动验证restart，不仅重new service。native command与上述Maven相同但`-Dtest=CheckoutNativeMysqlTest -Dcheckout.test.native=true`；须记录每方法actual count、errors、failures、skips。未跑不填数字。

相关回归：CheckoutOffersFlowTest、StoreOrderRoutingTest、OrderReceiptWiringFlowTest、OrderReceiptDeadlineFlowTest、OrderReceiptConcurrencyFlowTest、OrderAutoReceiptTransactionFlowTest、BizOrderFulfillFlowTest、M4FulfillmentFlowTest、OfflinePayFlowTest、LegacyCouponCheckoutJournalFlowTest、LegacyCouponCheckoutRecoveryFlowTest、FoodEmbeddedWiringTest；native race优先，已有flow按变更风险有针对性运行。不宣称food479新文件回归。ArchitectureTest、BackendI18nParityTest、ConditionalBeanWiringTest、SQL portability、entity alignment、generated docs和相关后端schema守卫按真实选择器核对；生成命令按现scripts/check-generated-docs.mjs记录，不改ratchet。OpsPoints/V506、beauty-domain/job.config注册、插件SQL定向扫描都是Task60欠账，保持明确。

## 11. root技术审阅点与实施顺序

1. 已裁决的source基线保持；审核§4显式buyer增量与§5 local券/目录锁方法，特别确认本地root锁不碰远程registry、managed尾detail不碰物流RPC。
2. 审核§6追加SQL/单operation不可重报价语义、§7观察revision（退款不增现version的现实）；在真实迁移文件首执行前固定SQL+hash。此文SQL尚未执行，不是迁移验收。
3. 按§10 RED→实现六方法与安全quote→同writer seal/native竞争→最低CAS/旧入口→source→相关回归/消融；每步报告真实日志。root先完成设计技术放行，再允许产品/测试/native；未放行前只本文与report。
4. 全部实现后交未commit tree/diff供root spec/quality独立审阅；获准后精确owned stage/commit，无push/merge/deploy。三份产物/主TDD由root对账，状态不能提前改已实现。

## 12. 实施后的精确对账（审查候选）

本节及report§18–22补充实际落点；前述RED/执行步骤保持批准时设计记录，不表示仍在等待技术实施放行。V539首sql block没有任何实施后改写，8444 bytes及固定SHA保持不变。

1. 契约仍6方法、4内部B路由、3当前C路由。current-buyer本地读方法真实存在；局部JsonAnySetter及严格整数CountDeserializer同时覆盖内部嵌套请求/C body，旧全局JSON配置不改。
2. CouponPortRouter最初直接依赖LegacyCouponFenceMapper触发既有architecture rule真实RED。已把来源验证及固定MKT→PMT root锁移回既有LegacyCouponSourceFenceServiceImpl。准确接口为SourceSystem ordinarySource(String tenantNo,String buyerNo,String userCouponNo)和SourceSystem lockOrdinarySource(String tenantNo,String buyerNo,String userCouponNo)。后者@Transactional(MANDATORY)，校验平台DataSource绑定、调用真实requireWritable并锁后重读；router随后调用实际owner instance锁和allocate。不增加一个仅转发Mapper的repository。现LegacyCouponFenceMapper/PromotionMappers均无需修改；两service文件进入实际owned。
3. explicitbuyer flash以ActivityPricingService.ordinaryFlashPrices复用现实际循环，明确拒获胜的CUTOFF/复杂触发；旧flash方法行为保持。managed金额用multiplyExact/addExact在split聚合前验证。最终目录刷新在coupon/activity全部潜在阻塞锁后，final seal再次读Clock验证expiry。
4. 同次LineShares由实际pricing返回束复用到OrdItem写入；sourceLine在该insert获ID时同TX记录。source RR事务第一条SQL锁op，之后全部事实普通consistent read；UNKNOWN只返回最后verified pair+空行/BLOCKED，不创建同revision新hash。
5. prepare key hash包含真实realm/subjectKind/actorNo；confirm/place各永久首key，不接受不同key别名。RELEASED原intent不复活；PREPARED/RECOVERING/RECOVERY_REQUIRED指向原意图观察，CREATED永久重放不重算今价。锁外不支持选择只是早拒；最终价格与scope seal仍由实际writer执行。
6. 新增CheckoutAccessPolicy、ManagedServiceCompletionPolicy遵守当前auth及最低CAS合同；旧offline最小拒绝、付款不发核销码；合法取消/未付超时仍沿真实原owner。signed service测试只证明STORE_VERIFY不适用EXPRESS，最低CAS另有实际直接用例。
7. 规范档位2，复用既有主TDD/PRD和根ADR046。实际85产品文件+2 ignored设计报告；根文件不stage，待独立review。精确测试结果、A/B消融恢复hash、静态HEAD8项相同失败/架构两项基线债、生成漂移与food形态边界见report，不能把Task56候选称整个backend/美业付款或双部署完成。
