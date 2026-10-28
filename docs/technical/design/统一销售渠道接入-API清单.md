# 统一销售渠道接入 API清单

> 状态：全量候选设计；已编码子集见下方实施进度，候选数不代表完成数。由 `node scripts/gen-commerce-integration-design.mjs` 生成，勿手改。真源：[commerce-integration-design.json](./schema/commerce-integration-design.json)，2026-10-26。

## 当前实施进度

| 项 | 实际范围 |
| --- | --- |
| 状态 | C1_MAPPING_ENGINE_VERIFIED |
| 已编码迁移表 | cim_provider, cim_product_profile, cim_account, cim_store_connection, cim_connection_revision, cim_request_journal, cim_authorization_revision, cim_operation, cim_operation_attempt, cim_catalog_scope, cim_catalog_source, cim_sync_policy, cim_catalog_snapshot, cim_sync_run, cim_sync_run_item, cim_resource_mapping, cim_resource_mapping_revision |
| 已编码API子集 | COM-001, COM-002, COM-003, COM-004, COM-005, COM-009, COM-010, COM-011, COM-006, COM-007, COM-008, COM-035, COM-070, COM-015, COM-016, COM-017, COM-018, COM-019, COM-020, COM-021, COM-022, COM-023, COM-024, COM-025, COM-026, COM-027, COM-028, COM-029, COM-030 |
| 限制 | B端仍仅主体属主；累计29个基础/授权/目录/任务路由，17张迁移业务表。目录仅支持一个连接一个STORE完整范围，同行业聚合餐牌。默认无原行业目录/来源/授权/真实网关提供方；无连接激活接口，不能直接对Keeta等发布。目录自动策略与唯一恢复Job默认关闭；onAvailability和自动远端取消未开放。同步恢复只查原目录动作，不恢复C0 OAuth过期/UNKNOWN。目录授权事实只读内部端点及独立客户端已编码；完整渠道业务的独立模式客户端、订单导入、履约售后、资金对账及真实平台仍未开放。基础内核已集成部署；新增原FOOD来源/价格、目录v2、授权/日历/媒体已推送，未据此声明部署。 定时核验仅针对选定来源修订及本地发送，不自动发现source head或读取远端菜单漂移；原来源须投递可信发布通知。 资源映射公共写入仅IMPORTED草稿，核验由默认关闭的唯一Job调用；MANAGED代码分配/发布证明、真实资源查询网关尚未开放。历史解析内核已实现，不代表原订单导入已就绪。 目录v2合同/冻结/稳定摘要/版本派发门禁已实现，原连接授权/日历/媒体来源已分别编码，原FOOD完整目录尚未组装。 S2B2B1仅原连接/CATALOG授权事实，未装配完整FOOD目录；不新增公开路由和业务表。 S2B2B2a原营业日历来源已编码，保留未来策略与营业日例外，无新公开API或表；完整FOOD目录组装尚未完成。 S2B2B2b共享原公开媒体事实已编码，无新公开API或表；本地相对URL保持不可外发，完整FOOD目录/餐牌图片依赖绑定仍待下一步。 S2B2B2c-1新增可选food-commerce绑定核验，默认shop.food.commerce.binding.enabled=false；完整目录组装、原价格政策核准、事件桥接及真实渠道仍未完成。 A1仅连接解析、无SKU门店市场事实、原写前授权与政策证据，不新增表或公开路由；封面解析、非CNY维护、来源分派及完整v3捕获尚未完成。 A2.1补齐原商品封面身份/可信受管别名和内部HTTP、餐饮remote/lazy链路；无新公开API或表，仍不提供完整目录捕获。非CNY资源维护、schema6、owner分派、v3及真实渠道仍待后续。 A2.2a新增原商品实际市场Goods/SKU元数据、原市场证明/版本、两路内部只读API及餐饮remote/lazy链；旧CN方法不变，无新增表/公开渠道API。原FOOD非CN维护/发布和schema6仍未切换，不能据此声明可以对真实渠道发布。 A2.2b1新增纯币种规则及五kind schema6冻结市场/历史校验，旧1至5字节/hash保持，工位仍5；原业务写入、发布/重发布、CURRENT及价格来源尚未接线，不新增开关/API/表，不能声明非CN业务发布可用。 A2.2b2已贯通原FOOD非CN维护/发布/重发布/CURRENT和实时原价格市场一致性，默认关闭，旧字节及历史独立读取保持。A2.3新增唯一行业来源路由与默认关闭的原FOOD/MENU提供方，复用原格式4/6和原服务证明；不提供真实零售来源或完整目录捕获，不新增业务表/公开API。B完整v3、C跨进程捕获与发送、S3自动滚动以及真实平台仍待后续。 B1新增v3时间/税合同、冻结/摘要/原preview税scope核对及原FOOD UTC区间编译；无新增表/迁移/公开API或开关。MenuSchedulePolicy同FQN移到food-api，旧语义不变；原营业helper和税定义只增纯入口。B2完整图/可行性、C内部传输、S3通知及真实平台仍未开放，未部署或启用目录捕获。 |
| 验证 | 累计79个唯一受影响Java专项通过；复核及关联修复后仅复测35项。资源归属消融红灯、恢复绿灯；一项独立复核Important与原连接owner历史解析回归均RED→GREEN。原生隔离MySQL V1-V4十七表及约束通过；最终宿主/server编译打包、设计10/文档8/SQL守卫见TDD。不等同真实来源/供应商、生产JDBC并发和完整认证链验收。 本批目录v2另有48个相关专项及5个架构/装配专项通过；历史79项不与本批简单相加。统一编译/干净打包和消融记录见原TDD。 S2B2B1新增原授权读取/真实HTTP/令牌和独立关闭测试，具体集中回归及构建证据见TDD。 A2.1新增27项、直接相关30项专项通过；商品重读守卫消融RED→GREEN，最终构建和复核见TDD；不冒充生产并发和真实平台联验。 A2.2a新增26项、直接相关48项及5项架构检查通过，累计79个唯一专项；市场切换守卫及review发现的非精确SKU归属均RED→GREEN。默认干净编译/集成独立打包及修复后产物重打包通过，详见TDD。 A2.2b1新增26项、旧相关83项及5项架构检查通过，共114项；另1项原生DB并发条件跳过。历史六kind字节/hash取自上一提交旧编译产物；市场守卫消融及2项review问题均真实RED→GREEN。干净打包和修复后产物重打包通过，详见TDD。 A2.3整批统一编译24.269s，18个新增专项及44个直接相关回归共62/62通过19.388s。仅期限守卫消融真实RED，恢复验证及最终独立复核见TDD；本批未全仓回归。A2.2b2初轮161通过/4原生条件跳过、审查修复后相关86通过及打包证据见TDD，不与重复复测累加。 守卫恢复后62/62复验36.537s、集成独立package22.573s；另1项同名旧Port在允许Bean覆盖时混合拒绝用例通过，共63个唯一检查，最终复核见TDD。 A2.3最终独立只读review无P0-P3，无新增minor；审查不代替执行方测试，未启用真实来源/平台或部署；本批业务提交0236cf872在本地。 B1从修改前真实编译产物捕获v1/v2字节/hashgolden，集中113项中112通过/1原生DB条件跳过22.553s；补充实际排队/恢复器发送3项通过5.635s，唯一检查114项中113通过/1跳过。DST第二offset消融真实RED，恢复后GREEN。默认零售编译18.540s、集成独立clean package62s并核验唯一规则FQN；最终复核见TDD。 B1最终独立只读review无Important或新增Minor；业务提交58642e6a0仅本地，未推送部署或启用真实渠道。B2完整目录/选择可行性、C传输及S3通知仍待后续。 |

## L1 定位

一套B端API服务零售、餐饮，C端点餐/购物继续沿原业务API，不另造渠道C端下单入口。OPS只做基础配置和授权支持，回调不使用商家登录认证。下列为完整目标路由，实际已编码子集见实施进度；不是运行中OpenAPI发现结果。

## L2 分类与公共合同

公开/回调API共70项；内部业务Port及typed网关合同共15组，内部方法不算公开API。

| 分类 | 数量 |
| --- | --- |
| B | 65 |
| OPS | 3 |
| CALLBACK | 2 |

B端返回沿原ApiResult包装，列表为PageData<T>，Controller直接返回VO；内部typed与Webhook/授权响应须按原wrapper机制排除，不套业务JSON。page>=1，size默认20/最大100；时间from含to不含，查询最长31天是本方案首版默认建议，超出明确拒绝；历史分页稳定排序created_at,id，物理id仅排序不返回。

B端scope由BizContext、当前门店头及原StoreDirectoryPort校验；账户管理为主体级管理员能力，不把当前门店角色当账号全部权限。OPS显式entityNo/storeNo仅作目标，仍按运营数据域交叉核验。普通GET无外部网络副作用；refresh/recover均POST且返回原动作。

所有写动作需要Idempotency-Key，同键异hash或不兼容操作者冲突。对象修改/状态动作需要version；创建不传version。Req缺失/非法字段BAD_REQUEST，跨域资源按平台统一策略拒绝，旧版本CONFLICT，未获能力明确拒绝；不补假成功。所有枚举错误码需登记原ErrorCode与三语文案，本文不预造数值码。

拟权限组：INTEGRATION/CATALOG/ORDER/FULFILLMENT/AFTER_SALE/FINANCE/ACCOUNT_ADMIN，映射到原BizPerms及授权模型，不能仅凭新增常量就给员工授权。权限实际代码在实施时登记，不建立第二套RBAC。

### 候选平台与外部协议

| 平台 | 市场 | 业务产品 | 接口合同状态 |
| --- | --- | --- | --- |
| UBER_EATS | TW | INSTANT_DELIVERY | 官方Marketplace合同；账号权限待核验 |
| FOODPANDA | TW | INSTANT_DELIVERY | 餐饮POS合同待确认；不能套用商超Partner API |
| KEETA | AE | INSTANT_DELIVERY | 已查阅标准文档；按用户决定仅规划真实接入 |
| TALABAT | AE | INSTANT_DELIVERY | POS合同与地区权限待确认 |
| DELIVEROO | AE | INSTANT_DELIVERY | 菜单/订单合同；接单模式按账号确认 |
| NOON_FOOD | AE | INSTANT_DELIVERY | 待获取官方获准合同 |
| CAREEM_FOOD | AE | INSTANT_DELIVERY | 待获取官方获准合同 |
| SMILES_FOOD | AE | INSTANT_DELIVERY | 待获取官方获准合同 |
| TAOBAO | CN | MARKETPLACE | 商品/交易/物流/售后按应用权限核验 |
| DOUYIN | CN | ECOMMERCE | 抖店协议及权限核验 |
| DOUYIN | CN | LOCAL_LIFE | 生活服务验券/核销协议独立核验 |

外部平台接口不包含在70个本系统公开/回调路由计数中。淘宝/抖店、餐饮POS、商超Partner API和生活服务分别按获准协议注册Gateway，不能因同品牌共用未经核验的字段或能力。具体外部HTTP路径及报文字段在适配器合同测试中冻结，未获取的协议不编造端点。

## L3 对外路由与输入输出

### B

| 编号 / 方法路径 | 输入 / Req | 输出 | Service方法 / 表 / AC |
| --- | --- | --- | --- |
| COM-001 GET `/biz/x/commerce/providers` | ProviderQuery：market/productCode/status/page/size | PageData<ProviderVO> | ProviderService.listProviders；cim_provider, cim_product_profile；AC01；BASE |
| COM-002 GET `/biz/x/commerce/providers/{providerNo}/profiles` | ProfileQuery：providerNo/market/productCode/page/size | PageData<ProductProfileVO> | ProviderService.listProfiles；cim_product_profile；AC01；BASE |
| COM-003 POST `/biz/x/commerce/accounts` | CreateChannelAccountReq：profileNo/externalAccountRef；无entityNo及明文密钥 | ChannelAccountVO | ChannelAccountService.createAccount；cim_account, cim_request_journal；AC01；BASE |
| COM-004 GET `/biz/x/commerce/accounts` | AccountQuery：profileNo/status/page/size；账号摘要按可见门店裁剪 | PageData<ChannelAccountVO> | ChannelAccountService.listAccounts；cim_account；AC01；BASE |
| COM-005 GET `/biz/x/commerce/accounts/{accountNo}` | AccountRef：accountNo；不返回Token/credentialRef细节 | ChannelAccountVO | ChannelAccountService.getAccount；cim_account, cim_authorization_revision；AC01；BASE |
| COM-006 POST `/biz/x/commerce/accounts/{accountNo}:authorize` | AuthorizeChannelAccountReq：version/returnTargetCode；回跳目标服务端登记 | AuthorizationActionVO | ChannelAuthorizationService.authorize；cim_account, cim_authorization_revision, cim_operation, cim_request_journal；AC01；BASE |
| COM-007 POST `/biz/x/commerce/accounts/{accountNo}:revoke` | RevokeChannelAuthorizationReq：version/reasonCode/reason | OperationVO | ChannelAuthorizationService.revoke；cim_account, cim_authorization_revision, cim_operation, cim_request_journal；AC01；BASE |
| COM-008 GET `/biz/x/commerce/accounts/{accountNo}/authorization-revisions` | AuthorizationQuery：accountNo/page/size；敏感引用裁剪 | PageData<AuthorizationRevisionVO> | ChannelAuthorizationService.listRevisions；cim_authorization_revision；AC01；BASE |
| COM-009 POST `/biz/x/commerce/stores/{storeNo}/connections` | CreateChannelConnectionReq：accountNo/externalStoreRef/sourceOwner/fulfillmentProfile；派生profile和channelStoreKey | ChannelConnectionVO | ChannelConnectionService.createConnection；cim_store_connection, cim_connection_revision, cim_request_journal；AC02；BASE |
| COM-010 GET `/biz/x/commerce/stores/{storeNo}/connections` | ConnectionQuery：provider/product/market/status/page/size | PageData<ChannelConnectionVO> | ChannelConnectionService.listConnections；cim_store_connection；AC02；BASE |
| COM-011 GET `/biz/x/commerce/connections/{connectionNo}` | ConnectionRef：connectionNo；门店、账号授权交叉核验 | ChannelConnectionVO | ChannelConnectionService.getConnection；cim_store_connection, cim_connection_revision；AC02；BASE |
| COM-012 PUT `/biz/x/commerce/connections/{connectionNo}` | SaveConnectionConfigurationReq：version/acceptMode/fulfillmentProfile/sourceRoutingRef；不能换主体或远端身份 | ChannelConnectionVO | ChannelConnectionService.saveConfiguration；cim_connection_revision, cim_store_connection, cim_request_journal；AC02；BASE |
| COM-013 POST `/biz/x/commerce/connections/{connectionNo}:verify` | VerifyChannelConnectionReq：version；能力核验异步动作 | OperationVO | ChannelConnectionService.verify；cim_store_connection, cim_operation, cim_operation_attempt；AC02；BASE |
| COM-014 POST `/biz/x/commerce/connections/{connectionNo}:set-status` | SetConnectionStatusReq：version/status/reason；停新单不丢弃已绑定历史事件 | ChannelConnectionVO | ChannelConnectionService.setStatus；cim_store_connection, cim_request_journal；AC02；BASE |
| COM-015 POST `/biz/x/commerce/stores/{storeNo}/catalog-scopes` | CreateCatalogScopeReq：connectionNo/remoteScopeKey=STORE/name/sourceRefs[{sourceOwner,sourceRef,selectionRevision,priority}] | CatalogScopeVO | CatalogScopeService.createScope；cim_catalog_scope, cim_catalog_source, cim_request_journal；AC03；CATALOG |
| COM-016 GET `/biz/x/commerce/stores/{storeNo}/catalog-scopes` | CatalogScopeQuery：page/size/connectionNo?/status?；仅ACTIVE范围 | PageData<CatalogScopeVO> | CatalogScopeService.listScopes；cim_catalog_scope；AC03；CATALOG |
| COM-017 GET `/biz/x/commerce/catalog-scopes/{scopeNo}` | CatalogScopeRef：scopeNo；由上下文验证当前门店 | CatalogScopeVO | CatalogScopeService.getScope；cim_catalog_scope, cim_catalog_source；AC03；CATALOG |
| COM-018 PUT `/biz/x/commerce/catalog-scopes/{scopeNo}` | SaveCatalogSourcesReq：version/sourceRefs；完整替换来源配置，已发送动作不撤销 | CatalogScopeVO | CatalogScopeService.saveSources；cim_catalog_scope, cim_catalog_source, cim_request_journal；AC03；CATALOG |
| COM-019 GET `/biz/x/commerce/catalog-scopes/{scopeNo}/sync-policy` | CatalogScopeRef：scopeNo；返回默认关闭策略 | SyncPolicyVO | SyncPolicyService.getPolicy；cim_sync_policy；AC04；SYNC |
| COM-020 PUT `/biz/x/commerce/catalog-scopes/{scopeNo}/sync-policy` | SaveSyncPolicyReq：version/enabled/onPublish/onAvailability=false/scheduledCheck/debounceSeconds/recoveryProfile=QUERY_ONLY | SyncPolicyVO | SyncPolicyService.savePolicy；cim_sync_policy, cim_request_journal；AC04；SYNC |
| COM-021 POST `/biz/x/commerce/catalog-scopes/{scopeNo}:preview` | PreviewCatalogReq：expectedScopeVersion；返回snapshotNo/hash/currency/itemCount/expiresAt/scopeVersion/document；无调用者价格/库存覆盖 | CatalogPreviewVO | CatalogPublicationService.preview；cim_catalog_snapshot, cim_catalog_source, cim_catalog_scope；AC03；SYNC |
| COM-022 POST `/biz/x/commerce/stores/{storeNo}/sync-runs` | SubmitSyncRunReq：scopeNo/snapshotNo/hash/expectedScopeVersion/reason；QUEUED无generation，发送时才分配 | SyncRunVO | SyncRunService.submit；cim_sync_run, cim_operation, cim_request_journal；AC04；SYNC |
| COM-023 GET `/biz/x/commerce/stores/{storeNo}/sync-runs` | SyncRunQuery：page/size/connectionNo?/status?；数据库分页 | PageData<SyncRunVO> | SyncRunService.listRuns；cim_sync_run；AC04；SYNC |
| COM-024 GET `/biz/x/commerce/sync-runs/{runNo}` | SyncRunRef：runNo；状态查询无远端副作用 | SyncRunVO | SyncRunService.getRun；cim_sync_run, cim_catalog_scope, cim_operation；AC04；SYNC |
| COM-025 GET `/biz/x/commerce/sync-runs/{runNo}/items` | SyncItemQuery：runNo/page/size；原尝试结果逐项分页 | PageData<SyncRunItemVO> | SyncRunService.listItems；cim_sync_run_item；AC04；SYNC |
| COM-026 POST `/biz/x/commerce/sync-runs/{runNo}:cancel-queued` | CancelQueuedSyncReq：version/reason；仅尚未发送QUEUED可取消，不取消远端任务 | SyncRunVO | SyncRunService.cancelQueued；cim_sync_run, cim_operation, cim_request_journal；AC04；SYNC |
| COM-027 GET `/biz/x/commerce/stores/{storeNo}/resource-mappings` | MappingQuery：connectionNo?/objectType?/externalRef?/sourceRef?/page/size；sourceRef按latest修订筛选，库内分页 | PageData<ResourceMappingVO> | ResourceMappingService.listMappings；cim_resource_mapping, cim_resource_mapping_revision；AC05；CATALOG |
| COM-028 GET `/biz/x/commerce/resource-mappings/{mappingNo}/revisions` | MappingRevisionQuery：mappingNo/page/size；原不可变修订倒序，包含草稿及核验修订 | PageData<MappingRevisionVO> | ResourceMappingService.listRevisions；cim_resource_mapping_revision；AC05；CATALOG |
| COM-029 POST `/biz/x/commerce/resource-mappings` | CreateResourceMappingReq：connectionNo/objectType/externalIdentity{externalRef,parents[{objectType,externalRef}]}/sourceOwner/sourceRef/sourceRevision/salesOfferRef?；只返回IMPORTED DRAFT，不接收entity/状态/证明 | ResourceMappingVO | ResourceMappingService.createMapping；cim_resource_mapping, cim_resource_mapping_revision, cim_request_journal；AC05；CATALOG |
| COM-030 PUT `/biz/x/commerce/resource-mappings/{mappingNo}` | ReviseResourceMappingReq：version/sourceOwner/sourceRef/sourceRevision/salesOfferRef?/reason；外部identity不可变；追加草稿且清active，不覆盖历史 | ResourceMappingVO | ResourceMappingService.reviseMapping；cim_resource_mapping_revision, cim_resource_mapping, cim_request_journal；AC05；CATALOG |
| COM-031 POST `/biz/x/commerce/stores/{storeNo}/availability-publications` | PublishAvailabilityReq：connectionNo/原商品可售事实引用/expectedSourceVersion；不接受伪库存量 | OperationVO | ChannelAvailabilityService.publishItemAvailability；cim_operation, cim_sync_run, cim_request_journal；AC06；SYNC |
| COM-032 POST `/biz/x/commerce/stores/{storeNo}/inventory-publications` | PublishInventoryReq：connectionNo/原库存发布策略及仓库映射引用/sourceRevision；不直接传新库存余额 | OperationVO | ChannelAvailabilityService.publishInventory；cim_operation, cim_sync_run, cim_resource_mapping_revision；AC06；SYNC |
| COM-033 POST `/biz/x/commerce/connections/{connectionNo}:set-availability` | PublishStoreAvailabilityReq：version/OPEN或PAUSED/until/reason；受门店接单策略限制 | OperationVO | ChannelAvailabilityService.publishStoreAvailability；cim_operation, cim_sync_run, cim_request_journal；AC06；SYNC |
| COM-034 GET `/biz/x/commerce/stores/{storeNo}/operations` | OperationQuery：connectionNo/type/status/from/to/page/size | PageData<OperationVO> | ChannelOperationService.listOperations；cim_operation；AC07；EVENT |
| COM-035 GET `/biz/x/commerce/operations/{operationNo}` | OperationRef：operationNo；脱敏错误、回执及可恢复操作 | OperationVO | ChannelOperationService.getOperation；cim_operation；AC07；EVENT |
| COM-036 GET `/biz/x/commerce/operations/{operationNo}/attempts` | AttemptQuery：operationNo/page/size；不返回原始密钥或顾客PII | PageData<OperationAttemptVO> | ChannelOperationService.listAttempts；cim_operation_attempt；AC07；EVENT |
| COM-037 POST `/biz/x/commerce/operations/{operationNo}:refresh` | RefreshOperationReq：version/reason；仅按已声明能力查询原动作 | OperationVO | ChannelOperationService.refresh；cim_operation, cim_operation_attempt, cim_request_journal；AC07；EVENT |
| COM-038 POST `/biz/x/commerce/operations/{operationNo}:recover` | RecoverOperationReq：version/reason；原键/原hash，UNKNOWN不能任意换键重发 | OperationVO | ChannelOperationService.recover；cim_operation, cim_operation_attempt, cim_request_journal；AC07；EVENT |
| COM-039 GET `/biz/x/commerce/stores/{storeNo}/orders` | ChannelOrderQuery：connectionNo/externalOrderRef/sourceOwner/importStatus/platformStatus/from/to/page/size | PageData<ChannelOrderVO> | ChannelOrderService.listOrders；cim_order_link；AC08；ORDER |
| COM-040 GET `/biz/x/commerce/orders/{channelOrderNo}` | ChannelOrderRef：订单业务号；分状态、原金额依据和核心关联，不返回物理id | ChannelOrderVO | ChannelOrderService.getOrder；cim_order_link, cim_order_revision；AC08；ORDER |
| COM-041 GET `/biz/x/commerce/orders/{channelOrderNo}/lines` | ChannelOrderLineQuery：orderRevisionNo/page/size | PageData<ChannelOrderLineVO> | ChannelOrderService.listLines；cim_order_line_link；AC08；ORDER |
| COM-042 GET `/biz/x/commerce/orders/{channelOrderNo}/revisions` | ChannelOrderRevisionQuery：channelOrderNo/page/size | PageData<ChannelOrderRevisionVO> | ChannelOrderService.listRevisions；cim_order_revision；AC08；ORDER |
| COM-043 POST `/biz/x/commerce/orders/{channelOrderNo}:accept` | AcceptChannelOrderReq：version/可信备货时间；仅INSTANT且POS_ACCEPT能力，业务准备后确认 | OperationVO | ChannelOrderService.accept；cim_order_link, cim_operation, cim_request_journal；AC09；ORDER |
| COM-044 POST `/biz/x/commerce/orders/{channelOrderNo}:reject` | RejectChannelOrderReq：version/reasonCode/reason；无拒单能力或已受理按取消合同处理 | OperationVO | ChannelOrderService.reject；cim_order_link, cim_operation, cim_request_journal；AC09；ORDER |
| COM-045 POST `/biz/x/commerce/orders/{channelOrderNo}:refresh` | RefreshChannelOrderReq：version；查询外部原单并按快照规则处理，不能直接覆盖 | OperationVO | ChannelOrderService.refreshOrder；cim_order_link, cim_order_revision, cim_operation；AC08；ORDER |
| COM-046 POST `/biz/x/commerce/orders/{channelOrderNo}:recover-import` | RecoverChannelImportReq：version/reason；先查原行业导入结果 | OperationVO | ChannelOrderService.recoverImport；cim_order_link, cim_operation；AC09；ORDER |
| COM-047 GET `/biz/x/commerce/stores/{storeNo}/inbound-events` | InboundEventQuery：connectionNo/type/status/orderRef/from/to/page/size | PageData<InboundEventVO> | ChannelEventService.listEvents；cim_inbound_event；AC07；EVENT |
| COM-048 GET `/biz/x/commerce/inbound-events/{eventNo}/receipts` | InboundReceiptQuery：eventNo/page/size；摘要/分类，不直接下载原敏感报文 | PageData<InboundReceiptVO> | ChannelEventService.listReceipts；cim_inbound_receipt；AC07；EVENT |
| COM-049 POST `/biz/x/commerce/inbound-events/{eventNo}:recover` | RecoverInboundEventReq：version/reason；重放已验签的原快照，冲突需原事实核验 | InboundEventVO | ChannelEventService.recoverEvent；cim_inbound_event, cim_operation, cim_request_journal；AC07；EVENT |
| COM-050 GET `/biz/x/commerce/orders/{channelOrderNo}/fulfillments` | FulfillmentQuery：channelOrderNo/page/size | PageData<FulfillmentLinkVO> | ChannelFulfillmentService.listFulfillments；cim_fulfillment_link, cim_fulfillment_line_link；AC10；FULFILLMENT |
| COM-051 POST `/biz/x/commerce/stores/{storeNo}/fulfillment-publications` | PublishFulfillmentReq：channelOrderNo/sourceOwner/sourceFulfillmentRef/sourceRevision；只传原履约引用不自报已完成 | OperationVO | ChannelFulfillmentService.publishFulfillment；cim_fulfillment_link, cim_fulfillment_line_link, cim_operation；AC10；FULFILLMENT |
| COM-052 GET `/biz/x/commerce/stores/{storeNo}/after-sales` | AfterSaleQuery：connectionNo/channelOrderNo/type/status/from/to/page/size | PageData<AfterSaleLinkVO> | ChannelAfterSaleService.listCases；cim_after_sale_link；AC11；AFTER_SALE |
| COM-053 GET `/biz/x/commerce/after-sales/{caseNo}` | AfterSaleRef：caseNo；原行及资金证据分状态 | AfterSaleLinkVO | ChannelAfterSaleService.getCase；cim_after_sale_link, cim_after_sale_line_link；AC11；AFTER_SALE |
| COM-054 POST `/biz/x/commerce/after-sales/{caseNo}:apply-decision` | ApplyAfterSaleDecisionReq：version/sourceDecisionRef/sourceRevision；退款须原售后+资金授权，不接受任意金额 | OperationVO | ChannelAfterSaleService.applyDecision；cim_after_sale_link, cim_operation, cim_financial_evidence_link；AC11；AFTER_SALE |
| COM-055 POST `/biz/x/commerce/stores/{storeNo}/redemption-previews` | PreviewChannelRedemptionReq：connectionNo/受控券引用/sourceConsumptionRef；验券并核验原权益合同 | RedemptionPreviewVO | ChannelRedemptionService.preview；cim_operation；AC12；VOUCHER |
| COM-056 POST `/biz/x/commerce/stores/{storeNo}/redemptions` | RedeemChannelCertificateReq：previewRef/expectedHash/sourceAuthorizationRef；原业务消费唯一身份 | OperationVO | ChannelRedemptionService.redeem；cim_redemption_link, cim_redemption_item, cim_operation；AC12；VOUCHER |
| COM-057 GET `/biz/x/commerce/redemptions/{redemptionNo}` | RedemptionRef：redemptionNo；不暴露明文券码 | RedemptionVO | ChannelRedemptionService.getRedemption；cim_redemption_link, cim_redemption_item；AC12；VOUCHER |
| COM-058 POST `/biz/x/commerce/redemptions/{redemptionNo}:reverse` | ReverseChannelRedemptionReq：version/sourceReversalAuthorizationRef/reason；依协议及原权益合同 | OperationVO | ChannelRedemptionService.reverse；cim_redemption_link, cim_operation；AC12；VOUCHER |
| COM-059 POST `/biz/x/commerce/accounts/{accountNo}/statement-imports` | ImportChannelStatementReq：平台账单引用或受控文件引用/hash；主体级财务权限+账号全部范围 | StatementImportVO | ChannelReconciliationService.importStatement；cim_statement, cim_statement_line, cim_request_journal；AC13；FINANCIAL |
| COM-060 GET `/biz/x/commerce/accounts/{accountNo}/statements` | StatementQuery：期间/currency/status/page/size；主体级财务权限，门店角色不开放 | PageData<StatementVO> | ChannelReconciliationService.listStatements；cim_statement；AC13；FINANCIAL |
| COM-061 GET `/biz/x/commerce/stores/{storeNo}/statement-lines` | StatementLineQuery：statementNo/connectionNo/differenceCode/page/size；强制门店行过滤 | PageData<StatementLineVO> | ChannelReconciliationService.listStoreLines；cim_statement_line；AC13；FINANCIAL |
| COM-062 GET `/biz/x/commerce/stores/{storeNo}/statistics/sync` | SyncStatisticsQuery：from/to/provider/product/type；按run创建时间队列，同一asOf快照 | SyncStatisticsVO | ChannelStatisticsService.syncStatistics；cim_sync_run；AC14；BASE |
| COM-063 GET `/biz/x/commerce/stores/{storeNo}/statistics/providers` | ProviderStatisticsQuery：from/to/provider/product/page/size | PageData<ProviderStatisticsVO> | ChannelStatisticsService.providerStatistics；cim_sync_run, cim_store_connection；AC14；BASE |
| COM-064 GET `/biz/x/commerce/stores/{storeNo}/statistics/order-imports` | ImportStatisticsQuery：from/to/connectionNo；技术导入数，不是成交金额 | ImportStatisticsVO | ChannelStatisticsService.importStatistics；cim_order_link, cim_inbound_event；AC14；BASE |
| COM-065 GET `/biz/x/commerce/stores/{storeNo}/integration-health` | IntegrationHealthQuery：connectionNo/provider；授权、积压、最老待办、远端一致性 | IntegrationHealthVO | ChannelStatisticsService.health；cim_store_connection, cim_operation, cim_sync_run, cim_inbound_event；AC14；BASE |

### OPS

| 编号 / 方法路径 | 输入 / Req | 输出 | Service方法 / 表 / AC |
| --- | --- | --- | --- |
| COM-066 GET `/ops/x/commerce/support/health` | CommerceSupportQuery：entityNo/storeNo；只读支持数据域显式核验 | IntegrationHealthVO | CommerceSupportService.supportHealth；cim_store_connection, cim_operation；AC01；BASE |
| COM-067 GET `/ops/x/commerce/support/operations/{operationNo}` | CommerceSupportOperationQuery：entityNo/storeNo/operationNo；交叉核验对象归属 | OperationVO | CommerceSupportService.supportOperation；cim_operation, cim_operation_attempt；AC01；BASE |
| COM-068 PUT `/ops/x/commerce/product-profiles/{profileNo}` | SaveProductProfileReq：version/受控开关和协议参数；变更语义则追加档案，不审批商家目录 | ProductProfileVO | ProviderService.saveProfile；cim_product_profile, cim_request_journal；AC01；BASE |

### CALLBACK

| 编号 / 方法路径 | 输入 / Req | 输出 | Service方法 / 表 / AC |
| --- | --- | --- | --- |
| COM-069 POST `/callback/commerce/{providerCode}/{endpointNo}` | RawChannelWebhook：原始字节/必要请求头/平台路由；验签后可信定位账号门店；ACK按协议 | ChannelAcknowledgement | ChannelWebhookService.receive；cim_inbound_event, cim_inbound_receipt；AC07；EVENT |
| COM-070 GET `/callback/commerce/{providerCode}/authorization` | RawAuthorizationCallback：code/state及协议字段；state绑定操作者/账号/有效期/单次消费，不接受任意回跳 | AuthorizationCallbackResult | ChannelAuthorizationService.completeAuthorization；cim_authorization_revision, cim_operation；AC01；BASE |

### 原有API复用

餐饮餐牌绑定继续复用/biz/x/food/stores/{storeNo}/menu-channel-bindings及单绑定读写，新增连接校验不新增第二份餐牌定义。原订单、支付、库存、配送、权益接口不复制为commerce CRUD；渠道只公开接入对象与受控业务动作。

### 内部合同及传输

| Port / 所有者 | 方法及返回 | 约束 |
| --- | --- | --- |
| ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort；FOOD/RETAIL | owner()->Owner；capture(CaptureCommand)->Document；v1保留原Section/Offer/Sku等嵌套record和无版本JSON；v2(schemaVersion=2)使用唯一ChannelCatalogDetails，含按Variant选项/套餐、精确数量、选择规则和日期例外，不混写旧集合；原FOOD日历来源StoreCalendarSourceService.capture(Query(entityNo/storeNo/fromDate/toDate))->Facts，内部POST /internal/food/store-calendar-sources/capture；共享原媒体spi.media.MediaPublicSourcePort.capture(Request(entityNo/storeNo/assetKeys))->Facts；内部POST /internal/media/public-sources/capture；v3(schemaVersion=3)追加availability:CatalogAvailability、taxBases:List<CatalogTaxBasis>；保留原8/10参数构造，旧1/2字节和hash不变 | 完整目录、原价格/税/库存依据及依赖；v2无损保留餐饮和零售语义，UNKNOWN库存不伪造数量；不授予渠道重算价格权限；日历来源保留原门店营业时间、营业日切点、策略生效时间线和日期例外；限定31个营业日/256次策略变更；判定只约束日历，不替代最终订单准入。完整目录/媒体适配仍待后续。；原公开GOODS图片完整批次最多20项，稳定HTTPS由原MediaStore生成，元数据未知保留null，不上传/下载/签私有URL；通用channel客户端显式media-client.enabled开启且本地owner优先。 原餐牌/连接身份组合由可选food-commerce桥接模块的FoodCatalogBindingService.capture提供；精确绑定外部账号及门店、当前餐牌双读核验，仅证明绑定身份，不授予pricePolicyRef定价权限，也不注册完整目录提供方。 A1新增FoodCatalogPricePolicyService.capture，仅接受MENU_PUBLISHED_PRICES_V1策略，复用原PriceLine并保留价税/市场证据；原MenuChannelAuthorityPort适配核对原绑定和共享CatalogPriceSourcePort.market(MarketScope)事实，配置不依赖CURRENT发布或虚构SKU。price-policy.enabled默认关闭；原核心市场POST /internal/product/catalog-price-sources/market不输出价格或税。 B1已实现v3有限7天UTC来源窗口、原scope/市场/精度/税定义、全部UnitPrice税引用、空目录清空及原preview范围核对；仅新3使用排序稳定摘要，旧网关默认拒绝3。原FOOD时间编译复用原营业/餐牌判定，按政策生效点及DST全部offset分段；完整FOOD图组装与选择可行性仍在B2，不因合同可冻结就注册真实完整目录提供方。 |
| ai.neargo.shop.spi.commerce.ChannelAvailabilitySourcePort；商品/独立库存原owner | captureAvailability(CaptureAvailabilityCommand)->AvailabilitySnapshot; captureInventory(CaptureInventoryCommand)->InventoryPublicationSnapshot | 可售量/仓库/渠道配额均取原owner，不建立渠道库存账 |
| ai.neargo.shop.spi.commerce.ChannelOrderIngestPort；FOOD/RETAIL | prepareImport(PrepareChannelOrderCommand)->ImportPreparation; getImport(ChannelOrderReference)->ImportPreparation; confirmAcceptance(ConfirmChannelAcceptanceCommand)->AcceptanceReceipt; cancelPreparedImport(CancelPreparedImportCommand)->ImportCancellationReceipt | 原核心订单、库存承诺、接单证据及一次派工；POSTPAY证明不可滥用 |
| ai.neargo.shop.spi.commerce.ChannelOrderAmendmentPort；原订单/行业售后 | apply(ApplyChannelAmendmentCommand)->AmendmentReceipt; getAmendment(ChannelAmendmentReference)->AmendmentReceipt | 原行/数量/金额和取消/替换守恒，资金动作需另授权 |
| ai.neargo.shop.spi.commerce.ChannelFulfillmentSourcePort；FOOD/零售/原物流履约 | capture(CaptureFulfillmentCommand)->FulfillmentSnapshot | 制作/拣货/包裹/交接均取已提交原事实；回传无权发明履约 |
| ai.neargo.shop.spi.commerce.ChannelRedemptionSourcePort；原权益/交易能力 | authorize(AuthorizeRedemptionCommand)->RedemptionAuthorization; confirm(ConfirmRedemptionCommand)->RedemptionReceipt; reverse(AuthorizeRedemptionReverseCommand)->RedemptionAuthorization | 先持久化原消费授权，再执行平台核销，未知查回；不造券余额 |
| ai.neargo.shop.spi.commerce.ChannelFinancialEvidencePort；独立支付/原资金所属域 | record(RecordChannelFinancialEvidenceCommand)->FinancialEvidenceReceipt; get(ChannelFinancialEvidenceReference)->FinancialEvidenceReceipt | 外部收款、退款、结算证据类型分离；不凭回调修改paid_at |
| ai.neargo.shop.spi.commerce.ChannelCredentialPort；原安全凭据能力 | resolve(ChannelCredentialReference)->ScopedCredentialHandle | 业务只传引用；签名/刷新组件受控使用，不出日志/公共响应 |
| ai.neargo.shop.spi.commerce.ChannelSourceValidationPort；各资源原owner或仅调用原Port的宿主只读组合 | validate(Source(entity/store/owner/kind/ref/revision/salesOfferRef?))->Validation(status/canonicalRef/canonicalRevision/hash/validUntil/receipt/canonicalSalesOfferRef?) | 校验entity/store/来源归属、资源类型、sourceRef/sourceRevision和授权销售条目；返回VALID/STALE/UNSUPPORTED/UNKNOWN、规范身份/原修订/hash/有效期及原回执；不跨库读原表。目录/映射/仓库来源均须typed验证，金额最终由原定价及订单prepareImport核验。 C1兼容原六参数构造；销售映射必须显式返回规范salesOfferRef，旧provider的null回执不能默认证明新关系。 |
| ai.neargo.shop.spi.commerce.ChannelConnectionIdentityPort；可信平台授权/查询适配器 | verify(Identity)->Proof(VERIFIED/UNKNOWN/UNSUPPORTED, identity, receiptRef, verifiedAt, validUntil)；已实现只读ChannelCatalogAuthorityService.capture(Query(entityNo/storeNo/connectionNo/revisionNo))->Facts，内部POST /internal/commerce/catalog-authorities/capture | 事务外核验远端门店真实归属，本地事务锁账号/协议并复核冻结身份及有效期；无实现拒绝认领，不提供默认成功；S2B2B1读取原ACTIVE平台/账号/连接、VERIFIED档案及CATALOG授权，以RR读取/事务外verify/RR重读冻结30秒事实，不输出密钥；commerce-channel显式authority-client.enabled选择COMMERCE typed远程，默认关闭且本地owner优先。此合同不代替餐牌/价格/日历/媒体准入。 A1增量：ChannelCatalogAuthorityService.resolve(IdentityQuery(entityNo/storeNo/providerCode/externalAccountRef/externalStoreRef))沿原Mapper唯一匹配FOOD/LOCAL/INSTANT当前连接，捕获前后复查歧义；内部POST /internal/commerce/catalog-authorities/resolve与原typed客户端保持同合同，无激活副作用。 |
| ai.neargo.shop.spi.commerce.ChannelAuthorizationGateway；commerce-channel获准协议适配器 | begin(AuthorizationContext)->Start；exchange(context,code)->Grant；revoke(RevocationContext)->Revocation | 原意图先提交，事务外OAuth/撤权；网关必须绑定原外部账号、凭据安全引用及可信回执；无真实提供方不授权 |
| ai.neargo.shop.spi.commerce.ChannelCatalogGateway；commerce-channel获准协议适配器 | supportsDocumentSchema(int)->boolean（默认仅v1）；supportsQuery()->boolean；publish(Publication)->Result；query(Publication,taskRef)->Result | 冻结目录发送与原动作查回；返回operation/generation/hash绑定和allWritesTerminal及原回执；没有默认实现，不在超时后盲重发 v2须显式完整支持；提交及派发重验版本，UNKNOWN恢复不降级或重发。 |
| ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway；commerce-channel获准协议适配器 | query(Query(operation/entity/store/connection/profile/原账号/原门店/原授权/凭据引用/objectType/父身份))->Proof(status/规范绑定/远端回执/validUntil) | 只读原平台资源身份核验；没有默认实现，公共请求不能上传成功证据。MANAGED资源代码分配及发布原证据仍未开放。 仅核验本能力与原授权，不反向要求目录发布Gateway。 |
| ai.neargo.shop.spi.commerce.ChannelResourceMappingPort；commerce-core | resolveHistorical(Reference(entity/store/connection/mappingNo/revisionNo))->Resolution(原身份/来源/原修订/销售条目/hash/历史远端证据/新来源回执) | 只解析显式VERIFIED历史修订并重新核验原owner；不回退当前指针，不自动批准下单。 |
| ai.neargo.shop.spi.commerce.ChannelSourceValidationProvider；FOOD/RETAIL | owner()->Owner；validate(ChannelSourceValidationPort.Source)->Validation | 行业来源贡献接口；唯一通用Port按owner分派。原FOOD提供方仅MENU、原格式4/6，重核当前修订和完整CURRENT证明；原错误不降级。无提供方不制造成功，同owner重复或旧Port混合启动失败。原六参数Source构造兼容；不授予完整目录、价格、库存、支付或履约权限。 |

内部合同在commerce-api中保持唯一spi包，宿主提供本地或typed远程实现；内部路由按现有/Internal*Controller协议在提供方登记，不额外复制同义Service。拆分前为每组方法补齐实际内部path/请求schema/服务身份/签名及wrapper排除，不能据此宣称已存在内部端点。

### 关键DTO字段

| DTO | 字段与校验 |
| --- | --- |
| ChannelContext（仅服务端） | tenantNo/entityNo/允许的storeNos/actorNo/realm；不接受公共JSON自报 |
| ChannelCatalogSnapshot | schemaVersion/snapshotNo/scopeNo/connectionRevisionRef/sourceManifest/items/hash；item含salesOfferRef/sourceOwner/sourceRef/sourceRevision/分类/SKU/选项/组合/价格及税来源/图片/时段；货币单位沿MoneyMinor，库存另取事实 |
| PrepareChannelOrderCommand | 稳定connection/remoteOrderIdentity、原快照/hash、externalLines及parentRefs、精确quantity/unit、原价格优惠税费用证据、mappingRevisionRefs、履约计划、资金证据引用、原operationNo；内部认证且原owner核验 |
| ImportPreparation | importRef/sourceOrderRef/coreOrderRefs、状态/版本、原数量与库存承诺证明、履约准入依据；未就绪不返回可制作 |
| ConfirmChannelAcceptanceCommand | 原importRef/expectedVersion/platformAcceptanceEvidenceRef/原operationNo；证据必须来自已验签/可信查询并由接入owner复核 |
| SyncRunVO | runNo/type/trigger/status/snapshotHash/desiredSnapshot/lastConfirmedSnapshot/remoteConsistency、startedAt/finishedAt/错误与可操作项；SUBMITTED不是SUCCEEDED |
| ChannelOrderVO | channelOrderNo/externalOrderRef/sourceOwner/导入及平台及履约及资金证据分状态/核心业务引用/版本；金额来源明确，不返回core物理自增id |
| OperationVO | operationNo/type/status/原业务引用/版本/可刷新可恢复标志/脱敏错误/平台证据引用；UNKNOWN不等于失败 |
| RawChannelWebhook | 原始字节、不变必要签名头、登记endpointNo；大小限制/签名/平台时间戳语义按获准协议，不先解析再重序列化验签 |
| ChannelAcknowledgement | 平台要求的HTTP状态/headers/body，只有原事件或重复事件已可靠保存后ACK；落库失败按协议非成功响应 |
| StatementVO / StatementLineVO | 账号级摘要需主体财务权限；门店只读已归属原行，未归属和跨店行不泄漏；缺金额/分配返回unknown而非0 |

## L4 同步及状态口径

全量串行覆盖远端异步期及可写子任务；SUBMITTED/UNKNOWN不因租约到期放行后续。仅原任务明确终结/取消或协议有序覆盖证明释放，缺查回能力保持阻断；新期望可排队但不发送。

首版同一连接的目录及订单只有一个sourceOwner，来源由连接冻结路由解析；仅同域多餐牌/多选品聚合。混合行业目录和订单均关闭，防止已发布但无法履约。

from/to为UTC Instant，按run.created_at划定同期任务，单一asOf读快照；成功率SUCCEEDED/(SUCCEEDED+FAILED+PARTIAL)，分母0返回null；UNKNOWN/执行中/CANCELLED单列。订单接入数按稳定外部订单身份去重，回调到达次数另计。

目录快照过期或sourceHash漂移须重新预览；明确快照不变但状态修复走原operation recover。批量目标首版调用多个单范围命令，避免新增一个无边界all-stores接口。全量菜单部分失败后的补发依旧按完整范围，不把失败项单独全量提交。

INSTANT模式可接/拒单；MARKETPLACE按原付款及仓库履约准入，不强行接单；LOCAL_LIFE按券核销授权，不套订单接单API。资金操作/券核销/账单及真实渠道各自有独立能力门禁；经营报表未在此重建。

[总览](../TDD-统一销售渠道接入.md) · [数据库](./统一销售渠道接入-数据库设计.md) · [代码](./统一销售渠道接入-代码清单.md)
