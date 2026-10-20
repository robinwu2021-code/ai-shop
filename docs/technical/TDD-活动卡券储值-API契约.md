# 活动卡券储值模块API契约

> 2026-10-20 门店版实施修订：余额BalanceQuery必须指定storeNo；/biz/stored-value/accounts返回本店余额，不再返回商家共用余额。独立方案及plan-recharges增量接口按门店开发TDD §1.3实施，旧活动充值保持请求兼容。 详见[TDD-独立储值方案与B端接入](./TDD-独立储值方案与B端接入.md)。

状态：Java后端首期范围已按实现验收；生产切换与前端页面后置 · 2026-10-20

执行状态与先后顺序见[Java后端独立任务表](./任务表-活动卡券储值-Java后端.md)，已实现目录内核直接复用，其余目标设计不视为已完成。

[系统入口](./TDD-活动卡券储值-模块化系统设计.md) · [功能矩阵](../requirements/活动卡券储值-模块功能矩阵.md) · [数据库](./TDD-活动卡券储值-数据库设计.md)。以下主表已按真实Handler/Jackson DTO对账，专项OpenAPI与共享TS已生成（64操作/107DTO）；后续增量记录保留历史口径。沿用接口必须先校验当前DTO兼容，已有URL由原Controller改委派，不能重复注册Handler。

## 一、通用契约

HTTP入口在shop-app，core/pay列指业务所有者与事务。C用户从登录态取，B主体从BizContext取，门店再验岗位；不收客户端X-User-No/entityNo替代身份。运营按主体授权与角色过滤。

响应沿项目ApiResponse包装；普通分页沿PageData {records,total,page,size}；CursorPage {records,nextCursor,hasMore}。C页size默认20最大50，B/O默认20最大100。查询状态UNKNOWN不是FAILED；POST返回资源和当前状态，不保证异步完成。未登录401，越权资源404，语义冲突409，业务校验按现有错误包装返回；不临时改变全局响应结构。

写请求必须Idempotency-Key，最大64字符、大小写敏感且无首尾空白，作用域tenant+操作+key；服务端持久关联payloadHash及资源号，同键异参409。接口body中的revision必须匹配草稿/状态CAS。UTC ISO8601输出、金额为币种最小单位整数、数量正整数，任何浮点金额拒绝。手机号脱敏，凭据用私有mediaRef；券码不进普通日志。

目录游标用签名filterHash/snapshotAt/末项时间与编号，绑定本人、门店/筛选；每次首次请求固化snapshotAt。活动时序按业务时间GREATEST更新；分页期间变更的目录允许刷新重取，不声称完整历史快照。记录不可变流水同时间用usageNo稳定排序，详情用发生时门店/服务快照。

## 二、核心服务活动与业务入口

| ID | 方法 | 路径 | query或body | 业务响应 | 权限 | 说明 |
|---|---|---|---|---|---|---|
| C01 | GET | /biz/activity-presets | scene?,category?,industryTag? | PresetPage | biz:campaign | 目录，仅可用能力推荐 |
| C02 | GET | /biz/card-product-presets | industryTag? | PresetPage | biz:goods | 6产品模板，非已发布商品 |
| C03 | GET | /biz/activities | model=unified,scene?,status?,page,size | Page<ActivityView> | biz:campaign | 沿用，扩筛选 |
| C03a | GET | /biz/activities/{activityNo} | model=unified | ActivityView | biz:campaign | 已发布版本及草稿详情 |
| C04 | POST | /biz/activities | model=unified + ActivityDraft | ActivityView | biz:campaign | 沿用保存；只可修改草稿 |
| C05 | POST | /biz/activities/{activityNo}/publish | PublishCommand | ActivityVersionView | biz:campaign | 新增显式发布/规则版本 |
| C06 | PUT | /biz/activities/{activityNo}/status | model=unified + StatusCommand | ActivityView | biz:campaign | 沿用，暂停/结束不撤销参与 |
| C07 | POST | /biz/card-product-bindings | ProductEntitlementBinding | BindingView | biz:goods | 引用已发布pay模板，商品售价仍由商品API保存 |
| C08 | POST | /biz/member-code/resolve | MemberResolveCommand | CustomerReference | biz:customer | 统一会员域接入契约，若已有等价URL则复用 |
| C09 | POST | /mp/orders/{orderNo}/entitlement-authorizations | {orderRevision,quoteRef,selectionDigest,requestKey} | {authorizationRef,status,expiresAt} | 登录本人 | 只确认服务端预存的可信pay报价意图；同键原样重放，改价/改选/过期拒绝 |
| C10 | GET | /biz/marketing-summary | storeNo?,from,to | MarketingSummary | biz:campaign | 目标统一看板，迁移已有摘要入口时保留别名 |
| C11 | POST | /mp/member-code | 无，C会话本人 | MemberCodeView | 消费者登录 | JB-010统一会员码签发，不是储值专用码 |

## 三、支付服务权益与储值入口

| ID | 方法 | 路径 | query或body | 业务响应 | 权限 | 说明 |
|---|---|---|---|---|---|---|
| P01 | GET | /biz/entitlement-templates | kind?,status?,page,size | Page<TemplateView> | pay:ent:template:read | pay模板含券/兑换/付费卡 |
| P02 | POST | /biz/entitlement-templates | TemplateDraft | TemplateView | pay:ent:template:write | 创建草稿 |
| P03 | PUT | /biz/entitlement-templates/{templateNo}/draft | TemplateDraft + expectedRevision | TemplateView | pay:ent:template:write | 只改草稿，新版不改旧实例 |
| P04 | POST | /biz/entitlement-templates/{templateNo}/publish | PublishCommand | TemplateVersionView | pay:ent:template:publish | 发布时校验使用模型和来源 |
| P05 | PUT | /biz/entitlement-templates/{templateNo}/status | StatusCommand | TemplateView | pay:ent:template:publish | 停发不撤销已发实例 |
| P06 | PUT | /biz/entitlement-templates/{templateNo}/issue-policy | IssuePolicyCommand | IssuePolicyView | pay:ent:issue:manage | 不能低于占用+已用 |
| P07 | POST | /biz/entitlement-issue-batches | IssueBatchCommand | IssueBatchView | pay:ent:issue | 异步结果，不假称全部成功 |
| P08 | GET | /biz/entitlement-issue-batches/{batchNo} | 无 | IssueBatchView | pay:ent:read | 含逐项跳过/失败原因 |
| P09 | GET | /mp/entitlements | kind?,status?,cursor?,size | CursorPage<EntitlementItem> | 登录本人 | 本人卡券/次卡/月卡 |
| P10 | GET | /mp/entitlements/{instanceNo} | entityNo? | EntitlementDetail | 登录本人 | 余次/期限/范围，已过期保留 |
| P11 | POST | /mp/entitlement-claims | BenefitClaimCommand | ClaimReceipt | 登录本人 | 模板CENTER或活动资格，不任意领付费卡 |
| P11a | GET | /mp/entitlement-claims/{intentNo} | 无 | ClaimReceipt | 登录本人 | 查询原领取意图及结果，不隐式重新发行 |
| P12 | POST | /biz/entitlement-uses/preview | UsePreviewCommand | UsePreview | pay:ent:use | 先看后核，检查可用状态 |
| P13 | POST | /biz/entitlement-uses | UseSubmitCommand | OperationView | pay:ent:use | 受控核销，由应用协调源授权，不裸露commit |
| P14 | GET | /biz/entitlement-uses/{operationNo} | 无 | OperationView | pay:ent:read | 未知状态查原操作，不重新扣 |
| P14a | POST | /biz/entitlement-uses/{operationNo}/cancel | reason + Idempotency-Key | OperationView | pay:ent:use | 原经办人和门店校验，只取消未完成意图，不是退款 |
| P15 | POST | /mp/entitlement-quotes | QuoteRequest | QuoteView | 登录本人 | 可信订单draftRef，只试算 |
| P16 | POST | /mp/entitlements/{instanceNo}/activation-quotes | 无 | ActivationQuote | 登录本人 | 查询起算效果，不任意提前激活 |
| P17 | GET | /mp/entitlements/{instanceNo}/allowances | serviceDate? | AllowanceView | 登录本人 | 指定日剩余/冻结，与总次数区分 |
| P18 | POST | /biz/stored-value/recharges | RechargeCreateCommand | RechargeView | pay:sv:recharge:create | 调用core reserve后创建，金额服务端快照 |
| P19 | POST | /biz/stored-value/recharges/{rechargeNo}/submit | RechargeSubmitCommand | RechargeView | pay:sv:recharge:create | 实收凭据，仅提交待核验 |
| P20 | POST | /biz/stored-value/recharges/{rechargeNo}/receive | ReceiveCommand | RechargeView | pay:sv:recharge:receive | 确认实收，到账可以仍在处理中 |
| P21 | POST | /biz/stored-value/recharges/{rechargeNo}/cancel | CancelCommand | RechargeView | pay:sv:recharge:create | 只未实收取消；驳回用reason同状态守卫 |
| P22 | POST | /biz/stored-value/consumptions | StoredValueConsumeCommand | OperationView | pay:sv:consume:create | 绑定真实服务订单与本人授权 |
| P23 | GET | /mp/stored-value/stores | cursor?,size | CursorPage<StoreItem> | 登录本人 | 有成功储值历史门店 |
| P24 | GET | /mp/stored-value/consumptions | entityNo,storeNo?,cursor?,size | CursorPage<UsageItem> | 登录本人 | 本主体本人储值使用历史，可按门店筛选 |
| P25 | GET | /mp/entitlement-usage/{usageNo} | entityNo? | UsageDetail | 登录本人 | 本人统一核销详情 |
| P25a | GET | /mp/entitlements/{instanceNo}/usage | entityNo?,cursor?,size=20 (1-50) | CursorPage<UsageItem> | Login owner | Full history; signed user/asset scoped cursor; occurredAt/usageNo DESC |
| P25b | GET | /mp/entitlement-usage | entityNo?,cursor?,size=20 (1-50) | CursorPage<UsageItem> | 登录本人 | 统一核销历史，包含无活跃实例的归档记录；不返回未知的逐笔余次 |
| P26 | GET | /biz/entitlement-usage | storeNo?,assetKind?,from?,to?,page,size | PageData<UsageItem> | pay:ent:read | 只授权主体门店 |
| P27 | GET | /biz/stored-value/recharges/{rechargeNo} | 无 | RechargeView | pay:sv:recharge:read | 查询入账进度，不能再次确认收款代替查询 |
| P28 | GET | /biz/stored-value/accounts | customerRef,currency,storeNo? | StoredValueBalanceView | pay:sv:account:read | 总余额、总冻结及当前项目可用额；不返回accountNo或内部双池 |
| P29 | GET | /biz/entitlements/{instanceNo} | customerRef,storeNo | EntitlementDetail | pay:ent:read | 本主体已识别本人指定卡余次，不任意枚举其他用户 |
| P30 | POST | /mp/entitlements/{instanceNo}/redeem-codes | CodeRequest | CodeView | 登录本人 | 仅STORE_EXCHANGE签短期券码，不签储值码 |
| P31 | POST | /biz/stored-value/consumptions/preview | Idempotency-Key + customerRef,storeNo,items | StoredValueConsumptionPreview | pay:sv:consume:create | 建立计价服务单并读取储值报价；无扣款/预占，C端仍需本人确认 |
| P32 | GET | /mp/entitlement-service-orders | size=20(1–50) | List<ServiceView> | 登录本人 | 本人待确认或处理服务单，不接收客户端userNo |
| P33 | GET | /mp/entitlement-service-orders/{serviceOrderNo} | 无 | ServiceView | 登录本人 | 本人服务单详情及确认元数据，不返回原命令或身份凭证 |

P31创建时由core解析会员引用，重放按原key、原操作者/主体/门店及原payload核实，不依赖引用仍在TTL内；已提交服务单返回冻结报价，不重新报价改账。P22首次提交核实本人引用和授权，已有持久命令的重放核实原authorizationRef并返回原操作，短期引用过期不得阻断原结果恢复。新操作仍须有效身份引用；此规则不豁免权限、原门店范围或同号异参校验。

付费卡来源兼容：已有销售快照的来源类型为`PAID_CARD_ORDER`，其sourceRef与grantKey保持原样；GrantCommand.issueMode()仅将其发行模式映射为目录定义的`PAID_ORDER`，不改写来源身份或新增JSON字段。两种付费来源均必须提供paidFactRef、订单行/单件/件内序号，quantity必须为1，不能带活动permit。该适配不替代真实付款事实provider、付款前发行预留或售卡协调器，未接齐前销售能力保持关闭。

付费卡真实来源由core的CardSaleGrantSourcePortImpl读取原销售快照及主订单付款投影。仅接管CORE/PAID_CARD_ORDER，校验租户、原订单行/单件/件内权益、受益人、主体、模板版本、快照hash、付款引用、币种和原付款时间；主订单须已支付且正金额，当前仅接受WECHAT/ALIPAY/OFFLINE现金路径。未确认付款返回PENDING，不推定取消；FREE/POINTS/STORED_VALUE及未知通道拒绝。成功决策号由原来源键稳定派生，occurredAt沿原paidAt，不随事件投递变化。复用既有内部grantSource/grantDecision路由，无新增公网入口；该provider不代表付款前预留、逐件发行及取消已接齐。

付费卡取消来源：买家/平台取消及超时关闭的原订单事务内调用CardSaleSnapshotService.recordCancelledFromOrder，按冻结的单件/件内权益写ord_ent_source_decision的GRANT_ABORT；该方法要求已有core事务，锁定真实主单，已付款或带已付款快照时拒绝。来源查询只有在持久取消决策、主单终态及原快照完整一致时返回CANCELLED，缺决策即使主单已关闭也仍为PENDING。已取消来源若出现迟到/矛盾的付款投影，prepare和decision均拒绝，不翻为成功；查询不生成或修改决策。本项只提供后续预留释放的可信依据，不直接释放pay容量。

## 四、运营接口

| ID | 方法 | 路径 | query或body | 业务响应 | 权限 | 说明 |
|---|---|---|---|---|---|---|
| O01 | GET | /ops/pay/entitlement-exceptions | entityNo?,kind?,status?,page,size | Page<ExceptionView> | ops:pay:ent:read | 支付权威异常 |
| O02 | GET | /ops/pay/entitlement-exceptions/{exceptionNo} | entityNo | ExceptionView | ops:pay:ent:read | 证据/源单/动作 |
| O02a | GET | /ops/pay/entitlement-exceptions/{exceptionNo}/actions | entityNo,page,size | Page<ActionView> | ops:pay:ent:read | 原操作审计元数据，无内部快照 |
| O03 | POST | /ops/pay/entitlement-exceptions/{exceptionNo}/retry-grant | entityNo + RecoveryCommand + Idempotency-Key | ExceptionView | ops:pay:ent:recover | 原grantNo恢复，不重发新权益 |
| O03b | POST | /ops/pay/entitlement-exceptions/{exceptionNo}/retry-event | entityNo + RecoveryCommand + Idempotency-Key | ExceptionView | ops:pay:ent:recover | 只重排原事件，不执行原财务命令 |
| O04 | POST | /ops/pay/entitlement-exceptions/{exceptionNo}/query-source | entityNo + RecoveryCommand + Idempotency-Key | ExceptionView | ops:pay:ent:verify | 查事实，不改余次 |
| O05 | GET | /ops/pay/entitlement-reconciliations | entityNo,bizDate?,page,size | Page<RunView> | ops:pay:ent:read | 当前快照按批次日期查询 |
| O05a | POST | /ops/pay/entitlement-reconciliations | entityNo + Check + Idempotency-Key | RunView | ops:pay:ent:verify | 发起有界单页检查 |
| O05b | GET | /ops/pay/entitlement-reconciliations/{reconNo} | entityNo | RunView | ops:pay:ent:read | 查询原批次及续扫游标 |
| O05c | GET | /ops/pay/entitlement-reconciliations/{reconNo}/differences | entityNo,page,size | Page<DiffView> | ops:pay:ent:read | 仅返回安全差异元数据 |
| O06 | GET | /ops/notify-logs | 现有通知查询参数 | 沿用NotifyLogPage | message:template:read | 外发通道记录，复用既有通知中心 |
| O06a | GET | /ops/inapp-messages | receiverType?,receiverNo?,from?,to?,page,size | Page<InAppLogVO> | message:template:read | 站内消息到达与已读记录 |
| O07 | POST | /ops/pay/entitlements/{instanceNo}/freeze | entityNo + FreezeCommand + Idempotency-Key | FreezeView | ops:pay:ent:freeze | 冻结/解冻需原因、版本及审计；不清余额 |
| O07a | GET | /ops/pay/entitlements/{instanceNo}/lifecycle | entityNo | FreezeView | ops:pay:ent:read | 获取治理操作前的资产版本与状态 |
| O08 | GET | /ops/pay/entitlement-dead-letters | cursor?,limit? | DeadLetterPage | ops:pay:ent:read | 仅真实ALL运营读取安全元数据，含无法可信归属的损坏事件 |

## 五、内部SPI（独立部署使用现有内部API适配，不直接公开）

| ID | 所有者 | 方法 | 返回 | 守卫 |
|---|---|---|---|---|
| I01 | core | ActivityParticipationPort.reserve(ReserveActivity) | ParticipationPermit | 校验活动/人群、限购和预算，预留 |
| I02 | core | ActivityParticipationPort.confirm(ConfirmParticipation) | ParticipationView | 权威pay成功事实转预算已用 |
| I03 | core | ActivityParticipationPort.release(ReleaseParticipation) | ParticipationView | 必须pay已禁止后续发放的取消事实 |
| I04 | core | ActivityParticipationPort.query(SourceRef) | ParticipationView | 恢复/对账，不按TTL猜终态 |
| I05 | pay | EntitlementGrantPort.prepare(GrantCommand) | GrantView | 预留发行容量、固定输入与source |
| I06 | pay | EntitlementGrantPort.commit(GrantDecision) | GrantView | 资格/收款可信事实后发实例 |
| I07 | pay | EntitlementGrantPort.abort(AbortGrant) | GrantView | 仅PREPARED可终止，已承诺实收的入异常 |
| I08 | pay | EntitlementUsePort.prepare(UseCommand) | HoldView | 券、卡或储值各自校验和分配 |
| I09 | pay | EntitlementUsePort.commit(UseDecision) | UseReceipt | 源成功授权决策，扣减/账/事件同事务 |
| I10 | pay | EntitlementUsePort.release(CancelUse) | HoldView | 原意图明确取消，已COMMITTED不可释放 |
| I11 | pay | EntitlementUsePort.reverse(OrderBenefitReverse) | UseReceipt | 既有订单券原使用受限回补；不用于新储值退款 |
| I12 | pay | StoredValuePort.credit(CreditRecharge) | RechargeView | 已RECEIVED，原充值号唯一发放 |

内部共有SourceRef={sourceSystem,sourceType,sourceNo,sourceLineNo?,unitIndex?,entitlementIndex?,sourceRevision}；DecisionRef={decisionNo,occurredAt,sourceRevision,decisionType}；调用均带tenant、actor、requestId、payloadHash，服务端核实服务身份与来源事实。I05/I08的查询按grantNo/operationNo由内部query服务提供；响应不可达不改用新业务号。幂等键不能仅用用户号或当天日期。SourceRef序号从1开始，件内权益序号要求单件序号，单件序号要求订单行；修订号非负。稳定身份使用长度前缀SHA256，发行/成功使用键排除修订号，预占意图键包含修订号；数据库唯一键仍须包含租户。

core本地实现SourceDecisionPort查询时先按修订意图键查；新修订没有精确记录时再按不含修订的settledKey查已成功事实，返回原成功来源，避免将跨修订已成功误报UNKNOWN。AuthorizationDecision另外返回orderSnapshotHash，pay据此绑定被本人确认的订单事实。远程传输仍由JB-041接线。

ReserveActivity={sourceRef,activityNo,versionNo,tierNo?,userNo,entityNo,storeNo,currency,quantity}；金额/赠送由core规则算。ParticipationPermit={reservationNo,sourceRef,activity/version/tier,user/entity/store,currency,payAmountMinor?,bonusAmountMinor?,benefitRefs[],redemptionScope,snapshotHash,issuedAt}。benefitRefs引用pay已发布版本，不能用任意JSON指定付费权益。

GrantCommand={grantNo,sourceRef,permit?,templateVersionNo,recipientUserNo,quantity,benefitSnapshotHash,paidFactRef?}；来源PAID_ORDER时paidFactRef与源订单行必需，活动/手工发放遵守allowIssueModes。GrantDecision、AbortGrant引用grantNo+DecisionRef，不重复传可变业务金额。

UseCommand={operationNo,sourceRef,userNo,entity/store,currency,assetKind,instanceNo?/accountNo?,serviceDay?,units?,amountMinor?,itemsSnapshot,authorizationRef,decisionRef}；UsageKind白名单：ORDER_COUPON、STORE_EXCHANGE、PAID_CARD、STORED_VALUE。来源意图先冻结价格版本和资产选择，pay不能从不可信请求拼本人授权。

## 六、请求字段定义

ID/版本号均非空字符串≤64，名称≤100；以下?表示可选，非?必须提供。所有scope对象显式mode=ALL/STORES/GOODS/CATEGORIES、refs数组，ALL时refs为空；仅主体内，门店与项目由可信门面查验。asset使用范围与活动参与范围分别提供。

| schema | 字段与规则 |
|---|---|
| ActivityDraft | activityNo?,expectedRevision?,name,scene,templateCode,templateVersion,owner=MERCHANT,storeParticipationScope,audienceRef?,schedule{type,startAt,endAt?,zone},rule{schemaVersion,trigger,benefits[],tiers?},quotaLimit?,budgetLimitMinor?,perUserLimit,currency；储值tier精确payAmount和bonusAmount；权益benefit引用pay版本 |
| PublishCommand | expectedRevision,reason?；发布生成不可变versionNo，不能覆盖已发布规则 |
| StatusCommand | expectedRevision,status=RUNNING/PAUSED/ENDED（权益模板ACTIVE/PAUSED/ENDED）,reason；scene/kind不可改 |
| ProductEntitlementBinding | goodsNo,skuNo,expectedProductRevision,templateVersionNo,unitsPerItem；售价通过既有商品API维护；仅PAID_CARD已发布版本可绑定，币种/主体匹配 |
| MemberResolveCommand | memberCode,storeNo；不记录明码日志，不将token永久绑定用户 |
| AuthorizationCommand | orderRevision,quoteRef,selectionDigest,requestKey；不接收用户/门店/金额/折扣/资产清单；重复同键返回原授权 |
| TemplateDraft | templateNo?,expectedRevision?,title,kind=ORDER_COUPON/STORE_EXCHANGE/PAID_CARD,benefitMode=CASH/PERCENT/FREE_SHIP/GIFT/SERVICE_UNITS,benefitValue?,benefitCapMinor?,benefitRef?,currency,minAmountMinor?,minQty?,usageModel=COUNT/PERIOD_COUNT/PERIOD_DAILY,totalUnits?,dailyUnits?,validity{mode=ABSOLUTE/RELATIVE,startAt?,endAt?,validDays?,startBasis=GRANTED/PAID/FIRST_USE,periodBasis=ROLLING_DAYS/CALENDAR_MONTH,zone},redemptionScope,allowedIssueModes[],funder；折扣benefitValue为应付比例bp，8500=支付85%，cap为减免上限 |
| IssuePolicyCommand | expectedRevision,limitCount?,maxExposureMinor?,perUserLimit,enabled；限额不得小于held+used。maxExposure是授权发行敞口，不虚构现金备付 |
| IssueBatchCommand | templateVersionNo,segmentRef?/recipientRefs[],activityNo?,activityVersionNo?,storeNo；至少一种受众来源，由core生成受控用户列表快照，不接受任意外部用户号批量灌入 |
| ClaimCommand | templateVersionNo,activityNo?,activityVersionNo?；非CENTER/未获资格拒绝，PAID_CARD不可通过claim领取 |
| UsePreviewCommand | code?/customerRef?,instanceNo?,orderRef?,storeNo,serviceDate?,units?；身份路径互斥且须绑定具体权益，STORE_CODE只定位对应券 |
| UseSubmitCommand | previewRef,sourceRef,authorizationRef,expectedAssetRevision；本人授权/源事实检查，赠送兑换券仍遵守既有先看后核规则；不靠B自填金额 |
| QuoteRequest | orderDraftRef,orderRevision,selectedInstanceNos；core提供可信行项/baseAmount，不接受前端base作为权威 |
| RechargeCreateCommand | customerRef,activityNo,versionNo,tierNo,storeNo；门面调core reserve，不接受赠金金额 |
| RechargeSubmitCommand | actualAmountMinor,collectionMethod=CASH/TRANSFER,receiptRef,receiptDigest,expectedRevision |
| ReceiveCommand | expectedRevision,receiptDigest,confirmationNote；有权实收确认人取会话，不能代填操作者 |
| CancelCommand | expectedRevision,reason；仅未RECEIVED可取消；已实收争议进入异常 |
| StoredValueConsumeCommand | customerRef,orderNo,orderRevision,authorizationRef；价格与项目从源订单取 |
| RecoveryCommand | expectedExceptionRevision,reason；恢复原业务，不允许指定新受益人或金额 |
| FreezeCommand | expectedAssetRevision,action=FREEZE/UNFREEZE,reason；操作留痕，不注销余额/余次 |
| CodeRequest | useContext=STORE_EXCHANGE；模板允许券码，实例本人且可用 |

## 七、响应字段与隐私

| schema | 业务字段 |
|---|---|
| PresetPage | records[{templateCode,templateVersion,name,category,industryTags,available,missingCapabilities}]，默认不发布 |
| ActivityView/ActivityVersionView | activityNo,versionNo,revision,name,scene,category,status,schedule,participationScope,ruleSummary,quotaHeld/used,budgetHeld/used,currency；完整规则仅授权管理接口 |
| TemplateView/TemplateVersionView | templateNo,templateVersionNo,revision,title,kind,status,benefitSummary,usageModel,totalUnits?,dailyUnits?,validity,redemptionScope,allowedIssueModes；实例不实时随模板改变 |
| BindingView | goodsNo,skuNo,templateVersionNo,unitsPerItem,status |
| CustomerReference | customerRef,maskedName,expiresAt；短期主体门店绑定，不返明文手机 |
| AuthorizationView | intentNo,orderNo,revision,amountMinor,currency,status,expiresAt；私有授权引用不放列表/通知 |
| IssuePolicyView | limit/held/issued数量、maxExposure/held/used、revision |
| IssueBatchView | batchNo,status,planned/issued/skipped/failed/pendingCount,skipReasons[{code,count}],itemStatusQueryRef；未完成返回PROCESSING |
| GrantView | grantNo,status,instanceNos[],sourceRef,occurredAt；同源重投返回同一实例集合 |
| EntitlementItem/Detail | instanceNo,title,kind,status,remainingUnits?,dailyRemaining?,effectiveFrom,effectiveUntil,scopeSummary,sourceLabel；Detail加用法和记录入口。COUNT剩余和日额度分开，不传内部双池 |
| UsePreview | previewRef,instanceNo,title,maskedHolder,redeemable,reason,unitsToUse,remainingAfter?,assetRevision,snapshotHash,expiresAt |
| OperationView/HoldView/UseReceipt | operationNo,status=WAIT_AUTH/HELD/COMMITTED/RELEASED/PROCESSING/EXCEPTION,usageNo?,holdNo?,occurredAt?,nextAction；COMMITTED后不得显示可重试扣款 |
| QuoteView | quoteRef,orderRevision,selectionDigest,discountMinor,currency,allocations[{instanceNo,orderLineNo,discountMinor}],expiresAt；quote不占权益 |
| ActivationQuote | instanceNo,startBasis,periodBasis,effectiveFrom?,effectiveUntil?,activationAction；FIRST_USE只预览，不能由GET激活 |
| AllowanceView | instanceNo,serviceDay,zone,totalRemaining?,dayLimit?,dayReserved?,dayUsed?,dayRemaining? |
| RechargeView | rechargeNo,status,activityTitle,paidAmountMinor,creditedTotalMinor,currency,collectionMethod,receivedAt?,creditedAt?；不返回本金赠金拆分 |
| StoreItem | storeNo,entityNo,storeName,storeStatus,lastActivityType,lastActivityAt；目录不混合不同币种余额 |
| UsageItem/Detail | usageNo,assetKind,title,amountMinor?,currency?,units?,storeNo,storeName,occurredAt,status；Detail加items、serviceStaffName、sourceOrderNo、可展示卡剩余次数；不暴露授权、token、内部批次 |
| MarketingSummary | 分项orderDiscountMinor,couponIssuedCount,couponUsedCount,rechargeReceivedMinor,storedValueConsumedMinor，按币种分组；禁止把各项相加 |
| ExceptionView/Detail | exceptionNo,kind,status,bizNo,entity/store,totalAmount?,currency?,reason,allowedActions,revision；Detail加脱敏证据与操作审计 |
| ReconView | reconNo,bizDate,zone,currency?,axis,checkedCount,diffCount,status；不同量纲不相加 |
| StoredValueBalanceView | accountNo,currency,balanceMinor,heldMinor,availableMinor,applicableMinor?；无内部双池 |
| CodeView | token,expiresAt,instanceNo；token只在本人本次响应返回、不入日志，二维码由客户端渲染 |
| ParticipationView | reservationNo,status,sourceRef,heldQuota,heldBudgetMinor,currency,confirmedFactNo? |

现有grant/order/usage业务号长度最终与BizKey生成规则核对，已存在业务号不截断。首次实施时每个请求响应须登记OpenAPI类型及字段说明、三语错误码和权限点，不以本Markdown取代运行契约生成。

## 八、旧契约兼容对照

| 当前真实路径 | 目标处理 |
|---|---|
| GET /biz/activities/{activityNo}，POST /biz/activity-conflicts | 继续core，补scene=ORDER隔离；储值不能进入订单冲突算价 |
| GET/POST /biz/coupons；GET /biz/coupons/{couponNo}；PUT /biz/coupons/{couponNo}/status | 保留旧券DTO和单Handler，内部映射pay券模板；与P01-P05共享服务，不双写 |
| POST /biz/coupons/{couponNo}/issue；GET /biz/coupon-issues | 委派pay发放/批次查询，响应兼容；异步部分状态用明确契约版本协商，不偷偷改旧成功语义 |
| GET /biz/coupon-redeem/{code}；POST /biz/coupon-redeem | 老入口只兼容既有券，日志脱敏；新客户端用P12/P13避免码落普通URL日志 |
| GET /mp/my-coupons；GET /mp/coupon/mine | 保留原语义和字段，pay提供数据；新统一P09不强行替换旧客户端 |
| GET /mp/coupon；POST /mp/coupon/{couponNo}/receive；POST /mp/coupon/best | 原入口继续，core活动资格与pay权益计算在门面协调；best复用pay报价，不能再保留两份优惠算法 |
| /ops/promotion/activities、platform-activities及enrollments | core保留；活动停止与平台报名不搬pay |
| GET /ops/promotion/coupons | 权益预算数据改从pay聚合，保留既有权限到新pay权限的明确映射 |

旧DTO不含所需新字段时采取新统一资源端点；不得在一次发布中同时删除旧API与迁移存量权益。迁移后按明确owner registry访问，未迁移批仍唯一写旧owner，已迁pay不回查旧表兜底。

## 九、负面接口与验收

不提供储值或新付费卡的C端refund接口、任意adjust-balance、裸pay.commit HTTP、储值专用code签发、用活动资格直接领取付费卡。既有订单券售后仅内部I11受原使用/售后事实限制。无能力玩法available=false且说明缺失项，不自动开放。

契约验收至少包含：所有C查询越权与DTO字段白名单、同键异参409、预占未知结果查询、老URL兼容、币种/服务日校验、同订单行多件唯一发卡、储值赠金不进入订单discount，以及预置模板不自动发布。

## 十、权限落点与可信执行

表中pay:*及ops:pay:*均为拟新增权限；沿用biz:campaign等已有权限时仍须主体/门店守卫。迁移旧券接口使用明确角色映射，不给所有客户查询员自动授予发券/冻结权。P20实收确认人和经办人是否允许同人由商家策略决定，默认独立权限，不能由请求传用户号绕过。请求的customerRef/previewRef/quoteRef均是短期不透明引用，绑定主体、用户、店、版本与调用上下文；服务端来源事实校验不以字符串存在为已授权。

P12 preview得到无副作用短期报价；P13调用应用协调层建立意图，再按具体券/卡/储值规则取得可信确认，不能仅提交expectedAssetRevision就任意扣款。P30券码与统一会员码两套用途分别鉴权，禁止使用一个资产码访问账户全部权益。

原活动PUT status的DRAFT→RUNNING兼容调用必须委派同一publish校验和版本生成逻辑，不允许绕过C05直接改状态。批量发券plannedCount先完成资格预检，再原子预留全部可发行容量；容量不足整批拒绝，不从第一个客户开始随机发到耗尽。发放已开始的异常逐项重试原grantNo，批次可以暂时PARTIAL/PROCESSING，但不得为未发用户伪造成功。

### 支付目录内部实现映射

本批EntitlementCatalogService提供create/saveDraft/publish/changeStatus/get/getVersion/list，内部使用EntitlementDefinition和CatalogScope，尚未注册P01–P05的HTTP Handler。CatalogScope必须由可信应用身份构造，不能直接绑定公开请求。EntitlementCatalogPort本地实现支持getPublishedVersion与validateReference；引用用途ACTIVITY_GRANT禁止PAID_CARD，PAID_PRODUCT仅允许PAID_CARD，并核实主体、币种、发布状态及发行途径。暂停后的历史版本仍可读，但拒绝建立新引用。独立pay的远程适配在D02.6接通前不开放此能力。

实施对照（2026-10-18）：上述表为目标HTTP契约，目录内核当前仅以可信服务调用和本地SPI提供，未开放P01–P06。内部TemplateView持有完整draft和currentVersionNo，PublishedVersionView持有definition及snapshotHash，不作为公网JSON直接返回；后续门面须映射为本文的管理DTO、核实scope.refs归属及权限。FREE_SHIP保留既有券类型语义，当前目录支持保存/发布不代表已接入免运费计价核销。


### JB-008共享Java协议冻结（实施中）

内部调用上下文为EntitlementContext(tenantNo,actorNo,requestId,payloadHash)，身份由认证适配器构造；payloadHash为64位小写SHA-256，业务owner仍重算实际命令摘要，不信任来方声明。DecisionRef.decisionType固定为GRANT_COMMIT/GRANT_ABORT/USE_COMMIT/USE_CANCEL/ORDER_REVERSE/RECHARGE_RECEIVED。提交和取消DTO分别限制可接受的决策类型；sourceRevision必须非负并与源意图一致，来源事实由SourceDecisionPort.query及authorization查询核实，引用字符串不是授权。

内部SourceDecision状态UNKNOWN/PENDING/COMMITTED/CANCELLED；UNKNOWN不带已决策事实，COMMITTED/CANCELLED必须带持久DecisionRef及用户、主体、币种、金额和快照摘要。未查到返回UNKNOWN，不伪造取消。内部端口方法统一显式传EntitlementContext；恢复query按来源或原业务号，不新建操作。协议record置于端口内或同SPI包，禁止数据库实体透传。

GrantCommand补entityNo用于权限和受益主体交叉核实；PAID_ORDER必须有订单行、单件、件内权益索引和paidFactRef。UseCommand的assetKind为枚举，STORED_VALUE只能accountNo+正金额，券/卡只能instanceNo+正次数，PAID_CARD不带消费金额；itemsSnapshot为不可变类型化条目。取消请求不接受提交决策，I11仅ORDER_COUPON可用。以上是结构校验，不能取代JB-011可信本人授权/源事实校验。

JB-008分页对齐：内部consumer查询沿C端size 1–50，B/O普通分页仍1–100；CursorPage显式records/nextCursor/hasMore，hasMore与是否存在nextCursor保持一致。源码核对新增边界测试已先复现51未拒绝及hasMore缺失，再修复，未将新协议直接开放为HTTP。


### JB-008报价/预览补齐

内部quote/preview/activationQuote均为只读协议，不预占、不激活。QuoteCommand由core冻结单主体门店订单行、活动后商品总额及运费；UseItem.amountMinor为行总额，不再乘quantity；行号与选择的实例去重，行总额精确等于商品总额，金额求和溢出拒绝。QuoteView分摊按(instanceNo,orderLineNo)唯一，合计等于discountMinor。优惠是否适用、折扣封顶和分摊算法仍由JB-021实现。

P12补assetRevision及snapshotHash，供P13提供expectedAssetRevision；内部UseCommand同步带expectedAssetRevision、previewRef?/quoteRef?。ORDER_COUPON要求quoteRef，兑换券/付费卡要求previewRef；储值不签独立资产码，不混入券报价。短期引用由pay验证绑定用户/主体/门店/资产/版本/数量/金额和过期时间，再核实core持久本人授权。UNKNOWN授权响应不得带用户等业务事实，HELD响应必须有holdNo和业务时间。

P06发行控制跨版本、跨allowedIssueModes合并计数，删除重复且无表对应的issueMode；模板allowedIssueModes是唯一途径约束，不新增分途径库存。P01授权管理列表包含完整draft/currentVersionNo及可选issuePolicy；首次配置expectedRevision=0，发布响应P04包含不可变definition/snapshotHash。已知版本历史由现有内部目录端口读取，不新增外部详情路由，外部接口总数为48。


JB-009范围预检落点：EntitlementScopeValidationService通过ProductScopeFactsPort与MerchantScopeFactsPort查询owner事实（显式tenant，分批200条）；门店/商品须属于该主体，类目为有效平台类目。EntitlementScopeResolver从MERCHANT会话/BizContext构造CatalogScope，platformActor固定false；有限门店员工只允许授权STORES子集。完整模板写入必须用resolveDefinition，额外核实GIFT.benefitRef商品归属；仅scope校验不能代替完整定义校验。P01–P05仍未开放，新HTTP实现须调用此守卫，不接受客户端owner/actor字段。配置允许READONLY门店及未上架商品，不代表允许实时交易，核销另查时点状态。

C02/C07统一沿用实际BizPerms.GOODS（biz:goods），不虚设不存在的read/update子权限。其余新增pay权限仍属拟登记清单，必须与真实Handler及角色测试同时交付，不提前给现有角色增权。后续新增的商家储值计价预览已登记为P31。


## 十一、内部事实查询与传输约定（JB-008设计冻结子项）

以下是待JB-041接线的内部路由，不是已注册Handler，不计入49条外部路由。全部POST、JSON，正文为{context,...args}；context为EntitlementContext，其余属性名与SPI方法参数一致。query/quote/preview虽用POST承载带身份范围的正文，仍只读，不能被通用POST重试器当作写命令。返回直接解SPI record，沿既有/internal路径跳过公网ApiResponse包装。目录既有两个方法不改Java签名，远程适配器先验证调用context，再向本地端口传tenant/entity。

| 所有者 | 路径 | 对应Java方法 | 参数（除context） |
|---|---|---|---|
| core | /internal/core/member/customer-reference/query | CustomerIdentityPort.resolveReference | query(entityNo,storeNo,customerRef) |
| core | /internal/core/entitlement/source/query | SourceDecisionPort.query | source |
| core | /internal/core/entitlement/authorization/query | SourceDecisionPort.authorization | authorizationRef |
| core | /internal/core/promotion/participation/reserve | ActivityParticipationPort.reserve | command |
| core | /internal/core/promotion/participation/confirm | ActivityParticipationPort.confirm | command |
| core | /internal/core/promotion/participation/release | ActivityParticipationPort.release | command |
| core | /internal/core/promotion/participation/query | ActivityParticipationPort.query | source |
| pay | /internal/pay/entitlement/catalog/version | EntitlementCatalogPort.getPublishedVersion | entityNo,versionNo；tenant来自context |
| pay | /internal/pay/entitlement/catalog/validate-reference | EntitlementCatalogPort.validateReference | query；query.tenantNo必须与context一致 |
| pay | /internal/pay/entitlement/grant/prepare | EntitlementGrantPort.prepare | command |
| pay | /internal/pay/entitlement/grant/commit | EntitlementGrantPort.commit | command |
| pay | /internal/pay/entitlement/grant/abort | EntitlementGrantPort.abort | command |
| pay | /internal/pay/entitlement/grant/query | EntitlementGrantPort.query | entityNo,grantNo |
| pay | /internal/pay/entitlement/use/quote | EntitlementUsePort.quote | command |
| pay | /internal/pay/entitlement/use/preview | EntitlementUsePort.preview | query |
| pay | /internal/pay/entitlement/use/prepare | EntitlementUsePort.prepare | command |
| pay | /internal/pay/entitlement/use/commit | EntitlementUsePort.commit | command |
| pay | /internal/pay/entitlement/use/release | EntitlementUsePort.release | command |
| pay | /internal/pay/entitlement/use/reverse | EntitlementUsePort.reverse | command |
| pay | /internal/pay/entitlement/use/reverse/query | EntitlementUsePort.queryReverse | entityNo,reverseNo |
| pay | /internal/pay/entitlement/use/query | EntitlementUsePort.query | entityNo,operationNo |
| pay | /internal/pay/entitlement/query/mine | EntitlementQueryPort.mine | query |
| pay | /internal/pay/entitlement/query/detail | EntitlementQueryPort.detail | query |
| pay | /internal/pay/entitlement/query/usage | EntitlementQueryPort.usage | query |
| pay | /internal/pay/entitlement/query/allowances | EntitlementQueryPort.allowances | query |
| pay | /internal/pay/entitlement/query/activation | EntitlementQueryPort.activationQuote | query |
| pay | /internal/pay/stored-value/recharge/create | StoredValuePort.createRecharge | command |
| pay | /internal/pay/stored-value/recharge/submit | StoredValuePort.submit | command |
| pay | /internal/pay/stored-value/recharge/receive | StoredValuePort.receive | command |
| pay | /internal/pay/stored-value/recharge/cancel | StoredValuePort.cancel | command |
| pay | /internal/pay/stored-value/recharge/credit | StoredValuePort.credit | command |
| pay | /internal/pay/stored-value/recharge/query | StoredValuePort.queryRecharge | entityNo,rechargeNo |
| pay | /internal/pay/stored-value/query/balance | StoredValuePort.balance | query |
| pay | /internal/pay/stored-value/query/stores | StoredValuePort.stores | query |
| pay | /internal/pay/stored-value/query/consumptions | StoredValuePort.consumptions | query |

服务端沿InternalHttp.TOKEN_HEADER（X-Internal-Token）与shop.services.internal-token，常量时间比较，未配置/缺失/不匹配均拒绝；公网反代不开放/internal/**。传输参数不是外部用户身份凭证；接收方仍按tenant/源类型/主体与持久事实核实，不能把context.actorNo直接当作拥有全部pay权限的用户。源查询返回的本人、主体、金额、修订、快照摘要都须与原命令匹配。跨租户或不可见引用不返回别人的事实。JB-041须复用InternalHttp/ServiceCalls、连接超时3秒、读超时5秒及既有服务解析，不另起无鉴权HTTP客户端；上述约定与真实双部署测试同时落地。

### 11.1 pay权益事件（JB-014内部SPI）

事件使用shop-base的版本化record，仅由pay在权益/储值事实事务内写入`pay_ent_outbox`，提交后以相同`eventNo`桥接到共享`sys_outbox`。当前已具备持久化、稳定重放、恢复桥接和inbox处理基础；具体发行/核销/储值业务服务在JB-016/020/034–038中逐步接入发布器。载荷字段严格限于：

| eventType | 允许字段 |
|---|---|
| ENTITLEMENT_GRANTED | eventNo, grantNo, userNo, entityNo, templateVersionNo, sourceType, sourceNo, occurredAt |
| ENTITLEMENT_USED | eventNo, usageNo, userNo, entityNo, storeNo, assetKind, assetNo, amountMinor或units（二选一）, occurredAt |
| STORED_VALUE_CREDITED | eventNo, rechargeNo, accountNo, userNo, entityNo, storeNo, paidAmountMinor, creditedAmountMinor, currency, occurredAt |

载荷不得包含会员码、核销码、访问token或完整支付凭据。pay与共享outbox属于不同事务管理器，因此通过稳定事件号、重复事实校验及恢复扫描实现至少一次传递；pay行`SENT`只代表统一outbox已持久接管，不代表最终消费者已处理。当前不开放外部HTTP事件接口。

网络不可达、超时、5xx、解析失败均为传输失败，不得伪造UNKNOWN或CANCELLED；原操作保持待查询/PROCESSING，由恢复任务查原编号。UNKNOWN仅表示查询成功且没有可见持久事实，PENDING表示已有意图但未决定，两者都不能release。授权EXPIRED不等于源取消，必须先查询源决策。写请求不自动换编号重试，合法重投须同业务编号、原摘要及原幂等键；只读查询可有界重试，所有重试不打印码、token或请求正文。内部裸commit不向公网开放。

传输实际Handler、共享HTTP接口/路径常量及拒绝测试由JB-041交付；本节只冻结路由与语义，不将本地接口声明计作远程可用。


## 十二、能力门禁配置契约（JB-008基础实现）

默认值全部false；PayEntitlementProperties、PromotionFeatureProperties及JB-010的MemberCodeProperties按下列名称绑定运行配置。已实现的目录写入、新引用委派门禁，其余业务入口尚未实现，随责任任务逐项接线。开关由服务端部署配置控制，HTTP请求或预置模板不能打开。实际available须同时满足开关、owner实现就绪、行业准入、主体状态、门店权限及所需能力集合；缺失项明确返回missingCapabilities，未知能力也按缺失处理。不能以类存在、目录seed存在或单体能编译作为READY证据。JB-032实现目录筛选，JB-053完成权限及配置拒绝验证。

| 配置名 | 控制的新业务 | 实施任务 |
|---|---|---|
| shop.member-code.enabled | 统一会员码新签发/扫码解析；已有顾客引用查询仍校验原期限 | JB-010 |
| shop.promotion.presets.enabled | 新预置目录推荐/创建草稿 | JB-032/JB-033 |
| shop.promotion.activity.enabled | 新活动发布/参与预留 | JB-029/JB-031/JB-033 |
| shop.pay.entitlement.catalog.enabled | 新权益定义写入/发布/引用 | JB-025 |
| shop.pay.entitlement.issue.enabled | 新领券、批量发行与发放prepare | JB-016/JB-017/JB-018/JB-026 |
| shop.pay.entitlement.use.enabled | 新券/兑换权益使用意图 | JB-020/JB-026 |
| shop.pay.stored-value.enabled | 新储值办理及核销意图 | JB-034–038 |
| shop.pay.paid-card.enabled | 新付费卡商品绑定/销售；已有卡的使用另受use控制 | JB-039/JB-040/JB-042 |

开关关闭不阻断已存在资产的本人历史查询、已承诺源决策的commit/release、合法原券回补或恢复任务；这些操作仍按原事实及状态机执行，不能形成有扣款无权益。开关不能切换owner或回写旧表。付费卡同时要求权益发行/使用开关和商品、收款、履约、条款验收，不因打开paid-card就跳过底座。运行的本地/远程选择沿既有shop.pay.deployment=embedded/standalone，不能再造第三套部署模式。

预置JSON当前实际引用41个不同能力码（35个活动码及6个付费卡产品码）；下表固定其归属与验收落点。有关拼团/秒杀/赠品完整履约的额外引擎不因列入目录自动列为本批已交付，未实现时一律保持不可用。

| 能力码 | owner与验收落点 |
|---|---|
| CARD_PRODUCT | core商品/SKU绑定及pay已发布付费权益定义；JB-039/052 |
| PAID_ORDER | core售卡入口及pay售卡能力门禁；JB-040/052 |
| IDEMPOTENT_CARD_ISSUE | pay销售预留及按源行/件/权益幂等发行；JB-040/052 |
| ENTITLEMENT_LEDGER | pay权益发行及使用分录；JB-016/020 |
| ATOMIC_USE_DEDUCT | pay预占/扣次与core会员身份/本人确认；JB-020/042/052 |
| VALIDITY_POLICY | pay冻结有效期和首用激活策略；JB-019/042 |
| ACTIVITY_VERSION | core.promotion；JB-029 |
| ATOMIC_QUOTA | core活动预算JB-030、pay发行额度JB-015，各自原子 |
| AUDIENCE | core人群/活动；JB-029/JB-017 |
| COMBO_RULE | core计价；对应组合引擎未验收前保持缺失 |
| COUPON_BUDGET | core营销预算JB-030及pay发行敞口JB-015，非同一账 |
| COUPON_ISSUE | pay；JB-016/JB-017 |
| COUPON_RECEIVE | pay；JB-018 |
| CUTOFF_JOB | core/pay各自截止任务；JB-051 |
| DISCOUNT_CAP | pay订单券计价；JB-021 |
| FLASH_STOCK | core库存/活动秒杀；额外引擎未验收前缺失 |
| FULFILLMENT | core履约事实；JB-040/JB-042 |
| GIFT_AFTER_SALE | core赠品售后；额外流程未验收前缺失 |
| GIFT_FULFILLMENT | core赠品履约；额外流程未验收前缺失 |
| GIFT_STOCK | core赠品库存；额外流程未验收前缺失 |
| GOODS_SCOPE | core商品归属JB-009；pay使用时重验JB-020/JB-021 |
| GROUP_CLOSE | core拼团关团；额外引擎未验收前缺失 |
| GROUP_INSTANCE | core拼团实例；额外引擎未验收前缺失 |
| INDUSTRY_ADMISSION | core既有行业准入；JB-032/JB-053实连接线 |
| MARKETING_RESERVATION | core参与协议；JB-031 |
| MEMBER_IDENTITY | core会员码/本人授权；JB-010/JB-011 |
| MERCHANT_ACTIVE | merchant；JB-009事实校验，新交易仍重查状态 |
| MIN_QTY_DECISION | core价格事实/pay券门槛；JB-021/JB-029 |
| MULTI_USE_REDEEM | pay多次权益使用；JB-020/JB-042 |
| NTH_ITEM_ALLOCATION | core计价分摊；JB-029；具体玩法缺分摊验收时不可用 |
| ORDER_PRICING | core可信计价；JB-021/JB-029接线及回归 |
| ORDER_REFUND | core售后决策/pay旧券回补；JB-022，仅ORDER_COUPON |
| PAY_CREDIT | pay储值到账；JB-035 |
| PERIOD_INSTANCE | pay周期权益实例；JB-040/JB-042 |
| PER_USER_LIMIT | core参与次数JB-030、pay发行个人额度JB-015 |
| REFUND_ALLOCATION | pay原券受限回补分摊；JB-022，不开放储值退款 |
| SERVICE_SCOPE | core服务商品事实JB-009、pay使用范围JB-042 |
| SKU_PRICE_VALIDATION | core付费商品绑定；JB-039 |
| STORED_VALUE_REDEEM | pay储值使用；JB-036 |
| STORE_CODE_REDEEM | pay既有券码JB-023；不能替代统一会员码或查全账户 |
| STORE_PERMISSION | app/merchant；JB-009及JB-053 |

预置默认seedEnabled=false、publishOnCreate=false、requiresMerchantReview=true；AP/EP清单只说明期望玩法。能力快照由可信owner提供，门禁失败不静默降级为另一种权益或计价规则。配置绑定、独立开关与付费卡组合依赖已由EntitlementFeatureGateTest验证；目录关闭拒绝、幂等重放及历史读取由EntitlementCatalogGateFlowTest验证。core PromotionFeatureGate只接受可信owner提供的ActivityCapability就绪集合，未知要求始终保留在missingCapabilities中，不能把配置值用作就绪事实。预置目录、真实发布/交易门面的接线仍由JB-029/JB-032/JB-033等交付，未实现入口不能宣称已受门禁保护。


### 12.1 已实现门禁的调用位置与关闭语义

pay的catalog/issue/use/stored-value/paid-card由PayEntitlementFeatureGate提供，core的presets/activity由PromotionFeatureGate提供，均为owner内组件，不增加HTTP接口。新发行需要catalog+issue开关；已有权益的新使用只要求use开关，不依赖仍可发行。新付费卡销售须catalog+issue+use+paid-card四开关，且core另核实支付、商品、履约、条款和范围；已有付费权益的使用不能因为停止新销售而被撤回。储值办理用stored-value开关，储值新使用同时要求stored-value和use；已有承诺的后续过账与恢复不调用新增业务门禁。

目录create/saveDraft/publish及恢复ACTIVE在EntitlementCommandDeduplicator的work回调内requireCatalog，已成功命令先重放原响应；新命令关闭则同事务回滚命令claim，不留下PENDING。get/list/getVersion/getPublishedVersion不受开关阻断；PAUSED/ENDED允许执行以停止现有发行。validateReference用于建立新活动/商品引用，受catalog门禁；恢复原承诺必须读取原历史版本，不能重新validateReference制造新的资格要求。开关不替代角色、租户、主体、门店或源决策核实。

## 十三、错误、状态与权限冻结边界（JB-008）

| 情形 | 对外业务码 / 消息键 | 处置 |
|---|---|---|
| 格式/枚举/金额次数单位不合法 | BAD_REQUEST 10400 / err.bad_request | 拒绝命令，不入账 |
| 未认证 / 会话过期 | UNAUTHORIZED 10401 / TOKEN_EXPIRED 10402 | 沿既有HTTP401及登录语义，不用功能开关码冒充 |
| 角色、realm或门店范围不允许 | FORBIDDEN 10403 / err.forbidden | 作用域取可信会话，拒绝越权 |
| 不存在或对当前租户/主体/本人不可见 | NOT_FOUND 10404 / err.not_found | 不泄露其他主体事实 |
| 旧revision、同键异参、终态矛盾、失效模板引用 | CONFLICT 10409 / err.conflict | 不换业务号盲重试，先刷新或查询原操作 |
| 新能力开关关闭 | ENTITLEMENT_FEATURE_UNAVAILABLE 40040 / err.entitlement.feature_unavailable | 中文“此权益功能暂未开放，请稍后再试”；英文和阿语同键，不能误作缺少员工角色而跳登录 |
| 源UNKNOWN/PENDING、超时后提交结果未定 | 对应业务响应PROCESSING或原query状态 | 未决不伪造取消/成功；传输失败沿ServiceCalls分类，不吞成业务终态 |
| 预览不可用 | PreviewReason枚举 | 明确NOT_ACTIVE/NOT_STARTED/EXPIRED/FROZEN/INSUFFICIENT_UNITS/OUT_OF_SCOPE，只读不扣减 |

通用码沿既有ErrorCode及三份messages资源，40040已新增三语并经BackendI18nParityTest验证。未来owner实现若需要细化余额不足等提示，须在该业务任务同步变更契约、枚举、三语与真实失败测试，不自行将异常字符串当用户文案。无码无接口的储值/新付费卡线上退款仍保持禁止，不为它建立可调用公网路由。I11已有券售后受原使用事实白名单控制。

权限码以本文件C01–C11/P01–P31/O01–O07表逐条冻结：新增pay/ops:pay码仅随真实Handler登记，JB-025/026/027/033/038等负责门面，JB-053核对角色与运营五处登记；当前不向Perms及角色表写死权限，避免给没有实现的路由授权。公共DTO不接受tenant/actor/entity/platformActor作为本人身份；service token只证明服务调用方，不能替代业务范围与持久授权。

JB-008是协议/配置门禁基础交付。34条内部传输Handler/代理由JB-041实现，49条外部Handler由各业务任务实现；5张支撑设计表的正式迁移/Mapper由JB-011/JB-012/JB-046实现。不能把这些后续运行实现反向作为008的完成条件形成依赖循环，也不能因008完成而宣称它们已可用。JB-010补充统一会员码签发与解析；不能拿登录token或客户端用户号充当会员码。


## JB-010统一会员凭证契约

C11由core.member签发，tenant/user仅来自可信C会话；响应`{memberCode,expiresAt}`，expiresAt为ISO UTC。码为mc1_前缀的256位随机值，有效60秒。C08沿用biz:customer，输入`{memberCode,storeNo}`，tenant/entity/actor来自商家会话，并验证店员门店子集及目标门店的biz:customer权限（不得借用请求头另一家店的角色）；响应`{customerRef,maskedName,expiresAt}`。引用有效120秒，绑定租户、主体、门店、操作人和顾客，顾客/商家/门店每次重验有效性，不返回手机或用户号。

码和引用仅保存SHA-256摘要，禁止请求体/凭证原文日志；DTO toString脱敏，不走URL/query。非法/过期码40041，跨范围或过期引用10404；开关关闭40040。shop.member-code.enabled缺省false，仅控制新签发/扫码，既有引用查询仍按原到期时刻执行。统一码在60秒内可重复识别，扫码不产生财务效果，不替代JB-011本人确认。会员码到期不使已签发引用提前过期；引用到期不得刷新原引用，应重新扫码。重试签发得到新凭证，不缓存秘密原文为幂等响应。

CustomerIdentityPort本地实现归member，向源业务只返回userNo；远程传输路由列入JB-041，当前不创建内部HTTP Handler。C端确认时必须由源业务使用冻结的原B操作人范围查询引用，不能改成C操作人，也不能将操作者直接当顾客。引用用于建立源意图，消费者确认与持久授权依JB-011另行处理。目标48条之外的旧会员接口保持兼容，不新增单独储值核销码。


## 第一阶段运行契约（2026-10-19）

C03/C04/C06及活动详情沿原URL，以`model=unified`选择新Handler；缺省仍为旧契约，双方条件互斥。C04请求保持ActivityDraft扁平字段，响应ActivityView包含activityNo/status/revision/currentVersionNo/definition；definition为统一类型化规则。C05 POST `/biz/activities/{activityNo}/publish`请求`{expectedRevision}`返回VersionView。状态请求`{expectedRevision,status,reason}`；DRAFT→RUNNING委派同一发布校验及持久幂等。所有写操作必传Idempotency-Key；同键异参拒绝，成功重放不受后来开关/状态变化影响，但仍校验当前调用人的数据范围。B端沿biz:campaign，身份从MERCHANT会话与门店授权推导。

运营GET `/ops/promotion/activities?model=unified`、POST `/ops/promotion/activities/{activityNo}/stop?model=unified`复用既有运营读/改权限；强停请求包含expectedRevision/reason，数据域在查询和写入前校验，不让旧stop绕过版本规则。

core内部POST `/internal/core/promotion/participation/{reserve,confirm,release,query}`使用ActivityParticipationInternal共享DTO和X-Internal-Token；未配置或错误token拒绝。context不是业务凭据，reserve核验来源拥有者事实，confirm/release核验可信终态、同租户及完整原命令绑定。未接pay来源提供者明确不可用；UNKNOWN不按TTL释放。此为JB-041的core子集，不代表pay远程协议已完成。

GET `/biz/marketing-summary?from=ISO-UTC&to=ISO-UTC&storeNo=...`返回币种分组及ORDER优惠额/事件数，最长366天，按可信员工门店过滤。旧`/biz/marketing/summary`保留，不把缺币种历史金额混合统计。C端账单/核销与pay业务接口留在第二阶段。

46项预置目录展示真实readiness与missingCapabilities；统一可执行预置仅AP009/AP010、MAIN/CNY固定CUT。其他预置返回PRESET_EXECUTOR等诊断；仅开部署开关或目录行不能绕过执行器校验。服务端资格证据核验商家、行业、门店，冻结人群条件与规则随已发布版本读取。


## 第二阶段B1内部源事实补充

EntitlementGrantPort原签名不变。新增owner扩展EntitlementGrantSourcePort.supports/resolve，返回tenantNo、完整原GrantCommand绑定及可信paidAt；pay必须逐字段一致，不把paidFactRef/permit原文当凭证。来源适配随031/032/040接入，没有真实provider拒绝新增发放；终态仍经SourceDecisionPort核实，UNKNOWN返回PROCESSING，不按超时推断取消。调用发生在pay资源事务之前。

Grant准备时持久保存完整命令、不可变定义、支付时间与计算的发行敞口，供关闭新发行后继续确认/释放。资金敞口按固定现金券面额/封顶券上限×每实例总使用次数×实例数量计算（现金券沿原算法忽略百分比封顶字段）；非现金次数类只累计发行数量，不能当现金备付。缺正数封顶（包括0）的百分比/免运费券暂不发放，避免把无法确定的金额授权写0。现有事件桥接绑定MAIN，本批发放执行入口对其他tenant明确不可用，不能串入MAIN事件；多租户事件承载由041/053对账后开放。


B1批量发行：CreateBatch仅接受templateVersionNo/audienceFactRef；EntitlementIssueAudiencePort由core受众owner返回固定身份/资格快照（每批最多1000项），禁止调用方传用户列表。resume每次1–100项，重复/重启沿原游标与稳定grant号。PAY/TARGETED来源由持久批次自身证明，使用GrantSourcePort.decision返回明确发放决定；其他来源仍查SourceDecisionPort。容量/来源冲突记逐项SKIPPED和原因，能力关闭保留pending；未知/传输异常不推定成功。失败项回滚后更新结果，不能吞掉一个已标rollbackOnly的事务并提交游标。


B1本地受众适配落于core.member.EntitlementIssueAudiencePortImpl：audienceFactRef当前解析为已有主体人群号/预设人群键；调用会员owner核实归属、会员受众和有效消费者，固定结果存入pay批次，不重筛。其他租户不借MAIN人群执行。独立pay部署的受众传输归041，未接时明确不可用。


### 第二阶段P01–P06实现补充

Java商家入口：`BizEntitlementTemplateController`；通过`EntitlementManagementAppService`选择内嵌领域服务或独立pay的`PayInternalApi`。P01列表返回完整草稿、当前版本号与可空issuePolicy；P02/P03/P05写响应不再附加后续变化的策略，以保持命令幂等响应稳定。策略独立由P06配置。商家作用域来自会话，4个pay权限仍各自校验，限制门店员工不得管理全主体模板。

独立pay承载目录/管理9个内部POST：`/internal/pay/entitlement/management/{list,get,create,draft,publish,status,issue-policy}`与`/internal/pay/entitlement/catalog/{version,validate-reference}`。请求使用`PayEntitlementRequests`，不得直接对公网暴露。共享密钥空或错误401；领域BAD_REQUEST/FORBIDDEN/NOT_FOUND/CONFLICT/FEATURE_UNAVAILABLE分别400/403/404/409/503；主应用映射原业务错误，网络/鉴权配置错误报内部异常，不能返回空列表或自动重试写。其余grant/use/储值内部承载尚待对应任务。


### 使用领域与查询实现裁定（第二阶段）

- Preview/quote在pay持久保存不可变提案，只写元数据，不预占、不激活。新UseSourcePort由core提供可信优惠后行项、运费、归属、门店名、类别快照及预约服务日；未接owner时fail closed。I08冻结本人授权和源事实，I09/I10终态以SourceDecisionPort为准，UNKNOWN/PENDING保留预占。原承诺完成不受停发/新用开关阻断。
- 当前订单合同仅单券，selectedInstanceNos最多1项；多券返回能力未就绪。quote快照记录资产修订，订单券hold使用该修订；expectedAssetRevision继续用于preview型卡/兑换，不能通过订单券调用方传0绕过quote快照并发校验。后续多券需扩展原子多分配命令。
- FREE_SHIP分摊行键固定`__FREIGHT__`，只减少运费；goodsAmountMinor与可信优惠后行项合计一致。优惠类型和分摊共用CouponCalculation，新旧券无两套算价实现。
- Query投影无授权/规则JSON/资金拆分；cursor使用HMAC绑定本人和筛选，30分钟有效，原始租户/用户号不在cursor明文中。各节点配置相同的SHOP_PAY_ENTITLEMENT_CURSOR_KEY。ActivationQuote在FIRST_USE尚未激活时ON_FIRST_COMMIT，已激活改为NONE并返回实际窗口；读取永不激活。
- I11合法回补、P30券码、真实订单协调以及使用/查询内部HTTP仍为后续任务，不能视为本批已开放。


内部源事实追加可空serviceAt（预约发生时刻）；pay按不可变规则zone换算serviceDay，避免currency推断时区。旧serviceDay和构造方式兼容。新增EntitlementUseFactsPort为内部core记账提供原实际hold的命令、规则/出资方、分摊和订单摘要，查询同时隔离tenant/entity；不向公网返回此含授权的内部DTO。报价后重新准备时重算当前类目适用分摊，不将旧提案误作新资格证明。传输在JB-041继续接线。


I07/I10允许可信取消先于prepare，持久负向事实后返回ABORTED/RELEASED。再次prepare/query仍回同一终态；不存在实际资产/hold时不制造资产引用。UNKNOWN/PENDING仅PROCESSING且不写取消标记，来源/租户/受益主体不匹配拒绝。内部UseFacts与普通query对负向终态一致，command/rule在无实际hold的RELEASED可空；HELD/COMMITTED仍必须有完整冻结事实。调用方不得把NOT_FOUND当作原请求将永不到达的证明。


### JB-020订单券协调补充（执行中）

核心服务以`ord_ent_order_use`保存原操作、授权、命令与原订单摘要，先持久化PENDING再向pay预占；pay返回的真实冻结分摊经核心服务核对后一次应用价格。`ord_ent_payment_seal`在调用外部收银前由核心服务锁单封存应付金额；已发起过收款或已封存的订单不能新增优惠。核心服务终态事务同时记录USE_COMMIT/USE_CANCEL及Outbox；应用协调器只提交/释放原operationNo，丢响应按原操作恢复，不以超时推断取消。暂停新增能力不阻止已有承诺完成。

本切片仅接ORDER_COUPON；服务券/次卡的真实服务意图和储值扣款仍在原任务内继续，不能用普通现金订单支付成功冒充已完成扣次。每单首期只接受一张新订单券；预占后不支持替换券，需取消原订单后重新下单。新表均在核心库；不跨库事务，不直接访问pay实体。


### I11受限回补来源绑定

`OrderBenefitReverse`签名不变；sourceRef固定CORE/ORDER_REFUND，sourceNo=权威售后号、sourceLineNo=原usageNo，无unitIndex/entitlementIndex。ORDER_REVERSE决策需权威owner查询核实原本人/主体/门店/币种/订单摘要及本次discount归属。原reverseNo重放不受新增开关影响，同号异参409；同售后换reverseNo不重复回补；未知只返回PROCESSING，不信用调用方自报金额。累计归属到齐才恢复原使用次数，部分归属不会恢复一整次券。新储值/付费卡线上退款继续拒绝；旧券整单退回策略及URL接回仍归JB-049。

新增内部事件ENTITLEMENT_USE_REVERSED（schemaVersion=1）：reverseNo、原usageNo、本人/主体/门店、instanceNo、restoredUnits、discountMinor、currency、occurredAt及eventNo；不含授权、码或支付凭据。次数与优惠归属金额分量纲，不记作新收入。


### I11回补原号查询

响应丢失用queryReverse(context,entityNo,reverseNo)查询；内部POST `/internal/pay/entitlement/use/reverse/query`归041承载。ReversalView只包含reverseNo、NOT_FOUND/COMMITTED、usageNo?及occurredAt?；其他商家返回NOT_FOUND且不带原明细。新增单独只读query，不混用use operationNo和reverseNo命名空间。NOT_FOUND不是源取消证明，恢复仍用原reverseNo；reverse/query既有参数不变。

### P30兼容凭证领域能力

CodeRequest仅useContext=STORE_EXCHANGE；本人会话构造userNo/actor，不能由HTTP传入他人身份。CodeView仅token/expiresAt/instanceNo，日志toString脱敏。凭证至多60秒且受实例期限限制；重放原Idempotency-Key返回同一凭证，换实例则409。B解析由已核实门店范围的P12/P13应用层调用，得到内部userNo/instanceNo定位结果，仍须预览与核心服务的本人授权；不得以兑换码查完整账户或直接扣款。

兼容开关SHOP_PAY_ENTITLEMENT_CODE_ENABLED默认false；缺32字节以上稳定SHOP_PAY_ENTITLEMENT_CODE_KEY拒绝签发。支持签发幂等、过期/撤销拒绝、原操作+门店+操作人完全相同的短期重放；非原绑定均拒绝。STORE_EXCHANGE类型为首期模板准入策略，没有新增逐模板开关。C端主入口继续统一会员码；不增加储值专用码、C端独立核销码页面或线上退款。领域服务不代表P30 HTTP/041远程已交付，门面仍在026/041接线。


## 第二阶段：JB-041 权益运行协议承载

本批新增以下25个pay内部POST端点，复用`PayInternalApi`、`PayInternalPaths`和共享SPI record。
外部B/C接口仍由P编号任务实现，不将内部端点当作已开放的消费者接口。

| 常量 | POST路径 |
|---|---|
| `ENT_GRANT_PREPARE` | `/internal/pay/entitlement/grant/prepare` |
| `ENT_GRANT_COMMIT` | `/internal/pay/entitlement/grant/commit` |
| `ENT_GRANT_ABORT` | `/internal/pay/entitlement/grant/abort` |
| `ENT_GRANT_QUERY` | `/internal/pay/entitlement/grant/query` |
| `ENT_USE_QUOTE` | `/internal/pay/entitlement/use/quote` |
| `ENT_USE_PREVIEW` | `/internal/pay/entitlement/use/preview` |
| `ENT_USE_PREPARE` | `/internal/pay/entitlement/use/prepare` |
| `ENT_USE_COMMIT` | `/internal/pay/entitlement/use/commit` |
| `ENT_USE_RELEASE` | `/internal/pay/entitlement/use/release` |
| `ENT_USE_REVERSE` | `/internal/pay/entitlement/use/reverse` |
| `ENT_USE_REVERSE_QUERY` | `/internal/pay/entitlement/use/reverse/query` |
| `ENT_USE_QUERY` | `/internal/pay/entitlement/use/query` |
| `ENT_QUERY_MINE` | `/internal/pay/entitlement/query/mine` |
| `ENT_QUERY_DETAIL` | `/internal/pay/entitlement/query/detail` |
| `ENT_QUERY_USAGE` | `/internal/pay/entitlement/query/usage` |
| `ENT_QUERY_ALLOWANCES` | `/internal/pay/entitlement/query/allowances` |
| `ENT_QUERY_ACTIVATION` | `/internal/pay/entitlement/query/activation` |
| `ENT_USE_FACTS` | `/internal/pay/entitlement/use/facts` |
| `ENT_PAYMENT_ATTEMPTS` | `/internal/pay/entitlement/payment/attempt-exists` |
| `ENT_BATCH_CREATE` | `/internal/pay/entitlement/batch/create` |
| `ENT_BATCH_GET` | `/internal/pay/entitlement/batch/get` |
| `ENT_BATCH_RESUME` | `/internal/pay/entitlement/batch/resume` |
| `ENT_CODE_ISSUE` | `/internal/pay/entitlement/code/issue` |
| `ENT_CODE_RESOLVE` | `/internal/pay/entitlement/code/resolve` |
| `ENT_CODE_INVALIDATE` | `/internal/pay/entitlement/code/invalidate` |

core反向事实由`CoreEntitlementInternalApi`统一声明、实际订单/授权/来源owner提供：

| POST路径 | 实际责任 |
|---|---|
| `/internal/core/entitlement/source/use` | 核心订单与商品来源快照 |
| `/internal/core/entitlement/source/query` | 原来源的持久决定 |
| `/internal/core/entitlement/authorization/query` | 原消费者授权状态与完整绑定 |
| `/internal/core/entitlement/source/grant` | 唯一grant来源owner提供冻结事实；缺owner返回503 |
| `/internal/core/entitlement/source/grant-decision` | 来源owner终态；未提供时查询核心决定注册表 |
| `/internal/core/entitlement/audience/resolve` | 核心真实定向人群事实 |
| `/internal/core/entitlement/events/receive` | 白名单支付事件持久写入核心outbox后确认 |

所有端点验证`SHOP_INTERNAL_TOKEN`，缺失或错误返回401；业务错误按400/403/404/409/503映射，
未知异常不包装为成功。响应为空按能力不可用处理，查询不会生成新操作或自动重试写命令。
金额必须为整数最小货币单位，两个进程显式关闭Jackson的浮点转整数选项。
支付历史查询返回`PaymentAttemptView(tenantNo,orderNo,hasAnyAttempt)`，核对绑定后才使用结果；
不能把空体、错单响应解释为“没有历史收款”。代码失效返回带用户与实例绑定的明确回执。

主应用`shop.pay.deployment=embedded`（默认）只绑定本地owner适配；`standalone`只绑定远程适配。
pay-svc始终绑定支付owner，不在pay领域内读取部署开关。独立支付反查核心使用服务名`PLATFORM`，
地址由`PLATFORM_API_BASE_URL`配置；未配置继续拒绝访问，不生成虚构事实。
本批不宣称既有独立支付全域兼容已完成，储值与售卡运行端点随对应任务继续补齐。

事件交接采用原eventNo及不可变类型/版本/聚合/发生时间/载荷哈希；仅接受已登记强类型事件（当前五类，含批次准备事件）。
额外载荷字段、错哈希、错租户均拒绝；同号同事实返回原确认，同号异事实409。
支付收到核心持久化确认后才将原pay事件标记FORWARDED；确认丢失继续重试原事件。
每种部署只运行支付owner中的一套relay，消费者处理与通知完成不属于交接确认的含义。
当前事件租户仍明确限制MAIN，完整租户封装由JB-053收口。

订单协调在原命令已持久化后遇503或其他不确定错误，返回PROCESSING并保留占用；
只恢复原operationNo，未知结果不释放、不换号。已有积分分摊的订单暂不支持新券改价，
待JB-020/JB-049具备原积分分摊的原子重算协议后放开；旧积分与旧券流程不受此新增入口影响。


## 第二阶段：活动发放与本人领取

P11已实现：`BenefitClaimCommand(templateVersionNo,activityNo?,activityVersionNo?,entityNo,storeNo)`，
要求`Idempotency-Key`（非空，最多128字符），本人/租户从消费者会话构造。
请求不接受收件人、数量、资格凭证、决定或实付金额。活动与活动版本须同时提供或同时为空。
无活动时只允许CENTER发行途径，付费卡及仅定向发行模板拒绝；有活动时核实真实已发布活动版本、
当前运行状态、门店、人群、营销预算与限领，冻结该活动的完整权益包，不能由C任意拼装权益。
当前主动领取仅支持NONE触发的ENTITLEMENT_GRANT，订单条件活动须等待其真实来源执行器。
AP004进店领券已具备执行器与能力证据；预置仍默认关闭，需运营启用及通过商家准入后才能发布。

返回`ClaimReceipt(intentNo,status,instanceNos)`，状态COMMITTED/ABORTED/PROCESSING。
多权益活动返回一个领取意图，包含所有成功实例号；不向C暴露子命令、内部许可、来源决定或双池字段。
`GET /mp/entitlement-claims/{intentNo}`只查询本人原意图及原pay事实，不隐式发放或恢复。
继续办理用相同P11幂等键和原请求；异参409，不生成替代grant。后台持久决定事件继续原操作。

调用顺序：core begin冻结来源与一次活动预留→pay逐子prepare→core决定+outbox同事务→
pay逐子commit/abort→按全部真实pay终态confirm/release活动→core记录完成。
全部prepare成功才允许COMMIT；某子确定失败则整体ABORT并逐子建立取消栅栏，防止迟到prepare。
某次远程结果未知则PROCESSING，原营销占用与pay准备承诺保留，不按TTL回收。
决定后遇失联只恢复原决定；消费/发行开关变更不撤销既有承诺。
一次活动只消耗一个营销名额，多个权益项使用稳定sourceLineNo/grantNo；源owner核对完整冻结命令。
`ACTIVITY_BENEFIT_DECIDED`不含许可/码/余额，重复和乱序投递均重读原核心计划。


## 第二阶段：本人资产与商家记录查询

P09/P10/P17/P25/P26/P29已实现；同时接入只读起算预览P16及默认关闭的短期券码P30。
C端租户和userNo取登录会话，统一列表不先选主体；详情/核销详情/日额度中的entityNo是可选的额外筛选，
未提供时仍须同时匹配tenant+sessionUser+instanceNo/usageNo。提供错误主体不会回退为全主体查询。
P09沿已有签名游标绑定本人、主体/类型/状态筛选与稳定主键；size范围1–50，禁用STORED_VALUE类型，储值另走P23/P24。
P10只返回本人实例及历史；P25返回原核销项目、门店、人员、源订单与原消费账后的余次；不拿当前余额改写历史。
P17 serviceDate可选，按卡内固定时区取服务日；P16只预览起算效果，均不写日额度、不激活、不发事件。
P30仍要求Idempotency-Key及STORE_EXCHANGE上下文，受独立默认关闭开关控制，不能为付费卡或储值签码。

商家记录P26新增共享`BizUsageQuery`及POST `/internal/pay/entitlement/query/merchant-usage`。
主体来自商家会话，每个可见门店还需具备pay:ent:read；空门店权限集合返回空列表，不省略IN条件放大数据域。
owner全主体范围仅在可信内部DTO中以null集合表达；远程传输区分null与空集合。
按业务发生时间倒序及usageNo倒序稳定排列，from含、to不含，接受ISO8601 Instant；page为1–10000，size为1–50。
金额带币种、次数独立返回，不把次数当金额。新权限不自动加入店长或其他预置员工角色。
P29必须提供指定门店及原customerRef；通过会员码识别取得的引用绑定租户/主体/门店/经办人，不能换店复用。
对STORES范围的实例，非适用门店查询返回404。商家不直接提交任意顾客userNo读取资产。


## 第二阶段：商家定向批量发放入口

P07/P08已实现，分别要求pay:ent:issue与pay:ent:read；人群跨主体门店，首期只接受主体全域管理上下文。
受限门店角色不能通过全主体人群向其他门店顾客发放。新发放权限不自动授予预置店长。
P07请求复用`CreateBatch(templateVersionNo,audienceFactRef)`；受众引用由核心真实会员人群解析，
不接受前端传顾客名单。Idempotency-Key非空且最多128字符，应用转SHA256内部键后沿原请求幂等处理。
创建返回冻结的受理结果；重试相同键返回原受理结果，P08读取当前真实逐项进度，GET不会续跑或发放。

支付事务同时保存批次、全部合格对象的准备承诺及`ENTITLEMENT_BATCH_PREPARED`事件。
即使创建响应丢失，也不会出现只留批次却缺续跑触发的间隙。事件字段仅为eventNo/tenantNo/batchNo/entityNo/actorNo/occurredAt；
无顾客名单、明码或余额。此事件是第五个已登记支付事件，仍只允许MAIN，其他四类既有事件哈希格式不变。
事件经原pay→core持久确认通道交接；EntitlementBatchPreparedConsumer按原批次和原游标推进，
每次resume最多100项，每次消费最多10轮（冻结人群最多1000项）；正常分段推进不当作消息失败。
游标不能推进或远程结果未知时重试原批次，不生成新发放来源。重复事件不重新选人、不追加容量。
最终COMPLETED或PARTIAL表示逐项处理结束，不保证所有人均成功；必须显示issued/skipped/failed/pending及原因。


### 第二阶段服务核销入口实现（P12/P13/P14，V442）

核销单是核心服务的真实非现金业务源`CORE/SERVICE_USE`，修订固定0；支付只负责预览、预占和扣次。
服务时间由服务器记录；项目来自真实在售且支持STORE_VERIFY的SERVICE SKU，数量逐项目冻结。
不新增现金订单或收入事件。下面路径均已实现；P15订单券报价与储值用款另按任务表推进。

| 入口 | 请求 | 响应与权限 |
|---|---|---|
| POST /biz/entitlement-uses/preview | Idempotency-Key≤128；storeNo、instanceNo、items[{skuNo,quantity}]；customerRef/code/orderRef三选一；serviceDate、units可选但必须等于真实源 | pay:ent:use且门店范围；{operationNo,orderRef,sourceRef,preview}；预览不扣次；CODE仅可选兑换券定位 |
| POST /biz/entitlement-uses | {sourceRef,previewRef,expectedAssetRevision,authorizationRef?} | pay:ent:use、原经办人及门店；{operationNo,status,holdNo,usageNo,occurredAt}；服务端从源派生会员、项目、次数、币种和授权引用 |
| GET /biz/entitlement-uses/{operationNo} | 原操作号 | pay:ent:read及门店范围；只读。授权前WAIT_AUTH，确认待商家提交WAIT_SUBMIT；已过新提案期限EXPIRED；未知PROCESSING；扣次后COMMITTED |
| POST /biz/entitlement-uses/{operationNo}/cancel | {reason}≤100字 | pay:ent:use、原经办人及门店；仅取消尚未完成的核销请求，不是退款；负栅栏阻止迟到prepare |
| GET /mp/entitlement-service-orders | size默认20、1–50 | C本人尚待确认/提交/处理的核销单，不接收userNo |
| GET /mp/entitlement-service-orders/{serviceOrderNo} | 核销单号 | 仅C本人；门店、项目、数量、服务时间、权益标题、扣后余次、确认元数据；不返回原命令/身份码/授权引用 |
| POST /mp/orders/{serviceOrderNo}/entitlement-authorizations | 复用C09 {orderRevision:0,quoteRef,selectionDigest,requestKey} | 本人确认实际服务项目；同一授权表和状态机；确认后仍需B提交，不自动证明服务已履约 |

C服务单确认投影为`confirmation{orderRevision,quoteRef,selectionDigest,expiresAt}`；服务单及消费明细不展示
本金/赠金，不提供线上退款或购买入口。可选兑换码仍默认关闭，不新增C独立码页面；开通兼容后仅定位
STORE_EXCHANGE实例，不能替代本人确认，不能核销储值。明码仅送pay解析，不入日志/业务快照；
解析绑定预先固定的原操作、门店和经办人；同请求摘要的已有服务单可在码过期后重放。

核心服务先持久原UseCommand，支付读取UseSourceFact.stagedOperationNo匹配原操作后才接受新prepare。
新准备必须授权未过期；已有持久预占承诺按原命令及源决定恢复，即使当前授权过期也不重新发起扣次。
UNKNOWN保留原号与占用，不按TTL释放；源决定与UseDecided同事务，消费者重复投递不会重扣。


### P15订单券报价与确认衔接（已实现）

`POST /mp/entitlement-quotes`接收`{orderDraftRef,orderRevision,selectedInstanceNos}`；draftRef当前指实际待付款
子订单号，C会话必须为该订单本人；订单金额、项目、主体、门店、币种与源修订从core读取，首期最多1张券。
报价只试算，不冻结资产。选定有效优惠时，将原源事实和pay QuoteView冻结在现有授权记录的
`order_use_snapshot`（V443新增可空JSON），不新增授权表或账户。

C09确认这类报价时，core一个事务完成本人授权、原UseCommand与UsePrepared outbox；app随后调用原协调器。
报价为空选择时不创建使用承诺；旧授权/服务核销C09沿原语义。AuthorizationView兼容保留3字段，新增可空
`operationNo/useStatus`说明原订单券使用进度（HELD已实际改价，PROCESSING不可新发支付）。同确认请求重放原
授权与操作，不重新选券。核心原支付封口仍阻止未应用价格的PENDING请求发起现金支付。


### C07商品权益版本绑定（已实现）

POST /biz/card-product-bindings，权限biz:goods且主体全域配置权限；Idempotency-Key大小写敏感、最长64。
请求仍为`{goodsNo,skuNo,expectedProductRevision,templateVersionNo,unitsPerItem}`；返回
`BindingView{goodsNo,skuNo,templateVersionNo,unitsPerItem,currency,status,productRevision,bindingRevision}`。
只允许实际CARD商品的商家主市场SKU和已发布、仍可用于PAID_ORDER的PAID_CARD版本；币种/主体校验，
售价不改。成功会递增商品修订；旧键返回原结果，同键异参及新请求旧修订409。绑定不等于已接通售卡发行，
后续040按实际付款与原订单逐件快照发放，既有绑定不因模板草稿或新发布版本自动改变。


### JB035内部到账实现范围（实施中）

StoredValueCreditService沿既有CreditRecharge，只接收原`PAY/RECHARGE`与RECHARGE_RECEIVED决定引用，
不接收金额；读取自己pay持久的实收记录、原core来源与ParticipationPermit核对。复用RechargeView无本金/赠金
字段，内部备查账不直接作为B/C响应。记录CREDITED重放先校验原决定及凭证/批次，返回原到账时刻和修订；
账户及流水不重复增加。B实收和StoredValuePort HTTP/远程入口仍未开放，待034/041真实来源接通。

### 储值来源及实收内部承载（JB-034/041增量）

尚未接完的公共入口不因内部承载存在而视为开放。P18仍收customerRef/activityNo/versionNo/tierNo/storeNo，不接受本金、赠金、受益人、币种或确认人。core源工厂按真实身份和市场冻结后，内部CreateRecharge改为`(sourceRef,permit)`，避免恢复依赖已过期的原始会员引用。CORE/RECHARGE(intentNo)是营销来源，PAY/RECHARGE(rechargeNo)是支付实收决定，二者不可替换。

反向事实POST为`/internal/core/stored-value/source`（context+source）、`source-by-recharge`（context+rechargeNo），返回完整冻结SourceFact。pay内部POST为`/internal/pay/stored-value/{create,submit,receive,cancel,credit,facts}`，create/submit/receive/cancel均携带可信context和entityNo，credit只能带原PAY实收决定；facts返回安全RechargeView、许可及来源绑定，不返回收款凭据或内部双池。全部沿内部令牌、非空响应及明确UNKNOWN语义，不自动重试写请求。

人工实收是持receive权限的商家对原CASH/TRANSFER收款凭据的明确核验，不宣称平台银行核验。提交金额必须等于冻结应收，receive必须匹配原revision及receiptDigest；实际确认人/时间由服务端记录。不同命令键重复操作遵循状态守卫，同键原样重放返回原操作答复；create额外返回当前实际状态，避免已取消原单被旧WAIT_COLLECTION答复重新提示收款。到账可异步，查询不能代替再次确认实收。

商家充值协调当前实现：P18创建后只有实际pay建单应答才返回WAIT_COLLECTION；已有core意图但pay尚无应答，P27返回业务PROCESSING，不能解释为可收款。P20返回实收的当前事实，到账可以继续为RECEIVED；Received Outbox触发原单credit并确认core营销。P27保持纯查询。三个明确权限pay:sv:recharge:create/receive/read均按该笔原门店校验；无新C购买、退款或独立码接口。

### JB-040付款前预留协议（内核已编码，RPC/应用协调待接）

公共端点仍49条；以下是新增内部SPI设计，不计为已上线HTTP端点。`EntitlementSaleReservationPort`与发行`GrantCommand`分离，不以假paidAt/paidFactRef模拟未付款发行。

| 操作 | 输入 | 响应与语义 |
|---|---|---|
| reserve | context + ReserveSale(reservationNo,sourceRef,templateVersionNo,userNo,entityNo,benefitSnapshotHash) | SaleView；真实待支付且未过截止、冻结规则等于目录历史版本、发行门禁及总/个人容量通过后RESERVED，不建实例 |
| release | context + ReleaseSale(原ReserveSale,decisionRef) | core持久GRANT_ABORT并完整匹配才RELEASED；UNKNOWN/PENDING返回PROCESSING且不改容量；可信终态与调用引用不符409 |
| query | context + entityNo + reservationNo | NOT_FOUND/PROCESSING/RESERVED/TRANSFERRED/RELEASED；不触发支付、发行或释放 |
| source（反向事实） | context + 原ReserveSale | SaleSourceFact(binding,currency,ruleSnapshot,canReserve,decision)；core真实主单及逐件快照核对，不访问pay表 |

预留号固定为SR+原PAID_CARD_ORDER grantKey前60位；CORE来源revision=0，行/单件/件内权益索引齐全。查询响应只含reservationNo/sourceRef/status/grantNo；TRANSFERRED必须带原grantNo。原号原参重放先于今日开关与发布检查；异参409。已释放原号不重开；缺预付款承诺的已付款来源不能临时占额发行。销售付款前必须等待所有单件预留成功，此应用封口尚未接线，销售入口保持关闭。

发行仍使用既有prepare/commit/abort/query。PAID_CARD_ORDER的prepare先读取冻结预留，再查可信实际付款，移交原容量；模板暂停或停止新发行不阻断既有承诺。事件`ENTITLEMENT_GRANTED`保留原sourceType=PAID_CARD_ORDER，codec明确允许该类型，不篡改来源为PAID_ORDER。RPC适配、订单逐件协调、Outbox与恢复归后续JB-040/041。

### 售卡预留传输适配增量

新增EntitlementSaleReservationPortImpl、本地EntitlementRuntimeRouting与pay-svc Bootstrap装配；standalone由RemoteEntitlementSaleReservationPort调用PayInternalApi，三条POST为/internal/pay/entitlement/sale/{reserve,release,query}，共享PaySaleRequests。InternalSaleReservationEndpoint沿既有内部令牌校验和异常映射，不重试写操作。反向SaleSource RPC及真实HTTP往返专项尚待实现，销售门禁继续关闭。

### 售卡反向来源传输增量（2026-10-19）

新增POST /internal/core/entitlement/source/sale，共享SaleSourceQuery(context,ReserveSale)，返回SaleSourceFact；InternalSaleSourceEndpoint沿内部令牌认证、唯一owner选择与标准错误映射。pay-svc的CoreEntitlementGrantSourcePortImpl同时实现EntitlementSaleSourcePort，经既有CoreEntitlementInternalApi反查，不跨库、不在pay锁内发起网络调用。Bean工厂返回具体类型，使两个SPI在启动时均可发现。三条pay预留POST补真实HTTP测试，覆盖reserve/release/query及错误令牌拒绝调用业务。完整订单销售协调仍未验收，JB040/041保持DOING。

### JB040取消事件与付款前容量校验接线

core OrderServiceImpl的cancelPayment/closeOne在原事务内写GRANT_ABORT，存在售卡快照才发布CardSaleEvents.Cancelled（CARD_SALE_CANCELLED）。CardSaleEventConsumer替代原仅付款消费者，消费取消时必须全部RELEASED才能确认；失败沿统一Outbox重试，不因截止释放。无售卡快照不发布额外事件。

PaidCardPaymentGatePort/Impl在OrderServiceImpl.pay发起支付尝试前调用协调器，零售卡权益不影响原订单；有权益但未全部RESERVED拒绝继续。该调用点无core写事务。此处尚非所有收款路径验收，线下收款等仍待核对；商品售卡门禁保持关闭，真实建单快照尚未接齐，不宣称完整上线。新增测试验证重复取消事件不双释放，取消后的付款预留检查拒绝。

### JB040建单权益快照接线

CardProductSalePort新增saleDefinition内部读取（无绑定返回空），SaleDefinition包含原模板版本、币种、每件权益数、已发布规则JSON。core商品owner从真实商品归属推导tenant，按原商品/SKU查绑定，仅ACTIVE且未删除可读取，不调用pay。OrderServiceImpl在真实OrdItem插入后以稳定OL+主键构建逐件/件内权益快照，检查币种并加入原建单事务；只对购买行展开，不把赠品行当付费卡发行来源。读取/快照失败原建单一起回滚。

requirePurchasable仍拒绝新绑定销售，未因接线而开放。当前测试覆盖真实绑定读取与版本副本、源快照、预留/发卡/取消；尚缺开放门禁后的真实checkout全链路专项。线下收款目前存在事务包围，需要先拆边界再接预留检查，不能在持锁事务里调用pay。

### JB040/051售卡恢复扫描

V451只扩展core原售卡快照delivery_status及恢复索引，不新增表（运行表仍250）。PaidCardSaleRecoveryPort/Impl读取主单已PAID/CANCELLED/CLOSED且待确认的订单，支持afterOrderNo、1–200上限及稳定排序；不扫未付款单、不按TTL释放。CardSaleRecoveryService/Impl在事务外逐单调用原协调器，全部发行或全部释放才acknowledge；失败计数并继续其余订单，原记录保留PENDING。

CardSaleRecoveryJob沿JobHandler/JobDeclaration/JobSupport及ShedLock注册card-sale-recovery，受shop.job.enabled控制；shop.job.card-sale-recovery.cron默认每5分钟。每批100单，游标轮转越过故障单；成功确认持久化，进程重启只重复未完成单。调度失败由既有作业结果记录，恢复不退款、不调平、不更换原预留/发行号。源Port属于core，扫描不读取pay表。此内部扫描无新增公共端点、权限或消费者字段。

新增场景验证：sweepRecoversLostPaidEventAndPersistsAcknowledgement、failedRecoveryRetainsPendingWithoutIssuing。尚待原生迁移、调度故障/并发及最终checkout专项；父任务仍DOING，不据定向测试直接开放销售。

### JB040完整checkout专项与默认关闭准入

新增shop.trade.paid-card-sale.enabled（默认false）显式开关，仅用于允许有效绑定进入core建单；ACTIVE/未删除/真实SKU与规则副本仍校验，pay侧发行/使用/付费卡开关和容量继续独立守卫。默认配置保留旧拒绝行为，不自动修改部署配置。新PaidCardCheckoutFlowTest使用真实商品绑定及真实OrderService.createFor，2件×每件2权益须持久4条来源、付款前0实例、预留4、markPaid状态机/Outbox事件后发行4，重复消费与恢复仍4。markPaid为内部集成测试调用，不代表外部通道实际收款验收。

首轮专项发现测试缺门店导致来源快照校验失败。生产快照门店改为sub.getStoreNo，与真实履约/库存落地一致；测试补真实默认门店，不放松门店必填。尚需独立部署、原生迁移、并发及真实通道演练后才建议开启新开关，父任务不提前标完成。

### JB043交易站内通知切片

EntitlementNotificationSubscriber实现OutboxConsumer，仅处理ENTITLEMENT_GRANTED/ENTITLEMENT_USED/STORED_VALUE_CREDITED。共享codec校验收件人、业务字段，额外匹配eventNo/aggregateType/aggregateId，防止错事件身份发送；dedupKey为EN+SHA256(事件号+USER+收件人)，复用MessageService.push的全局持久去重及既有站内信发送日志。只写TRADE站内信，未开通短信/微信/推送，不新增NotifyScene通道种子，不制造未落地C路由；link为空。不展示双池、账户号、原凭据或会员码，不调用pay资金/权益写服务。失败抛回现有Outbox重投，成功消息重投仍唯一。

EntitlementNotificationFlowTest覆盖到账/发放/核销三类真实消息落库与重复去重、错头/无收件人拒绝、消息首次失败后恢复且未创建账户或发卡。当前是消费者站内通知子项；外发、商家/运营异常通知及完整O06治理仍待后续，JB043不能提前DONE。

### JB045支付异常记录与平台查询内核

V452建既定pay_ent_exception，PayEntException/Mapper归pay数据源。EntitlementExceptionService/Impl实现captureGrantFailure/get/list；只捕获真实PREPARED grant，先锁原grant并按tenant+kind+原grantNo唯一认领，稳定异常号EX+来源摘要，不接收调用方提供的证据或受益人。返回Evidence白名单只含原grantNo、捕获时状态、来源类型和固定DELIVERY_FAILED，不回传付款凭据、用户授权或原命令。捕获不改变容量/实例/分录。

查询限定platformActor且按tenant/entity收窄；分页1–100条，稳定createdAt/id倒序。该内部CatalogScope须后续由运营可信会话/数据域构造，不能直接绑定请求。O01–O04的HTTP/权限、独立pay适配、按原号恢复及动作审计尚待接，本批不开放伪实现的运营端点。新增真实PREPARED发行捕获/重复、脱敏、非平台和跨主体拒绝场景，父任务仍DOING。

### JB045原grant恢复与动作审计内核

V453新增pay_ent_exception_action及实体/Mapper；ExceptionViews.RecoveryCommand只含expectedExceptionRevision/reason，幂等键另传，不接受新受益人、金额或权益。EntitlementExceptionRecoveryService/Impl调用EntitlementExceptionActionWriter.begin认领动作与异常PROCESSING，释放pay锁后读真实来源决定并调用原grant commit/abort；UNKNOWN/PENDING不释放容量，完成动作并将异常回OPEN供下一次核实。成功原grant终态后同事务写RESOLVED与DONE安全响应。原请求中断可用同键原参续办；同键异参或新键抢PROCESSING拒绝；完成响应沿原键稳定重放。

审计冻结操作者、主体、异常号、请求摘要、原因与最终回复，不保存原支付凭据。只捕获/恢复已有grant，不重建发行单。测试模拟begin后进程中断，再同键续办；原grant只发行1次、容量只结算1次、动作只留1条，重复回复稳定，异参/非平台拒绝。当前仍为pay内核，运营权限/会话和本地远程HTTP适配随后接线，不宣称O03已经可公开调用。

### JB045异常管理内部承载实施计划

新增内部POST /internal/pay/entitlement/exceptions/{list,get,retry-grant}，共享请求携带可信CatalogScope；只供已有内部令牌客户端使用。list/get委派异常查询、retry携带原exceptionNo/RecoveryCommand/原key委派恢复。app本地/standalone适配互斥，写请求不自动重试。先补传输及真实HTTP验证，再接运营会话/数据域和权限，不直接将CatalogScope绑定公共HTTP。

### JB045查源动作与内部传输

V454新增operation_kind区分QUERY_SOURCE/RETRY_GRANT，旧动作为RETRY_GRANT。ExceptionViews.Evidence新增可空sourceStatus；旧证据JSON及4参构造兼容。查源复用RecoveryCommand与独立key，锁外读取来源决定后把状态存入异常证据及动作响应；异常回OPEN，不调用grant commit/abort，也不结算占用。原查询键重放原结果；查询键改用于恢复冲突，后续恢复须带最新revision和新key。

新增内部POST /internal/pay/entitlement/exceptions/query-source，沿原内部令牌及共享RetryGrant请求、本地/远程AppService。无新增公开运营Handler，本批仍待O01–O04权限及可信会话/主体范围接线。测试覆盖COMMITTED来源仅查不发、原容量不动、独立动作审计、查源重放与恢复键隔离，然后实际原grant恢复；HTTP验证查源参数完整往返。

### O01–O04首版主体范围收窄

本轮1档实现：四条运营路由都要求entityNo查询参数，替代原O01可省略主体筛选的草案；首版不做跨主体聚合。权限分别ops:pay:ent:read/recover/verify，不自动授予FINANCE等岗位。scope只由当前OPERATOR会话构造，MAIN租户、actor来自会话，主体必须属于ALL或MERCHANT授权集合；缺scope、SELF/COMMUNITY等无法证明商家范围时拒绝。不接受请求体platformActor/tenant/actor。O03/O04带原Idempotency-Key及RecoveryCommand，返回安全ExceptionView（原OperationView草案收敛为含status/revision/bizNo的ExceptionView）。跨主体聚合列表和完整动作历史展示仍后续，不据四条Handler存在宣称父项完成。

### JB046权益次数账实对账内核

V456落既定PayEntReconRun/Diff及两个Mapper；EntitlementReconciliationService/Impl以payTxManager REPEATABLE_READ对同一主体的实例/分录做RIGHTS轴核对。CheckRights冻结日期/市场时区/afterInstanceNo/limit（1–200），RunView仅含批次、主体、量纲、检查数/差异数/下一游标，内部expected/actual JSON不直传页面。SUM余次/占用及MAX序号与实例逐份比较，一份实例无论几个字段异常只留1条BALANCE_MISMATCH，绝不调账。日限无限总次数实例不按虚构total比较余次；仍比占用/序号。

每页一个幂等批次，同键同参回原结果，异参冲突；有nextCursor必须后续用新key继续，不把单页COMPLETED当全主体完成。差异/批次与快照读取同事务，失败全部回滚。仅平台scope且按tenant/entity收窄，无ALL隐式跨主体。测试验证正常、差异、原号重放、差异不重复、余额保持29及非平台/跨主体拒绝。发行/金额/服务日桶对账、O05及本地/远程承载仍后续，JB046不能提前DONE。


### 对账扩展执行契约（N01，2026-10-19）

沿用O05 GET `/ops/pay/entitlement-reconciliations`，首版要求显式entityNo；新增GET `/{reconNo}`、GET `/{reconNo}/differences`和POST基础路径发起单页检查。读用`ops:pay:ent:read`，发起用`ops:pay:ent:verify`，均经运营会话数据域构造scope。POST携带Idempotency-Key，body为Check(axis,currency?,bizDate,marketZone,afterResourceNo,limit)，limit为1–200。MONEY必须显式三位大写币种，其他轴拒绝currency。bizDate仅标注批次日期，不承诺历史日终重建。

RIGHTS核对实例与流水；HOLD核对实例总预占、实际HELD分配和服务日bucket（按instance分页，包含该实例全部bucket）；ISSUANCE按grant分页核对COMMITTED数量及实例归属，未提交grant不得已有实例；MONEY按同币种账户分页核对凭证、批次、批次分录、HELD分配与管理账分录。每个扫描资源最多一条差异，具体不同字段仅存在内部快照。公开DiffView仅diffNo/reconNo/entityNo/resourceNo/diffKind/status；禁止直接回传内部快照。

每页幂等事务，axis/currency及完整命令进入指纹。RunView补bizDate/marketZone/currency。批次和差异列表按主体隔离、页大小1–100，稳定排序；不存在或其他主体批次返回NOT_FOUND。本地和standalone经同一类型化请求及内部token校验，内部新增check/list/get/differences四条POST，写失败不自动换键重试。检查不修账、不重发权益、不改变原交易。


### N02异常发放自动登记与动作历史（2026-10-19）

真实源决策校验通过后，pay发放writer事务失败时，保留原grant号与原异常，将仍为PREPARED的grant幂等登记为GRANT_DELIVERY。登记在原发放事务回滚之后执行；登记失败不能替换原发放错误。来源伪造、权限拒绝、prepare失败及UNKNOWN/PENDING不据此登记为已确认发放故障。

新增GET `/ops/pay/entitlement-exceptions/{exceptionNo}/actions`，query=entityNo,page,size，权限沿用ops:pay:ent:read；先查同主体异常再读其动作，稳定按id倒序。ActionView字段仅actionNo/exceptionNo/operationKind/status/actorNo/reason/createdAt/updatedAt，不返回requestKey/payloadHash/responseSnapshot。同主体列表仍显式要求entityNo，跨主体聚合查询继续挂账，不借全局列表放宽恢复操作权限。standalone新增POST内部actions端点，沿用token校验与本地/远程AppService适配。


### N03冻结治理细化契约（2026-10-19）

O07沿POST `/ops/pay/entitlements/{instanceNo}/freeze`，query显式entityNo，header=Idempotency-Key，body=FreezeCommand(expectedAssetRevision,action=FREEZE/UNFREEZE,reason)。权限ops:pay:ent:freeze单独登记，默认只给SUPER_ADMIN，不随read/verify自动获得。

原草案响应EntitlementDetail缺少下一次治理所需资产revision；本次首次实现细化为FreezeView(instanceNo,status,revision,remainingUnits,reservedUnits)，仅运营端使用。冻结允许ACTIVE/PENDING_ACTIVATION/EXHAUSTED/EXPIRED，拒绝REVOKED或已经FROZEN的新请求；解冻仅FROZEN。解冻按真实有效期、激活事实及剩余量恢复EXPIRED/EXHAUSTED/PENDING_ACTIVATION/ACTIVE，不延长有效期、不恢复额度。原幂等键始终重放原响应，不返回变化后的新余额。

冻结不修改余额/预占/bucket，不撤销原已承诺交易；新核销被状态校验拒绝，原HELD仍可按真实源确认或释放，提交后仍保持FROZEN。以pay_ent_command_dedup保存含reason/action/beforeStatus/expectedRevision及FreezeView的内部审计响应，actor/time用既有审计字段，公开只返回FreezeView；ent_ledger写零额度变化的FREEZE/UNFREEZE记录并递增asset_seq。本地/Remote AppService与类型化内部POST freeze端点行为一致。

N03补充GET lifecycle及内部POST state：运营首次操作前只读取得当前版本；该查询不自动解冻/激活/过期，不改变资产。V457登记冻结权限及超管动作，测试schema生成后仍254表、2374种子。


### N04/N05正式储值预置发布接线（2026-10-19）

AP008/AP024/AP030/AP034/AP037复用同一已实现的RECHARGE/NONE/tiers执行器，不开放其余预置；仍检查CNY、商家真实准入、主体/门店、规则、预算及预置enabled。新增StoredValueCapabilityPort.status(tenantNo)，Status(tenantNo,creditReady,redemptionReady)仅内部使用；embedded读pay owner，standalone经token保护的POST `/internal/pay/stored-value/capabilities`。缺失响应、租户不匹配或远端不可用均不能据此宣称可发布。pay提供到账与核销执行器在其既有开关下的证据，core/app提供已实现的活动版本/参与及会员码能力证据；任何开关都不能替代实际执行器实现或商家准入。

无新公网请求字段或活动库表，不修改种子enabled默认值。能力查询及引用预检在锁活动/幂等命令之前完成，写入时继续复核revision和原草稿。新增真实美业链路验收：正式创建发布AP030→统一会员码办理→商家实收→原号到账→本人授权核销→零余额门店保留及消费明细；不得直接插活动版本或调用测试credit夹具代替此验收。


### N07后台恢复与到期契约（2026-10-19）

新增core只读EntitlementRecoveryScanService.scan(tenantNo,kind,afterId,limit)，kind限定RECHARGE/SERVICE_USE/ORDER_USE/ACTIVITY_BENEFIT，limit1–100，按持久id keyset扫描非终态指针。只返回原资源号/主体/原操作者，不回传命令快照或凭证；无公网路由。app EntitlementRecoveryService逐项调用原协调器，结果区分completed/deferred/failed；等待实收、待支付及未知源保留，不自动收款、退款或TTL取消。成功原协调器持久确认终态；失败保留原记录，扫描游标继续前进并在尾部归零，重启重新扫描仍靠原业务幂等。

新增内部POST `/internal/pay/entitlement/lifecycle/expire`，body=Expire(EntitlementContext,limit)，response=ExpireResult(changed)，内部token必需，MAIN且limit1–100。沿既有expireDue逐实例加锁重核，余额/预占不清空；冻结不被到期批次改写，解冻时仍重算有效期。无外部到期写接口。

EntitlementRecoveryJob及EntitlementExpiryJob复用JobHandler/JobDeclaration/JobSupport及持久ShedLock租约，仅shop.job.enabled=true加载；新cron配置shop.job.entitlement-recovery.cron默认`30 */2 * * * *`，shop.job.entitlement-expiry.cron默认`45 */5 * * * *`。游标只做调度优化，业务完成状态是恢复真源，不以进程内游标作为唯一完成证据。死信治理与提醒通知另归N02/N08后续子项。


### N08异常通知与日志口径（2026-10-19）

新增可加性的schemaVersion=1事件类型ENTITLEMENT_EXCEPTION_OPENED，aggregateType=ENTITLEMENT_EXCEPTION，字段eventNo/exceptionNo/entityNo/kind/bizNo/occurredAt。异常首次成功登记时与pay outbox同事务写入，重放不再发一条新事件。事件不含源命令、付款引用、账户双池或凭证；本地与standalone共用同一codec白名单及桥接校验。

异常提交后发送站内交易待办：B端只给该主体有效店主账号；运营端只给拥有ops:pay:ent:read且数据域允许该主体的在职账号。OpsStaffPort补staffNosWithPermForEntity，复用StaffScopes和实际角色权限，不以角色名猜测或跨主体广播。按事件号+receiverType+receiverNo去重，失败只重试消息，不调用发放/核销/调账。外发SMS/WXSUB/PUSH不在本切片启用。

O06明确沿既有两类日志：当前INAPP消息查GET `/ops/inapp-messages`，权限message:template:read；外部通道记录才查GET `/ops/notify-logs`。站内消息事实本身可查，不向sys_notify_log重复写一条伪外发成功。B/OPS待办正文只提示核实原单，未交付页面不拼造跳转URL。


### N08经营效果分量纲投影（2026-10-19）

C10继续复用ActivityMarketingSummaryService，Metric细化为effectKind,currency?,amountMinor?,eventCount,units?；旧ORDER_DISCOUNT四字段构造保留兼容。RECHARGE记录实际实收事件，CREDITED记录到账总额，CONSUMED记录储值消费，COUPON_ISSUED/RIGHTS_USED记录数量，COUPON_DISCOUNT/COUPON_RESTORED记录优惠兑现/受限回补。不同阶段不相加为收益，数量不冒充金额；同一消费跨多个活动分配时，金额按真实批次贡献归因，商家汇总eventCount按源事件去重。

新增内部EntitlementMarketingFactsPort.usage(context,entityNo,usageNo,afterActivityNo,limit)，经token保护POST `/internal/pay/entitlement/marketing/usage-effects`。limit1–100，UsageEffects返回租户/使用号/用户/主体/门店/资产种类及号/币种/原消费金额或次数/发生时间、Attribution(activityNo,amountMinor?,units?)列表和nextCursor。储值按已提交voucher→entry→lot→recharge.activityNo分组，不按最后充值猜测，分币种且全归因金额须等于真实消费；权益按原实例grant冻结的活动permit归因，无活动来源的直接售卡/定向权益不伪造活动归属。不回传本金/赠金、命令、授权、凭证。

app投影订阅者只处理提交后的Received/Credited/Granted/Used/Reversed事实；发放/充值先匹配core原冻结计划，使用先匹配pay原账本安全事实。远程事实查询在core写事务之外，每个活动完整贡献幂等落pmt_effect_projection；重投/乱序不重计、不改财务。统计侧不能重放收款、发放或核销命令。core校验活动主体归属，允许已结束活动与历史门店事实，不用当前活动运行状态改写历史。


### N10精确owner登记基础（2026-10-19）

新增EntitlementOwnerRegistryPort.resolve(context,SourceIdentity(sourceSystem,sourceKind,sourceNo))；系统=MKT_LEGACY/PMT/PAY，类型=TEMPLATE/INSTANCE/USAGE，三者共同定位。缺owner返回NOT_FOUND；不能改查另一来源或回退旧写者。OwnerRef仅返回原身份、目标号、ownerSystem、迁移状态、批次、水位和版本；这是路由元数据，不是写入租约或并发切换授权。

迁移域EntitlementOwnerRegistryService.registerLegacy仅受信平台scope可调用，注册原CORE_LEGACY/PREPARING，不创建目标、不发券、不改旧券；同源同批幂等、不同批冲突，不允许以此把PAY来源改成旧owner。该写方法没有公网或内部HTTP入口，后续离线迁移工具调用。只读resolve新增token保护POST `/internal/pay/entitlement/owners/resolve`及本地/Remote适配。PAY owner解析时还检查目标实际存在（TEMPLATE→ent_template，INSTANCE→ent_instance，USAGE→ent_usage），缺目标拒绝，不回退旧系统。

本切片不启用旧券路由切换，不把一次owner读当作跨库写锁。N10后续兼容与离线迁移必须完成冻结/排空/水位核对及原子导入/切owner，禁止在旧写者仍可变更的情况下仅凭登记翻转owner；本计划不执行生产切换。

### 付费卡能力诊断补充（2026-10-19）

新增token保护只读POST `/internal/pay/entitlement/capabilities`，入参`{tenantNo}`，响应`Status(tenantNo,catalogReady,issueReady,useReady,paidCardReady)`。独立开关及MAIN租户共同约束结果；远程适配拒绝空响应或不同租户，不以目录行宣称能力就绪。此接口没有发卡/扣次或开关写入效果。

6类EP属于PAID_PRODUCT目录，不得作为ACTIVITY发布。共享能力词汇补`CARD_PRODUCT/PAID_ORDER/IDEMPOTENT_CARD_ISSUE/ENTITLEMENT_LEDGER/ATOMIC_USE_DEDUCT/VALIDITY_POLICY`；core销售开关、消费者识别/授权与pay发行/扣次必须共同满足，目录才显示可执行。46项默认禁用种子不变；逐项结果见[预置能力验收矩阵](./design/活动权益-预置能力验收矩阵.md)。

### 到期提醒内部读取（2026-10-19）

新增token保护POST `/internal/pay/entitlement/lifecycle/expiring-soon`，入参`Reminders(context,afterId,limit)`，afterId≥0、1≤limit≤100。仅MAIN；时间由pay取UTC当前时刻，固定读取未来24小时内截止的ACTIVE实例，过滤尚未生效/已过期/冻结/次数全耗尽或已全预占。未首用激活且没有真实有效期的实例不返回。日限模型可返回，无总余次不会被当作耗尽。

响应`ReminderPage(records,nextId)`，条目仅`instanceNo/userNo/effectiveUntil`，nextId=0表示本轮结束；这是受信任务内部最小字段，不是C端公开资产接口。core任务先完成pay查询，再写站内消息，二者不在同一事务。稳定消息键包含租户、实例及准确截止时间，重复扫描/重启不重复提示；单行消息失败不阻断后续行，循环扫描重新尝试，不调用发行/核销/到期命令。外部通知渠道不开启。

### 运营异常聚合读取（2026-10-19）

O01的entityNo改为可选筛选。服务端从已登录MAIN运营会话及既有读权限构造OperatorEntityScope：ALL映射entityNos=null，MERCHANT规则合并成明确集合，空集合代表没有任何主体权限；社区/站点范围不隐含商家权限。最多1000个明确商家引用，不能静默截断或在空集合时取消SQL过滤；超限可选择一个已授权主体，或维护合适的会话范围。请求体/查询参数不能声明ALL或传allowedEntities。

pay在计数与分页之前应用租户与允许商家集合，跨主体列表只返回原脱敏ExceptionView。详情、动作历史、查源和恢复接口继续要求entityNo，并按原范围校验。新增内部token保护POST `/internal/pay/entitlement/exceptions/list-scoped`（ListAcross(scope,kind,status,page,size)），旧单主体内部list保留并共用同一查询实现；此扩展不增加恢复权限、不修改任何资产。

### 旧券迁移源预检（本地工具边界，2026-10-19）

core `LegacyCouponMigrationPreflightService.scan(context,source,afterId,limit)`仅供受信离线工具调用，无公网或内部HTTP入口；仅MAIN、MKT_LEGACY/PMT，主键游标分页1–100项，页内一致快照不等于冻结水位。Report输出最小核对事实及blockers，不返回旧券码明文/码摘要，不执行导入或切owner。所有结果保留SOURCE_FENCE_REQUIRED与LEGACY_WRITE_ADAPTER_REQUIRED，禁止把一次预检当作可切换许可。

V232历史回填导致同号mkt/pmt行可能代表一份权利；遇到重叠显式CROSS_SOURCE_NUMBER，不能凭来源不同自动增加两份实例。pmt使用实际用户券expireAt转换为半开截止，核销历史只计未回补pmt_apply COUPON行；历史与timesUsed不符需核对，不编造使用记录补齐。平台承担、旧订单和静态券码需有相应兼容适配后才可迁移。

### Java后端专项契约生成（2026-10-19）

新增[权益后端OpenAPI 3.1](../api/entitlement-backend.openapi.json)与[共享TypeScript类型](../../packages/shared/src/generated/entitlement-backend.ts)，覆盖本表56条已核对公开路由、103个按请求/响应方向区分的DTO schema。已有三端UI契约不被覆盖，内部RPC与旧活动model!=unified不在本专项spec中；统一活动入口显式要求model=unified。

生成源为真实Spring Handler和实际Jackson DTO视图：保留请求头、查询默认值、路径参数、分页泛型、枚举、空值、字段重命名和忽略字段，响应使用真实`{code,msg,data}`包裹。动态JsonNode/任意JSON为unknown，不编造结构。schema包含可获取的Bean Validation，不能代替构造器校验、主体/门店权限、资金范围与上下文规则；缺省/必填仍以实际端点与业务契约为准。LocalDateTime不包含偏移，具体时区沿对应接口规则，不对既有日志时间作全局UTC假设。

执行`JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH node scripts/gen-entitlement-backend-contracts.mjs`会先编译并运行专项Handler检查后生成；加`--check`核对仓库生成物。`--from-spec`仅供已准备target输出后的快速重建，不代替Java验证。Node守卫拒绝悬空DTO引用、路径参数漂移、重复operationId或接口清单明显缺失；输出按确定次序排序，不含生成时间噪音。类型文件只提供后端契约，不自动改客户端调用、页面或运营菜单。

### 旧券源栅栏工具接口（本地，2026-10-19）

LegacyCouponSourceFenceService仅供受信离线工具：transition(context,TEMPLATE来源,Command(action,batchNo,expectedRevision,reason,requestKey))、state，以及原写服务内部requireWritable。仅MAIN/MKT_LEGACY或PMT模板，迁移准备仅商家承担。Action只有BEGIN_DRAIN/FREEZE/ABORT；无COMMIT/翻owner入口，无公网或内部HTTP暴露。普通OPEN旧写不依赖迁移密钥。

FenceView返回sourceSystem/templateNo/entityNo/batchNo/state/watermark/frozenRevision/revision。冻结在根锁内核对当前可见实例、pmt_apply历史及适用范围；未知原订单、未排空支付或非终态子单均阻止冻结。单模板上限5000实例、5000范围、100000历史行，超限拒绝、不截断；不是所有旧券已可导入的声明。

水位在LEGACY_SOURCE_V1域下流式HMAC-SHA256处理，不返回旧码或其可枚举裸摘要。配置shop.core.entitlement.migration-watermark-key（环境变量SHOP_CORE_ENTITLEMENT_MIGRATION_WATERMARK_KEY）至少32字符，默认空禁止新冻结；批次结束前保留同一密钥。ABORT在提交前恢复旧写；COMMIT_DECIDED仅预留状态，本切片无对应写方法。

原领取、定向发放、规则/预算/状态修改、活动同步、markUsed、到店核销、退券及历史回补使用同一根锁。旧4参issue有显式事务入口；回补按原订单条件写，即使用户券orderNo已变，历史apply回补仍须锁根。

## 消息死信恢复闭环（2026-10-19，N02/N07/N08）

O03b对OUTBOX_DELIVERY异常执行RETRY_EVENT，命令仍为expectedExceptionRevision/reason，加原Idempotency-Key；实体、租户、操作人取可信运营范围。只将原DEAD事件改回PENDING并重置本轮尝试次数，保留eventNo、类型、载荷、hash及财务事实。同步响应PROCESSING表示已排队；桥接真实成功并将原事件落为SENT后才RESOLVED。O04对此类异常改查原事件，动作记QUERY_EVENT，不查grant来源。O03拒绝该类型，避免把消息恢复当成重新发放。

O01/O02/O02a复用现有主体数据域查询。Evidence增加可空delivery={eventNo,eventType,status,attempts}，原grant证据字段仍兼容。O08为GET /ops/pay/entitlement-dead-letters，cursor可选、limit默认20且最多100，返回安全DeadLetterPage(records,nextCursor)，nextCursor=null为结束。游标沿现有EntitlementCursorCodec签名并绑定租户、操作人和固定DEAD筛选，不能跨操作人或篡改重放；沿既有shop.pay.entitlement.cursor-key稳定密钥配置，不向公开query/响应暴露物理自增ID。需ENT_EXCEPTION_READ且可信会话实际为ALL，有限MERCHANT/空范围拒绝；因为损坏载荷不能可信推断entity。仅返回事件号、类型、schemaVersion、aggregateNo、尝试数和时间，不返回payload、hash、凭证或用户信息。损坏事件不开放自动修改载荷/重投。

新增内部令牌POST /internal/pay/entitlement/exceptions/retry-event及dead-letters，本地/远程应用实现语义相同；query-source沿原路径分派。公开路由58、pay内部Handler69、core反向Handler10；专项OpenAPI/TypeScript更新为58操作、106 DTO schemas，旧UI契约保持独立。

## I-MIG-01冻结源证据读取（2026-10-19）

所有者core；内部POST /internal/core/entitlement/migration/frozen-snapshot，共享CoreEntitlementInternalApi.frozenLegacySnapshot。请求FrozenSnapshotQuery={context,query}，query={source:{sourceSystem,sourceKind:TEMPLATE,sourceNo},batchNo,frozenRevision,watermark}；sourceSystem仅MKT_LEGACY/PMT。内部令牌先验证，错误/缺失令牌不能执行源读取。

响应Snapshot={schemaVersion:1,source,entityNo,batchNo,frozenRevision,watermark,contentHash,payload}。watermark为当前源根已冻结且现场重新验证的HMAC-SHA256；contentHash为payload原始UTF-8字节的SHA-256。payload为LEGACY_SOURCE_V1的7槽JSON数组：域、精确来源、批次、原模板定义、可见实例数组、关联历史数组、范围数组；不把core实体类泄漏为pay的Java依赖。只在受认证内部迁移协议内传递，包含旧码，禁止出现在C/B/运营公开响应和日志。

缺少密钥/能力返回不可用；源不存在404；非FROZEN、已ABORT、版本/水位/事实不匹配409。导出上限8MiB，固定模板单元；无自动拆分或静默截断。响应不是导入授权，后续暂存/校验/源提交决定仍需单独协议。公开专项接口仍58操作/106 DTO，pay内部69 Handler，core反向增加到11 Handler。

## 内部Java盘点契约补充（S03，2026-10-19）

core LegacyCouponMigrationPreflightService.inventory(context,sourceSystem,afterId,limit)，仅MAIN/MKT_LEGACY/PMT，单页最多100模板。返回InventoryReport(capturedAt,records,nextId,orphanInstances,orphanIssues,orphanHistory)，该游标只用于内部源盘点，不新增公开HTTP。TemplateInventory包含原模板业务号/主体、删除/归档/迁移状态、全量及已删除实例/发行/历史计数、同号来源提示和阻塞原因；不返回旧码或金额明细。

MKT historyCount/deletedHistoryCount/orphanHistory为null，表达源表没有可证实的独立历史；不能把空值推断为0次消费。PMT orphanHistory涵盖缺实例和缺原模板两种情况。所有计数不排除墓碑；可见实例scan和全量计数inventory不可混作同一口径。目标暂存、规范化及提交协议仍未由本次只读方法实现。

## I-MIG-02–04证据暂存接口（2026-10-19）

owner为pay；沿现有内部令牌及统一响应，不新增公开运营/C/B入口。

| 内部POST | 请求 | 响应 | 业务边界 |
|---|---|---|---|
| /internal/pay/entitlement/migration/stage | PayMigrationRequests.Stage(scope,StageCommand(query,reason),requestKey) | StageView | scope必须MAIN平台Actor且主体与真实源一致；同键异参及同批换键冲突 |
| /internal/pay/entitlement/migration/stage/get | Get(scope,stageNo) | StageView | 只按tenant+entity+业务号读取安全元数据 |
| /internal/pay/entitlement/migration/stage/verify | Get(scope,stageNo) | Verification | 校验原密文/审计绑定，再现场读真实源；不是资产导入操作 |

query沿I-MIG-01原source/batchNo/frozenRevision/watermark。reason非空最多500，原键最多64。StageView包含stageNo、来源系统/模板、主体/批次、STAGED、sourceSchemaVersion、frozenRevision、字节/实例/历史计数、sourceStateAtCapture=FROZEN、createdAt/revision和importReady=false；既有证据重放不宣称源当前仍FROZEN。Verification只有stageNo、SOURCE_MATCHED、importReady=false；真实源ABORT/不匹配/不可达或密文不合法拒绝，不能推定接管成功。DTO亦拒绝把STAGED结果标为importReady。

没有payload、密文、密钥、旧码、消费者身份或付款事实直传响应。embedded用EntitlementMigrationAppServiceImpl委派，standalone用RemoteEntitlementMigrationAppService经PayInternalApi；源读取继续由pay→core反向Port承载。公开58操作/106 DTO保持不变，pay内部Handler增为72，core反向11。密钥配置SHOP_PAY_ENTITLEMENT_MIGRATION_SNAPSHOT_KEY/SHOP_PAY_ENTITLEMENT_MIGRATION_SNAPSHOT_KEY_ID默认空，元数据原键重放不依赖重新取得当前密钥或源冻结状态。

## I-MIG-05/06源家族清单与暂存（2026-10-19）

core新增POST /internal/core/entitlement/migration/frozen-manifest，沿FrozenSnapshotQuery及原内部令牌；由LegacyCouponMigrationManifestPort.exportManifest读取。响应仍为schemaVersion=1的Snapshot信封，payload为manifestVersion=2对象，包含source/batchNo、完整template、instances/issues/applications/scopes数组、historyAvailable和fenceWatermarkKind=VISIBLE_V1。原始删除标记/审计/业务号均保留。8MiB和模板单元上限仍生效，超限拒绝，不截断。MKT的应用数组为空且historyAvailable=false，原已用实例记录仍保留；不能伪造金额或历史。

pay新增POST /internal/pay/entitlement/migration/stage-manifest，请求仍为PayMigrationRequests.Stage；返回ManifestStageView(evidence:StageView,manifestVersion:2,historyAvailable)。原stage/get/verify路径保持，旧stage不自动升级；同键改用另一清单模式冲突。generic get返回原安全证据元数据，verify依据持久manifest版本选择原来源接口并现场比较完整字节/hash。旧服务不认识新路径时失败关闭，不能退回V1并当成完整清单。

app门面增加stageManifest，embedded/standalone分别委派真实owner或类型化RPC。完整清单及SOURCE_MATCHED仍importReady=false；没有公开运营切换/导入接口。当前公开58操作/106 DTO、pay内部73 Handler、core反向12 Handler。

### S03旧权益只读规范化评估（2026-10-20）

内部POST /internal/pay/entitlement/migration/stage/assess，使用PayMigrationRequests.Get(scope,stageNo)。仅内部令牌认证、MAIN及平台操作人，主体必须与暂存记录一致；不开放公网运营写接口。embedded与standalone均经EntitlementMigrationAppService.assess调用。

请求只接受已存在manifestVersion=2暂存。先拒绝外部事务，再验证AES-GCM AAD、明文hash/字节数和当前core完整冻结源；源终止、删除事实变化、新增跨系统重叠或市场事实变化均拒绝旧证据。随后在pay本地读取MarketPort主数据，不在core源根锁内请求pay。

返回Report(stageNo,status,importReady=false,definition,instances,notices,blockers)。status仅NORMALIZED/BLOCKED；NORMALIZED表示规则可表达，不表示已导入或可以接管。instances含sourceInstanceNo、archivalOnly、sourceStatus、remainingUnits、effectiveFrom、effectiveUntil、historicalDefinition，不含消费者号、旧核销码或原始快照。字段异常不会自动补值修复。MKT缺失消费历史明确SOURCE_HISTORY_UNAVAILABLE，缺市场不默认CN/CNY，同号跨系统明确SOURCE_NUMBER_COLLISION。

原始配置与实际行为差异由notices报告：PMT旧订单核销未执行商品范围和最低件数，因此目标有效订单规则scope=ALL、minQty=null，完整旧配置仍留加密证据。STORE_CODE或不支持的优惠、暂停/结束模板、次数/历史不符、无有限原始期限等由blockers明确隔离，不生成新资产、账本或通知。

S03 Report增加issuance汇总：sourceIssuedCount、observedInstanceCount、limitCount、perUserLimit、maxExposureMinor、lifetimeIssuedExposureMinor、activeRemainingExposureMinor、archivalInstanceCount、recipientCount和blockers；不返回逐人配额明细。所有额度/预算/逐人上限冲突并入顶层BLOCKED。lifetimeIssuedExposureMinor是终身发行承诺（原最大优惠×每张总次数×发行数），不是消费者历史已消费金额。两套源总量的“无限”分别是MKT=0、PMT=NULL，PMT=0严格保留为零；预算0/已知NULL为不限，缺失或非法数字另行阻断。删除/过期不返还历史发行额度；当前剩余活跃敞口与终身承诺分列。重复使用的旧PMT预算只按单次优惠计，增加LEGACY_BUDGET_EXCLUDES_REPEAT_USES提醒，禁止自动扩大原预算以通过评估。

### 双源候选比较与认领计划（2026-10-20）

增加内部POST /internal/pay/entitlement/migration/alias/compare、/alias/recognize、/alias/get。仅MAIN平台scope与服务令牌，两个stage必须同主体、同原批次、同模板号，且真实来源分别PMT与MKT_LEGACY。compare返回CANDIDATE_ALIAS/DIFFERENT、差异码及整体摘要，不含消费者身份或原码。recognize要求原stage版本、原因和幂等键，固定PMT为canonical；不是通用合并/覆盖接口，不接受客户端传比较结果、原明文或计数修复值。

返回认领元数据含两个stage/hash、比较摘要、原批次/主体、匹配数与comparedAt，importReady始终false。get和原键重放只是历史认领事实；后续assess仍须重验双方现况。主源通过已认领且现场一致的别名上下文解除“已证明的这一对”碰撞，影子明确ALIAS_SHADOW_SOURCE并保持BLOCKED，防止两次导入。任何实质差异、已有使用历史或额外来源碰撞都不能靠recognize覆盖。

上述三条alias内部工具已接线到pay-svc及embedded/standalone app。已有source-evidence接口统一由LegacyMigrationEvidenceReader读取。recognize仅保存原次声明；原键重放在源已ABORT时仍可返回历史元数据，不能当成当前授权。assess主来源必须重新验证双方hash及按原comparedAt生成的比较摘要，影子即使一致也返回ALIAS_SHADOW_SOURCE；当前时刻的使用期限/剩余敞口另外实时评估。现金封顶字段不生效、空/零门槛等价；两者原始配置继续留在加密源。未新增公网入口或权限码。

N10新增LegacyCouponIdentityPort.ownedOrigins仅为共享SPI，未注册新HTTP。参数为内部context、已鉴权holder、旧实例号，返回Origin列表（source/template/entity/funder/rootState/batch/未来cutoverProofHash/rootPresent/deleted/entityConsistent），不包含holder或旧码。LegacyCouponSourceCommitPort.decided目前只冻结共享只读契约：CommitProof(template,batch,decisionNo,planHash,decidedAt)，没有提交命令实现。普通源OPEN/ABORT仍归CORE，COMMIT无该真实决定不得尝试PAY；Native PAY来源必须精确登记，不依赖ID前缀。只读route不是金融授权。

### 旧ORDER券准备协议记录（2026-10-20）

已新增共享LegacyCouponCheckoutSourcePort（resolve/decision/reversal）及LegacyCouponUsePort（quote/prepare/commit/release/reverse/query）类型，尚无对应HTTP或源生产者/金融实现。CheckoutFact冻结主体/持有人、原来源/目标/owner计划、post-activity多门店组、经济摘要、选择actor及真实时间；terminal Decision和Reversal独立读取，Optional.empty代表未决定，不等于取消。

MerchantGroup以原groupKey和真实可选storeNo描述，不伪造多店门店号。Legacy Quote/Operation只表达ORDER券优惠金额，不能当作实际消费付款或储值借记；RELEASED且从未占用时金额NULL/无分摊。所有有优惠分摊的状态严格金额守恒。仅供后续旧接口协调器内部调用，新储值/付费卡不接此协议。

### 旧ORDER券pay金融内核进展（2026-10-20）

LegacyCouponUseService/Writer现已实现quote/prepare/commit/release/reverse/query领域方法；仍未注册新HTTP或接旧订单入口。CheckoutFact增加明确IntentKind=QUOTE_ONLY/CHECKOUT且protocolVersion=1，只读报价意图可报价但绝不能预占。未作普通会员识别/新卡储值授权入口。

新预占须use.enabled及legacy-coupon.enabled同时开启，后者默认false（环境变量SHOP_PAY_ENTITLEMENT_LEGACY_COUPON_ENABLED）；真实Source与精确PAY owner/主体/持有人/规则匹配后才写原份数。已有操作的重放与承诺完成不受开关关闭/冻结影响；源决定缺失不是取消，金额/原号/快照不一致则拒绝。只针对MAIN/CNY及支持的ORDER券，quote优惠金额不是订单付款金额。

HOLD对账现合并ent_legacy_coupon_operation.HELD+ever_held=1，不将合法新预占误报为差异。取消未占用时保留NULL金额、空分摊且无份数分录。Root真实源/通知/消费历史与全部旧DTO/URL仍待后续接线，不能因为领域接口已实现而开放迁入或切owner。

### N10旧ORDER券内部结算协议

pay新增`/internal/pay/entitlement/legacy-coupons/{quote,prepare,commit,release,reverse,query}`六个令牌鉴权POST，使用共享LegacyCouponUsePort命令和返回类型；embedded直接同域委派，standalone通过PayInternalApi。quote/prepare绑定checkoutNo/operationNo/userNo/entityNo，完成命令还必须绑定真实decisionNo。QUOTE_ONLY不可预占，HELD才占次数，COMMITTED才扣次数；ABORT且未hold没有金额与流水，不能伪造0元扣减。

core新增`/internal/core/entitlement/legacy-checkout/{source,decision,reversal}`三个令牌鉴权POST，只读core日记。查无意图404；未决定为200且`decision:null`或`reversal:null`；服务不可用503；缺少/错误内部令牌401且不调用来源。决策空信封与空传输响应严格区分。公开专项接口仍58操作/106 DTO；pay内部83 Handler、core反向15 Handler。现有URL/DTO写入兼容仍需原交易入口联调后验收。

### N10纯估算及旧券包快照

内部pay新增`legacy-coupons/estimate`及`legacy-coupons/snapshot`。estimate没有checkout/op/经济摘要/核销凭证，不建日记、不预占；snapshot按source/target/batch/proof及买家/主体核验，在pay单一RR快照返回总余次与预占，关闭新使用后仍可读取。两者只有服务令牌入口，旧C端响应形状不变；当前pay内部85、core反向15、公开专项58操作/106 DTO。

`/mp/my-coupons`和`/mp/coupon/mine`通过owner投影读取实际pay数量/状态；ORDER的redeemCode为空。`/mp/coupon/best`改用同一allocate真源，不再按冻结旧表推算已用完资产的优惠。无币款收退、principal/bonus字段或新在线退款入口。源读取SPI由独立Core Port适配Journal，业务写接口不再兼任可替换读Port。

### 消费全历史查询增量（2026-10-20）

P25a 在详情 recentUsage（最近 20 条）之外提供完整分页。消费者只传权益号和可选主体，不接受 userNo；网关从会话构造本人。支付先验证权益归属，再按 tenant/user/entity/instance 查询真实 STANDARD 或 LEGACY 核销。以 occurredAt/usageNo 倒序做 keyset，返回 hasMore/nextCursor，签名游标绑定消费者、主体、权益及 RIGHTS_USAGE 查询类型，禁止跨查询复用。只读查询不激活、不核销、不重发通知。

独立部署使用 `/internal/pay/entitlement/query/history`，请求为 UsageHistory(context,query)，返回同一 CursorPage<UsageItem>；需内部 token。支付内部接口由 85 增为 86，核心反向接口仍 15。公开契约在本批真实映射验证后重新生成。

### 固定目标计划内部 API（2026-10-20）

| 方法 | 支付内部路径 | 请求 | 返回 |
|---|---|---|---|
| POST | /internal/pay/entitlement/migration/plan | PayMigrationRequests.Plan(scope,PlanCommand(stageNo,reason),requestKey) | PlanView |
| POST | /internal/pay/entitlement/migration/plan/get | PlanGet(scope,planNo) | PlanView |
| POST | /internal/pay/entitlement/migration/plan/verify | PlanGet(scope,planNo) | PlanView |

全部要求内部服务 token、MAIN、平台操作人及主体范围；无公开消费者入口。PlanView 仅返回 plan/stage/entity/batch、PLANNED、目标及源bundle摘要、计数/评估时刻/版本和 importReady=false；不返回身份、密文、目标明细或导入许可。同键同操作人同参重放；异参或跨操作人冲突，跨主体不可见。verify 重新验证密文、全部源证据及同号认领，不改变计划。已有历史元数据重放不等于新的源验证。

本批22模块编译、43项定向测试通过（含真实typed HTTP及三条接口错误token、本地/远程唯一装配、真实冻结来源计划）。支付内部接口89、核心反向15、公开59操作/106 DTO。源端提交和目标原子导入仍未暴露为可用能力。

### 源证明与支付基线迁入内部接口（2026-10-20）

| 方法 | 路径 | 请求 | 返回 |
|---|---|---|---|
| POST | /internal/pay/entitlement/migration/plan/prepared | PlanGet(scope,planNo) | LegacyCouponMigrationPlan 固定非敏感计划头 |
| POST | /internal/core/entitlement/migration/source-commit | MigrationCommitQuery(context,template) | MigrationCommitReply(proof?) |
| POST | /internal/pay/entitlement/migration/import | Import(scope,ImportCommand(planNo,reason),requestKey) | ImportView |
| POST | /internal/pay/entitlement/migration/import/get | PlanGet(scope,planNo) | ImportView |

全部要求内部 token；源证明接口只读，不是触发源提交的入口。proof=null 是明确尚无决定；缺失 transport/envelope 为不可用，不能猜测释放或导入。准备头读取校验加密计划完整性；新的源提交由核心锁内重验原完整快照，不能用只读计划头跳过源栅栏。

ImportView 含 import/plan/entity/sourceDecision、planHash、IMPORTED、资产/归档数量、issuanceEnabledAtImport=false、实际导入时刻；不含用户、旧码、密文或虚构的付款时间。历史PMT来源必须认证完整原证据，原净消耗与真实未退回核销条数一致才可提交及迁入；MKT“无可用历史”保留未知，不宣称历史为0。原消费记录作为MIGRATED展示事实复制，不重造旧金融账。迁入时发行保持暂停，不重复普通grant、不重发到账通知，已持有权益的可用性由使用开关及原期限决定。

支付内部92接口、核心反向16接口；公开59操作/106 DTO不变。22模块编译及43项定向测试通过，真实MySQL并发迁入另行验证；未执行生产切换。

### 迁入前核销的原订单退回协议（2026-10-20）

| 所属服务 | 方法/路径 | 输入 | 输出 |
|---|---|---|---|
| core | POST /internal/core/entitlement/migration/history-return/source | HistoricalReturnQuery(context,returnNo) | 原核销、原实例、迁移决定/batch/planHash、本人/主体/订单、取消或全退款依据、原优惠金额、来源行哈希、决定时间 |
| core | POST /internal/core/entitlement/migration/history-return/ack | HistoricalReturnAck(context,Receipt) | 已确认的同一Receipt |
| pay | POST /internal/pay/entitlement/migration/history-return/reverse | Query(context,entityNo,returnNo) | 唯一退回回执、原核销及新记录/实例号、恢复1次、实际余次、来源哈希和决定时间 |
| pay | POST /internal/pay/entitlement/migration/history-return/query | 同上 | 既有回执；不存在404 |

仅服务令牌可访问，不新增C端现金退款入口。来源端只读取既有决定或确认回执，不能通过该API创建退款决定。支付在事务外取得不可变来源事实，再在本地同事务回补、更新记录及生成回执；核心确认在支付提交后。已完成回执重读不依赖来源当前可用。拒绝跨租户/主体、来源哈希或迁入决定不一致，以及重复不同身份的退回。

原取消入口和AFTER_SALE_REFUNDED事件根据真实订单状态创建core决定，同事务outbox驱动支付；HISTORICAL_COUPON_RETURN有界恢复扫描只读取PENDING原号，不推断超时取消。公开查询P25b继续只展示登录本人的记录，不暴露来源快照或拆分本金/赠金。

### 旧商家券定义兼容接口（N10续）

公开URL和CouponVO/CouponSaveCmd保持原契约，未迁移继续原core实现。已永久迁移按sourceSystem+TEMPLATE+sourceNo、batch、planHash与精确owner核验后，只访问pay。

| 方法 | 内部支付路径 | 输入 | 输出 |
|---|---|---|---|
| POST | /internal/pay/entitlement/legacy-template/get | Get(CatalogScope,Reference) | Snapshot(当前模板、已发布定义、当前发行策略、净使用次数/金额) |
| POST | /internal/pay/entitlement/legacy-template/status | Status(scope,reference,status,requestKey) | 同上，模板状态和发行启停同本地事务 |
| POST | /internal/pay/entitlement/legacy-template/save | Save(scope,reference,DefinitionChange,requestKey) | 同上，新版发布与策略额度同本地事务 |

Reference含明确来源、目标模板号、迁移批次和计划摘要。发行保持关闭时旧DTO显示PAUSED，商家显式恢复才启用。保存只改后续版本；旧实例rule_snapshot、期限和余次不重写。当前兼容保存仍限定已支持的MERCHANT/CNY/ORDER_COUPON/COUNT/ALL/CASH或封顶PERCENT，不将旧页面支持的其他类型猜测转换。降额不得低于已发行/预占基线，失败必须回滚同次发布。以上均为服务令牌接口，不向C端开放模板管理。

### 旧发放与本人领取的支付批次协议

| 所属 | 方法/路径 | 输入 | 输出 |
|---|---|---|---|
| core | POST /internal/core/entitlement/legacy-coupon/audience | LegacyAudience(context, Query) | 已核验永久来源/主体的真实受众、账号资格与不可触达汇总 |
| pay | POST /internal/pay/entitlement/legacy-issue/create | Issue(scope, Command, requestKey) | 原兼容操作、原生batch及实际结果 |
| pay | POST /internal/pay/entitlement/legacy-issue/get | Get(scope, issueNo) | 同主体已接受原号的当前结果 |
| pay | POST /internal/pay/entitlement/legacy-issue/resume | Resume(scope, issueNo, limit 1–1000) | 只续原受众和原grant |
| pay | POST /internal/pay/entitlement/legacy-issue/list | History(scope, sourceSystem, sourceTemplateNo?) | 同主体原模板的实际记录，模板可空用于原历史入口 |

Command包含精确模板Reference、TARGETED/CENTER及人群/标签选择条件，不含客户输入的收件人名单。TARGETED由core会员域解析；CENTER只认context实际领取人，账号必须活跃，版本必须允许CENTER。pay真实个人基线计入已迁实例，不能通过切入口重复领取。

原biz发券与mp领券的URL及已有响应字段保留。新发权益仍是原生pay实例；原表不新增券或增发计数。发行暂停、库存、个人额度与预算均在pay生效；C领券中心读当前pay状态及本人已领计数，不能按冻结core计数展示“未领取”。当前私有pay101/core19，公开专项60操作/106DTO；尚未宣称MKT第三方奖励全部接续。

### 原MKT运营与消费者入口兼容（2026-10-20）

`marketing.coupon.CouponService` 原URL、DTO和运营权限保持，已迁移的MERCHANT券按Core永久来源决定及精确TEMPLATE owner读取pay。Ops列表在当前pay状态/归档投影后筛选，不以冻结旧状态预筛；状态显式恢复发行，保存只改未来发布版本，预算走当前发行策略版本，已得权益规则不变。Ops SINGLE_USER解析实际活动账户，1–1000张按统一原生批次的独立券单元发放，个人限领/库存/预算整批校验；不信任自由文案为受众，不模拟消费者领取。历史记录的名称来自真实发行版本，金额来自实际已发行敞口，核销统计来自当前净金融事实。

新增私有pay `POST /internal/pay/entitlement/legacy-template/archive`，请求`PayLegacyTemplateRequests.Archive(scope,reference,archived,requestKey)`，响应`LegacyCouponTemplateViews.Snapshot`，仅platformActor，要求原精确来源引用。内部密钥与其余内部接口一致；归档时间首次固定，重复归档幂等，恢复清空；不修改原Core根或已得券。当前私有pay102/core19、专项公开60操作/106DTO。

私有旧发行Command补充`userKey/count/targetDesc`：只有平台身份且TARGETED无商家选择器时可指定单用户数量；Core独立解析活动账户并回传原操作人，Pay使用原数量创建独立grant，不接受公开任意收件人列表。C领取保持仅本人、1张。Snapshot补充`archivedAt/usedCouponCount`，净核销张数为真实已使用实例去重，不将多次券的次数冒充张数。

旧Fission/派生COUPON活动仍引用的来源在完整源评估中报告`LEGACY_PROGRAM_CONVERSION_REQUIRED`，先转换到统一活动，再申请切换；完整源决定重验当前引用事实及hash。Fission新建绑定与启用在Core根栅栏下校验，不能把迁移后的券重新绑定旧事务发奖路径。原未迁移/平台券继续原有逻辑；第三方奖励不能伪装CENTER本人领取，也不能在Core持锁事务下远调pay。

## 门店独立储值增量（2026-10-20）

| ID | 方法 | 路径 | query或body | 业务响应 | 权限 | 说明 |
|---|---|---|---|---|---|---|
| P60 | GET | /biz/stored-value/context | storeNo | MarketBrief | pay:sv:plan:read | 商家市场币种/精度/时区；也允许管理/充值/账户权限 |
| P61 | GET | /biz/stored-value/plans | storeNo,status?,cursor?,size | CursorPage<View> | pay:sv:plan:read | 也允许管理或充值权限；仅充值权限只见启用方案 |
| P62 | POST | /biz/stored-value/plans | Create + Idempotency-Key | View | pay:sv:plan:manage | 同店同名唯一；金额冻结 |
| P63 | GET | /biz/stored-value/plans/{planNo} | storeNo | View | pay:sv:plan:read | 也允许管理/充值权限 |
| P64 | PUT | /biz/stored-value/plans/{planNo}/status | Status + Idempotency-Key | View | pay:sv:plan:manage | expectedRevision；不改变经济规则 |
| P65 | POST | /biz/stored-value/plan-recharges | CreateRequest + Idempotency-Key | StoredValueRechargeProgress | pay:sv:recharge:create | customerRef仅识别；不依赖活动 |
| P66 | GET | /biz/stored-value/plan-recharges/{rechargeNo} | 原单号 | StoredValueRechargeProgress | pay:sv:recharge:read | 也允许原办理权限 |
| P67 | POST | /biz/stored-value/plan-recharges/{rechargeNo}/submit | SubmitRequest + Idempotency-Key | StoredValueRechargeProgress | pay:sv:recharge:create | 实收金额/方式/receiptNo，摘要由BFF生成 |
| P68 | POST | /biz/stored-value/plan-recharges/{rechargeNo}/receive | ReceiveRequest + Idempotency-Key | StoredValueRechargeProgress | pay:sv:recharge:receive | 商家核实收款；RECEIVED等待到账 |
| P69 | POST | /biz/stored-value/plan-recharges/{rechargeNo}/cancel | CancelRequest + Idempotency-Key | StoredValueRechargeProgress | pay:sv:recharge:create | 确认收款前可取消 |
| P70 | POST | /biz/stored-value/plan-recharges/resolve | 原CreateRequest + 原Idempotency-Key | ResolveView | pay:sv:recharge:create | 不依赖过期会员引用重新解析 |
| P71 | POST | /biz/stored-value/plan-recharges/cancel-create | 原CreateRequest + 原Idempotency-Key | StoredValueRechargeProgress | pay:sv:recharge:create | 持久取消栅栏，拒绝晚到创建 |
| P72 | GET | /biz/stored-value/account-directory | storeNo,keyword?,cursor?,size | CursorPage<AccountView> | pay:sv:account:read | 仅本店成功充值账户；零余额保留 |
| P73 | GET | /biz/stored-value/accounts/{accountRef} | storeNo | AccountView | pay:sv:account:read | 签名引用绑定操作人/商家/门店，只读 |
| P74 | GET | /biz/stored-value/records | storeNo,type?,startDate?,endDate?,accountRef?,cursor?,size | CursorPage<RecordView> | pay:sv:account:read | ALL/RECHARGE/REDEMPTION；日历日期按商家市场时区换算 |
| P75 | GET | /biz/stored-value/records/{recordNo} | storeNo | RecordView | pay:sv:account:read | 按凭证冻结金额及变动后余额 |

具体字段、错误处理与模块映射见[TDD-门店储值实现与交付](./TDD-门店储值实现与交付.md)。新增接口已接入B端；旧活动/C端/运营的延期条目仍按交付登记管理。


## 会员底部弹框储值增量（2026-10-20）

| 编号 | 方法 | 路径 | 请求 | 响应 | 权限 | 规则 |
|---|---|---|---|---|---|---|
| P80 | GET | /biz/stored-value/customer-candidates | storeNo,phone | PageData<Candidate> | biz:customer pay:sv:recharge:create | 完整手机号，会员可在尚未开户时被找到 |
| P81 | POST | /biz/stored-value/customer-selections | SelectRequest | Selection | biz:customer pay:sv:recharge:create | 会员／账户来源二选一；独立短时选用凭证 |
| P82 | POST | /biz/stored-value/plan-recharges/confirm-offline | CreateRequest + Idempotency-Key | StoredValueRechargeProgress | pay:sv:recharge:create pay:sv:recharge:receive | Pay本地确认、原单重放、MERCHANT_OFFLINE；顾客标识与登录账号分离 |
