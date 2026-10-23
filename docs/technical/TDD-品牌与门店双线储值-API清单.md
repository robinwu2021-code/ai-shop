# 品牌与门店双线储值 · API清单与契约设计

状态：评审草案，尚未实现。与34屏V2原型及[数据库设计](TDD-品牌与门店双线储值-数据库设计.md)配套。新接口为建议路径，不代表当前服务器已支持。

## 1. 通用约定

- 统一ApiResponse包装；分页沿用CursorPage(records,nextCursor)，size默认20，最大100，C端最大50。游标签名绑定租户、操作者、权限范围、过滤条件；改筛选重置。
- 租户、actor、岗位和管理空间授权由会话解析。顶层管理API不从X-Store-No推导管理空间；门店API的路径storeNo必须与门店上下文一致。managementNo/brandNo/issuerNo均只是资源选择，不授予权限。
- 金额为64位整数minor，示例金额用数字表达；前端只输入实收/赠送档位，到账、可用、占额及清分服务端计算。比例bps=万分比，20%=2000。加法和乘法用溢出安全算法。
- 所有业务写操作包括DELETE需Idempotency-Key(≤64字符)；同键异参冲突。唯一域至少tenant/actor/operation/resource/key；收款、核销、账单生成另有全局原单业务去重。幂等重放先校验当前读取权限，再返回原结果；已提交交易不能因新的限制重复执行。
- 编辑和状态动作需expectedRevision；首次创建不需要。资源引用短时opaque，绑定当前店/actor/customer/issuer用途；accountRef只读识别，不是扣款授权。切店、切范围、过期必须重新选择。
- 日期YYYY-MM-DD含结束日，按发行方marketZone换算UTC[from,to)，拒绝倒置及与Instant混传。同一发行方首期只配置一种业务币种；品牌门店办理需币种兼容，无汇率换算。
- 查询同品牌会员不等于查询其所有储值账户和他店财务；候选返回脱敏资料，跨店会员名册模糊搜索仅在明确授权范围，不做全平台手机号探测。
- 错误沿用现有ErrorCode，新增businessReason供交互区分；不在设计中猜造数字码。建议reason：SCOPE_MISMATCH、ISSUER_NOT_ACCEPTED、POLICY_LIMIT_EXCEEDED、POLICY_CHANGED、APPROVAL_REQUIRED、ACTIVITY_NOT_ACTIVE、QUOTA_EXCEEDED、PAYMENT_ALREADY_REGISTERED、RECEIPT_ROLE_REQUIRED、LEGACY_MAPPING_REQUIRED。

## 2. 按模块接口清单

状态NEW=新增建议，EXTEND=已存在接口改造，REUSE=沿用现有流程。所有scope限制服务端执行，不能因URI在management下就跨主体放权。

### 管理空间

| 编号 | 方法与路径 | 模块 | 输入 | 输出 | 权限／原型 | 状态 |
|---|---|---|---|---|---|---|
| MG01 | `GET /biz/management/context` | Merchant/BFF | — | ManagementContext | `management.enter`；s01 | NEW |
| MG02 | `POST /biz/management/spaces` | Merchant | ManagementSpaceCreate | name,sourceAccountNo；所有人从会话核验，bootstrapKey取幂等头，不能客户端指定任意所有人 |
| MG03 | `GET /biz/management/spaces/{managementNo}/resources` | Merchant | resourceType?,view=EFFECTIVE或GRANTS,actorNo?,cursor,size | CursorPage<GrantedResource> | `management.resource.read`；s01,s24 | NEW |
| MG04 | `POST /biz/management/spaces/{managementNo}/grants` | Merchant | GrantCreate | actorNo,resourceType,resourceNo,permissionCode,validFrom,validTo?,delegatable,expectedRevision?,reactivate；单项能力授予，来源服务端校验，撤销后不能由旧请求复活 |
| MG05 | `DELETE /biz/management/spaces/{managementNo}/grants/{grantNo}` | Merchant | expectedRevision,reason | GrantView | `management.grant.manage`；s24 | NEW |
| MG06 | `GET /biz/management/spaces/{managementNo}/entities` | Merchant | cursor,size | CursorPage<EntityBrief> | `entity.read`；s01,s13 | NEW |
| MG07 | `GET /biz/management/spaces/{managementNo}/stores` | Merchant | brandNo?,cursor,size | CursorPage<StoreBrief> | `store.read`；s05 | NEW |

- MG01：只列出当前主体真实获授管理空间，不能以账号所有人身份遍历整个tenant
- MG02：创建不自动授予所有主体权限；由已验证所有人启动
- MG05：逻辑撤销，不物理删除审计

### 品牌与门店

| 编号 | 方法与路径 | 模块 | 输入 | 输出 | 权限／原型 | 状态 |
|---|---|---|---|---|---|---|
| BR01 | `GET /biz/management/brands` | Merchant | managementNo,status?,keyword?,cursor,size | CursorPage<Brand> | `brand.read`；s02,s03,s04 | NEW |
| BR02 | `POST /biz/management/brands` | Merchant | BrandCreate | Brand | `brand.manage`；s02,s03,s04 | NEW |
| BR03 | `GET /biz/management/brands/{brandNo}` | Merchant | — | BrandDetail | `brand.read`；s02,s03,s04 | NEW |
| BR04 | `PUT /biz/management/brands/{brandNo}` | Merchant | BrandEdit | Brand | `brand.manage`；s02,s03,s04 | NEW |
| BR05 | `POST /biz/management/brands/{brandNo}/status-changes` | Merchant | StatusChange | expectedRevision,targetStatus,reason；有有效绑定/待决变更拒绝停用；不清空资金 |
| BR06 | `GET /biz/management/brands/{brandNo}/stores` | Merchant | cursor,size | CursorPage<BrandStore> | `brand.read`；s02,s03,s04 | NEW |
| BR07 | `GET /biz/management/brands/{brandNo}/audit-events` | Merchant | cursor,size | CursorPage<AuditEvent> | `brand.audit.read`；s02,s03,s04 | NEW |
| ST01 | `GET /biz/stores/{storeNo}/operating-context` | Merchant/BFF | managementNo | StoreOperatingContext | `store.read`；s06,s09 | NEW |
| ST02 | `POST /biz/stores/{storeNo}/brand-changes/check` | Merchant/BFF | BrandChangeCheck | managementNo,toBrandNo可空；返回checkToken/sourceRevisions/blockers/warnings/expiresAt，默认60秒；不是Pay资金锁 |
| ST03 | `POST /biz/stores/{storeNo}/brand-changes` | Merchant | BrandChangeSubmit | managementNo,toBrandNo?,checkToken,expectedStoreRevision,expectedScopeRevision,expectedFromBrandRevision?,expectedToBrandRevision?,reason；双方授权快照由服务端补入 |
| ST04 | `GET /biz/management/brand-changes/{changeNo}` | Merchant | — | BrandChange | `brand.change.read`；s08 | NEW |
| ST05 | `POST /biz/management/brand-changes/{changeNo}/decisions` | Merchant/BFF | BrandChangeDecision | expectedRevision,decision=APPROVE/REJECT,reason；仍需重新检查范围和未完成资金，批准不等于跨模块激活 |

- ST01：返回主体和品牌；P1发行信息为NOT_ENABLED或LEGACY_STORE，后续才返回已开通issuer；不改变顶部管理范围
- ST05：批准不等于立即改brandNo；范围双向激活及历史资金检查完成才COMPLETED

### 管控与发行设置

| 编号 | 方法与路径 | 模块 | 输入 | 输出 | 权限／原型 | 状态 |
|---|---|---|---|---|---|---|
| PL01 | `GET /biz/management/stored-value/policies` | Pay/BFF | managementNo,targetType?,targetNo?,currency | List<Policy> | `sv.policy.read`；s25 | NEW |
| PL02 | `PUT /biz/management/stored-value/policies/{policyNo}` | Pay/BFF | PolicyEdit | PolicyVersion | `sv.policy.manage`；s26 | NEW |
| PL03 | `POST /biz/management/stored-value/policies` | Pay/BFF | PolicyCreate | PolicyVersion | `sv.policy.manage`；s26 | NEW |
| PL04 | `GET /biz/stores/{storeNo}/stored-value/effective-policy` | Pay/BFF | currency | EffectivePolicy | `sv.store.activity.read`；s10,s23 | NEW |
| PL05 | `GET /biz/management/stored-value/settlement-entity-candidates` | Pay/BFF | managementNo,scopeType,scopeNo | List<AuthorizedEntity> | `sv.issuer.configure`；s13 | NEW |
| IS01 | `GET /biz/management/brands/{brandNo}/stored-value/issuer` | Pay/BFF | — | Issuer | `sv.brand.issuer.read`；s13,s17 | NEW |
| IS02 | `POST /biz/management/brands/{brandNo}/stored-value/issuer` | Pay/BFF | IssuerCreate | Issuer | `sv.brand.issuer.configure`；s13,s17 | NEW |
| IS03 | `POST /biz/management/stored-value/issuers/{issuerNo}/configuration-checks` | Pay/BFF | IssuerConfigCheck | ConfigurationImpact | `sv.issuer.configure`；s13,s17 | NEW |
| IS04 | `POST /biz/management/stored-value/issuers/{issuerNo}/configuration-versions` | Pay/BFF | IssuerConfigCreate | IssuerVersion | `sv.issuer.configure`；s13,s17 | NEW |
| IS05 | `GET /biz/stores/{storeNo}/stored-value/issuers` | Pay/BFF | — | StoreIssuerOptions | `sv.account.read`；s13,s17 | NEW |

- PL04：返回来源版本和生效上下限，store-only；不是brand发行限制
- PL05：只返回有权配置且主体接受已确认的收款配置，不返回密钥

### 活动生命周期

| 编号 | 方法与路径 | 模块 | 输入 | 输出 | 权限／原型 | 状态 |
|---|---|---|---|---|---|---|
| BA01 | `GET /biz/management/brands/{brandNo}/stored-value/activities` | Core/Pay coordinator | status?,cursor,size | CursorPage<StoredValueActivity> | `sv.brand.activity.read`；s12,s14 | NEW |
| BA02 | `POST /biz/management/brands/{brandNo}/stored-value/activities` | Core/Pay coordinator | ActivityDraftCreate | ActivityDraft | `sv.brand.activity.manage`；s12,s14 | NEW |
| BA03 | `GET /biz/management/brands/{brandNo}/stored-value/activities/{activityNo}` | Core/Pay coordinator | — | ActivityDetail | `sv.brand.activity.read`；s12,s14 | NEW |
| BA04 | `PUT /biz/management/brands/{brandNo}/stored-value/activities/{activityNo}/draft` | Core/Pay coordinator | ActivityDraftEdit | ActivityDraft | `sv.brand.activity.manage`；s12,s14 | NEW |
| BA05 | `POST /biz/management/brands/{brandNo}/stored-value/activities/{activityNo}/checks` | Core/Pay coordinator | ActivityCheck | ActivityCheckResult | `sv.brand.activity.manage`；s12,s14 | NEW |
| BA06 | `POST /biz/management/brands/{brandNo}/stored-value/activities/{activityNo}/submissions` | Core/Pay coordinator | ActivitySubmit | ActivitySubmission | `sv.brand.activity.publish`；s12,s14 | NEW |
| BA07 | `POST /biz/management/brands/{brandNo}/stored-value/activities/{activityNo}/stop-issuance` | Core/Pay coordinator | StopIssuance | ActivityStatus | `sv.brand.activity.manage`；s12,s14 | NEW |
| BA08 | `POST /biz/management/brands/{brandNo}/stored-value/activities/{activityNo}/copies` | Core/Pay coordinator | ActivityCopy | ActivityDraft | `sv.brand.activity.manage`；s12,s14 | NEW |
| SA01 | `GET /biz/stores/{storeNo}/stored-value/activities` | Core/Pay coordinator | status?,cursor,size | CursorPage<StoredValueActivity> | `sv.store.activity.read`；s23,s33 | NEW |
| SA02 | `POST /biz/stores/{storeNo}/stored-value/activities` | Core/Pay coordinator | ActivityDraftCreate | ActivityDraft | `sv.store.activity.manage`；s23,s33 | NEW |
| SA03 | `GET /biz/stores/{storeNo}/stored-value/activities/{activityNo}` | Core/Pay coordinator | — | ActivityDetail | `sv.store.activity.read`；s23,s33 | NEW |
| SA04 | `PUT /biz/stores/{storeNo}/stored-value/activities/{activityNo}/draft` | Core/Pay coordinator | ActivityDraftEdit | ActivityDraft | `sv.store.activity.manage`；s23,s33 | NEW |
| SA05 | `POST /biz/stores/{storeNo}/stored-value/activities/{activityNo}/checks` | Core/Pay coordinator | ActivityCheck | ActivityCheckResult | `sv.store.activity.manage`；s23,s33 | NEW |
| SA06 | `POST /biz/stores/{storeNo}/stored-value/activities/{activityNo}/submissions` | Core/Pay coordinator | ActivitySubmit | ActivitySubmission | `sv.store.activity.publish`；s23,s33 | NEW |
| SA07 | `POST /biz/stores/{storeNo}/stored-value/activities/{activityNo}/stop-issuance` | Core/Pay coordinator | StopIssuance | ActivityStatus | `sv.store.activity.manage`；s23,s33 | NEW |
| SA08 | `POST /biz/stores/{storeNo}/stored-value/activities/{activityNo}/copies` | Core/Pay coordinator | ActivityCopy | ActivityDraft | `sv.store.activity.manage`；s23,s33 | NEW |

- BA01：URL固定发行范围；brandNo不能被storeNo或body覆盖；submit可能PENDING_APPROVAL或PREPARING，只有Pay激活后才可办理
- BA02：URL固定发行范围；brandNo不能被storeNo或body覆盖；submit可能PENDING_APPROVAL或PREPARING，只有Pay激活后才可办理
- BA03：URL固定发行范围；brandNo不能被storeNo或body覆盖；submit可能PENDING_APPROVAL或PREPARING，只有Pay激活后才可办理
- BA04：URL固定发行范围；brandNo不能被storeNo或body覆盖；submit可能PENDING_APPROVAL或PREPARING，只有Pay激活后才可办理
- BA05：URL固定发行范围；brandNo不能被storeNo或body覆盖；submit可能PENDING_APPROVAL或PREPARING，只有Pay激活后才可办理
- BA06：URL固定发行范围；brandNo不能被storeNo或body覆盖；submit可能PENDING_APPROVAL或PREPARING，只有Pay激活后才可办理
- BA07：URL固定发行范围；brandNo不能被storeNo或body覆盖；submit可能PENDING_APPROVAL或PREPARING，只有Pay激活后才可办理
- BA08：URL固定发行范围；brandNo不能被storeNo或body覆盖；submit可能PENDING_APPROVAL或PREPARING，只有Pay激活后才可办理
- SA01：URL固定发行范围；brandNo不能被storeNo或body覆盖；submit可能PENDING_APPROVAL或PREPARING，只有Pay激活后才可办理
- SA02：URL固定发行范围；brandNo不能被storeNo或body覆盖；submit可能PENDING_APPROVAL或PREPARING，只有Pay激活后才可办理
- SA03：URL固定发行范围；brandNo不能被storeNo或body覆盖；submit可能PENDING_APPROVAL或PREPARING，只有Pay激活后才可办理
- SA04：URL固定发行范围；brandNo不能被storeNo或body覆盖；submit可能PENDING_APPROVAL或PREPARING，只有Pay激活后才可办理
- SA05：URL固定发行范围；brandNo不能被storeNo或body覆盖；submit可能PENDING_APPROVAL或PREPARING，只有Pay激活后才可办理
- SA06：URL固定发行范围；brandNo不能被storeNo或body覆盖；submit可能PENDING_APPROVAL或PREPARING，只有Pay激活后才可办理
- SA07：URL固定发行范围；brandNo不能被storeNo或body覆盖；submit可能PENDING_APPROVAL或PREPARING，只有Pay激活后才可办理
- SA08：URL固定发行范围；brandNo不能被storeNo或body覆盖；submit可能PENDING_APPROVAL或PREPARING，只有Pay激活后才可办理

### 活动审批

| 编号 | 方法与路径 | 模块 | 输入 | 输出 | 权限／原型 | 状态 |
|---|---|---|---|---|---|---|
| AP01 | `GET /biz/management/stored-value/activity-approvals` | Core | managementNo,status?,cursor,size | CursorPage<Approval> | `sv.store.activity.approve`；s28 | NEW |
| AP02 | `GET /biz/management/stored-value/activity-approvals/{approvalNo}` | Core/BFF | — | ApprovalDetail | `sv.store.activity.approve`；s29 | NEW |
| AP03 | `POST /biz/management/stored-value/activity-approvals/{approvalNo}/decisions` | Core/Pay coordinator | ApprovalDecision | ApprovalDecisionResult | `sv.store.activity.approve`；s29 | NEW |

- AP03：批准重查最新策略并锁定draftHash；硬超限返回BLOCKED_POLICY，不允许强批；禁止同人审批自身申请为默认岗位规则

### 会员与办理

| 编号 | 方法与路径 | 模块 | 输入 | 输出 | 权限／原型 | 状态 |
|---|---|---|---|---|---|---|
| MB01 | `GET /biz/stores/{storeNo}/stored-value/customer-candidates` | Core/BFF | keyword?,cursor,size | CursorPage<MemberAccountSummary> | `sv.customer.select`；s16 | NEW |
| MB02 | `POST /biz/stores/{storeNo}/stored-value/customer-selections` | Core/BFF | CustomerSelect | CustomerSelection | `sv.customer.select`；s17 | NEW |
| MB03 | `POST /biz/stores/{storeNo}/members` | Core | MemberCreate | ScopeMember | `member.manage`；s16 | EXTEND |
| MB04 | `GET /biz/stores/{storeNo}/stored-value/customer-selections/{customerRef}/accounts` | Core/BFF | — | List<AvailableAccount> | `sv.account.read`；s17,s32 | NEW |
| RC01 | `GET /biz/stores/{storeNo}/stored-value/recharge-options` | Pay/BFF | customerRef,scopeType=STORE／BRAND | RechargeOptions | `sv.recharge.create`；s17 | NEW |
| RC02 | `POST /biz/stores/{storeNo}/stored-value/issuer-recharges/confirm-offline` | Pay/BFF | IssuerRechargeCreate | RechargeProgress | `sv.recharge.create + sv.recharge.receive`；s17 | NEW |
| RC03 | `GET /biz/stores/{storeNo}/stored-value/issuer-recharges/{rechargeNo}` | Pay/BFF | — | RechargeProgress | `sv.recharge.read`；s17 | NEW |
| RC04 | `POST /biz/stores/{storeNo}/stored-value/issuer-recharges/resolve` | Pay/BFF | RechargeReplay | RechargeResolve | `sv.recharge.create`；s17 | NEW |
| RC05 | `POST /biz/stores/{storeNo}/stored-value/issuer-recharges/cancel-create` | Pay/BFF | RechargeReplayCancel | CancelResult | `sv.recharge.create`；s17 | NEW |
| RC06 | `POST /biz/stores/{storeNo}/stored-value/issuer-recharges/{rechargeNo}/cancel` | Pay/BFF | CancelRequest | RechargeProgress | `sv.recharge.cancel`；s17 | NEW |

- MB01：一个搜索入口，默认已有成功储值会员按最近充值；品牌识别不等于他店资金查询权限
- MB02：只收memberNo/accountRef/统一会员码，不收可伪造customerNo；ref绑定当前店及操作者
- MB03：会员模块原有入口，储值页面不新增；先匹配平台顾客再建品牌或单店关系
- RC01：仅已激活发行版本；范围显式，未开账户返回account=null，不伪造accountRef
- RC02：校验实际收款方，品牌统一收款时门店人员须有该收款配置核验权；不得默认把门店现金认成总部到账
- RC04：原幂等键和指纹找原单，不重复收款或再开账户
- RC05：只允许未收款未知创建的持久取消栅栏；已收款须原单恢复
- RC06：只允许未收款；不提供C端线上退款

### 核销与收支

| 编号 | 方法与路径 | 模块 | 输入 | 输出 | 权限／原型 | 状态 |
|---|---|---|---|---|---|---|
| US01 | `POST /biz/stored-value/consumptions/preview` | Core/Pay | ConsumePreviewV2 | ConsumePreview | `sv.consume.create`；s32 | EXTEND |
| US02 | `POST /biz/stored-value/consumptions` | Core/Pay | ConsumeV2 | ConsumptionResult | `sv.consume.create`；s32 | EXTEND |
| US03 | `POST /mp/orders/{orderNo}/entitlement-authorizations` | Core | ExistingAuthorizationRequest | Authorization | `consumer.self`；s32 | REUSE |
| RD01 | `GET /biz/stores/{storeNo}/stored-value/issuer-records` | Pay/BFF | scopeType,type=ALL／RECHARGE／REDEMPTION,startDate?,endDate?,accountRef?,cursor,size | CursorPage<IssuerRecord> | `sv.record.read`；s18 | NEW |
| RD02 | `GET /biz/stores/{storeNo}/stored-value/issuer-records/{recordNo}` | Pay/BFF | scopeType | IssuerRecordDetail | `sv.record.read`；s18 | NEW |

- US01：增加accountRef/issuerRef，在报价冻结；一次只允许一个账户；旧客户端省略时只选本店账户，不自动选品牌
- US02：沿用订单、预览及消费者授权；必须匹配选定issuer和account，禁止混扣
- US03：消费者确认内容扩展为发行范围和选定账户，不用customerRef代替扣款授权
- RD01：门店上下文不加第二个店筛选；品牌时只返回本店实际办理/核销，全集需总部权限

### 汇总

| 编号 | 方法与路径 | 模块 | 输入 | 输出 | 权限／原型 | 状态 |
|---|---|---|---|---|---|---|
| RP01 | `GET /biz/management/brands/{brandNo}/stored-value/summary` | Pay readmodel/BFF | activityNo?,startDate,endDate,currency | BrandSummary | `sv.brand.report`；s19 | NEW |
| RP02 | `GET /biz/management/brands/{brandNo}/stored-value/store-breakdown` | Pay readmodel/BFF | activityNo?,startDate,endDate,currency,cursor,size | CursorPage<StoreAttribution> | `sv.brand.report`；s20 | NEW |
| RP03 | `GET /biz/management/stored-value/store-issuer-summary` | Pay readmodel/BFF | managementNo,startDate,endDate,currency,cursor,size | StoreIssuerBusinessSummary | `sv.store.report.all`；s34 | NEW |
| RP04 | `GET /biz/stores/{storeNo}/stored-value/store-summary` | Pay readmodel/BFF | activityNo?,startDate,endDate,currency | StoreSummary | `sv.store.report`；s31 | NEW |
| RP05 | `GET /biz/management/stored-value/activities/{activityNo}/summary` | Pay readmodel/BFF | startDate,endDate,currency | ActivityLotSummary | `sv.activity.report`；s15 | NEW |

- RP01：返回asOf/projectionWatermark及范围；不跨币种求和，活动余额按来源批次
- RP02：返回asOf/projectionWatermark及范围；不跨币种求和，活动余额按来源批次
- RP03：返回asOf/projectionWatermark及范围；不跨币种求和，活动余额按来源批次
- RP04：返回asOf/projectionWatermark及范围；不跨币种求和，活动余额按来源批次
- RP05：返回asOf/projectionWatermark及范围；不跨币种求和，活动余额按来源批次

### 结算与对账

| 编号 | 方法与路径 | 模块 | 输入 | 输出 | 权限／原型 | 状态 |
|---|---|---|---|---|---|---|
| CL01 | `GET /biz/management/stored-value/settlement-bills` | Pay/BFF | managementNo,scopeType,issuerNo?,periodFrom,periodTo,currency,status?,cursor,size | CursorPage<SettlementBill> | `sv.settlement.read`；s21,s22 | NEW |
| CL02 | `POST /biz/management/stored-value/settlement-bills/generations` | Pay/BFF | BillGeneration | GenerationProgress | `sv.settlement.generate`；s21,s22 | NEW |
| CL03 | `GET /biz/management/stored-value/settlement-bills/{billNo}` | Pay/BFF | — | BillDetail | `sv.settlement.read`；s21,s22 | NEW |
| CL04 | `GET /biz/management/stored-value/settlement-bills/{billNo}/items` | Pay/BFF | cursor,size | CursorPage<ClearingItem> | `sv.settlement.read`；s21,s22 | NEW |
| CL05 | `POST /biz/management/stored-value/settlement-bills/{billNo}/reconciliation-confirmations` | Pay/BFF | BillConfirm | BillDetail | `sv.settlement.reconcile`；s21,s22 | NEW |
| CL06 | `POST /biz/management/stored-value/settlement-bills/{billNo}/disputes` | Pay/BFF | BillDispute | BillDetail | `sv.settlement.dispute`；s21,s22 | NEW |
| CL07 | `POST /biz/management/stored-value/settlement-bills/{billNo}/dispute-resolutions` | Pay/BFF | DisputeResolution | BillDetail | `sv.settlement.dispute.resolve`；s21,s22 | NEW |
| CL08 | `POST /biz/management/stored-value/settlement-bills/{billNo}/adjustments` | Pay/BFF | BillAdjustment | BillDetail | `sv.settlement.adjust`；s21,s22 | NEW |
| CL09 | `POST /biz/management/stored-value/settlement-bills/{billNo}/payments` | Pay/BFF | PaymentRegistration | PaymentDetail | `sv.settlement.payment.register`；s21,s22 | NEW |
| CL10 | `POST /biz/management/stored-value/settlement-payments/{paymentNo}/receipt-confirmations` | Pay/BFF | ReceiptConfirm | BillDetail | `sv.settlement.receipt.confirm`；s21,s22 | NEW |
| CL11 | `POST /biz/management/stored-value/settlement-payments/{paymentNo}/voids` | Pay/BFF | PaymentVoid | BillDetail | `sv.settlement.payment.void`；s21,s22 | NEW |
| CL12 | `GET /biz/management/stored-value/settlement-bills/{billNo}/events` | Pay/BFF | cursor,size | CursorPage<SettlementEvent> | `sv.settlement.audit`；s21,s22 | NEW |

- CL01：付款登记不调用银行；收款确认必须为收款主体岗位；同主体账单只内部对账
- CL02：付款登记不调用银行；收款确认必须为收款主体岗位；同主体账单只内部对账
- CL03：付款登记不调用银行；收款确认必须为收款主体岗位；同主体账单只内部对账
- CL04：付款登记不调用银行；收款确认必须为收款主体岗位；同主体账单只内部对账
- CL05：付款登记不调用银行；收款确认必须为收款主体岗位；同主体账单只内部对账
- CL06：付款登记不调用银行；收款确认必须为收款主体岗位；同主体账单只内部对账
- CL07：付款登记不调用银行；收款确认必须为收款主体岗位；同主体账单只内部对账
- CL08：付款登记不调用银行；收款确认必须为收款主体岗位；同主体账单只内部对账
- CL09：付款登记不调用银行；收款确认必须为收款主体岗位；同主体账单只内部对账
- CL10：付款登记不调用银行；收款确认必须为收款主体岗位；同主体账单只内部对账
- CL11：付款登记不调用银行；收款确认必须为收款主体岗位；同主体账单只内部对账
- CL12：付款登记不调用银行；收款确认必须为收款主体岗位；同主体账单只内部对账

### C端兼容影响

| 编号 | 方法与路径 | 模块 | 输入 | 输出 | 权限／原型 | 状态 |
|---|---|---|---|---|---|---|
| MP01 | `GET /mp/stored-value/issuers` | Pay/BFF | cursor,size | CursorPage<MyIssuer> | `consumer.self`；s17 | NEW |
| MP02 | `GET /mp/stored-value/issuers/{issuerRef}/records` | Pay/BFF | type,startDate?,endDate?,focusDocumentNo?,cursor,size | MyIssuerRecords | `consumer.self`；s18 | NEW |

- MP01：品牌账户只出现一次，单店账户单列；只返回本人成功储值记录
- MP02：无本金赠金拆分，无购买/退款；时区跟发行方，跨店记录带实际发生店

### 内部契约

| 编号 | 方法与路径 | 模块 | 输入 | 输出 | 权限／原型 | 状态 |
|---|---|---|---|---|---|---|
| IN01 | `POST /internal/stored-value/issuers/access-revisions` | Core↔Pay | StoreAccessRevision | AccessAck | `service.identity`；s08,s14,s29 | NEW |
| IN02 | `POST /internal/stored-value/releases/prepare` | Core↔Pay | ReleasePrepare | PreparedRelease | `service.identity`；s08,s14,s29 | NEW |
| IN03 | `POST /internal/stored-value/releases/{releaseNo}/activate` | Core↔Pay | ReleaseActivate | ReleaseState | `service.identity`；s08,s14,s29 | NEW |
| IN04 | `POST /internal/stored-value/releases/{releaseNo}/stop` | Core↔Pay | ReleaseStop | ReleaseState | `service.identity`；s08,s14,s29 | NEW |
| IN05 | `POST /internal/stored-value/policies/evaluations` | Core↔Pay | PolicyEvaluate | EffectivePolicyResult | `service.identity`；s08,s14,s29 | NEW |
| IN06 | `GET /internal/stored-value/commands/{commandNo}` | Core↔Pay | — | OriginalCommandResult | `service.identity`；s08,s14,s29 | NEW |

- IN01：端点为设计建议，落地优先扩展现有payclient AppService契约；防重、签名、范围及版本均服务端核实
- IN02：端点为设计建议，落地优先扩展现有payclient AppService契约；防重、签名、范围及版本均服务端核实
- IN03：端点为设计建议，落地优先扩展现有payclient AppService契约；防重、签名、范围及版本均服务端核实
- IN04：端点为设计建议，落地优先扩展现有payclient AppService契约；防重、签名、范围及版本均服务端核实
- IN05：端点为设计建议，落地优先扩展现有payclient AppService契约；防重、签名、范围及版本均服务端核实
- IN06：端点为设计建议，落地优先扩展现有payclient AppService契约；防重、签名、范围及版本均服务端核实

[P1组织与品牌详细设计](TDD-P1-经营组织与品牌管理-详细设计.md)细化本阶段21项接口、DTO与状态。MG03使用EFFECTIVE/GRANTS两种视图；MG08仅返回有效已授权商户候选。

## 3. 核心请求字段与响应

以下字段加上通用expectedRevision（已有资源写入）、Idempotency-Key构成最小契约。DTO中的No是业务标识，Ref是有用途和有效期的opaque引用，不能互换。

| DTO | 字段及校验 |
|---|---|
| ManagementSpaceCreate | name；ownerActor由会话及既有所有人资格核验，不能客户端指定任意所有人 |
| GrantCreate | actorRef,resourceType,resourceNo,permissionCodes[],validTo?；授予者必须本身有资源授权且可委派，不能仅凭空间所有人授权不属于自己的主体 |
| BrandCreate / BrandEdit | 创建managementNo,name(1–100),logo?,status=DRAFT；编辑name,logo?,expectedRevision；不含entityNo，状态独立命令 |
| StatusChange | expectedRevision,status,reason；停用前有存量必须返回影响检查或变更单，不清空资金 |
| BrandChangeCheck | targetBrandNo可空,expectedBindingRevision；返回checkNo/checkHash/sourceRevisions/blockers[]/warnings[]/expiresAt |
| BrandChangeSubmit | checkNo,checkHash,targetBrandNo,expectedBindingRevision,reason；双方授权快照由服务端补入 |
| BrandChangeDecision | expectedRevision,decision=APPROVE/RETURN,reason；审批仍需重新检查范围和未完成资金 |
| PolicyCreate / PolicyEdit | managementNo,targetType,targetNo,currency,allowIssue,requireApproval,maxBonusBps,maxPaidMinor,maxBonusMinor,maxActiveDays,maxOutstandingMinor；编辑加expectedRevision和expectedParentVersions[]；金额上限不允许NULL表示绕过父规则 |
| IssuerCreate | settlementEntityRef,collectionConfigRef,currency,marketZone,clearingRule；品牌来自URL，单店主体来自已授权门店；由服务端校验主体接受及收款配置 |
| IssuerConfigCheck / IssuerConfigCreate | expectedRevision,targetSettlementEntityRef,collectionConfigRef,clearingRule,reason；创建版本需checkNo和checkHash，无余额及未完成业务才可走直接变更，否则返回受控处理要求 |
| ActivityDraftCreate | name,currency,startsOn,endsOn,marketZone,tiers[{clientTierId,paidMinor,bonusMinor}]；scope从URL确定，brandNo不接受body覆盖 |
| ActivityDraftEdit | expectedRevision,draftRevisionNo,name,startsOn,endsOn,tiers[]；draftHash服务器计算；金额变化不原地修改已发布版本 |
| ActivityCheck | expectedRevision,draftRevisionNo；返回checks[],effectivePolicy,parentVersions,draftHash,requiredAction=FIX/SUBMIT_APPROVAL/PUBLISH |
| ActivitySubmit | expectedRevision,draftRevisionNo,draftHash,checkNo；品牌需发行权限，单店由当前最新策略决定审批，不能由客户端传skipApproval |
| StopIssuance | expectedRevision,reason；不停止历史余额核销，返回STOP_REQUESTED直到Pay确认停止新入账资格；已收款原单继续恢复 |
| ActivityCopy | name,targetStartsOn,targetEndsOn；仅同scope复制，不允许“复制”暗中跨品牌或跨店 |
| ApprovalDecision | expectedRevision,expectedDraftHash,decision=APPROVE/RETURN,reason；重复决定只返回原结果，draft变化则STALE |
| CustomerSelect | source={type:MEMBER,memberNo}或{type:ACCOUNT,accountRef}或{type:MEMBER_CODE,code}；严格单一来源 |
| MemberCreate | 姓名/已验证联系方式及必要信息沿用会员模块；先识别人档；品牌归属服务器按当前店解析，不由调用者任意指定customerNo |
| IssuerRechargeCreate | customerRef,rechargeOptionRef,releaseNo,tierNo；可附collectionConfirmationRef；禁止提交自算到账额、issuerNo覆盖选项或任意收款主体 |
| RechargeReplay / RechargeReplayCancel | originalIdempotencyKey,originalRequestFingerprint；cancel加reason；不依赖已过期customerRef重新识别 |
| CancelRequest | expectedRevision,reason；收款已确认后拒绝取消，指引原单恢复或独立财务退回流程 |
| ConsumePreviewV2 | storeNo,customerRef,items[]和accountRef；scope来自选定账户，服务端商品售价算amount，选账锁入预览 |
| ConsumeV2 | orderNo,orderRevision,customerRef,authorizationRef；必须匹配预览、发行方、账户及assetRevision；不用新customerRef替代原授权 |
| ExistingAuthorizationRequest | 复用订单授权契约；授权快照补issuerScopeType/issuerName/accountRef指纹与本次金额，不新增独立核销码 |
| BillGeneration | managementNo,issuerNo,currency,periodFrom,periodTo；规则时区、收款/付款主体由原清分事实决定；不允许客户端提交应付总额 |
| BillConfirm | expectedRevision,expectedItemDigest；收款侧对账权限；内部对账单使用同一确认能力但不进入付款流程 |
| BillDispute | expectedRevision,reason,itemRefs[]?；不修改原金额 |
| DisputeResolution | expectedRevision,decision=RESUME/ADJUST,reason,evidenceRefs[]；涉及金额必须生成独立调整事实 |
| BillAdjustment | expectedRevision,originalClearingNo,deltaMinor,reason,evidenceRefs[]；需财务调整岗位；已结算原单调整进入下一期，禁止覆盖历史 |
| PaymentRegistration | expectedRevision,amountMinor>0,paidAt,receiptNo,evidenceRefs[]；<=应付净额−已登记未撤销金额；凭据服务端加密/摘要去重 |
| ReceiptConfirm | expectedRevision,expectedPaymentRevision,receivedAt,confirmationNote?；只能收款主体有权岗位；不得由付款岗位替代 |
| PaymentVoid | expectedRevision,reason；仅REGISTERED可撤销，已确认到账必须单独纠错事件，不删除 |
| ReleasePrepare（内部） | commandNo,activityNo,draftRevisionNo,scope,issuerNo,issuerVersionNo,currency,schedule,tiers[],sourceFactRevision；不信任未经认证的浏览器载荷 |
| ReleaseActivate（内部） | commandNo,releaseHash,activityVersionNo,approvalDecisionRef?,expectedPolicyVersions,scopeRevision；Pay重查当前策略及准入再原子ACTIVE |
| StoreAccessRevision（内部） | commandNo,sourceBindingNo,sourceRevision,storeNo,operatingEntityNo,issuerNo,canIssue,canRedeem,status；拒绝旧revision覆盖新事实 |

### 3.1 关键响应结构

```json
{
  "storeNo": "ST-DEMO-A",
  "operatingEntityNo": "ENTITY-A",
  "brand": {"brandNo": "BR-DEMO", "scopeRevision": 12},
  "issuers": [
    {"scopeType": "STORE", "issuerRef": "opaque-store", "name": "科技园店储值", "account": {"accountRef": "opaque-wallet-1", "availableMinor": 6000, "currency": "CNY"}},
    {"scopeType": "BRAND", "issuerRef": "opaque-brand", "name": "悦颜品牌储值", "account": {"accountRef": "opaque-wallet-2", "availableMinor": 13000, "currency": "CNY"}}
  ]
}
```

未储值的scope返回account=null而非伪造零余额账户；前端不合计上述两个余额。平台身份保留服务端canonical customerNo，不从公开请求信任任意userNo/personNo。

```json
{
  "draftRevisionNo": "DRAFT-3",
  "draftHash": "sha256-canonical",
  "requiredAction": "FIX",
  "effectivePolicy": {"allowIssue": true, "requireApproval": true, "maxBonusBps": 2000, "maxPaidMinor": 100000},
  "sources": [
    {"targetType": "SPACE", "versionNo": "PV-1", "maxBonusBps": 3000},
    {"targetType": "BRAND_STORES", "versionNo": "PV-2", "maxBonusBps": 2000}
  ],
  "checks": [{"reason": "POLICY_LIMIT_EXCEEDED", "field": "tiers[0].bonusMinor", "actualMinor": 3000, "maxAllowedMinor": 2000}]
}
```

充值返回：rechargeNo/documentNo/status/CREDITED标识、accountRef、creditedMinor、availableMinor、revision、nextAction。状态未知不能显示“已到账”；已提交原单超时先resolve，不生成第二单。金额来自原版本，不从前端显示文案取值。

活动返回：activityNo/name/scopeType/scopeNo/issuerRef/draftRevisionNo/draftHash/revision/operationalState/approvalNo?/publicationState/tiers只读投影/startsAt/endsAt/createdAt/updatedAt/createdBy/updatedBy。operationalState为DRAFT/PENDING_APPROVAL/RETURNED/PREPARING/SCHEDULED/RUNNING/ISSUE_STOPPED/EXPIRED，由Core状态、审批和Pay激活共同映射，不能拿原RUNNING一个字段判断。

报表返回：scopeType/scopeNo/issuerNo/currency/from/to/asOf/projectionWatermark、期间流量、期初余额、期末批次余额；人数及混合批次核销次数按原业务号distinct，不相加各档位去重人数。发行方总余额与活动来源批次剩余分字段。

账单返回：billNo/documentNo/issuerNo/payerEntity/payeeEntity/currency/period/billKind/status/revision/basePayableMinor/adjustmentMinor/netPayableMinor/registeredPaymentMinor/confirmedReceiptMinor/unregisteredMinor/unconfirmedReceiptMinor/allowedActions[]。内部对账billKind=INTERNAL_RECON，不给payments动作。

## 4. 原接口兼容清单

| 已有路径 | 兼容策略 |
|---|---|
| GET /biz/stored-value/context、/plans及详情 | 保留单店语义，不能因门店有品牌返回品牌余额或品牌活动 |
| POST /biz/stored-value/plans及状态动作 | V2启用后通过单店协调器执行限制及审批；无法返回审批状态的旧客户端写入明确要求升级，不保留直写Pay旁路 |
| /biz/stored-value/plan-recharges/* | 原单重放与恢复照旧；新办理只在已映射单店发行方、已批准发布及额度校验通过后执行。不能通过旧接口办理品牌资金 |
| /biz/stored-value/customer-candidates、customer-selections | 未升级客户端仍为门店／既有主体授权，不自动扩张全品牌；新双账户UI用MB01/02 |
| /biz/stored-value/account-directory、accounts/{accountRef}、records | 保持单店发行方隔离；旧accountRef不能因为新增issuer维度突然获得品牌查询权限 |
| /biz/stored-value/consumptions/preview、consumptions | US01/02改造；没有明确选账的旧请求只兼容本店账户，不能自动优先扣品牌余额 |
| /mp/stored-value/stores、store/{storeNo}/records | 保留本人单店记录语义；品牌目录使用MP01，不把同一品牌钱包复制为每家门店余额 |
| 原通知任务与会员码 | 继续使用；通知增加发行方scope和账户定位，幂等仍按原usage/recipient/scene，不能按不同门店重复发送 |

## 5. 授权与失败矩阵

- 顶层空间所有人没有自动的外部主体财务权；结算主体选择须独立有效授权和对方接受事实。
- 门店有品牌，STORE接口仍只创建本店活动。BRAND接口必须验证品牌发行岗位；body混入另一scope直接拒绝。
- 同品牌会员跨经营主体可识别，但店员只能查本店执行交易及可用账户摘要，不能直接读取他店账单、私有档案或原始收款凭据。
- 活动提交后draftHash变化则旧审批失效；审批时策略收紧则BLOCKED_POLICY；审批成功但Pay激活失败则PREPARING/FAILED，不对外办理。
- 品牌范围变更、发行配置变更与未完成单互斥；已收款原充值按原issuer/release恢复，不跟随当前品牌重新选账。
- 权限撤销影响新请求，不删除已发生账务；普通账户冻结不能冒充财务退款。
- 本文列出的90个操作包含管理、内部协调及C端兼容影响，不等于90个全新独立Controller；允许共用领域服务，但不能合并授权边界。


### 5.1 金额规则精度补充

IssuerCreate/IssuerConfigCreate需指定经服务端支持的debitAllocationMode与clearingMode。样例新品牌采用PRO_RATA＋PRINCIPAL_COMPONENT；历史批次LEGACY_SNAPSHOT不能被当前品牌设置覆盖。接口返回的实收清分基于原扣减分量，不能前端用核销金额自行乘比例作为应付真源。


## 6. 支付商户维度修订

主体与商户为1:N：商户指mch_payment_merchant，明确使用payMerchantNo；issuerNo仍表示储值发行方。复用现有支付商户，不新增同义商户实体。

| 编号 | 方法与路径 | 输入 | 输出 | 权限 |
|---|---|---|---|---|
| MG08 | GET /biz/management/entities/{entityNo}/payment-merchants | managementNo,payChannel?,payScene?,storeNo? | AuthorizedPaymentMerchant[] | payment.merchant.read |
| IS06 | GET /biz/management/stored-value/issuers/{issuerNo}/collection-routes | payChannel?,payScene? | IssuerCollectionRoutes | sv.issuer.configure |

IssuerCreate/IssuerConfigCreate的collectionConfig改为版本化routes[{payChannel,payScene,payMerchantRef}]。服务器解析既有payMerchantNo及merchantRevision，核验tenant、entity归属、ACTIVE进件、实际授权支付方式、币种和收款配置；不能只核验主体。

AuthorizedPaymentMerchant返回payMerchantRef、entityNo、payChannel、applyStatus、allowedPayMethods和脱敏结算信息，不返回密钥。品牌统一收款路线必须属于指定结算／收款主体；本店自己的默认商户不能替代总部商户。CASH/TRANSFER不要求伪造payMerchantRef。

充值请求仍提交受控rechargeOptionRef和原收款确认引用，不允许调用者自报任意商户或“成功的渠道流水”。collectionPaymentNo来自已有支付系统内部的可信关联；线上回执按原支付号及实际商户验签防重，线下仅记录有权岗位确认事实，不假称银行核验成功。

新增2个查询操作后总计90个操作（86新增、3扩展、1复用）；34屏覆盖不变。结算账单的主体维度不改为payMerchantNo，顾客账户唯一键也不加payMerchantNo或payChannel。

### MG09 管理空间可授权人员（P1补充）

`GET /biz/management/spaces/{managementNo}/recipients`，参数cursor/size；响应items[{actorNo,displayName}]/nextCursor。需要management.resource.read及management.grant.manage；仅当前操作者可控主体的有效成员，不使用当前门店上下文。

## P2已实现契约补充

MB01/02/03已实现，权限映射到既有门店CUSTOMER/SV_RECHARGE_CREATE及单独SV_ACCOUNT_READ；MB02返回selection及sourceStoreNo/Name，MB01含identityConflictCount和默认本店已储值账户。旧主体接口不自动变为品牌营销授权。详细字段、前后端类及边界见[TDD-P2实施设计](TDD-P2-品牌会员共享-实施设计.md)。

### P3 实现增量：单店发行准备（2026-10-23，开发中）

- 内部传输实际路径：`POST /internal/pay/stored-value/issuers/prepare-store`，对应 `StoredValueIssuerAppService.prepareStore(StoreAuthority)`，支持嵌入及独立 Pay；调用需内部服务令牌。
- Core 先以 `IssuerAuthorityService.storePreparationScope` 核实有效门店、当前经营主体和真实所有人，得到 tenantNo/actorNo/entityNo/storeNo/storeName/storeRevision。公开请求不能绑定 StoreAuthority，不能把门店管理权解释为品牌结算授权。
- 返回 `StoredValueIssuers.View`；该操作幂等准备 STORE 发行草稿及 PREPARING 准入，can_issue/can_redeem 均为 0。不建立钱包、不激活储值、不迁余额；变更经营主体冲突，不据手机号或同主体合并。
- 这是 IN01 的单店准备基础，不代表准入修订、迁移切写、IS05 店内发行选项或 B 端入口已全部实现。现网尚未部署该接口。

### P3 内部迁移清单合同（2026-10-23，开发中，未开放HTTP）

| Java操作 | 输入 | 返回与条件 |
|---|---|---|
| StoredValueMigrationManifestService.prepare | Core解析的Evidence、原命令key | 持久Manifest；需migration-enabled，不要求事先打开issuer-mode；无金融修改 |
| find | 同范围Evidence、manifestNo | 原审计清单标识和摘要；不重新盘账 |
| apply | 同范围Evidence、manifestNo、原执行key | METADATA_MAPPED；需维护及新写路径开关、目标FENCED和原事实/来源修订一致；成功不释放栅栏 |

Evidence是服务端合同，不作为公开用户DTO。执行只使用服务器保存的摘要；清单包含完整原账户映射和授权/事实快照。持久记录复用pay_ent_command_dedup，独立命令空间避免历史普通映射回执绕过最终维护要求。Core实时证据解析、全进程就绪与维护入口尚待对接；不以这些Java方法代替完整迁移或切写API。

### 发行配置版本并发口径（P3，2026-10-23）

发行配置 `View.revision` 与配置写入的 `Config.expectedRevision` 均表示不可变配置的 `version_index - 1`，首次配置为 0。保存时在发行方行锁内核对当前配置版本，过期配置提交仍返回冲突。同幂等键、相同内容返回原配置版本，不返回后续版本。`sv_issuer.version` 仅用于数据库行级变更，旧账映射、写入栅栏或状态维护增加该值，不应让未变化的配置变成过期。此修复不等于 IS03/IS04 的受控变更检查、权限及完整 HTTP 流程已交付。
