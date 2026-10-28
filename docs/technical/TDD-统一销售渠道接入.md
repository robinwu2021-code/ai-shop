# TDD 统一销售渠道接入

> 状态：开发中，2026-10-26；C0授权、C1目录引擎与资源映射已集成部署，运行提交b076524b6，实际17张业务表/29条公开路由。原FOOD餐牌资源核验、S2A/S2B1价格依据与共享目录v2已编码且相关专项通过；完整餐牌价格组合/来源/事件桥接与C2–C5尚未完成。历史批次记录保留当时状态，以本文末尾当前交付记录为准。需求：[PRD](../requirements/PRD-统一销售渠道接入.md)；决策：[ADR042](./ADR/ADR-042-统一销售渠道接入与行业来源隔离.md)。

## L1 入口与结论

零售、餐饮共用外部销售渠道平台，兼容即时配送、快递电商和生活服务核销。原商品、价格、订单、支付、库存、打印、物流和权益继续是权威。当前集成、将来独立部署，前端页面暂不处理。

| 阅读入口 | 内容 |
|---|---|
| [数据库设计](./design/统一销售渠道接入-数据库设计.md) | 31张候选表、公共字段、唯一键、历史及归属 |
| [API清单](./design/统一销售渠道接入-API清单.md) | 70个公开/回调路由、输入输出、权限及14组SPI/typed网关 |
| [代码清单](./design/统一销售渠道接入-代码清单.md) | 模块、完整包名、Controller/Service/Mapper、DTO及计划测试 |
| [结构化真源](./design/schema/commerce-integration-design.json) | 同一份表/API/Service/AC引用，不与实现清单混用 |

上三份清单及表族SVG由scripts/gen-commerce-integration-design.mjs生成。数量是设计候选，不代表本次要求一次性创建31表；实际按闭环增量实现，资金、券和真实渠道分别投放。

## §0 对账一 需求到设计

| AC | 需求 | 设计落点 |
|---|---|---|
| AC01 | 渠道授权、平台配置、账号和主体/门店范围统一且不泄密 | COM-001, COM-002, COM-003, COM-004, COM-005, COM-006, COM-007, COM-008, COM-066, COM-067, COM-068, COM-070；详见三份清单 |
| AC02 | 稳定渠道门店身份在换授权后仍保持订单去重，配置修订可追溯 | COM-009, COM-010, COM-011, COM-012, COM-013, COM-014；详见三份清单 |
| AC03 | 餐饮和零售生成有原价依据的完整目录，单一远端范围只一个发布者 | COM-015, COM-016, COM-017, COM-018, COM-021；详见三份清单 |
| AC04 | 自动/手动共用任务，旧ACK和部分失败不伪装最新全量确认 | COM-019, COM-020, COM-022, COM-023, COM-024, COM-025, COM-026；详见三份清单 |
| AC05 | 历史映射及原行/组件身份保留，名称和条码不作猜测映射 | COM-027, COM-028, COM-029, COM-030；详见三份清单 |
| AC06 | 多渠道可售量取原库存承诺，不能通过API自报余额 | COM-031, COM-032, COM-033；详见三份清单 |
| AC07 | 回调先验签落库ACK，重复/冲突/乱序和UNKNOWN可追溯且原键恢复 | COM-034, COM-035, COM-036, COM-037, COM-038, COM-047, COM-048, COM-049, COM-069；详见三份清单 |
| AC08 | 外部主子单/精确数量/金额映射原核心订单，不按当前目录改价 | COM-039, COM-040, COM-041, COM-042, COM-045；详见三份清单 |
| AC09 | 行业准备、平台接单、资金履约准入、受理/一次派工分开确认 | COM-043, COM-044, COM-046；详见三份清单 |
| AC10 | 即时出餐、零售拣货、多包裹发货分别回传已提交事实 | COM-050, COM-051；详见三份清单 |
| AC11 | 取消/退餐/退货/退款分别沿原合同，不伪回补或重复退款 | COM-052, COM-053, COM-054；详见三份清单 |
| AC12 | 券核销/撤销由原消费和权益授权，重复或结果未知不再消费 | COM-055, COM-056, COM-057, COM-058；详见三份清单 |
| AC13 | 账单/到账/经营日期和资金证据分离，缺失不填伪0 | COM-059, COM-060, COM-061；详见三份清单 |
| AC14 | 技术统计分母、时点和去重规则明确，不冒充成交/净利 | COM-062, COM-063, COM-064, COM-065；详见三份清单 |
| AC15 | 集成和独立部署无业务实现环依赖，关闭不影响零售餐饮 | 五模块装配、唯一SPI和架构检查；详见三份清单 |

全部表、API及SPI映射到上述需求；无孤立业务能力。AC15是部署/架构要求，不额外造公开端点。

## §1 现状与影响面

已检查food五模块、餐牌渠道绑定、MenuPublicationSnapshot、DiningOrderServiceImpl、DiningOrderAccepted和DiningKitchenAcceptedConsumer，以及原BaseEntity、PageData和平台Java规范。现有food-channel负责内部跨服务适配，不能改成外卖平台SDK集合。

原餐牌快照不授予价格/库存权威；当前商品报价提供方固定CN/CNY的限制应先在原owner扩展，不能由新模块绕过。现有acceptUnpaid要求POSTPAY及核心生命周期证明，外卖已代收单必须有专用受理/资金履约合同，不放宽原判断。

现有fnb_menu_channel_binding及外部映射须兼容；不修改历史Flyway或现有生产配置。新profile/permission/ErrorCode需实施时登记原平台目录及三语，不在本设计伪称已存在。真实API能力以地区获准协议核验，不照搬商超foodpanda Partner API当台湾餐饮POS。

## §2 方案与实施边界

### L2 总体结构

![表族与衔接](./design/diagrams/commerce-integration-table-groups.svg)

同一远端范围的目录由一个scope发布；行业只提供来源。首版source_owner一致：可聚合多个餐饮餐牌或多个零售选品，但混合行业目录也关闭，避免能下单却不能履约；以后需统一跨行业导入合同后开放。平台适配器不能操作本地厨房/库存，行业适配器不能接触平台SDK。跨域调用以持久化原动作查回，不持有事务跨网络。

### L3 三条闭环

**目录发布**：来源修订事件→合并尚未发送意图→capture完整快照及原价/税依据→预览→提交run+operation+Outbox→网关发送→回调/查询结果→generation一致时推进确认。已发快照不被新修改覆盖；全量平台只补失败项可能造成删除，恢复仍按完整替换范围。图片和菜单结果独立；PARTIAL时最后确认版本不是当前远端全量证明。

**订单导入**：原始字节验签→event/receipt持久化→协议ACK→必要时查原单→来源/金额/映射核验→原业务prepareImport→平台接单/核验既有接受→原业务confirmAcceptance→原受理Outbox→厨房/拣货/打印。即时单、待付款快递单、券核销按不同准入策略；平台已接受但本地失败，持续原键恢复并优先告警，不能只标本地失败后丢弃。

**履约及售后回传**：原owner提交事实→原Outbox→按订单关联建立operation→网关调用→结果/回调/查回。仅真实已完成数量可回传；备餐不是签收，退款申请不是退款成功，退餐不是自动库存回补。来源反射事件不重复触发同一出站动作，防止回调回传循环。

### L3 S1餐牌原来源核验实施合同（2026-10-26）

本切片对应AC03/AC05/AC15，沿ConfigRevisionService新增verifyMenuResource(Query)，内部POST `/internal/food/config-revisions/verify-menu-resource`。不新增表或公开B/C/云端端点，复用原FOOD服务令牌和私有持久化。旧提供方默认NOT_FOUND，禁止静默回退到工作草稿。

- Query：entityNo、storeNo、menuNo、revisionNo、kind（MENU/CATEGORY/OFFER/SKU/OPTION_GROUP/OPTION）、resourceRef、salesOfferRef、optionGroupRef、mode（CURRENT/HISTORICAL）。SKU/配料组/配料必须指定餐牌条目salesOfferRef；OPTION另需optionGroupRef，证明该选项确属本次餐牌条目采用的原组，而不是只证明全局编号存在。OFFER的salesOfferRef必须等于resourceRef。MENU/CATEGORY不接受销售条目或选项组条件。
- Proof：schemaVersion=1、原Query、ConfigRevisionContentVO publication内容、canonical Resource（kind/ref/salesOfferRef/optionGroupRef）、verifiedAt、validUntil、receiptNo。自包含原文用于远程客户端重验原scope/hash及投影，回执摘要只是完整性校验，可信来源仍是FOOD令牌通道；不作为价格、库存、付款、接单或签名凭据。
- ConfigRevisionServiceImpl在原REPEATABLE_READ只读事务内查指定修订、门店scope、有效状态与原内容hash。CURRENT同时比较当前生效修订；HISTORICAL允许已被后续修订替代的原有效内容，但拒绝尚未生效或取消的修订。保守地不授予已取消来源核验资格，历史订单仍沿原订单不可变凭据处理。
- 证明默认30秒有效，CURRENT期限不能越过已知下一生效修订；校验和投影在同一次只读快照内完成。与之后发布/编辑的并发变化仍须下游在写入/发送前重验，不把30秒TTL当锁。
- MenuSourcePolicy只投影已冻结原分类、启用销售条目、EXPLICIT/ALL下原SKU归属及itemOptions冻结组；不读可编辑继承关系，REPLACE空组必须保持空。OPTION核验原组依赖中的optionItemNo，不用modifierNo/specValueNo冒充选项身份。

| 文件/类 | 方法与责任 | 验证 |
|---|---|---|
| food-api dto.MenuSourceContracts | Query/Kind/Mode/Resource/Proof，严格JSON和原身份 | HTTP非法字段、数值冒充字符串及枚举拒绝 |
| food-api policy.MenuSourcePolicy | validateQuery/project/receipt/validateProof，原内容与远程回执重验 | 非原条目SKU、组/选项父身份、hash/时间/回执不匹配拒绝 |
| food-api service.ConfigRevisionService与spi.ConfigRevisionInternalApi | 默认关闭方法和typed内部合同 | 旧提供方不支持不通过 |
| food-core service.impl.ConfigRevisionServiceImpl | verifyMenuResource，一致性只读事务/当前与历史边界/期限 | MenuSourceVerificationTest |
| food-web foodclient.RemoteConfigRevisionService | typed调用一次、重验原scope/document/query/resource/期限，不将超时变成功 | RemoteMenuSourceVerificationTest |
| food-server api.InternalConfigRevisionController | 原令牌鉴权下delegate，不操作Mapper | InternalMenuSourceApiTest |

执行顺序：合同及失败专项→原owner实现和远程/内部接线及全部测试源码→集成宿主/独立server编译→受影响专项→一次消融和独立复核→相关修复复测/文档对账/白名单提交。先交付原FOOD能力，本切片不注册commerce完整目录提供方；S2价格/税与S3事件接线继续待办。

### L3 S2A通用目录基准价与税来源合同（2026-10-26）

依据AC03/AC15和S2执行下一项可独立验收的原owner能力；不修改既有CN/CNY零售/餐饮结账链路。旧DishOfferQueryPort继续提供原本地含活动报价；新CatalogPriceSourcePort只提供CATALOG_BASE来源，基准价优先门店价、无有效门店价回退原主体SKU价，不自动叠加本地活动/券。餐饮餐牌覆盖价/配料/套餐组合为S2B，事件桥接为S3；不把来源凭据等同于完整渠道Document已可发布。

- Request仅entityNo/storeNo/skuNos（1–100、去重、原编号32字符），市场与币种不接受客户端传值。原StoreDirectoryPort核对活动门店及主体，MerchantQueryPort.marketOf读取真实主体市场，MarketPort.find核对启用市场、币种和小数位。无配置不回退CN/CNY，不兑换汇率。
- Facts含schemaVersion=1、purpose=CATALOG_BASE、Request、Market、TaxFact、完整Price列表、verifiedAt/validUntil（30秒）和receiptNo。TaxFact投影原MerchantTaxSnapshot，保留平台默认/商家自定、受管版本、源版本、税率bp、INCLUSIVE/EXCLUSIVE及内容hash。没有有效税源不签发事实；不在本接口计算总税或审批税档。
- Price保留原goodsNo/skuNo、KNOWN/UNAVAILABLE、最小单位价格、ENTITY_SKU/STORE_PRICE、goods/sku/门店价/门店上架版本及sourceRevision摘要。未知价为null，明确0价可为KNOWN；不返回库存承诺，不把未知库存变成0。sourceRevision是绑定真实行版本和原价的内容摘要，不伪造新增数据库版本。
- 原商品读取沿entity+市场选SKU，查原商品归属/审核/总上架及门店上架覆盖层，杜绝同SKU不同主体、不同市场及不同门店串价；存在门店上架管理但本店无行则不可售。商品无行/不可售或任一候选缺失直接NOT_FOUND，完整列表不以缺行补0。空门店价回退主体价，原价负数/超JSON安全范围或缺真实行版本拒绝。
- 市场/主体/门店/税的跨域事实在本域只读事务前后各读一次并比较；原商品行读取为REPEATABLE_READ；不持有本域事务调用远端端口。外部事实有变化则CONFLICT，不签发混合来源。原scope/header不作为支付资格。
- 新内部POST `/internal/product/catalog-price-sources/capture` 沿原服务令牌/status约定，只接受原Request，不新增公开B/C/云端管理路由。food-channel提供typed远程适配：一次调用、重验原请求/完整SKU集/金额类型/市场税hash/版本摘要/期限/receipt；404保留NOT_FOUND（旧接口不存在亦不得兜底），501/503明确能力未支持，不能兜底旧CN报价。

| 文件/类 | 方法/责任 | 验收 |
|---|---|---|
| shop-base spi.product.CatalogPriceSourcePort | Request/Market/TaxFact/Versions/Price/Facts；capture | 严格输入输出，旧能力缺席拒绝 |
| shop-base spi.product.CatalogPriceSourceFacts | validateRequest/validate/revision/receipt/taxSnapshot及严格数值反序列化 | 原scope/hash/价格/税/市场/期限篡改拒绝 |
| shop-base spi.product.CatalogPriceSourceInternalApi | CAPTURE_PATH与typed capture(Request) | 令牌、严格JSON、原状态 |
| shop-core product.port.CatalogPriceSourcePortImpl | capture，复用Goods/Sku/StorePrice/StoreGoods Mapper及原owner端口 | AE/CNY跨市场、分店价/上架、缺税、并发事实变化、真实0/未知价 |
| shop-app portal.internal.InternalCatalogPriceSourceController | 委派原port，沿InternalServiceResponses | InternalCatalogPriceSourceApiTest |
| food-channel port.CoreCatalogPriceSourcePortImpl及config.FoodOfferClientConfiguration | 懒加载typed客户端及新原能力Bean，不替换旧DishOffer Bean | CoreCatalogPriceSourcePortTest |

不新增表/Mapper/权限码/i18n/运行配置，不修改已发布迁移；共享能力仍在原零售及核心模块。内部组合入口沿现有InternalDishOfferEndpoint放宿主portal.internal，避免Servlet请求/异常鉴权类型进入商品领域；独立商品服务将来需重新装配该入口，原能力Port保持可复用。执行：失败专项→完成生产与测试源码→集成宿主、food-server、commerce-server统一编译→集中相关专项→一次消融和独立复核→仅相关修复复测→文档对账/白名单本地提交。

### L3 S2B1原餐牌价格投影实施计划（2026-10-26）

继续AC03/AC05/AC15：先交付可远程核验的原FOOD价格投影，再在S2B2装配完整ChannelCatalogSourcePort.Document。当前commerce没有真实连接授权提供方；价格投影不能越过该门禁。复用S1完整发布凭据及S2A基准价/税，不引入commerce-api到FOOD原领域，也不把原结账试算替换为渠道目录计算。

- 输入只有entityNo/storeNo/menuNo/revisionNo，严格原编号且拒绝未知JSON字段；服务自己读取CURRENT菜单证明、收集实际MAIN/配料/套餐差价所需原SKU，再调用共享CatalogPriceSourcePort。单菜单所需SKU最多100；超限明确拒绝，不拆成无一致性保证的多批价格，也不返回部分目录。配方库存原料不作为售卖价候选。
- 同商品在不同menuItem下独立定价；EXPLICIT/ALL沿已发布原SKU集合；REPLACE空选项仍为空。MODIFIER读取冻结salesSkuNo报价，SPEC_VALUE为明确免费规格；组合配料不另求和原料价格。套餐INCLUDED=0，ADD_SKU=选中SKU价，DIFF_BASE=max(选中SKU价−基准SKU价,0)，均保留unitsPerPick原精确数量，不能提前取整改变原下单公式。主SKU、配料和套餐SKU可用本item覆盖价，覆盖币种须匹配可信市场。原覆盖定义/结账仍保持CNY限制，不将CNY换算为AED/TWD；无覆盖价支持共享原市场币种。无货不造0，定价不授予库存资格。
- MenuCatalogPriceContracts保留原Proof、完整product Facts、扁平PriceLine（item、主SKU分支、group/choice、kind、sku、unitPriceMinor、unitsPerPick、sourceRevision）及短期限/摘要。每一行绑定原餐牌hash、item及依赖身份、原价修订和税事实。传输接收端重算完整投影，防漏行、多行、改价、串条目及伪凭据，不只检查receipt摘要。依赖原SKU归属必须与原价事实一致。
- MenuCatalogPriceServiceImpl不持有事务调用其他owner，先后核验CURRENT菜单，原hash/修订变化则CONFLICT；有效期取最新菜单与基准价凭据最短期限。仍是时点事实，无法消除ABA或读取后变化；最终下单沿原能力重算，不锁价/锁库存。
- 新增food-api dto.MenuCatalogPriceContracts、policy.MenuCatalogPricePolicy、service.MenuCatalogPriceService、spi.MenuCatalogPriceInternalApi；food-core service.impl.MenuCatalogPriceServiceImpl；food-web foodclient.RemoteMenuCatalogPriceService并沿FoodClientConfiguration装配；food-server api.InternalMenuCatalogPriceController沿原令牌链。无新公开路由、表/Mapper/迁移、权限或支付配置。
- 执行：写拒绝默认能力的失败专项→完成实现及全部测试源码→集成与独立server统一编译→集中相关专项→一次守卫消融和独立复核→仅相关修复复测→三处对账、生成清单、精确白名单本地提交。预期覆盖重复SKU不同条目、覆盖/零/未知、原SKU归属、配料/免费规格、套餐三模式/差价和小数用量、缺基准价/币种冲突、原证明过期/变化、输出篡改及令牌/status。既有SSE架构问题保持单独待办，不放宽守卫。

### L3 S2B2A无损目录合同实施计划（2026-10-26）

原目录v1仅整数选项/套餐数量、统一offer级选项和周时段，无法无损承接原FOOD的小数用量、按规格选项、规则依赖和日期例外。先补共享合同，再交付S2B2B原FOOD适配及授权/媒体来源，不在此处对原餐牌取整、删除例外或伪造库存。

- ChannelCatalogSourcePort.Document追加可空schemaVersion和details，原八参数构造/无字段旧JSON仍为v1；空追加字段不输出，v1哈希字节算法保持原实现。v2必须schemaVersion=2、旧sections/offers/schedules明确空列表，details为唯一结构真源；未知版本、混合两份结构拒绝。
- 新spi.commerce.ChannelCatalogDetails：原Section/Image复用；Offer下每个Variant独立价格/库存/选项/套餐，QuantityRange以严格十进制字符串表达min/max/default；选项保留SPEC_VALUE/MODIFIER及原source/sku，mutexSets/requires不丢；套餐保留INCLUDED/ADD_SKU/DIFF_BASE、原SKU和精确unitsPerPick。库存必须显式UNKNOWN或可信POLICY引用/回执，UNKNOWN不填伪数量。此处不签发库存、税或媒体权威。
- SourceSchedule保留timezone、有效日期、周规则的endDayOffset和日期OPEN/CLOSED例外；空周规则明确无常规开放窗口，不变成全天营业。按原餐饮本地起始日语义校验跨午夜及周边界重叠、例外范围/唯一性，保持24小时窗口可表达。
- 新CatalogDetailsPolicy校验完整v2范围、引用唯一/依赖无环、可行的数量边界、价格/币种/税/库存、图片HTTPS和全部源时段；单纯刷新明细价格/库存/媒体回执不改变稳定业务hash，真实价格修订/规则/例外变更必须改变hash。v1继续沿原算法，不能重算历史摘要。
- CatalogDocumentPolicy按版本分派freeze/hash，新增version/itemCount。CatalogPublicationServiceImpl将真实schemaVersion/itemCount写原cim_catalog_snapshot已有字段，CatalogPersistence读取时核对row/payload版本。旧表和迁移不变。
- ChannelCatalogGateway新增默认仅支持v1的supportsDocumentSchema；SyncRunServiceImpl提交及事务内复核，CommerceRecoveryServiceImpl首次派发/原UNKNOWN查询前重新校验，v2不降级交旧gateway。提交不支持则无run；排队后能力收回则无远端操作并记录SCHEMA_UNSUPPORTED；原已提交/未知动作保留阻断，恢复查询不支持时不重发。
- 验收：先写v2内容变更hash/坏数量拒绝的失败专项→全量本批源码与测试→集成宿主和独立server编译→相关目录/映射/调度专项→一次版本门禁消融和独立复核→仅相关修复复测→文档/生成清单对账与白名单本地提交。覆盖v1旧JSON/旧hash、v2精确数量/按规格选项/依赖规则、跨午夜/全天/日期例外、真0与未知库存、市场及税凭据、数量/时段篡改、旧网关拒绝和支持收回。当前没有真实渠道或新来源提供方启用；完整S2B2仍待B阶段。

### L3 S2B2B1原连接与目录授权事实实施计划（2026-10-26）

S2B2B先补真实连接/授权只读合同（AC01/AC02/AC03/AC15），再装配原FOOD目录、营业日历和媒体；现有CIM原数据和身份核验Port复用，不将调用方自报连接当成授权依据。

- Query只有entityNo/storeNo/connectionNo/revisionNo四个原编号，严格类型且拒绝未知字段。ChannelCatalogAuthorityService.capture输出purpose=CATALOG_AUTHORITY/schemaVersion=1及原Provider/Profile/Account/Connection/Grant版本事实、当前身份Proof投影、verifiedAt/validUntil/receiptNo，不输出credentialRef、token、原授权配置或私有查询行。
- 以原账号/连接/修订精确scope读取，要求ACTIVE连接/账号/平台、VERIFIED档案、LOCAL目录权威、原CATALOG授权且未过期、原连接指针一致。输出真实渠道profile市场，不调用网络获取商品/税、不替代最终目录捕获。保留原账户标识及外部引用，不截断或伪造餐牌绑定编号。
- 调用原ChannelConnectionIdentityPort.verify重新核验当前远端身份；不拿建连接时旧Proof冒充新回执，不依赖发布Gateway已装配。原Proof必须VERIFIED、精确绑定原Identity/行版本/期限；缺能力/UNKNOWN/原范围不符一律关闭。只读RR快照→事务外身份查询→RR重读比对，拒绝调用方已有事务，30秒期限并取原授权/身份最短期限。私有state另比对凭据引用和原scope/config原文，不把这些私有值序列化。
- 原ChannelIdentity算法原样迁到commerce-api.policy，保持FQN与算法不变供核心/客户端共享，避免再造store key算法；不改历史迁移。新增commerce-api dto.ChannelCatalogAuthorityContracts、policy.ChannelCatalogAuthorityPolicy、service.ChannelCatalogAuthorityService、spi.ChannelCatalogAuthorityInternalApi；core.service.impl.ChannelCatalogAuthorityServiceImpl及原CoreAutoConfiguration Bean。
- 内部POST `/internal/commerce/catalog-authorities/capture`位于commerce-web.api.internal，沿InternalServiceResponses令牌/status约定，输入错误不回显内容；新独立内部自动装配不启用B端Controller。commerce-server依赖原web接入模块，并限定自身安全链仅令牌内部口/健康检查；未授权的其他路径拒绝。集成宿主继续原安全链和内部wrapper约定，不放宽全局规则。
- 独立客户端位于commerce-channel，单次typed调用COMMERCE服务（新增ServiceName常量），重验全部事实/派生身份hash/channelStoreKey/期限/回执，无默认目标和回退。`shop.commerce.authority-client.enabled`默认关闭，已有本地Service优先；该开关仅远程读，不启用发布/任务/业务写入。原部署命令不变。
- 执行：先观察无能力失败专项→完成全部代码/测试→集成与独立服务统一编译→集中原连接/授权/新只读/真实HTTP/令牌及关闭装配专项→消融与一次独立复核→仅相关修复复测→设计、代码、验收对账/生成清单/本地白名单提交。预期覆盖跨主体/门店/旧修订、DRAFT/停用、撤权/过期/缺CATALOG、网络外事务、版本/凭据/身份变化、伪回执/超期、输出无密钥、旧服务缺能力、401/400/404/409和独立关闭状态。不宣称完整FOOD适配或真实平台已可发布。

### L3 S2B2B2a 原营业日历来源实施计划（2026-10-26）

完整FOOD目录前补原营业日历只读合同，不在外部适配器复制fnb表或解析一份新营业时间。此批只交付可信日历来源；媒体和完整目录映射仍属后续S2B2B2。

- food-api新增StoreCalendarSourceContracts.Query(entityNo/storeNo/fromDate/toDate)，显式营业日期范围最多31天。Facts包含原门店status/openHours、原策略时间线policyNo/timezone/cutoverMinute/effectiveFrom/version、范围内日期例外calendarNo/date/mode/intervals/policyNo/version及30秒回执；无默认时区、全天营业或默认策略。无生效策略/未知营业时间的判定关闭，READONLY保留并判为不接单。
- 保留覆盖该日期范围的UTC保护区间（fromDate减2天至toDate加3天），读区间前最后一个策略和区间内全部后续策略；最多256次策略变化，超界拒绝。跨时区/切点可能使同一营业日跨自然日，不将例外擅自改为自然日；原保存时policyNo仅溯源，不要求等于现在生效策略。
- StoreCalendarSourceServiceImpl使用原StoreCardQueryPort、StorePolicyMapper、ServiceCalendarMapper。原门店查询均在事务外；主体/门店/MAIN限定的本域两次只读RR读取围绕第二次门店查询，比较原事实检测变化；已有事务拒绝，缺失原门店/能力/策略关闭；无任何业务写入。
- 原ServiceCalendarPolicy同FQN迁到food-api，原签名/行为保留并追加仅需时区/切点的重载，客户端复用acceptsAt/businessDate/normalize，提供范围内给定Instant的精确判定。StoreCalendarSourcePolicy校验严格输入/字段/顺序/唯一性/原scope/期限和完整摘要；摘要不是签名，不替代服务令牌。
- 沿现有food-web.foodclient/FoodClientConfiguration注册单次FOOD typed远程服务，food-server.api.InternalStoreCalendarSourceController沿原安全链；内部POST /internal/food/store-calendar-sources/capture，不新增公开API、表、权限或任务。
- 执行：先观察capture未实现RED；全部代码及测试完成后统一编译；集中校验原小时/跨午夜/04点切日/OPEN与CLOSED/未来策略切换/跨主体店/未知时段/缺能力/并发变化/原始输出篡改与令牌；一次守卫消融、一次独立只读复核，修复仅验证相关部分。集成及独立服务干净打包，更新三处对账，仅本地白名单提交。

### L3 S2B2B2b 原公开媒体来源实施计划（2026-10-26）

原MediaAssetQueryPort只返回归属和状态，目录还需要由原媒体所有者提供稳定公开地址、原图元数据和短期完整性回执。复用sys_media_asset及原MediaStore，不新增媒体表/上传路径或行业图片库。

- shop-base唯一spi.media新增MediaPublicSourcePort.capture(Request(entityNo/storeNo/assetKeys))、MediaPublicSourceFacts及MediaPublicSourceInternalApi；最多20个不重复原assetKey，严格类型/未知字段拒绝；返回schemaVersion=1/purpose=CATALOG_PUBLIC_MEDIA、原Request、依请求顺序的Asset(assetKey/publicUrl/contentType/bytes/width/height/sourceRevision)、verifiedAt/validUntil/receiptNo。
- shop-core.media.port.MediaPublicSourcePortImpl原主体/门店/GOODS/ACTIVE且无purgeBatchNo精确读原台账；原字段构成稳定修订（原台账没有version，禁止伪造版本/内容hash）。两次本域RR只读围绕事务外MediaStore.publicUrl生成，比对原元数据/归属/状态，已有外层事务拒绝。所有请求图片都必须得到事实，缺失/私有/待清理不返回部分成功。
- 只接原MediaStore生成的稳定HTTPS地址，校验无用户信息/query/fragment/路径穿越且路径精确对应原key；不请求图片、不读取字节、不签私有URL，不承诺30秒内文件必然可下载。原本地相对地址不猜外网host，明确冲突；图片原台账必须为公开GOODS，额外核对原公开路径形态，防止脏台账将私有路径公开。
- 沿shop-app.portal.internal挂POST /internal/media/public-sources/capture，InternalServiceResponses先令牌再输入/status，错误不回显内部信息。已发现direct图片域名过滤器会改变新回执里的规范URL；仅为此新内部路径增加上下文路径感知的原样放行，公开入口改写保持原行为。
- commerce-channel.RemoteMediaPublicSourcePort及CommerceMediaClientAutoConfiguration供各行业共用，单次PLATFORM typed调用，校验完整事实/范围/期限/摘要，404/409等保持原状态，无目标回退。shop.commerce.media-client.enabled默认关闭，本地原owner优先，不隐式启用CIM/发布任务或真实渠道。
- 验证：先观察原捕获缺能力RED及direct过滤器改坏回执RED→完成全部代码与专项源码→统一编译→集中H2原归属/状态/元数据/事务/变化、稳定URL/超期/缺图/严格HTTP/令牌/本地优先及旧媒体回归→一次守卫消融及一次独立只读复核→相关修复复测→干净打包/设计对账/本地精确提交。完整FOOD目录/媒体与原餐牌依赖绑定仍在下一步，不宣称已经发布菜单。

### L3 S2B2B2c-1 餐牌渠道连接核验计划（2026-10-28）

本批落实AC03/AC15的目录来源准入子集，沿既有PRD和ADR-042；不代表完整目录适配完成。

- 新增可选`food-commerce`桥接jar，只依赖food-api、commerce-api与Spring装配/事务边界；通用commerce五模块不反向依赖餐饮。新增`food-commerce-embedded`构建profile，组合原food-embedded、commerce-embedded用于集成宿主；food-server可单独选此profile并复用原COMMERCE内部客户端。默认零售/餐饮构建不引入桥接。
- `food.commerce.dto.FoodCatalogBindingContracts.Query`含原MENU/CURRENT查询、原连接查询、bindingNo；输入不接受成功标志、URL或价格。Facts返回原两份证明、绑定、CHANNEL/DELIVERY语境及两份证明最短有效期；它只证明身份关系，不证明pricePolicyRef的定价授权、库存或接单资格，不注册完整ChannelCatalogSourcePort。
- `food.commerce.service.FoodCatalogBindingService.capture`与`service.impl.FoodCatalogBindingServiceImpl.capture`执行原餐牌CURRENT读取→原连接授权读取→再次CURRENT读取，调用期间不允许外层事务。校验完整原证明/摘要/范围/期限，比较两次原发布内容与资源；不得缓存或以历史修订替代当前修订。
- 精确匹配原binding的menu/store、ACTIVE、provider=providerCode、channelAccountRef=externalAccountRef、externalStoreRef；保持大小写和原编号，不用CIM内部accountNo尝试回退、不截断128位外部编号迎合旧64位字段。旧绑定不满足新核验则明确冲突，需原业务重新确认；本批不迁移、不改写旧绑定。连接必须FOOD/LOCAL/INSTANT；绑定须唯一对应CHANNEL/DELIVERY语境，保留原pricePolicyRef但不把它当已核准价格。
- 默认关闭`shop.food.commerce.binding.enabled`；还需shop.food.enabled与原两个Service及ObjectMapper。自动装配在原food owner/web、commerce owner/client和Boot Jackson之后，本地或独立客户端均调用相同Service合同，缺任何依赖不装配，不伪造成功。可由宿主提供显式自定义Service替换。
- 文件：新模块pom、Contracts、Service/Impl、AutoConfiguration及imports、专项测试/夹具；修改backend/shop-app/food-server的profile及food聚合pom，更新ADR、结构化设计用途/限制和生成清单。本批无新表、Mapper、迁移、公开或内部HTTP端点、任务、真实网关、支付或前端。
- 验收：FoodCatalogBindingServiceTest覆盖成功与期限、跨范围/历史输入、平台/账号/门店不匹配、错误owner/履约、缺失绑定/语境、伪造/过期事实、读取中变化、原错误保持及外层事务禁止；FoodCatalogBindingWiringTest覆盖默认关闭、缺依赖、启用及自定义优先，并检查依赖隔离。先完成全部业务/测试源码，再编译集成与独立形态，集中专项、一次守卫消融、一位全批只读review，修复只相关复测，精确提交。本次不启动真实平台、不部署；完整目录、时段交集、选择可行性、非CNY维护和S3仍按后续计划。

### L3 S2B2B2c-2 完整餐牌目录适配设计（2026-10-28，已确认，分批实施）

#### L1 目标与范围

上一批`c1c10cd6a`只证明原餐牌与原连接属于同一平台/外部账号/门店，不授予价格政策、营业时间或完整发布资格。本阶段把原餐牌、基准价/税、渠道价策略、营业日历、媒体及组合规则合成可冻结的完整FOOD目录，打通原预览/手动提交/独立worker读取。真实平台Gateway、外部订单、支付、前端及S3发布事件仍后置。

复用PRD AC03（完整可信目录）、AC04（未决动作不重发）、AC15（同合同双部署）；本节方向已由用户确认，未交付条目不是已实施合同或已通过验收。新类、方法、配置与JSON均为拟定，不能据此标记已实现或增加已交付API数量。

#### L2 方案比较与推荐

| 方案 | 优点 | 限制 | 建议 |
| --- | --- | --- | --- |
| 直接投影目录v2，仅允许可表达的日历 | 改动小，旧格式原样沿用 | 无法完整表达营业日切点、未来策略/时区改变、一天多段营业；复杂情况只能拒绝 | 可作为严格受限兼容路径，不作为完整餐饮方案 |
| 保留v1/v2，新增v3有效时间约束 | 原规则不丢失，原快照/摘要/旧适配器兼容；同样适用于零售 | 增加通用合同与网关能力门禁 | 按已确认方向分批实施 |

价格不新增第二套SKU价表。首版建议内置唯一政策`MENU_PUBLISHED_PRICES_V1`：原餐牌覆盖价优先，否则用原商品CATALOG_BASE；配料/套餐仍沿已完成的MenuCatalogPricePolicy。不同渠道通过不同餐牌和原skuPrices维护差价与选项。未来加价率、促销等模式另由原价格owner增加可信策略，不在渠道内核写表达式引擎。

架构流程见[完整目录捕获](./design/diagrams/food-channel-catalog-capture.svg)。桥接层组合原证据，通用内核仍不读取fnb/商品/库存表；框内的“可冻结”不意味着真实平台已授权或已发布。

#### L3 1. 原来源与价格政策闭环

现有MenuChannelAuthorityPort在修改渠道绑定、餐牌语境和发布时核验，但尚无真实实现。只增加捕获器会留下“原绑定无法正常激活/发布”的死路，本阶段必须同时接通写入前核验和发布后证据读取。

1. 在原commerce-api的ChannelCatalogAuthorityService增量提供`resolve(IdentityQuery)->Facts`。IdentityQuery为entityNo/storeNo/providerCode/externalAccountRef/externalStoreRef；原CIM实现从本域表严格查找当前FOOD/LOCAL/INSTANT连接，零条或多条均拒绝，不能取第一条。随后复用capture对真实身份、当前连接修订及CATALOG授权核验。旧服务默认拒绝；不接受调用方自报connectionNo与解析结果冲突，不以此接口激活连接。
2. food-commerce的`MenuChannelAuthorityPortImpl`实现原FOOD契约：authorized用于绑定激活前的精确外部身份核验；pricePolicyAuthorized用于语境保存/发布前核验支持的策略、原绑定范围、原连接与原门店市场。调用原MenuChannelBindingService.getBinding；不依赖“已经存在CURRENT发布”，避免先发布才允许配置的循环依赖。所有写入仍由原Service负责权限、乐观锁、审计、幂等及Outbox。
3. `FoodCatalogPricePolicyService.capture(PricePolicyQuery)->PricePolicyFacts`用于发布后读取。输入entityNo/storeNo/menuNo/menuRevisionNo/bindingNo/connectionNo/connectionRevisionNo/pricePolicyRef；输出policyRef、policyVersion=1、mode=MENU_OVERRIDE_THEN_CATALOG_BASE、market/currency、menuContentHash、原MenuCatalogPriceContracts.Facts、原绑定与授权证明、verifiedAt/validUntil/receiptNo。
4. 政策编号只是明确选择原处理器，绝不是成功凭据。需同时验证原冻结语境的pricePolicyRef、当前菜单证明、原账号授权、原价/税、币种及回执。原值不在支持列表时拒绝，不把历史任意字符串自动解释为新策略；商家须沿原配置入口明确重选并重新发布，不直接更新历史快照。
5. 主价公式：`P = menuOverride(sku, item)`，若无覆盖则`P = originalCatalogBase(sku)`。配料MODIFIER、免费SPEC_VALUE及套餐INCLUDED/ADD_SKU/DIFF_BASE继续调用原投影，不再在bridge重复计算。最小货币单位用long，组合数量保留原精确值；同一目录只一种原门店币种，连接profile.market须与原门店market一致，不做汇兑，不叠加本地优惠。
6. 非CNY覆盖价须改原FOOD维护、发布和投影校验，使覆盖币种与原门店市场事实严格一致。不能仅删除normalize里的CNY判断；旧CNY合同与历史快照必须兼容。若原下单仍不支持该市场，应分别返回“目录可生成/订单准入不支持”，不把目录价能力冒充非CNY收款能力。

不新建渠道价管理表，不新增公开管理端点。使用原绑定/餐牌语境/覆盖价接口；政策证明为只读内部组合。新resolve只解析既有连接，真实网关和连接激活仍保持原门禁。

#### L3 2. 时间规则：原规则交集与目录v3

业务公式：`可售(t) = 原门店营业日历允许(t) AND 原餐牌时段允许(t) AND 已核验的渠道语境匹配`。同一连接的多个餐牌分别保留自己的可售时间，不能先并集餐牌时段再让所有菜品共享。

时间口径必须跟代码一致：当前MenuSchedulePolicy实际按门店策略timezone求餐牌窗口，并检查窗口起始自然日及前一自然日；不直接使用MenuVO.timezone另造一套时钟。营业日例外仍按原cutoverMinute计算businessDate。营业日例外和餐牌起始日例外是两个不同维度，不能互相覆盖或直接改名转换。

建议首版固定编译7天UTC窗口：[捕获日UTC00:00, 第7天后UTC00:00)，由服务器确定、不接受前端任意时间。UTC只是技术窗口坐标，业务判定仍用原门店时区和切点。原日历查询按该范围两端各扩2个UTC日期覆盖可能的营业日，约12个日期、低于现有31天上限；保留原256次策略变更等边界。窗口内若缺策略或原证据不完整，拒绝整份捕获，不把“未知”生成永久可售。

有效时间编译采用边界分段：

- 收集窗口起止、原策略生效点、原门店/餐牌开闭点、例外日/有效期边界及对应ZoneRules时区偏移变化点。
- 夏令时重叠须纳入同一本地时间的全部合法offset；不存在的本地时刻纳入跳变边界，不能随意选一个offset或平移业务时间。
- 相邻边界形成半开区间[a,b)。用原owner的纯判定在a求交集，保留允许区间并合并相邻区间；不按分钟轮询、不按整点采样。
- 提取/移动原MenuSchedulePolicy至food-api、保持原FQN和原入口，增加纯菜单判定/已校验事实的准备入口供组合复用；禁止在bridge复制业务判定。StoreCalendarSourcePolicy继续拥有营业日规则。未来时区/切点变化按原生效点分段计算。
- 同一来源可有多个UTC区间；全关闭时保留来源/商品及空区间，明确表示不可售，不把空集合解释成全天开放。建议每来源4096段硬上限，超限整份UNSUPPORTED，不截断。

拟定通用增量合同，命名归commerce-api，非餐饮专属：

| 对象 | 字段与语义 |
| --- | --- |
| Document.schemaVersion | 新增3；v1/v2 JSON、固定历史hash、旧构造调用保持原样 |
| Document.availability（仅v3非空） | horizonStart/horizonEnd、sources；旧版本字段为null且不序列化 |
| CatalogAvailability.SourceWindow | sourceRef、selectionRevision、effectiveWindows；每个来源精确一份 |
| CatalogAvailability.UtcWindow | fromInclusive/toExclusive，原Instant精度、升序、非重叠、在horizon内 |
| 原details.schedules | v3中保留原餐牌规则供追溯；最终可售时间唯一以availability为准，不与原规则作OR或自行改写 |
| Document.taxBases（仅v3） | 按taxBasisRef保存原rateBp、priceTaxMode、market/currency/currencyScale、原税owner/version/contentHash；不能只留无法解释含税口径的引用 |
| 唯一接收门禁 | Gateway显式supportsDocumentSchema(3)，并能精确表达全部时间/价格/选择规则；不支持即拒绝，不能降为v2或永久周循环 |

税基础只投影原CatalogPriceSourcePort.TaxFact，不在Gateway重算税。原价为未税但平台要求含税等语义转换，须由原定价owner提供可核验展示价格；未提供时UNSUPPORTED，不在渠道临时加5%或把5%写死。

7天营业窗口与30秒证明有效期必须分离。validUntil仍取全部证据最短期限、最多捕获开始后30秒；它只限制本次证据被接纳的时点，不等于菜单只营业30秒，也不证明未来7天价格不会改变。已冻结快照不延长、不覆盖；过期手动预览要求重新预览确认，不能静默替换已确认内容。价格/日历变更通过下一批S3事件重捕获，未来生效时点也须唤醒。

窗口滚动沿原唯一CommerceRecoveryJob和cim_sync_policy.next_check_at，最迟horizonEnd前24小时重捕获；不新增每渠道cron。具体接线归S3，未完成前默认不启用自动发布。真实Gateway若不能保证窗口外关闭/有限有效期，不得声明支持v3；人工补发或定时任务在线不是过期保护的替代品。

#### L3 3. 目录组装与完整性校验

| 原对象 | 目录投影 | 要求 |
| --- | --- | --- |
| 菜单分类 | Section | 不用基础商品分类替代餐牌分类；保留原sortNo |
| menuItem/原商品 | Offer | 菜项是销售身份，同SKU出现在不同餐牌/菜项时不合并 |
| 原可售SKU | Variant | EXPLICIT/ALL只取冻结发布成员，不临时继承新增SKU |
| 原ItemOptions及依赖快照 | OptionGroup/Option | 保留REPLACE空列表、口味/规格/配料类型、精确数量、互斥与依赖 |
| 原套餐依赖 | BundleGroup/BundleMember | 保留原基准SKU、INCLUDED/ADD_SKU/DIFF_BASE及unitsPerPick，不提前取整或把容器和成员重复计价 |
| MenuCatalogPriceContracts.Facts | UnitPrice | 只映射已核验原PriceLine和原税依据，不自行计算另一份价格 |
| DishPresentation.imageRefs | Image | 使用原assetKey请求MediaPublicSourcePort，最多20项一批，零缺失、零猜测、零下载 |
| 无餐饮自定义图时的GoodsFact.cover | 原商品封面 | cover是URL时不能靠截字符串推断assetKey；需原商品/媒体owner的规范key解析合同，无法核验则拒绝或明确无图，不用非可信URL顶替。存在但不可核验的原封面不得静默当无图 |
| 库存 | Inventory.UNKNOWN | 未提供真实库存策略时明确UNKNOWN，不伪造0、不锁库存；要求实时数量的平台保持不支持 |

封面解析拟扩展已有GoodsCatalogQueryPort的`resolveCatalogImages(CatalogImageQuery)->CatalogImageFacts`：输入原主体/门店及1至20组goodsNo/expectedCoverRef；原商品owner核对当前封面与期望原值对应同一原资产，合法出口别名等价须有原媒体证明，由原媒体owner严格证明受管引用与key，MediaKeys仅可产生候选而不作授权，返回goodsNo/原coverRef/assetKey/原商品版本/期限/回执。没有封面须原owner明确确认；外部URL无法解析为受管资产则UNSUPPORTED。解析不授予图片可用资格，随后仍需原MediaPublicSourcePort核验台账与稳定HTTPS。旧提供方默认拒绝，不能在bridge猜URL路径。

目录内分类/菜项/选项/套餐技术key按原来源层级做长度编码后稳定hash；保留原menuNo/revisionNo/menuItemNo/groupNo/choiceNo/SKU的类型化来源清单，不能只留下不可逆hash。多来源混合主体/门店/币种或同远端scope混合FOOD与RETAIL均拒绝。同一binding/channel下只捕获显式配置来源，不把所有已发布餐牌自动并入。

稳定contentHash包含原来源修订、原价格/税修订、政策版本、选择规则、有效窗口和媒体稳定修订；剔除回执刷新时间、validUntil及临时receipt值。排序确定，空值语义固定；输入顺序不制造伪变化，真实价格/图/时段变化必须改变hash。午夜技术窗口滚动导致hash变化是预期，不是价格变化。

组合可行性在原FOOD owner补充，不在通用渠道内核重新定义餐饮规则：

- 先沿原校验检查数量范围、默认值、互斥、依赖无环；再证明至少存在一个合法组合，且每个max>0的可展示选项都有可达的合法组合。
- 以选项是否选择为布尔分支，要求项/依赖闭包/互斥传播后，检查原数量上下界及组总量。选项最多6位小数转BigInteger精确单位，套餐份数按原整数约束，不用double或把小料取整。
- 默认值沿原规则校验，可低于组最低量，仍允许顾客补选；不新增“默认值必须直接成单”的要求。
- 使用确定性的有界搜索/剪枝，不引入任意脚本或通用规则平台。建议每组10万搜索节点、全目录100万节点；超限返回UNPROVEN并阻止发布，不声称规则无解、不放行猜测。该限制是实施建议，可按性能证据调整，不能改变判定语义。

整个capture顺序为：验证命令及原来源→精确绑定和价格政策→原价格/日历/媒体→原选择可行性→投影及UTC区间→重读当前餐牌和原授权、校验全部证据期限/稳定修订→返回完整Document。无网络跨事务，任一失败整份失败，不返回部分可发布目录。现有单菜单原价100 SKU、图片20项/批、整体4MiB/1万销售条目等限制继续执行；超限不拆成伪一致快照。

#### L3 4. 唯一Port、双部署与拟定API

目前ChannelSourceValidationPort没有owner分派且原内核要求唯一实例。不能直接把FOOD和RETAIL实现都注册成该Port。建议保持消费Port和旧调用签名兼容，新增唯一行业提供合同ChannelSourceValidationProvider.owner()/validate(Source)，由commerce-core的单一Port实现按owner注册和分派；重复owner启动失败，缺owner返回UNSUPPORTED，不默认成功、不跨行业回退。

本批目录来源只支持FOOD/MENU：sourceRef=原menuNo，sourceRevision=原发布revisionNo，必须CURRENT。OFFER/SKU/OPTION等历史映射需要显式父餐牌/销售条目/选项组语境，留C2前增量设计，不从裸编号猜父关系、不用MENU证明替代全部子资源授权。

| 拟定内部接口 | 输入 | 输出/鉴权 |
| --- | --- | --- |
| POST /internal/commerce/catalog-authorities/resolve | IdentityQuery | 原ChannelCatalogAuthorityContracts.Facts；原服务令牌，无公共账号信息泄露 |
| POST /internal/food/commerce/catalog/capture | 原CaptureCommand | Document v3；原FOOD内部令牌及SYSTEM范围签名，主体/门店精确核对 |
| POST /internal/food/commerce/sources/validate | 原Source | 原Validation；同上，缺合同/不支持种类明确拒绝 |
| POST /internal/product/catalog-images/resolve | CatalogImageQuery | 原CatalogImageFacts；CORE原服务令牌，复用原商品Port的增量方法 |

A1已编码原CIM resolve及新增的原CORE market读取，余下目录capture/source validate/封面resolve仍未注册。已有B端scope/source/policy/preview/submit/run查询API继续复用，不新增第二套“外卖菜单”管理端点。内部校验回执不暴露给顾客端。错误沿400/403/404/409及原未知状态规则；原失败保持，日志和诊断不输出凭据或完整账号秘密。

集成：food-commerce本地读取两个api的原Service，向原CIM提供FOOD目录Port/来源provider。独立：commerce-channel通过唯一通用内部合同调用FOOD，FOOD可以通过原COMMERCE只读接口取授权事实；链路无跨网络事务，授权读取不会递归触发目录捕获。餐饮Controller落food-commerce.api.internal并复用原安全链，不放到零售Controller；新内部合同的跨进程传输依赖在该可选模块显式声明。

每个owner只能有一种实际目录提供方。远程与本地提供方同时启用应启动失败，不用全局ConditionalOnMissingBean掩盖另一个行业。commerce-server不引food-api/core；禁用bridge时不挂新FOOD端点、不改变原餐饮发布；禁用commerce时不影响原零售/餐饮业务。

#### L3 5. 数据、类与方法清单

数据库复用：原fnb_menu*、fnb_config_revision、商品/价格/税和sys_media_asset只由原owner访问；cim_catalog_scope/source/snapshot/sync_run/run_item/request_journal/operation/attempt记录范围、冻结及执行。V3保存现有snapshot.schema_version、payload_json与source_manifest，保持历史不可变。本设计不新增业务表；若实现发现需要新增列，先补增量迁移设计，不改V1-V4或已执行FOOD迁移。

| 模块/包 | 拟新增或扩展类 | 方法/责任 |
| --- | --- | --- |
| commerce-api dto/service | ChannelCatalogAuthorityContracts、ChannelCatalogAuthorityService | IdentityQuery及resolve；老提供方默认拒绝 |
| commerce-api spi.commerce | CatalogAvailability、CatalogTaxBasis、ChannelCatalogSourcePort.Document | v3可售窗口、taxBases及原身份清单；旧版本序列化/构造/hash不变 |
| commerce-api spi.commerce | ChannelSourceValidationProvider、ChannelCatalogSourceInternalApi、ChannelSourceValidationInternalApi | owner/validate、capture、validate的唯一合同 |
| commerce-core port/policy | ChannelSourceValidationPortImpl、CatalogAvailabilityPolicy | 唯一owner分派；v3范围/窗口/来源/版本核验 |
| commerce-core service.impl/api.internal | 原ChannelCatalogAuthorityServiceImpl及内部Controller、原CatalogPublication/同步流程 | resolve原读取，v3冻结/发送版本门禁，过期不延长 |
| commerce-channel | RemoteChannelCatalogSourcePort、RemoteChannelSourceValidationProvider、配置 | 显式FOOD目标，严格typed读/回执校验，默认关闭，无目标回退 |
| shop-base spi.product / 原商品owner | 原GoodsCatalogQueryPort及对应内部合同/原实现 | resolveCatalogImages，核对期望封面和原受管key；不复制商品/媒体台账 |
| food-api/core policy | 原MenuSchedulePolicy、MenuPricePolicy、OptionSelectionPolicy及发布入口 | 纯规则复用、多币种原维护与发布、组合可行性；旧下单语义不变 |
| food-commerce dto/service/impl | FoodCatalogPricePolicyContracts、FoodCatalogPricePolicyService/Impl | 捕获明确政策及原价证据，不重复定价 |
| food-commerce port | MenuChannelAuthorityPortImpl、FoodCatalogSourcePortImpl、FoodSourceValidationProviderImpl | 原FOOD写前核验；owner=FOOD/capture；MENU原来源validate |
| food-commerce assembler/policy | FoodCatalogAssembler、FoodCatalogAvailabilityPolicy、FoodCatalogSelectionPolicy | assemble、compileIntervals、requireFeasible；原身份清单完整保留 |
| food-commerce api.internal/config | InternalFoodCatalogSourceController、InternalFoodSourceValidationController、原装配配置 | capture/validate；原token+SYSTEM scope链，独立开关/唯一提供方 |

所有“Service/Impl”在实施清单中按统一service/service.impl包展开；Port接口保持唯一spi，实现在port。原菜单图片key归属解析若原合同不足，必须在原owner扩展类型化只读能力；不能在channel写另一份URL反向识别规则。该差距须在第一批原来源接线时解决，不留到真实平台联验才发现。

#### L3 6. 三批交付与验收标准

| 批次 | 工作 | 验收落点（拟定测试名，尚未执行） |
| --- | --- | --- |
| A 原政策与来源闭环 | resolve、原绑定/语境可配置、固定价策略证明、封面key规范来源、非CNY覆盖原维护、唯一来源分派 | FoodChannelPricePolicyTest、ChannelCatalogAuthorityResolveTest、FoodSourceValidationTest |
| B 完整目录与时间合同 | v3兼容、原时段交集、分类/规格/配料/套餐、媒体、精确数量与可行性、稳定hash | FoodCatalogSourcePortTest、FoodCatalogAvailabilityTest、FoodCatalogSelectionFeasibilityTest、CatalogV3CompatibilityTest |
| C 双部署接线与集中验证 | 内部API/远端客户端、原安全链、预览/过期/提交/worker/UNKNOWN阻断、默认关闭 | FoodCatalogSourceHttpTest、RemoteChannelCatalogSourcePortTest、CatalogV3WorkflowTest、原架构/部署专项 |

A-C按依赖顺序完成业务与测试源码，统一编译后一次集中受影响测试；修复只相关复测。不会再次全量测试，不为此启用真实渠道或支付。S3随后单独交付发布/价格/日历/图/窗口滚动的可靠通知；C2外部订单继续排在完整目录和S3之后。

关键验收：

1. 同一SKU在早餐与外送餐牌有不同菜项/价格/配料，均完整保留；不同主体/店/连接/币种串用一律失败。
2. 切点04:00时凌晨02:00按原营业日；餐牌跨午夜例外保持起始日语义；一日两段营业、未来时区/切点生效和DST两次/缺失时刻均与原判定逐边界一致。
3. 缺价格、税、政策、图片归属或证据过期时整份拒绝；不填0、不丢选项、不改成全天。伪造receipt、改修订、读取中变更都不能发布。
4. 数量为0.5/1.25等时不取整；互斥和依赖造成的死分支/无解必须拒绝；搜索超限明确UNPROVEN；默认未达到组最低量仍允许顾客补选。
5. v1/v2固定历史JSON/hash不变；旧Gateway默认不支持v3；不支持绝对有效期/选择规则的平台不能接收降级目录。
6. 刷新回执不改变业务hash，改真实价/税/媒体/营业窗口必改变；7天窗口边界滚动按原唯一job调度，不创造第二套cron。
7. 原有旧快照过期不被延长或覆盖；重预览得到新快照；SUBMITTED/UNKNOWN保持原阻断，不因来源刷新盲重发。
8. 新内部API缺token/签名/范围不匹配先拒绝；默认禁用、缺原提供方、多owner重复、本地和独立读取合同均有真实HTTP/装配专项。文档和设计守卫不能代替上述业务验收。

方案整理阶段历史记录（2026-10-28）：一位独立只读复核者未发现需要业务方补答的重要问题。该轮只形成设计和流程图，随后用户确认方向并启动A1；已交付子集以A1记录为准，其余拟定类、端点和v3字段仍未实施。

#### L3 A1 原连接解析与价格政策实施切片

本切片执行已确认A批中的连接解析、原写前授权和政策证据，仍仅菜单同步。封面规范解析、非CNY覆盖价维护及owner来源分派接续A2；v3/完整目录仍在B，不提前标完成。

- commerce-api增IdentityQuery与resolve默认拒绝，Policy验证精确身份/原短期事实；core沿原StoreConnectionMapper有界join读取当前匹配连接（最多2条判歧义），capture前后复查唯一候选，远端身份核验不持事务。web原Controller增加resolve，channel typed客户端增加同合同，旧capture调用兼容。
- shop-base原CatalogPriceSourcePort增market(MarketScope)->MarketFacts，无SKU、无税和价格写入；原product owner读取真实门店归属、经营主体市场及MarketPort，前后比较，30秒事实/摘要。原CORE内部Controller/客户端加market读取，拒绝未知JSON字段及来路market/currency。写前验证不再依赖CURRENT菜单或虚构SKU，且不能拿渠道市场充当门店市场。
- food-commerce新增dto.FoodCatalogPricePolicyContracts、policy.FoodCatalogPricePolicy、service.FoodCatalogPricePolicyService及service.impl.FoodCatalogPricePolicyServiceImpl：绑定证明→原MenuCatalogPriceService→再次绑定证明，校验政策键、原发布/授权稳定身份、实际市场/币种/原价/税和最短期限；不重复计算PriceLine。
- food-commerce.port.MenuChannelAuthorityPortImpl沿原MenuChannelBindingService.getBinding和两份原事实核验，实现authorized及pricePolicyAuthorized。激活前不要求绑定已经ACTIVE；语境政策要求原绑定ACTIVE，前后重读防变更；不要求已有发布。新price-policy.enabled显式默认false，并要求原Service/Port存在、本地自定义实现优先。原FOOD写入入口、权限、版本与幂等不变。
- 不新增表、迁移、公开路由、支付/订单/库存逻辑；内部新增resolve和market两路。先整批业务及测试源码，再统一集成/独立编译，集中专项，一次独立只读复核及守卫消融，修复仅相关复测，本地精确提交；不推送部署或启用真实网关。
- 拟定验收：ChannelCatalogAuthorityResolveTest、原授权服务/HTTP/客户端专项；CatalogMarketSourceTest及原价格/CORE内部/FOOD客户端专项；FoodChannelPricePolicyTest、MenuChannelAuthorityPortTest、FoodCatalogPricePolicyWiringTest与原绑定15项，覆盖精确身份、歧义/跨范围、变化/期限、默认关闭、无CURRENT配置、市场不符及伪回执。

#### L3 A2 详细设计与代码对照复核（2026-10-28，A2.1、A2.2a及A2.2b1已实现，其余待实施）

**L1 任务目的。** A1已完成精确渠道身份、原市场及价格政策事实；A2补齐完整目录所需的原图片身份、真实市场商品/SKU及覆盖价、行业来源核验。A2完成仍不等于可以发送完整菜单：目录组装/v3在B，端到端接线在C，自动发布事件在S3。本节保留A2完整设计；原封面身份A2.1及真实市场元数据A2.2a已实施并单独验收；纯币种规则与schema6历史格式A2.2b1已补齐；原FOOD业务写入、发布接线已由A2.2b2交付，owner分派按A2.3继续。

**L2 复核结论。** 原方向可用，但以下设计缺口必须补齐后再编码；它们不是本轮已复现的新A1生产缺陷。

| # | 重要性 | 原代码证据 | 设计修正 |
| --- | --- | --- | --- |
| R1 | 高 | MediaKeys.extract明确“宁可多抠”，用于GC，剥离任意HTTP host且可能返回多个key | 只作为候选提取，不能作URL归属证明；由原媒体owner严格证明出处、别名及原台账 |
| R2 | 高 | GoodsCatalogQueryPortImpl.getGoods/getSku固定market=CN；Dish/Modifier/Bundle/Recipe及通用发布器均调用它们 | 新合同需覆盖餐牌和独立原依赖资源；不能只放开覆盖价或只在CHANNEL菜单内分支 |
| R3 | 高 | MenuCompositionDefinitions、MenuCompositionSource、MenuPublicationDocuments、CatalogPolicy及结账均调用CNY normalize | 分离纯结构、当前写入上下文、冻结历史校验；旧入口语义保持，不把实时市场查询塞进历史解析 |
| R4 | 高 | MENU冻结为schema4，MenuSourcePolicy硬性只接受4；独立依赖用1/2/3，schema5已被STATION使用 | 新市场上下文格式建议6，按kind+schema区分MENU及依赖资源；旧1至5及hash保持原样 |
| R5 | 高 | CatalogPersistence按唯一ChannelSourceValidationPort.getIfAvailable取实例 | 只保留一个消费门面，按owner注册提供方；并存/缺失/重复/旧实现兼容有明确规则 |
| R6 | 中 | 独立FOOD有CoreGoodsCatalogQueryPortImpl，内部还有LazyGoodsCatalogQueryPortImpl逐方法转发 | 新方法必须贯通原owner、HTTP、remote和lazy，避免看似实现但实际走默认不支持 |

**L3 1. 原封面key解析。**

目的：把已发布餐牌中的原商品封面，解析为可核验的原assetKey，再交由已有MediaPublicSourcePort验证可公开展示；不下载、搬运图片，不重建图片表。

- 优先级：餐牌冻结的DishPresentation.imageRefs非空时只用这些原key；为空才使用同一冻结GoodsFact.cover。自定义图无效时拒绝，不悄悄回退基础图；真实无图可以明确返回NONE，已配置但不能核验的图不得当作无图。
- 原商品owner核对entity/store/goods及期望封面，不能只验证“某个key属于该店”。真实封面key已改变则STALE/CONFLICT，要求由原FOOD重新发布餐牌；本批不动态改写旧发布快照。将来自动重发布也只能经原发布服务生成新修订，不能由渠道覆盖旧快照。
- 同一key的已知server/direct/CDN出口差异允许等价，但必须由原媒体owner证明是受管别名。不能单纯以URL字符串相等、截取路径相等或去掉任意host来证明；外部站点即使路径相同也不得认领本店资产。
- 商品层扩展已有GoodsCatalogQueryPort.resolveCatalogImages；原媒体适配层补窄职责MediaReferenceQueryPort.resolve，知道原MediaStore及合法出口/别名，依赖原MediaAssetQueryPort核对台账。严格解析实现放在可复用原媒体出口规则的位置；通过shop-base SPI供商品调用，不让shop-core引用shop-channel类。
- 合法绝对出口只取原MediaStore.publicUrl及已有shop.cos.domain、shop.media.direct-base-url明确配置的根；按scheme/host/port及路径边界核对，不信任请求Host或后缀相似域名。空配置不认可该别名，未受管旧域名须原媒体配置明确迁移，不自动白名单。
- MediaKeys可产生候选，但须严格URI、单一出处、受管host/前缀、完整路径与原key往返、原用途/归属等核验后才选定。多候选无法唯一证明、query/fragment/私有签名、编码/点路径、不支持格式均拒绝。不要修改GC提取器，避免扩大清理风险。
- 解析与公开地址分开：裸key/合法本地引用可解析为原资产身份，但最终外发仍要求原MediaPublicSourcePort给稳定HTTPS。本地相对地址不能借请求Host或服务内网地址拼成外网URL。
- 商品RR读取→事务外原媒体证明→商品RR重读，比较原封面/版本；整批1至20条、无重复、无静默缺失、按请求顺序。无图也由owner明确确认；全部事实最多30秒。任一失败整批失败，不能取第一张或任意候选。

拟定DTO，属于只读合同而非可编辑实体：

| 对象 | 字段及约束 |
| --- | --- |
| CatalogImageQuery | entityNo/storeNo、1至20个Expectation |
| Expectation | goodsNo、expectedCoverKind=NONE/REFERENCE、expectedCoverRef；NONE必须无ref，REFERENCE必须有原字符串；缺kind不能当NONE |
| CatalogImageItem | goodsNo、coverKind、原引用摘要、assetKey（NONE时null）、原goodsVersion、assetIdentityHash/referenceReceiptNo（身份摘要，不冒充台账version）；期望来源须与冻结GoodsFact一致 |
| CatalogImageFacts | schemaVersion=1、purpose=CATALOG_IMAGE_IDENTITY、原query、完整items、verifiedAt/validUntil/receiptNo |
| MediaReferenceQuery/Facts | 原entity/store及单条引用（商品批次上限20条）→唯一canonicalKey、受管出口依据、原台账scope/status与assetIdentityHash、短期限；不接受调用者自报成功；真正公开媒体稳定修订仍由MediaPublicSourcePort提供 |

内部沿既定POST /internal/product/catalog-images/resolve及原服务令牌；若新增媒体内部transport，沿原媒体令牌合同，不另建公开图片接口。带引用/摘要的内部响应必须避免MediaHostRewriteFilter改写；原MediaDeliveryConfig仅注册/mp/biz/ops，内部路由保持此隔离并验证其原始字节。若实际过滤链覆盖内部路由再做精确保护，不全局关闭公开图片转换。实际字段codec、未知字段拒绝、范围/期限/摘要校验与A1相同。

**A2.1 实施批次（2026-10-28）。** 本批只交付上述图片身份链路：shop-base原媒体/商品合同与校验、shop-channel受管引用解析、shop-core原商品封面核验、shop-app内部令牌端点、food-channel远程及food-core延迟转发。无新表或迁移；市场元数据、schema6及owner分派仍待实施。原商品封面按原字符串摘要审计，允许受管别名同key；空串/null视为原无图，空白或非受管引用为错误。全部请求/响应严格codec，单批最多20条，最长30秒，事务外调用媒体，前后RR确认商品未变。验收类为MediaReferenceQueryPortTest、CatalogImageFactsTest、CatalogImageQueryTest、InternalCatalogImagesApiTest、CoreCatalogImagesTest、LazyCatalogImagesTest；覆盖别名/假域名、跨范围/失活、无图/失效、变更/期限、令牌原始协议及远程证据。按用户约定整批编码编译后集中专项测试，一次守卫消融及一次只读最终review。

**L3 2. 真实市场元数据与覆盖价。**

目的：让门店可以维护并发布其原市场币种的渠道餐牌，例如AED、TWD；不换算旧CNY金额、不开放其他市场下单或收款。

a. 先补原商品元数据，不能绕过原商品owner：

| 新增原方法 | 原输入 | 原输出 |
| --- | --- | --- |
| GoodsCatalogQueryPort.getCatalogGoods | entityNo/storeNo/goodsNo、可选expectedMarket仅作断言 | CatalogGoodsFacts：原scope、实际market/currency/scale、GoodsFact（真实市场SKU）、版本、期限/回执 |
| GoodsCatalogQueryPort.getCatalogSku | entityNo/storeNo/skuNo、可选expectedMarket仅作断言 | CatalogSkuFacts：实际市场、原SkuIdentityFact、期限/回执 |

实际market必须由原门店/主体/MarketPort确定，调用方不能选择另一个市场。可复用A1市场读取的原owner实现，不复制商家市场规则；每个事实都核对原store/entity和market。旧getGoods/getSku保留CN行为与调用签名，旧提供方对新方法默认不支持，不能回退CN。当前管理读取允许有效主体下ACTIVE/READONLY门店，不授予销售资格。

新内部方法沿原GoodsCatalogInternalApi，原InternalGoodsCatalogController接只读方法；CoreGoodsCatalogQueryPortImpl和LazyGoodsCatalogQueryPortImpl都必须完整转发和校验。新合同先贯通再改原FOOD调用，不能只在food-commerce投影层临时读表或拼SKU。

**A2.2a实施批次（2026-10-28）。** 先贯通原商品真实市场只读合同：CatalogMarketContracts.GoodsQuery/SkuQuery携带entity/store/业务编号及可选expectedMarket（仅断言），GoodsFacts/SkuFacts带原CatalogPriceSourcePort.market完整证明、原GoodsFact/SkuIdentityFact、版本、最长30秒期限及回执。SKU附原归属商品版本；商品SKU展开最多256，查询第257条即拒绝而非截断。只在新字段上使用严格嵌套codec，旧GoodsFact/旧CN transport不改。原owner按“事务外原市场→RR元数据→事务外原市场→RR元数据重读”确认上下文和对象未变；原市场缺失、断言不符、跨范围、归档、失活和证据过期均失败，不调用原计价capture/税/库存。管理允许READONLY，沿A1原市场口径。新增原内部POST /internal/product/catalog/goods/market-lookup和/market-sku，复用令牌、raw响应和PLATFORM目标；food-channel及food-core延迟转发完整实现。原2参数及图片构造方式保留，新增自动注入仅延迟取得原市场owner。验收为CatalogMarketFactsTest、CatalogMarketGoodsTest、InternalCatalogMarketApiTest、CoreCatalogMarketTest、LazyCatalogMarketTest及直接相关原方法回归。此批不改原FOOD发布/覆盖价路径，不启用catalog-currency开关；schema6、独立依赖维护、币种归一和来源分派继续按本节后续计划实施。

b. 维护时与历史时采用不同上下文：

- normalizeStructure(prices)：纯函数校验币种格式、金额非负/JSON安全、重复SKU、数量上限、排序；不访问DB/网络。
- normalizeForCurrency(prices, expectedCurrency)：在结构校验后要求所有覆盖价匹配原上下文币种；仍为纯函数。
- 旧normalize(prices)及本地price(...)入口保持原CNY语义；price使用覆盖值前还须核对nativePrice币种，不得把CNY覆盖值标成AED。实际订单准入仍沿原能力限制，不因目录元数据升级放开。
- 新渠道写流程在事务外取得A1市场事实，形成服务端MenuCurrencyContext，按真实市场取商品/SKU、校验覆盖价，再在原短事务中比较原工作版本/定义并保存。客户请求里的price.currency只是待验证输入，不是市场依据。市场或版本变化重新读取，不能带旧事实继续写。
- 新路径建议显式开关shop.food.commerce.catalog-currency.enabled，默认false。路径选择依据服务端原entity/store实际市场，而非“已存在CHANNEL餐牌”：开关关闭或原市场CN走既有CN路径；开关开启且实际非CN时，菜品、配料、选项组、套餐及菜单已绑定配方的身份读取/配置/发布均使用新市场合同。没有任何餐牌时也应能创建这些独立原资源；旧owner缺新方法时拒绝，不回退CN。
- 对MENU本身，新市场发布还须具备A1核验的CHANNEL/原价格政策语境；本地未声明支持的非CN销售保持关闭。原依赖资源没有餐牌语境，不能以CHANNEL存在作为它们的身份核验前置。创建顺序可以是原菜品/配料/套餐→原发布→餐牌/绑定/语境→组成→餐牌发布。
- 必改调用链包括DishServiceImpl（菜品维护）、ModifierServiceImpl/ModifierPublicationSource、OptionGroupServiceImpl及其SKU依赖、BundleServiceImpl/BundlePublicationSource、RecipeServiceImpl/RecipePublicationSource，以及ConfigRevisionServiceImpl共用的SKU收集循环。只扩展其原身份与发布解释，不增加库存扣减、制作或资金能力。
- 未覆盖SKU仍按原基准价；配料/套餐按原PriceLine策略，不新增汇率、加价率或优惠。金额单位来自原currencyScale：例如JPY无小数、KWD三位，不一律按两位解释；界面换币标签不得触发自动金额迁移。

**A2.2b1实施批次（2026-10-28）。** schema6专用PublicationJsonPolicy从原mapper重建严格读器，复用已严格实例、不使用全局缓存或修改原mapper；拒绝DTO嵌套未知字段及String/Boolean/Integer标量强转，保留精确数量字符串。ADDON事实集合须对应自身可选销售SKU，COMBO有根销售SKU时须携带其事实，同时保留原子项事实及免费无SKU能力。 在A2.2a真实市场事实合同之上，本批实现纯币种规则与自包含冻结格式：MenuPricePolicy新增normalizeStructure/normalizeForCurrency；旧normalize和price仍按CNY，显式阻止覆盖价与nativePrice币种混用。MenuCompositionDefinitions新增结构/币种入口供新格式使用，旧normalize保持CNY。新增PublicationMarketContext及PublicationMarketPolicy，从已验证的原市场证明生成稳定scope/market/currency/scale摘要和审计回执；历史校验不访问实时owner或检查旧证明期限。四类原Snapshot增加仅非null输出的marketContext并保留旧构造器；schema6按MENU/MODIFIER/OPTION_GROUP/BUNDLE/RECIPE分派，STATION仅5。新6的SKU/原商品scope匹配冻结context，MENU全部依赖及RECIPE内嵌选项组要求同市场/币种/精度和scope；不按schema6一个数字猜类型。旧1至5须无非null市场上下文，原字节和hash不变。验收覆盖五kind新6、跨市场/无context/错误kind拒绝、历史不依赖现时效、旧冻结字节/hash及非CNY本地计价阻断。此批仅纯文档/币种合同，不装配新开关、不切换原业务写入口或CURRENT价格捕获；ConfigRevisionService/原独立资源写入/重发布/来源策略须下一批一起接线后才声明可发布新6，不把本批纯格式支持冒充完整非CN维护和发布能力。

**A2.2b2 实施批次（2026-10-28）。** 统一接入原业务：新增CatalogCurrencyService/service.impl和每次操作独立的CatalogCurrencySession，默认shop.food.commerce.catalog-currency.enabled=false；无ThreadLocal/全局可变上下文。关闭时不查询新market合同且沿旧CN商品/SKU方法；开启时先在事务外取得原市场，实际CN仍走旧身份路径，实际非CN走A2.2a原getCatalogGoods/getCatalogSku并验证scope/market/currency/scale及最短30秒期限。菜品、配料、套餐、配方和菜单组成维护在原本地事务前读原owner，再复核市场；事务内只核对已捕获证据期限，不做远程调用。保留旧构造器、版本/幂等/日志/发件箱与独立库存口径。菜单本地读取先捕获市场；内部发布捕获仅解析结构并由发布者给币种资格，避免锁内网络。发布与重发布统一使用当前原商品身份，非CN冻结新6、父子依赖必须同上下文；MENU非CN只CHANNEL/DELIVERY并重新核验原A1绑定与价格政策，STATION非CN保持拒绝。历史读取只验证原文/范围，不查询现时owner；重发布必须重新取得当前市场并匹配原冻结口径，不自动重写旧文或金额。CURRENT来源允许4/6且新6再次检查实时原市场；原菜单价格读取支持4/6并核对新6与实时原商品税/价的市场语境，旧本地CNY计价限制保持。新增开关仅在原food模块装配，集成与独立模式仍复用同一Port/原服务令牌，不新增业务表、付款/库存扣减或真实供应商接线。代码及测试先整批编写编译再集中专项；一次市场/期限守卫消融，一次只读最终review。

c. 发布版本与兼容：

- 建议预留市场上下文发布格式content_schema=6（实施前核对最新占用），由configKind+schema共同判别：支持MENU、MODIFIER、OPTION_GROUP、BUNDLE、RECIPE的非CN只读身份/发布；STATION仍只用5，不在本批扩展。6不是旧数字分支可随意共用，所有kind必须有明确的类型化解析器。此编号属于fnb_config_revision，与后续cim_catalog_snapshot目录schemaVersion=3无关。
- 原选项/配料1、套餐2、配方3、MENU4和工位5的字节/hash及语义不改。新6在对应的PublicationSnapshot、BundlePublicationSnapshot、RecipePublicationSnapshot、MenuPublicationSnapshot中加入统一PublicationMarketContext；旧构造器保留、旧版本字段null且不序列化。上下文冻结原entity/store、market/currency/scale、稳定摘要及审计依据；新实际市场SKU事实由该上下文解释，不能把AE的SKU版本装成原CN事实。
- 对schema6依赖按(kind,schema)解析，校验依赖的原主体/门店/市场上下文与父餐牌一致。支持的组合为旧CN的kind+1/2/3/4及STATION+5、新市场的上述五种kind+6；未知组合拒绝。ConfigRevisionServiceImpl.frozen/revalidateHistorical等只按数字1/3/4分支的地方须同步改为kind+schema分派，不能让同为6的菜单和配料被读成同一种对象。
- 冻结的市场上下文仅说明发布时的解释口径，不能把30秒证明冻结成永久授权。历史解析只读原文/稳定字段；不得因今天市场停用、证明过期或商家配置改变而改写历史金额/拒绝合法历史文档。
- 新发布前取得原活市场事实，校验外部profile.market和全部覆盖币种；事务内只比较版本、定义及证据期限，不做网络调用。schema6的CURRENT捕获再按今天原市场重新核验，变化则拒绝并要求原重新发布。
- PublicationDocuments、Menu/Bundle/RecipePublicationDocuments、ConfigRevisionServiceImpl（所有kind发布、get/resolve、历史重发布和依赖重验）、MenuSourcePolicy、MenuCompositionDefinitions/Source及相关CatalogPolicy分支必须一起改。不能仅将“!=4”改为“允许6”而省略新上下文校验。
- MenuCatalogPricePolicy允许4/6原文，但价格始终读实时原商品价/税并匹配原市场；本地DiningPriceSourcePolicy/CatalogService若未支持该市场应明确阻断，绝不回退CNY计算。工位schema5不扩展；配方/套餐/选项组旧格式不变，实际非CN依赖使用明确的6分支。

存储复用原skuPrices JSON及fnb_config_revision.definition_snapshot/content_schema，不新建价格表。若实际数据库CHECK或结构验证器限制版本，另做增量放行，不能改已执行迁移，也不能给旧schema4原文直接加字段。

**L3 3. 行业来源分派。**

目的：CIM消费端只调用一个原校验入口，餐饮和将来零售通过各自提供方证明来源，不能因多个Bean或默认方法让核验失效。

| 层 | 拟定对象 | 职责 |
| --- | --- | --- |
| 唯一消费接口 | 原ChannelSourceValidationPort | validate(Source)和旧构造/调用签名保留 |
| 提供方合同 | commerce-api.spi.commerce.ChannelSourceValidationProvider | owner()->FOOD/RETAIL；validate(Source)->原Validation |
| 通用门面 | commerce-core.port.ChannelSourceValidationPortImpl | 按owner唯一路由，不引入food-api/core，不跨行业回退 |
| 装配 | CommerceSourceValidationAutoConfiguration | 有提供方才注册门面，默认无提供方时保持原关闭行为；放在贡献提供方的配置之后 |
| 原FOOD提供方 | food-commerce.port.FoodSourceValidationProviderImpl | 仅FOOD/MENU，用原ConfigRevisionService.verifyMenuResource的CURRENT证明，验证scope、原发布schema4/6、hash、期限和回执 |

输入MENU时sourceRef=menuNo、sourceRevision=原发布revisionNo、salesOfferRef必须为null。OFFER/SKU/OPTION等裸资源不能伪装成MENU；本批不做其历史映射授权。旧6参数Source调用保留，其他kind返回UNSUPPORTED，C2再补明确父语境。

启动/失败语义必须固定：

1. 无行业提供方：不制造默认成功门面，维持原“缺来源不可用”的行为；来源缺失不能触发外部网关调用。
2. 同owner两个提供方：启动失败；本地与远程同owner也不能同时激活。FOOD和RETAIL各一个可共存，缺RETAIL不会转查FOOD。
3. 只有一个旧自定义ChannelSourceValidationPort且没有新提供方：继续使用旧实现。旧实现与新owner提供方并存：明确启动失败，要求显式迁移为provider适配，不能通过ConditionalOnMissingBean静默丢弃新FOOD实现。
4. 原scope/字段非法沿BAD_REQUEST；缺能力为UNSUPPORTED；能确认修订不再CURRENT才为STALE；原404/403及传输故障按原合同保持，不把网络失败伪装成规则不支持或缓存为成功。
5. VALID必须精确返回canonicalRef/revision/hash/期限/ownerReceipt；receipt是原可追溯摘要，不是服务认证。远程只有Validation时必须明确它是受信owner答复，不宣称客户端能凭一个hash独立重建完整原快照。
6. FOOD provider配置显式默认false；不要求CIM核心也在FOOD进程中，独立FOOD可只提供其owner能力。通用CIM worker客户端和完整capture仍在C接线，不把A2提供方冒充完整目录Port。

提供方配置须声明在通用registry配置之前；不能仅以类型ConditionalOnBean依赖偶然加载顺序。CatalogPersistence等仍持ObjectProvider/延迟引用，注册表装配不得强迫构造跨域发布环。关闭FOOD/bridge或CIM仍保持原业务默认路径。

**A2.3 执行批次：行业来源统一分派（2026-10-28，代码与专项已验证，最终复核中）。**

本批沿已确认的 A2 设计，承接已交付的 A2.2b2 原 FOOD 多市场维护/发布，不重新设计商品或订单。先推送已完成提交，再执行以下任务。完整目录 v3 为 B 批，捕获/预览/发送接线为 C 批；A2.3 不能单独声明外送菜单已可同步。备选方案中，CIM 核心直接依赖餐饮会破坏行业隔离；多个同类型 Port 靠 Spring 选主会掩盖错误装配，均不采用。沿已确认的“唯一通用 Port + 行业 Provider”方案。

| 顺序 | 精确文件（相对模块 src/main/java） | 输入与输出/实现 |
| --- | --- | --- |
| 1 | commerce-api/ai/neargo/shop/spi/commerce/ChannelSourceValidationProvider.java | owner(): Owner；validate(Source): Validation；不得继承旧 Port，否则 Provider 也被发现为旧 Port，引起混合装配误判 |
| 2 | commerce-core/ai/neargo/shop/commerce/port/ChannelSourceValidationPortImpl.java | 构造 Provider 列表、Clock；不可变 owner 映射；validate 只按 owner 路由；重复/null owner 拒绝 |
| 3 | commerce-core/ai/neargo/shop/commerce/config/CommerceSourceValidationAutoConfiguration.java | 排在行业提供方之后、CommerceCoreAutoConfiguration之前；有提供方才注册 Port，无数据库依赖；检查所有旧 Port 与新提供方混合 |
| 4 | food-commerce/ai/neargo/shop/food/commerce/port/FoodSourceValidationProviderImpl.java | ObjectProvider/Supplier 原 ConfigRevisionService、ObjectMapper、Clock；只 FOOD/MENU；原当前版本与 CURRENT 证明核验 |
| 5 | food-commerce/ai/neargo/shop/food/commerce/config/FoodSourceValidationAutoConfiguration.java | food.enabled=true 且 shop.food.commerce.source-validation.enabled=true；默认关闭；原餐牌服务和 mapper 可用时才贡献提供方 |
| 6 | 两模块 META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports | 明确发现与顺序；通用核心只引用行业配置类名，不引入 food-api/core 的依赖 |
| 7 | 各模块 src/test/java 中专项 | ChannelSourceValidationRegistryTest、SourceValidationWiringTest、FoodSourceValidationTest、FoodSourceValidationWiringTest |
| 8 | 本 TDD、生成设计清单及执行台账 | 三处对账、真实编译/验收/消融/一次独立只读审查结果 |

Source 保留 entityNo/storeNo/owner/kind/sourceRef/sourceRevision/salesOfferRef，旧六参数构造和 Validation 原字段不变。通用输入按原业务编号边界校验；FOOD/MENU 的菜单与修订编号按原32位合同验证。首批 salesOfferRef 必须为空；SKU/OPTION/OFFER 等不因独立身份合法就被授予 MENU 来源资格。

具体流程：

1. 通用入口先校验输入，再按 owner 选择唯一 Provider。缺行业返回 UNSUPPORTED；不回退其他行业、不兜底成功、不调用外部渠道网关。
2. FOOD 提供方在本地事务外调用原 resolveRevision(entity,store,MENU,menu,now)，复核返回的主体/门店/原文、kind、摘要及生效口径。能够确认当前修订与请求不同才返回 STALE。
3. 同修订调用原 verifyMenuResource 的 CURRENT Query，使用 MenuSourcePolicy.validateProof 重新校验完整证明。schema4/6由原解析器解释；新6的实时市场核验由原服务负责，提供方不复制市场规则。
4. VALID 精确返回 canonicalRef/revision/contentHash/validUntil/ownerReceiptNo。通用入口核对请求身份、非空原摘要/回执、未过期且最长30秒；不能延长原证明期限。远程 Validation 是可信 owner 答复，摘要不是签名，不宣称可从缩减字段重建原快照。
5. 原404/403、网络故障和无法确认版本变化的 CONFLICT 保留原语义；不把所有异常当 STALE/UNSUPPORTED，也不缓存成功。无价格、税、库存或订单查询副作用。

启动矩阵：无 Provider 不注册默认 Port；FOOD/RETAIL 各一个共存；同 owner 两个、本地与远程同 owner、旧自定义 Port 与新 Provider 混合均启动失败；只有旧 Port 时保持原行为。提供方构造不读取原业务，原服务延迟到调用时解析，防止发布/目录服务循环。独立 FOOD 可以只贡献其原能力，不要求启用 CIM 数据库；独立 CIM 的远程提供方客户端仍在 C 批，当前不假装已具备跨进程完整捕获能力。

存储/API/任务：复用 cim_catalog_source 及原短时 Validation，复用现有消费 Port；本批不加表、迁移、公开 Controller、定时任务或实际平台适配。后续完整捕获仍须复核原证明；证明既不是永久销售权限，也不是资金或履约授权。

执行与验收：

- 先整批完成生产及测试源码，统一 test-compile，再集中执行四类专项与直接相关原目录/映射/装配回归。预期全部通过；默认关闭、无原 owner 与旧 Port 场景必须覆盖。遵从用户批量编码后编译测试的节奏，不先执行全仓回归。
- 输入反例覆盖跨主体/门店、非法/未知 kind、带 salesOfferRef 的 MENU；输出反例覆盖错原编号/修订/hash、过期/超期、伪造或错范围证明。测试原错误透传、明确旧修订 STALE 与真正未知故障的区分。
- 装配覆盖无 Provider、双行业、重复行业、旧实现单独/混合、默认关闭、FOOD 独立且无 CIM 数据源；确认配置发现顺序，防止仅凭 ConditionalOnBean 偶然可用。
- 对明确的一条期限或行业守卫做消融：移除后对应测试必须失败；恢复后专项通过。一次独立只读 review，重要问题一轮修复，以失败复现和相关绿测闭环。
- 编译验证集成宿主、独立 FOOD 和独立 CIM；无 POM/共享依赖改动时不重复无关默认零售全仓测试。规范检查包括相关架构、生成设计10项、25个生成器、SQL/diff。
- 精确提交本批文件和文档；不自动开启生产开关、不部署。B 批接完整 v3 目录及时间交集，C 批接捕获/预览/发送，S3接自动滚动重发布；真实 Keeta 等仍需后续获准合同和环境。

**L3 4. 文件与方法落实清单（已实施子集见A2.1/A2.2a，其余待实施）。**

| 模块 | 范围 |
| --- | --- |
| shop-base spi.product/media | 原GoodsCatalogQueryPort/GoodsCatalogInternalApi增图片及市场元数据方法；严格DTO/验证策略；窄职责MediaReferenceQueryPort |
| 原商品owner/portal.internal | GoodsCatalogQueryPortImpl的RR/外读/RR；原InternalGoodsCatalogController增内部方法；不在商品Controller直接查媒体Mapper |
| 原媒体适配层 | 严格MediaReferenceQueryPortImpl，复用原MediaStore/出口规则和MediaAssetQueryPort；不改GC MediaKeys算法 |
| food-channel / food-core.port | CoreGoodsCatalogQueryPortImpl及LazyGoodsCatalogQueryPortImpl转发完整新方法；只读PLATFORM目标，无降级CN |
| food-api/core | MenuPricePolicy纯入口、服务端MenuCurrencyContext及冻结PublicationMarketContext；菜品/配料/选项/套餐/已绑定配方的原身份与发布；kind+schema新6和旧1至5兼容、真实市场事实与原事务边界 |
| commerce-api/core | 唯一provider SPI、门面/注册表装配与旧Port并存检查；原持久化引用兼容 |
| food-commerce | FOOD/MENU provider、独立开关及原证明复核；不注册完整目录成功提供方 |

新接口一律沿原内部token/Scope签名链、严格JSON和错误状态约定；不增加公开价格/图片管理API。A2不预设新业务表，不改变商品/订单/库存所有权，不启用Keeta或其他真实平台。

**L3 5. 验收矩阵与交付顺序。**

| 工作组 | 主要验收 | 拟定测试 |
| --- | --- | --- |
| A2.1 图片身份 | direct/server/裸key受管往返；外部同路径URL拒绝；跨店/私有/歧义拒绝；NONE与不可核验区分；商品改图STALE；期间变更拒绝；内部URL与摘要不被重写 | CatalogImageResolutionTest、MediaReferenceQueryPortTest、CatalogImageInternalApiTest、RemoteCatalogImageTest |
| A2.2 市场与发布 | 无餐牌时也能用AE/TW独有SKU创建原配料/套餐/关联配方，再发布并被渠道餐牌引用；跨市场拒绝；CN旧路径原样；币种不符拒绝；JPY/KWD最小单位；旧1至5字节/hash不变；新6按kind分派且跨市场依赖拒绝；历史不访问当前owner，CURRENT重验；本地下单限制不变 | CatalogMarketGoodsTest、MenuCurrencyContextTest、DependencyPublicationMarketTest、MenuPublicationCurrencyTest、MenuCurrencyCompatibilityTest |
| A2.3 来源分派 | FOOD/RETAIL正确路由；重复/混合旧实现启动失败；无provider默认关闭；缺行业不回退；老Source构造兼容；MENU_CURRENT旧修订STALE；未知kind不授权 | ChannelSourceValidationRegistryTest、FoodSourceValidationTest、SourceValidationWiringTest |

先完成A2.1/A2.2原能力及相应测试源码，再接A2.3；整批编码结束统一编译默认、集成、独立形态，再集中测试受影响功能。旧金额/历史快照与默认关闭是必须验收的兼容项，不能用文档守卫代替业务测试。本轮仅设计复核，不执行上述拟定Java测试。

**L4 已明确的取舍。** 图片跟随原餐牌发布语义，换真实key需重发布；受管别名变化可证明后等价。市场扩展仅面向渠道餐牌，不借此开放本地非CNY支付；格式6是拟定未占用的市场上下文格式族，需实施前复核且按kind分派；不扩展STATION。严格身份解析与价格上下文可能拒绝历史脏数据，应由原业务修正，不做静默猜测。S3自动重发布、全量v3及真实平台仍按后续批次处理。

独立设计复核：一位只读复核者指出“独立配料/套餐等资源无CHANNEL语境但仍依赖CN查询”的实施阻断；已按原代码补齐上述服务端市场路径、原发布器及kind+schema的上下文兼容设计。未要求新的业务规则，未修改业务代码；不把本轮文档检查当作上述拟定功能已通过测试。

### 明确的事务和并发

- 原请求journal区分PLATFORM/ENTITY/STORE，平台配置不伪造经营主体。账号授权operation允许尚无connection，按ACCOUNT/CONNECTION非空规范键去重。原请求journal与本域配置写入在本地事务；同键异payload或不兼容操作者冲突。
- inbound event、original receipt和待处理意图先提交再ACK。Outbox投递失败与事实写入原子回滚；重复到达查回原event，冲突报文另存receipt。
- claim/发送前意图持久化、网络调用、回执保存分别执行；进程在发送后崩溃按UNKNOWN处理。外部支持幂等才用原键重发；否则先查，无法确认则转人工。
- 同远端发布范围串行覆盖平台完整异步执行期和图片等可写子任务，SUBMITTED/UNKNOWN设置activeOperation阻断后续发送；lease过期只换worker，不能释放远端阻断。新期望只排队，generation在获准发送时分配。仅原任务明确完成/取消或协议有序覆盖证明可解除；无查询能力保持阻断并告警，人工reason不是完成证明。锁序scope/业务资源→operation；attempt/fence/lease及generation同时校验。ACK早于HTTP返回也通过已持久化关联查回，无法匹配时待核对而非丢弃。
- 平台无事件修订号时基于可信订单查询和允许的状态迁移处理乱序，不按到达时间覆盖；先到取消留阻断事实，后到新单不得误派工。
- 平台新增/支付/库存/退款事件不覆盖原历史金额，历史目录/映射无法确定则阻止自动受理。

### 来源与金额核验合同

ChannelSourceValidationPort.validate显式核验typed资源类型、主体/门店、sourceOwner、sourceRef/sourceRevision，返回规范身份、冻结hash、有效期限和原回执。商品/SKU/类目由原商品能力提供，仓库及库存策略由独立库存能力提供；宿主跨域组合只能调Port，不跨库读表。无提供方明确UNSUPPORTED，不能默认true。

手工映射先DRAFT；原对象和修订通过核验后，MANAGED对象可用本模块已受控分配的外部代码及原发布依据验证，IMPORTED对象须有可信远端查询/订单证据证明身份。capture完整目录时、prepareImport正式导入时再次核验冻结来源，不只在创建映射时验证一次。

原成交价格由行业prepareImport调用原定价/订单来源合同核验。平台原订单与获准渠道快照匹配，能被原价格、商家/平台优惠、费用、税口径及原行分配解释的历史价可以合法导入，不要求等于当前零售价；平台无目录修订号时必须取得足以消除歧义的原行证据。各协议定义必需金额事实族，缺源保留UNKNOWN并阻止自动资金/履约准入；不得用当前报价覆盖或把平台配送费重复计入商家销售。公共请求不能直接授予CHANNEL_ORDER价格权威。

### 装配与配置

候选配置统一shop.commerce.enabled、deployment=embedded/standalone；独立server默认关闭真实平台调用，具体Profile装配由架构测试验证。默认宿主不带新构件，集成profile加载web/core/channel；独立profile只保留公开web及typed客户端，server负责core/channel及内部Controller。内部原始回调也需平台验签，不能只信转发服务。

单一CommerceRecoveryJob复用原调度锁，负责有界检查、漏同步、UNKNOWN和原导入恢复；按到期紧急程度及每店预算避免菜单堵住接单。不新增每平台cron，不以静态重试次数替代协议幂等判定。经营日报仍按既定方案最后开发/启用。

### 设计清单与未来开发批次

| 批次 | 可交付闭环 | 源码与合同 |
|---|---|---|
| C0 | 五模块、账号连接、关闭与双部署 | 权威边界/注册表/凭据引用/迁移归属 |
| C1 | 餐饮和零售目录、自动手动同步 | 完整scope、历史映射、价格真源、非幂等恢复 |
| C2 | 原生外部订单到厨房或零售履约 | 原核心订单/库存、接单及付款准入、Outbox |
| C3 | 多包裹、取消、退餐退货等 | 原物流/售后合同扩展，原行数量金额守恒 |
| C4 | 获准资金证据、券核销及账单 | 原资金/权益owner先补合同；独立能力门禁 |
| C5 | 真实平台与独立服务切换 | 官方协议/测试账号、单写者及未决动作交接 |

2026-10-26用户已确认执行，按以下细分任务持续推进。每批遵循用户要求：先整批业务与测试源码，再统一编译各形态，之后集中受影响测试，最后一次提交推送部署；修复只相关复测。不能据候选表/API机械生成全域空壳。

### 当前执行分解

1. C0基础切片：新增五模块与显式commerce-embedded构建；账号草稿、连接草稿及不可变修订、请求journal使用MyBatis及独立迁移。实体不得出现在公开响应；无授权不能ACTIVE；草稿认领全局远端身份也须可信归属证明，缺提供方拒绝。所有公开管理入口沿B端上下文且首批仅主体属主。
2. C0协议基础：网关注册表拒绝重复profile、未知能力；稳定远端身份由协议命名空间决定；不注册任何真实平台默认成功实现。
3. C1并发基础：全量发布的generation、active operation及SUBMITTED/UNKNOWN阻断以纯策略实现并测试；接入持久化run前不对外开放发送。
4. 验证：先写身份/范围/并发/幂等与迁移测试源码，整批完成后`mvn -o ... -DskipTests test`编译，再集中执行commerce专项测试；原平台及food回归只覆盖改动装配。
5. 后续闭环：授权operation/安全凭据、来源验证和scope/snapshot/run持久化→owner原订单导入合同→履约售后；逐批补真实端点，不把计划中的70路由标为上线。C4/C5按原门禁保留。

Review Focus：重放异payload与异actor；重复连接跨主体；无网关时不能激活；未决远端结果不能被worker超时解锁；默认关闭不执行迁移。

### C0授权闭环执行计划（续批）

- [x] A1 先写授权/回调单次消费/撤权/范围及未知结果测试源码；沿用用户要求，全部编码后统一编译再集中测试。
- [x] A2 增加V2迁移：cim_authorization_revision、cim_operation、cim_operation_attempt；V1冻结。账号增加active_operation_no串行化授权和撤权；operation增加回调消费时点、公开授权URL及固定回跳目标码。授权凭据只保留安全存储引用。
- [x] A3 ChannelAuthorizationGateway独立typed SPI，无真实提供方。authorize/revoke先持久化原意图及journal、再在事务外发送，返回固定初始响应，前端GET operation查询进度和授权URL；同键不再次调用。UNKNOWN不盲重发。
- [x] A4 回调按state摘要查原动作，校验平台/原账号/期限，锁账号后锁动作一次消费；先落attempt再交换临时代码。原网关必须验证实际外部账号、原凭据存储和回执，成功后锁定同一fence及账号操作版本追加授权修订。早到回调不会被晚到发送结果覆盖。回调不接受主体或任意URL，也不回传凭据。
- [x] A5 公开路由COM-006/007/008/035/070；B沿原属主与STORE_ADMIN；callback以原String响应绕过现有wrapper，OAuth state证明与协议交换由网关共同验证，不新增通配免鉴权。当前无真实gateway仍拒绝授权，独立远程形态继续关闭。
- [x] A6 扩展迁移结构校验、原生MySQL验收与设计真源；统一编译集成和server，再集中专项、一次独立复核、修复后本地提交。目录来源、同步worker和UNKNOWN主动恢复留到后续同一任务，不伪称完成。

Review Focus：同键异账号；回调伪造/过期/重放；授权实际账号不匹配；URL包含密钥或任意回跳；发送超时不重发；早到回调不被晚HTTP结果回退；撤权未确认不恢复ACTIVE。

### C1目录同步执行计划（续批）

- [x] C1.1 先写目录范围/来源核验/快照/同步阻断/恢复及HTTP测试源码，整批编码后统一编译，再集中专项。
- [x] C1.2 V3增量迁移六表scope/source/policy/snapshot/run/run_item；V1/V2冻结。首版remote_scope_key固定STORE，由服务器确定，同一连接唯一。来源仅FOOD或RETAIL且与连接一致；保存时事务外核验原owner，事务内锁连接/范围复核版本，禁止混合目录。
- [x] C1.3 类型化ChannelCatalogSourcePort捕获完整已发布目录，原价/税/配料/套餐/时段均由owner给证据，不重新定价；ChannelSourceValidationPort逐来源核对规范身份和修订、hash、期限和回执。预览保存不可变规范快照、来源证明及连接修订，4MiB/10000销售条目上限；首版payload_json存本域表、payload_ref为DB业务引用，以后再迁对象存储。
- [x] C1.4 COM-015～026共12路由：范围创建/读写、策略读写、预览、手动提交/查询/明细和取消未发任务。保留主体属主和门店核验；查询用数据库分页，发送前必须重新核验来源、快照hash/期限、连接ACTIVE/授权CATALOG及有效期。
- [x] C1.5 手动/自动共用QUEUED run。dispatch获得范围锁才分配generation与active operation，提交attempt后事务外发送。SUBMITTED/UNKNOWN或未终结可写子任务持续阻断；恢复只按原operation查询，不盲重发；终结PARTIAL允许后续全量修复，lastConfirmed只记历史全量成功。
- [x] C1.6 typed发布通知按原事件键去重并核验新修订，合并尚未发送的自动意图；定时策略、queued dispatch和原任务query共用CommerceRecoveryService有界入口。单个CommerceRecoveryJob沿原JobHandler注册，默认不启用；首批仅目录，onAvailability不开放，不新增每平台cron。
- [x] C1.7 统一集成/server编译和打包，集中受影响专项、原生MySQL迁移/唯一键、消融与一次独立复核，修复后本地提交。真实FOOD/RETAIL来源提供方、获准外部Gateway与连接激活适配未接入时不伪造目录或发送；本批不宣称真实平台联验。

Review Focus：跨店/跨主体/混合来源；陈旧或过期来源与报价；预览后改配置；UNKNOWN不重复发送；lease过期不解除远端阻断；晚attempt/generation不覆盖新状态；PARTIAL不是全量一致；默认关闭无job与mapper。


### C1资源映射执行计划（续批）

- [x] M1 先编写ResourceMappingTest及HTTP/JSON专项：草稿不用于订单解析；跨店/主体/混合来源拒绝；父身份隔离；历史修订不跟随当前指针；旧核验回执不能激活新草稿；缺凭证/过期证明不通过。整批编码与测试源码→编译→集中受影响专项，遵循用户交付节奏。
- [x] M2 V4仅新增resource_mapping/resource_mapping_revision两表，V1～V3冻结。映射含可信store、不可变外部identity、latest/active修订、核验lease/operation/next_check；修订只追加，草稿与已核验事实不原地改写。公共写入仅IMPORTED草稿；MANAGED须将来由受控发布代码提供，不接受客户端声明。
- [x] M3 COM-027～030四个B路由沿主体属主/STORE_ADMIN/当前门店原目录核验；Service数据库分页及严格过滤。销售条目、SKU/选项关系由扩展ChannelSourceValidationPort显式核验salesOfferRef并返回规范条目；原六参数调用兼容，不让旧provider默认核验新增语义。
- [x] M4 ChannelResourceIdentityGateway只执行可信原平台资源查询，不修改平台；同profile唯一注册，缺真实提供方保持草稿。核验前提交原operation/attempt及lease，网络外调用来源和远端，完成时锁account→connection→mapping→operation/attempt复核原最新修订、连接修订、授权、lease/fence和证据期限。超时只重查；迟到只留证，不激活新草稿。
- [x] M5 ResourceMappingVerificationService.verifyDue按next_check_at有界扫描，共用唯一CommerceRecoveryJob，不新增平台cron。已关闭策略不授予映射核验权；核验须ACTIVE连接/有效CATALOG授权。未知保留DRAFT；成功追加VERIFIED修订并移动active，修订来源错误不影响既有历史事实。修订新草稿先移除当前active指针，禁止继续自动使用旧配置。
- [x] M6 ChannelResourceMappingPort.resolveHistorical必须显式mappingNo/revisionNo，拒绝DRAFT及当前版本回退；原行业重新核验冻结来源/销售条目/hash和期限，返回历史映射事实，不授予价格/库存/付款/接单资格。旧远端证据是历史依据，不伪称当前对象一致；订单仍须原owner.prepareImport。
- [x] M7 统一宿主/server编译打包、受影响Java/HTTP/原生MySQL迁移和约束、设计/文档守卫、消融及一次独立复核；修复只相关复测，本地白名单提交。原food MenuPublicationSnapshot明确不授予价格/库存权威，本批不凭它直接注册完整目录成功适配器；真实来源桥接、事件投递、外部订单Webhook和激活仍单独交付。

Review Focus：同远端子编号不同父身份不能串线；手工草稿不得成为VERIFIED；source/销售条目证明不匹配；旧证明晚到及新草稿期间不得激活；核验lease不能授权新版本；来源和远端查询不可跨事务；历史解析不得偷偷查最新映射或把过期当前凭据当历史有效证明。

## §3 选型与取舍

1. 统一commerce域替代零售/餐饮各自平台系统；外部交易接入与内部资金/库存归属保持独立。
2. 授权账号和店铺连接分开，避免一家多店重复Token及限流错误。
3. 独立业务表和迁移，但当前同库；未来拆服务不依赖原跨域事务。只有关联与证据，没有第二套订单或资金账。
4. 确定性结构化真源生成数据库/API/代码三清单，原API生成器不能把PLANNED路由扫描成已实现。
5. 对照上一版delivery方案，统一改名commerce；fnb既有运行表不直接重命名，后续兼容迁移。发货/券/账单是能力扩展，不强制每个平台全部实现。

## §4 风险与保留边界

| 风险 | 处理 |
|---|---|
| 未取得平台账号协议或测试环境 | 标UNVERIFIED，候选Gateway不注册默认成功 |
| 原订单/支付/权益能力不足 | 在原owner补合同，当前能力不开放 |
| 混合行业订单与跨主体连接转让 | 首版关闭，需专项归属/资金方案 |
| 临时授权回调或消息重复冲突 | state绑定原主体/操作者/账号及期限、一次消费，持久化原nonce摘要；冲突另存证据 |
| 发布部分失败或旧ACK | 分状态、generation及逐项结果；不伪称远端仍是旧完整版本 |
| 账号级账单含其他门店 | 主体财务权限；门店只读已归属行，无归属不强分摊 |

本设计默认的首版单行业连接、31天查询窗、20/100分页、预览TTL配置、限流细节可由实现者按平台容量测试微调，修改须同步真源及文档；不改变权限和金额口径。平台具体凭据/地区协议属于外部前置，不是用户需现在提供的开发阻断。

## §5 对账三 计划验收

| AC | 计划测试 | 运行/消融 |
|---|---|---|
| AC01 | ChannelAuthorizationScopeTest#rejectCrossEntityAccountAndReplayState | NOT_RUN；未创建Java实现，集中测试阶段补证据 |
| AC02 | ChannelConnectionIdentityTest#credentialRotationKeepsRemoteBusinessIdentity | NOT_RUN；未创建Java实现，集中测试阶段补证据 |
| AC03 | CatalogSynchronizationTest#fullStoreScopeSourcesAndSnapshotAreVersionedAndScoped、retailUsesSameKernelAndCannotMixFood；CatalogDocumentPolicyTest | C1内核子集PASS；真实来源提供方和平台适配未联验，不标全量AC完成 |
| AC04 | CatalogSynchronizationTest#submittedAndUnknownKeepRemoteBarrierUntilTerminalProof、lateSenderCannotOverwriteRecoveredPartialOrReleaseNewGeneration及复核4项 | C1持久化阻断/恢复子集PASS；真实远端异步协议仍待适配器合同验证 |
| AC05 | ResourceMappingTest#trustedProofAppendsVerifiedRevisionAndOldRevisionRemainsFrozen、legacySourceProofCannotConfirmSalesOfferMembership、historicalResolutionUsesOriginalConnectionOwnerAfterRouteRevision | IMPORTED历史映射及来源核验内核PASS；MANAGED原发布代码、真实行业/平台合同及订单导入准入未验 |
| AC06 | ChannelInventoryPublicationTest#rejectCallerQuantityAndUseOriginalOwner | NOT_RUN；未创建Java实现，集中测试阶段补证据 |
| AC07 | ChannelWebhookRecoveryTest#lostResponseDoesNotRepeatNonIdempotentAction | NOT_RUN；未创建Java实现，集中测试阶段补证据 |
| AC08 | ChannelOrderImportTest#preserveExternalOriginalLinesAndAmounts | NOT_RUN；未创建Java实现，集中测试阶段补证据 |
| AC09 | ChannelAcceptanceTest#unknownAcceptanceNeverDispatchesKitchenOrShipment | NOT_RUN；未创建Java实现，集中测试阶段补证据 |
| AC10 | ChannelFulfillmentTest#publishOnlyOriginalCompletedQuantities | NOT_RUN；未创建Java实现，集中测试阶段补证据 |
| AC11 | ChannelAfterSaleTest#refundRequestDoesNotBecomeMoneyOrStockReceipt | NOT_RUN；未创建Java实现，集中测试阶段补证据 |
| AC12 | ChannelRedemptionTest#recoverOriginalCertificateConsumption | NOT_RUN；未创建Java实现，集中测试阶段补证据 |
| AC13 | ChannelReconciliationTest#unknownAllocationCannotLeakAcrossStores | NOT_RUN；未创建Java实现，集中测试阶段补证据 |
| AC14 | ChannelStatisticsTest#excludeUnknownAndCancellationFromSuccessDenominator | NOT_RUN；未创建Java实现，集中测试阶段补证据 |
| AC15 | CommerceDeploymentTest#sameContractsWithLocalAndRemoteProviders | NOT_RUN；未创建Java实现，集中测试阶段补证据 |

S1子集验收：AC03/AC05对应MenuSourceVerificationTest的8项（原scope/EXPLICIT与ALL成员/当前与历史/原组及选项/非法状态/依赖SKU/分类销售条目）；AC15对应RemoteMenuSourceVerificationTest的3项和InternalMenuSourceApiTest的2项。共13项新增专项，连同26项原发布/远程合同回归累计39个唯一Java专项，0失败0跳过。当前修订检查消融使`currentRejectsOldAndFutureRevisionsWhileHistoryPreservesOriginalFacts`真实失败，恢复后8项来源专项全部通过。测试是H2事务、typed客户端Mock与真实服务令牌过滤链的MockMvc，不冒充生产数据库并发或真实跨进程端到端验证。

前轮只验证文档生成、引用及结构一致性，不把设计守卫当业务验收。当前已新增Java子集，按每个AC登记真实源码/用例/执行范围及必要消融，不回填虚假绿灯。

S2B2B2c-1子集验收：AC03由FoodCatalogBindingServiceTest核验原冻结文档、精确外部身份及当前来源二次读取，AC15由FoodCatalogBindingWiringTest核验默认关闭、缺来源不装配、自定义优先、默认宿主依赖隔离及真实Boot Jackson装配。新增15项加既有CommerceArchitectureTest两项，累计17个唯一受影响专项通过；不与重复复测次数相加。移除原发布内容比较后变化专项真实失败，恢复后全模块专项通过。独立复核发现JSON装配顺序缺陷，真实Boot测试先RED（缺服务），补afterName后新模块15项全部GREEN。默认零售干净test-compile48.064秒、集成/独立test-compile约63秒；最终打包证据见交付附记。此处为原API组合与真实Spring装配，不宣称生产数据库并发、独立进程或真实渠道联验。

A1子集验收：AC03由FoodChannelPricePolicyTest的7项、MenuChannelAuthorityPortTest的4项、ChannelCatalogAuthorityResolveTest的5项和CatalogMarketSourceTest的5项覆盖；AC15由FoodCatalogPricePolicyWiringTest的2项、两端新增HTTP方法及原令牌/客户端/架构回归覆盖。累计76个唯一受影响Java专项通过（新增27、相关49），不累加重复复测次数。连接复查守卫消融使期间新增匹配连接专项真实RED，恢复后GREEN。独立复核1项Important：暂停营业管理被市场读误拦，新增真实原owner回归先RED FORBIDDEN，再分离市场读与实际取价的状态要求；相关19项GREEN。此处是H2原Mapper、原API组合及本地真实HTTP/令牌链，不代表生产JDBC并发或真实外卖联验。最终构建和交付记录另附。

A2.1子集验收：AC03由MediaReferenceQueryPortTest#resolvesOnlyOriginalExportsAndExplicitAliasesToSameIdentity、#arbitraryHostsSignedEncodedPrivateAndAmbiguousReferencesAreRejected、CatalogImageQueryTest#differentFrozenAssetCannotBeReplacedByTheCurrentCover、#onlyActuallyAbsentOriginalCoverCanBecomeNone及#originalGoodsChangesDuringMediaVerificationAreRejected验证原封面身份和变化；CatalogImageFactsTest验证严格codec、范围、顺序、版本、期限和摘要。AC15由InternalCatalogImagesApiTest、CoreCatalogImagesTest#standaloneCapabilityUsesActualTypedHttpWithOriginalTokenAndUnchangedReferences、LazyCatalogImagesTest及MediaReferenceQueryPortTest#normalSpringWiringRetainsOptionalOwnersAndUsesOnlyConfiguredAliases、#internalImageFactsRemainOutsideThePublicMediaRewriteRegistration覆盖。新增27项GREEN38.293s；直接相关30项亦通过，累计57个唯一专项。移除商品RR重读守卫后变化检测真实RED1，恢复后本批27项全部GREEN。此为原Mapper/H2、原token的MockMvc及本机真实typed HTTP，不代表生产并发或真实外卖供应商验收。最终打包和review证据另附。

A2.2a子集验收：AC03由CatalogMarketGoodsTest#actualMarketSelectsMetadataWithoutTaxPriceInventoryOrCnFallback、#originalMarketChangeBetweenMetadataReadsIsRejected、#metadataSkuAndParentVersionChangesAreDetectedOutsideTransactions、#pausedStoresCanReadManagementMetadataAndUnsellableGoodsRemainReadable及#boundedExpansionIsCompleteSortedAndRestoresCallerScope验证原市场/元数据/事务边界。CatalogMarketFactsTest验证原币种精度（AED/TWD/JPY/KWD）、严格嵌套codec、原版本、期限、范围及回执；不是实际金额试算。AC15由InternalCatalogMarketApiTest、CoreCatalogMarketTest#standaloneBothCapabilitiesUseActualTypedPlatformHttpAndOriginalToken、LazyCatalogMarketTest及CatalogMarketGoodsTest#normalSpringWiringResolvesOriginalMarketLazilyAndPropagatesItsOutage验证令牌、原始协议、真实typed HTTP、旧能力兼容及默认可选装配。新增25项GREEN46.862s，另48项直接相关回归通过；累计73个唯一专项。实际市场比较守卫消融后市场变化用例真实RED1，恢复后25项全部GREEN；最终打包/架构及review另附。测试为H2/原Mapper、MockMvc及本机真实HTTP，不代表生产并发、外送平台或FOOD非CN发布已就绪。

A2.2b1子集验收：AC03由MenuCurrencyPolicyTest（结构/币种分离、整数最小单位、CNY不混币）、PublicationMarketContextTest（新建原证明范围/时效、历史独立、稳定上下文及严格codec）、PublicationCurrencyDocumentsTest（五kind新6、同币种跨市场依赖拒绝、配方嵌套组及字典双轴归属）覆盖；AC15兼容子集由PublicationCurrencyCompatibilityTest覆盖。旧1至5共六种kind的字节/hash基线在源码首次编译前取自aa48f6575的已编译旧类，运行捕获器显式确认旧类没有marketContext访问器。新增22项GREEN6.128s；旧直接相关84项中83通过、1项nativeConcurrentPublicationsHaveOneWinner因未配置FOOD_TEST_DISPOSABLE跳过，不伪称原生DB联验。冻结市场比较守卫消融使同币种跨市场菜单依赖测试真实RED1，恢复后22项全部GREEN。当前累计105个唯一用例通过、1个条件跳过，最终架构及打包证据另附。此为纯文档/规则和旧发布计价回归，不代表原业务已能创建或发布非CN资源。

## §6 对账二 设计到实现

全量目标保留70个API、31张候选表。当前实现进度以结构化真源implementation子集为准：五模块、累计29个基础/授权/目录/映射/任务/回调路由、17张迁移表、13个业务Service（含内部只读授权事实）与本域journal、身份和持久化远端发布阻断策略。公开请求严格拒绝主体/凭据与未知字段；Service另校验、列表按数据库分页。该内核已集成发布；新增S1原FOOD内部核验、S2A共享基准价/税来源、S2B1餐牌价格投影及后续授权/日历/媒体已随4ef107f4a推送；不据此声明部署。通用授权和目录同步内核已交付；真实来源/授权/外部适配器、MANAGED发布映射、外部订单导入、履约、售后、资金证据仍待后续闭环。不能将整个AC01、AC02或AC15标为已全部通过。

## L4 官方资料及待核验

[Uber Marketplace](https://developer.uber.com/docs/eats/introduction) · [foodpanda餐饮POS](https://integration.foodpanda.com/) · [Keeta菜单全量异步](https://api-docs.mykeeta.com/apis/standard/menu/openitemcode-based/menusync) · [Keeta订单](https://api-docs.mykeeta.com/apis/standard/docs/orderintegrationguide) · [Talabat POS](https://integration.talabat.com/en/documentation/) · [Deliveroo订单](https://api-docs.deliveroo.com/docs/order-integration) · [淘宝ERP对接](https://open.alitrip.com/docs/doc.htm?articleId=118848&docType=1&treeId=1) · [抖店](https://op.jinritemai.com/home) · [抖音生活服务核销](https://developer.open-douyin.com/docs/resource/zh-CN/local-life/develop/capability/basic/verify-introduce)。

引用仅支持平台存在对应开放体系及已核验语义，不代表获准账号或所有市场能力。本文不是外部API实时镜像；接口请求/响应、Webhook与状态转换必须在真实适配器实施时按获准协议冻结合同测试。

## 历史设计阶段核验记录（编码前）

2026-10-26仅文档及生成合同范围：`node --test scripts/tests/commerce-integration-design.test.mjs` 9通过、0失败；`vitest run --root packages/shared tests/doc-standard.test.ts` 8通过、0失败。`node scripts/gen-commerce-integration-design.mjs --check`和JavaScript语法检查通过，git diff --check无空白错误。反例覆盖重复路由、未知表、未知AC、重复字段，并验证账号级动作范围、远端异步发布阻断和来源核验合同。不是Java业务测试或外部平台联验。

独立builder复核提出并已补齐：首版混合目录与单责任方冲突、账号授权尚无连接的动作作用域、显式来源及历史价格核验合同、覆盖远端异步子任务的全量发布阻断。复核后无重要待回答问题；单行业连接是本版默认边界，不等于平台只服务一个行业。关系SVG已渲染检查，技术索引已更新。

执行日志：/private/tmp/commerce-design-contract-final.log、/private/tmp/commerce-design-doc-standard-final.log。该历史阶段全部业务API、库表及Java类仍PLANNED，未提交推送、未执行建表、未部署；当前状态以§6及下面交付记录为准。


## 首批C0草稿切片交付记录（历史，2026-10-26）

### L1 实际行为

已落地五模块和8个B端基础路由（COM-001～005、009～011），不是完整70路由。账号创建固定DRAFT；连接即使是草稿，也须可信ChannelConnectionIdentityPort返回精确绑定主体、本店、账号/协议修订和远端身份的有效归属证明，才可事务性认领全局唯一键。当前未注册真实提供方，因此不能创建生产连接或启用外部接单。凭据不能从公开JSON传入。现有零售/餐饮业务没有改动。

### L2 交付范围

| 项 | 实际交付 |
|---|---|
| 模块 | commerce-api/core/web/channel/server；仅commerce-embedded显式依赖进入宿主 |
| 数据 | 六张V1业务表及独立cim_flyway_history；未执行生产迁移 |
| API权限 | 现有STORE_ADMIN再要求主体属主；按BizContext/当前门店头/原门店目录核对；尚未开放委派权限 |
| 幂等 | 请求journal与业务事实同事务；异操作者/异参数冲突，重复返回冻结响应，不重新查询远端归属 |
| 隔离 | 宿主ComponentScan与MapperScan均排除commerce，自身配置关闭时不注册Mapper、不迁移 |
| 来源归属 | 事务外核验归属证明，写事务锁账号/协议重验修订及有效期，保留原回执与时间 |
| 同步基础 | 唯一网关注册表；generation/active operation阻断纯策略，不冒充已持久化同步流程 |
| 独立形态 | server能构建打包，尚无独立业务客户端/内部业务端点，不能直接宣称拆分可用 |

### L3 验证证据

按照用户要求先整批源码及测试源码、再统一编译、再集中专项；修复只重跑相关范围。

- `mvn -o -f backend/pom.xml -Pcommerce-embedded -pl shop-app,:commerce-server -am -DskipTests test`：集成宿主、五模块及测试源码编译通过。
- 同一reactor指定ChannelIdentityTest、CatalogPublicationBarrierTest、ChannelFoundationPersistenceTest、CommerceArchitectureTest、CommerceWebContractTest、CommerceGatewayRegistryTest、CommerceMapperScanIsolationTest：29通过，0失败、0跳过；无全仓业务测试。
- `node scripts/verify-commerce-schema-mysql.mjs`：本地已有镜像、无网络、tmpfs临时MySQL，六表原生迁移和全局身份/原操作者/JSON快照验证通过，容器已清理；没有业务数据库凭据。
- `node --test scripts/tests/commerce-integration-design.test.mjs`：10通过；文档标准8通过；生成文件、SQL方言及git diff --check通过。
- 复核两项问题均有RED→GREEN：原草稿可抢占远端店，修复为无可信证明不认领；真实宿主全局Mapper扫描在关闭时仍注册渠道Mapper，修复后缺席。另测证明过期、核验期间账号修订变化及重放不重调远端。

### L4 尚未交付与实施决定

后续仍是授权operation/安全凭据与原协议目录管理→来源验证、目录scope/snapshot/run与自动/手动恢复→原owner订单/厨房或拣货履约→售后回传。真实Keeta等、资金、券与经营报表维持原门禁。未开发前端，未推送或部署。

本批决定：首版管理只向属主开放，代价是不能先委派给店长；协议与远端归属均须可信核验，代价是真实身份提供方未接入前连接创建关闭；journal响应保存服务端编码LONGTEXT JSON以兼容MySQL/MariaDB/H2，代价是数据库原生JSON验证由服务端编码保证。旧“只整理设计”内容已标明历史阶段，不能覆盖本次实际进度。

验收边界：原生SQL CLI不等于生产驱动JDBC/Flyway全链路；standaloneSetup HTTP不等于真实宿主完整认证链；纯发布阻断策略不等于已完成并发worker。上述在对应后续闭环补充，当前没有宣称已通过。

日志：/private/tmp/commerce-review-fix-compile.log、/private/tmp/commerce-review-fix-tests.log、/private/tmp/commerce-native-mysql-final.log、/private/tmp/commerce-design-final.log、/private/tmp/commerce-doc-final.log、/private/tmp/commerce-sql-final.log。


## C0授权续批交付记录（当前，2026-10-26）

### L1 当前能力

新增COM-006/007/008/035/070：授权发起、撤权、授权历史、动作详情和OAuth回调。累计13个路由、9张业务迁移表。新增ChannelAuthorizationService/Impl、ChannelOperationService/Impl、typed ChannelAuthorizationGateway与三组Entity/Mapper；没有真实平台网关注册，也没有前端页面。

### L2 状态与边界

| 动作 | 执行与结果 |
|---|---|
| 授权发起 | 版本校验和账号串行锁→journal/operation/attempt同事务提交SENDING→事务外网关begin→SUBMITTED或UNKNOWN；接口固定返回初始响应，当前结果通过动作GET查询 |
| 授权URL | 来源必须登记、参数只接受既定公开OAuth白名单、state精确匹配、redirect_uri绑定服务器登记地址；未登记return_url/api_key等不持久化也不公开 |
| 回调 | nonce摘要找原动作，核对平台、账号、期限，在账号锁→动作锁内单次消费；code/error仅保存摘要，同state异payload冲突；事务外交换临时代码，按fence确认原账号授权及凭据安全引用 |
| 授权历史 | 追加不可变授权修订和原回执；current结合当前账号、活动修订及有效期计算，不返回credentialRef |
| 撤权 | 只针对保留的已确认授权，允许ACTIVE或重新授权被拒的AUTH_REJECTED；仍核对版本及未决动作。确认才REVOKED并清活动引用；未知保持REVOKE_UNKNOWN，不能自动恢复ACTIVE |
| 幂等与乱序 | 同键返回冻结初始响应，不重复调用；早到回调的SUCCEEDED不被晚到begin结果回退；UNKNOWN按原动作保留，不盲重发 |
| 回调响应 | 固定协议JSON的String响应沿原wrapper例外透传；B端保持原ApiResult包装。回调不接受主体、凭据或任意URL |

V1逐字保持冻结。V2新增授权修订、动作、调用证据三表，并给账号增加active_operation_no；私有Flyway校验升级v2，原平台Flyway归属不变。当前业务库未执行迁移。

### L3 验收与复核

- 集成宿主、五模块、独立server及测试源码统一编译通过；只运行本批及受影响专项，未跑全仓业务回归。
- Java专项44通过、0失败、0跳过：ChannelAuthorizationTest 13，ChannelFoundationPersistenceTest 12，CommerceArchitectureTest 2，ChannelIdentityTest 3，CatalogPublicationBarrierTest 3，CommerceWebContractTest 7，CommerceGatewayRegistryTest 2，CommerceMapperScanIsolationTest 2。
- 验证nonce伪造/过期/单次消费、外部账号不匹配、同键异账号、敏感与未登记URL、原grant撤权、撤权未知、早回调晚响应、UNKNOWN原动作晚到确认；网络调用均断言无本地事务。
- ChannelAuthorizationTest#unsafeAuthorizationUrlIsNeverReturned消融：临时移除敏感参数拦截后从UNKNOWN误变SUBMITTED，测试RED；立即恢复并最终GREEN。扩展过期夹具统一UTC LocalDateTime，避免本地Timestamp偏移。
- 独立只读复核提出2项Important，均有真实失败用例后一次修复：URL黑名单改公开参数白名单；重新授权被拒后允许撤销保留的旧授权。4个回归RED→GREEN，没有遗留Minor。
- 临时无网络MySQL验证V1+V2九表、稳定店铺键、journal原操作者、动作scope/state唯一键及授权修订唯一键通过，容器清理。它不是生产JDBC并发验证。
- 设计生成合同10、文档规范8、原SQL方言及空白检查通过。

核心证据：/private/tmp/commerce-auth-final-package.log（最终打包通过）、/private/tmp/commerce-auth-compile.log、/private/tmp/commerce-auth-review-red.log、/private/tmp/commerce-auth-review-green.log、/private/tmp/commerce-auth-url-ablation.log、/private/tmp/commerce-auth-mysql.log。

### L4 取舍与下一步

本批采用固定初始响应加动作查询，以保证幂等重放不会重调外部服务；代价是前端需查询动作获得当前状态。公开OAuth参数采用保守白名单；新增平台字段须通过适配器合同扩展。授权效期必须明确且未来有效，回调目前固定state/code/error；永久授权或其他回调形态不能直接注册。重新授权被拒不推断旧grant仍可本地使用，但允许申请外部撤销，未知撤权继续隔离。

真实平台真实性、凭据安全存储、外部账号证明及撤权确认由获准typed适配器提供，没有默认成功实现。真实认证链启动、生产驱动并发、独立远程客户端、UNKNOWN/崩溃/过期恢复worker和凭据轮换清理仍是后续门禁。

下一批继续Task2的餐饮/零售目录来源核验、catalog scope/source/snapshot/run持久化、手动/自动共用同步任务和有界恢复；其后才是原owner订单及履约。Keeta等真实平台继续只规划，不启用资金或券，不推送部署。


## C1目录同步内核交付记录（2026-10-26）

### L1 本批结果

新增六表与COM-015～026十二路由；目录范围、来源修订、预览、策略、手动排队、自动通知、恢复和明细均有实际Service/Mapper。累计十五张业务迁移表、二十五个已编码路由。V1/V2保持字节不变，只新增V3。价格、税、配料组合、套餐、图片和餐牌时段由原行业typed来源返回，渠道不创建第二份商品/订单/库存账。

### L2 功能与调用

| 操作 | 核心方法 | 约束 |
|---|---|---|
| 全店范围及来源 | CatalogScopeService.createScope/saveSources | 一个连接一个STORE；FOOD/RETAIL互斥；主体/门店及可信来源核验 |
| 冻结预览 | CatalogPublicationService.preview | 最长五分钟；4MiB/10000条；完整文档和原价/税/库存策略引用；配置版本与连接修订冻结 |
| 手动同步 | SyncRunService.submit/cancelQueued | 提交与发送分别进行；只能取消QUEUED；未决外部动作不能人工reason解锁 |
| 自动策略 | SyncPolicyService.savePolicy、CommerceRecoveryService.onPublished | 默认关；发布通知原键去重、事务外原owner核验、合并尚未消费通知；建任务事务内复核策略版本和enabled |
| 恢复 | CommerceRecoveryService.recoverDue、CommerceRecoveryJob | 单个默认关闭平台Job；最多100项一批，通常50；恢复只查询原动作；阻断队列不挤占原动作查询预算 |
| 状态/明细 | getScope/getRun/listRuns/listItems | lastConfirmed是历史；当前配置/连接修订不同不标CONSISTENT；分项仅记录平台实际返回 |

首版定时核验当前已选修订、缺少本地发送的意图和原任务状态，不具备自动发现新source head或读取远端实时菜单漂移的合同；来源改版由原owner的可信发布通知推进。onAvailability=true明确拒绝，未接库存变化事件。关闭策略阻止新自动意图，不取消此前已提交任务；可单独取消QUEUED。策略保存、自动提交和恢复按account→connection→scope→run→operation顺序串行，同一范围策略修改也由scope锁保护。

### L3 验证与复核

- 初次受影响Java专项59项通过；另补4项目录文档规则、4项复核/关联回归，累计67个唯一专项均已通过；修复后仅复测目录/HTTP相关23项，0失败0跳过。没有执行全仓后端测试。
- 复核三项Important及一个关联边界均先复现再修复：队列饥饿、旧成功覆盖新配置STALE、捕获中关闭策略仍建任务、相同内容但新配置被自动去重跳过。原失败与修复通过日志分别在/private/tmp/commerce-catalog-review-red.log、commerce-catalog-binding-red.log、commerce-catalog-review-final-green.log。这些回归同时证明移除对应保护会失败，不以镜像断言替代业务场景。
- 过期预览、来源hash变化、跨主体/混合行业、缺授权、原价格非法、超时不重发、迟到attempt只留证、PARTIAL历史确认、配料价格/库存依据、套餐环、跨夜时段及严格HTTP JSON均有源码验证。
- 原生隔离MySQL V1/V2/V3十五表及唯一约束通过；不使用业务库凭据。H2事务场景与MySQL CLI DDL证据不冒充生产JDBC并发验收。
- 最终宿主/独立server编译打包、设计生成10项、文档规范8项及SQL方言守卫以末尾最终核验记录为准。

### L4 保留门禁与下一批

本批不接真实Keeta或其他平台，不注册默认成功来源/网关，不开放连接激活；C0 OAuth的UNKNOWN/过期/崩溃恢复尚未由本目录worker接管。真实行业来源桥接、历史资源映射、外部订单Webhook持久化/验签/ACK、接单及原订单导入仍是后续任务，之后再接履约/售后。独立服务远程客户端/内部端点、生产驱动并发、真实认证链与供应商协议另验。

已完成一次独立复核并关闭三项Important，关联稳定哈希边界同批修复；不做第二轮复核。当前开发期间分支并入了打印发布提交，渠道交付仅提交本批白名单文件，不改动或回退打印工作。未推送、未生产建表、未部署。


最终核验：集成宿主shop-app与独立commerce-server的`-DskipTests package`通过（/private/tmp/commerce-catalog-final-package.log）；修复范围23项通过（/private/tmp/commerce-catalog-review-final-green.log）；MySQL扩展约束通过（/private/tmp/commerce-catalog-mysql.log）；设计生成10项、文档8项、SQL方言与`git diff --check`通过。共67个唯一受影响Java专项证据由原59项集中、目录文档4项与新增4项回归组成；复测不重复计入。V1/V2对原交付提交无差异。执行本地文件白名单提交，不推送部署。


## C1资源映射内核交付记录（2026-10-26）

### L1 结果与范围

新增COM-027～030四个B路由和V4两张表；累计二十九个已编码公开路由、十七张业务迁移表。沿commerce-api/core/web/channel/server和原B端主体属主、STORE_ADMIN、当前门店及StoreDirectoryPort边界。没有前端改动，没有复制原商品、订单、库存、凭据或资金账。

### L2 功能说明

| 能力 | API / 执行入口 | 行为 |
|---|---|---|
| 映射列表 | COM-027 listMappings | connection/type/externalRef/sourceRef筛选；sourceRef按latest，数据库分页；父身份参与稳定远端hash |
| 历史修订 | COM-028 listRevisions | 不可变修订倒序返回；草稿和已核验事实分开，不以latest冒充active |
| 创建草稿 | COM-029 createMapping | IMPORTED DRAFT；公共请求不接收主体、核验成功、MANAGED或证据字段；同键重放初始结果 |
| 修改映射 | COM-030 reviseMapping | 保持外部身份不变，追加草稿、清active；终止旧只读核验的本地权限，未完成attempt记UNKNOWN；保留旧VERIFIED历史 |
| 后台核验 | ResourceMappingVerificationService.verifyDue | 先原operation/attempt和lease，再事务外查原来源及原平台；双证明有效且原配置/授权/version仍匹配才追加VERIFIED；原共享Job默认关闭 |
| 历史解析 | ChannelResourceMappingPort.resolveHistorical | 显式mappingNo/revisionNo，限定原entity/store/connection；原连接修订owner和LOCAL语义不随当前改路由而变；重新核验source/offer/hash，返回原事实 |

核验只依赖原来源Port、资源身份查询Gateway和有效CATALOG授权，不要求目录发布Gateway。目录发布仍单独检查发布Gateway，不能因为映射能查就允许菜单发送。销售条目、SKU、选项组/选项映射必须有规范salesOfferRef关系证明；Source/Validation兼容旧六参数构造，但旧provider的null规范销售条目不授予新语义。

映射失败或缺真实provider时保持草稿，原操作记UNKNOWN并延后只读重查。不会自动创建REJECTED结论或把未知解释为不存在；REJECTED仍是后续运营显式裁决候选。MANAGED代码分配/发布证明未启用。销售价格、税、库存、付款和接单是否成立仍由原业务/订单prepareImport确认；历史远端receipt仅是原核验时的证据，不代表当前远端对象未变化。

### L3 验证及复核证据

- 初次集中受影响Java专项75项全部通过；补审计/旧provider和复核/历史路由4项，累计79个唯一专项均通过；最后仅复测相关35项，0失败0跳过。未跑全仓后端测试。
- 独立复核一项Important：只读核验误依赖发布网关。commerce-mapping-review-red.log记录无发布Gateway时通过数为0；修复拆分authorized与ready，commerce-mapping-review-green.log通过，同时缺发布Gateway仍拒绝菜单提交。
- 同批补历史连接改为RETAIL/REMOTE后的解析回归：原FOOD/LOCAL修订继续可解析，新写入不绕过当前归属/LOCAL门禁。旧行为CONFLICT已复现，修复后通过。审计操作者错误亦先复现SYSTEM再修复为原actor；未完成核验的本地终止有状态断言。
- 消融删除canonicalSalesOfferRef关系检查时，legacySourceProofCannotConfirmSalesOfferMembership红灯（commerce-mapping-offer-ablation.log）；恢复后相关专项通过。含父身份隔离、重复键、源hash漂移、来源失效、无授权、迟到回执、严格JSON及参数化sourceRef过滤。
- 隔离临时MySQL V1/V2/V3/V4十七表及原身份/journal/操作/快照/映射父身份/修订唯一约束通过（commerce-mapping-mysql.log）；V1～V3对eb6ef11ee无差异。
- 宿主与独立server编译打包、设计10项、文档8项、SQL守卫及diff检查，以末尾最终记录为准。H2事务与MySQL CLI DDL不冒充生产驱动并发或完整认证链验收。

### L4 取舍、门禁与下一步

复核一项Important已在一次修复中关闭，不重复派发复核。原来源adapter、真实远端资源规范身份、凭据存储/激活、生产JDBC并发、完整认证链和独立远程客户端仍是明确门禁。原food MenuPublicationSnapshot的schema声明不授予价格/库存权威，不能直接转成自动成功来源；下一批先补原行业可信来源/事件桥接，再推进原始Webhook验签、落库ACK和原订单导入。当前调用只读查询，未连接Keeta等真实平台。

本批只在现有独立worktree本地文件白名单提交；不执行本批生产迁移、远端推送或部署。打印发布提交保留，不在渠道提交中改动。


最终核验：`shop-app`与`commerce-server -DskipTests package`通过（/private/tmp/commerce-mapping-final-package.log）；修复范围35项通过（/private/tmp/commerce-mapping-review-green.log）；MySQL十七表及约束通过（/private/tmp/commerce-mapping-mysql.log）；设计10项、文档8项、SQL方言守卫与`git diff --check`通过。累计79个唯一Java专项按原75项集中加4项新增边界计数，重复复测不重复计入。完成一次独立复核，无未修复Important；保留实际行业/供应商及生产并发门禁。本批白名单35文件，仅本地提交。


## 2026-10-26 集成发布及后续任务（当前）

### L2 已发布边界

统一分支与线上构建均为`b076524b6607b2e93663adce57ad2d35af74578f`。发布前已合并远端现有支付/打印等变更，没有强推。完成79项受影响Java专项；修复合并后血缘对照与错误码文档的两项守卫，相关27项通过；正常pre-push通过既有基线并从干净提交编译测试源码，按用户要求没有重跑全后端测试。

使用正式CommerceSchemaValidator/Flyway执行V1–V4，再以VALIDATE校验；17张业务表与私有历史表均位于原订单/商品业务库。主应用CORE589、FOOD25，health=200/UP，actuator完整gitSha匹配。独立支付服务PID及启动时间未变化。生产正常模式为VALIDATE，真实渠道网关与自动同步任务保持关闭。

证据与备份/回退依据见[发布记录](./acceptance/commerce-integration/release-2026-10-26.json)。完整库备份已完成压缩完整性检查，尚未进行恢复演练。这里的部署健康不代表获准账号的外卖端到端验收，也不代表独立部署已经具备内部业务客户端。

### L2 下一步：行业原始来源与发布事件桥接

S0现状review、S1原FOOD来源证明、S2A共享基准价/税来源及S2B1餐牌价格投影已完成；S2B2A无损目录合同已完成；S2B2B原行业适配及S3事件桥接继续待开发。以下未标完成项不代表已有提供方可用。先完成来源/事件，再执行原设计C2回调和订单导入，不以人工导入映射替代原来源核验。

| 现有对象/实现 | 可以复用 | 仍需补齐 |
|---|---|---|
| FOOD ConfigRevisionService.getRevision/resolveRevision；ConfigRevisionInternalApi与RemoteConfigRevisionService | 原主体/门店范围、不可变内容/hash、按生效时间选取；集成与独立读合同已有 | 渠道语境的来源证明及menu item→销售条目→SKU/选项关系投影；不能仅返回文档就宣称可发布 |
| MenuPublicationSnapshot schema4 / MenuPublicationDocuments | 原分类、餐牌、时段、选项/套餐依赖、SKU归属与原始结构 | 原价/税/可售承诺依据；结构快照不提供这些授权 |
| DishOfferQueryPort / DishOfferQueryPortImpl | 原商品、门店价格和库存读取 | 当前LOCAL CN/CNY限制；渠道目录需原owner输出币种、价格修订、税依据与回执，不在渠道层改写为AED/TWD |
| MenuChannelAuthorityPort | 原账号归属及渠道价策略核验契约 | 真实连接/授权证明适配，无默认成功实现 |
| ConfigPublished / FoodOutboxRouting | 已持久化原发布事实与原事件身份 | 当前FOOD_CONFIG_PUBLISHED为archive-only；有条件地接消费路由，关闭commerce不得阻断原餐饮发布 |
| CommerceRecoveryService.onPublished / 原请求journal | 已有来源更新、按scope合并意图及幂等入口 | 可信事件消费接线、过期/未来/重复/乱序事件策略、跨服务失败后的原键恢复 |

#### L3 顺序与验收

- [x] S0 核对现有原owner读接口、价格边界、事件路由及模块依赖；明确已部署能力与未接线能力。
- [x] S1 原来源证明：在原FOOD能力上补充类型化资源投影，复用getRevision/resolveRevision，不另建餐牌主数据。输入entityNo、storeNo、menuNo、publicationRevisionNo及明确资源kind/ref/salesOfferRef；输出原revisionNo、contentHash、canonical resource/sales-offer关系、证明期限和原回执。当前核验须匹配当前生效修订；历史查询只证明原不可变资源，不授予当下销售资格。旧提供方不支持新合同应关闭处理。
- [x] S2A 原商品基准价与税来源：共享CatalogPriceSourcePort及原owner实现、CORE内部HTTP、独立FOOD typed客户端完成；仅提供CATALOG_BASE，不替代最终下单试算。
- [x] S2B1 原FOOD餐牌价格投影：复用CURRENT发布证明和共享原价/税事实，组合条目覆盖、配料与套餐单价，保留原精确用量并提供可远程重算的内部合同。
- [x] S2B2A 无损目录v2：保留v1 JSON及摘要，支持按规格规则、精确小数、日期例外、显式未知库存，并完成冻结/数量/版本派发门禁。
- [x] S2B2B1 原连接/CATALOG授权事实：原平台/账号/连接当前修订、有效授权与身份核验，支持本地/独立调用。
- [x] S2B2B2a 原营业日历来源：原门店营业时间、营业日切点、未来策略时间线和日期例外，支持本地/独立调用。
- [x] S2B2B2b 共享原公开媒体来源：原台账完整归属及元数据、稳定HTTPS和短期回执，通用远程读取与规范URL保护。
- [x] S2B2B2c-1 原餐牌渠道绑定核验：可选food-commerce桥接，精确匹配原平台/外部账号/门店，CURRENT双读、最短期限及默认关闭装配；仅身份关系，不授予价格政策或完整发布资格。
- [x] A1 原连接解析与价格政策：原resolve/门店市场事实、原写前核验、唯一策略证明，默认关闭；不等于完整目录或真实渠道接通。
- [ ] S2B2B 原行业完整目录适配：复用S1/S2A/S2B1原来源及价格事实，接原连接授权和餐牌渠道语境、时段与例外、展示与可信媒体，交付ChannelCatalogSourcePort.Document及原来源校验提供方；补齐独立worker客户端和非CNY覆盖价的原owner策略。原配置或币种/税依据缺失保持不可发布；原库存未知保持UNKNOWN，不回填0，不在目录捕获时预占。
- [ ] S3 事件桥接：复用原OutboxEnvelope、事件号及CommerceRecoveryService.onPublished；消费前核对源系统、schemaVersion、aggregate和原门店/餐牌/修订。事件只是重读提示，不直接信任事件中的价格或把旧修订写成最新。重复沿原eventNo查回；乱序重新解析当前生效修订；未来生效应在到期核验，不能消费后丢失后续唤醒。新增路由须按模块是否装配条件化，禁用commerce时原发布继续成功。
- [ ] S4 集成验证：批量完成代码与测试源码，再编译food+commerce集成宿主及独立server；只运行受影响专项。覆盖跨主体/门店、菜单内同SKU不同销售条目、REPLACE空配料、套餐依赖修订、hash篡改、未来/取消/过期发布、缺币种/税/价格回执、事件重复/乱序/重试、模块关闭。最后一次独立review并处理问题，随后白名单提交。

#### L3 文件与接口责任清单

原owner改动落在food-api的dto/service/spi、food-core的service.impl及policy，远程适配沿food-web/foodclient和food-server/api原结构；商品报价扩展落在shop-base/spi/product与shop-core/product/port，税仍走原trade端口。对外平台适配继续位于commerce-channel，不导入原food-core实现。编码前在对应TDD小节登记最终类和方法，不为未实现合同预注册成功Bean。

来源证明与目录Document复用现有cim_catalog_source、cim_catalog_snapshot、cim_request_journal及原fnb_config_revision；本批不新增第二套商品/订单表，也不为来源事实再建可编辑副本。需要新增持久化字段时另增Flyway版本，不修改已发布V1–V4。S1–S3内部合同不计入29条现有公开路由；如新增HTTP内部合同，需同步服务令牌鉴权和服务端/客户端合同测试。

S4完成后才进入C2：先原始回调验签和持久化ACK，再进行原owner.prepareImport、平台接单确认及厨房/零售履约，后续仍按PRD/ADR042处理资金、售后和统计边界。Keeta等真实账号与SDK继续为后续获准协议适配，不是本批启动前置。


## S1原餐牌资源核验交付记录（2026-10-26，本地）

### L2 已实现

新增一个FOOD内部POST合同，复用ConfigRevisionService、原服务令牌和fnb_config_revision。支持MENU/CATEGORY/OFFER/SKU/OPTION_GROUP/OPTION六种冻结资源；输入输出、CURRENT/HISTORICAL及父归属规则见§2。公开commerce路由仍为29条，业务表仍为17张；该FOOD内部路由独立登记，未伪称完整目录适配器已经注册。

原owner同一REPEATABLE_READ快照核验修订、scope、hash和资源关系；当前证明最多30秒并截断下一已知生效时点。远端一次typed调用后重验Query、原文与规范资源、期限及摘要，超时不当成功。原未支持提供方默认NOT_FOUND。

### L3 需求/设计/代码对账

| §2设计单元 | 实际文件及结果 |
|---|---|
| 严格请求/响应 | 新增food-api/dto/MenuSourceContracts.java及policy/MenuSourcePolicy.java；单独保留optionGroupRef与salesOfferRef |
| 原服务与持久化 | 扩展food-api/service/ConfigRevisionService.java和food-core/service/impl/ConfigRevisionServiceImpl.java；只读复用ConfigRevisionMapper，无新表/Mapper/迁移 |
| 内部HTTP与远程读 | 扩展food-api/spi/ConfigRevisionInternalApi.java、food-server/api/InternalConfigRevisionController.java、food-web/foodclient/RemoteConfigRevisionService.java |
| 验收 | 新增core/MenuSourceVerificationTest.java、web/RemoteMenuSourceVerificationTest.java、server/InternalMenuSourceApiTest.java；39个唯一Java专项通过 |
| 范围 | 10个Java文件及本TDD；生成索引随生成器更新；无前端或真实平台启用 |

### L3 验证及复核

先批量写代码与测试源码、再集成编译、再统一专项。第一次独立服务编译读到旧本地shop-base SNAPSHOT；随后使用临时聚合POM把backend、food-channel、food-server纳入同一reactor，完整当前源码编译通过，没有改变项目生产模块结构或安装旧缓存冒充成功。

- `/private/tmp/menu-source-red.log`：初始4项因默认NOT_FOUND失败，证明新能力原本不存在。
- `/private/tmp/menu-source-boundaries-red.log`：非法发布状态、ALL误包含依赖SKU两项真实失败；收紧后均通过。
- `/private/tmp/menu-source-focused-green.log`：38项受影响Java专项通过；补齐分类/销售条目正反场景后`menu-source-core-final.log`来源8项通过，累计39个唯一用例。
- `/private/tmp/menu-source-ablation-red.log`：移除当前修订比较，指定旧修订测试失败；`menu-source-ablation-restored.log`恢复后通过。
- 设计/清单守卫25项、生成物25组与diff检查通过，最终打包及提交记录见本批最后回执。
- 一次独立最终复核：无Critical/Important或Minor，不再重复派发复核。复核后仅删除无关格式化差异及补正向分类测试，业务逻辑保持一致。

### L4 实施决定与后续边界

当前修订选择由原FOOD服务在可信令牌通道内证明；远端自包含原文可以重验hash和成员关系，但不能凭一份文档重建完整发布时间线。若原owner选择逻辑出错，客户端无法仅靠摘要纠正，故保留原服务测试及发送前再核验要求。

跨进程时间校验采用严格UTC有效期，不新增时钟偏差宽限；部署节点需同步时钟，偏差可能使来源证明被安全拒绝。证明摘要不是签名或资金凭据。

S2下一步先扩充原商品报价的市场/币种、价格修订及税依据，再组合餐牌覆盖价、配料和套餐价格；S3随后接入原Outbox发布事件。完整ChannelCatalogSourcePort/ChannelSourceValidationPort提供方尚未装配，当前不会向真实外卖平台发送菜单。已取消历史来源继续拒绝新证明，历史订单仍沿原冻结订单事实解释。本批无生产数据库变更，不改变上一批已部署版本。

最终回执：`menu-source-final-package.log`集成宿主、food-server和commerce-server统一源码打包BUILD SUCCESS（30.870秒）；包标记为local-menu-source-verification，不作线上发布件。消融恢复后的来源8项PASS，累计39个唯一相关Java用例，设计/清单25项、文档70份、生成物25组及diff检查通过。一次独立复核无重要问题或延后Minor。本批本地白名单12文件提交；无推送、无部署、无数据库迁移。


## S2A共享目录基准价与税来源交付记录（2026-10-26，本地）

### L2 功能与对账

AC03的原价依据和AC15的本地/远程合同子集已实现，完整AC仍待行业目录和渠道联验。请求仅原主体、门店及SKU集合，市场/币种/税均由原owner提供；真实门店价优先，空覆盖价沿原逻辑回退主体SKU价。明确零价和未知价分离，所有存在的商品、SKU及覆盖行必须有真实版本。既有结账、活动券和库存能力保持原实现。

本批共11个Java文件：shop-base新增CatalogPriceSourcePort、CatalogPriceSourceFacts、CatalogPriceSourceInternalApi及FactsTest；shop-core新增CatalogPriceSourcePortImpl及PortTest；shop-app新增portal.internal.InternalCatalogPriceSourceController及scenario.InternalCatalogPriceSourceApiTest；food-channel新增CoreCatalogPriceSourcePortImpl及其Test、修改FoodOfferClientConfiguration。方法、包名和责任见§2的S2A合同表。原五模块17张业务表/29条公开路由不变，仅新增1条共享CORE内部路由；无SQL迁移、前端、渠道启用或支付改动。

### L3 验收证据

- 27个唯一业务/接口专项通过、0失败0跳过：FactsTest 5、新原owner 9、原DishOffer 4、新内部HTTP 2、原内部HTTP 1、新远程适配4、原远程适配2。包括真实本地HttpServer与InternalHttp服务令牌调用，但不等同真实跨服务部署验收。
- 门店可见性守卫消融后，originalStoreVisibilityDoesNotLeakThroughOtherStoreOrEntityOverlayRows真实失败；恢复后只复测该项，1项通过。缺版本的门店覆盖行另有失败再修复证据，不能把已有无版本行伪装为无配置。
- 默认零售模式5项相关架构检查通过。集成FOOD模式同组检查4通过、1失败：domainsMustNotTouchWebRuntime报告24条依赖，均来自未修改的food-web原FoodKdsStreamService及FoodKdsStreamServiceImpl使用SseEmitter/ResponseBodyEmitter。未放宽架构守卫；未运行干净基线构建，不把此处描述为基线运行证据。ARC-SSE-01后续单独将SSE传输移回API/宿主边界，保持业务Port隔离。
- 一次独立只读代码复核无确认的Critical/Important问题；无第二轮复核。统一编译、最终打包及文档生成检查以本批末尾核验记录为准；不运行全量后端测试。

### L4 取舍与下一步

内部令牌/status传输放在宿主portal.internal，避免商品领域引入Servlet依赖；将来拆分商品服务时需重新装配入口。来源事实为短时点证据，不是锁价、锁税、库存预占或付款资格；前后两次跨域核对仍不能消除ABA及读取后变化，下单写入前必须沿原业务再次确认。摘要只校验内容完整性，不代替服务身份认证或签名。严格UTC期限沿S1合同，节点时钟未同步可安全拒绝。

下一批S2B：基于S1已发布资源证明和本批共享事实，在原FOOD能力组合餐牌覆盖价、配料与套餐增价，形成完整ChannelCatalogSourcePort.Document；继续保留未知库存，不自动叠加本地活动。随后S3接入原可靠发布事件。真实外卖渠道、C2订单导入等仍待办。本批仅本地提交，不推送、不部署，不改变线上b076524b6。

最终核验：同一源码临时reactor以food-embedded、commerce-embedded完成shop-app、food-server、commerce-server的clean package（跳过重复测试），BUILD SUCCESS，1分05秒；仅本地验证标记，不作为生产发布SHA。设计及清单25项、V2文档70份、生成物25项及diff检查通过。集成架构既有SSE失败保持上述记录，不宣称全绿。


## S2B1原餐牌价格投影交付记录（2026-10-26，本地）

### L2 已实现与调用

原FOOD MenuCatalogPriceService.capture(Query)按指定当前发布修订生成完整餐牌价格行，集成模式使用原本地Service，独立调用沿FOOD服务令牌HTTP。新增内部POST `/internal/food/menu-catalog-prices/capture`，仅接受entityNo、storeNo、menuNo、revisionNo四个原编号，拒绝调用方币种、价格、税率及未知字段。

输出Facts包括schemaVersion、原query、menuProof、productFacts、prices、verifiedAt/validUntil/receiptNo。每条PriceLine保留menuItemNo、mainSkuNo、groupNo、choiceNo、kind、skuNo、unitPriceMinor、unitsPerPick、sourceRevision。MAIN/OPTION使用当前原价或本条目合法覆盖价，SPEC_VALUE和BUNDLE_INCLUDED为明确0，BUNDLE_ADD使用选中SKU单价，BUNDLE_DIFFERENCE为max(选中单价−基准单价,0)。unitsPerPick是原精确十进制字符串（套餐沿原三位小数合同）；投影不提前计算每选一次后的四舍五入金额，最终按原结算数量公式计算。

明细修订绑定可信市场/币种、餐牌内容hash、item/主SKU分支/group/choice、原商品修订及税hash；即使免费规格也不跨市场复用修订。整份回执绑定所有原证据和明细。接收端校验原scope/当前菜单证明/完整SKU集合/税事实/期限，再重算全部价格行，重新计算一个摘要不能把改价或漏行变成合法目录。

原菜单上架与选项关系、EXPLICIT/ALL成员、套餐三种模式和同SKU不同item语义保持原合同。配料使用其原销售SKU，不求和原料成本；本接口不返回库存承诺或扣库存，不选券/活动、不计算订单总税、不确认支付。

### L3 需求、设计、代码和验收对账

| 需求/设计 | 实现与方法 | 实际专项 |
|---|---|---|
| AC03 原价格及餐牌组合 | food-api.dto.MenuCatalogPriceContracts；policy.MenuCatalogPricePolicy的productRequest/compose/seal/validate/receipt | MenuCatalogPriceServiceTest的16项：市场、独立item、配料/免费规格、套餐三模式/数量、未知/0/覆盖、币种拒绝、归属、篡改、超限、ALL与期限 |
| AC03/AC05 原当前发布依据 | food-core.service.impl.MenuCatalogPriceServiceImpl.capture，前后调用ConfigRevisionService.verifyMenuResource | currentSourceMustBeRecheckedAfterOriginalPriceRead；原MenuSourceVerificationTest 8项回归 |
| AC15 原服务接口和装配 | food-api.service.MenuCatalogPriceService；spi.MenuCatalogPriceInternalApi；food-core.config.FoodAutoConfiguration新增Bean；food-web.foodclient.RemoteMenuCatalogPriceService与FoodClientConfiguration新增Bean | 新远程3项含真实本地HttpServer/typed InternalHttp令牌；原远程3项；FoodConditionalWiringTest 8项 |
| AC15 内部传输 | food-server.api.InternalMenuCatalogPriceController.capture/businessFailure | 新内部HTTP 2项含实际SecurityFilterChain；原内部HTTP 2项 |
| 原结账兼容 | 未改DiningPriceSourcePolicy或MenuPricePolicy | DiningPriceSourcePolicyTest 6项 |

本批实际12个Java文件（10新增、2装配修改），另更新原TDD与索引；没有新Mapper、数据库迁移、公开API、权限/i18n/配置键。共48个唯一相关Java业务/接口专项通过，0失败0跳过（新增21、原回归27）。4项相关集成架构规则及1项条件装配守卫通过。上一批已确认的厨房SSE隔离问题ARC-SSE-01仍待单独修复，本批未重跑该无关失败项，未放宽守卫。

### L4 边界和下一步

S2B1是完整原餐牌价格投影，不是完整ChannelCatalogSourcePort.Document，也没有注册实际commerce目录提供方。S2B2继续接原连接授权/餐牌渠道范围、时段与例外、商品展示/媒体证据、独立worker的原来源客户端装配以及原餐牌非CNY覆盖价维护扩展；未具备的能力明确拒绝，不把本投影冒充平台菜单已同步。S3可靠原发布事件桥接随后执行。

实施取舍：原FOOD不反向依赖commerce-api，通过原Service合同供后续适配器转换；现有CNY覆盖维护及结账保持兼容，非CNY覆盖不做汇率或标签替换；单菜单只接受最多100个实际销售/增价SKU，超过上限整份拒绝，避免无一致性保证的分批事实；最多20000条投影行。时点事实不是锁价/库存/支付授权，前后检查无法消除ABA或读取后变化，原下单仍须重算。回执摘要不代替服务身份认证。严格UTC期限沿S1/S2A，节点时钟不同步可安全拒绝。

本批仅本地提交，不推送部署、不改变线上b076524b6及任何支付开关。独立复核、消融和最终打包以随后核验记录为准。

独立只读复核一次，无确认的Critical/Important或延期Minor。复核不运行构建，实际48项和编译结果由执行侧日志核验；本地测试不冒充线上令牌配置/原owner可用性。原服务身份是信任边界，若可信owner本身被攻破，内容摘要不能防止其同时伪造事实和摘要，未在本切片引入新的签名协议。

最终核验：商品归属守卫消融后originalProductScopeAndGoodsOwnershipCannotBeRewrittenBySourcePrices真实失败（期望异常未抛），原样恢复后仅复测该项，1项通过；免费规格市场修订专项也有先失败再修复证据。同源码临时reactor以food-embedded、commerce-embedded完成shop-app、food-server、commerce-server的clean package（跳过重复测试），BUILD SUCCESS，58.493秒；仅本地验证标记，不作为生产发布SHA。设计及清单25项、V2文档70份、生成物25项、diff检查通过。


## S2B2A无损目录v2交付记录（2026-10-26，本地）

### L2 功能与兼容

共享目录新增显式schemaVersion=2和ChannelCatalogDetails，餐饮/零售仍共用同一目录、快照、同步和恢复链路。v2的旧sections/offers/schedules为空，details为唯一结构真源；旧八参数构造和旧JSON不输出追加空字段，缺版本仍为v1，固定历史摘要保持原值，不能把v2静默降级给旧适配器。

每个商品条目的Variant独立保留价格、库存状态、选项、互斥/依赖规则和套餐。quantity.minimum/maximum/defaultQuantity及unitsPerPick为严格精确十进制字符串，不取整、不提前按每选一次四舍五入。套餐保留INCLUDED/ADD_SKU/DIFF_BASE及原SKU；免费规格必须零价、无销售SKU且库存NOT_APPLICABLE，销售SKU未知库存显式UNKNOWN且不带余额或伪政策回执，POLICY必须提供原政策及回执引用。

SourceSchedule保留原时区、有效起止日期、周规则endDayOffset和OPEN/CLOSED日期例外，能表达跨午夜及完整24小时。空周规则是无常规开放窗口；临时例外不删、不改成全天。按本地起始日解释窗口，包括跨周重叠检查及日期例外唯一/范围检查；门店营业时间和接单日历仍需B阶段沿原owner核验，菜单窗口本身不授予门店营业资格。

v2稳定业务摘要包含真实价格/修订、税依据、库存政策、配料/套餐规则及时间例外，只排除可刷新receiptNo；仅换回执不制造菜单业务变化。v1继续原哈希算法，不重算历史快照。原cim_catalog_snapshot已具备schema_version及item_count，本批按实际版本和商品条目数写入，读取时核对载荷版本及业务摘要，没有新表或迁移。

### L3 需求、设计、代码和验收对账

| 需求/设计 | 实现及方法 | 实际专项 |
|---|---|---|
| AC03 无损结构及精确数量/日期例外 | commerce-api.spi.commerce.ChannelCatalogDetails；ChannelCatalogSourcePort.Document追加字段和兼容构造；commerce-core.policy.CatalogDetailsPolicy.validate/businessContent | CatalogDetailsPolicyTest 15项：价格/用量hash、按规格规则、依赖/互斥、默认总量/范围、跨午夜/全天/例外、库存/币种/税、严格JSON、版本混合拒绝及固定历史摘要 |
| AC03/AC05 冻结和历史兼容 | CatalogDocumentPolicy.freeze/version/itemCount/hash；CatalogPersistence.document/compatible；CatalogPublicationServiceImpl.preview | 新同步专项验证schema=2、itemCount=1和载荷/元数据拒绝；旧CatalogDocumentPolicyTest 4项 |
| AC04 版本准入及UNKNOWN不重发 | ChannelCatalogGateway.supportsDocumentSchema默认仅v1；SyncRunServiceImpl.submit事务前后复核；CommerceRecoveryServiceImpl.claim/recoverDue/failQueued | CatalogDetailsSynchronizationTest 5项：旧gateway不创建run、显式支持原样发送、排队撤回无operation、UNKNOWN保留阻断及恢复只query、坏快照拒绝 |
| AC04/AC05 原链路回归 | 旧CatalogSynchronizationTest仅新增测试夹具的v2开关和记录原Publication | 原同步15项及ResourceMappingTest 9项 |
| AC15 模块及装配 | 没有新模块/领域实现依赖，SPI仍在原commerce-api | 4项相关ArchitectureTest及1项ConditionalBeanWiringTest通过 |

实际12个Java文件（4新增、8修改）及原TDD、结构化真源和三份生成设计；索引重生成无差异。48个唯一相关业务专项通过、0失败0跳过（新增20、旧回归28）；相关架构/装配5项通过。两项补充边界有先失败再修复证据：默认总量超组上限；冒号拼接的不同source/ref组合产生歧义，现沿原长度编码。生成清单和设计25项通过。最终消融/打包记录见末尾，不运行全量后端测试。

### L4 复核决定与未开放范围

独立只读复核一次，无确认Critical/Important或延期Minor；执行侧实际检查测试/构建输出，复核未运行构建。当前共享校验是结构、数量边界、默认量、依赖引用/无环和时段完整性校验，并不求解所有互斥/依赖/组最小量的完整组合可满足性；原owner及B阶段适配器仍须确认真实选择可用性，不能以本合同校验代替原行业准入。默认量可低于组选量下限，剩余由下单选择补足；组上的defaultQuantity（若给出）不是各choice默认分配的替代，当前FOOD原定义没有该值，适配时应保留null。

日期例外保留原本地起始日/前一天跨夜窗口语义，不更改原营业规则。精确数值字符串拼写及数组顺序按原源保留，因此不同字符串/展示顺序可以形成新摘要；原来源应输出稳定规范字符串及顺序。业务摘要不认证原税/库存/媒体事实，也不能防御可信owner本身被攻破；实际原证据读取和权限门禁在B阶段接线。

旧gateway默认仅v1。v2提交不支持则无run；排队后撤回支持则FAILED/SCHEMA_UNSUPPORTED且不创建外部operation；已发送/UNKNOWN动作恢复遇到不支持则保留原阻断并延后，能力恢复后只查原动作，不另发。源码和H2事务测试不冒充实际平台协议或生产数据库并发验收。既有厨房SSE架构问题保持ARC-SSE-01待办，不重跑该无关失败项、不放宽守卫。

下一步S2B2B：装配原FOOD来源到共享目录的适配，核验连接授权/餐牌渠道范围、原营业时间与菜单时段及接单例外、媒体来源、非CNY覆盖价原策略和独立worker客户端；补齐组合选择有效性和实际平台表达能力检查。其后S3可靠发布事件桥接，再进入原C2订单接入。当前没有注册真实目录提供方或外卖平台gateway，没有启用任务，也没有改支付/前端。本批仅本地提交，不推送部署，线上仍b076524b6。

版本门禁消融记录：临时摘除SyncRunServiceImpl两处兼容校验，previewPersistsRealVersionAndOfferCountWhileLegacyAdapterCannotSubmit真实失败（原应拒绝的提交未抛异常）；原样恢复后仅复测该项，1项通过。一次独立复核完成，无确认阻断问题；最终干净打包以下一条核验记录为准。

最终核验：同源码临时reactor以food-embedded、commerce-embedded完成shop-app、food-server、commerce-server的clean package，BUILD SUCCESS，59.068秒；已编译全部相关测试源码，打包跳过重复执行测试。48项相关业务专项、5项相关架构/装配、设计25项、V2文档70份、生成物25项及diff检查通过。仅本地验证标记，不作为生产发布SHA；当前无推送、迁移或部署。


### S2B2B1 原连接/CATALOG授权事实交付（2026-10-26）

仅交付来源读取的前置能力；S2B2B2完整FOOD目录/原营业日历/媒体适配仍待后续，不将本次短期事实当成可发布或可接单证明。当前没有真实ChannelConnectionIdentityPort提供方，生产连接保持关闭。无新表、Mapper、迁移、公开权限、前端或支付改动。

#### 内部输入输出与错误

`POST /internal/commerce/catalog-authorities/capture`；`X-Internal-Token`沿InternalHttp原协议。请求四个必填严格字符串entityNo/storeNo/connectionNo/revisionNo（每项最多32），拒绝数字代串、空白、未知字段。scope来自已认证服务，并由原CIM关联逐项验证，不能用此接口代替商户用户权限。

| 输出对象 | 实际字段及来源 |
| --- | --- |
| Facts | schemaVersion=1/purpose=CATALOG_AUTHORITY/query；verifiedAt、validUntil、receiptNo |
| Provider | providerNo/providerCode/version；原ACTIVE平台行 |
| Profile | profileNo/productCode/market/protocolCode/identityNamespace/accountScoped/version；原VERIFIED档案，支持的原身份schema为1 |
| Account | accountNo/externalAccountRef/version；原主体账号 |
| Connection | connectionNo/revisionNo/externalStoreRef/channelStoreKey/sourceOwner/catalogAuthority/fulfillmentProfile/configHash/version/revisionVersion/revision；精确当前修订 |
| Grant | authorizationNo/version/revision/capability=CATALOG/capabilityHash/expiresAt；原有效授权，不含凭据和原scope配置 |
| IdentityEvidence | status=VERIFIED/identityHash/receiptRef/verifiedAt/validUntil；本次事务外原Port核验结果投影 |

期限不超过调用开始后30秒，并取授权、身份回执的最短有效期；慢调用超过预算拒绝。receiptNo是全内容（自身字段清空）的确定性完整性摘要，不是签名或调用者授权令牌；可信服务认证仍由原令牌链承担。客户端重验原身份hash、原channelStoreKey、完整输出/期限及版本范围（不超过JSON安全整数）；没有自动PLATFORM回退。400输入错误、401缺失/错误令牌、403原业务禁止、404原资源或旧能力不存在、409原授权/修订/身份冲突，响应错误不携带内部对象；501/503等传输故障原样失败，不返回空成功。

#### 实际类与方法对账

| 模块 | 类及实际职责 |
| --- | --- |
| commerce-api | dto.ChannelCatalogAuthorityContracts（8组嵌套record）；service.ChannelCatalogAuthorityService.capture；policy.ChannelCatalogAuthorityPolicy.validateQuery/validateBase/identity/seal/validate；policy.ChannelIdentity同FQN原算法迁移；spi.commerce.ChannelCatalogAuthorityInternalApi.capture |
| commerce-core | service.impl.ChannelCatalogAuthorityServiceImpl.capture/load/read；CoreAutoConfiguration注入原CatalogPersistence/ProviderMapper/事务管理器/可选IdentityPort，无发布Gateway依赖 |
| commerce-web | api.internal.InternalChannelCatalogAuthorityController.capture/malformed/unavailable；config.CommerceInternalAutoConfiguration按enabled与本地Service装配，公共BFF装配边界不变 |
| commerce-channel | RemoteChannelCatalogAuthorityService.capture；CommerceAuthorityClientAutoConfiguration。shop.commerce.authority-client.enabled默认false，本地Service优先，远端COMMERCE目标必须显式配置，5秒单次typed读取 |
| commerce-server / shop-base | 独立服务复用web内部合同；CommerceInternalSecurityConfiguration仅内部令牌路径/健康检查；CommerceApplication默认standalone；ServiceName.COMMERCE新增原定位常量 |

集成宿主继续原服务认证和包装排除，不新增全局安全例外；独立服务公共BFF关闭。服务器无新增定时任务或外部平台激活。相同读取合同可供FOOD/RETAIL适配器使用，后续仍须核对原门店、餐牌绑定、价格/税、媒体和营业日历。

#### 验收与限制

| 原需求 | 本次可验证子集 | 专项 |
| --- | --- | --- |
| AC01/AC02 | 原平台/档案/账号/连接/授权精确关联、真实身份及当前版本；无凭据输出 | ChannelCatalogAuthorityServiceTest |
| AC03 | 原CATALOG授权及LOCAL目录来源门禁，不冒充完整FOOD目录 | 同上；CatalogSynchronizationTest回归 |
| AC15 | 无网络跨事务；双RR读取检测期间变更；本地优先/远端显式开启；令牌优先与独立BFF关闭 | RemoteChannelCatalogAuthorityServiceTest、InternalChannelCatalogAuthorityApiTest、CommerceArchitectureTest |

初始功能缺失RED：默认capture抛NOT_FOUND。H2原配置JSON对象使用FORMAT JSON测试数据，不将H2文本嵌套行为放宽到生产校验。集中相关回归首次65项全部通过（11新增、54原回归），无失败或跳过；不执行全仓业务测试。最终消融、独立复核和干净打包结果随后附记。

最终验证：集成宿主shop-app及food-server/commerce-server在相同两Profile下`clean package -DskipTests`成功（60秒，含测试源码编译）；随后集中运行本批及相关原连接/授权/目录/映射专项65项、相关架构/装配5项，共70项全部通过，0失败0跳过。10项设计专项、25个生成器及git diff --check通过。临时移除前后事实比较使并发变更专项真实失败，自动恢复后本次70项绿灯。一次独立只读复核无Critical/Important/Minor发现，无二次复核。测试为H2、真实本地HTTP及MockMvc令牌链，未冒充生产MySQL并发、真实外卖平台或跨进程上线验收。原厨房SSE架构问题未修改、未放宽规则、未重跑无关失败项。

本阶段只做本地提交，不推送/部署。后续S2B2B2将本次事实与原餐牌绑定/价格/营业日历/媒体组合，再进入S3可信发布通知；完整原FOOD目录捕获和真实平台适配仍未完成。


### S2B2B2a 原营业日历来源交付（2026-10-26）

为完整目录适配提供原接单日历事实，不复制或改写现有营业时间/日历表。未新增公开路由、数据表/迁移、权限、前端、定时任务、外卖平台或支付开关；媒体来源和完整FOOD目录仍待后续。

#### 内部合同和输入输出

`POST /internal/food/store-calendar-sources/capture`，沿原FOOD服务令牌链和无业务包装响应。请求不接受营业时间、切点或例外配置覆盖。

| 对象 | 字段及校验 |
| --- | --- |
| Query | entityNo/storeNo严格原编号；fromDate/toDate严格YYYY-MM-DD字符串，含首尾最多31个营业日，支持年份1900～9998；未知字段拒绝 |
| Facts | schemaVersion=1、purpose=STORE_CALENDAR、query、store、policies、exceptions、verifiedAt/validUntil/receiptNo |
| Store | 原entityNo/storeNo/status/openHours；保留READONLY和未知原文，不猜全天营业 |
| Policy | policyNo/timezone/cutoverMinute/effectiveFrom/version；原UTC生效时间，按时序保留范围前基线及保护区间内全部策略；版本为JSON安全整数 |
| ExceptionDay | calendarNo/businessDate/mode/intervals/savedPolicyNo/version；日期为营业日，OPEN规范区间最多32个、CLOSED空区间；范围内日期唯一升序 |

有效期最多从读取开始起30秒；receiptNo为完整内容摘要（自身字段清空），不是签名或权限。400非法输入、401未授权服务、403原业务禁止、404原门店/能力/策略缺失、409原数据冲突/期间变化；501/503等远程故障不返回空成功，也不自动回退。未知营业时间可以作为原事实返回，但日历判定为关闭，目录发布方不能把未知解析为全天。

#### 原日历如何判定

调用`StoreCalendarSourcePolicy.acceptsAt(facts, targetInstant, observedAt)`：先验证本次事实未过期且范围/摘要完整，再按目标瞬间选最后一条已生效原策略，以该策略的时区和切点求营业日，查对应例外；原门店须ACTIVE、原营业时间须允许，且该日OPEN例外若存在也须允许。CLOSED例外、缺已生效策略、未知原小时或营业日超出捕获范围均返回false。无例外时沿原门店营业时间。savedPolicyNo只作保存时溯源，策略变更后仍按原行为使用该营业日例外。

为覆盖时差、切点和跨午夜，策略读取使用UTC保护区间：fromDate前2天至toDate后3天，另取下界前最后一条基线；最多256次区间内变更，超量拒绝且不截断。此策略保留原始时间线，不将营业日例外直接误投影为自然日例外，也不把目标将来的Instant当成证据校验时刻。最终真实下单仍须由原订单流程再次确认接单资格，此来源不授予支付、库存或菜单资格。

#### 实际代码和方法

| 模块 | 类及方法 |
| --- | --- |
| food-api | dto.StoreCalendarSourceContracts及严格整数/日期解析器；service.StoreCalendarSourceService.capture；spi.StoreCalendarSourceInternalApi.capture；policy.StoreCalendarSourcePolicy.validateQuery/lower/upper/seal/validate/acceptsAt |
| food-api / 原food-core | ServiceCalendarPolicy同FQN迁移，原businessDate/normalize/acceptsAt保持行为；追加只接收时区/切点的重载，避免构造虚假付款策略 |
| food-core | service.impl.StoreCalendarSourceServiceImpl.capture/store/load；原StorePolicyMapper和ServiceCalendarMapper按MAIN/主体/门店限定，两次RR读取及门店事务外重读比对，拒绝调用方已有事务；FoodAutoConfiguration沿原持久化开关装配 |
| food-web | foodclient.RemoteStoreCalendarSourceService.capture及FoodClientConfiguration；沿原独立形态/开关和本地Service优先约定，5秒单次typed调用，无默认远端目标 |
| food-server | api.InternalStoreCalendarSourceController.capture/businessFailure；沿原FOOD安全链与异常状态约定，无新增全局安全例外 |

#### 验收对账

| 原需求 | 本次已编码子集 | 对应测试 |
| --- | --- | --- |
| AC03 原来源及时段 | 原营业小时、04点切日、OPEN/CLOSED例外、未来策略生效点、未知/READONLY、范围外关闭 | StoreCalendarSourceServiceTest、原ServiceCalendarPolicyTest |
| AC15 scope/边界/独立部署 | 跨主体/门店、缺原能力/策略、拒绝事务外层、原事实变更检测、超量不截断、时间线/日期唯一性 | StoreCalendarSourceServiceTest及原日历保存专项 |
| AC15 内部合同 | 严格日期/整数/文本与未知字段、完整摘要/期限、真实HTTP令牌、错误保留、默认关闭及本地owner优先 | RemoteStoreCalendarSourceServiceTest、InternalStoreCalendarSourceApiTest及原RemoteStoreOperationServiceTest |

初始RED为capture尚未实现时NOT_FOUND；首次原数据库专项及原策略回归10项通过。默认关闭装配测试最初错误加载启用态门店探针，导致测试夹具启动失败；将该探针限定到启用态后远程及内部接口专项通过，未为此修改生产行为。完整集中测试、守卫消融、独立复核与干净打包证据在最终附记对账。

最终验证：相同food-embedded/commerce-embedded Profile下，shop-app、food-server、commerce-server干净`clean package -DskipTests`成功（57.592秒，包含测试源码编译）；随后集中相关Java共34项全部通过（13新增、16原日历/远程回归、5相关架构/装配），0失败0跳过，16.387秒。10项设计专项、25个生成器及git diff --check通过。临时移除日历前后比较后，并发变更专项真实失败；自动恢复后本次集中测试通过。一次独立只读复核无Critical/Important/Minor发现，无二次复核。H2、真实本地HTTP和MockMvc服务令牌链不代表生产MySQL并发或真实外部平台验收；未重跑或放宽原厨房SSE无关失败规则。

本批只作本地白名单提交，不推送、部署或启用真实渠道。接续S2B2B2：原可信媒体展示来源、餐牌渠道语境与完整FOOD目录组装；然后S3可靠发布通知。本次31天/256变更的边界不用于猜测更长的营业日历，扩展范围需另补有边界的组合合同。


### S2B2B2b 共享原公开媒体事实交付（2026-10-26）

原sys_media_asset和MediaStore继续拥有图片台账与地址。新增共享来源放在shop-base SPI、原读取实现在shop-core，餐饮/零售通过通用Port调用；无新表/Mapper/迁移、公开API、上传/清理逻辑、前端、定时任务、外部平台或支付配置。此批不代表完整FOOD目录适配已完成。

#### 输入输出和状态

`POST /internal/media/public-sources/capture`，沿原`X-Internal-Token`与InternalServiceResponses协议，不套业务信封。输入不接受URL、请求域名、图片元数据或成功证明。

| 对象 | 实际字段和约束 |
| --- | --- |
| Request | entityNo/storeNo原编号；assetKeys为1～20项、不重复的原图片key；严格字符串，未知字段拒绝 |
| Facts | schemaVersion=1、purpose=CATALOG_PUBLIC_MEDIA、request、assets、verifiedAt、validUntil、receiptNo |
| Asset | assetKey/publicUrl/contentType/bytes/width/height/sourceRevision；依请求顺序，完整批次返回 |
| 地址 | 由原MediaStore.publicUrl生成的稳定HTTPS；合法host、无用户信息/query/fragment/编码或点路径段，路径精确对应原key；不猜外网host或改写相对地址 |
| 元数据 | bytes为真实正数且不超过JSON安全整数；width/height若已知为正整数，未知保持null；contentType若未知保持null，不从扩展名推断 |
| 修订 | 原assetKey/主体/门店/大小/尺寸/类型/创建时刻的稳定摘要，不伪造原台账没有的version，也不冒充文件字节hash；审计updatedAt仅参与捕获期间变更比较，不制造扫描刷新导致的修订漂移 |
| 回执 | 最多读取开始后30秒，完整事实确定性摘要；摘要不是签名，仍需服务令牌认证 |

缺图、跨主体/门店、私有用途、PENDING/RECLAIMABLE/PURGED或已认领清理批次均不返回部分成功。400输入错误、401错误/缺失服务令牌、403原业务禁止、404缺失原资产/能力、409原状态/元数据冲突、期间变更或无法生成稳定公开HTTPS。远程501/503等故障保持失败，不回退或捏造空列表。错误不回显私有DB、提供方或地址信息。

公开目录格式只沿原GOODS路径/旧两段key，归属始终以原台账为准，不根据路径推断主体或门店。符合台账的未知元数据可以返回；具体平台若强制尺寸/类型，后续协议适配仍须明确检查，不填0。来源不读取/下载文件、不调用exists、不签私有URL，不能把30秒事实当成文件绝不会被删除的承诺；最终外部发布仍需原目录冻结/能力门禁。

#### 类和方法对账

| 模块 | 实际代码与职责 |
| --- | --- |
| shop-base | spi.media.MediaPublicSourcePort.capture及Request/Asset/Facts；MediaPublicSourceFacts.validateRequest/publicGoodsKey/validateUrl/seal/validate及严格文本/整数codec；MediaPublicSourceInternalApi.capture |
| shop-core | media.port.MediaPublicSourcePortImpl.capture/load：原SysMediaAssetMapper读取，限定主体/门店/GOODS/ACTIVE/无purgeBatchNo；RR读取→事务外原MediaStore地址→RR重读完整比较，拒绝外层事务；原自动扫描Component装配 |
| shop-app | portal.internal.InternalMediaPublicSourceController.capture/malformed/unavailable；沿原内部令牌、错误状态、包装排除及隐私约定 |
| shop-channel | MediaHostRewriteFilter仅精确跳过新内部路径，识别contextPath；规范URL参与摘要，不经direct出口改写。其他公开URL转换、SSE规则不变 |
| commerce-channel | RemoteMediaPublicSourcePort.capture；CommerceMediaClientAutoConfiguration显式shop.commerce.media-client.enabled（默认false），本地owner优先；单次PLATFORM typed调用，5秒超时、无目标回退、不隐式启用CIM |

原本地MediaStore返回相对/uploads地址时明确冲突；需要由原媒体所有者先提供稳定HTTPS，不能用调用方Host或内部服务地址替代。未来其他包含URL且依赖完整性摘要的内部路由，也必须在接线时明确保护原字节语义；本批未全局改变所有内部路径的过滤规则。

#### 验收对账

| 原需求 | 本次可核验子集 | 专项 |
| --- | --- | --- |
| AC03 原展示来源 | 原图片完整归属、原元数据、稳定原URL、未知保留、无部分成功 | MediaPublicSourcePortTest及原MediaAssetQueryPortTest |
| AC15 原范围及事务 | 原台账真实Mapper、元数据/用途/状态/清理批次、事务外URL读取、期间变化、过期与批次上限、原Component装配 | MediaPublicSourcePortTest |
| AC15 内部传输 | token先于解析、严格字段、摘要和期限、真实HTTP、错误保持、本地优先/默认关闭 | RemoteMediaPublicSourcePortTest、InternalMediaPublicSourceApiTest、原InternalMediaAssetApiTest |
| AC15 出口兼容 | 新内部规范URL和回执不被改写，含contextPath；原公开direct/server转换、provider key往返保持 | MediaSourceRewriteBoundaryTest、原MediaHostRewriteFilterTest及MediaKeyRoundTripTest |

初始capture缺能力NOT_FOUND为RED；新内部路径原direct过滤器改写URL也真实RED。地址专项发现URI.normalize保留根前导/../，本地Java探针复现后补显式点段拒绝，相关测试由RED转绿。集中34项相关Java通过（15新增、14原回归、5相关架构/装配），0失败0跳过；含真实本地HTTP及原过滤器/令牌/信封组合MockMvc，并非真实云存储、生产并发或跨进程上线验收。最终独立复核、消融和干净打包在附记记录。

最终验证：相同food-embedded/commerce-embedded Profile下，shop-app、food-server、commerce-server干净`clean package -DskipTests`成功（约62秒，包含测试源码编译）。集中34个唯一相关Java专项通过（新增15/原媒体14/架构装配5）；守卫消融恢复并完成最终构建后，仅复验新增15项，9.071秒，全部通过、0失败0跳过，不把重复执行与34简单相加。10项设计专项、25个生成器及git diff --check通过。一次独立只读复核无Critical/Important/Minor发现，无二次复核；临时移除原元数据比较使变化专项真实失败，恢复后绿灯。不代表真实云存储下载、生产MySQL并发或实际跨进程上线验收，未重跑或放宽原厨房SSE无关失败规则。

本批仅本地白名单提交，不推送、部署或启用真实外卖渠道。下一步S2B2B2c：核对原餐牌渠道绑定，将原来源/价格/连接授权/营业日历/媒体组合为完整FOOD目录；还需处理原owner非CNY覆盖价策略和组合选择的有效性，随后进入S3可靠事件桥接。共享媒体来源已经具备，不将其冒充完整FOOD目录或真实平台菜单已同步。

### S2B2B2c-1 餐牌渠道绑定核验交付（2026-10-28）

本批20个文件：food-commerce新模块9个（pom、Contracts、Service/Impl、AutoConfiguration/imports、夹具与两个专项类），4份原构建pom、ADR/TDD、结构化设计及3份生成清单、依赖生成清单。与§2逐项对齐，无新增表、HTTP路由、Mapper、任务、支付或前端。桥接只保留原证据，不保存可编辑绑定副本，不注册完整目录提供方。

输入是原MENU/CURRENT查询、原连接修订查询和bindingNo；输出为原餐牌证明、原连接授权、精确绑定及CHANNEL/DELIVERY语境、最短有效期。外部账号只匹配externalAccountRef，不回退accountNo，不截断原编号。原pricePolicyRef仍只是引用，完整价格政策授权和时段交集不得跳过。

验证：默认零售clean test-compile成功48.064秒；最终集成宿主、food-server、commerce-server在三个显式profile下干净clean package -DskipTests成功约61秒，包含测试源码编译。17个唯一受影响Java专项通过（新模块15、原架构2）；打包后仅复验新模块15项，2.348秒，零失败零跳过。设计专项10项、25个生成器及git diff --check通过。原发布比较守卫消融真实RED，恢复后GREEN；一次独立复核的JSON装配顺序Important以真实Boot测试RED、修复、全模块GREEN关闭，无二次复核或遗留Minor。

本轮按用户要求整批编码编译后测试，以守卫消融补充验证。可选模块需显式启用；身份不明确的旧绑定会冲突，需原业务确认；短期事实不提供分布式原子快照或即时撤权保证，最终发送前仍须重验。当前为源码组合和真实Spring装配验收，不代表生产并发、独立进程或真实平台联验。本批只本地提交，未推送部署。完整FOOD目录、价格政策、时段交集和选择可行性、非CNY维护及S3事件桥接继续保留，不启用Keeta。

### A1 原连接解析与价格政策交付（2026-10-28）

本批执行已确认的第三方外送菜单同步A1，不包含外部订单、支付、前端或真实渠道。共38个文件：21份既有代码/测试/装配、11份新增Java、TDD/设计JSON与3份生成清单，以及前轮已确认的流程SVG。与§2 A1职责逐项对齐。无新表、迁移或公开路由；旧capture方法与历史合同保持兼容，新增方法在旧owner上默认拒绝。

| 实际入口 | 输入 | 输出与限制 |
| --- | --- | --- |
| POST /internal/commerce/catalog-authorities/resolve | entityNo/storeNo/providerCode/externalAccountRef/externalStoreRef | 原Facts：当前Query、provider/profile/account/connection/grant/identity及短期回执；不输出凭据，不激活连接 |
| POST /internal/product/catalog-price-sources/market | entityNo/storeNo | schemaVersion=1、purpose=CATALOG_MARKET、scope、market/currency/currencyScale、verifiedAt/validUntil/receiptNo；无SKU、价格或税；最多30秒 |
| MenuChannelAuthorityPort.authorized | 原主体/门店及精确外部身份 | 原唯一连接与有效CATALOG授权；无CURRENT发布依赖 |
| MenuChannelAuthorityPort.pricePolicyAuthorized | 原主体/门店、bindingNo、policyRef | ACTIVE绑定前后相等、精确身份、真实门店市场匹配；仅支持MENU_PUBLISHED_PRICES_V1 |
| FoodCatalogPricePolicyService.capture | binding Query及pricePolicyRef | schemaVersion=1、query、policyVersion=1、mode=MENU_OVERRIDE_THEN_CATALOG_BASE、bindingFacts、原prices、verifiedAt/validUntil/receiptNo；只读原PriceLine，拒绝串市场、串修订或改价后重算摘要 |

原商品市场读取复用门店归属、主体市场和MarketPort，不靠虚构SKU或已发布餐牌，也不要求先配置税。市场事实允许ACTIVE/READONLY用于管理，不授予销售资格；实际原价capture仍要求ACTIVE。原CIM候选读取使用原二进制排序规则及范围条件，最多2条判歧义，捕获前后重复读取，原远端身份核验在事务外。外部账号不回退内部accountNo、不截断、不改大小写。

装配：shop.food.commerce.price-policy.enabled默认false。读政策证明还需原binding.enabled及BindingService、原MenuCatalogPriceService；写前核验需原绑定Service、CIM authority和CatalogPriceSourcePort。启用后仍必须有真实原提供方；原身份核验缺失、旧服务不支持新合同等均失败，不把测试夹具当正式授权。自定义本地实现优先，原普通业务默认不受影响。

验证：76个唯一相关Java专项通过（新增27、原回归/架构49），零失败零跳过；初轮歧义夹具缺少原必填身份列，补齐完整第二份身份/授权后通过，未放宽原约束。候选后复查守卫消融使对应测试真实RED并恢复。一次独立只读复核确认1项Important：暂停门店市场读被误拦；真实原owner回归先RED FORBIDDEN，单次修复后相关19项GREEN，未二次复核，无遗留Minor。最终默认clean test-compile成功50.380秒，集成宿主/FOOD独立/CIM独立clean package -DskipTests成功约81秒，均包含测试源码；打包后只复验新增27项，14.501秒通过，不重复全量测试。

本批是H2原Mapper、typed组合、真实本地HTTP/令牌/装配与本地构建证据，不证明生产并发或真实多进程上线。双读不消除ABA或最后读取后的变化，摘要不替代服务认证，最终发布仍须核验原来源与授权。构建仅作本地验证，不是已部署的固定SHA发布包。

仍待A2：原封面key解析、非CNY覆盖价维护、owner来源分派；随后B的v3/时段交集/选择可行性/完整目录、C的最终接线以及S3可靠事件。仅本地精确提交，不推送、部署或启用Keeta等平台；整体任务台账继续保留。

### A2.1 原商品封面身份交付（2026-10-28，本地）

- 已编码：shop-base的MediaReferenceQueryPort/MediaReferenceFacts与CatalogImageContracts/CatalogImageFacts；GoodsCatalogQueryPort新增可选能力，旧实现默认拒绝。shop-channel.media.port原受管出口解析不使用GC提取器、不搬图片；shop-core原商品owner执行RR→事务外媒体身份→RR，无图、失效和已变更明确区分。
- 原内部POST /internal/product/catalog-images/resolve复用服务令牌和原始JSON。food-channel负责PLATFORM协议及返回证据校验，food-core延迟转发保持新能力；旧CN元数据查询行为不变。新增内部只读接口，无公开渠道路由、新表或迁移，无真实渠道启用。
- 本批代码/测试清单与§2 A2.1相符：6个新增专项类、原GoodsCatalogQueryPortTest仅更新构造接线；最终结果见下方。本批不交付真实市场元数据、schema6、owner分派、v3组装、订单或真实外送联验。

- 最终证据：默认零售clean test-compile PASS47.660s；宿主集成、独立food-server、独立commerce-server clean package PASS约62秒。打包后新增27项及5项直接相关架构检查全部通过17.295s，0失败/异常/跳过；与此前直接相关30项合计62个唯一Java专项，不把重复复测累加。设计10项、全部25个生成器、SQL方言及diff检查通过。日志位于/private/tmp/food-image-a21-*。
- 一次独立只读review无Critical/Important。保留1项P3测试补充：CatalogImageQueryTest尚缺全部成功、且请求顺序与库排序相反的多商品批次用例；生产代码保留按请求重排并在最终Facts核验顺序，本批不另扩修复。后续验收待办明确保留此项。
- review边界决定：生产JDBC并发/时钟同步/部署配置、真实CDN可用性及图片内容版本留后续联验；当前是短期原身份事实，不是永久发布资格、实时撤销或图片内容版本。干净打包由执行方证据补齐；已提交A1及剩余A2/B/C不纳入本批review，不冒充整个外送接入完成。
- 提交仅含本批24个代码/测试/文档文件，沿原规范新增一个SPI及一个内部只读方法；无新数据表或Flyway修改。当前只本地提交，不推送或部署；后续任务台账继续保留。

### A2.2a 真实市场原商品元数据交付（2026-10-28，本地）

- shop-base新增CatalogMarketContracts/CatalogMarketFacts；沿GoodsCatalogQueryPort及GoodsCatalogInternalApi新增两种只读方法。返回仍为原GoodsFact/SkuIdentityFact，通过新字段上的严格codec避免改变旧CN JSON；完整绑定原市场证明、商品/SKU版本、范围、最短有效期与回执。
- shop-core原GoodsCatalogQueryPortImpl复用A1的CatalogPriceSourcePort.market，实际市场前后核验与两次RR元数据读取，SKU展开最多256且不截断，无CN降级、税/价/库存查询。原2参数、图片4/5参数构造保持，新增Spring自动注入以可选原owner提供方延迟解析。
- shop-app原InternalGoodsCatalogEndpoint新增market-lookup/market-sku，服务令牌先于校验、原始JSON与原错误状态保留；food-channel提供PLATFORM的真实typed HTTP及返回校验，food-core延迟转发完整支持。新增5个专项类与既有直接相关回归，清单和§2 A2.2a相符；无新增表、迁移或公开渠道API。
- 本批仅贯通实际市场读取；原FOOD服务仍走原路径，catalog-currency开关尚未启用。非CN原依赖维护、币种校验、schema6、行业来源分派、完整目录/v3和真实供应商联验仍按后续计划推进。

- 最终只读review确认1项P2：商品SKU展开依赖SQL的宽松字符串比较，未取回原entity/goods/market逐值核对。已核对原V1 prd_sku的utf8mb4_uca1400_ai_ci；新增CatalogMarketGoodsTest#sqlEquivalentButNonExactSkuOwnershipCannotEnterMarketGoodsEvidence，在H2 VARCHAR_IGNORECASE先证明SQL确实匹配，再要求拒绝非精确归属，修复前RED1。单次修复补三列及精确equals后，原商品市场12项GREEN17.665s；不改旧方法/原表排序规则，不二次review。
- 本批新增专项现为26项，直接相关48项已先前通过；最终产物/架构证据随后登记。H2回归覆盖大小写不敏感的原Mapper匹配，不冒充真实生产MariaDB排序规则或并发联验；精确equals守卫同时拒绝非相同原字符串。
- 复核边界决定：生产时钟/并发/部署、真实供应商及CDN、原FOOD消费者和schema6仍在后续联验/开发；事实摘要不构成永久发布授权，也不保证实时撤销或消除ABA。当前专项不是穷尽所有畸形输入组合的声明；上批图片多项顺序P3待办保持后置。

- 最终构建：默认零售clean test-compile PASS52.034s，集成宿主/独立food-server/独立commerce-server clean package PASS约69秒；review修复后同受影响产物编译重打包PASS22.964s。最终打包后新增26项及5项相关架构检查共31/31 GREEN16.973s，0失败/异常/跳过；加先前48项直接相关专项为79个唯一Java检查，不累加重复执行。一次原市场守卫消融及一次review回归均有RED→GREEN证据；无二次review或新增延后minor。
- 设计10项、25个生成器一致性、SQL方言和diff检查均通过。日志/private/tmp/food-market-a22a-*，真实范围记录于台账；仅本地精确20文件提交，无推送、部署或真实供应商启用。代码、原DTO和原架构兼容验证与§2实施清单相符，唯一review重要问题已在本批修复；后续仍是原FOOD非CN资源维护/发布及schema6。

### A2.2b1 币种规则与冻结市场格式交付（2026-10-28，本地）

- MenuPricePolicy新增纯normalizeStructure/normalizeForCurrency及priceForCurrency，保留旧CNY入口并阻断nativePrice混币；原菜单组成结构有相应新纯入口。没有汇率、金额换标签、按两位自动缩放或浮点金额计算。
- PublicationMarketContext/PublicationMarketPolicy从已核验的原市场事实及调用方期望scope冻结市场、币种、精度、稳定hash与原审计回执；历史不调用活owner或复用旧TTL授权。原SKU及字典值/维度双轴范围在新6中复核。
- PublicationSnapshot、BundlePublicationSnapshot、RecipePublicationSnapshot、MenuPublicationSnapshot新增非null才输出的marketContext并保留旧构造器；旧1至5六kind真实字节和hash不变。PublicationDocuments、MenuPublicationDocuments及RecipePublicationDocuments按kind+schema解释新6；MENU仅CHANNEL/DELIVERY，依赖及配方内嵌选项组须同scope/market/currency/scale。工位仍只5。
- 当前仅格式/纯规则，不新增Service装配、业务开关、HTTP API、表或迁移，不切换原ConfigRevisionService、原维护服务、CURRENT来源或实时价格读取；本地实际交易的CNY限制保留。下一批须统一完成原写入/发布/重发布/来源接线后才能声明业务可用。
- 对账文件为上述DTO/Policy、4个新专项类、两个测试夹具与上一提交产物捕获的golden资源，以及本TDD和生成设计说明。初轮唯一失败为测试误把原市场证据IllegalArgumentException预期成IllegalStateException，纠正测试保留原错误语义；没有为凑绿更改原owner行为。

- 最终独立只读review发现2项P2：新6 BUNDLE嵌套未知字段及未注解SKU字段强转仍可通过；MODIFIER未核对引用销售SKU与事实集合。执行方新增三个反例均真实RED（未知字段、匹配引用下数字标识强转、缺销售SKU事实），不是仅依赖review探针结论。单次修复以schema6局部严格mapper和配料事实完整性约束关闭问题；增加免费ADDON/组合子项事实正向对照。新增26项GREEN8.334s；无二次review或新增延后minor。
- 最终构建：集成宿主、独立food-server及独立commerce-server clean package PASS约78秒；两项修复后同受影响产物重新编译打包PASS约61秒。打包后新增26项及5项相关架构检查31/31 GREEN14.349s，0失败/异常/跳过；加此前直接相关83项通过，累计114项唯一检查通过。另有1项原生数据库并发用例条件跳过，未计入通过数、不累加重复复测。旧六kind真实字节/hash基线仍全部通过。
- 复核范围决定：原业务维护/发布、CURRENT与价格来源接线、A2.3/v3/真实供应商、生产时钟/并发/原生数据库联验均后续实施；历史回执只作解释/审计，不作永久授权。上批图片多商品顺序P3继续留待后续。最终测试/构建是执行方证据；review只读源码和已编译类探针，不冒充部署或原生DB验收。
- 设计10项、25个生成器、SQL方言和diff检查通过。所有改动局限food-api的DTO/Policy、专项及golden、设计真源和生成说明；不新增表/迁移/公开API/业务开关，不推送、部署或启用非CN业务路径。最终提交范围及真实SHA在执行台账记录。


### A2.2b2 原 FOOD 多市场业务接线（2026-10-28，本地）

本批将 A2.2a 的原商品市场事实与 A2.2b1 的币种规则接入现有业务服务。新增配置 `shop.food.commerce.catalog-currency.enabled=false`，默认关闭；启用后实际市场由原 entity/store 决定，不由终端币种或餐牌标签决定。没有餐牌时也可独立维护菜品、配料、组合配料、选项组、套餐及配方。实际 CN 保持旧商品读取和旧发布格式；非 CN 使用原市场商品/SKU 合同，不回退 CN，不换汇或自动缩放金额。

#### 实现与验收对账

| 需求/设计落点 | 实际类与方法 | 定向验收 |
| --- | --- | --- |
| 独立原市场与最短期限 | CatalogCurrencyServiceImpl.open；CatalogCurrencySession.goods/sku/refresh/guardTransaction | CatalogCurrencyBusinessTest.disabledSelectionNeverResolvesMarketOwnerAndCnKeepsOriginalSkuMethod；marketRefreshAndDependencyContextCannotSilentlySwitchCurrency |
| 原配料及组合维护、发布 | ModifierServiceImpl.createModifier/updateModifier；ConfigRevisionServiceImpl.publish | standaloneForeignAddonAndComboPublishWithoutAnExistingMenuAndHistorySurvivesOwnerOutage；corruptForeignIdentityFailsBeforeWritingAnything |
| 菜品扩展和详情 | DishServiceImpl.saveDish/getDish | DishServiceTest.foreignDishSaveAndReadUseOriginalMarketGoodsAndKeepPresentation |
| 套餐根、基准及可选销售 SKU | BundleServiceImpl.createBundle/replaceDefinition | BundleServiceTest.foreignBundleValidatesRootBaseAndChoicesBeforeAtomicDefinitionWrite |
| 配方与原独立库存材料 | RecipeServiceImpl.createRecipe/replaceDefinition；RecipePublicationSource.document | RecipeServiceTest.foreignRecipeKeepsIndependentMaterialAuthorityWhileUsingMarketAwareSaleSku；RecipePublicationServiceTest |
| 选项与餐牌价格、组成 | OptionGroupServiceImpl.createOptionGroup/replaceOptionGroupItems；MenuServiceImpl.replaceComposition/getMenu；MenuCompositionSource | OptionGroupServiceTest；MenuCompositionServiceTest；MenuCurrencyPolicyTest |
| 父子依赖和冻结解释一致 | ConfigRevisionServiceImpl.validateCurrencyDependencies；MenuPublicationSource；RecipePublicationSource | foreignParentCannotBorrowAnImplicitCnDependency；PublicationCurrencyDocumentsTest |
| 重发布不改历史字节 | ConfigRevisionServiceImpl.republishRevision/revalidateHistorical | marketChangeBlocksRepublishWithoutChangingOriginalBytesOrAppendCount；ConfigRevisionServiceTest |
| CURRENT 重核原市场、历史独立读取 | ConfigRevisionServiceImpl.verifyMenuResource；MenuSourcePolicy.project | currentMenuRequalifiesMarketWhileHistoricalReadNeedsNoOwner；foreignCurrentContextCannotReinterpretLegacyCnMenu |
| 真实原销售价格与冻结市场一致 | MenuCatalogPricePolicy.compose；原 MenuCatalogPriceServiceImpl.capture | MenuCatalogPriceServiceTest.foreignMenuUsesNativePriceAndRejectsRepricedDifferentMarket |
| 证据过期不得提交 | CatalogCurrencySession.guardTransaction 注册本事务 beforeCommit 检查 | expiryDuringOutboxWorkRollsBackBusinessAuditIdempotencyAndOutbox，移除该守卫后真实失败 |
| 旧合同与部署兼容 | FoodAutoConfiguration；全部旧构造器保留 | FoodConditionalWiringTest；FoodPersistenceWiringTest；PublicationCurrencyCompatibilityTest |

#### 调用与兼容边界

- 所有原市场和商品读取在本地事务之外进行；每次操作显式持有自己的短时 Session。事务内只验证已捕获的最短期限，并在提交前再核对，确保等待锁、幂等和 outbox 工作期间过期时整体回滚。它不是跨服务原子快照，也不能保证市场变更的瞬时撤销。
- 新非 CN 发布使用 schema6；父子必须同主体、门店、市场、币种及精度，不能借用隐式 CN 旧依赖。MENU 新6仅渠道外送；工位非 CN 仍拒绝。原 CN/关闭路径保留1至5，不重写旧字节或 hash。
- CURRENT先确认本地版本，事务外取得并复核实际市场，再确认原版本未变；回执期限不超过原市场证明。HISTORICAL/getRevision/resolveRevision 不读取实时 owner。新6在关闭开关后仍可审计，当前使用须具备已开启的真实市场资格。
- 重发布重核原商品、库存材料、依赖、渠道绑定和价格政策；保存的原文与金额不变。市场变化返回业务冲突，需要显式重新维护及发布，不能自动重贴币种。
- 不新增 HTTP 端点、业务表或迁移；原来源/原菜单计价端点扩展接纳新6。集成宿主和独立服务均使用同一原 Port，独立模式继续走已有令牌及原目标服务。原订单本地 CNY 计价限制保留。
- 此批仅业务接线；完整对外目录/v3、行业来源分派及真实供应商联调仍属后续。Keeta保持规划状态，不启用真实同步、支付、生产配置或部署。

- 执行证据：集成及独立模块 test-compile PASS33.829s；集中专项165项中161通过、4项原生DB条件跳过（未计入通过），0失败/异常，48.962s。范围含原菜品服务回归、4类新6币种规则/真实旧字节golden、装配条件与5项相关架构；未执行全仓回归。
- 守卫消融：仅移除事务 beforeCommit 的 requireFresh 后，过期/outbox用例失败（预期异常未抛，11.363s）；源码恢复，集中专项该用例通过。已有失败均定位为测试夹具缺依赖或正常市场变化错误语义，未添加任何CN回退。
- 生成设计10项、25个生成器、SQL方言与diff检查通过。后续构建、最终审查及本地提交信息继续记录；当前不推送或部署。

- 集成 shop-app、独立 food-server/commerce-server 的 clean package PASS，约77秒，包含全部受影响源码和测试源码重新编译；不是线上部署验收。三处对账共25个文件：15个原生产类、3个新增上下文类、6个场景测试（含1个新类）与本TDD，无表/迁移/POM改动。

- 最终独立只读审查仅发现1项P2：餐牌组成修改在查询已完成幂等结果前依赖实时市场，导致已提交的AED请求在owner离线或改币后无法重放。执行方先用 MenuServiceTest.completedForeignCompositionReplaysWhileOriginalMarketIsOffline / completedForeignCompositionReplaysAfterOriginalMarketCurrencyChanges 真实复现两项RED（8.935s），再修正为纯normalizeStructure(ReplaceMenuCompositionReq)→原canonical指纹→完成回执优先→新请求才核对实时市场。原actor/payload冲突语义及新请求币种校验保留。
- 审查边界明确：原生数据库/时钟/线上/真实提供方未联验；v3/来源分派/订单支付准入后续；原无销售SKU配料的发布准入未扩张；选项组市场中立GoodsSummary可见性批读不选择SKU，因此保持原合同；审查仅只读源码和执行方证据，不冒充独立复跑。无新增待延后minor，上批图片排序P3仍在后续清单。

- 单次修复后，菜单维护/组成、原发布/来源/价格、币种及旧字节兼容、5项相关架构合计86/86通过（23.434s，0失败/异常/跳过）；包含离线和改币重放正向对照、同键异actor/异payload拒绝，以及新请求仍失败的保护。不二次review，不重复无关业务测试。初轮161项通过和4项原生DB条件跳过仍按原范围记录，不把重复复测累计成新增通过数。

- 修复后集成宿主和独立服务 package PASS30.786s，包含最终生产修改；设计10项、25个生成器、SQL方言和diff检查再次通过。最终改动共25文件，两个本地提交；无新迁移、POM或前端改动，不推送、部署或开启生产开关。


### A2.3 行业来源分派交付（2026-10-28，本地）

按 §2 完整计划落地新 Provider SPI、commerce-core 唯一行业路由与装配、food-commerce 原餐牌提供方及默认关闭装配；两个原自动配置发现文件追加注册。原 Source/Validation 记录和构造器不变，无表/迁移/公开API/POM变化，不将 Provider 继承为旧 Port。

| AC/规则 | 实现 | 验收方法 |
| --- | --- | --- |
| 同行业唯一、缺行业不回退 | ChannelSourceValidationPortImpl 构造/validate | ChannelSourceValidationRegistryTest.routesBothIndustriesAndNeverFallsBackForAMissingOwner；rejectsDuplicateAndMissingOwnersAtConstruction |
| 校验输入、原成功身份及期限 | 同 Port.validate | rejectsMalformedInputsBeforeCallingOwner；rejectsWrongIdentityMissingReceiptAndExpiredOrExtendedSuccess；preservesOwnerErrorsAndNonSuccessWithoutCachingThemAsSuccess |
| 无提供方不造Port、旧实现兼容、混合失败 | CommerceSourceValidationAutoConfiguration 两个Bean | SourceValidationWiringTest 四项，含双行业/无数据库装配与重复、混合启动失败 |
| 当前原餐牌与scope，旧修订明确STALE | FoodSourceValidationProviderImpl.validate | FoodSourceValidationTest.validatesOriginalMenuAndPreservesOriginalDeadlineHashAndReceipt；explicitDifferentCurrentRevisionIsStaleWithoutGrantingAnyProof |
| 4/6原格式与受限kind | 同 Provider | acceptsMarketSchemaSixWithoutReinterpretingFrozenCurrency；unsupportedKindsAndSalesOfferCannotMasqueradeAsMenu |
| 非法输入/过期/错范围/原异常不降级 | 同 Provider | malformedScopeAndOverlongMenuIdentityFailBeforeOriginalRead；expiredAndWrongScopeOriginalProofsAreNotAccepted；notFoundPermissionTransportAndUnconfirmedConflictRetainOriginalMeaning |
| 两次原读取必须相同内容 | 原resolveRevision后CURRENT完整证明 | changingOriginalContentAtTheSameRevisionCannotMixHashAndReceipt |
| 明确默认关闭、独立FOOD无CIM数据库 | FoodSourceValidationAutoConfiguration | FoodSourceValidationWiringTest 两项 |

整批 test-compile PASS24.269s。首轮唯一错误为异常透传用例用Mockito.when重设已抛异常的桩，第二次设桩触发旧异常；改为doThrow，未改原业务错误处理。集中专项62/62通过19.388s（0失败/异常/跳过），含新增18项、原绑定与目录/映射回归及3项相关架构。

消融仅删除通用路由的过期下界判断，rejectsWrongIdentityMissingReceiptAndExpiredOrExtendedSuccess 真实失败（应抛异常未抛，3.812s），随后自动恢复原文件。真实零售提供方、跨进程CIM来源客户端、完整目录v3/发送、生产数据库和实际平台不在本批验收范围。

- 守卫恢复后的完整定向62/62再次通过36.537s；受影响集成/独立服务 package PASS22.573s。另补1项SourceValidationWiringTest.enablingBeanOverrideCannotSilentlyReplaceANamedLegacyPort，确认允许Bean覆盖时，同名旧Port混合仍会启动失败；此用例现有实现直接通过，无生产修改，日志虽以override-red命名但不是红灯证据。本批共63个唯一检查通过，不累加重复复测。
- 设计真源新增Provider合同，生成接口/代码/数据库说明同步；未新增表/API。设计10项、25个生成器、SQL/diff通过；最终只读复核待记录。
