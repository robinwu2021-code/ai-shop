# 审核需求清单 · near-pay 与 ai-shop

> 日期：2026-11-23 · 状态：**盘点稿 + 分级与工作流**（用户 2026-11-23 定：① 一律可以本人审批 ② 审批分 3 个等级 ③ 每个审批整理工作流，见 §8、§9）
> 范围：两个系统里所有「审核 / 审批 / 复核 / 确认」类事项——代码里已实现的、只在需求文档里提出的、明确不审的。
> 关联：[TDD-审批统一与简化](../technical/TDD-审批统一与简化.md)（统一方案与接入 OpsKit 的业务清单）· [TDD-OpsKit审批对接](../technical/TDD-OpsKit审批对接.md)
> 证据路径：near-pay 仓 `backend/pay/{pay-domain,pay-portal,pay-svc}`（下称 NP），ai-shop 仓 `backend/*`、`ops-web`、`b-app`（下称 SH）。行内只给类名或文档名，精确行号见盘点底稿（§7）。

---

## 0. 怎么读

**现状**
- ✅ 已实现
- 🟡 部分实现
- 📄 仅需求文档
- ⚪ 已声明未接
- ⛔ 已废弃

**两人**：是否强制「提交人 ≠ 审核人」。
- 硬：写死，不读开关
- 开关：读 near-pay `single-operator` 开关，生产为 true，即实际允许本人
- 码：要求两个权限码，但可以是同一个人
- 否：不要求

**归属**：按 [TDD-审批统一与简化](../technical/TDD-审批统一与简化.md) §1、§4 的口径。
- **OK**：内部人员对一项变更做「同意 / 不同意」→ 进 OpsKit 审批（已在接入清单里的标 ●）
- **业务**：买卖双方协商、对账确认、自确认 → 留在业务流程，不进审批
- **队列**：量大的内容审核队列 → 暂不进，以后可用 OpsKit「审批模板 + 队列组」接
- **不审**：直接生效、无审核要求

---

## 1. 汇总

§2 已实现 90 条（near-pay 39、ai-shop 49、两边共有 2），§3 待排期 30 条（多数是一行里含多项的需求组）。

| 归属 | §2 条数 | 说明 |
|---|---:|---|
| **OK**（进 OpsKit 审批） | 55 | 其中 33 条已在 TDD-审批统一与简化 §5 的接入清单（●）；其余 22 条是本次新发现、建议补进清单 |
| **业务**（协商 / 对账 / 自确认 / 工单） | 15 | 售后、渠道订单、对账确认、开票作业、告警与风险处置等 |
| **队列**（量大的内容审核） | 7 | 商品、评价、申诉、种草、拼团、黑名单申诉、店招（待定 Q2） |
| **不审**（直接生效 / 技术核验） | 9 | 成员管理、凭据吊销、渠道启停、认证标等 |
| 已废弃 | 1 | ai-shop `stl_withdraw` |

强制「提交人 ≠ 审核人」写死不读开关的有 **14** 条，见 §5。

---

## 2. 已实现的审核

### 2.1 商户、主体、入驻、门店

| # | 系统 | 审核事项 | 发起 → 审核 | 现状 | 两人 | 归属 | 依据 |
|---|---|---|---|---|---|---|---|
| A1 | NP | 商户资料修订批准 MERCHANT_PROFILE_APPROVE | 商户 / 运营代办 / ai-shop 提交 → 运营 `pay:merchant:approve` | ✅ 审批中心 | 开关 | OK ● | ProfileApprovalSubject · ProfileRevisionService |
| A2 | NP | 主体资料提交、补件（ai-shop 内部入口） | ai-shop → 运营 request-more | ✅ 独立入口，审批复用 A1 | — | OK（随 A1） | InternalEntityProfileController · OpsEntityMaterialController |
| A3 | NP | 「确认审核」一键通过（核验来源 + 走完 A1） | 运营 | ✅ 组合入口 | 只在单人模式 | OK（被 OpsKit「本人审批」取代后下线） | OpsMerchantConfirmController |
| A4 | NP | 恢复平台商户 PLATFORM_MERCHANT_RESUME | 运营 manage → approve | ✅ 审批中心 | 开关 | OK ● | MerchantResumeSubject |
| A5 | NP | 来源映射人工核验（VERIFIED / MISMATCH） | 运营 | ✅ 独立 | 否 | 不审（核验结论，不是审批） | SourceLinkService |
| A6 | NP | 来源归属修复 SOURCE_ASSOCIATION_REPAIR | 运营申请 → approve | ✅ 审批中心 | 开关 | OK ● | SourceAssociationRepairSubject |
| A7 | NP | 法币进件平台审核 ONBOARDING_REVIEW | 商户 / 运营提交 → `pay:onboarding:approve` | ✅ 审批中心 | 开关 | OK ● | OnboardingReviewSubject；平台通过 ≠ 渠道通过 |
| A8 | NP | 进件资料草稿确认 | 商户 / 运营 | ✅ | — | 业务（资料定稿） | OnboardingDraftService |
| A9 | NP | 蓝新已建店商户修改 NEWEBPAY_MERCHANT_MODIFY | onboarding manage → approve | ✅ 审批中心 | **敏感两节点**（开关） | OK ● | NewebPayMerchantModificationSubject |
| A10 | SH | 入驻申请受理 + 审核（mch_entity_apply） | 商家 b-app / 运营代填 → `merchant:apply:audit` | ✅ | 否（代填人可自审，只记 sameActor） | OK ●（shop.merchant_entry） | OpsPlatformController · OpsServiceImpl |
| A11 | SH | 门店营业开通复核 | 运营提交 → 运营裁决（同码） | ✅ | 否 | **OK ● 试点已接**（shop.store_opening） | OpsStoreController · StoreOperationalReviewServiceImpl |
| A12 | SH | 店招 / 公告人工审核（mch_store_audit NOTICE） | 商家 → `store:page:audit`（机审命中才进） | ✅（BANNER 图片未落地） | 否 | 队列（量大）/ OK ●（shop.store_sign，二选一待定 Q2） | OpsMerchantAuthController |
| A13 | SH | 服务范围变更审核（SERVICE_AREA，省市区 INCLUDE） | 商家 → 运营 | ✅ | 否 | OK（新增，建议并入 A12 同一流程） | MchStoreAudit · PRD-位置与经营范围 B4 |
| A14 | SH | 商家提报新社区审核 | 商家 → 运营 | ✅ | 否 | OK（新增，低频） | OpsCommunityController |
| A15 | SH | 自建自提点审核 | 商家 / 团长 → `community:pickup:update` | ✅ | 否 | OK（新增，低频） | OpsCommunityController |
| A16 | SH | 资质证照登记 → 运营看证授类目码 | 商家上传 → 运营手工授码 | 🟡 无待审队列 | 否 | OK（新增：上传即送审，通过即授码） | BizMerchantController · OpsQualificationController |
| A17 | SH | 认证标授予 | 运营 | ✅ | 否 | 不审（运营直接操作） | OpsMerchantAuthController |
| A18 | SH | 自营供应商出款账户审核 | 运营录入 → 运营审核 | ✅ | 否 | OK ●（shop.payout_account） | OpsPayoutAccountController |
| A19 | SH | 门店加入 / 变更品牌 | 门店 → 品牌管理人 `brand.change.decide` | ✅ | 否 | OK ●（shop.brand_change，商户空间 S3） | BrandServiceImpl |
| A20 | NP | 商户成员邀请 / 停用 / 恢复 | 商户管理员 / 运营 | ✅ 无审核 | — | 不审（需求只要求实时收回） | MembershipService |

### 2.2 渠道、账户、门店绑定、终端

| # | 系统 | 审核事项 | 发起 → 审核 | 现状 | 两人 | 归属 | 依据 |
|---|---|---|---|---|---|---|---|
| B1 | NP | 渠道账户核验 CHANNEL_ACCOUNT_VERIFY | manage → approve | ✅ 审批中心 | 开关 | OK ● | ChannelAccountVerifySubject；未核验不得启用（C-03） |
| B2 | NP | 恢复渠道账户 CHANNEL_ACCOUNT_RESUME | manage → approve | ✅ 审批中心 | 开关 | OK ● | ChannelAccountResumeSubject |
| B3 | NP | 渠道资格激活 CHANNEL_QUALIFICATION（含测试渠道试运行） | manage → approve | ✅ 审批中心 | **敏感两节点**（开关） | OK ● | QualificationSubject · OpsTestChannelTrialController |
| B4 | NP | 渠道账户转移 CHANNEL_ACCOUNT_TRANSFER | 运营申请 → approve，再执行 | ✅ 审批中心 | 开关 | OK ● | ChannelAccountTransferSubject |
| B5 | NP | 商户认领渠道账户 | 商户上传证明 → 运营 resolve | ✅ 独立 | 否 | OK（新增，建议进审批） | ChannelAccountClaimService |
| B6 | NP | 门店关联变更 STORE_ASSOCIATION_CHANGE | store manage → approve | ✅ 审批中心 | **敏感两节点**（开关） | OK ● | StoreAssociationSubject |
| B7 | NP | 门店渠道账户换绑 STORE_BINDING_REBIND | store-binding manage → approve | ✅ 审批中心 | 开关 | OK ● | StoreBindingRebindSubject；首次绑定、解绑直接执行 |
| B8 | NP | 终端配置核验 | 配置人 → 另一人 verify（须有证据） | ✅ 独立 | **硬** | OK（新增，配置核验类） | TerminalConfigurationService |
| B9 | NP | PayBy POS 配置核验再发布 | 配置人 → 另一人 verify → publish | ✅ 独立 | **硬** | OK（新增） | PosOpsConfigurationService |
| B10 | NP | PayBy POS 沙箱 UAT 验收签核 | 建单 / 记用例人 → 独立 reviewer | ✅ 独立 | **硬** | 业务（验收签核，留在业务） | PosAcceptanceService |
| B11 | NP | POS 退款经理授权码（一次性） | 店长签发 | ✅ | — | 业务（现场授权，不能替代退款复核） | TerminalManagerAuthorizations |
| B12 | NP | 服务器端渠道密钥导入 maker / checker | 部署工具 | ✅ 工具 | 开关 | 不审（运维工具，保留） | PaymentProvisioningRunner |
| B13 | SH | 商家认领已有渠道收款账户（转发 near-pay） | 商家 b-app → near-pay | ✅ | — | 随 B5 | BizChannelAccountClaimController |

### 2.3 开放平台、凭证、密钥

| # | 系统 | 审核事项 | 发起 → 审核 | 现状 | 两人 | 归属 | 依据 |
|---|---|---|---|---|---|---|---|
| C1 | NP | 接入应用启用 / 恢复 OPEN_APP_ENABLE | app manage → approve（须回调已核验） | ✅ 审批中心 | **硬**（登记人不得启用） | OK ● | OpenAppEnableSubject · OpenAppService |
| C2 | NP | 应用回调地址核验 | 设置人 → 另一人 verify | ✅ 独立 | **硬** | OK（新增） | OpenAppService |
| C3 | NP | 开放 API 接入申请审批 | 商户 → 运营 approve / reject | ✅ 独立 | **硬** | OK ●（np.open_app_application） | AppApplicationService |
| C4 | NP | 凭据版本核验（DRAFT → VERIFIED） | credential manage | ✅ 独立 | 否 | 不审（技术核验） | PayCredentialService |
| C5 | NP | 凭据版本启用 CREDENTIAL_ENABLE | manage → approve | ✅ 审批中心 | **敏感两节点**（开关；sec 层录入人 ≠ 启用人） | OK ● | CredentialEnableSubject |
| C6 | SH | 平台密钥启用 | 录入人 → 另一人 | ✅ | **硬**（DUAL_CONTROL） | OK（新增，敏感） | PayAccessOpsFacade |
| C7 | NP | 凭据吊销、应用授权撤销 | 运营 / 商户 | ✅ 无审核 | — | 不审（保护性动作） | OpsCredentialController |

### 2.4 商品、内容、评价（审核队列）

| # | 系统 | 审核事项 | 发起 → 审核 | 现状 | 两人 | 归属 | 依据 |
|---|---|---|---|---|---|---|---|
| D1 | SH | 商品发布审核（AUDITING，开关 `goods.audit` 默认开） | 商家 → `product:sku:audit` | ✅ | 否 | 队列（曾积压 194 件） | OpsGoodsController · MerchantGoodsServiceImpl |
| D2 | SH | 评价审核 | 买家 → `review:review:audit` | ✅ | 否 | 队列 | OpsReviewController |
| D3 | SH | 商家恶意差评申诉裁决 | 商家 → 运营 | ✅ | 否 | 队列 | OpsReviewController |
| D4 | SH | 种草内容审核（含批量通过） | 用户 / 商家 → `content:material:audit` | ✅ | 否 | 队列 | OpsContentController |
| D5 | SH | 商家团审核（开关 `group.audit` 默认关） | 商家 → `group:campaign:audit` | ✅ | 否 | 队列 | OpsGroupController |
| D6 | SH | 求团报价毁约判定 | 运营裁决 | ✅ | 否 | 不审（判定，不是审批） | OpsQuoteController |

### 2.5 营销

| # | 系统 | 审核事项 | 发起 → 审核 | 现状 | 两人 | 归属 | 依据 |
|---|---|---|---|---|---|---|---|
| E1 | SH | 平台活动报名审核（超预算不能通过） | 商家 → 运营 | ✅ | 否 | OK ●（shop.promotion_enrollment） | OpsPromotionController · PlatformActivityServiceImpl |
| E2 | SH | 门店储值活动审批（超上层管控才审） | 门店 → 上层授权人 | ✅ | **硬**（禁止自批） | OK ●（shop.stored_value_activity，商户空间 S3） | StoredValueActivityJournal |

### 2.6 交易、售后、退款、开票

| # | 系统 | 审核事项 | 发起 → 审核 | 现状 | 两人 | 归属 | 依据 |
|---|---|---|---|---|---|---|---|
| F1 | NP+SH | 退款复核（一审 → 二审，命中规则才进；开关 `shop.pay.refund.review` 生产为 false） | 退款申请 → 财务 `pay:refund:review` | ✅ 旧审批表，不进 SY01 | **硬**（固定两人，三人互斥） | OK ●（np.refund_review） | RefundServiceImpl · RefundReviewPolicy · ApprovalServiceImpl；规则无配置接口（AC33） |
| F2 | SH | 售后同意 / 驳回（未备货免审自动退） | 买家 → 商家 | ✅ | — | 业务（买卖协商） | AfterSaleServiceImpl |
| F3 | SH | 平台介入裁决 + 判责 + 出资比例 | 买家上升 → `aftersale:ticket:handle` | ✅ | 否 | 业务（OpsKit 工单类型，另立） | OpsAfterSaleController |
| F4 | SH | 商城退款审核（含 review-quotes） | 商家买方 → 运营 | ✅ | 否 | OK ●（shop.mall_refund） | CommerceOpsMallHttpController |
| F5 | SH | 商城线下付款核款（确认 / 驳回转账凭证） | 商家 → `commerce:mall:offline:review/confirm` | ✅ | 码 | OK（新增，动钱） | CommerceOfflineOpsHttpController |
| F6 | SH | 商户套餐线下付款确认 / 驳回；现金退款出款 | 商家 → 运营 | ✅（运营页、端到端验收未完） | 码 | OK（新增，动钱） | OpsPlanPurchaseController · OpsEntityPlanRefundController |
| F7 | SH | 人工改价：员工提议 → 店主批准 | 店员 → 店主（员工自批额度 0） | ✅ | 否（店主本人提议即生效） | OK ●（shop.manual_price，商户空间 S3） | PricingManualAdjustmentServiceImpl |
| F8 | SH | 外部渠道订单接单 / 拒单；渠道售后决定 | 外部平台 → 商家 | ✅ | — | 业务（买卖） | BizChannelOrderController · BizChannelAfterSaleController |
| F9 | SH | 销项票申请：开具 / 驳回 | 买家 → 运营财务 | ✅（手工开票） | 否 | 业务（开票作业，不是审批） | OpsInvoiceRequestController |
| F10 | NP | 异常交易受控退款 | 运营 | ✅ | — | 随 F1 | OpsOrderController |
| F11 | NP | 人工确认到账（manual-settle，说明 ≥ 4 字） | 运营 compensate | ✅ | 否 | OK（新增，动钱；需求只要求受审计） | OpsOrderController |

### 2.7 资金、结算、对账、积分、储值

| # | 系统 | 审核事项 | 发起 → 审核 | 现状 | 两人 | 归属 | 依据 |
|---|---|---|---|---|---|---|---|
| G1 | NP | 提现审批 | 商户 → 财务 `withdrawal:review` | ✅ 独立，**单人一步** | 否（需求要求高额两人） | OK ●（np.withdrawal） | OpsFundController · WithdrawServiceImpl |
| G2 | SH | 提现审批（stl_withdraw） | — | ⛔ 2026-10-09 撤入口 | — | 删除（S4） | OpsWithdrawController；验收用例 TP-33 / TB-39 已过期 |
| G3 | NP | 出款回执登记（PAID / FAILED） | 财务 payout manage | ✅ 独立 | 否 | OK（新增；PAYOUT 已声明敏感） | OpsFundController |
| G4 | NP+SH | 结算批次风控挂起后放行（超时自动放行） | 风控 HOLD → `finance:settle:execute` | ✅ | 否 | OK ●（shop.settle_release） | SettleBatchServiceImpl · OpsSettleController |
| G5 | SH | 结算批次放款（RECONCILED → RELEASED） | `finance:payout:execute` | ✅ | 码（放行与放款不强制两人） | OK（新增，动钱） | OpsSettleController |
| G6 | SH | 自营应付确认；进项票核验 / 驳回 | 供应商 → `finance:invoice:verify` | ✅ | 否 | OK（新增）/ 业务（票据核验）待定 Q3 | OpsPayableController |
| G7 | SH | 商家结算发票开具 / 驳回 | 商家 → 运营 | ✅ | 否 | 业务（开票作业） | OpsSettleInvoiceController |
| G8 | NP | 对账差异认领后关闭 | 财务认领 → 认领人关闭 | ✅ 独立 | 否（REC-01 要求复核） | OK（新增，补复核） | OpsReconDiffController |
| G9 | NP | 渠道账单差异决定；YitePay VA 认领、差异处理 | 运营 | ✅ 独立 | 否 | 业务（对账作业） | OpsChannelBillController · OpsYitePayVaController |
| G10 | SH | 积分资金池备款 / 提取申请复核 | 运营 → 另一名运营 | ✅ | **硬** | OK ●（shop.points_pool_request） | PoolRequestServiceImpl · PoolWithdrawalServiceImpl |
| G11 | SH | 积分补充计划 | 制单人 → 审批 | ✅ | **硬** | OK（新增，并入 G10） | TopupPlanServiceImpl |
| G12 | SH | 用户积分调账复核 G-ADJUST | 运营 → 另一名财务 | ✅ | **硬** | OK ●（shop.points_adjustment） | PointsAdjustmentServiceImpl |
| G13 | SH | 门店积分账单确认 / 异议 | 运营出单 → 门店财务 | ✅ | — | 业务（对账确认） | BizStorePointsController |
| G14 | SH | 储值清分对账确认、争议、收款确认 | 发行方 ↔ 门店 | ✅ | — | 业务（双方对账） | BizStoredValueSettlementController |
| G15 | SH | 线下储值充值确认（套餐 / 发行方） | 商家自确认 | ✅ | — | 业务（自确认） | BizStoredValuePlanRechargeController |

### 2.8 库存、行业插件

| # | 系统 | 审核事项 | 发起 → 审核 | 现状 | 两人 | 归属 | 依据 |
|---|---|---|---|---|---|---|---|
| H1 | SH | 盘点单提交 → 复核 → 过账 | 录入人 → 复核人 | 🟡 后端有，b-app 未接 approve / reject | 可配（`reviewerMustDiffer`，MT-08） | OK ●（shop.stock_count，商户空间 S3） | CountController · HostCountServiceImpl |
| H2 | SH | 期初对齐确认 | 商家 | ✅ | — | 业务（自确认） | BizStockSyncController |
| H3 | SH·餐饮 | 非资金退餐审批 | 员工 → 有权限者 | ✅ | 否 | OK ●（shop.food_return，商户空间 S3） | BusinessReturnApprovalServiceImpl |
| H4 | SH·餐饮 | 日结签核 | 预结人 → 另一人 | ✅ | **硬**（FNB-I06） | OK（新增，商户空间） | BusinessCloseServiceImpl |
| H5 | SH·餐饮 | 日结例外结转：提议 → 批准 | 财务 A → 财务 B | ✅ | **硬**（FNB-I08 部分） | OK（新增，商户空间） | BusinessCloseServiceImpl |
| H6 | SH·餐饮 | 清台 / 转台的 approvalRef | — | 🟡 只预留字段，传了即拒 | — | OK（随 FNB-A07 实现） | CheckServiceImpl |
| H7 | SH·餐饮 | 离线补录冲突 REVIEW_REQUIRED | 设备 → ? | 🟡 只打标记 | — | OK（随 FNB-G06） | OfflineDraftServiceImpl |
| H8 | SH·美业 | 接待异常申请（PENDING_REVIEW） | 前台 → 店长 | 🟡 没有裁决端点 | — | OK（新增，商户空间） | BizReceptionController · ReceptionCoordinator |

### 2.9 权限、风控、配置、监控

| # | 系统 | 审核事项 | 发起 → 审核 | 现状 | 两人 | 归属 | 依据 |
|---|---|---|---|---|---|---|---|
| I1 | NP | 权限映射启用 PERMISSION_MAPPING_ENABLE | manage → approve | ✅ 审批中心 | 开关 | OK ● | PermissionMappingSubject |
| I2 | NP | 数据委托启用 DATA_SCOPE_ENABLE | manage → approve | ✅ 审批中心 | 开关 | OK ● | DataScopeSubject |
| I3 | NP | 告警规则发布 ALERT_RULE_PUBLISH | manage → approve（需求要求另一人复核） | ✅ 审批中心 | 开关 | OK ● | AlertRulePublishSubject |
| I4 | NP | 告警事件认领、指派、处置、关闭 | 运营 | ✅ | — | 业务（OpsKit 工单类型，另立） | OpsAlertController |
| I5 | SH | 风险事件处置 | 系统 → `risk:event:handle` | ✅ | 否 | 业务（处置作业，可做工单） | OpsRiskController |
| I6 | SH | 黑名单解禁申诉裁决 | 用户 → 运营 | ✅ | 否 | 队列 | OpsRiskController |
| I7 | SH | 免二次验证白名单（理由必填、不能给自己勾） | 运营 | ✅ | 硬（不能操作自己） | 不审（配置，自带防自操作） | OpsPlatformController · OpsServiceImpl |
| I8 | NP | 渠道 / 产品启停；终端激活码、挂失；SoftPOS 绑定；收款入口启停 | 运营 / 店长 | ✅ 无审核 | — | 不审 | OpsChannelController · TerminalMerchantManagement |

---

## 3. 已声明未接 / 仅需求文档（待排期）

| # | 系统 | 事项 | 需求要求 | 现状 | 建议归属 | 依据 |
|---|---|---|---|---|---|---|
| X1 | NP | 调账申请（对账差异 → 调账） | 两人（BM22-F04、BM18-F03） | ⚪ 端点存在，返回 notReady；ADJUSTMENT 已声明 | OK（敏感，两步） | OpsReconDiffController · SensitiveSubjects |
| X2 | NP | 平台佣金规则发布 COMMISSION_RULE_PUBLISH | 两人（BM17-F03） | ⚪ **代码直接生效**，无复核 | OK（敏感） | OpsRateController |
| X3 | NP | 商户费率套餐发布 FEE_PLAN_PUBLISH（OP06） | 两人；通过 ≠ 生效 | ⚪ 无后端（Mock） | OK（敏感） | 业务功能矩阵 BM17-F02 |
| X4 | NP | 渠道成本版本发布（OP05） | 复核发布 | ✅ 直接追加，无审核 | OK | 角色与数据权限.md:83 |
| X5 | NP | 商户费率变更申请（B 端） | 审批单 + 待生效版本 | 📄 | OK | ref/页面交互方案 |
| X6 | NP | 资金路径切换 FUNDS_PATH_SWITCH | 两人 | ⚪ | OK（敏感） | SensitiveSubjects |
| X7 | NP | 出款 PAYOUT 进统一审批 | 两人 | ⚪ | OK（敏感，见 G3） | SensitiveSubjects |
| X8 | NP | 高额提现双人复核 | 两人（核心业务流程.md:272） | 📄（G1 现为单人） | OK（G1 流程加金额条件） | ref/核心业务流程 |
| X9 | NP | 收款银行 / 结算规则变更复核（FI05） | 两人 | 📄 | OK | BM20-F01 · WF-01 |
| X10 | NP | 商户敏感变更 ME04（资料 / 费率 / 风险等级 / 结算） | 两人，结算资料须财务复核 | 📄（Mock） | OK | design-v2/02 · 页面交互方案 |
| X11 | NP | 储值发行、积分受理、加密准入申请审核（ME03 其余三个页签） | 申请人不得自批 | 📄（Mock） | OK | 业务功能矩阵 BM13 / 14 / 16 |
| X12 | NP | 清分异常复核、冲正调整 | 审批后追加凭证 | 📄 | OK | BM19-F04 · BM18-F03 |
| X13 | NP | 储值冻结 / 调整审批；积分储值调整审批 | 审批与执行分开 | 📄 | OK | BM14-F04 · 大支付需求规格 |
| X14 | NP | 风控规则发布（RC01）、风控等级发布（OP07） | 复核 | 📄 | OK | 10-风险合规与审批 |
| X15 | NP | 风险限制新增 / 延期 / 解除（RC04）；名单解除（RC02） | 本人不得审放宽 | 📄 | OK | 10-风险合规与审批 |
| X16 | NP | 合规审查、定期复审（RC05）、整改复核（RC06） | 复核 | 📄 | OK | 10-风险合规与审批 |
| X17 | NP | 争议案件证据提交（RC07） | — | 📄 | 业务（工单） | 10-风险合规与审批 |
| X18 | NP | 审批策略发布（SY02）/ APPROVAL_POLICY_ACTIVATE | 两人治理 | ⚪ notReady | 由 OpsKit 流程配置取代 | OpsApprovalController |
| X19 | NP | 导出复核 | — | 📄 | OK（低优先） | BM03-F04 |
| X20 | NP | 退款复核规则生效 REVIEW_RULE_ACTIVATE；收款商户启用 MERCHANT_ACTIVATE | — | 📄 旧 DDL 注释 | 由 OpsKit 流程条件取代 | ddl-columns.csv |
| X21 | SH | 运营端功能矩阵 §七：A04 实名认证审核 · A08 人工换绑（双人）· **M07 主体信息变更审核（P0）** · M15 解冻 · M17 清退 · M18 注销 · M20 负责人变更（双方确认）· **S03 独立新店审核（P0）** · S04 门店名称 / 地址变更审核 · S08 关店 · S09 门店归属迁移 · P06 费率调整审核 · C02 门店接入渠道审核 · **G06 高风险动作双人复核** · G07 批量审核；「提交人 ≠ 审核人，BD 不持有审核权」 | 草稿待评审（2026-11-16） | ❌ / 🟡（M07 在 near-pay 主体资料侧部分落地 = A1） | OK（逐项立流程） | 运营端-账户商户门店渠道-功能矩阵.md |
| X22 | SH | 需求矩阵-三端：客服退款审批（阈值内）· 多语言文案翻译审核 · 图片审核 · 归因冲突裁决 · 服务改期审批 · 临时自提点 30 天 ≥ 3 次转人工复核 · P-N-2 / 3 / 4 待审通知 | 多数 ⬜ | 📄（极速退已改为按「未备货」免审） | 混合：退款、改期 → OK；图片、翻译 → 队列；待审通知由 OpsKit 待办统一解决 | 需求矩阵-三端.md |
| X23 | SH·餐饮 | FNB-A07 金额阈值与双人审批、禁止自批 · F06 折扣 / 抹零 / 免单 · G06 错单重开与离线补录 · I08 强制关账 · C02 订金没收 · F09 押金破损扣款 · I03 现金收支凭证 · L03 费用减免 · J09 审批审计 | 「审」列，无完成标记 | 📄 | OK（商户空间，按金额条件） | 餐饮系统-需求矩阵 · 后端业务模块功能清单 |
| X24 | SH·美业 | BTY-A03 / A05 能力申请 · B02 门店价格审核 · C02 / C03 / C05 岗位、资格、离职交接 · D04 请假加班 · D05 / F04 改约换人 · E04 / E05 资料访问 · F06 违约收费 · H04 资格资源复核 · H06 完工复核 · N01 售后审核 | 目标职责 | 📄 | OK（商户空间）；N01 → 业务 | 美业系统-多端功能矩阵 |
| X25 | SH | 代客单金额 / 笔数阈值，超阈值二次审批（M6） | 待定 | 📄 | OK（金额条件） | 代客下单-需求梳理 |
| X26 | SH | 进件风控转人工（同证照多主体、结算账户异常） | 草稿 | 📄 | OK | PRD-资金风控 P1 |
| X27 | SH | 美业退余额审批；储值活动发布审批的角色与额度 | 草稿 | 📄 | OK（商户空间） | PRD-美业储值 |
| X28 | SH | 支付运营关键操作两人复核、放款复核 | 草稿 | 🟡（退款、密钥已做；放款未强制两人 = G5） | OK | PRD-支付域 |
| X29 | SH | 改商品类型 traits 走审批 · 订单调整超阈值审批 · 资产调整审批 · Trait 字段审核白名单 | 目标态 | 📄 | OK | docs/v2/05 / 06 / 09 / 12 / 15 |
| X30 | SH | 村级区划补录 → 运营确认 | 菜单注释 | 📄 无端点 | OK（低频） | ops-web lib/nav.ts |

---

## 4. 明确不审核（文档定论）

| 事项 | 依据 |
|---|---|
| 求团报价不审核 | ADR-003 · B端功能清单 |
| 餐饮税率不走平台审核 | 餐饮需求 |
| 渠道目录不审核 | PRD-统一销售渠道接入 |
| 服务范围 EXCLUDE 不审（是否也要审仍待决） | PRD-位置与经营范围 §11 Q3 |
| 商户成员邀请 / 停用 / 恢复、凭据吊销、渠道产品启停等保护性或运维动作 | 见 A20、C7、I8 |

---

## 5. 两人控制现状（「强制提交人 ≠ 审核人」）

| 方式 | 事项 |
|---|---|
| **硬性、不读开关** | 退款复核（F1）· 开放应用启用 / 回调核验 / 接入申请（C1–C3）· 终端与 POS 核验、UAT 签核（B8–B10）· 平台密钥启用（C6）· 积分资金池 / 补充计划 / 调账（G10–G12）· 储值活动审批（E2）· 餐饮日结签核与例外结转（H4、H5） |
| **读 near-pay 单人开关（生产为允许本人）** | near-pay 审批中心 16 类 · 渠道账户核验、换绑、进件、资格、转移 · 密钥导入工具 |
| **只要求两个权限码** | 核款审核（F5）· 套餐确认（F6）· 放行与放款（G4 / G5） |
| **可配置** | 库存盘点 `reviewerMustDiffer`（H1） |
| **不要求** | 入驻审核、门店营业复核、店招、商品、内容、报名、提现等其余各项 |

迁入 OpsKit 后，上述口径统一由**流程上的 `selfApproval` 开关**决定：**一律可以本人审批**（用户 2026-11-23 定，Q1）。原来写死禁止自批的 14 条，迁移时一并改为由流程配置决定，代码里的硬性校验在 S4 清理时删除。

---

## 6. 差距与待决

| # | 问题 | 建议 |
|---|---|---|
| Q1 | 原来硬性禁止自批的 14 条，迁入 OpsKit 后是否仍禁止本人审批 | ✅ **用户 2026-11-23 定：一律可以本人审批**（所有流程 `selfApproval=true`，多步流程也不开「同一人不能审两步」）。需要更严时由运营在流程配置里单独关掉 |
| Q2 | 店招 / 公告 / 服务范围审核（A12、A13）进审批还是作为审核队列 | 进审批（量不大，且有「通过后生效」的执行动作） |
| Q3 | 进项票核验（G6）、开票（F9 / G7）算审批还是作业 | 算作业，不进审批 |
| Q4 | 需求要求两人、代码目前单人或直接生效的：提现（G1 / X8）、佣金规则发布（X2）、渠道成本发布（X4）、对账差异关闭（G8）、放款（G5） | 迁入 OpsKit 时直接在流程上配两步或金额条件，不再改代码 |
| Q5 | 售后平台裁决（F3）、告警处置（I4）、风险事件（I5）、争议案件（X17） | 走 OpsKit **工单**，不走审批，另立方案 |
| Q6 | 过期文档：平台端功能清单、验收用例 TP-33 / TB-39 仍写「提现审批已闭合」；三端角色权限对齐清单仍写「AUDITOR 不存在」 | 随 S4 清理时更新 |

---

## 7. 盘点底稿

本清单由两次只读盘点合成（2026-11-23）：
- **near-pay**：`backend/pay/*`、`ops-web/lib/catalog.ts`；需求来自 `ref/*`、`docs/product/*`、`docs/database/design-v2/10、02`、`docs/delivery/05-BM25-业务审批`、`docs/technical/TDD-单人审批开关与确认审核.md`、`TDD-业务审核接入与人员流转方案.md`
- **ai-shop**：`backend/*`、`backend-plugins/*`、`ops-web`、`b-app`；需求来自 `docs/requirements/*`、`docs/qa/线上验收-用例-*`、`docs/v2/*`

各行的精确行号在盘点报告里，需要时可以按事项编号补进本表。

---

## 8. 审批分级（用户 2026-11-23 定：分 3 级）

所有等级共用的规则：
- **可以本人审批**（`selfApproval=true`），多步流程也允许同一个人连续审批（`crossStepExclusive=false`）。
- 审批通过后由**执行节点**触发业务生效；执行失败停在「生效失败」，可以重试或放弃。
- 驳回即终态，业务回到可编辑，修改后重新送审（生成新单）。
- 对象在审批中被改动：业务自动撤回；执行时版本不一致判 `STALE`。
- 流程都可以在运营端「审批流程」页调整，下表只是**默认流程**，由业务启动时登记（不覆盖已改过的）。

| 等级 | 适用范围 | 默认流程 | 时限 | 申请内容（facts） |
|---|---|---|---|---|
| **一级 · 一般** | 配置启停、资料确认、低频运营审核；出错可以随时撤销 | 提交 → **审核**（1 步）→ 生效 | 每步 72 小时 | 关键字段即可 |
| **二级 · 重要** | 影响商户经营、收款能力、对象归属、对外开放；撤销有成本 | 提交 → **审核**（1 步）→ 〔满足条件时加 **复核**〕→ 生效 | 每步 24 小时 | 必须带「改前 → 改后」 |
| **三级 · 资金与密钥** | 动钱（退款、提现、出款、调账、放款、核款）、动密钥、动资金路径、费率与分润发布 | 提交 → **业务审核** → **财务 / 安全复核**（固定 2 步）→ 生效 | 每步 24 小时 | 必须带金额、账户、规则的「改前 → 改后」 |

流程定义里的写法（以三级为例）：

```jsonc
{ "steps": [
    { "code": "review", "type": "APPROVAL", "name": {"zh": "业务审核"}, "approver": {"kind": "PERM", "value": "<业务审批码>"}, "mode": "ANY", "dueHours": 24 },
    { "code": "recheck", "type": "APPROVAL", "name": {"zh": "财务复核"}, "approver": {"kind": "PERM", "value": "<复核码>"}, "mode": "ANY", "dueHours": 24 },
    { "code": "apply", "type": "ACTION", "name": {"zh": "生效"}, "action": {"code": "<subjectType>.apply", "timeoutMinutes": 10} } ],
  "rules": { "selfApproval": true, "crossStepExclusive": false } }
```

二级的「满足条件时加复核」用步骤的 `when`（如 `{"field": "highRisk", "op": "TRUE"}`），条件字段由业务送审时算好放进 `fields`。

---

## 9. 每个审批的工作流

**列说明**：

| 列 | 含义 |
|---|---|
| 代号 | 流程代号（下划线）；业务对象类型、执行动作用点号，如 `np_credential_enable` / `np.credential_enable` / `np.credential_enable.apply` |
| 送审 | 业务里哪一步触发送审 |
| 审批步骤 | 审批人一律按权限码（`PERM:`） |
| 生效 | 执行节点调用的业务动作 |
| 未通过 | 驳回 / 撤回 / 放弃后业务回到哪个状态 |
| 空间 | 平台 = `ext:PLATFORM`；商户 = `ext:MCH-{no}`（S3） |
| 状态 | ✅ 已接 OpsKit；● 在接入清单；＋ 本次新增建议；○ 业务未实现，接入时一并做 |
| 权限码 | 均已对照 `Perms.java` 与 near-pay 审批类型核实；标「新增码」的今天不存在，接入时登记。「店长权限」「财务」「风控」「合规」「安全」等是角色描述，具体码随业务落地时定 |

### 9.1 一级 · 一般

| # | 代号 | 审批事项 | 送审 | 审批步骤 | 生效 | 未通过 | 空间 | 状态 |
|---|---|---|---|---|---|---|---|---|
| I1 | `np_permission_mapping` | 权限映射启用 | 映射保存后点「提交启用」 | 审核：`pay:permission:approve` | 映射 ACTIVE | 保持 DRAFT | 平台 | ● |
| I2 | `np_data_scope` | 数据委托启用 | 委托保存后提交 | 审核：`pay:data-scope:approve` | 委托 ACTIVE | 保持 DRAFT | 平台 | ● |
| I3 | `np_alert_rule` | 告警规则发布 | 新版本保存后提交 | 审核：`pay:alert-rule:approve` | 规则版本生效 | 版本作废 | 平台 | ● |
| A4 | `np_merchant_resume` | 恢复平台商户 | 停用商户上点「恢复」 | 审核：`pay:merchant:approve` | 商户 ACTIVE | 保持 SUSPENDED | 平台 | ● |
| B2 | `np_channel_account_resume` | 恢复渠道账户 | 停用 / 仅退款账户上点「恢复」 | 审核：`pay:channel-account:approve` | 账户 ACTIVE | 保持原状态 | 平台 | ● |
| A6 | `np_source_repair` | 来源归属修复 | 建修复单即送审 | 审核：`pay:merchant:repair:approve` | 改挂来源关联 | 修复单关闭 | 平台 | ● |
| A13 | `shop_store_service_area` | 服务范围变更（省市区 INCLUDE） | 商家保存范围 | 审核：`store:page:audit` | `mch_service_area` 生效 | 回到草稿，原因回给商家 | 平台 | ＋ |
| A12 | `shop_store_sign` | 店招 / 公告审核（机审命中才送） | 机审命中敏感词 | 审核：`store:page:audit` | 内容上线 | 驳回，原因回给商家 | 平台 | ●（Q2 定进审批） |
| A14 | `shop_community_new` | 商家提报新社区 | 商家提交 | 审核：`community:community:update` | 社区可用 | 驳回 | 平台 | ＋ |
| A15 | `shop_pickup_new` | 自建自提点 | 商家 / 团长提交 | 审核：`community:pickup:update` | 自提点生效 | 驳回 | 平台 | ＋ |
| X30 | `shop_region_village` | 村级区划补录 | 商家补录 | 审核：`community:region:update` | 区划可用 | 驳回 | 平台 | ○ |
| H2 | — | （期初对齐确认、自确认类不进审批） | — | — | — | — | — | — |
| H8 | `shop_beauty_reception_exception` | 美业接待异常 | 前台提交异常 | 审核：店长权限 | 异常处置生效 | 驳回 | 商户 | ＋ |
| X19 | `np_export` | 导出复核 | 申请导出敏感数据 | 审核：`pay:audit:view` | 生成导出文件 | 不导出 | 平台 | ○ |

### 9.2 二级 · 重要

| # | 代号 | 审批事项 | 送审 | 审批步骤 | 条件复核（when） | 生效 | 未通过 | 空间 | 状态 |
|---|---|---|---|---|---|---|---|---|---|
| A1 | `np_merchant_profile` | 商户资料修订 | 修订 SUBMITTED | 审核：`pay:merchant:approve` | 变更结算 / 法人字段 → 复核：`pay:merchant:approve` | 修订 APPROVED，替代旧版 | 修订退回可编辑 | 平台 | ● |
| A7 | `np_onboarding_review` | 法币进件平台审核 | 材料版本提交 | 审核：`pay:onboarding:approve` | — | review APPROVED，可送渠道 | 退回补件 | 平台 | ● |
| A9 | `np_newebpay_modify` | 蓝新已建店商户修改 | 修改单提交 | 审核：`pay:onboarding:approve` | 恒加复核（原敏感两节点） | 调用蓝新修改接口 | 修改单关闭 | 平台 | ● |
| A10 | `shop_merchant_entry` | 入驻申请审核 | 商家 / 代填提交 | 审核：`merchant:apply:audit` | — | 主体 ACTIVE，定服务范围 / 授权码 | 驳回，可重新提交 | 平台 | ● |
| A11 | `shop_store_opening` | 门店营业开通 | 「提交开通」 | 审核：`merchant:apply:audit` | — | `decide(approved)` → ACTIVE | 回到 PENDING_OPEN / REJECTED | 平台 | ✅ 试点 |
| A16 | `shop_qualification` | 资质证照审核并授类目码 | 商家上传证照 | 审核：`merchant:category:grant` | — | 授对应类目码 | 驳回，原因回给商家 | 平台 | ＋ |
| A18 | `shop_payout_account` | 自营供应商出款账户 | 录入账户 | 审核：`finance:payout:execute` | 改账号 → 复核 | 账户 ACTIVE | 驳回 | 平台 | ● |
| A19 | `shop_brand_change` | 门店加入 / 变更品牌 | 门店申请 | 审核：`brand.change.decide` | — | 门店归属变更 | 驳回 | 商户 | ● |
| B1 | `np_channel_account_verify` | 渠道账户核验 | 账户登记后提交核验 | 审核：`pay:channel-account:approve` | — | VERIFIED | REJECTED | 平台 | ● |
| B3 | `np_channel_qualification` | 渠道资格激活 | 资格版本提交 | 审核：`pay:channel-account:approve` | 恒加复核（原敏感两节点） | 资格 ACTIVE | 版本退回 | 平台 | ● |
| B4 | `np_channel_account_transfer` | 渠道账户转移 | 建转移单即送审 | 审核：`pay:channel-account:transfer:approve` | — | 改挂账户归属 | 转移单关闭 | 平台 | ● |
| B5 | `np_channel_account_claim` | 商户认领渠道账户 | 商户上传证明提交 | 审核：`pay:channel-account:approve` | — | 关联到商户 | REJECTED | 平台 | ＋ |
| B6 | `np_store_association` | 门店关联变更 | 登记变更 | 审核：`pay:store:approve` | 恒加复核（原敏感两节点） | 门店改挂商户 | 变更关闭 | 平台 | ● |
| B7 | `np_store_rebind` | 门店渠道账户换绑 | 预览后提交 | 审核：`pay:store-binding:approve` | — | 旧绑定结束、新绑定生效 | 换绑单关闭 | 平台 | ● |
| B8 | `np_terminal_config` | 终端配置核验 | 配置保存并附证据 | 审核：`pay:terminal:verify` | — | VERIFIED，可启用 | 退回配置 | 平台 | ＋ |
| B9 | `np_pos_config` | PayBy POS 配置核验并发布 | 草稿提交 | 审核：`pay:terminal:verify` | — | VERIFIED → publish 激活设备 | 退回草稿 | 平台 | ＋ |
| C1 | `np_open_app_enable` | 接入应用启用 / 恢复 | 应用登记完成（回调已核验） | 审核：`pay:app:approve` | 扩权 → 复核 | 应用 ACTIVE | 保持 DRAFT / SUSPENDED | 平台 | ● |
| C2 | `np_app_callback` | 应用回调地址核验 | 修改回调 | 审核：`pay:app:approve` | — | VERIFIED | UNVERIFIED | 平台 | ＋ |
| C3 | `np_open_app_application` | 开放 API 接入申请 | 商户提交申请 | 审核：`pay:app:approve` | — | 以申请人身份建应用 | 申请驳回 | 平台 | ● |
| E1 | `shop_promotion_enrollment` | 平台活动报名 | 商家报名 | 审核：`marketing:campaign:update` | 占用预算 ≥ 阈值 → 复核 | 占预算、APPROVED（超预算 → 生效失败） | 驳回 | 平台 | ● |
| E2 | `shop_stored_value_activity` | 门店储值活动 | 超上层管控时提交 | 审核：`sv.brand.activity.approve` | — | 活动发布 | 退回 | 商户 | ● |
| F7 | `shop_manual_price` | 人工改价 | 店员提议 | 审核：`biz:finance`（店主） | 改价幅度超阈值 → 复核 | 改价生效 | 提议作废 | 商户 | ● |
| H1 | `shop_stock_count` | 盘点过账 | 录入人提交 | 审核：店长权限 | 差异金额超阈值 → 复核 | 过账（批准版本 = 内容版本） | 退回重盘 | 商户 | ●（b-app 需补入口） |
| H3 | `shop_food_return` | 餐饮非资金退餐 | 员工提交 | 审核：`biz:aftersale` | — | 厨房处置、应收调整 | 驳回 | 商户 | ● |
| H4 | `shop_food_day_close` | 餐饮日结签核 | 预结完成 | 审核：店长 / 财务 | — | 正式版本锁定 | 退回预结 | 商户 | ＋ |
| H5 | `shop_food_carry_over` | 日结例外结转 | 财务提议 | 审核：财务 | — | 结转到次日 | 驳回 | 商户 | ＋ |
| X4 | `np_channel_cost` | 渠道成本版本发布 | 新版本保存 | 审核：`pay:rate:approve`（新增码） | — | 版本生效 | 版本作废 | 平台 | ○（今天直接生效） |
| X10 | `np_merchant_change` | 商户敏感变更 ME04 | 变更单提交 | 审核：`pay:merchant:approve` | 结算资料变更 → 财务复核 | 待执行 → 生效 | 关闭 | 平台 | ○ |
| X11 | `np_special_admission` | 储值发行 / 积分受理 / 加密准入申请 | 商户申请 | 审核：资质审核权限 | — | 专项启用 | 驳回 | 平台 | ○ |
| X14 | `np_risk_rule` | 风控规则 / 风控等级发布 | 新版本提交 | 审核：风控权限 | 放宽类变更 → 复核 | 版本生效 | 作废 | 平台 | ○ |
| X15 | `np_risk_limit` | 风险限制新增 / 延期 / 解除；名单解除 | 风控申请 | 审核：风控权限 | 解除 / 放宽 → 复核 | 限制生效 / 解除 | 关闭 | 平台 | ○ |
| X16 | `np_compliance_review` | 合规审查、复审、整改复核 | 合规发起 | 审核：合规权限 | — | 结论生效（≠ 解除限制） | 退回 | 平台 | ○ |
| X21 | `shop_*` | 运营端功能矩阵 §七（A04、M07、S03、S04、S08、S09、M15、M17、M18、M20、C02 等） | 各自变更提交 | 审核：`merchant:apply:audit` 等 | 「G06 高风险动作」→ 复核 | 各自生效 | 驳回 | 平台 | ○ 逐项立流程 |
| X22 | `shop_service_reschedule` 等 | 服务改期、客服退款（阈值内）、临时自提点转人工复核 | 各自触发 | 审核：对应权限 | — | 各自生效 | 驳回 | 平台 / 商户 | ○ |
| X24 | `shop_beauty_*` | 美业：能力申请、门店价格、岗位资格、离职交接、请假加班、改约换人、资料访问、违约收费、资源复核、完工复核 | 各自触发 | 审核：店长 / 品牌权限 | — | 各自生效 | 驳回 | 商户 | ○ |
| X29 | `shop_goods_traits` 等 | 改商品类型 traits、订单调整超阈值 | 各自触发 | 审核：对应权限 | 超阈值 → 复核 | 各自生效 | 驳回 | 平台 | ○ |

### 9.3 三级 · 资金与密钥

| # | 代号 | 审批事项 | 送审 | 业务审核 → 复核 | 生效 | 未通过 | 空间 | 状态 |
|---|---|---|---|---|---|---|---|---|
| F1 | `np_refund_review` | 退款复核 | 退款命中复核规则（单笔额、日累计、比例、超天数，规则改为流程 `when`） | `pay:refund:review` → `pay:refund:review`（财务） | 退款派发 | 释放预占、退款关闭 | 平台 | ●（原旧审批表） |
| G1 | `np_withdrawal` | 提现审批 | 商户申请提现 | `pay:withdrawal:review` → 财务复核 | APPROVED，进出款 | 驳回（原因必填） | 平台 | ●（今天单人，按需求补复核） |
| G3 | `np_payout` | 出款回执登记 | 财务登记 PAID / FAILED | `pay:payout:manage` → 财务复核 | 结算单置已付 / 回退 | 回到待登记 | 平台 | ＋ |
| G4 | `shop_settle_release` | 结算批次放行（风控挂起） | 风控 HOLD | `finance:settle:execute` → 风控复核 | RECONCILED，可放款 | 继续挂起 | 平台 | ● |
| G5 | `shop_settle_payout` | 结算批次放款 | 批次 RECONCILED | `finance:payout:execute` → 财务复核 | 生成放款记录 | 留在 RECONCILED | 平台 | ＋ |
| G8 | `np_recon_close` | 对账差异关闭 | 认领人提交关闭 | `pay:recon-diff:manage` → 财务复核 | 差异 IGNORED / RESOLVED | 回到认领 | 平台 | ＋ |
| X1 | `np_adjustment` | 调账（对账差异 → 调账） | 差异单发起调账 | 财务 → 财务复核 | 追加反向凭证 | 关闭 | 平台 | ○ |
| X12 | `np_clearing_adjust` | 清分异常复核、冲正调整 | 财务发起 | 财务 → 财务复核 | 追加凭证 | 关闭 | 平台 | ○ |
| X13 | `np_sv_adjust` | 储值冻结 / 调整；积分储值调整 | 财务发起 | 财务 → 财务复核 | 冻结 / 调整入账 | 关闭 | 平台 | ○ |
| F4 | `shop_mall_refund` | 商城退款 | 商家买方申请 | `commerce:refund:review` → 财务复核 | 退款 | 驳回 | 平台 | ● |
| F5 | `shop_mall_offline_payment` | 商城线下付款核款 | 商家上传转账凭证 | `commerce:mall:offline:review` → `commerce:mall:offline:confirm` | 入账 | 驳回凭证 | 平台 | ＋ |
| F6 | `shop_plan_offline_payment` | 套餐线下付款确认；现金退款出款 | 商家付款 / 申请退款 | 运营审核 → 财务复核 | 套餐生效 / 出款 | 驳回 | 平台 | ＋ |
| F11 | `np_manual_settle` | 人工确认到账 | 运营核实后提交 | `pay:order:compensate` → 财务复核 | 补记入账 | 不入账 | 平台 | ＋ |
| G10 | `shop_points_pool_request` | 积分资金池备款 / 提取 | 运营提交（须有资金证据） | `finance:settle:execute` → `finance:payout:execute` | 过账 | 驳回 | 平台 | ● |
| G11 | `shop_points_topup_plan` | 积分补充计划 | 制单人提交 | 同 G10 | 生成备款 | 驳回 | 平台 | ＋（并入 G10 流程） |
| G12 | `shop_points_adjustment` | 用户积分调账 | 运营提交 | `finance:settle:execute` → `finance:payout:execute` | 统一入账 | 驳回 | 平台 | ● |
| C5 | `np_credential_enable` | 凭据版本启用 | 版本 VERIFIED 后提交 | `pay:credential:approve` → 安全复核 | ACTIVE，旧版 ROTATING | 保持 VERIFIED | 平台 | ● |
| C6 | `shop_platform_key_enable` | 平台密钥启用 | 录入密钥 | 安全审核 → 安全复核 | 密钥生效 | 作废 | 平台 | ＋ |
| X2 | `np_commission_rule` | 平台佣金规则发布 | 新版本保存 | `pay:rate:approve`（新增码） → 财务复核 | 版本按预约时间生效 | 作废 | 平台 | ○（今天直接生效） |
| X3 | `np_fee_plan` | 商户费率套餐发布 | 新套餐保存 | `pay:rate:approve`（新增码） → 财务复核 | 待生效版本 | 作废 | 平台 | ○ |
| X5 | `np_merchant_fee_change` | 商户费率变更申请 | 商户 / 运营申请 | `pay:rate:approve`（新增码） → 财务复核 | 待生效版本 | 驳回 | 平台 | ○ |
| X6 | `np_funds_path_switch` | 资金路径切换 | 运营申请 | 运营 → 财务复核 | 资金路径生效 | 关闭 | 平台 | ○ |
| X9 | `np_settlement_account` | 收款银行 / 结算规则变更 | 商户 / 财务申请 | 财务 → 财务复核 | 新规则版本 | 驳回 | 平台 | ○ |
| X23 | `shop_food_*` | 餐饮：折扣 / 抹零 / 免单、订金没收、押金扣款、现金收支凭证、费用减免、强制关账 | 超阈值时 | 店长 → 财务复核 | 各自生效 | 驳回 | 商户 | ○ |
| X25 | `shop_proxy_order` | 代客单超阈值二次审批 | 超金额 / 笔数阈值 | 店长 → 财务复核 | 下单生效 | 取消 | 商户 | ○ |
| X26 | `np_onboarding_risk` | 进件风控转人工 | 命中同证照多主体、结算账户异常 | 风控 → 风控复核 | 放行进件 | 拒绝进件 | 平台 | ○ |
| X27 | `shop_beauty_sv_refund` | 美业退余额 | 顾客申请 | 店长 → 财务复核 | 退余额 | 驳回 | 商户 | ○ |

### 9.4 不进审批的（留在业务、走工单或审核队列）

| 去向 | 事项 |
|---|---|
| **审核队列**（暂不进） | D1 商品发布 · D2 评价 · D3 差评申诉 · D4 种草内容 · D5 商家团 · I6 黑名单申诉 · X22 图片 / 多语言翻译审核 |
| **OpsKit 工单**（另立方案） | F3 售后平台裁决 · I4 告警事件处置 · I5 风险事件处置 · X17 争议案件 · B10 POS UAT 验收签核 |
| **业务协商 / 对账 / 自确认** | F2 售后同意驳回 · F8 渠道订单与售后 · G13 门店积分账单 · G14 储值清分对账 · G15 线下储值充值确认 · H2 期初对齐 · A8 进件草稿确认 · F9 / G7 开票 · G6 进项票核验 · G9 渠道账单与 VA 差异 · B11 POS 退款经理授权码 |
| **不审** | A5 · A17 · A20 · B12 · C4 · C7 · D6 · I7 · I8 |
| **废弃** | G2 `stl_withdraw` · A3「确认审核」一键通过（被本人审批取代） |
