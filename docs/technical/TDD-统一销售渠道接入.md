# TDD 统一销售渠道接入

> 状态：开发中，2026-10-26；C0草稿与通用授权引擎切片已编码且专项验证通过；完整C0–C5未完成，未生产建表或发布。需求：[PRD](../requirements/PRD-统一销售渠道接入.md)；决策：[ADR042](./ADR/ADR-042-统一销售渠道接入与行业来源隔离.md)。

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

前轮只验证文档生成、引用及结构一致性，不把设计守卫当业务验收。当前已新增Java子集，按每个AC登记真实源码/用例/执行范围及必要消融，不回填虚假绿灯。

## §6 对账二 设计到实现

全量目标保留70个API、31张候选表。当前实现进度以结构化真源implementation子集为准：五模块、累计29个基础/授权/目录/映射/任务/回调路由、17张迁移表、12个业务Service与本域journal、身份和持久化远端发布阻断策略。公开请求严格拒绝主体/凭据与未知字段；Service另校验、列表按数据库分页。未生产迁移或发布。通用授权和目录同步内核已交付；真实来源/授权/外部适配器、MANAGED发布映射、外部订单导入、履约、售后、资金证据仍待后续闭环。不能将整个AC01、AC02或AC15标为已全部通过。

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
