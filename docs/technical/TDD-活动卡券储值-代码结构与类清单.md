# 活动卡券储值代码结构与类清单

状态：Java后端首期范围已按实现验收；生产切换与前端页面后置 · 2026-10-20

执行状态与先后顺序见[Java后端独立任务表](./任务表-活动卡券储值-Java后端.md)，已实现目录内核直接复用，其余目标设计不视为已完成。

本文细化[系统总设计](./TDD-活动卡券储值-模块化系统设计.md)，与[功能矩阵](../requirements/活动卡券储值-模块功能矩阵.md)、[API契约](./TDD-活动卡券储值-API契约.md)、[数据库设计](./TDD-活动卡券储值-数据库设计.md)配套。除下方实施清单外，类名为实施建议，不表示已创建Java文件。标记“现有改造/复用”的类已在本地源码核对；其他均为拟新增。最终迁移需继续盘点旧marketing/mkt实现，不能只迁promotion。

## §0 设计约束

活动规则、资格与营销预算属于core；券/兑换券/付费卡定义、用户权益、储值与核销账属于pay。商品售价、订单和履约仍在core。40项活动模板是数据预置，不创建40个Service；6项售卡产品是目录配置，也不创建6个卡服务。共用COUNT、PERIOD_COUNT、PERIOD_DAILY策略。

总设计中的activity/catalog/grant等是职责分组。物理包以当前项目的entity/service/impl/mapper/dto/port习惯为准，不要求先整体重命名promotion。本文进一步明确拟新增类的物理归属。

## §1 目录与依赖

下列路径相对仓库根；Java包统一以ai.neargo.shop开头。

```text
backend/
  shop-base/src/main/java/ai/neargo/shop/spi/
    promotion/       活动参与端口及跨服务DTO
    entitlement/     权益目录、发行、使用、查询端口及DTO
    storedvalue/     储值端口及DTO
    marketing/       保留CouponPort等既有协议，逐步委派
  shop-core/src/main/java/ai/neargo/shop/
    promotion/
      entity/ mapper/ dto/ service/ service/impl/ port/ policy/ job/
    product/         CARD商品及权益版本绑定（沿既有商品包）
    trade/           售卡订单快照、本人授权、履约决策
    member/          会员识别（在既有会员域内落地）
    message/         复用消息能力
  pay/pay-domain/src/main/java/ai/neargo/shop/pay/
    entitlement/
      entity/ dto/ service/ service/impl/ policy/ posting/
      port/ event/ job/ migration/
    storedvalue/
      entity/ dto/ service/ service/impl/ policy/ posting/ port/
    mapper/entitlement/ mapper/storedvalue/  沿现有pay.mapper扫描根
    client/          扩展现有PayInternalApi与PayInternalPaths
  shop-app/src/main/java/ai/neargo/shop/
    portal/biz/ portal/mp/ portal/ops/   HTTP适配与鉴权
    payclient/ payclient/impl/          沿已有本地/远程门面模式
    paybridge/                         活动、售卡、使用协调器
```

依赖：shop-app→core服务/共享SPI/pay客户端；core→shop-base SPI；pay-domain→shop-base与既有基础依赖。core不引用pay实体/Mapper，pay不引用shop-core服务。HTTP Controller不访问Mapper；跨服务DTO不带数据库实体或Spring事务对象。

pay新增Mapper物理位置为pay/mapper/entitlement和pay/mapper/storedvalue，沿现有扫描根，不放在扫描范围外的pay/entitlement/mapper；必须绑定paySqlSessionFactory，写服务显式使用payTxManager；core使用既有核心数据源配置。跨模块编排不包跨库@Transactional，远程调用不放进持锁事务。model/policy采用纯Java；只有有替换边界的服务定义接口+Impl，纯策略和不可变record无需空接口。

## §2 核心服务类清单

以下路径相对shop-core的ai/neargo/shop/；service接口的实现统一落同域service/impl/{名称}Impl.java。

| 类/接口 | 位置 | 状态 | 职责与关键方法 | 表/API |
|---|---|---|---|---|
| ActivityService / ActivityServiceImpl | promotion/service及impl | 现有改造 | saveDraft、publish、changeStatus；发布固定版本 | pmt_activity/version；C03–C06 |
| ActivityPricingService / ActivityPricingServiceImpl | promotion/service及impl | 现有改造 | 仅ORDER活动定价；隔离储值场景 | 既有规则；旧定价接口 |
| PlatformActivityService / PlatformActivityServiceImpl | promotion/service及impl | 现有改造 | 平台活动/报名治理，沿既有身份和权限 | 原平台活动接口 |
| MarketingSummaryService / MarketingSummaryServiceImpl | promotion/service及impl | 现有改造 | 分项统计，不把储值赠金加进订单折扣 | pmt_effect_projection；C10 |
| ActivityVersionService / Impl | promotion/service及impl | 新增 | publishSnapshot、getPublished；发布前核实pay模板版本 | pmt_activity_version |
| ActivityPresetService / Impl | promotion/service及impl | 新增 | list、instantiateDraft、filterCapabilities；40+6目录分类 | pmt_preset_template；C01/C02 |
| ActivityParticipationService / Impl | promotion/service及impl | 新增 | reserve、confirm、release、query；营销与个人限额同事务 | budget/participant/reservation；I01–I04 |
| ActivityParticipationPortImpl | promotion/port | 新增 | 实现共享ActivityParticipationPort，转换DTO | I01–I04 |
| ActivityRuleValidator | promotion/policy | 新增 | 按scene/template/schemaVersion验证范围、排期、档位与利益引用 | C04/C05 |
| ActivityEligibilityPolicy | promotion/policy | 新增 | 受众/门店/时间/跨版本限购判定，无余额写入 | I01 |
| MarketingEffectProjector | promotion/service | 新增 | 按eventNo+activityNo消费pay事实，更新派生统计 | pmt_effect_projection |
| ActivityReservationRecoveryJob | promotion/job | 新增 | 查询pay终态，确认或释放；未知不释放 | pmt_activity_reservation |
| CardProductBindingService / Impl | product/service及impl | 新增 | bind、validatePublishedVersion；售价仍用原商品能力 | prd_entitlement_binding；C07 |
| CardSaleSnapshotService / Impl | trade/service及impl | 新增 | 固化订单行/件/件内权益序号、模板版本与付款事实 | 既有订单行扩展；售卡流程 |
| EntitlementAuthorizationService / Impl | trade/service及impl | 新增 | stageTrustedIntent、confirm；订单+明细快照重验及本人幂等授权 | C09；V427 |
| EntitlementSourceDecisionService / Impl、EntitlementSourceDecisionPortImpl | trade/service及impl、trade/entitlement/port | 新增 | 源事务内持久终态决策、按SourceRef/authorizationRef反查 | pay恢复查询；V427 |
| MemberCodeResolveService / Impl | core.member.service及impl | JB-010实施 | 随机凭证摘要校验、短期顾客引用；不复用登录token | mbr_identity_ticket；C08/C11 |

ActivityVersionService只负责版本快照，ActivityService负责管理入口；参与服务不再次实现活动发布。订单意图、订单Outbox优先沿现有持久机制，若缺失需补充正式DDL与契约后实施，不允许用本地Map保存授权决策。

## §3 支付服务类清单

以下路径相对pay-domain的ai/neargo/shop/pay/。接口与Impl按既有service/impl风格配对。

| 类/接口 | 子包 | 状态 | 职责与关键方法 | 表/API |
|---|---|---|---|---|
| EntitlementCatalogService / Impl | entitlement/service及impl | 新增 | saveDraft、publish、changeStatus；模板版本不可变 | ent_template/version；P01–P05 |
| EntitlementIssuePolicyService / Impl | entitlement/service及impl | 新增 | updateLimit、reserveCapacity、commitCapacity、releaseCapacity | ent_issue_policy/recipient；P06 |
| EntitlementIssueBatchService / Impl | entitlement/service及impl | 新增 | preflight、start、getProgress；先预留发行容量，按原grant重试 | ent_issue_batch；P07/P08 |
| EntitlementGrantService / Impl | entitlement/service及impl | 新增 | prepare、commit、abort、query；来源唯一发放 | ent_grant/instance/ledger；I05–I07/P11 |
| EntitlementUseService / Impl | entitlement/service及impl | 新增 | preview、prepare、commit、release、query、reverse；状态互斥 | intent/allocation/usage/reversal；P12–P15/I08–I11 |
| EntitlementLifecycleService / Impl | entitlement/service及impl | 新增 | activationQuote、activateOnCommit、expire、freeze | ent_instance/bucket；P16/P17/O07 |
| EntitlementQueryService / Impl | entitlement/service及impl | 新增 | 本人券包、权益详情、额度和使用记录；白名单DTO | P09/P10/P17/P25/P26/P29 |
| EntitlementCodeService / Impl | entitlement/service及impl | 新增 | issueToken、resolveToken、invalidate；仅兑换券用途 | ent_code_token；P30/P12 |
| EntitlementPostingService | entitlement/posting | 新增 | 在调用方pay事务内改次数、bucket、分配及ledger；不自行开启独立事务 | ent_instance/bucket/ledger |
| EntitlementRuleValidator | entitlement/policy | 新增 | 模板kind/benefit/使用模型/有效期组合校验 | P02–P04 |
| CountUsagePolicy | entitlement/policy | 新增 | 总次数与冻结次数校验 | COUNT |
| PeriodCountUsagePolicy | entitlement/policy | 新增 | 有效窗口内总次数校验 | PERIOD_COUNT |
| PeriodDailyUsagePolicy | entitlement/policy | 新增 | 指定服务日每日额度校验，无结转 | PERIOD_DAILY |
| EntitlementValidityPolicy | entitlement/policy | 新增 | 支付起算/首用激活、自然月/滚动天数，半开区间 | 实例快照 |
| CouponQuotePolicy | entitlement/policy | 新增迁入 | 迁移既有门槛、折扣及分摊算法，唯一计算实现 | P15/旧CouponPort |
| StoredValueRechargeService / Impl | storedvalue/service及impl | 新增 | create、submit、receive、cancel、get；已实收禁止取消 | sv_recharge；P18–P21/P27 |
| StoredValueCreditService / Impl | storedvalue/service及impl | 新增 | credit、query；实收事实验证，按充值号唯一入账 | sv_account/lot/voucher/entry；I12 |
| StoredValueRedemptionService / Impl | storedvalue/service及impl | 新增 | quote/预占/确认/释放；共享统一使用头 | sv_hold_allocation+ent_use_intent；P22/P31/I08–I10 |
| StoredValuePostingService | storedvalue/posting | 新增 | 账户/批次/借贷分录/凭证同事务；内部双池守恒 | sv_account/lot/voucher/entry |
| StoredValueAllocationPolicy | storedvalue/policy | 新增 | 按既定范围与扣款顺序分配批次/双池；纯计算 | sv_lot/hold_allocation |
| StoredValueQueryService / Impl | storedvalue/service及impl | 新增 | 门店目录、本人核销、商家总余额查询及签名游标；app DTO不暴露pay accountNo | P23/P24/P28 |
| StoredValueStoreProjector | storedvalue/service | 新增 | 成功储值标记与max事件时间，乱序不倒退 | sv_user_store_index |
| EntitlementReconciliationService / Impl | entitlement/service及impl | 新增 | 次数、冻结、发行与储值分录分别对账；不跨量纲相加 | O05 |
| EntitlementExceptionService / Impl | entitlement/service及impl | 新增 | 列表、证据、查源、恢复原grant；不任意调余额 | pay_ent_exception；O01–O04 |
| EntitlementCommandDeduplicator | entitlement/service | 新增 | 幂等键/payloadHash、稳定响应同业务事务 | pay_ent_command_dedup |
| PayEntitlementEventPublisher / PayEntitlementOutboxRelay / PayEntitlementInboxProcessor | entitlement/event | JB-014已实现 | payTx同事务写类型白名单事件；提交后稳定eventNo幂等桥接至sys_outbox，启动/周期扫描恢复；inbox按tenant+consumer+eventNo锁定去重并同业务事务应用 | pay_ent_outbox/inbox、sys_outbox |
| EntitlementRecoveryJob | entitlement/job | 新增 | 查源决策恢复发放/预占/充值，持久租约防重复执行 | 在途业务/异常 |
| EntitlementExpiryJob | entitlement/job | 新增 | 到期状态与提醒事件；查询也校验真实期限 | ent_instance/事件 |
| EntitlementMigrationService / Impl | entitlement/migration | 新增 | 批次导入、对账、owner切换；不触发新发行通知 | ent_owner_registry |

统一使用入口按assetKind委派券/卡策略或StoredValueRedemptionService，后者不得反向再调用统一入口形成递归。单次使用由一个pay业务事务协调全部记账；posting方法不得使用REQUIRES_NEW。冻结金额和余次不通过Controller直接更新。

### 首批基础实现

SourceRef在shop-base/spi/entitlement，生成稳定来源键，不代替身份或源事实认证。UsageQuota在pay/entitlement/policy，提供不可变Count/Daily计数与reserve/commit/release；后续使用策略复用该计算规则，目前未接入数据库。

## §4 共享端口与协议类型

端口只在shop-base/spi声明；实现放业务所有者域内port包。以下均拟新增，既有CouponPort另列。

| 接口 | SPI子包 | 核心方法 | 实现位置 |
|---|---|---|---|
| ActivityParticipationPort | promotion | reserve/confirm/release/query | core.promotion.port.ActivityParticipationPortImpl |
| EntitlementCatalogPort | entitlement | getPublishedVersion/validateReference | pay.entitlement.port.EntitlementCatalogPortImpl |
| EntitlementGrantPort | entitlement | prepare/commit/abort/query | pay.entitlement.port.EntitlementGrantPortImpl |
| EntitlementUsePort | entitlement | quote/preview/prepare/commit/release/reverse/query | pay.entitlement.port.EntitlementUsePortImpl |
| EntitlementQueryPort | entitlement | mine/detail/usage/allowances | pay.entitlement.port.EntitlementQueryPortImpl |
| StoredValuePort | storedvalue | createRecharge/submit/receive/cancel/credit/queryRecharge/balance/stores | pay.storedvalue.port.StoredValuePortImpl |
| CouponPort | marketing | 保留allocate/markUsed/release及原有查询 | 现有调用方保留，由迁移路由适配pay，最终唯一pay实现 |

协议DTO建议record：SourceRef、DecisionRef、ParticipationPermit、ReserveActivity、ConfirmParticipation、ReleaseParticipation、GrantCommand、GrantDecision、AbortGrant、UseCommand、UseDecision、CancelUse、OrderBenefitReverse、CreditRecharge；字段以API契约§5为准。SourceRef的订单行/单件/件内权益序号全部纳入稳定来源唯一性。命令/结果放对应SPI子包dto，业务内部实体不穿透端口。

新增端口的query方法是恢复所需基础契约，不因API编号只列写操作而遗漏。部署为远程pay时，通过现有PayInternalApi/PayInternalPaths扩展方法和路径；服务身份、签名、超时语义沿已有配置。ActivityParticipationPort反向调用core需单独内部适配，禁止pay引用core实现。

## §5 应用门面、Controller与协调器

以下路径相对shop-app的ai/neargo/shop/。新增Controller名避免与现有路由重复注册。DTO按端放portal对应dto，映射器显式挑字段；HTTP DTO与SPI DTO可以不同。

| 类/接口 | 位置 | 状态 | API/职责 |
|---|---|---|---|
| BizActivityController | portal/biz | 现有改造 | C03–C06及既有详情/冲突，委派core |
| BizActivityPresetController | portal/biz | 新增 | C01/C02，能力筛选目录 |
| BizCardProductBindingController | portal/biz | 新增 | C07 |
| BizMemberCodeController / MpMemberCodeController | portal/biz、mp | JB-010实施 | C08商家识别 / C11本人签发 |
| MpEntitlementAuthorizationController | portal/mp | 新增 | C09，本人确认 |
| BizMarketingSummaryController | portal/biz | 新增或兼容既有摘要入口 | C10 |
| BizEntitlementTemplateController | portal/biz | 新增 | P01–P06 |
| BizEntitlementIssueController | portal/biz | 新增 | P07/P08 |
| BizEntitlementUseController | portal/biz | 新增 | P12–P14/P26/P29 |
| BizStoredValueController | portal/biz | 新增 | P18–P22/P27/P28/P31 |
| MpEntitlementController | portal/mp | 新增 | P09–P11/P15–P17/P25/P30 |
| MpStoredValueController | portal/mp | 新增 | P23/P24；只返回本人简明记录 |
| OpsEntitlementController | portal/ops | 新增 | O01–O05/O07 |
| OpsPromotionController | portal/ops | 现有改造 | 核心活动治理保留；权益统计委派pay |
| BizCouponController、MpCouponCenterController | portal/biz、portal/mp | 现有兼容改造 | 旧URL/DTO保持，内部经兼容门面路由 |
| ActivityBenefitCoordinator | paybridge | 新增 | core reserve→pay prepare/commit→core confirm |
| StoredValueRechargeCoordinator | paybridge | 新增 | 充值资格与充值源号绑定，实收后入账/回写资格 |
| CardSaleCoordinator | paybridge | 新增 | 订单支付事实→按件发卡，重投不重发 |
| EntitlementOrderCoordinator | paybridge | 新增 | 本人授权/源决策→pay预占确认释放 |
| BizEntitlementAppService | payclient及impl | 新增接口及本地/远程实现 | B券卡管理、发放与核销门面 |
| MpEntitlementAppService | payclient及impl | 新增接口及本地/远程实现 | C权益与储值记录查询，DTO白名单 |
| BizStoredValueAppService | payclient及impl | 新增接口及本地/远程实现 | B储值办理和进度 |
| OpsEntitlementAppService | payclient及impl | 新增接口及本地/远程实现 | O异常、对账、受限恢复 |
| LegacyCouponCompatibilityService | paybridge | 新增 | 旧DTO映射、owner路由；业务算法委派唯一owner |
| EntitlementNotificationSubscriber | paybridge | 新增 | pay事件转既有MessageService，去重及收件人校验 |

本地实现名采用{名称}Impl、远程实现采用Remote{名称}，由既有PayClientConfig模式选择，不同时注册两个同接口默认Bean。O06复用既有通知日志Controller，禁止创建重复GET /ops/notify-logs。订单支付/取消/售后订阅适配沿既有事件机制接入，不在Controller请求结束后用内存线程补发卡。

HTTP请求类、响应类的完整清单以API契约§6/§7的schema为准；Java目标名保持一致，如ActivityDraft、TemplateDraft、UseSubmitCommand、RechargeCreateCommand、StoreItem、UsageDetail。Page/CursorPage/ApiResponse复用公共类型。EntitlementItem/Detail等合写名称在Java拆为EntitlementItem与EntitlementDetail两个record，禁止把运营内部VO直接返回C端。

## §6 数据实体与Mapper逐表清单

每行对应数据库设计的一张目标逻辑表。新增实体可按现有BaseEntity惯例对齐审计字段；现有PmtActivity等保留包位置。核心Mapper可沿PromotionMappers既有聚合方式扩展；表中给出逻辑Mapper名，不强制每个独立文件。pay侧拟用独立Mapper接口方便扫描和事务归属审查。

| 所有者 | 表 | 实体 | Mapper | 状态 |
|---|---|---|---|---|
| promotion | pmt_preset_template | PmtPresetTemplate | PmtPresetTemplateMapper | 拟新增；同名存量实施前核对 |
| promotion | pmt_activity | PmtActivity | PmtActivityMapper | 现有实体改造 |
| promotion | pmt_activity_version | PmtActivityVersion | PmtActivityVersionMapper | 拟新增；同名存量实施前核对 |
| promotion | pmt_activity_scope | PmtActivityScope | PmtActivityScopeMapper | 拟新增；同名存量实施前核对 |
| promotion | pmt_activity_audience | PmtActivityAudience | PmtActivityAudienceMapper | 现有实体改造 |
| promotion | pmt_recharge_tier | PmtRechargeTier | PmtRechargeTierMapper | 拟新增；同名存量实施前核对 |
| promotion | pmt_activity_budget | PmtActivityBudget | PmtActivityBudgetMapper | 拟新增；同名存量实施前核对 |
| promotion | pmt_activity_participant | PmtActivityParticipant | PmtActivityParticipantMapper | 拟新增；同名存量实施前核对 |
| promotion | pmt_activity_reservation | PmtActivityReservation | PmtActivityReservationMapper | 拟新增；同名存量实施前核对 |
| promotion | pmt_effect_projection | PmtEffectProjection | PmtEffectProjectionMapper | 拟新增；同名存量实施前核对 |
| promotion | pmt_command_dedup | PmtCommandDedup | PmtCommandDedupMapper | 拟新增；同名存量实施前核对 |
| product | prd_entitlement_binding | PrdEntitlementBinding | PrdEntitlementBindingMapper | 拟新增；同名存量实施前核对 |
| pay.entitlement | ent_template | EntTemplate | EntTemplateMapper | 已实现；V425及实体Mapper，见JB-004 |
| pay.entitlement | ent_template_version | EntTemplateVersion | EntTemplateVersionMapper | 已实现；V425及实体Mapper，见JB-004 |
| pay.entitlement | ent_issue_policy | EntIssuePolicy | EntIssuePolicyMapper | 拟新增；同名存量实施前核对 |
| pay.entitlement | ent_issue_recipient | EntIssueRecipient | EntIssueRecipientMapper | 拟新增；同名存量实施前核对 |
| pay.entitlement | ent_issue_batch | EntIssueBatch | EntIssueBatchMapper | 拟新增；同名存量实施前核对 |
| pay.entitlement | ent_grant | EntGrant | EntGrantMapper | 拟新增；同名存量实施前核对 |
| pay.entitlement | ent_instance | EntInstance | EntInstanceMapper | 拟新增；同名存量实施前核对 |
| pay.entitlement | ent_period_bucket | EntPeriodBucket | EntPeriodBucketMapper | 拟新增；同名存量实施前核对 |
| pay.entitlement | ent_use_intent | EntUseIntent | EntUseIntentMapper | 拟新增；同名存量实施前核对 |
| pay.entitlement | ent_use_allocation | EntUseAllocation | EntUseAllocationMapper | 拟新增；同名存量实施前核对 |
| pay.entitlement | ent_usage | EntUsage | EntUsageMapper | 拟新增；同名存量实施前核对 |
| pay.entitlement | ent_ledger | EntLedger | EntLedgerMapper | 拟新增；同名存量实施前核对 |
| pay.entitlement | ent_code_token | EntCodeToken | EntCodeTokenMapper | 拟新增；同名存量实施前核对 |
| pay.storedvalue | sv_account | SvAccount | SvAccountMapper | 拟新增；同名存量实施前核对 |
| pay.storedvalue | sv_recharge | SvRecharge | SvRechargeMapper | 拟新增；同名存量实施前核对 |
| pay.storedvalue | sv_lot | SvLot | SvLotMapper | 拟新增；同名存量实施前核对 |
| pay.storedvalue | sv_hold_allocation | SvHoldAllocation | SvHoldAllocationMapper | 拟新增；同名存量实施前核对 |
| pay.storedvalue | sv_voucher | SvVoucher | SvVoucherMapper | 拟新增；同名存量实施前核对 |
| pay.storedvalue | sv_entry | SvEntry | SvEntryMapper | 拟新增；同名存量实施前核对 |
| pay.storedvalue | sv_user_store_index | SvUserStoreIndex | SvUserStoreIndexMapper | 拟新增；同名存量实施前核对 |
| pay.entitlement | ent_owner_registry | EntOwnerRegistry | EntOwnerRegistryMapper | 拟新增；同名存量实施前核对 |
| pay.entitlement | pay_ent_outbox | PayEntOutbox | PayEntOutboxMapper | 拟新增；同名存量实施前核对 |
| pay.entitlement | pay_ent_inbox | PayEntInbox | PayEntInboxMapper | 拟新增；同名存量实施前核对 |
| pay.entitlement | pay_ent_exception | PayEntException | PayEntExceptionMapper | V452已编码，异常捕获与受控查询内核；恢复接线待续 |
| pay.entitlement | pay_ent_command_dedup | PayEntCommandDedup | PayEntCommandDedupMapper | 已实现；V425及实体Mapper，见JB-004 |
| pay.entitlement | ent_use_reversal | EntUseReversal | EntUseReversalMapper | 拟新增；同名存量实施前核对 |

核心预算/幂等Mapper只被对应参与服务使用；pay Mapper只被支付业务/记账/查询服务使用。迁移工具不得成为日常发券入口，历史导入不能调用发放Service导致再次发行。

## §7 旧类改造与退出清单

| 现有类 | 目标处理 | 退出条件 |
|---|---|---|
| PmtCoupon、PmtCouponScope、PmtUserCoupon、PmtCouponIssue | 保留迁移读取与旧批处理，目标映射pay权益模型 | 已迁批不再写旧表，所有历史核对完成后只读归档 |
| CouponService/PmtCouponServiceImpl | 旧模板/发行入口委派兼容门面 | 不再持有已迁资产写逻辑 |
| CouponAllocService/CouponAllocServiceImpl | 算法迁至pay CouponQuotePolicy，旧接口适配 | 新旧订单报价一致，唯一计算实现 |
| CouponRedeemService/CouponRedeemServiceImpl | 旧兑换入口委派pay统一使用服务 | 旧码/余次/原使用流水可验证一致 |
| PmtApply | core保留活动优惠事实，券使用迁pay，core只留派生查询 | 不重复汇总券折扣或储值消费 |
| PromotionMappers | 核心活动Mapper保留，已迁券写Mapper停止调用 | 批次owner切换并排空在途 |
| CouponPort调用方 | 原签名兼容，逐步接入预占/确认语义 | 核对markUsed原调用时点，不仅重命名成commit |

逐实例owner由ent_owner_registry决定；双读比对仅用于迁移校验，不能“新表找不到就写旧表”。旧mkt_coupon相关实现也需同样盘点，不能由上表推断已覆盖全部旧模型。

## §8 关键调用链与实施顺序

充值：BizStoredValueController→BizStoredValueAppService→StoredValueRechargeCoordinator→ActivityParticipationPort.reserve→StoredValuePort.createRecharge；商家receive后，StoredValueCreditService→StoredValuePostingService→同事务Outbox→core确认预算/消息通知。

售卡：core订单权威支付事实→CardSaleCoordinator→EntitlementGrantPort.prepare/commit→EntitlementGrantService→实例/发行账/Outbox。单行多件及每件多个权益均以完整来源键去重。

核销：储值由BizStoredValueController的P31按真实服务SKU计价建单并取得只读quote，核心订单冻结preview与授权意图；C通过既有/mp/orders/{orderNo}/entitlement-authorizations本人确认；商家P22提交后由EntitlementServiceUseCoordinator核验订单/授权→EntitlementUsePort.prepare→pay按储值批次分配预占→core持久化成功决策→pay commit→使用流水、凭证与事件。次卡/月卡走余次与服务日bucket。未知结果由query与RecoveryJob恢复，不能新建操作再扣。

C端记录：MpStoredValueController→StoredValueRechargeAppService→StoredValueQueryService→仅本人有成功储值记录的门店，按最后成功储值/核销倒序→选择门店后读取已提交消费明细。页面及通知不暴露本金赠金拆分、不新增储值专用码、不新增线上退款入口。

落地依序：共享契约/Mapper归属→pay权益模板发行账→旧券迁移与兼容→core活动参与协议→储值闭环→付费卡与专用UI→三端权限/通知/对账。任务边界沿总设计D01–D07，不能先接UI后补幂等与余额约束。

本轮交付仅代码设计文档及索引关联；具体Java实现、正式迁移与运行契约仍需按任务逐步落地。

## §9 当前实际实现清单

- shop-base：SourceRef、EntitlementDefinition（含Validity/RedemptionScope）、EntitlementCatalogPort（ReferenceQuery/PublishedDefinition）。JB-008新增EntitlementContext、DecisionRef、SourceDecisionPort、ActivityParticipationPort、EntitlementGrantPort、EntitlementUsePort、EntitlementQueryPort、StoredValuePort及嵌套DTO；均为协议，除原目录端口外尚未接业务实现。通用CursorPage持有不可变records、nextCursor与一致的hasMore。
- pay-domain：CatalogScope、CatalogViews、EntTemplate、EntTemplateVersion、PayEntCommandDedup；EntitlementRuleValidator、UsageQuota、EntitlementValidityPolicy、CountUsagePolicy、PeriodCountUsagePolicy；EntitlementCatalogService及Impl、EntitlementCatalogPortImpl、EntitlementCommandDeduplicator。
- pay-domain/pay/mapper/entitlement：EntTemplateMapper、EntTemplateVersionMapper、PayEntCommandDedupMapper，沿现有pay.mapper扫描根绑定。
- shop-app：V425目录三表、生成H2 schema、EntitlementCatalogFlowTest。尚无新商家/C端/运营Controller或远程目录门面。

目录已委派EntitlementCommandDeduplicator唯一实现；该组件要求已有pay事务，后续发放/核销/充值复用，不保留目录私有第二套算法。范围事实预检现已由core EntitlementScopeValidationService/Impl、product ProductScopeFactsPortImpl及merchant MerchantScopeFactsPortImpl提供；shop-base持有两个事实SPI，shop-app EntitlementScopeResolver从商家会话构造可信范围，完整定义另核实赠品归属。对外开放前仍需HTTP权限接线。本清单不改变未实现类的状态。


JB-008协议补充：EntitlementUsePort提供QuoteCommand/QuoteView/CouponAllocation、PreviewQuery/PreviewView；UseCommand绑定expectedAssetRevision及previewRef/quoteRef。EntitlementQueryPort提供ActivationQuote；预览不激活或扣减。对应EntitlementPreviewContractTest，尚无业务实现。

支撑持久化类：core.trade已实现OrdEntAuthorization/Mapper、OrdEntSourceDecision/Mapper（JB-011）和OrdCardSaleSnapshot/Mapper（JB-012）；`CardSaleSnapshotPlan`按订单行、件数及件内权益生成稳定来源与哈希，商家主体/门店由每行可信子单事实确定，`CardSaleSnapshotService/Impl`幂等落库并仅从主订单支付事实固化paidFactNo/paidAt。V428创建快照表；order_line_no由core基于已落库ord_item主键派生并只写入快照，不更改通用订单表。pay侧PayEntReconRun/Mapper、PayEntReconDiff/Mapper仍归JB-046；恢复租约复用shedlock（JB-051），无新增通用租约实体。


JB-008门禁基础：pay.entitlement.config.PayEntitlementProperties与policy.PayEntitlementFeatureGate；core promotion.config.PromotionFeatureProperties与policy.PromotionFeatureGate；shop-base spi.promotion.ActivityCapability。7个开关默认关闭、35个能力码仅定义词汇不宣称就绪。目录写入/新引用已调用门禁，幂等响应回放及历史读取不受阻，其他消费者按所属任务实现；未新增公开Controller或实际远程调用。ErrorCode增加40040及三语消息。


### JB-010落地类（2026-10-18）

| 模块/包 | 类清单 | 职责 |
|---|---|---|
| shop-base/spi.member | CustomerIdentityPort、ReferenceQuery、ResolvedCustomer | 跨域最小身份事实，不含实体/余额 |
| shop-base/spi.user | UserScopeFactsPort、ActiveUser | 显式租户及有效消费者事实 |
| core.user.port | UserScopeFactsPortImpl | 用户owner查询有效账户 |
| core.member.config/dto | MemberCodeProperties、MemberCodeScope、MemberCodeViews、MemberResolveCommand | 缺省关闭、可信作用域、脱敏响应/请求 |
| core.member.entity/mapper | MbrIdentityTicket、MemberIdentityTicketMapper | V426核心持久摘要凭证 |
| core.member.service/impl | MemberCodeResolveService、MemberCodeResolveServiceImpl | 统一码签发60秒、解析及绑定引用120秒 |
| core.member.port | MemberCustomerIdentityPortImpl | 本地SPI委派；远程HTTP由JB-041实现 |
| app.portal.mp/biz | MpMemberCodeController、BizMemberCodeController | C11本人签发、C08带权限和门店子集解析 |
| app测试 | MemberCodeResolveFlowTest、MemberCodeHttpFlowTest | 数据库隔离/TTL/会话/角色/门店子集 |

识别成功不是扣款授权，后续消费者本人确认依JB-011。活动与权益owner不因统一会员码而改变；pay不存会员码、登录token或core用户实体。


## 第一阶段实际落地补充（2026-10-19）

| 模块 | 新增或接入的实现 |
|---|---|
| shop-base/spi.promotion | ActivityParticipationInternal共享传输DTO；ActivityParticipationOutcomePort可信来源终态扩展点 |
| shop-base/spi.marketing | ActivityOrderFactsPort真实订单事实契约 |
| core.promotion | ActivityPresetService/Impl；ActivityVersionService/Impl；ActivityGovernanceService/Impl；ActivityParticipationService/Impl与ActivityParticipationPortImpl；ActivityEligibilityPolicy、ActivityPresetExecutionPolicy |
| core.promotion定价 | UnifiedOrderActivityPricingService/Impl、UnifiedOrderActivityCapabilityEvidence；既有ActivityPricingServiceImpl调用新执行器并保留旧路径 |
| core.promotion统计恢复 | MarketingEffectProjector；ActivityMarketingSummaryService/Impl；ActivityReservationRecoveryJob复用ShedLock |
| core.promotion持久化 | Pmt预置/版本/范围/受众/档位/预算/参与者/参与凭证/投影/幂等实体Mapper；UnifiedActivityManagementMapper显式CAS；UnifiedOrderActivityAccountingMapper条件返还 |
| core.trade | ActivityOrderFactsPortImpl由交易owner读取；OrderServiceImpl取消后释放活动使用资源 |
| core/merchant/platform | MerchantActivityCapabilityEvidence经MerchantScopeFactsPort及MasterDataPort核验持久主体、行业注册与资质事实 |
| app.portal | BizActivityPresetController、BizVersionedActivityController、BizActivityMarketingSummaryController；OpsVersionedActivityController；InternalActivityParticipationEndpoint |

Service遵循接口/Impl分层，跨域Port仅在shop-base SPI，聚合更新Mapper显式@Mapper并绑核心SqlSessionFactory。无真实pay来源提供者时拒绝新增参与，不注册假成功适配器。完整测试、迁移及阶段边界见[第一阶段交付报告](./活动第一阶段-交付与验收.md)。


### 第二阶段B1代码落点（编码中）

pay.entitlement新增IssuePolicyViews、IssueBatchViews、PreparedGrant；EntIssuePolicy/EntIssueRecipient/EntGrant/EntInstance/EntLedger/EntIssueBatch及pay.mapper.entitlement同名Mapper。EntitlementIssuePolicyService/Impl管理跨版本授权容量；EntitlementGrantService/Impl负责源事实预检，EntitlementGrantWriter负责pay事务；EntitlementGrantPortImpl委派本地SPI；BatchGrantSourcePortImpl从持久批次证明PAY/TARGETED来源；EntitlementIssueBatchService/Impl及Writer负责冻结受众和逐项事务。Writer为组件而非第二套Service接口，避免在持锁事务跨域取事实。

shop-base新增EntitlementGrantSourcePort和EntitlementIssueAudiencePort，供来源/受众owner验证，不接收公共客户端自报用户列表。真实活动/售卡适配后续由031/032/040接入。测试EntitlementIssuePolicyFlowTest、EntitlementGrantFlowTest、EntitlementIssueBatchFlowTest使用真实pay服务/Mapper/事务，EntitlementIssuanceNativeMysqlTest及verify-entitlement-issuance-mysql.mjs验证隔离原生MySQL容量/回滚/源唯一约束。本记录尚非验收结论。


第二阶段已实现使用领域：pay的UseService/Writer、LifecycleService/Impl、QueryService/Impl、PostingService/Impl，Use/Query/UseFacts Port适配；UseSnapshots冻结原证明，CursorCodec签名分页。core新增EntitlementOrderSnapshotPolicy、trade.entitlement.port.EntitlementUseSourcePortImpl；LegacyCouponOrderFactsPort在app组合marketing.CouponService与promotion.CouponAllocService的精确占用事实，不能靠显示名称。ProductScopeFactsPort提供订单类目的已知祖先。订单协调、负向源围栏、reverse/code仍在后续020/022/023/051中推进。


补充pay.entitlement的PayEntSourceFence/Mapper、EntitlementSourceFence（技术锁与负向事实，不是业务权益Service）；GrantWriter/UseWriter共享其来源锁，调用者在事务外取得SourceDecision。负向事实的存续不依赖请求超时或提案有效期。SourceDecisionService统一UTC毫秒时间，产品类目事实只追加同租户已知父级。020/051继续承担订单协调与恢复调度，不以本底座交付替代它们。


### JB-020订单券协调切片

| 模块 | 类/接口 | 责任 |
|---|---|---|
| base | PaymentAttemptFactsPort、EntitlementOrderEvents.UseDecided | 历史收款事实、只含租户/操作标识的终态事件 |
| pay.entitlement.port | PaymentAttemptFactsPortImpl | 读取全部PAY尝试，含失败/关闭；不从无回调推断未支付 |
| core.trade | EntitlementOrderUseService/Impl、OrderEntitlementUseView | stage/apply/seal/decision/acknowledge；只用核心事务 |
| core.trade.entitlement.entity/mapper | OrdEntOrderUse/EntitlementOrderUseMapper、OrdEntPaymentSeal/EntitlementPaymentSealMapper | 持久意图与现金支付金额封存；V438 |
| app.paybridge | EntitlementOrderCoordinator/Impl、EntitlementOrderUseDecisionConsumer | 挂起核心事务后逐个调用owner，丢响应重放原命令，Outbox恢复原操作 |

ORDER_COUPON协调切片验收后仍不能关闭JB-020：真实服务核销意图、储值使用与对应远程接线须继续交付。


### JB-022受限回补切片

| 模块 | 类/接口 | 责任 |
|---|---|---|
| pay.entitlement | EntitlementReversalService/Impl、EntitlementReversalWriter | 先查可信售后决策，再同pay事务处理原分配累计、退回次数、反向账、原消费状态及事件 |
| pay.entity/mapper | EntUseReversal、EntUseReversalMapper | V439，原回补号/售后唯一性、不可变命令与query |
| base | EntitlementUsePort.ReversalView、queryReverse；EntitlementEvents.EntitlementUseReversed | 独立回补query命名空间、版本化反向事实，不开放储值/付费卡退款 |
| core.trade | EntitlementSourceDecisionPortImpl | ORDER_REFUND明确路由REVERSE目的，避免误查GRANT |

已完成pay领域切片；旧券URL、owner路由与整单退回触发接线由JB-049继续。实际验收命令、结果见独立任务表JB-022执行记录。


### JB-023兼容兑换凭证切片

| 模块 | 类/接口 | 责任 |
|---|---|---|
| pay.entitlement.dto | CodeViews.CodeRequest/CodeView/ResolvedCode | 公共签发请求/白名单响应与内部单实例定位结果 |
| pay.entitlement.service | EntitlementCodeService/Impl、EntitlementCodeWriter、EntitlementCodeSigner | 本人签发、HMAC幂等重建、短期原绑定解析及撤销；无资产扣减 |
| pay.entity/mapper | EntCodeToken、EntCodeTokenMapper | V440，摘要及幂等nonce、期限、原解析操作/门店/人 |
| app/pay-svc配置 | PayEntitlementProperties/Gate及环境映射 | 默认关闭的code开关、稳定密钥，不影响统一会员码功能 |

可选兼容领域已完成；P30及P12/P13调用和双部署内部HTTP承载仍由026/041完成，不提前开放独立消费者码入口。38项定向Java、最终场景6项及原生MySQL约束1项通过，证据见独立任务表。


## 第二阶段 JB-041 运行接线类

| 模块 | 类/接口 | 责任 |
|---|---|---|
| base.spi.entitlement | CoreEntitlementInternalApi / EntitlementCoreInternal | 核心事实HTTP契约与请求 |
| base.spi.entitlement | EntitlementEventInternal / PayEntitlementEventBridgePort / PayEntitlementEventCodec | 强类型事件交接、白名单与不可变哈希 |
| base.svc | InternalServiceResponses | 内部令牌、错误状态与空响应规则 |
| pay.client | PayEntitlementRequests / PayEntitlementResponses / EntitlementRemoteCalls | 运行请求、绑定回执、无隐式重试传输 |
| pay-svc | InternalEntitlementRuntimeEndpoint / EntitlementCoreClientConfiguration | 25个真实内部Handler与反向客户端接线 |
| pay-svc.client.port | CoreEntitlementUseSourcePortImpl / CoreSourceDecisionPortImpl | 核心来源与授权/决定查询 |
| pay-svc.client.port | CoreEntitlementGrantSourcePortImpl / CoreEntitlementIssueAudiencePortImpl | 冻结grant来源与定向人群 |
| pay-svc.client.port | CorePayEntitlementEventBridgePortImpl | 核心持久确认后完成支付事件交接 |
| app.payclient | EntitlementRuntimeRouting / EntitlementCodeAppService / EntitlementBatchAppService | 本地/独立形态的应用边界 |
| app.payclient.port | RemoteEntitlementGrantPort / RemoteEntitlementUsePort / RemoteEntitlementQueryPort | 原SPI运行远程承载 |
| app.payclient.port | RemoteEntitlementUseFactsPort / RemotePaymentAttemptFactsPort | 精确占用与历史收款事实 |
| app.payclient.impl | EntitlementCodeAppServiceImpl / RemoteEntitlementCodeAppService | 兑换码服务互斥接线 |
| app.payclient.impl | EntitlementBatchAppServiceImpl / RemoteEntitlementBatchAppService | 批量发行服务互斥接线 |
| app.portal.internal | InternalEntitlementFactsEndpoint | 核心真实来源、授权与事件接收端点 |
| app.paybridge | EntitlementEventReceiverService / impl | 核心事务内接收、去重、冲突验证 |
| app.paybridge.port | LocalPayEntitlementEventBridgePortImpl | 嵌入式形态的核心事件事务 |

PayEntitlementOutboxRelay由组合根显式创建，独立形态只在pay-svc运行，主应用不重复扫描支付事件。
领域PortImpl只由所属组合根绑定，避免本地和HTTP实现同时成为候选Bean。


## 第二阶段 B3 活动权益与领取类

| 模块 | 类/接口 | 责任 |
|---|---|---|
| core.promotion.dto | BenefitClaimCommand / BenefitGrantPlan / ActivityBenefitDecided | 最小领取输入、冻结权益包与持久决定事件 |
| core.promotion.entity/mapper | PmtBenefitIntent / PmtBenefitIntentMapper | V441真实来源、本人幂等及状态锁 |
| core.promotion.service/impl | ActivityBenefitIntentService / Impl | 冻结来源、父参与许可、子发放命令和不可反悔决定 |
| core.promotion.port | ActivityGrantSourcePortImpl | CENTER/ACTIVITY完整冻结命令与真实决定核实 |
| core.promotion.service.impl | ActivityClaimCapabilityEvidence | 仅已实现主动领取能力，不证明定向/订单触发引擎 |
| app.paybridge | ActivityBenefitCoordinator / impl / ActivityBenefitDecisionConsumer | 无跨库事务的权益包协调与重复事件恢复 |
| app.paybridge.port | ActivityGrantOutcomePortImpl | 所有子pay终态核实后提供营销确认/释放事实 |
| app.portal.mp | MpEntitlementController | P11及原领取只读查询，消费者字段白名单 |

ActivityPresetExecutionPolicy仅新增AP004的NONE触发权益领取执行器；其他预置仍逐项判断实际能力。


第二阶段查询HTTP：MpEntitlementController增加本人列表、详情、使用详情、日额度与起算预览及可选兑换码；
BizEntitlementQueryController提供P26授权门店记录与P29已识别顾客的指定实例详情。
EntitlementQueryPort/Service/Impl增加merchantUsage，BizUsageQuery只允许内部可信范围构造；
PayInternalApi/Requests/Paths、RuntimeEndpoint与RemoteEntitlementQueryPort承载同一商家查询协议。
本批无新增数据库表，复用ent_instance/ent_usage/ent_ledger/ent_period_bucket与会员身份引用。


P07/P08：BizEntitlementIssueController复用EntitlementBatchAppService本地/远程门面，主体全域发行。
EntitlementIssueBatchWriter新增同pay事务的EntitlementBatchPrepared事件；共享EntitlementEvents、
PayEntitlementEventCodec与PayEntitlementOutboxRelay补第五个白名单类型。
EntitlementBatchPreparedConsumer在app paybridge按原批次有界续跑；批次事实Mapper刷新会话缓存，
避免外层无资源事务同步范围沿用预提交的旧游标。本批无新增迁移。


### 第二阶段实际服务核销源类（V442）

| 模块 | 类/接口 | 职责 |
|---|---|---|
| core.trade.dto | ServiceOrderCreate、ServiceOrderIdentifiedCreate | 前者接作用域内会员引用；后者仅内部接已由pay验证的定位结果；真实SKU、数量，不信任页面售价 |
| core.trade.dto | ServiceOrderSnapshot、ServiceUseSubmit | 内部原源/命令/决定快照与提交条件，不能直接作为C响应 |
| core.trade.entitlement | EntitlementServiceSnapshotPolicy | 原JSON摘要、稳定源/操作编号、业务修订0、提交就绪事实 |
| core.trade.entitlement.entity / mapper | OrdEntServiceOrder、EntitlementServiceOrderMapper | 新V442核心业务表，含幂等、预览、原命令、源决定、期限与恢复索引 |
| core.trade.service / impl | EntitlementServiceOrderService/Impl | 校验实际商品/会员/门店，冻结服务事实、绑定预览、核对本人授权、准备原命令、记录提交或取消决定 |
| core.trade.service / impl | EntitlementServiceAuthorizationService/Impl | 现有C09/OrdEntAuthorization的服务源验证委托；不增加授权模型 |
| core.trade.entitlement.port | EntitlementUseSourcePortImpl | 按CORE/SERVICE_USE分发给实际服务源；普通订单源保持原校验 |
| app.paybridge / impl | EntitlementServiceUseCoordinator/Impl | NOT_SUPPORTED编排core和pay；prepare→可信UseFact→核心决定→commit，响应丢失按原号恢复 |
| app.paybridge.dto | EntitlementServiceViews | C字段白名单、B业务进度投影；WAIT_SUBMIT/EXPIRED不进入支付预占枚举 |
| app.portal.biz | BizEntitlementUseController | P12/P13/P14与请求取消；独立pay:ent:use权限及门店交集，不扩充经理默认授权 |
| app.portal.mp | MpEntitlementController | 补C本人待确认列表/详情；项目和余次清晰展示；C09沿原控制器授权 |

EntitlementOrderCoordinatorImpl原操作恢复找不到普通订单链接时路由服务核销协调器；复用UseDecided事件。
UseSourceFact新增可空stagedOperationNo，由core源所有者提供，pay新prepare核对；旧构造器及旧源null保持兼容。
消费发生后的余次来自pay真实usage，不从预览推算最终账。实物赠品库存出库尚未接入，该源明确只允许服务SKU。


### P15订单券报价衔接（已实现）

core.trade新增`EntitlementOrderQuoteService/Impl`和`OrderCouponSelection`：解析本人实际子订单、冻结
可信QuoteView/源事实/选券，复用原授权和原订单使用服务。app新增`EntitlementCheckoutCoordinator/Impl`
及`MpEntitlementQuoteController`；C09控制器转交协调器，原服务授权不自动扣次。V443只增原授权JSON字段。
核心`UsePrepared`事件在原命令写入事务发布；现有`EntitlementOrderUseDecisionConsumer`区分准备与终态事件，
准备达到实际已应用价格的HELD即可，未知重试原操作；终态事件仍必须达到COMMITTED/RELEASED。
AuthorizationView新增可空operationNo/useStatus及3参兼容构造器，不把使用状态当作本人同意状态。


### JB039商品绑定（已实现）

core.product的`CardProductBindingService/Impl`负责真实商品/SKU/市场及pay版本预检；
`CardProductBindingWriter`在独立核心事务内幂等保存、商品修订CAS及回执；
`ProductEntitlementBinding`、`CardProductBindingView`为输入/响应；
`PrdEntitlementBinding`、`PrdEntBindingCommand`及对应Mapper沿商品扫描根；
app `BizCardProductBindingController`仅负责会话、biz:goods及全主体权限。


### JB035实收到账内核（实施中，未开放实收入口）

pay.storedvalue的`StoredValueCreditService/Impl`只接受原pay实收决定；
`StoredValuePostingService/Impl`以payTxManager完成账户→原充值单锁定、原号到账、批次/凭证/分录/平衡备查账及出站事件。
`StoredValuePostingPolicy`为按本金/赠金分池的内部借贷规则；`StoredValueFacts`核对原收到证明及不可变许可；
`StoredValueStoreProjector`只建立成功到账历史目录、时序不回退，当前门店状态后续查询时核实。
实体`SvAccount/SvRecharge/SvLot/SvVoucher/SvEntry/SvPostingLine/SvUserStoreIndex`与
pay.mapper.storedvalue下同名Mapper均绑定pay数据源；不通过core Mapper操作资金账。
实际业务接线及完整StoredValuePort本地/远程装配待JB034/037/038/041，不把内部到账内核当作整条充值完成。


JB040先行安全切片：shared spi.product.CardProductSalePort与core.product.port.CardProductSalePortImpl
按真实商品/主体验证并查询含历史状态的新绑定；core.trade.OrderServiceImpl的split在建单/预览前调用。
当前有新绑定即能力未就绪，无绑定旧卡放行；不安装恒成功或伪造付款适配，不增加新的默认角色权限。

付费卡接线补充：GrantCommand.issueMode()将已持久快照来源PAID_CARD_ORDER映射为发行规则PAID_ORDER，EntitlementGrantService使用该模式做allowIssueModes与付款事实要求校验；sourceRef与grantKey不改写，JSON不新增字段。完整售卡协调和真实付款来源适配仍归JB-040。

JB-040新增`core.trade.port.CardSaleGrantSourcePortImpl`，实现EntitlementGrantSourcePort；读取CardSaleSnapshotMapper和OrderMapper，不读取pay库、不调用外部通道、不在持锁事务中远程调用。resolve核实冻结命令及真实付款投影，decision沿原来源键/paidAt返回稳定成功；付款未决不伪造取消。既有InternalEntitlementFactsEndpoint按supports选唯一owner，本地和独立pay反向来源调用共同复用。付款前发行占用及销售协调未就绪，入口保持关闭。

取消接线补充：CardSaleSnapshotService.recordCancelledFromOrder由OrderServiceImpl的cancelPayment和closeOne调用；实现要求MANDATORY事务，锁真实主单，按冻结来源调用EntitlementSourceDecisionService记录GRANT_ABORT。CardSaleGrantSourcePortImpl在resolve/decision前读取持久终态，取消必须与主单及快照完全一致；即使后续出现付款标记也不得翻为成功。未决及只关闭未记决策的来源继续PENDING。本项尚不调用pay容量释放。

### JB-040容量预留类清单（付款前与付款后分离）

| 模块 | 类/接口 | 责任与状态 |
|---|---|---|
| shop-base.spi.entitlement | EntitlementSaleReservationPort | ReserveSale/ReleaseSale/SaleView及稳定预留号；内部SPI，尚未装配公共adapter |
| shop-base.spi.entitlement | EntitlementSaleSourcePort | 冻结售卡事实与可预留条件；不提供付款伪证 |
| core.trade.port | CardSaleGrantSourcePortImpl | 同时实现原发行来源与预留来源；真实主单/快照/持久决定读取 |
| pay.entitlement.entity / pay.mapper.entitlement | EntSaleReservation / EntSaleReservationMapper | V450持久预留、唯一来源、claim/锁及恢复索引 |
| pay.entitlement.service | EntitlementSaleReservationService / impl.EntitlementSaleReservationServiceImpl | 无持锁跨域调用；预留门禁、源事实核对、重放、可信释放及冻结promise读取 |
| pay.entitlement.service | EntitlementSaleReservationWriter | payTxManager事务内占额/释放；MANDATORY transfer将容量移交grant，不二次占额 |
| pay.entitlement.service | EntitlementGrantServiceImpl / EntitlementGrantWriter | PAID_CARD_ORDER改为读取原承诺、核实付款、原事务接管；其他发行模式沿原流程 |
| shop-base.spi.entitlement | PayEntitlementEventCodec | 保留并接收原PAID_CARD_ORDER事件来源 |
| shop-app测试 | PaidCardReservationFlowTest | 真实Spring/Mapper/事务验证暂停履约、重放、取消栅栏、缺承诺拒绝、额度失败回滚 |

待接类：本地/远程预留Port装配、反向SaleSource RPC适配、core逐件销售协调/持久事件与恢复器。它们尚未实现，不以领域Service存在视为销售已开放。

### 售卡预留传输适配增量

新增EntitlementSaleReservationPortImpl、本地EntitlementRuntimeRouting与pay-svc Bootstrap装配；standalone由RemoteEntitlementSaleReservationPort调用PayInternalApi，三条POST为/internal/pay/entitlement/sale/{reserve,release,query}，共享PaySaleRequests。InternalSaleReservationEndpoint沿既有内部令牌校验和异常映射，不重试写操作。反向SaleSource RPC及真实HTTP往返专项尚待实现，销售门禁继续关闭。

### 售卡反向来源传输增量（2026-10-19）

新增POST /internal/core/entitlement/source/sale，共享SaleSourceQuery(context,ReserveSale)，返回SaleSourceFact；InternalSaleSourceEndpoint沿内部令牌认证、唯一owner选择与标准错误映射。pay-svc的CoreEntitlementGrantSourcePortImpl同时实现EntitlementSaleSourcePort，经既有CoreEntitlementInternalApi反查，不跨库、不在pay锁内发起网络调用。Bean工厂返回具体类型，使两个SPI在启动时均可发现。三条pay预留POST补真实HTTP测试，覆盖reserve/release/query及错误令牌拒绝调用业务。完整订单销售协调仍未验收，JB040/041保持DOING。

### JB040逐件售卡协调实现切片

新增shop-base PaidCardSalePlanPort，由core CardSaleGrantSourcePortImpl按tenant/order读取原快照并按行、单件、件内权益排序；app CardSaleCoordinator/Impl仅消费可信计划，不接受外部传入权益清单。逐件预留沿SR+sourceKey，发卡沿CG+sourceKey；全部RESERVED才readyForPayment，部分成功保留原占用并可按原号恢复，不将超时猜作取消。真实COMMITTED来源后prepare→commit；可信CANCELLED后release；未知继续pending。所有pay调用均在core事务之外，不建立跨库事务。

本批未开放公共入口。尚须在真实建单路径冻结快照、付款入口强制封口、支付/取消Outbox投递和定时原号恢复接齐后才能开放售卡；协调器存在不表示这些调用点已接齐。验证目标包括重复付款恢复、取消释放、2件×2权益不漏不重。JB040/041仍进行中。

CardSalePaidEventConsumer实现OutboxConsumer，接既有ORDER_PAID，校验载荷orderNo与aggregateId相同，按原单恢复；全部issued才确认，失败重试。当前仅MAIN，与pay发行支持范围一致；SysOutbox没有tenant列，未据不存在字段推断多租户支持。取消消费、付款封口和建单快照接线待续。

### JB040取消事件与付款前容量校验接线

core OrderServiceImpl的cancelPayment/closeOne在原事务内写GRANT_ABORT，存在售卡快照才发布CardSaleEvents.Cancelled（CARD_SALE_CANCELLED）。CardSaleEventConsumer替代原仅付款消费者，消费取消时必须全部RELEASED才能确认；失败沿统一Outbox重试，不因截止释放。无售卡快照不发布额外事件。

PaidCardPaymentGatePort/Impl在OrderServiceImpl.pay发起支付尝试前调用协调器，零售卡权益不影响原订单；有权益但未全部RESERVED拒绝继续。该调用点无core写事务。此处尚非所有收款路径验收，线下收款等仍待核对；商品售卡门禁保持关闭，真实建单快照尚未接齐，不宣称完整上线。新增测试验证重复取消事件不双释放，取消后的付款预留检查拒绝。

### JB040建单权益快照接线

CardProductSalePort新增saleDefinition内部读取（无绑定返回空），SaleDefinition包含原模板版本、币种、每件权益数、已发布规则JSON。core商品owner从真实商品归属推导tenant，按原商品/SKU查绑定，仅ACTIVE且未删除可读取，不调用pay。OrderServiceImpl在真实OrdItem插入后以稳定OL+主键构建逐件/件内权益快照，检查币种并加入原建单事务；只对购买行展开，不把赠品行当付费卡发行来源。读取/快照失败原建单一起回滚。

requirePurchasable仍拒绝新绑定销售，未因接线而开放。当前测试覆盖真实绑定读取与版本副本、源快照、预留/发卡/取消；尚缺开放门禁后的真实checkout全链路专项。线下收款目前存在事务包围，需要先拆边界再接预留检查，不能在持锁事务里调用pay。

### JB040线下收款事务边界

MerchantOrderServiceImpl.confirmOfflinePay以NOT_SUPPORTED挂起调用方事务，先require商家/门店权限与待线下收款状态，再调用PaidCardPaymentGatePort。随后通过core transactionManager的TransactionTemplate进入原收款事务，重复require并FOR UPDATE锁主单后再次检查WAIT_OFFLINE_PAY，才写确认人/时刻并复用markPaid。并发取消在预检后成功，则锁内拒绝确认，原取消不被覆写；全部本地收款审计和付款事件仍与主单同事务。预留失败不写收款审计，已经预留但取消须沿可信取消事件释放。

验证映射：PaidCardReservationFlowTest.offlinePrepaymentRunsOutsideTransactionAndCancellationWinsBeforeCollection断言pay预检无core事务、插入并发取消后收款拒绝且无确认人/发卡；既有OfflinePayFlowTest验证普通线下收款路径兼容。当前无新增HTTP字段、表或权限，销售门禁仍待完整checkout专项。

### JB040/051售卡恢复扫描

V451只扩展core原售卡快照delivery_status及恢复索引，不新增表（运行表仍250）。PaidCardSaleRecoveryPort/Impl读取主单已PAID/CANCELLED/CLOSED且待确认的订单，支持afterOrderNo、1–200上限及稳定排序；不扫未付款单、不按TTL释放。CardSaleRecoveryService/Impl在事务外逐单调用原协调器，全部发行或全部释放才acknowledge；失败计数并继续其余订单，原记录保留PENDING。

CardSaleRecoveryJob沿JobHandler/JobDeclaration/JobSupport及ShedLock注册card-sale-recovery，受shop.job.enabled控制；shop.job.card-sale-recovery.cron默认每5分钟。每批100单，游标轮转越过故障单；成功确认持久化，进程重启只重复未完成单。调度失败由既有作业结果记录，恢复不退款、不调平、不更换原预留/发行号。源Port属于core，扫描不读取pay表。此内部扫描无新增公共端点、权限或消费者字段。

新增场景验证：sweepRecoversLostPaidEventAndPersistsAcknowledgement、failedRecoveryRetainsPendingWithoutIssuing。尚待原生迁移、调度故障/并发及最终checkout专项；父任务仍DOING，不据定向测试直接开放销售。

### JB040完整checkout专项与默认关闭准入

新增shop.trade.paid-card-sale.enabled（默认false）显式开关，仅用于允许有效绑定进入core建单；ACTIVE/未删除/真实SKU与规则副本仍校验，pay侧发行/使用/付费卡开关和容量继续独立守卫。默认配置保留旧拒绝行为，不自动修改部署配置。新PaidCardCheckoutFlowTest使用真实商品绑定及真实OrderService.createFor，2件×每件2权益须持久4条来源、付款前0实例、预留4、markPaid状态机/Outbox事件后发行4，重复消费与恢复仍4。markPaid为内部集成测试调用，不代表外部通道实际收款验收。

首轮专项发现测试缺门店导致来源快照校验失败。生产快照门店改为sub.getStoreNo，与真实履约/库存落地一致；测试补真实默认门店，不放松门店必填。尚需独立部署、原生迁移、并发及真实通道演练后才建议开启新开关，父任务不提前标完成。

### V450/V451原生增量与独立装配验证

新增scripts/tests/paid-card-migrations.test.mjs，仅在无端口、无网络的临时MySQL 9.7.2执行V450及V428→V451，finally清理。验证源键唯一、RESERVED规则快照必填、TRANSFERRED接管grant必填、RELEASED取消决定必填、JSON合法性；旧快照升级后默认PENDING、非法状态拒绝及恢复索引存在。/tmp/paid-card-migrations.log为1项通过，0失败/跳过，约6.8秒。该证据仅覆盖迁移与数据库约束，不冒充真实Writer并发或生产版本升级演练。

PayApplicationBootTest新增saleCapacityAndReverseSourceHaveExactlyOneStandaloneOwner，检查真实独立pay上下文的预留SPI、反向SaleSource及HTTP端点唯一，来源确为core远程适配。结合运行时HTTP与主应用embedded/standalone互斥路由测试验收本批装配；不据此宣称两个真实进程的业务链路已全部验收。

### JB043交易站内通知切片

EntitlementNotificationSubscriber实现OutboxConsumer，仅处理ENTITLEMENT_GRANTED/ENTITLEMENT_USED/STORED_VALUE_CREDITED。共享codec校验收件人、业务字段，额外匹配eventNo/aggregateType/aggregateId，防止错事件身份发送；dedupKey为EN+SHA256(事件号+USER+收件人)，复用MessageService.push的全局持久去重及既有站内信发送日志。只写TRADE站内信，未开通短信/微信/推送，不新增NotifyScene通道种子，不制造未落地C路由；link为空。不展示双池、账户号、原凭据或会员码，不调用pay资金/权益写服务。失败抛回现有Outbox重投，成功消息重投仍唯一。

EntitlementNotificationFlowTest覆盖到账/发放/核销三类真实消息落库与重复去重、错头/无收件人拒绝、消息首次失败后恢复且未创建账户或发卡。当前是消费者站内通知子项；外发、商家/运营异常通知及完整O06治理仍待后续，JB043不能提前DONE。

### JB045支付异常记录与平台查询内核

V452建既定pay_ent_exception，PayEntException/Mapper归pay数据源。EntitlementExceptionService/Impl实现captureGrantFailure/get/list；只捕获真实PREPARED grant，先锁原grant并按tenant+kind+原grantNo唯一认领，稳定异常号EX+来源摘要，不接收调用方提供的证据或受益人。返回Evidence白名单只含原grantNo、捕获时状态、来源类型和固定DELIVERY_FAILED，不回传付款凭据、用户授权或原命令。捕获不改变容量/实例/分录。

查询限定platformActor且按tenant/entity收窄；分页1–100条，稳定createdAt/id倒序。该内部CatalogScope须后续由运营可信会话/数据域构造，不能直接绑定请求。O01–O04的HTTP/权限、独立pay适配、按原号恢复及动作审计尚待接，本批不开放伪实现的运营端点。新增真实PREPARED发行捕获/重复、脱敏、非平台和跨主体拒绝场景，父任务仍DOING。

### JB045原grant恢复与动作审计内核

V453新增pay_ent_exception_action及实体/Mapper；ExceptionViews.RecoveryCommand只含expectedExceptionRevision/reason，幂等键另传，不接受新受益人、金额或权益。EntitlementExceptionRecoveryService/Impl调用EntitlementExceptionActionWriter.begin认领动作与异常PROCESSING，释放pay锁后读真实来源决定并调用原grant commit/abort；UNKNOWN/PENDING不释放容量，完成动作并将异常回OPEN供下一次核实。成功原grant终态后同事务写RESOLVED与DONE安全响应。原请求中断可用同键原参续办；同键异参或新键抢PROCESSING拒绝；完成响应沿原键稳定重放。

审计冻结操作者、主体、异常号、请求摘要、原因与最终回复，不保存原支付凭据。只捕获/恢复已有grant，不重建发行单。测试模拟begin后进程中断，再同键续办；原grant只发行1次、容量只结算1次、动作只留1条，重复回复稳定，异参/非平台拒绝。当前仍为pay内核，运营权限/会话和本地远程HTTP适配随后接线，不宣称O03已经可公开调用。

### JB045异常服务双部署传输

EntitlementExceptionAppService新增受控list/get/retryGrant；embedded由EntitlementExceptionAppServiceImpl委派pay服务，standalone由RemoteEntitlementExceptionAppService通过PayInternalApi调用。共享PayExceptionRequests及Paths三条POST，InternalEntitlementExceptionEndpoint沿内部令牌和统一异常映射；请求校验可信scope、原异常号、原恢复命令和幂等键。写请求不自动重试，返回安全ExceptionView/PageData。未将内部scope放入公共请求；O01–O04运营入口仍待可信会话/数据域及权限登记，query-source动作尚待实现。

新增真实HTTP异常读取/分页/恢复DTO往返与错误令牌拒绝调用业务，路由夹具加入两种适配及对应服务替身，断言每种部署只有一个异常AppService。

### JB045查源动作与内部传输

V454新增operation_kind区分QUERY_SOURCE/RETRY_GRANT，旧动作为RETRY_GRANT。ExceptionViews.Evidence新增可空sourceStatus；旧证据JSON及4参构造兼容。查源复用RecoveryCommand与独立key，锁外读取来源决定后把状态存入异常证据及动作响应；异常回OPEN，不调用grant commit/abort，也不结算占用。原查询键重放原结果；查询键改用于恢复冲突，后续恢复须带最新revision和新key。

新增内部POST /internal/pay/entitlement/exceptions/query-source，沿原内部令牌及共享RetryGrant请求、本地/远程AppService。无新增公开运营Handler，本批仍待O01–O04权限及可信会话/主体范围接线。测试覆盖COMMITTED来源仅查不发、原容量不动、独立动作审计、查源重放与恢复键隔离，然后实际原grant恢复；HTTP验证查源参数完整往返。

### JB045运营公开入口（主体明确限定）

OpsEntitlementController新增O01–O04，ops profile及独立read/recover/verify权限；一律要求entityNo查询参数，O03/O04还须Idempotency-Key/RecoveryCommand。OpsEntitlementScopeResolver从当前OPERATOR会话取tenant/actor，ALL或MERCHANT.refs包含指定主体才构造platform scope，缺scope/其他维度不猜主体范围。pay继续核对tenant/entity，前端不能指定platformActor。

V455注册三项后端功能点/超管授权，Perms.java及ops-web perm-map同步；不授予FINANCE/CS等普通角色，不增加UI未落地菜单（ui_ready=0）。O01首版明确主体，跨主体聚合列表仍待实现；安全ExceptionView暂不包含全动作历史，JB045不因Handler存在直接DONE。测试覆盖真实运营登录四路由、原key/命令、未登录/缺主体不调用pay，以及纯范围解析的跨主体、社区维度/消费者拒绝。

### JB046权益次数账实对账内核

V456落既定PayEntReconRun/Diff及两个Mapper；EntitlementReconciliationService/Impl以payTxManager REPEATABLE_READ对同一主体的实例/分录做RIGHTS轴核对。CheckRights冻结日期/市场时区/afterInstanceNo/limit（1–200），RunView仅含批次、主体、量纲、检查数/差异数/下一游标，内部expected/actual JSON不直传页面。SUM余次/占用及MAX序号与实例逐份比较，一份实例无论几个字段异常只留1条BALANCE_MISMATCH，绝不调账。日限无限总次数实例不按虚构total比较余次；仍比占用/序号。

每页一个幂等批次，同键同参回原结果，异参冲突；有nextCursor必须后续用新key继续，不把单页COMPLETED当全主体完成。差异/批次与快照读取同事务，失败全部回滚。仅平台scope且按tenant/entity收窄，无ALL隐式跨主体。测试验证正常、差异、原号重放、差异不重复、余额保持29及非平台/跨主体拒绝。发行/金额/服务日桶对账、O05及本地/远程承载仍后续，JB046不能提前DONE。


### N01对账承载补充（2026-10-19）

| 模块 | 类 | 职责 |
|---|---|---|
| pay-domain | ReconciliationViews.Check/RunView/DiffView | 四轴命令、日期币种标签、安全差异元数据；内部快照不出接口 |
| pay-domain | EntReconciliationFactsMapper | 同一pay快照只读凭证/批次/管理账/预占及日bucket事实，不读取core库 |
| pay-domain | EntitlementReconciliationService/Impl | 有界页执行、持久幂等、分轴比较、批次及差异查询 |
| pay-domain | PayReconciliationRequests、PayInternalApi/Paths | check/list/get/differences类型化内部HTTP |
| pay-svc | InternalEntitlementReconciliationEndpoint | 内部token验证后调用pay事务服务 |
| shop-app payclient | EntitlementReconciliationAppService、本地Impl及RemoteImpl | embedded/standalone互斥适配，保留原幂等键 |
| shop-app ops | OpsEntitlementReconciliationController | O05及补充发起/详情/差异接口，可信主体数据域及既有read/verify权限 |

MONEY除账户级合计外逐lot核对分录与HELD分配，逐voucher核对批次分录及各pool/book，防止不同批次或凭证的相反误差抵消。HOLD比较全部历史服务日bucket，检测缺失bucket及规则限额偏差；ISSUANCE核对提交数量及受益人/主体/模板归属。只读检查不能证明外部实际收款或修复源事实。


### N02异常接线补充（2026-10-19）

EntitlementGrantServiceImpl在真实源决策通过且writer发放事务失败后调用EntitlementExceptionService.captureGrantFailure；不在发放持锁事务内登记或远程查源。ExceptionViews.ActionView、ExceptionService.actions、ExceptionAppService本地/Remote、PayExceptionRequests.Actions及内部/运营端点承载安全动作历史。记录原因、操作者、种类与时间，保留原号恢复，不公开内部响应快照。


### N03冻结治理承载（2026-10-19）

EntitlementLifecycleService/Impl新增freeze，复用EntitlementCommandDeduplicator和EntitlementPostingService；LifecycleViews提供FreezeCommand/FreezeView及仅内部FreezeAudit。EntitlementLifecycleAppService本地/Remote承载治理调用，PayLifecycleRequests/PayInternalApi/Paths和InternalEntitlementLifecycleEndpoint承载独立部署；OpsEntitlementLifecycleController调用可信OpsEntitlementScopeResolver并单独校验ENT_FREEZE权限。V457只登记权限及SUPER_ADMIN后端动作，不增加前端菜单。


### N04/N05储值活动能力接线（2026-10-19）

shop-base增加StoredValueCapabilityPort及Status；pay-domain的StoredValueCapabilityPortImpl结合已实现执行器与既有开关，shop-app EntitlementRuntimeRouting和pay-svc EntitlementPayBootstrap绑定本地owner；RemoteStoredValueCapabilityPort通过新增类型化内部status接口调用。StoredValueActivityCapabilityEvidence整合pay就绪事实及core/member能力，ActivityPresetExecutionPolicy仅放行五个同构储值预置及RECHARGE场景。BeautyStoredValueLifecycleFlowTest不用版本/到账夹具验证实际发布至C端消费明细的完整链路。


### N07恢复与到期承载（2026-10-19）

core trade增加EntitlementRecoveryWork.Kind/Row/ScanPage及EntitlementRecoveryScanService/Impl，只经core既有四类Mapper扫描非终态指针。app增加EntitlementRecoveryService/Impl和EntitlementRecoveryJob，将指针交给充值、服务/订单使用及活动权益原协调器；单条异常不阻断后续扫描。新增EntitlementExpiryJob调用LifecycleAppService.expire，本地直达pay、Remote走PayLifecycleRequests.Expire/ExpireResult及内部expire端点。所有外部源判断在原协调器中完成，不在扫描持锁事务内远程调用，不新增业务表。


### N08异常站内通知承载（2026-10-19）

EntitlementEvents.ExceptionOpened及PayEntitlementEvent/Codec增加白名单类型；ExceptionService首次capture时通过PayEntitlementEventPublisher写稳定事件。shop-app EntitlementExceptionNotificationSubscriber仅消费提交后的异常事件，MerchantStaffPort解析有效店主，OpsStaffPort.staffNosWithPermForEntity解析真实权限与主体数据域收件人，复用MessageService.pushTo及既有INAPP日志。无新消息表、无外发通道启用，无财务命令调用。


### N08经营效果接线（2026-10-19）

shop-base新增EntitlementMarketingFactsPort.UsageEffects/Attribution，pay-domain新增EntMarketingFactMapper、EntitlementMarketingFactsService/Impl及本地PortImpl；app RuntimeRouting、pay-svc Bootstrap及RemotePort绑定同一只读owner。PayMarketingRequests.Usage及内部usage-effects端点承载分页事实。core MarketingEffectProjector补幂等recordPayEffect，PmtEffectProjectionMapper补分效果锁和支付效果汇总；ActivityMarketingSummaryService.Metric补可空金额/数量。app EntitlementMarketingEffectSubscriber匹配原计划/原使用事实后逐活动写投影，不跨域查库、不持锁调用远程，不调用财务写服务。


### N10 owner基础承载（2026-10-19）

shop-base新增EntitlementOwnerRegistryPort的显式身份/owner/阶段record及enum。pay migration增加EntOwnerRegistry实体、EntitlementOwnerRegistryService/Impl，Mapper放pay/mapper/entitlement归paySqlSessionFactory；本地PortImpl及RemotePort承载只读resolve，PayOwnerRequests/PayInternalApi/Paths、InternalEntitlementOwnerEndpoint做token保护读取。V458只物化既有目标表，不引入第二套券模型。注册旧owner与解析真实目标均无发放、核销或通知效果，兼容切换及迁移工具仍为独立后续任务。

### 付费卡预置能力证据（2026-10-19）

shop-base新增EntitlementCapabilityPort；pay的EntitlementCapabilityPortImpl根据真实运行开关和租户给出只读就绪结果；PayCapabilityRequests/PayInternalApi、InternalEntitlementCapabilityEndpoint及RemoteEntitlementCapabilityPort承载独立进程。app的PaidCardActivityCapabilityEvidence组合core售卡、会员身份/消费者确认及pay就绪证据，活动目录继续复用ActivityPresetService。ActivityPresetExecutionPolicy允许6EP作为产品建议，但禁止将其发布成统一营销活动。

### 到期提醒任务（2026-10-19）

pay LifecycleViews补ExpiryReminder/ReminderPage，EntitlementLifecycleService/Impl补只读到期窗口查询；PayLifecycleRequests、PayInternalApi/Paths、InternalEntitlementLifecycleEndpoint及本地/Remote LifecycleAppService传输同一契约。app新增EntitlementExpiryReminderService，只写MessageService；EntitlementExpiryReminderJob复用JobSupport、JobHandler/Declaration和ShedLock，15分钟扫描一批100项、全局job开关仍默认关闭。内存游标只负责轮转，持久消息键承担重启去重，不把游标当作账务处理结果。

### 运营异常列表范围（2026-10-19）

pay DTO新增OperatorEntityScope，OpsEntitlementScopeResolver.resolveList只从真实运营会话构造ALL或商家集合。EntitlementExceptionService.listAcross在数据域过滤后计数/分页；原单主体list委托同一内核。EntitlementExceptionAppService及两种实现、PayExceptionRequests/ListAcross、PayInternalApi/Paths、InternalEntitlementExceptionEndpoint承载集合，OpsEntitlementController将entityNo作为可选筛选。动作和详情未放宽范围。

### 迁移源只读预检（2026-10-19）

core新增promotion/migration/LegacyCouponMigrationPreflightService及impl/LegacyCouponMigrationPreflightServiceImpl，复用原CouponMappers、PromotionMappers读取模板、实例及pmt_apply，不新增第二套源表Mapper/实体。报告区分精确来源及revision、实际余次/历史、回填重叠与兼容缺口；没有发行/核销/通知/owner写依赖。pay EntitlementMigrationService、冻结排空和写路由仍待后续，不能将本只读类替代导入服务。

### 后端专项生成物（2026-10-19）

测试支持类EntitlementOpenApiExporter使用现有Jackson3的序列化/反序列化描述解析真实DTO，EntitlementApiContractCoverageTest在现有Handler/权限对账后生成target spec；EntitlementOpenApiExporterTest专门检查泛型、JsonIgnore及JsonProperty，避免将忽略字段误导出。scripts/gen-entitlement-backend-contracts.mjs验证并输出专项OpenAPI与共享TS类型，不新增运行端点或框架依赖。

### 源写栅栏与旧业务接线（2026-10-19）

core新增LegacyCouponSourceFenceService/Impl、LegacyCouponFenceMapper（纯SQL接口，显式@Mapper接既有扫描），复用ActivityCommandDeduplicator记录成功命令及原因；MktCoupon/PmtCoupon补4字段，V460和生成schema同步。源锁服务无pay依赖或远程调用。

CouponServiceImpl、PmtCouponServiceImpl、CouponPortImpl、CouponAllocServiceImpl、CouponRedeemServiceImpl、CampaignServiceImpl注入栅栏并加入原事务。历史apply独立反查根，不能仅依赖最后orderNo；旧4参issue补具体@Transactional入口，两种release限制原订单条件。旧Controller/DTO不新增迁移字段，OPEN默认保持原行为。

## 死信恢复类与接线（2026-10-19）

新增pay EntitlementEventDeliveryService，负责可信DEAD捕获、重排/核查审计、实际SENT关闭和全局安全元数据分页；无发放/核销/资金Writer或远程桥依赖。PayEntitlementOutboxCodec验证持久事件的租户、字段白名单、不可变头、规范JSON和hash。共享PayEntitlementEventCodec.read集中类型解析，桥接与恢复共用同一白名单；PayEntitlementEvent补entityNo契约，不改变线上的record字段。

PayEntitlementOutboxRelay在重试耗尽时捕获，在成功后关闭；启动及5秒循环执行有界恢复扫描。生产embedded/standalone组合根均注入真实DeliveryService；旧三参构造器仅保留已有原生/隔离测试兼容。异常恢复服务按kind分派，原grant恢复保持独立。PayExceptionRequests/PayInternalApi/Paths、InternalEntitlementExceptionEndpoint、ExceptionAppService两种实现、OpsEntitlementController接齐O03b/O08，权限沿原read/recover，缺少真实ALL不能读取全局死信。

EntitlementDeadLetterFlowTest使用真实DB验证耗尽/重排/幂等异参/跨实体拒绝/实际成功关闭/崩溃补偿/损坏行不饿死其他行/无财务写入/不递归；真实内部HTTP测试验证令牌及类型传输，运营HTTP验证权限与会话范围。契约测试使用独立H2库，避免不同Spring上下文重跑种子污染。

## N11源证据类与反向RPC（2026-10-19）

shop-base新增LegacyCouponFrozenSnapshotPort及Query/Snapshot：精确来源、水位、内容摘要、8MiB上限和载荷toString脱敏。core的LegacyCouponSourceFenceServiceImpl复用原快照捕获与流式HMAC实现exportFrozen，冻结/导出独立事务防止外层旧快照误验；不新增源实体/Mapper。app新增InternalLegacyCouponMigrationEndpoint，沿InternalServiceResponses做令牌校验，缺provider失败关闭。

EntitlementCoreInternal新增路径/FrozenSnapshotQuery，CoreEntitlementInternalApi新增frozenLegacySnapshot；pay-svc的CoreLegacyCouponFrozenSnapshotPortImpl与EntitlementCoreClientConfiguration装配纯远程Port。当前只读取源证据，后续EntitlementMigrationService仍需持久暂存、完整性比对、源提交决定、目标原子导入/owner接管以及旧券兼容Writer。

LegacyCouponSourceFenceFlowTest扩展真实PMT/MKT源导出、旧码脱敏、内容hash、批次/终止/篡改拒绝；新增外层RR旧快照回归先失败后通过。EntitlementCoreFactsHttpFlowTest增加真实令牌HTTP导出/拒绝/终止场景；部署classpath测试继续保证独立pay无core/app/merchant依赖。

### 死信公开分页规范修正（2026-10-19）

O08不再公开afterId/nextId，改为cursor/nextCursor；采用现有EntitlementCursorCodec，签名绑定MAIN、真实操作人、OUTBOX_DEAD_ASC_V1筛选，30分钟到期。沿现有cursor-key部署配置；篡改、跨操作人使用拒绝，公开响应不包含物理ID。Mapper仍以内部id做有界升序keyset。22模块编译及38项定向测试通过，/tmp/ent-deadletter-cursor-green.log；专项OpenAPI/TS重新生成并一致性校验，58操作/106 DTO不变。

## S03源库存类（2026-10-19）

LegacyCouponMigrationPreflightService增加TemplateInventory/InventoryReport及inventory；Impl复用已有LegacyCouponFenceMapper执行包含墓碑的完整源计数，并返回来源重叠、归档、墓碑及缺逐实例发行关系等阻塞。新增core LegacyCouponInventoryRow承载类型明确的SQL投影。原可见实例scan不改成资金写入口；没有新增源实体、目标Mapper、owner修改或消息发送。

LegacyCouponMigrationPreflightFlowTest覆盖两代来源、删除/归档根、实例/发行/历史墓碑、缺实例和缺模板的孤立历史，以及OTHER租户排除。scripts/tests/legacy-coupon-inventory.test.mjs在隔离MySQL执行源码中的实际库存SQL和孤立历史SQL；夹具仅提供生成的列形状，验证范围明确为查询。

## S02证据暂存类及部署接线（2026-10-19）

| 模块 | 类 | 职责 |
|---|---|---|
| pay.migration.entity / pay.mapper.entitlement | EntMigrationStage / EntMigrationStageMapper | V462加密证据及排他唯一认领，归pay数据源 |
| pay.migration.dto | MigrationStageViews.StageCommand/StageView/Verification | 来源query/原因与安全元数据，STAGED永不表示可导入 |
| pay.migration.service | LegacySnapshotCipher | AES-256-GCM、随机nonce、AAD绑定和显式keyId；默认密钥关闭 |
| pay.migration.service及impl | EntitlementMigrationService/Impl | scope/幂等/原文hash与来源绑定核查，事务外编排，源当前性verify |
| pay.migration.service | EntitlementMigrationStageWriter | 仅短pay事务认领密文；不远程、不写grant/实例/账本/owner |
| pay.client | PayMigrationRequests / Paths / PayInternalApi | 三条类型化内部协议 |
| pay-svc | InternalEntitlementMigrationEndpoint | 内部令牌及统一异常映射 |
| app.payclient及impl | EntitlementMigrationAppService、EntitlementMigrationAppServiceImpl、RemoteEntitlementMigrationAppService | embedded与standalone门面，写请求不自动改号重试 |

LegacySnapshotCipherTest验证随机密文、AAD/密文篡改、缺密钥/错误keyId拒绝。EntitlementMigrationStageFlowTest经真实embedded门面、core源冻结及pay写者验证同键/同批、主体/角色/租户、事务外源读取、默认关闭及无金融写入；不使用测试直写目标资产。实际内部HTTP新增stage/get/verify鉴权及DTO往返；部署路由验证门面唯一。EntitlementMigrationStageTwoProcessAcceptanceTest使用纯pay子JVM与独立DB，验证app→pay→core回读→pay密文持久化、原键重放及源ABORT拒绝。夹具按场景显式注入测试密钥，其他双进程场景保留默认空。

双进程runner已纳入第5个场景；本批定向执行新增暂存1场景及embedded1场景，均无跳过。既有美业1/三卡模型3的联合重跑留最终合并验收。

## S03完整记录保全接线（2026-10-19）

shop-base新增LegacyCouponMigrationManifestPort，复用Snapshot传输信封与Query；core LegacyCouponSourceFenceServiceImpl实现exportManifest，在独立RR及根锁内重验原水位、读取含墓碑的完整家族、检查全部在途订单并有界生成清单。LegacyCouponFenceMapper增加全部实例/发行/范围及实例关联应用记录SQL，显式匹配tenant，不受逻辑删除默认过滤。V1方法及原快照格式保留。

EntitlementCoreInternal/CoreEntitlementInternalApi、InternalLegacyCouponMigrationEndpoint及纯pay CoreLegacyCouponFrozenSnapshotPortImpl扩展frozen-manifest。客户端组合根返回具体双接口Adapter，独立pay不加载core业务类。pay Stage实体/Mapper及V463记录manifest版本/历史可用性；EntitlementMigrationService.stageManifest、新ManifestStageView、内部Endpoint、PayInternalApi与两种App门面接齐新路径。V2校验来源、批次、根冻结元数据、历史语义和四组记录；旧AAD/hash不改。

新增测试覆盖删除实例/发行/核销/范围原样保留、删除实例的未知在途订单拒绝、MKT历史不可用、完整内容变化即使旧可见水位未变仍拒绝；真实HTTP/双进程验证使用新清单接口。旧V1密文独立生成并将新增可用性列置NULL后仍可读；不靠新helper自证兼容。暂未实现目标规范化/资产导入/owner提交。

### S03规范化评估实际实现（2026-10-20）

| 模块 | 类/方法 | 职责 |
|---|---|---|
| core promotion migration | LegacyCouponSourceFenceServiceImpl.exportManifest | 增加assessmentFacts版本1；本地商家真实激活/市场、另一源模板及实例同号计数（包含墓碑），绑定完整hash |
| core promotion mapper | LegacyCouponFenceMapper.peerPromoTemplates/peerLegacyTemplates/peerPromoInstances/peerLegacyInstances | 固定SQL按MAIN及业务号比对，不暴露另一源用户资料 |
| pay migration service | LegacyCouponMigrationNormalizer | 纯只读规则翻译、原实例有效期、次数及历史一致性、差异/阻断报告；Report/InstancePlan无消费者身份或旧码 |
| pay migration service.impl | EntitlementMigrationServiceImpl.assess/authenticatedCurrentPayload | 密文及当前完整源双重重验，读取pay本地市场事实后评估；拒绝环境事务、V1和越权 |
| app payclient | EntitlementMigrationAppService及embedded/remote实现 | 本地/独立部署统一assess委派 |
| pay client / svc | PayInternalApi.migrationStageAssess / InternalEntitlementMigrationEndpoint.assess | 专用内部POST、现有服务令牌和类型化请求响应 |

这是S03的数据/规则评估子批次，不是S04源提交或目标导入。Report.importReady强制false；旧路由、别名人工认领、持久源决定、目标账本/owner原子接管仍需独立完成。

S03补充LegacyCouponIssuanceAssessor（pay.migration.service）及Assessment只读汇总，计算原发行额度/逐人上限、终身承诺和当前剩余敞口，整合进LegacyCouponMigrationNormalizer.Report.issuance；金额溢出或来源不全时保留未知，不回填0。测试LegacyCouponIssuanceAssessorTest覆盖两套0/NULL语义、墓碑/过期、单人限制、超预算及Long溢出。

### S03双源认领实际实现（2026-10-20）

| 模块 | 类/接口 | 实际职责 |
|---|---|---|
| pay migration service | LegacyMigrationEvidenceReader | 提取共用owned/current/source/validate/aad；认证原密文与当前源，拒绝事务内远程读取；保持V1原AAD兼容 |
| pay migration service | LegacyCouponAliasComparator | 严格比较PMT/MKT当前有效规则、发行基线及未用实例；只返回候选、差异与整体摘要，不修改原清单 |
| pay migration entity / mapper | EntMigrationAlias / EntMigrationAliasMapper | V464持久认领记录；成员角色、原批次、两个内容摘要与固定比较时刻；禁止READY/CUTOVER |
| pay migration service | EntitlementMigrationAliasService | 锁外重验双方、比较、原键重放、认领查询；canonical评估重验双方，shadow阻断重复导入 |
| pay migration service | EntitlementMigrationAliasWriter | 按stage_no排序逐行FOR UPDATE，验证版本及完整AAD/密文绑定；认领/命令同pay事务，唯一冲突回滚PENDING |
| pay client/svc/app | compareAlias/recognizeAlias/getAlias及三条内部HTTP、本地/远程委派 | 类型化、令牌认证、MAIN平台范围，当前pay内部77个Handler；公开58条不变 |

有效规则统一按实际算法比较：CASH不执行封顶，空门槛与零门槛等效，原值保留加密清单；不按“字段文字不同”认定实质差异，也不忽略真实余次、身份、期限或历史差异。当前仅识别未用/无历史、严格一致的V232候选；认领不创建目标资产/owner或新通知。

### N10旧入口来源元数据（2026-10-20）

- shop-base: LegacyCouponIdentityPort/Origin提供明确旧来源；LegacyCouponSourceCommitPort/CommitProof仅定义后续core持久决定读取契约，当前无生产者。
- core promotion migration: LegacyCouponOriginRow、LegacyCouponIdentityPortImpl及LegacyCouponFenceMapper.promoOrigin/legacyOrigin，fresh RR读取两套原实例和根，保留墓碑/孤立关系，不返回用户号或旧码。
- app paybridge.impl: LegacyCouponRouteResolver解析CORE/PAY元数据，核对双源阶段与同一计划/目标，禁止缺证明或持锁时请求pay；尚未替换旧CouponPortRouter金融分支。

N10新增LegacyCouponCheckoutSourcePort/LegacyCouponUsePort共享记录，以及pay.migration.policy.LegacyCouponQuotePolicy。纯策略复用CouponCalculation按原商家/门店组计算CASH/PERCENT并守恒分摊，排除其他商家、零优惠、不支持范围/无限封顶或金额溢出。当前只实现契约及纯策略；具体源持久记录、pay金融服务/HTTP及旧Controller委派继续实施。

N10金融内核新增EntLegacyCouponOperation/EntLegacyCouponOperationMapper（pay.migration/entity、pay/mapper/entitlement）、LegacyCouponUseService和LegacyCouponUseWriter。Service拒绝环境事务，在锁外获取真实源和精确owner；Writer按操作→原EntInstance加锁，同pay事务认领、份数/原EntitlementPostingService流水和状态，支持晚到取消墓碑及真实回补。PayEntitlementProperties.entitlement.legacyCoupon及FeatureGate.requireLegacyCoupon默认关闭；EntReconciliationFactsMapper.rightsHolds补合并该真实预占。尚无旧业务Facade/内部HTTP源实现、通知及消费记录适配。

### N10旧券内部结算与core源日记

| 模块 | 类／接口 | 职责 |
|---|---|---|
| shop-base | LegacyCouponUsePort、LegacyCouponCheckoutSourcePort、EntitlementCoreInternal查询与决定信封 | 旧ORDER券prepare/完成及核心真实来源类型；报价与下单意图区分 |
| pay-domain | PayLegacyCouponRequests、LegacyCouponUsePortImpl | 本地适配与内部请求体；金融内核及既有asset ledger复用 |
| pay-svc | InternalLegacyCouponUseEndpoint、CoreLegacyCouponCheckoutSourcePortImpl | 六个pay金融操作、三类反向来源读取；不引入core mapper |
| shop-app | RemoteLegacyCouponUsePort、InternalLegacyCouponCheckoutEndpoint、EntitlementRuntimeRouting | standalone适配、核心鉴权只读入口、部署形态单一实现选择 |
| shop-core | LegacyCouponCheckoutJournal／Impl、OrdLegacyCouponCheckout、LegacyCouponCheckoutMapper | 不可变意图、core事务绑定、真实订单提交决定、取消栅栏与来源读取 |

以上日记仅提供服务端可信事实存储；原OrderService、取消/售后及支付侧恢复/账单投影仍须完成对接，默认新入口开关关闭。已有CORE_LEGACY普通租户路由保留；MAIN限制只作用PAY路径。

### N10原订单创建接线

core新增`LegacyCouponOrderPricingService`（实际SKU/门店/活动后金额及规范化经济指纹）、`LegacyCouponBoundOrderService`（事务写入及原单读取）、`LegacyCouponOrderCreationCoordinator`（跨模块协调契约）。三者交易实现复用现有`OrderServiceImpl`的拆分、库存、积分及持久化；仅应用协调由`shop-app/paybridge/impl/LegacyCouponOrderCreationCoordinatorImpl`实现。原公共create/createFor先选择owner，再经Spring代理进入原core事务；已有直接构造的单元夹具维持原有无代理行为。

core日记`bindingFor`只提供已保存命令的服务端绑定，`bind`在同一行锁下返回真实不可变源；原券选择与实际价格摘要必须匹配。共享`LegacyCouponCheckoutEvents.Decided`和应用`LegacyCouponCheckoutDecisionConsumer`保证决定与重放通知同事务；消费者仅完成原操作，不新建替代扣券。配置信号SHOP_CORE_ENTITLEMENT_LEGACY_COUPON_ENABLED、SHOP_PAY_ENTITLEMENT_LEGACY_COUPON_ENABLED默认false，后者还要求既有use开关。

### 旧券统一消费事实增量

- 支付 migration.service.LegacyCouponUsageRecorder：原操作锁内、同支付事务落 ent_usage，保留原操作及实例关联；必须有原 LEGACY_COMMIT 账本证据，不写金融增量。
- LegacyCouponUseWriter.replay：原已接受决定幂等重放时补缺失查询事实，功能开关关闭仍可完成；不重新发放或通知。
- EntitlementQueryServiceImpl / EntUsageMapper：统一权益详情和消费详情识别 LEGACY，展示真实账后余次与原订单；商家权限保留主体/门店过滤。
- EntitlementEvents.LegacyCouponUsageChanged / PayEntitlementEventCodec：跨门店旧券成功及退回事件，不能解释为现金支付。
- EntitlementNotificationSubscriber：提交后的站内核销成功/退回消息，继承既有幂等和可重试消息投递。

消费全历史增量：EntitlementQueryPort.UsageHistoryQuery；EntitlementQueryServiceImpl.history；EntUsageMapper.history；MpEntitlementController.history；PayEntitlementRequests.UsageHistory / PayInternalApi.entHistory / InternalEntitlementRuntimeEndpoint.entHistory；本地 EntitlementQueryPortImpl 与 RemoteEntitlementQueryPort 对齐。复用支付域现有签名时间游标编码器，独立 RIGHTS_USAGE 类型及权益过滤摘要，不与储值查询游标通用。

- LegacyCouponUsageRepairService：缺失消费投影有界扫描、独立事务修复及失败统计；内部 trusted job 服务，不新增公开入口。
- LegacyCouponUseWriter.repairUsage：按原核销锁修复，复用 LegacyCouponUsageRecorder 的原始账本证据校验。
- EntLegacyCouponOperationMapper.missingUsage：按 tenant、terminal status、内部 id 定位缺失统一消费事实。
- pay.job.LegacyCouponUsageRepairJob：默认关闭、显式开关、集群锁、轮转游标及统一运行审计。

### 迁移固定目标计划（N11）

- EntMigrationPlan / EntMigrationPlanMapper：支付侧固定加密清单及主 stage、源批次、请求唯一约束。
- LegacyCouponTargetPlan：内部敏感清单、SourceRef/Version/Asset；模板及历史规则版本去重，目标编号固定，完整实例键集合校验；归档行不分配金融实例或发放号。
- EntitlementMigrationPlanService：平台主体范围、幂等重放、完整源及已认领同号双来源验证、加密、只读元数据和重新验证。
- EntitlementMigrationPlanWriter：沿既有命令去重，按稳定顺序锁本地证据、认领记录、计划，事务内不调远端、不写资产或账。
- MigrationPlanViews：PlanCommand/PlanView，返回 PLANNED/importReady=false，不返回消费者身份、密文、原券码或导入许可。

### 源永久决定与支付原子基线迁入（N11）

- shop-base.LegacyCouponMigrationPlan：支付固定计划头与完整来源引用，跨服务不含消费者身份；源端必须重验完整快照。
- core.LegacyCouponSourceCommitService/Impl、PmtMigrationDecision、LegacyCouponSourceCommitMapper：核心源永久决定及唯一来源绑定。
- core.LegacyCouponSourceCommitPortImpl：独立窄读取适配，避免只读 SPI 的测试替换影响核心事务写者。
- core.LegacyCouponSourceFenceService.commitSources：全部来源根稳定锁序，完整 V2 快照重验后统一标记 COMMIT_DECIDED。
- app.LegacyCouponMigrationCommitCoordinator：锁外读取支付计划头，核心事务内不调支付。
- InternalLegacyCouponSourceCommitEndpoint / CoreLegacyCouponSourceCommitPortImpl：内部鉴权只读源证明，空证明与不可用区分。
- pay.EntMigrationImport、EntMigrationAsset 及 Mapper：不可变迁入回执/原实例到余额基线映射，归档实例没有虚构资产。
- pay.LegacyCouponMigrationImportService：所有来源永久证明必须精确匹配原计划，读取证据/远端证明完成后才进入支付事务。
- pay.LegacyCouponMigrationImportWriter：固定计划、目录及历史规则版本、发行/逐人基线、实际剩余次数、MIGRATION_BASELINE、全部原来源owner接管及回执同事务，失败整体回滚；不调用普通发放命令或发消息。
- MigrationImportViews / InternalEntitlementMigrationImportEndpoint / EntitlementMigrationAppService 本地/远程：导入和回执查询，只返回元数据。
- LegacyCouponMigrationFixture、CommitFlowTest、ImportFlowTest：真实源冻结/认领/目标计划/核心永久决定/支付迁入与失败恢复，不预置已接管资产假装全流程。

### 历史核销迁入与退回增量（2026-10-20）

| 模块 | 类/协议 | 职责 |
|---|---|---|
| shop-base SPI | LegacyCouponHistoryFacts | 原核销事实的稳定规范化哈希 |
| shop-base SPI | LegacyCouponHistoricalReturnSourcePort / LegacyCouponHistoricalReturnPort | 不可变来源事实、支付回补回执及核心确认边界 |
| pay-domain migration | LegacyCouponHistoricalUsageProjector / Writer | 真实历史校验与MIGRATED消费投影，不补旧支付/旧消费账 |
| pay-domain migration | LegacyCouponHistoricalReturnService / Writer | 事务外查源、事务内一次性恢复原权益、原消费REVERSED、回执及通知事件 |
| pay-domain migration | EntMigrationUsage / EntMigrationReturn | 不可变来源映射与支付回执 |
| shop-core trade | LegacyCouponOrderReturnFacts / LegacyCouponHistoricalReturnService | 实际订单取消/整单退款判断、core退回决定和支付确认 |
| shop-core trade | PmtMigrationReturn / LegacyCouponHistoricalReturnMapper | 来源串行化、唯一决定、显式UTC及version确认 |
| shop-app bridge | LegacyCouponHistoricalReturnCoordinator / Consumer | 不跨库事务的pay完成→core确认，原号事件重试 |
| shop-app / pay-svc | RemoteLegacyCouponHistoricalReturnPort / CoreLegacyCouponHistoricalReturnSourcePortImpl | standalone双向HTTP适配；embedded复用本地同一所有者 |
| shop-app / pay-svc | InternalLegacyCouponHistoricalReturnEndpoint | 各所属服务令牌入口，无C端退款入口 |
| core scan / app recovery | EntitlementRecoveryWork.HISTORICAL_COUPON_RETURN | 有界扫描PENDING原决定，事件和丢失确认的恢复 |

同名的核心/支付业务服务分别保留在所属模块；支付Bean命名为payLegacyCouponHistoricalReturnService，避免embedded组件扫描名称冲突。核心来源窄适配器不与业务写服务混用，便于部署与测试替换。

### 旧入口接续统一发行增量

| 模块 | 类 | 责任 |
|---|---|---|
| shop-base SPI / core member | LegacyCouponAudiencePort / Impl | 只解析可信选择条件或实际领取人，不接收客户伪造名单 |
| pay migration | LegacyCouponTemplateManagementService / Impl | 来源精确核验、原定义读/原子改版/启停、本人发行计数 |
| app bridge | LegacyCouponCompatibilityServiceImpl | 原CouponService及DTO兼容，已迁只调pay，未迁仍走原实现 |
| pay migration | LegacyCouponIssueService / Impl / Writer | 原号关联原生批次，复用发行内核、真实额度/跳过结果及完成回执 |
| pay migration / job | LegacyCouponIssueRecoveryService / Job | 有界恢复原已接受批次，独立配置默认关闭 |
| core migration | LegacyCouponIssuedProjectionPortImpl / PmtPayCouponProjection | 真实支付完成后的身份/展示投影，不复制权益余额 |
| app bridge | LegacyCouponIssueCoordinator / CompletedConsumer | 支付完成后core投影；失联/重复事件不重复发券 |
| app wallet | LegacyCouponWalletProjectionServiceImpl | 新发pay实例并入原券包/下单候选，金额/余次/效期仍由pay供给 |
| app payclient / pay-svc | LegacyCouponIssueAppServiceImpl / RemoteLegacyCouponIssueAppService / InternalLegacyCouponIssueEndpoint | 本地与独立部署同一发行语义 |

LegacyCouponIdentityPort新增core本地模板主体/已提交模板目录读取，供旧C入口从pay当前定义展示，避免原视图冻结日期过滤掉新版活动。pay到core受众查询有独立鉴权接口，SourceSystem与模板/批次摘要共同校验。

### 旧MKT运营接续及原活动引用边界（2026-10-20）

| 模块 | 类/接口 | 职责 |
|---|---|---|
| app.paybridge.impl | `MktCouponCompatibilityServiceImpl` | 原MKT CouponService主实现，保留原服务限定注入；永久决定/精确owner路由，Ops定义/状态/预算/发行/历史与C目录/领取/券包适配 |
| app.paybridge.impl | `LegacyCouponArchiveCompatibilityServiceImpl` | 原ArchiveService主实现，只对已迁移MKT券调用pay；其它类型和未迁移券原样委派 |
| pay.migration | `LegacyCouponTemplateManagementService.archive` | 平台身份、持久命令幂等、模板根锁、归档/恢复审计字段；原权益保留 |
| shared/member/core | `LegacyCouponAudiencePort.Query`及Core实现 | 实际平台单用户数量解析，与本人领取/商家受众选择互斥；不接受任意公开收件人列表 |
| pay.migration | `LegacyCouponIssueWriter` | Core解析账户→N个原生统一发放单元；同锁校验当前版本、归档、库存及个人额度，整批预算前置 |
| core.promotion | `LegacyCouponFenceMapper.legacyProgramReferences` | 读取真实旧邀请有礼和派生COUPON活动引用，作为认证完整源清单的评估事实 |
| core.marketing | `FissionServiceImpl` | 保存绑定/启用在源根栅栏下完成，迁移中/已永久切换券不能新增旧程序引用 |
| test.scenario | `LegacyMktCouponCompatibilityFlowTest` | 实际迁入后Ops两张+C一张、限领、预算/改版、记录、归档；非协作晚期活动绑定使源提交失败 |
| test.scenario | `LegacyCouponMigrationTwoProcessAcceptanceTest` | 独立JVM/独立数据库，经真实HTTP永久源提交→pay导入→领取→原SKU消费/取消，历史取消回补不双记 |

### N12 领域契约与源适配整理（2026-10-20）

`EntitlementEventDeliveryService`、`EntitlementMigrationAliasService`、`EntitlementMigrationPlanService`、`LegacyCouponHistoricalReturnService`（pay/core各一）、`LegacyCouponIssueRecoveryService`、`LegacyCouponMigrationImportService`、`LegacyCouponMigrationRecoveryService`、`LegacyCouponUsageRepairService`、`LegacyCouponUseService`保留同名领域接口，实现在相邻`.impl`包；原事务注解、幂等、源码决定、bean身份及响应DTO保持。固定计划的owned/decode只供可信支付迁入协调器内部使用，不是HTTP实体响应。

跨旧MKT、PMT与交易的源屏障/盘点/历史协调器位于`shop-app/paybridge/impl`，跨旧源的Mapper位于`shop-app/paybridge/mapper`，仍由Core事务及Core SqlSessionFactory负责；这是核心服务宿主的跨域适配，不是把历史事实移交支付。活动规则、资格和预算仍在shop-core/promotion，权益与资金账仍在pay-domain。

共享`LegacyCouponWriteFencePort`只提供原Core事务内的本地写屏障；marketing不再引用promotion的具体服务。`paybridge/port/LegacyCouponWriteFencePortImpl`和`LegacyCouponSnapshotPortImpl`薄转发给核心源协调器；成员受众经SourceCommit/Identity SPI验证真实来源决定和主体。跨域源身份端口位于`paybridge/port`；纯promotion的SourceCommit/IssuedProjection端口位于`promotion/port`。embedded历史退回由`pay.entitlement.port.LegacyCouponHistoricalReturnPortImpl`适配，替代装配类匿名端口。

数据库/API契约未因包整理新增表或传输端点；完整验证状态见独立任务表与最终验收文档。

### 最终验收补充：来源绑定实体与售卡确认状态

- `core.promotion.migration.PmtMigrationSource`：`pmt_migration_source`的明确实体模型，含sourceSystem/sourceTemplateNo/decisionNo及BaseEntity审计列；唯一写者仍为`LegacyCouponSourceCommitMapper.bind`，不得增加通用CRUD绕过永久来源决定。
- `core.trade.entitlement.entity.OrdCardSaleSnapshot.deliveryStatus`：映射既有迁移的PENDING/ACKNOWLEDGED确认列；保存和恢复继续使用原售卡协议，不因实体补齐而重复发卡。
- 储值消费`ConsumptionQuery.storeNo`可选；为空时按本人和主体查询所有门店，签名游标仍绑定本人、主体和门店过滤条件，不能互换使用。

### 最终包归属（优先于上述历史增量记录）

| 所属模块 | 当前包/类型 | 职责与数据源 |
|---|---|---|
| core | `promotion.migration.LegacyCouponSourceFenceService`、`trade.service.LegacyCouponHistoricalReturnService` | 核心源屏障及历史退回协调契约 |
| 核心服务宿主shop-app | `paybridge.impl.LegacyCouponSourceFenceServiceImpl`、`LegacyCouponMigrationPreflightServiceImpl`、`LegacyCouponHistoricalReturnServiceImpl`、`EntitlementRecoveryScanServiceImpl` | 跨Core旧营销/活动/交易协调；继续用Core事务和SqlSessionFactory，paybridge包名不代表Pay拥有这些事实 |
| 核心服务宿主shop-app | `paybridge.mapper.LegacyCouponFenceMapper`、`LegacyCouponHistoricalReturnMapper` | 仅Core库，显式纳入Core扫描 |
| 核心服务宿主shop-app | `paybridge.port.LegacyCouponSnapshotPortImpl`、`LegacyCouponIdentityPortImpl`、`LegacyCouponWriteFencePortImpl` | 共享SPI适配，源快照独立RR事务，写屏障加入原Core事务 |
| core | `promotion.port.LegacyCouponSourceCommitPortImpl`、`LegacyCouponIssuedProjectionPortImpl` | 纯核心来源证明和展示映射适配，不跨域引用Mapper |
| pay | `migration.service`的Alias/Plan/HistoricalReturn/IssueRecovery/MigrationImport/MigrationRecovery/UsageRepair/Use八项Service契约及`service.impl`对应实现 | Pay固定计划、金融迁入、核销、回补和恢复；显式pay事务 |
| pay | `entitlement.service.EntitlementEventDeliveryService`及`service.impl.EntitlementEventDeliveryServiceImpl` | 耐久事件投递，不重复金融命令 |
| shop-app API | `portal.mp.MpEntitlementController`、`MpEntitlementServiceOrderController` | 本人权益/领取/消费记录与独立服务单资源；后者原URL及鉴权保持 |

公开契约以实际Handler/Jackson导出为准；当前64操作、107DTO。前端入口实际接入单独见[前端任务表](./任务表-活动权益-前端接入.md)，不能把Java后端交付解释为页面已上线。

### 品牌/门店双线储值 P3 实际新增与对接（2026-10-23）

| 模块 | 类/方法 | 实际职责 |
| --- | --- | --- |
| pay-domain/storedvalue/issuer | StoredValueIssuerAccountService.credit / lookup | 发行方/顾客/币种选账、已有身份别名证明、冻结版本校验、影子钱包冲突阻断、同事务首建零余额账户；不开跨品牌权限 |
| pay-domain/mapper/storedvalue | SvAccountMapper.findAccount / lockAccount | 已验证账户号的实际余额实体读取与锁定；保留原legacy owner方法 |
| pay-domain/storedvalue/service/impl | StoredValuePostingServiceImpl | 接入发行方选账；原accountNo继续被lot/voucher/账序使用；兼容关闭开关的旧路径 |
| pay-domain/storedvalue/service/impl | StoredValueQueryServiceImpl / StoredValueRedemptionServiceImpl / StoredValueRedemptionPostingServiceImpl | 余额、报价、最终核销统一选账；最终accountNo授权校验保留 |
| shop-app/paybridge | StoredValueMigrationReadinessService.load / check | 每轮读取服务端封存文件快照，无需重启即可更新本进程标识；坏文件不回退旧清单 |

发行方数据库唯一键切换、完整运维执行编排、其余IS/RC/RD和B端页面仍属后续交付，不在此表标记已完成。

### P3 维护编排新增类（2026-10-23）

| 模块 | 类/合同 | 职责 |
| --- | --- | --- |
| shop-app/paybridge | StoredValueMigrationMaintenanceService | 内部所有人授权入口，实时证据→新鲜证明→权限复核→暂停/映射；默认关闭 |
| pay-domain/storedvalue/migration | StoredValueMigrationExecutionService | 验证实际执行节点与证明，绑定暂停回执，分事务暂停/映射及提交前过期回滚 |
| shop-base/spi/storedvalue | StoredValueMigrationReadiness.Proof | 同次封存清单和观测报告，不接受普通公开请求提供 |
| shop-base/spi/storedvalue | StoredValueMigrationPlans.MaintenanceResult | 原清单、维护回执、状态、清单摘要及实际数量 |
| Core/Pay transport | StoredValueMigrationAppService、PayInternalApi、PayStoredValueMigrationRequests、InternalStoredValueMigrationEndpoint | 嵌入/独立 Pay typed pause/apply，沿用内部 token；不新增公开 API |

本轮没有唯一键 DDL、恢复发行或品牌资金激活类；全阶段仍在开发。

### P3 唯一键切换预检（2026-10-23）

| 模块 | 类/合同 | 职责 |
| --- | --- | --- |
| pay-domain/storedvalue/migration | StoredValueAccountKeyPlan.evaluate | 精确校验索引结构和汇总阻断条件，生成先建新键、再移旧键的固定下一步；按实际状态识别断点 |
| pay-domain/storedvalue/migration | StoredValueAccountKeyInspector.inspect | 只读MySQL元数据及全租户账户/冻结版本/发行方扫描，包含已删除行；不执行DDL、不公开资金数据 |
| pay-domain测试 | StoredValueAccountKeyPlanTest / StoredValueAccountKeyInspectorTest | 结构、顺序、断点、全库数据、前缀伪装与读取失败；不作为MySQL真实DDL验收替代 |

正式DDL执行器、持久步骤回执、全库操作授权与恢复发行仍待实现。
