# 活动卡券储值模块API契约

状态：目标契约草案，待评审 · 2026-10-18

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
| C02 | GET | /biz/card-product-presets | industryTag? | PresetPage | biz:goods:read | 6产品模板，非已发布商品 |
| C03 | GET | /biz/activities | scene?,status?,page,size | Page<ActivityView> | biz:campaign | 沿用，扩筛选 |
| C04 | POST | /biz/activities | ActivityDraft | ActivityView | biz:campaign | 沿用保存；只可修改草稿 |
| C05 | POST | /biz/activities/{activityNo}/publish | PublishCommand | ActivityVersionView | biz:campaign | 新增显式发布/规则版本 |
| C06 | PUT | /biz/activities/{activityNo}/status | StatusCommand | ActivityView | biz:campaign | 沿用，暂停/结束不撤销参与 |
| C07 | POST | /biz/card-product-bindings | ProductEntitlementBinding | BindingView | biz:goods:update | 引用已发布pay模板，商品售价仍由商品API保存 |
| C08 | POST | /biz/member-code/resolve | MemberResolveCommand | CustomerReference | biz:customer | 统一会员域接入契约，若已有等价URL则复用 |
| C09 | POST | /mp/orders/{orderNo}/entitlement-authorizations | AuthorizationCommand | AuthorizationView | 登录本人 | 目标通用本人确认，核对既有order路由后冻结，不增储值码 |
| C10 | GET | /biz/marketing-summary | storeNo?,from,to | MarketingSummary | biz:campaign | 目标统一看板，迁移已有摘要入口时保留别名 |

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
| TemplateDraft | templateNo?,expectedRevision?,title,kind=ORDER_COUPON/STORE_EXCHANGE/PAID_CARD,benefitMode=CASH/PERCENT/GIFT/SERVICE_UNITS,benefitValue?,benefitCapMinor?,benefitRef?,currency,minAmountMinor?,minQty?,usageModel=COUNT/PERIOD_COUNT/PERIOD_DAILY,totalUnits?,dailyUnits?,validity{mode=ABSOLUTE/RELATIVE,startAt?,endAt?,validDays?,startBasis=GRANTED/PAID/FIRST_USE,periodBasis=ROLLING_DAYS/CALENDAR_MONTH,zone},redemptionScope,allowedIssueModes[],funder；折扣benefitValue为应付比例bp，8500=支付85%，cap为减免上限 |
| IssuePolicyCommand | expectedRevision,limitCount?,maxExposureMinor?,perUserLimit,issueMode,enabled；限额不得小于held+used。maxExposure是授权发行敞口，不虚构现金备付 |
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
| IssuePolicyView | limit/held/issued数量、maxExposure/held/used、mode,revision |
| IssueBatchView | batchNo,status,planned/issued/skipped/failed/pendingCount,skipReasons[{code,count}],itemStatusQueryRef；未完成返回PROCESSING |
| GrantView | grantNo,status,instanceNos[],sourceRef,occurredAt；同源重投返回同一实例集合 |
| EntitlementItem/Detail | instanceNo,title,kind,status,remainingUnits?,dailyRemaining?,effectiveFrom,effectiveUntil,scopeSummary,sourceLabel；Detail加用法和记录入口。COUNT剩余和日额度分开，不传内部双池 |
| UsePreview | previewRef,instanceNo,title,maskedHolder,redeemable,reason,unitsToUse,remainingAfter?,expiresAt |
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
