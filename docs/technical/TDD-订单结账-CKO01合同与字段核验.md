# 订单结账CKO-01：合同、字段与能力基线

> 状态：源码合同核验完成，运行时开发及外部营业验收未完成 · 2026-10-22 · 档位2。执行[完整计划CKO-01](./TDD-餐饮订单结账-完整开发计划.md)；基线HEAD `217f4182a`，证据包含当前工作区源码快照。
>
> 机器清单：[pricing-checkout-contract-baseline.json](./design/schema/pricing-checkout-contract-baseline.json)。记录13项能力、源码文件/行号/锚点/SHA-256、7张既有fnb表的列与索引、7张待建ord表、23个目标API及权限/错误码登记计划。它是本次源码基线，不是对外服务已通过验收的声明。

## L1 本步结果

**共用部分在原零售及核心实现，餐饮、美业调用基础能力**的归属已确认。可以进入纯内核开发CKO-02；正式餐饮报价、独立收款/库存、后付、AA和储值混合仍受各自合同及环境门禁控制。

本次只读取仓库代码/迁移，不连接实际数据库、不读取支付凭证、不调用收退款、库存写入或外送平台。本篇“表已存在”仅指已在冻结迁移中定义，不能据此声称本地/远程数据库已应用到同一版本。

## L2 能力矩阵

状态分两轴：源码`SUPPORTED`表示已有指定范围的接口及实现依据，`CONTRACT_GAP`表示目标场景尚缺合同；环境独立标记`EXTERNAL_VERIFICATION_REQUIRED`。存在方法、默认返回值、Mock成功和服务健康200都不能单独证明真实交易支持。

| 编号 | 已有依据 | 源码状态 | 本次结论及任务 |
|---|---|---|---|
| CT01 | GoodsQueryPort.snapshot、StoreDirectoryPort | SUPPORTED | 可复用原商品/门店身份；VAT事实与餐饮价格映射需11补齐 |
| CT02 | OrderService.preview、CouponPort.allocate/markUsed/release | SUPPORTED | 当前单券、活动后用券；新多券/分项/金额后折扣不能直接宣称支持，05/06/11 |
| CT03 | EntitlementUsePort.quote/prepare/commit/release/query | SUPPORTED | 有selectedInstanceNos列表不等于支持任意叠折；UseItem数量int，11/21核验 |
| CT04 | StoredValueUseQuotePort、StoredValueRedemptionServiceImpl | CONTRACT_GAP | 真实来源仅CORE/SERVICE_USE、CNY、无freight、全额覆盖；餐饮/AED/部分混合关闭，21 |
| CT05 | PointsRedeemPort、PointsFactPort及真实实现 | SUPPORTED | 复用quote/hold/release与事实驱动协议；餐饮来源、储值后基数、税务分类待22 |
| CT06 | SettlePort、宿主SettlePortImpl | SUPPORTED | 普通本地收退款基础；币种必须显式，兼容构造器默认CNY，12/15 |
| CT07 | PayInternalApi与当前宿主桥接 | CONTRACT_GAP | 未证实普通订单收/查/关/退完整远程桥接，不能拿权益专用接口替代，12 |
| CT08 | StockPort、InventoryStockPort、ReservationService | SUPPORTED | 本地预留/释放/履约基础；StockPort数量int，原引用完整查询需补，13 |
| CT09 | 库存内部远程合同 | CONTRACT_GAP | 独立库不等于完整HTTP协议，订单不能调用公开stock:sync替代预留，13 |
| CT10 | fnb_settlement/item/charge_intent迁移 | CONTRACT_GAP | 表存在不等于CheckoutService、后付/AA/跨根分配已实现，16/19/20 |
| CT11 | AfterSaleServiceImpl及原退款协议 | SUPPORTED | 已有售后/部分退款基础，但一条实物退货现金路径明确限制full-child；餐饮逐行税/资产退款仍须23 |
| CT12 | 规则/税费及7表设计 | CONTRACT_GAP | 本轮未找到现成统一内核、7表迁移或完整可信VAT事实；02～11分步实施 |
| CT13 | 日报桥接、fnb当班迁移与打印设计 | CONTRACT_GAP | 原报表设施可复用；未证实完整共享PrintService及餐饮当班/日结实现，17/24/25 |

环境验证均未在本步执行。旧[支付库存核验](./TDD-餐饮系统-独立支付库存合同核验.md)关于普通独立交易门槛的结论仍有效；本篇补充最新积分及字段证据，不把“源码支持”扩写成“已可餐饮营业”。

## L3 合同与字段

## §0 需求 → 核验对账

| AC | CKO-01要求 | 结果 |
|---|---|---|
| F01 | 7新表与fnb已有字段/唯一约束差异 | §2.1～2.2及机器清单schema，已核对仓库迁移 |
| F02 | 原Port动作、身份、状态、幂等和恢复 | §2.3、CT01～13及文件hash证据 |
| F03 | 权限/错误码、canonical API与类归属 | §2.4～2.6，明确已有/目标/未登记 |
| F04 | 冻结下一阶段可实现合同并保留零售兼容 | §2.7纯内核合同；缺失外部协议列为后续任务门禁 |

## §1 证据采集及范围

扫描范围：shop-base SPI、shop-core.trade、宿主paybridge/payclient/invbridge、pay-domain及pay-svc相关源码、shop-inventory、food-api/core/web/channel及核心/餐饮迁移目录。实际证据文件及锚点以JSON为准，不仅依赖历史文档。

只读检索还确认：现有`CreateOrderCommand`是`OrderService`内嵌record，不是`trade.dto.CreateOrderCommand.java`；原储值实现位于`pay.storedvalue.service.impl`。下一步沿真实路径扩展，不照抄旧设计中的省略路径创建同名类。

本步未运行Java测试，因为未改运行时代码；用文档、源锚点和迁移解析检查证明本核验范围。没有以Mock证明支付、库存、税点或打印可用。

## §2 核验结论与冻结项

### 2.1 库表归属及共同字段

以下7张表在扫描到的核心/餐饮迁移中均未找到CREATE定义：

| 新表 | 归属 | 后续任务 |
|---|---|---|
| ord_pricing_template、ord_pricing_template_revision | shop-core.trade，核心Flyway目录 | CKO-08 |
| ord_store_pricing_config、ord_store_pricing_revision | 同上 | CKO-08 |
| ord_price_quote、ord_price_quote_line、ord_price_quote_allocation | 同上 | CKO-10 |

完整字段沿[存储方案](./TDD-统一试算引擎-存储与Java实施方案.md)及[税费增量](./TDD-统一试算引擎-初始规则与结账税费.md)。本步收口以下字段类型，覆盖早期候选类型：

- 时间采用现有`DATETIME(3)`/LocalDateTime持久化口径，替代候选`DATETIME(6)`；业务税点/失效时间先经可信时间转换，不能取端侧时钟。
- 当前经营币种列`CHAR(3)`，沿现有fiat合同；不能因Java字符串可以更长就任意接受币种。
- 业务No默认`VARCHAR(32)`；原`quote_ref/core_reference`等既有64长度保留，不为新文档缩短已用字段。
- Money为BIGINT，hash为CHAR(64)，JSON沿现有MySQL JSON；BaseEntity的tenant/version/deleted/审计字段及索引命名沿原栈。
- 核心计价数量候选DECIMAL(20,6)不代表原StockPort/UseItem的int已支持小数。不能将0.5份或配方重量截断为0/1，需后续真实计量协议。
- 本次扫描核心最大迁移号531、餐饮最大7，**不预占下一号**；实施前重新分配。冻结迁移不改checksum。

### 2.2 既有表复用与差异

机器清单已保存7张fnb表原列/类型/索引：settlement、settlement_item、charge_intent、cash_shift、cash_entry、return、return_item。重点差异如下。

| 表 | 已有字段/唯一键 | 必须解决的差异 |
|---|---|---|
| fnb_settlement | quote_ref、scope_hash、due_minor、core_cashier_ref、expires_at；唯一settlement_no | quote绑定完整版本，due_minor定义为本范围核心customerDue；有对价抵付时不能直接拿invoiceGross代入 |
| fnb_settlement_item | payment_root_no、store_order_no、allocation_ref、core_version、scope_key；唯一settlement_no＋scope_key | 只能防止同一结账重复插同scope，不能阻止两个结账争同份额；核心提供原范围预留/已付/UNKNOWN检测 |
| fnb_charge_intent | request_key/hash/snapshot、core_reference、cash_shift_no；唯一settlement_no＋request_key | 跨结账防重仍靠核心稳定源键；request_snapshot不能替代查询可校验的核心范围/原操作身份 |
| fnb_cash_shift/entry | 既有现金当班/收支表定义 | PAY确认引用与现金entry同一稳定业务结果；不是food直接改PAID或增余额 |
| fnb_return/item | 既有退餐/行引用 | 原成交/已退/在途/资金/税分摊按原退款协议，不能厨房退菜后直接标财务成功 |

本步不另造通用金融意图表，也不假定现有JSON字段足以实现原子范围锁。CKO-14/15在接核心确定的范围/意图合同后，若需增加显式可查绑定列或唯一约束，追加增量迁移并更新canonical schema；不得修改V1。

### 2.3 现有Port动作和恢复协议

| 能力 | 当前精确方法 | 输入身份/结果/限制 |
|---|---|---|
| 原券 | `allocate(userNo,userCouponNo,List<MerchantAmount>) → Allocation` | 门店/主体分摊，不是任意源行/数量分摊；markUsed保存原Allocation，release按orderNo |
| 原权益 | `quote(context,QuoteCommand) → QuoteView`；`prepare(context,UseCommand) → HoldView`；`commit(context,UseDecision) → UseReceipt`；`release(context,CancelUse)`；`query(context,entityNo,operationNo)` | 原SourceRef及决策/授权；NOT_FOUND/HELD/COMMITTED/RELEASED/PROCESSING/EXCEPTION等真实枚举不能粗略映射为成功失败 |
| 储值报价 | `StoredValueUseQuotePort.quote(EntitlementContext,SourceRef) → Quote` | amountMinor/applicableMinor/redeemable及revision/hash/expiry；redeemable要求amount==applicable，后续核销沿原权益/储值协议 |
| 积分 | `PointsRedeemPort.quote(RedeemQuery) → RedeemQuote`；`hold(HoldCommand) → HoldOutcome`；`release(ReleaseCommand) → HoldOutcome` | 已有AcceptedPrice及原orderNo/market/user/targets；最终处理还依赖PointsFactPort生命周期及原源日志，不补造一个空commit/query |
| 普通支付 | `SettlePort.initPayment(PaymentOpen) → PayInitResult`；`paymentByOutTradeNo(String) → PaymentReceipt`；`settlePayment(PaymentSettled) → String` | 返回真实outTradeNo及凭证，PaymentOpen显式currency/门店route；超时查原号，不换键 |
| 普通退款 | `requestRefund(RefundRequest) → RefundDispatchResult`；`queryRefundExecution(RefundRequest) → RefundDispatchResult` | 稳定退款指令及原资金证据；FAILED/UNKNOWN默认实现不能算已接线 |
| 库存 | `StockPort.lock(String,List<SkuQty>) → List<String>`；`release(String)`；`confirm(String,int)`；`ReservationService.reserve(ownerId,externalRef,List<Line>,ttlSeconds) → String` | int数量、生命周期版本、取消墓碑；原引用完整查询及跨进程恢复合同须CKO-13补齐 |

将这些原签名作为复用基线。未来独立HTTP直接使用原DTO的可序列化typed等价合同并明确版本；不能将反射调用或一条任意method/body接口作为新内部API。

**未来原引用库存查询的最小需求合同：** provider拥有`queryReservation(ownerId,externalRef) → ReservationOutcome`，返回NOT_FOUND/HELD/ALLOCATED/ISSUED/RELEASED/CANCELLED/UNKNOWN的明确映射、originalRequestHash、原计量行/已履约量、revision及权威引用。它是CKO-13目标语义，不是当前已有方法或已获库存状态机签名；实施13前按库存真实枚举收口并做provider/consumer合同测试。

**未来普通支付远程合同：** open/init、按原outTradeNo查凭证、原退款执行/查询均沿SettlePort动作；关单动作必须先核验当前PaymentLedgerService/网关真实方法与“已成功不可关”规则，再在CKO-12冻结typed请求/响应。不能在本步给未证明的provider写空实现并标完成。

### 2.4 API与类归属

保留原API资源，机器清单已登记23个结账相关原编号，不使用先前候选cart quote路由另建等义接口：

| API | 路由/目标类 | 本步状态 |
|---|---|---|
| 041/131 | `POST /biz|mp/x/food/stores/{storeNo}/quotes`；BizDiningQuoteController/MpDiningQuoteController | 目标API，不是已实现端点；11实现 |
| 042/132 | 原B/C门店batches；DiningOrderService.place/customerPlace | 目标下单；14实现 |
| 056～060 | 原B settlements创建/查询/pay/cancel/finalize；DiningCheckoutService | 目标结账；15实现 |
| 061 | 原settlements/{no}/adjustments | 目标调整；18实现 |
| 062～064 | 原returns创建/执行/查询 | 目标售后；23实现 |
| 065～067 | 原共享`/biz/print/jobs`及查询/reprint | 目标共享能力，不放food复制；24先核owner |
| 068～071 | 原现金当班/entry/count/handover | 目标编排；17实现 |
| 134/135/140 | 原C结账查询/pay、check创建settlement | C会话范围；15实现 |

Controller沿food-web对应biz/mp包，food-api接口及DTO、food-core实现；独立food-server内部协议不复制公网B/C鉴权。公共计算/报价/确认留在common.pricing/trade，类归属按已确认方案。

报价门面确定为`DiningPricingService`负责餐饮范围与事实适配；原设计`DiningOrderService.quote/customerQuote`若保留，只委托这一门面，不再分别计算。核心唯一`OrderPricingPort`对行业提供quote/getQuote/revalidate；确认由原获准业务意图入口调用`OrderPricingService#confirm`，不公开任意扣款接口。

### 2.5 权限与错误码登记基线

已有`biz:order:view`只用于获准订单读取；`biz:verify`不是发布价格/税率的授权。Ops已有finance费率/结算码也不直接等同销售VAT配置权限。

以下是**冻结的拟新增词表，尚未写入Java枚举/角色授权**；写Controller时同任务登记与验证：

| 归属 | 拟新增码 | 用途 |
|---|---|---|
| BizPerms | biz:pricing:config:read/update/publish（分别三条） | 门店价格规则只读、改草稿、发布分权 |
| BizPerms | biz:order:adjust、biz:order:waive | 人工打折扣减/纠错与末段抹零分别授权，还须金额/原因/审批 |
| Perms | pricing:template:read/update/publish（分别三条） | 平台行业模板管理 |
| Perms | pricing:tax:publish | 平台受控地区税档发布 |

不自动把新权限发给全体员工或运营角色；真实owner/角色矩阵在09/18登记，并验证原角色权限不被扩大。C端无上述权限入口。

拟新增业务错误名完整列在JSON：PRICING_CONFIG_INVALID、PRICING_RULE_CONFLICT、PRICING_OWNER_UNSUPPORTED、PRICING_QUOTE_EXPIRED、PRICING_SCOPE_CHANGED、PRICING_PLAN_SEARCH_LIMIT、PRICING_ADJUSTMENT_DENIED及4个税配置/分类/表达错误。数字码在实现时按最新枚举分配，不能先占号撞并行开发；新增错误同时登记三份`shop-app/src/main/resources/i18n/messages*.properties`。已有CONFLICT用于一般CAS竞争、PRICE_CHANGED用于其既有语义，不用通用冲突文案掩盖具体原因。

### 2.6 报价请求、输出与身份冻结

跨域合同归`ai.neargo.shop.spi.trade`，不引用core/food Entity。当前暂无这些运行时类，以下作为02/11任务实施边界：

```java
record PricingSourceRef(
        String sourceSystem, String sourceType, String sourceNo,
        long sourceVersion, String scopeNo, Long scopeVersion) {}

record QuotePricingReq(
        PricingSourceRef sourceRef, BenefitSelection selection,
        FundingIntent fundingIntent, List<AdjustmentIntent> adjustments,
        CashTenderIntent cashTender) {}
```

`BenefitSelection/FundingIntent/AdjustmentIntent/CashTenderIntent`为此报价SPI的请求record；纯内核使用其自身有类型事实/计划，不让common.pricing反向依赖SPI协议。外部DTO不得把common.pricing内部可执行规则/函数暴露为JSON，普通值对象仅在职责合适时复用。

- system/type在注册源白名单内，业务No按原32长度，version非负；scopeNo/version成对出现，源owner校验其真实归属。cart LIVE或冻结未付phase由服务端判定，不信客户端自报。
- 外部业务身份取登录会话/内部认证与原来源事实；不接受body自报tenant/entity/消费者身份替换真实归属。
- selection包含mode、couponNos、activityRefs，限制数量及唯一性；客户端不传权威折扣值或税率。
- adjustments必须有kind、金额或应付比例、reasonCode及必要approvalRef，声明amountBasis；C端禁止员工调整。
- 输出固定quoteNo、源/范围版本、config revision/hash、economicsHash、有效期、used/unused/violations、net/tax/invoiceGross/customerDue及资金计划；确认时保留完整商业差异，不只比较总额。
- 新报价SourceRef与原权益`spi.entitlement.SourceRef`语义不同，**不替换原资产来源类型**。适配到真实权益来源必须由owner注册并核验稳定身份/修订/授权，不能为了复用字段把FOOD_CART伪装成CORE/SERVICE_USE。

### 2.7 CKO-02可以立即实施的纯内核合同

```java
// 位于ai.neargo.shop.common.pricing；不持有Spring、Mapper或跨域Port。
record MoneyMinor(String currency, long minor) {
    MoneyMinor add(MoneyMinor other);
    MoneyMinor subtract(MoneyMinor other);
}
```

这是签名说明，不是可编译record定义。MoneyMinor允许有符号中间差额，使用Math.addExact/subtractExact且币种一致；价格/支付等外部金额非负由对应事实边界检查，不在通用值对象禁止所有负数。币种三位大写，交易接受范围仍由原owner核验。对外JSON金额绝对值不得超过9,007,199,254,740,991及更严格业务上限；checked long运算和API上限是两道独立检查。

PricingFacts/PricingUnit只承载可信不可变事实：稳定源行/组件/数量份额/税发票行、原商品价及修订、独立资格、原owner批准的优惠/资金/税证据。数量以BigDecimal处理，标准计价精度至多6位，超出拒绝不截断；原owner计量精度不兼容则阻断该来源。

同输入及版本金额/分摊/经济hash一致：规范JSON属性顺序、集合排序、数量表示和算法版本；不将随机quoteNo、创建时间放进economicsHash。业务源身份和实际优惠/税/份额改变必须反映在相应事实/经济证据中。

CKO-02测试冻结为`MoneyMinorTest#rejectOverflowAndCurrencyMismatch`以及数量/JSON边界测试；同币种100+200=300、MAX_VALUE+1失败、CNY与AED相加失败、小数份额不丢精度、负差额可表达但不得变成负应收。此切片不依赖尚缺的独立资金合同。

## §3 判定与执行裁决

- Ruling：本次“执行”针对紧邻提出的CKO-01，不把其他30项全部标为进行中；本步产出核验基线，下一代码切片是02。
- Ruling：当前为已授权餐饮分支上的只读源码核验及文档更新，保留当前工作区设计和他人未提交改动；不在本步创建会遗漏未提交设计的第二份代码实现。进入运行时代码前按计划隔离工作区并带入冻结设计。
- Ruling：SUPPORTED只描述明确源码能力，环境验证另列；默认返回null/FAILED/UNKNOWN不算业务实现。
- Ruling：权限/错误词表在设计中确定，在对应运行时任务注册，不为凑齐合同在本步授予权限或修改原支付/库存接口。
- Ruling：积分真实协议是quote/hold/release＋生命周期事实，不按通用资金模板强加另一份commit/query。

## L4 验证与后续门禁

## §4 保留的外部/后续门禁

CKO-01完成的是“知道原合同在哪里、能做什么、缺什么、由哪个任务补”，不是一次完成所有外部合同。CKO-12/13必须先收口真实provider typed签名/查询状态映射，再写适配器；21/22必须验证来源、授权、计量、资金及税务分类。新规则允许不代表原owner已经支持。

本次未验证实际数据库迁移历史、实例版本、通道沙箱、打印硬件或资金/库存闭环。它们不阻塞02纯内核开发，但阻塞对应营业能力开关。

## §5 核验检查及证据

| AC | 检查 | 本步证据 |
|---|---|---|
| F01 | 解析7张既有fnb表列/索引；搜索7个ord目标表CREATE | JSON schema及原V1文件hash，核心/餐饮迁移目录范围明确 |
| F02 | 13项能力引用文件存在、锚点/行号/hash一致 | JSON capabilities.evidence；现有动作与缺口分开 |
| F03 | 23条API与原索引一致；9项拟新增权限/11项错误名未伪称已登记 | JSON apis/permissions/errors；登记时再验冲突 |
| F04 | 纯内核无外部写接口依赖；后续任务门禁可追踪 | §2.7与gates；31任务仅01更新状态 |

检查工具只读取源码/迁移/JSON，不运行Java，也不能替代未来测试。验证结果在执行后回填§6；若他人并行修改证据源码，应重新核验hash及受影响结论，而不是悄悄更新hash掩盖语义变化。

## §6 执行记录

2026-10-22：完成CKO-01源码核验、能力分类、字段类型收口、API/权限/错误词表及下一代码切片合同。新增本核验文档与机器基线，更新完整计划和原存储方案。尚未建表、修改Java、发布配置或连接真实收退款/库存服务。


实际检查输出摘要：

```text
PASS: 13 capabilities; 25 current source hash/line/anchor checks
PASS: 7 existing table schemas and indexes; 7 target ord CREATE definitions absent
PASS: 23 canonical APIs; 9 proposed permissions; 11 proposed errors
PASS: CKO-01 4 checked steps only; 02-31 unchecked; local links and code fences
PASS: previous tax seed 20 rules / 3 templates / 2 tax profiles / 8 amount vectors
```

其中拟新增权限/错误仅检查“尚未登记、不冒充已有”；并未执行授权变更。后续代码任务的测试不能由这些设计检查替代。
