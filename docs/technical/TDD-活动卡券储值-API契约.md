# 活动卡券储值模块API契约

状态：目标契约草案，待评审 · 2026-10-18

执行状态与先后顺序见[Java后端独立任务表](./任务表-活动卡券储值-Java后端.md)，已实现目录内核直接复用，其余目标设计不视为已完成。

[系统入口](./TDD-活动卡券储值-模块化系统设计.md) · [功能矩阵](../requirements/活动卡券储值-模块功能矩阵.md) · [数据库](./TDD-活动卡券储值-数据库设计.md)。以下为目标接口，不代表已实现；没有修改运行OpenAPI。沿用接口必须先校验当前DTO兼容，已有URL由原Controller改委派，不能重复注册Handler。

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
| C03 | GET | /biz/activities | scene?,status?,page,size | Page<ActivityView> | biz:campaign | 沿用，扩筛选 |
| C04 | POST | /biz/activities | ActivityDraft | ActivityView | biz:campaign | 沿用保存；只可修改草稿 |
| C05 | POST | /biz/activities/{activityNo}/publish | PublishCommand | ActivityVersionView | biz:campaign | 新增显式发布/规则版本 |
| C06 | PUT | /biz/activities/{activityNo}/status | StatusCommand | ActivityView | biz:campaign | 沿用，暂停/结束不撤销参与 |
| C07 | POST | /biz/card-product-bindings | ProductEntitlementBinding | BindingView | biz:goods | 引用已发布pay模板，商品售价仍由商品API保存 |
| C08 | POST | /biz/member-code/resolve | MemberResolveCommand | CustomerReference | biz:customer | 统一会员域接入契约，若已有等价URL则复用 |
| C09 | POST | /mp/orders/{orderNo}/entitlement-authorizations | AuthorizationCommand | AuthorizationView | 登录本人 | 目标通用本人确认，核对既有order路由后冻结，不增储值码 |
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
| P10 | GET | /mp/entitlements/{instanceNo} | 无 | EntitlementDetail | 登录本人 | 余次/期限/范围，已过期保留 |
| P11 | POST | /mp/entitlement-claims | ClaimCommand | GrantView | 登录本人 | 模板CENTER或活动资格，不任意领付费卡 |
| P12 | POST | /biz/entitlement-uses/preview | UsePreviewCommand | UsePreview | pay:ent:use | 先看后核，检查可用状态 |
| P13 | POST | /biz/entitlement-uses | UseSubmitCommand | OperationView | pay:ent:use | 受控核销，由应用协调源授权，不裸露commit |
| P14 | GET | /biz/entitlement-uses/{operationNo} | 无 | OperationView | pay:ent:read | 未知状态查原操作，不重新扣 |
| P15 | POST | /mp/entitlement-quotes | QuoteRequest | QuoteView | 登录本人 | 可信订单draftRef，只试算 |
| P16 | POST | /mp/entitlements/{instanceNo}/activation-quotes | 无 | ActivationQuote | 登录本人 | 查询起算效果，不任意提前激活 |
| P17 | GET | /mp/entitlements/{instanceNo}/allowances | serviceDate? | AllowanceView | 登录本人 | 指定日剩余/冻结，与总次数区分 |
| P18 | POST | /biz/stored-value/recharges | RechargeCreateCommand | RechargeView | pay:sv:recharge:create | 调用core reserve后创建，金额服务端快照 |
| P19 | POST | /biz/stored-value/recharges/{rechargeNo}/submit | RechargeSubmitCommand | RechargeView | pay:sv:recharge:create | 实收凭据，仅提交待核验 |
| P20 | POST | /biz/stored-value/recharges/{rechargeNo}/receive | ReceiveCommand | RechargeView | pay:sv:recharge:receive | 确认实收，到账可以仍在处理中 |
| P21 | POST | /biz/stored-value/recharges/{rechargeNo}/cancel | CancelCommand | RechargeView | pay:sv:recharge:create | 只未实收取消；驳回用reason同状态守卫 |
| P22 | POST | /biz/stored-value/consumptions | StoredValueConsumeCommand | OperationView | pay:sv:consume:create | 绑定真实服务订单与本人授权 |
| P23 | GET | /mp/stored-value/stores | cursor?,size | CursorPage<StoreItem> | 登录本人 | 有成功储值历史门店 |
| P24 | GET | /mp/stored-value/consumptions | storeNo,cursor?,size | CursorPage<UsageItem> | 登录本人 | 该店本人储值使用历史 |
| P25 | GET | /mp/entitlement-usage/{usageNo} | 无 | UsageDetail | 登录本人 | 本人统一核销详情 |
| P26 | GET | /biz/entitlement-usage | storeNo?,assetKind?,from,to,page,size | Page<UsageItem> | pay:ent:read | 只授权主体门店 |
| P27 | GET | /biz/stored-value/recharges/{rechargeNo} | 无 | RechargeView | pay:sv:recharge:read | 查询入账进度，不能再次确认收款代替查询 |
| P28 | GET | /biz/stored-value/accounts | customerRef,currency,storeNo? | StoredValueBalanceView | pay:sv:account:read | 总余额、总冻结及当前项目可用额，内部双池不返回 |
| P29 | GET | /biz/entitlements/{instanceNo} | customerRef,storeNo | EntitlementDetail | pay:ent:read | 本主体已识别本人指定卡余次，不任意枚举其他用户 |
| P30 | POST | /mp/entitlements/{instanceNo}/redeem-codes | CodeRequest | CodeView | 登录本人 | 仅STORE_EXCHANGE签短期券码，不签储值码 |

## 四、运营接口

| ID | 方法 | 路径 | query或body | 业务响应 | 权限 | 说明 |
|---|---|---|---|---|---|---|
| O01 | GET | /ops/pay/entitlement-exceptions | entityNo?,kind?,status?,page,size | Page<ExceptionView> | ops:pay:ent:read | 支付权威异常 |
| O02 | GET | /ops/pay/entitlement-exceptions/{exceptionNo} | 无 | ExceptionDetail | ops:pay:ent:read | 证据/源单/动作 |
| O03 | POST | /ops/pay/entitlement-exceptions/{exceptionNo}/retry-grant | RecoveryCommand | OperationView | ops:pay:ent:recover | 原grantNo恢复，不重发新权益 |
| O04 | POST | /ops/pay/entitlement-exceptions/{exceptionNo}/query-source | RecoveryCommand | ExceptionDetail | ops:pay:ent:verify | 查事实，不改余次 |
| O05 | GET | /ops/pay/entitlement-reconciliations | entityNo?,bizDate,page,size | Page<ReconView> | ops:pay:ent:read | 按市场日切 |
| O06 | GET | /ops/notify-logs | 现有通知查询参数 | 沿用NotifyLogPage | 现有通知权限 | 复用既有通知中心；具体已有子路由沿原契约 |
| O07 | POST | /ops/pay/entitlements/{instanceNo}/freeze | FreezeCommand | EntitlementDetail | ops:pay:ent:freeze | 异常冻结/解冻需原因及审计；不清余额 |

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
| AuthorizationCommand | orderRevision,quoteRef,selectionDigest；服务端返回本人意图，过期/改价需重确认 |
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

C02/C07统一沿用实际BizPerms.GOODS（biz:goods），不虚设不存在的read/update子权限。其余新增pay权限仍属拟登记清单，必须与真实Handler及角色测试同时交付，不提前给现有角色增权。此调整不增加48条目标外部路由。


## 十一、内部事实查询与传输约定（JB-008设计冻结子项）

以下是待JB-041接线的内部路由，不是已注册Handler，不计入48条外部路由。全部POST、JSON，正文为{context,...args}；context为EntitlementContext，其余属性名与SPI方法参数一致。query/quote/preview虽用POST承载带身份范围的正文，仍只读，不能被通用POST重试器当作写命令。返回直接解SPI record，沿既有/internal路径跳过公网ApiResponse包装。目录既有两个方法不改Java签名，远程适配器先验证调用context，再向本地端口传tenant/entity。

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

网络不可达、超时、5xx、解析失败均为传输失败，不得伪造UNKNOWN或CANCELLED；原操作保持待查询/PROCESSING，由恢复任务查原编号。UNKNOWN仅表示查询成功且没有可见持久事实，PENDING表示已有意图但未决定，两者都不能release。授权EXPIRED不等于源取消，必须先查询源决策。写请求不自动换编号重试，合法重投须同业务编号、原摘要及原幂等键；只读查询可有界重试，所有重试不打印码、token或请求正文。内部裸commit不向公网开放。

传输实际Handler、共享HTTP接口/路径常量及拒绝测试由JB-041交付；本节只冻结路由与语义，不将本地接口声明计作远程可用。


## 十二、能力门禁配置契约（JB-008基础实现）

默认值全部false；PayEntitlementProperties与PromotionFeatureProperties已按下列名称绑定运行配置。已实现的目录写入、新引用委派门禁，其余业务入口尚未实现，随责任任务逐项接线。开关由服务端部署配置控制，HTTP请求或预置模板不能打开。实际available须同时满足开关、owner实现就绪、行业准入、主体状态、门店权限及所需能力集合；缺失项明确返回missingCapabilities，未知能力也按缺失处理。不能以类存在、目录seed存在或单体能编译作为READY证据。JB-032实现目录筛选，JB-053完成权限及配置拒绝验证。

| 配置名 | 控制的新业务 | 实施任务 |
|---|---|---|
| shop.promotion.presets.enabled | 新预置目录推荐/创建草稿 | JB-032/JB-033 |
| shop.promotion.activity.enabled | 新活动发布/参与预留 | JB-029/JB-031/JB-033 |
| shop.pay.entitlement.catalog.enabled | 新权益定义写入/发布/引用 | JB-025 |
| shop.pay.entitlement.issue.enabled | 新领券、批量发行与发放prepare | JB-016/JB-017/JB-018/JB-026 |
| shop.pay.entitlement.use.enabled | 新券/兑换权益使用意图 | JB-020/JB-026 |
| shop.pay.stored-value.enabled | 新储值办理及核销意图 | JB-034–038 |
| shop.pay.paid-card.enabled | 新付费卡商品绑定/销售；已有卡的使用另受use控制 | JB-039/JB-040/JB-042 |

开关关闭不阻断已存在资产的本人历史查询、已承诺源决策的commit/release、合法原券回补或恢复任务；这些操作仍按原事实及状态机执行，不能形成有扣款无权益。开关不能切换owner或回写旧表。付费卡同时要求权益发行/使用开关和商品、收款、履约、条款验收，不因打开paid-card就跳过底座。运行的本地/远程选择沿既有shop.pay.deployment=embedded/standalone，不能再造第三套部署模式。

预置JSON当前实际引用35个不同能力码；下表固定其归属与验收落点。有关拼团/秒杀/赠品完整履约的额外引擎不因列入目录自动列为本批已交付，未实现时一律保持不可用。

| 能力码 | owner与验收落点 |
|---|---|
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

权限码以本文件C01–C11/P01–P30/O01–O07表逐条冻结：新增pay/ops:pay码仅随真实Handler登记，JB-025/026/027/033/038等负责门面，JB-053核对角色与运营五处登记；当前不向Perms及角色表写死权限，避免给没有实现的路由授权。公共DTO不接受tenant/actor/entity/platformActor作为本人身份；service token只证明服务调用方，不能替代业务范围与持久授权。

JB-008是协议/配置门禁基础交付。34条内部传输Handler/代理由JB-041实现，48条外部Handler由各业务任务实现；5张支撑设计表的正式迁移/Mapper由JB-011/JB-012/JB-046实现。不能把这些后续运行实现反向作为008的完成条件形成依赖循环，也不能因008完成而宣称它们已可用。JB-010补充统一会员码签发与解析；不能拿登录token或客户端用户号充当会员码。


## JB-010统一会员凭证契约

C11由core.member签发，tenant/user仅来自可信C会话；响应`{memberCode,expiresAt}`，expiresAt为ISO UTC。码为mc1_前缀的256位随机值，有效60秒。C08沿用biz:customer，输入`{memberCode,storeNo}`，tenant/entity/actor来自商家会话，并验证店员门店子集；响应`{customerRef,maskedName,expiresAt}`。引用有效120秒，绑定租户、主体、门店、操作人和顾客，顾客/商家/门店每次重验有效性，不返回手机或用户号。

码和引用仅保存SHA-256摘要，禁止请求体/凭证原文日志；DTO toString脱敏，不走URL/query。非法/过期码40041，跨范围或过期引用10404；开关关闭40040。shop.member-code.enabled缺省false，仅控制新签发/扫码，既有引用查询仍按原到期时刻执行。统一码在60秒内可重复识别，扫码不产生财务效果，不替代JB-011本人确认。会员码到期不使已签发引用提前过期；引用到期不得刷新原引用，应重新扫码。重试签发得到新凭证，不缓存秘密原文为幂等响应。

CustomerIdentityPort本地实现归member，向源业务只返回userNo；远程传输路由列入JB-041，当前不创建内部HTTP Handler。C端确认时必须由源业务使用冻结的原B操作人范围查询引用，不能改成C操作人，也不能将操作者直接当顾客。引用用于建立源意图，消费者确认与持久授权依JB-011另行处理。目标48条之外的旧会员接口保持兼容，不新增单独储值核销码。
