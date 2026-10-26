# 统一销售渠道接入 API清单

> 状态：全量候选设计；已编码子集见下方实施进度，候选数不代表完成数。由 `node scripts/gen-commerce-integration-design.mjs` 生成，勿手改。真源：[commerce-integration-design.json](./schema/commerce-integration-design.json)，2026-10-26。

## 当前实施进度

| 项 | 实际范围 |
| --- | --- |
| 状态 | C0_DRAFT_SLICE_VERIFIED |
| 已编码迁移表 | cim_provider, cim_product_profile, cim_account, cim_store_connection, cim_connection_revision, cim_request_journal |
| 已编码API子集 | COM-001, COM-002, COM-003, COM-004, COM-005, COM-009, COM-010, COM-011 |
| 限制 | 八个路由已编码，仅属主草稿管理及分页；连接创建另需可信远端归属SPI证明，无生产提供方时拒绝认领身份；筛选参数尚未实现并明确拒绝；授权/核验/启用、目录发送、订单导入及真实平台未开放。独立服务可构建，尚无远程Service客户端或内部业务端点，不能宣称已独立业务闭环。未执行生产建表或部署。 |
| 验证 | 集成宿主与独立server编译通过；29项Java专项通过（包括真实宿主Mapper扫描）；设计10、文档8通过；隔离原生MySQL迁移及约束通过；完整认证链、原生JDBC/Flyway并发与独立业务闭环尚未联验。 |

## L1 定位

一套B端API服务零售、餐饮，C端点餐/购物继续沿原业务API，不另造渠道C端下单入口。OPS只做基础配置和授权支持，回调不使用商家登录认证。下列为完整目标路由，实际已编码子集见实施进度；不是运行中OpenAPI发现结果。

## L2 分类与公共合同

公开/回调API共70项；内部业务Port共10组，内部方法不算公开API。

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
| COM-015 POST `/biz/x/commerce/stores/{storeNo}/catalog-scopes` | CreateCatalogScopeReq：connectionNo/remoteScopeKey/name/sourceRefs；完整替换范围按平台能力限制 | CatalogScopeVO | CatalogScopeService.createScope；cim_catalog_scope, cim_catalog_source, cim_request_journal；AC03；CATALOG |
| COM-016 GET `/biz/x/commerce/stores/{storeNo}/catalog-scopes` | CatalogScopeQuery：connectionNo/status/page/size | PageData<CatalogScopeVO> | CatalogScopeService.listScopes；cim_catalog_scope；AC03；CATALOG |
| COM-017 GET `/biz/x/commerce/catalog-scopes/{scopeNo}` | CatalogScopeRef：scopeNo | CatalogScopeVO | CatalogScopeService.getScope；cim_catalog_scope, cim_catalog_source；AC03；CATALOG |
| COM-018 PUT `/biz/x/commerce/catalog-scopes/{scopeNo}` | SaveCatalogSourcesReq：version/sourceOwner+sourceRef+selectionRevision；无原始价格覆盖 | CatalogScopeVO | CatalogScopeService.saveSources；cim_catalog_scope, cim_catalog_source, cim_request_journal；AC03；CATALOG |
| COM-019 GET `/biz/x/commerce/catalog-scopes/{scopeNo}/sync-policy` | CatalogScopeRef：scopeNo | SyncPolicyVO | SyncPolicyService.getPolicy；cim_sync_policy；AC04；SYNC |
| COM-020 PUT `/biz/x/commerce/catalog-scopes/{scopeNo}/sync-policy` | SaveSyncPolicyReq：version/enabled/onPublish/onAvailability/scheduledCheck/debounceSeconds/recoveryProfile | SyncPolicyVO | SyncPolicyService.savePolicy；cim_sync_policy, cim_request_journal；AC04；SYNC |
| COM-021 POST `/biz/x/commerce/catalog-scopes/{scopeNo}:preview` | PreviewCatalogReq：sourceRevisions/expectedScopeVersion；返回snapshotNo/hash/diff/blockers/expiresAt | CatalogPreviewVO | CatalogPublicationService.preview；cim_catalog_snapshot, cim_catalog_source, cim_resource_mapping_revision；AC03；SYNC |
| COM-022 POST `/biz/x/commerce/stores/{storeNo}/sync-runs` | SubmitSyncRunReq：scopeNo/snapshotNo/hash/expectedScopeVersion/reason；首版只提交CATALOG，其他类型走类型化接口 | SyncRunVO | SyncRunService.submit；cim_sync_run, cim_operation, cim_request_journal；AC04；SYNC |
| COM-023 GET `/biz/x/commerce/stores/{storeNo}/sync-runs` | SyncRunQuery：connectionNo/type/triggerType/status/from/to/page/size | PageData<SyncRunVO> | SyncRunService.listRuns；cim_sync_run；AC04；SYNC |
| COM-024 GET `/biz/x/commerce/sync-runs/{runNo}` | SyncRunRef：runNo；期望/已提交/最后全量确认及remoteConsistency分别输出 | SyncRunVO | SyncRunService.getRun；cim_sync_run, cim_catalog_scope, cim_operation；AC04；SYNC |
| COM-025 GET `/biz/x/commerce/sync-runs/{runNo}/items` | SyncItemQuery：runNo/attemptNo/objectType/result/page/size | PageData<SyncRunItemVO> | SyncRunService.listItems；cim_sync_run_item；AC04；SYNC |
| COM-026 POST `/biz/x/commerce/sync-runs/{runNo}:cancel-queued` | CancelQueuedSyncReq：version/reason；仅未发出动作，非远端撤回 | SyncRunVO | SyncRunService.cancelQueued；cim_sync_run, cim_operation, cim_request_journal；AC04；SYNC |
| COM-027 GET `/biz/x/commerce/stores/{storeNo}/resource-mappings` | MappingQuery：connectionNo/objectType/externalRef/sourceRef/page/size | PageData<ResourceMappingVO> | ResourceMappingService.listMappings；cim_resource_mapping, cim_resource_mapping_revision；AC05；CATALOG |
| COM-028 GET `/biz/x/commerce/resource-mappings/{mappingNo}/revisions` | MappingRevisionQuery：mappingNo/page/size | PageData<MappingRevisionVO> | ResourceMappingService.listRevisions；cim_resource_mapping_revision；AC05；CATALOG |
| COM-029 POST `/biz/x/commerce/resource-mappings` | CreateResourceMappingReq：connectionNo/objectType/externalIdentity/sourceOwner/sourceRef/sourceRevision；必须真源核验 | ResourceMappingVO | ResourceMappingService.createMapping；cim_resource_mapping, cim_resource_mapping_revision, cim_request_journal；AC05；CATALOG |
| COM-030 PUT `/biz/x/commerce/resource-mappings/{mappingNo}` | ReviseResourceMappingReq：version/来源修订/reason；不覆盖历史 | ResourceMappingVO | ResourceMappingService.reviseMapping；cim_resource_mapping_revision, cim_resource_mapping, cim_request_journal；AC05；CATALOG |
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
| ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort；FOOD/RETAIL | capture(CaptureCatalogCommand)->ChannelCatalogSnapshot | 完整目录、价格来源、税口径、修订及依赖；不授予平台重算价格权限 |
| ai.neargo.shop.spi.commerce.ChannelAvailabilitySourcePort；商品/独立库存原owner | captureAvailability(CaptureAvailabilityCommand)->AvailabilitySnapshot; captureInventory(CaptureInventoryCommand)->InventoryPublicationSnapshot | 可售量/仓库/渠道配额均取原owner，不建立渠道库存账 |
| ai.neargo.shop.spi.commerce.ChannelOrderIngestPort；FOOD/RETAIL | prepareImport(PrepareChannelOrderCommand)->ImportPreparation; getImport(ChannelOrderReference)->ImportPreparation; confirmAcceptance(ConfirmChannelAcceptanceCommand)->AcceptanceReceipt; cancelPreparedImport(CancelPreparedImportCommand)->ImportCancellationReceipt | 原核心订单、库存承诺、接单证据及一次派工；POSTPAY证明不可滥用 |
| ai.neargo.shop.spi.commerce.ChannelOrderAmendmentPort；原订单/行业售后 | apply(ApplyChannelAmendmentCommand)->AmendmentReceipt; getAmendment(ChannelAmendmentReference)->AmendmentReceipt | 原行/数量/金额和取消/替换守恒，资金动作需另授权 |
| ai.neargo.shop.spi.commerce.ChannelFulfillmentSourcePort；FOOD/零售/原物流履约 | capture(CaptureFulfillmentCommand)->FulfillmentSnapshot | 制作/拣货/包裹/交接均取已提交原事实；回传无权发明履约 |
| ai.neargo.shop.spi.commerce.ChannelRedemptionSourcePort；原权益/交易能力 | authorize(AuthorizeRedemptionCommand)->RedemptionAuthorization; confirm(ConfirmRedemptionCommand)->RedemptionReceipt; reverse(AuthorizeRedemptionReverseCommand)->RedemptionAuthorization | 先持久化原消费授权，再执行平台核销，未知查回；不造券余额 |
| ai.neargo.shop.spi.commerce.ChannelFinancialEvidencePort；独立支付/原资金所属域 | record(RecordChannelFinancialEvidenceCommand)->FinancialEvidenceReceipt; get(ChannelFinancialEvidenceReference)->FinancialEvidenceReceipt | 外部收款、退款、结算证据类型分离；不凭回调修改paid_at |
| ai.neargo.shop.spi.commerce.ChannelCredentialPort；原安全凭据能力 | resolve(ChannelCredentialReference)->ScopedCredentialHandle | 业务只传引用；签名/刷新组件受控使用，不出日志/公共响应 |
| ai.neargo.shop.spi.commerce.ChannelSourceValidationPort；各资源原owner或仅调用原Port的宿主只读组合 | validate(SourceValidationCommand)->SourceValidationReceipt | 校验entity/store/来源归属、资源类型、sourceRef/sourceRevision和授权销售条目；返回VALID/STALE/UNSUPPORTED/UNKNOWN、规范身份/原修订/hash/有效期及原回执；不跨库读原表。目录/映射/仓库来源均须typed验证，金额最终由原定价及订单prepareImport核验。 |
| ai.neargo.shop.spi.commerce.ChannelConnectionIdentityPort；可信平台授权/查询适配器 | verify(Identity)->Proof(VERIFIED/UNKNOWN/UNSUPPORTED, identity, receiptRef, verifiedAt, validUntil) | 事务外核验远端门店真实归属，本地事务锁账号/协议并复核冻结身份及有效期；无实现拒绝认领，不提供默认成功 |

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
