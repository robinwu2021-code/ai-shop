# 活动卡券储值代码结构与类清单

状态：目标代码设计，待评审 · 2026-10-18

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
| StoredValueRedemptionService / Impl | storedvalue/service及impl | 新增 | amount预览/预占/确认/释放；共享统一使用头 | sv_hold_allocation+ent_use_intent；P22/I08–I10 |
| StoredValuePostingService | storedvalue/posting | 新增 | 账户/批次/借贷分录/凭证同事务；内部双池守恒 | sv_account/lot/voucher/entry |
| StoredValueAllocationPolicy | storedvalue/policy | 新增 | 按既定范围与扣款顺序分配批次/双池；纯计算 | sv_lot/hold_allocation |
| StoredValueQueryService / Impl | storedvalue/service及impl | 新增 | 门店目录、本人核销、商家总余额查询 | P23/P24/P28 |
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
| BizStoredValueController | portal/biz | 新增 | P18–P22/P27/P28 |
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
| pay.entitlement | pay_ent_exception | PayEntException | PayEntExceptionMapper | 拟新增；同名存量实施前核对 |
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

核销：BizEntitlementUseController→应用协调器核验本人/订单→EntitlementUsePort.prepare→策略与posting预占→core持久化成功决策→commit→使用流水及事件。储值走金额批次分配；次卡/月卡走余次与服务日bucket。未知结果由query与RecoveryJob恢复，不能新建操作再扣。

C端记录：MpStoredValueController→MpEntitlementAppService→StoredValueQueryService→有成功储值历史门店，按最后成功储值/核销倒序→本人核销明细。页面及通知不暴露本金赠金拆分、不新增储值专用码、不新增线上退款入口。

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
