# 附录 · 支付相关入口盘点（TDD-支付三端经网关透传与权限归属 §5 阶段 0）

生成：2026-11-22，ai-shop `origin/main` `1cc4fef64`，扫描 `backend/shop-gateway/.../portal/**/*Controller.java`。
口径：凡是依赖 `payclient` / `pay.client` / `pay.*` 服务，或含 `/biz/pay/v2` 转发器的 controller 都列入。

**第 1 部分的形态是机器初判；第 2 部分是 2026-11-22 逐个核对后的结论**（结论写在脚本的 `CONCLUSIONS` 里，代码变了重跑脚本，新出现的 C1 会标「未定」）。机器初判的判法：
- **B 转发**：含转发器、路径在 `/biz/pay/v2` 下。透传后整类下线。
- **C2 初判**：除了支付客户端，还注入了 ai-shop 进程内的服务（`pay.*Service`、商家 / 交易 / 平台域的 Service、Port、Mapper）。按原则仍由 ai-shop 判，原样保留。
- **C1 初判**：只依赖支付客户端（`payclient.*AppService`、`pay.client.*`），像是薄转调。要逐个确认三件事：near-pay 有没有对应的公开接口；`standalone` 形态下实际调的是不是远程；ai-shop 有没有在返回前拼自己的数据。

判权码一栏是 ai-shop 现在 `@PreAuthorize` 里的码，`Perms.X` 写常量名。迁到透传后，near-pay 的 `@payPerm` 要用等价的 `pay:` 码，那时同步登记到 `Perms` 与角色授予。

## 1. 机器盘点

「standalone 下」一栏：v2 的形态（`shop.pay.deployment=standalone`）下支付客户端实际走哪条路。**远程** = 经 near-pay 内部接口（下一栏是这个 controller 所用支付客户端**整个**调到的地址，去掉路径变量后归并，×N 为条数）；**进程内** = 只有同进程实现，业务在 ai-shop 进程里跑。

| 端 | 形态 | Controller | 接口数 | 判权码（ai-shop 现状） | standalone 下 | 所用支付客户端调到的 near-pay 内部地址 | 依赖 |
|---|---|---|---|---|---|---|---|
| B端 | B 转发 | `BizChannelAccountClaimController` | 2 | biz:finance | 转发 near-pay `/biz/pay/v2` | — |  |
| B端 | B 转发 | `BizPayMerchantChannelController` | 1 | （无注解） | 转发 near-pay `/biz/pay/v2` | — | BizOnboardingForwarder, PayBizOnboardingRelay |
| B端 | B 转发 | `BizPayMerchantController` | 4 | （无注解） | 转发 near-pay `/biz/pay/v2` | — | BizOnboardingForwarder, PayBizOnboardingRelay |
| B端 | B 转发 | `BizPayOnboardingController` | 15 | （无注解） | 转发 near-pay `/biz/pay/v2` | — | BizOnboardingForwarder, PayBizOnboardingRelay |
| B端 | C1 初判 | `BizEntitlementIssueController` | 2 | ENT_READ | 远程 | `/internal/pay/entitlement/batch/create`, `/internal/pay/entitlement/batch/get`, `/internal/pay/entitlement/batch/resume` | EntitlementBatchAppService |
| B端 | C1 初判 | `BizEntitlementTemplateController` | 6 | ENT_ISSUE_MANAGE; ENT_TEMPLA; ENT_TEMPLATE_WRITE | 远程 | `/internal/pay/entitlement/management/create`, `/internal/pay/entitlement/management/draft`, `/internal/pay/entitlement/management/get`, `/internal/pay/entitlement/management/issue-policy`, `/internal/pay/entitlement/management/list`, `/internal/pay/entitlement/management/publish`, `/internal/pay/entitlement/management/status` | EntitlementManagementAppService |
| B端 | C1 初判 | `BizPaymentController` | 23 | PAY_APP_GRANT; PAY_COLLECT; PAY_ORDER_VIEW; PAY_QR; PAY_REFUND; PAY_REFUND, PAY_STATEMENT; PAY_STATEMENT; PAY_STORE_METH | 远程 | `/internal/pay/v1/biz/entities/channel-accounts`, `/internal/pay/v1/biz/stores/app-grants`×3, `/internal/pay/v1/biz/stores/collect-status`, `/internal/pay/v1/biz/stores/collection-methods`×2, `/internal/pay/v1/biz/stores/collections`, `/internal/pay/v1/biz/stores/method-preferences`×2, `/internal/pay/v1/biz/stores/order-receipts`, `/internal/pay/v1/biz/stores/payment-links`×3, `/internal/pay/v1/biz/stores/payment-orders`×5, `/internal/pay/v1/biz/stores/qr-codes`×3, `/internal/pay/v1/biz/stores/refunds`×2, `/internal/pay/v1/biz/stores/statement` | PayBizInternalApi, StoreMethods |
| B端 | C1 初判 | `BizReceivableController` | 1 | （无注解） | 进程内 | — | ReceivableSummaryService, Summary |
| B端 | C1 初判 | `BizStoreFeatureController` | 2 | perm.memberOfSelectedStore#storeNo | 远程 | `/internal/pay/v1/biz/entities/channel-accounts`, `/internal/pay/v1/biz/stores/app-grants`×3, `/internal/pay/v1/biz/stores/collect-status`, `/internal/pay/v1/biz/stores/collection-methods`×2, `/internal/pay/v1/biz/stores/collections`, `/internal/pay/v1/biz/stores/method-preferences`×2, `/internal/pay/v1/biz/stores/order-receipts`, `/internal/pay/v1/biz/stores/payment-links`×3, `/internal/pay/v1/biz/stores/payment-orders`×5, `/internal/pay/v1/biz/stores/qr-codes`×3, `/internal/pay/v1/biz/stores/refunds`×2, `/internal/pay/v1/biz/stores/statement` | BizPaymentAppService |
| B端 | C1 初判 | `BizStoredValueGovernanceCapabilityController` | 1 | managementAccess.authenticated | 远程 | `/internal/pay/stored-value/policies/capability`, `/internal/pay/stored-value/policies/configure`, `/internal/pay/stored-value/policies/evaluate`, `/internal/pay/stored-value/policies/list`, `/internal/pay/stored-value/policies/locate`, `/internal/pay/stored-value/policies/plan-binding` | StoredValuePolicyAppService |
| B端 | C1 初判 | `BizStoredValueReportController` | 5 | managementAccess.authenticated | 远程 | `/internal/pay/stored-value/finance/activities`, `/internal/pay/stored-value/finance/adjust`, `/internal/pay/stored-value/finance/bills`, `/internal/pay/stored-value/finance/capabilities`, `/internal/pay/stored-value/finance/confirm`, `/internal/pay/stored-value/finance/detail`, `/internal/pay/stored-value/finance/directory`, `/internal/pay/stored-value/finance/dispute`, `/internal/pay/stored-value/finance/events`, `/internal/pay/stored-value/finance/generate`, `/internal/pay/stored-value/finance/items`, `/internal/pay/stored-value/finance/local-issuers`, `/internal/pay/stored-value/finance/locate`, `/internal/pay/stored-value/finance/payments`, `/internal/pay/stored-value/finance/rebuild-summary`, `/internal/pay/stored-value/finance/reconcile`, `/internal/pay/stored-value/finance/register`, `/internal/pay/stored-value/finance/resolve-dispute`, `/internal/pay/stored-value/finance/resolve-payment`, `/internal/pay/stored-value/finance/stores`, `/internal/pay/stored-value/finance/summary`, `/internal/pay/stored-value/finance/void-payment` | StoredValueFinanceAppService |
| B端 | C1 初判 | `BizStoredValueSettlementController` | 18 | managementAccess.authenticated | 远程 | `/internal/pay/stored-value/finance/activities`, `/internal/pay/stored-value/finance/adjust`, `/internal/pay/stored-value/finance/bills`, `/internal/pay/stored-value/finance/capabilities`, `/internal/pay/stored-value/finance/confirm`, `/internal/pay/stored-value/finance/detail`, `/internal/pay/stored-value/finance/directory`, `/internal/pay/stored-value/finance/dispute`, `/internal/pay/stored-value/finance/events`, `/internal/pay/stored-value/finance/generate`, `/internal/pay/stored-value/finance/items`, `/internal/pay/stored-value/finance/local-issuers`, `/internal/pay/stored-value/finance/locate`, `/internal/pay/stored-value/finance/payments`, `/internal/pay/stored-value/finance/rebuild-summary`, `/internal/pay/stored-value/finance/reconcile`, `/internal/pay/stored-value/finance/register`, `/internal/pay/stored-value/finance/resolve-dispute`, `/internal/pay/stored-value/finance/resolve-payment`, `/internal/pay/stored-value/finance/stores`, `/internal/pay/stored-value/finance/summary`, `/internal/pay/stored-value/finance/void-payment` | StoredValueFinanceAppService |
| B端 | C2 初判 | `BizEntitlementUseController` | 4 | （无注解） | 远程 | `/internal/pay/entitlement/code/invalidate`, `/internal/pay/entitlement/code/issue`, `/internal/pay/entitlement/code/resolve` | EntitlementCodeAppService, EntitlementUsePort |
| B端 | C2 初判 | `BizEntityProfileController` | 11 | perm.merchantAccount | — | — | EntityProfileRemoteCalls, PayEntityProfileApi, MerchantEntityService |
| B端 | C2 初判 | `BizEntityRecognitionController` | 2 | perm.merchantAccount | — | — | EntityProfileRemoteCalls, PayEntityProfileApi, MerchantEntityService |
| B端 | C2 初判 | `BizPayServiceController` | 2 | （无注解） | — | — | PayServiceApplyRequests, PaySourceLinkApi, MerchantEntityService |
| B端 | C2 初判 | `BizSettleController` | 11 | FINANCE | 进程内 | — | BizSettleAppService, SettleService |
| B端 | C2 初判 | `BizStorePointsAgreementController` | 4 | FINANCE | — | — | PointsAdministrationService, AgreementService, MerchantQueryPort |
| B端 | C2 初判 | `BizStorePointsController` | 8 | FINANCE | 进程内 | — | BizPointsStatementService, PointsAdministrationService, PointsInspectionService |
| B端 | C2 初判 | `BizStoredValueController` | 11 | SV_ACCOUNT_READ; SV_CONSUME_CREATE; SV_R; SV_RECHARGE_CREATE | 远程 | `/internal/pay/stored-value/cancel`, `/internal/pay/stored-value/create`, `/internal/pay/stored-value/credit`, `/internal/pay/stored-value/query/balance`, `/internal/pay/stored-value/query/consumptions`, `/internal/pay/stored-value/query/recharge`, `/internal/pay/stored-value/query/stores`, `/internal/pay/stored-value/receive`, `/internal/pay/stored-value/submit` | StoredValueRechargeAppService, StoredValueExecutionScopePort |
| B端 | C2 初判 | `BizStoredValueIssuerController` | 11 | managementAccess.authenticated | 远程 | `/internal/pay/stored-value/issuers/configure`, `/internal/pay/stored-value/issuers/find`, `/internal/pay/stored-value/issuers/prepare-store` | StoredValueIssuerAppService, IssuerAuthorityService |
| B端 | C2 初判 | `BizStoredValueMerchantQueryController` | 4 | SV_ACCOUNT_READ | 远程 | `/internal/pay/stored-value/merchant/account`, `/internal/pay/stored-value/merchant/directory`, `/internal/pay/stored-value/merchant/record`, `/internal/pay/stored-value/merchant/records` | StoredValueMerchantQueryAppService, StoredValueCustomerProfilePort |
| B端 | C2 初判 | `BizStoredValuePlanController` | 5 | SV_PLAN_MANAGE; SV_PLAN_READ | 远程 | `/internal/pay/stored-value/plans/create`, `/internal/pay/stored-value/plans/get`, `/internal/pay/stored-value/plans/list`, `/internal/pay/stored-value/plans/status` | StoredValuePlanAppService, MarketPort |
| B端 | C2 初判 | `BizStoredValuePlanRechargeController` | 8 | SV_; SV_RECHARGE_CREATE | 远程 | `/internal/pay/stored-value/cancel`, `/internal/pay/stored-value/create`, `/internal/pay/stored-value/credit`, `/internal/pay/stored-value/customers/bind`, `/internal/pay/stored-value/plan-recharges/cancel-creation`, `/internal/pay/stored-value/plan-recharges/confirm-offline`, `/internal/pay/stored-value/plan-recharges/create`, `/internal/pay/stored-value/plan-recharges/query`, `/internal/pay/stored-value/plan-recharges/recover`, `/internal/pay/stored-value/plan-recharges/replay`, `/internal/pay/stored-value/query/balance`, `/internal/pay/stored-value/query/consumptions`, `/internal/pay/stored-value/query/recharge`, `/internal/pay/stored-value/query/stores`, `/internal/pay/stored-value/receive`, `/internal/pay/stored-value/submit` | StoredValuePlanRechargeAppService, StoredValueRechargeAppService, CustomerIdentityPort |
| B端 | C2 初判 | `BizStoredValuePolicyController` | 6 | managementAccess.authenticated | 远程 | `/internal/pay/stored-value/policies/capability`, `/internal/pay/stored-value/policies/configure`, `/internal/pay/stored-value/policies/evaluate`, `/internal/pay/stored-value/policies/list`, `/internal/pay/stored-value/policies/locate`, `/internal/pay/stored-value/policies/plan-binding` | StoredValuePolicyAppService, StoredValuePolicyAuthorityService |
| B端 | C2 初判 | `BizStoredValueWalletController` | 2 | （无注解） | 远程 | `/internal/pay/stored-value/wallets/accounts`, `/internal/pay/stored-value/wallets/records`, `/internal/pay/stored-value/wallets/select` | PayStoredValueWalletRequests, StoredValueWalletAppService, StoredValueCustomerProfilePort |
| C端 | C1 初判 | `MpBuyerPointsController` | 2 | （无注解） | 进程内 | — | BuyerPointsAppService |
| C端 | C2 初判 | `MpEntitlementController` | 10 | （无注解） | 远程 | `/internal/pay/entitlement/code/invalidate`, `/internal/pay/entitlement/code/issue`, `/internal/pay/entitlement/code/resolve` | EntitlementCodeAppService, EntitlementQueryPort |
| C端 | C2 初判 | `MpStoredValueController` | 6 | （无注解） | 远程 | `/internal/pay/stored-value/cancel`, `/internal/pay/stored-value/consumer/issuer`, `/internal/pay/stored-value/consumer/issuer-records`, `/internal/pay/stored-value/consumer/issuers`, `/internal/pay/stored-value/consumer/locate-issuer`, `/internal/pay/stored-value/consumer/notification-fact`, `/internal/pay/stored-value/consumer/records`, `/internal/pay/stored-value/consumer/store`, `/internal/pay/stored-value/create`, `/internal/pay/stored-value/credit`, `/internal/pay/stored-value/query/balance`, `/internal/pay/stored-value/query/consumptions`, `/internal/pay/stored-value/query/recharge`, `/internal/pay/stored-value/query/stores`, `/internal/pay/stored-value/receive`, `/internal/pay/stored-value/submit` | StoredValueRechargeAppService, StoredValueConsumerQueryAppService, MarketPort |
| C端 | C2 初判 | `OrderPointsEarningController` | 2 | ORDER_VIEW | 进程内 | — | OrderPointsEarningViewService, OrderService |
| 运营 | C1 初判 | `OpsBankFlowController` | 1 | FINANCE_PAYOUT_EXECUTE | 进程内 | — | OpsBankFlowAppService, ImportResultVO |
| 运营 | C1 初判 | `OpsEntitlementController` | 7 | ENT_EXCEPTION_READ; ENT_EXCEPTION_RECOVER; ENT_EXCEPTION_VERIFY | 远程 | `/internal/pay/entitlement/exceptions/actions`, `/internal/pay/entitlement/exceptions/dead-letters`, `/internal/pay/entitlement/exceptions/get`, `/internal/pay/entitlement/exceptions/list`, `/internal/pay/entitlement/exceptions/list-scoped`, `/internal/pay/entitlement/exceptions/query-source`, `/internal/pay/entitlement/exceptions/retry-event`, `/internal/pay/entitlement/exceptions/retry-grant` | EntitlementExceptionAppService |
| 运营 | C1 初判 | `OpsEntitlementLifecycleController` | 2 | ENT_EXCEPTION_READ; ENT_FREEZE | 远程 | `/internal/pay/entitlement/lifecycle/expire`, `/internal/pay/entitlement/lifecycle/expiring-soon`, `/internal/pay/entitlement/lifecycle/freeze`, `/internal/pay/entitlement/lifecycle/state` | EntitlementLifecycleAppService |
| 运营 | C1 初判 | `OpsEntitlementReconciliationController` | 4 | ENT_EXCEPTION_READ; ENT_EXCEPTION_VERIFY | 远程 | `/internal/pay/entitlement/reconciliations/check`, `/internal/pay/entitlement/reconciliations/differences`, `/internal/pay/entitlement/reconciliations/get`, `/internal/pay/entitlement/reconciliations/list` | EntitlementReconciliationAppService |
| 运营 | C1 初判 | `OpsFeeRuleController` | 3 | FINANCE_RATE_READ; FINANCE_RATE_UPDATE | 远程 | `/internal/pay/fee-rules`, `/internal/pay/fee-rules/effective` | OpsFeeRuleAppService |
| 运营 | C1 初判 | `OpsOrderPointsIntegrationController` | 7 | FINANCE_SETTLE_EXECUTE; FINANCE_SETTLE_READ | 进程内 | — | OrderPointsIntegrationAppService |
| 运营 | C1 初判 | `OpsPayChannelController` | 3 | FINANCE_RATE_READ; FINANCE_RATE_UPDATE | 进程内 | — | OpsPayChannelAppService, ChannelVO |
| 运营 | C1 初判 | `OpsPayServiceController` | 3 | MERCHANT_PAY_APPLY | 进程内 | — | PayServiceApplyRequests, PayServiceApplicationService |
| 运营 | C1 初判 | `OpsPayServiceOnboardingController` | 1 | （无注解） | 进程内 | — | PayServiceOnboardingQueryService |
| 运营 | C1 初判 | `OpsPayableController` | 9 | FINANCE_INVOICE_READ; FINANCE_INVOICE_VERIFY; FINANCE_PAYOUT_EXECUTE; FINANCE_SETTLE_EXECUTE; FINANCE_SETTLE_READ | 进程内 | — | OpsPayableAppService |
| 运营 | C1 初判 | `OpsPaymentController` | 21 | PAY_APP_CREDENTIAL_REVOKE; PAY_APP_MANAGE; PAY_LOG_VIEW; PAY_NOTIFY_RESEND; PAY_ORDER_MANAGE; PAY_ORDER_VIEW; PAY_QR_MAN | 远程 | `/internal/pay/v1/ops/apps`, `/internal/pay/v1/ops/apps/credentials`×2, `/internal/pay/v1/ops/apps/grants`, `/internal/pay/v1/ops/exceptions`, `/internal/pay/v1/ops/exceptions/ignore`, `/internal/pay/v1/ops/exceptions/refund`, `/internal/pay/v1/ops/notifies`, `/internal/pay/v1/ops/notifies/resend`, `/internal/pay/v1/ops/orders`×2, `/internal/pay/v1/ops/platform-keys`, `/internal/pay/v1/ops/platform-keys/versions`×3, `/internal/pay/v1/ops/qr-codes`, `/internal/pay/v1/ops/qr-codes/disabled`, `/internal/pay/v1/ops/refunds`, `/internal/pay/v1/ops/refunds/review`, `/internal/pay/v1/ops/request-logs`×2 | OpsPaymentAppService |
| 运营 | C1 初判 | `OpsPointsController` | 2 | FINANCE_SETTLE_READ | 进程内 | — | PointsOpsAppService |
| 运营 | C1 初判 | `OpsPointsInspectionController` | 14 | FINANCE_RECON_READ; FINANCE_RECON_RESOLVE; FINANCE_SETTLE_EXECUTE; FINANCE_SETTLE_READ | 进程内 | — | PointsInspectionAppService |
| 运营 | C1 初判 | `OpsPointsPolicyController` | 11 | FINANCE_SETTLE_EXECUTE; FINANCE_SETTLE_READ | 进程内 | — | PointsConfigurationAppService |
| 运营 | C1 初判 | `OpsPointsTreasuryController` | 9 | FINANCE_PAYOUT_EXECUTE; FINANCE_SETTLE_EXECUTE; FINANCE_SETTLE_READ | 进程内 | — | PointsTreasuryAppService |
| 运营 | C1 初判 | `OpsPointsWorkbenchController` | 10 | FINANCE_PAYOUT_EXECUTE; FINANCE_SETTLE_EXECUTE; FINANCE_SETTLE_READ | 进程内 | — | PointsWorkbenchAppService |
| 运营 | C1 初判 | `OpsSettleStatsController` | 1 | FINANCE_SETTLE_READ | 进程内 | — | OpsSettleStatsAppService, StatRowVO |
| 运营 | C1 初判 | `OpsWithdrawController` | 2 | FINANCE_INVOICE_READ; FINANCE_INVOICE_VERIFY | 进程内 | — | OpsWithdrawAppService |
| 运营 | C2 初判 | `OpsChannelMessageController` | 2 | FINANCE_RECON_READ | — | — | ChannelMessageQueryService |
| 运营 | C2 初判 | `OpsPayoutController` | 3 | FINANCE_PAYOUT_EXECUTE; FINANCE_SETTLE_READ | 进程内 | — | OpsPayoutAppService, PayoutService |
| 运营 | C2 初判 | `OpsPayoutListController` | 1 | FINANCE_PAYOUT_EXECUTE | 进程内 | — | OpsPayoutListAppService, PayoutListVO, AuditLogPort |
| 运营 | C2 初判 | `OpsPointsAdjustmentController` | 3 | FINANCE_PAYOUT_EXECUTE; FINANCE_SETTLE_EXECUTE; FINANCE_SETTLE_READ | — | — | PointsOpsAccess, PointsOpsErrors, PointsAdjustmentService, UserQueryPort |
| 运营 | C2 初判 | `OpsPointsEarningRulesController` | 6 | FINANCE_SETTLE_EXECUTE; FINANCE_SETTLE_READ | — | — | PointsOpsAccess, PointsOpsErrors, PointsAdministrationService, PointsEarningRulePort |
| 运营 | C2 初判 | `OpsReconController` | 5 | FINANCE_RECON_READ; FINANCE_RECON_RESOLVE | 进程内 | — | OpsReconAppService, ReconService |
| 运营 | C2 初判 | `OpsRefundSplitBackController` | 2 | FINANCE_SETTLE_EXECUTE; FINANCE_SETTLE_READ | 进程内 | — | OpsRefundSplitBackAppService, RefundSplitBackService |
| 运营 | C2 初判 | `OpsSettleController` | 6 | FINANCE_PAYOUT_EXECUTE; FINANCE_SETTLE_EXECUTE; FINANCE_SETTLE_READ | — | — | PayoutService, SettleService, AuditLogPort |
| 运营 | C2 初判 | `OpsSettleInvoiceController` | 3 | FINANCE_INVOICE_READ; FINANCE_INVOICE_VERIFY | 远程 | `/internal/pay/settle-invoices`, `/internal/pay/settle-invoices/issue`, `/internal/pay/settle-invoices/reject` | OpsSettleInvoiceAppService, SettleInvoiceService |

| 端 | 形态 | 接口数 |
|---|---|---|
| B端 | B 转发 | 22 |
| B端 | C1 初判 | 58 |
| B端 | C2 初判 | 89 |
| C端 | C1 初判 | 2 |
| C端 | C2 初判 | 18 |
| 运营 | C1 初判 | 110 |
| 运营 | C2 初判 | 31 |

## 2. 阶段 0 结论（C1 逐个）

结论：**迁** = near-pay 已有同功能的公开接口，前端改调透传前缀、ai-shop 适配入口下线；**等补** = near-pay 只有内部接口，维持 ai-shop 判，等 near-pay 补公开接口再排；**改判 C2** = standalone 下在 ai-shop 进程里执行（或合了 ai-shop 的数据），按原则由 ai-shop 判，原样保留。

| 端 | Controller | 接口数 | 结论 | near-pay 公开对应 | 说明 |
|---|---|---|---|---|---|
| B端 | `BizEntitlementIssueController` | 2 | 等补 | 无 | 权益发放，near-pay 只有内部接口 |
| B端 | `BizEntitlementTemplateController` | 6 | 等补 | 无 | 权益模板，near-pay 只有内部接口 |
| B端 | `BizPaymentController` | 23 | 部分迁 | `/biz/pay/v2`：`collections`（收款）、`qr-codes`（收款码，停用为 POST `/disable`）、`apps` + `stores/{storeNo}/app-grants`（第三方授权，按 appNo 而非 id） | 可迁 7 个：代客收款 1、收款码 3、第三方授权 3（形状不同，b-app 要改调）。等补 16 个：退款 2、对账单 1、收款单 6（列表还带沙箱/正式环境路由 `PaymentReadRouter`）、付款链接 3、收款状态 1、门店付款方式 4（near-pay 没有对应动作） |
| B端 | `BizReceivableController` | 1 | 改判 C2 | — | standalone 下进程内：应收汇总合 ai-shop 的 App 外收款记录 |
| B端 | `BizStoreFeatureController` | 2 | 改判 C2 | — | 门店功能开关，合 ai-shop 门店与方案数据；支付只读一项收款状态（经同一个支付客户端）；commerce 插件内 |
| B端 | `BizStoredValueGovernanceCapabilityController` | 1 | 等补 | 无 | 储值治理，near-pay 只有内部接口；判权按管理范围（规则已在网关库里配） |
| B端 | `BizStoredValueReportController` | 5 | 等补 | 无 | 同上 |
| B端 | `BizStoredValueSettlementController` | 18 | 等补 | 无 | 同上 |
| C端 | `MpBuyerPointsController` | 2 | 改判 C2 | — | standalone 下进程内：积分在 ai-shop 进程 |
| 运营 | `OpsBankFlowController` | 1 | 改判 C2 | — | standalone 下进程内：银行流水导入，ai-shop 结算表 |
| 运营 | `OpsEntitlementController` | 7 | 等补 | 无 | 权益异常，near-pay 只有内部接口 |
| 运营 | `OpsEntitlementLifecycleController` | 2 | 等补 | 无 | 同上 |
| 运营 | `OpsEntitlementReconciliationController` | 4 | 等补 | 无 | 同上 |
| 运营 | `OpsFeeRuleController` | 3 | 等补 | 无 | 费率规则，near-pay 只有内部接口 |
| 运营 | `OpsOrderPointsIntegrationController` | 7 | 改判 C2 | — | standalone 下进程内：订单积分，合 ai-shop 售后 |
| 运营 | `OpsPayChannelController` | 3 | 改判 C2 | — | 进程内直连通道主数据（两种形态都装配，见 `OpsPayChannelAppServiceImpl` 注释） |
| 运营 | `OpsPayServiceController` | 3 | 改判 C2 | — | 进程内：开通申请合 ai-shop 门店快照，经来源关联接口调 near-pay |
| 运营 | `OpsPayServiceOnboardingController` | 1 | 改判 C2 | — | 进程内：合 ai-shop 商户治理数据 |
| 运营 | `OpsPayableController` | 9 | 改判 C2 | — | standalone 下进程内：结算应付 |
| 运营 | `OpsPaymentController` | 21 | 迁 10 / 等补 11 | `/pay-ops/v1`：`orders`、`refunds`（含复核）、`exceptions`、`notifies`、`qr-codes/{qrCode}/disabled`，同受 `AiShopOpsSessionFilter`，判权用同一组 `Perms.PAY_*` | 可迁 10 个（订单 2、退款 2、异常 3、通知 2、停用收款码 1）；等补 11 个（应用 4、请求日志 2、收款码列表 1、平台密钥 4）。支付中心「退款」页签现在借道 `/ops/payments/refunds`，迁后改走透传 |
| 运营 | `OpsPointsController` | 2 | 改判 C2 | — | standalone 下进程内：积分 |
| 运营 | `OpsPointsInspectionController` | 14 | 改判 C2 | — | 同上 |
| 运营 | `OpsPointsPolicyController` | 11 | 改判 C2 | — | 同上 |
| 运营 | `OpsPointsTreasuryController` | 9 | 改判 C2 | — | 同上 |
| 运营 | `OpsPointsWorkbenchController` | 10 | 改判 C2 | — | 同上 |
| 运营 | `OpsSettleStatsController` | 1 | 改判 C2 | — | standalone 下进程内：结算统计 |
| 运营 | `OpsWithdrawController` | 2 | 改判 C2 | — | standalone 下进程内：提现 |

| 结论 | 接口数 |
|---|---|
| 改判 C2 | 78 |
| 等补 | 75 |
| 迁 | 17 |
