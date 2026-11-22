# 附录 · 支付相关入口盘点（TDD-支付三端经网关透传与权限归属 §5 阶段 0 初稿）

生成：2026-11-22，ai-shop `origin/main` `1cc4fef64`，扫描 `backend/shop-gateway/.../portal/**/*Controller.java`。
口径：凡是依赖 `payclient` / `pay.client` / `pay.*` 服务，或含 `/biz/pay/v2` 转发器的 controller 都列入。

**形态是机器初判，不是结论。** 判法：
- **B 转发**：含转发器、路径在 `/biz/pay/v2` 下。透传后整类下线。
- **C2 初判**：除了支付客户端，还注入了 ai-shop 进程内的服务（`pay.*Service`、商家 / 交易 / 平台域的 Service、Port、Mapper）。按原则仍由 ai-shop 判，原样保留。
- **C1 初判**：只依赖支付客户端（`payclient.*AppService`、`pay.client.*`），像是薄转调。要逐个确认三件事：near-pay 有没有对应的公开接口；`standalone` 形态下实际调的是不是远程；ai-shop 有没有在返回前拼自己的数据。

判权码一栏是 ai-shop 现在 `@PreAuthorize` 里的码，`Perms.X` 写常量名。迁到透传后，near-pay 的 `@payPerm` 要用等价的 `pay:` 码，那时同步登记到 `Perms` 与角色授予。

| 端 | 形态 | Controller | 接口数 | 判权码（ai-shop 现状） | 调用 / 依赖 |
|---|---|---|---|---|---|
| B端 | B 转发 | `BizChannelAccountClaimController` | 2 | biz:finance |  |
| B端 | B 转发 | `BizPayMerchantChannelController` | 1 | （无注解） | BizOnboardingForwarder, PayBizOnboardingRelay |
| B端 | B 转发 | `BizPayMerchantController` | 4 | （无注解） | BizOnboardingForwarder, PayBizOnboardingRelay |
| B端 | B 转发 | `BizPayOnboardingController` | 15 | （无注解） | BizOnboardingForwarder, PayBizOnboardingRelay |
| B端 | C1 初判 | `BizEntitlementIssueController` | 2 | ENT_READ | EntitlementBatchAppService |
| B端 | C1 初判 | `BizEntitlementTemplateController` | 6 | ENT_ISSUE_MANAGE; ENT_TEMPLA; ENT_TEMPLATE_WRITE | EntitlementManagementAppService |
| B端 | C1 初判 | `BizPaymentController` | 23 | PAY_APP_GRANT; PAY_COLLECT; PAY_ORDER_VIEW; PAY_QR; PAY_REFUND; PAY_REFUND, PAY_STATEMENT; PAY_STATEMENT; PAY_STORE_METH | PayBizInternalApi, StoreMethods |
| B端 | C1 初判 | `BizReceivableController` | 1 | （无注解） | ReceivableSummaryService, Summary |
| B端 | C1 初判 | `BizStoreFeatureController` | 2 | perm.memberOfSelectedStore#storeNo | BizPaymentAppService |
| B端 | C1 初判 | `BizStoredValueGovernanceCapabilityController` | 1 | managementAccess.authenticated | StoredValuePolicyAppService |
| B端 | C1 初判 | `BizStoredValueReportController` | 5 | managementAccess.authenticated | StoredValueFinanceAppService |
| B端 | C1 初判 | `BizStoredValueSettlementController` | 18 | managementAccess.authenticated | StoredValueFinanceAppService |
| B端 | C2 初判 | `BizEntitlementUseController` | 4 | （无注解） | EntitlementCodeAppService, EntitlementUsePort |
| B端 | C2 初判 | `BizEntityProfileController` | 11 | perm.merchantAccount | EntityProfileRemoteCalls, PayEntityProfileApi, MerchantEntityService |
| B端 | C2 初判 | `BizEntityRecognitionController` | 2 | perm.merchantAccount | EntityProfileRemoteCalls, PayEntityProfileApi, MerchantEntityService |
| B端 | C2 初判 | `BizPayServiceController` | 2 | （无注解） | PayServiceApplyRequests, PaySourceLinkApi, MerchantEntityService |
| B端 | C2 初判 | `BizSettleController` | 11 | FINANCE | BizSettleAppService, SettleService |
| B端 | C2 初判 | `BizStorePointsAgreementController` | 4 | FINANCE | PointsAdministrationService, AgreementService, MerchantQueryPort |
| B端 | C2 初判 | `BizStorePointsController` | 8 | FINANCE | BizPointsStatementService, PointsAdministrationService, PointsInspectionService |
| B端 | C2 初判 | `BizStoredValueController` | 11 | SV_ACCOUNT_READ; SV_CONSUME_CREATE; SV_R; SV_RECHARGE_CREATE | StoredValueRechargeAppService, StoredValueExecutionScopePort |
| B端 | C2 初判 | `BizStoredValueIssuerController` | 11 | managementAccess.authenticated | StoredValueIssuerAppService, IssuerAuthorityService |
| B端 | C2 初判 | `BizStoredValueMerchantQueryController` | 4 | SV_ACCOUNT_READ | StoredValueMerchantQueryAppService, StoredValueCustomerProfilePort |
| B端 | C2 初判 | `BizStoredValuePlanController` | 5 | SV_PLAN_MANAGE; SV_PLAN_READ | StoredValuePlanAppService, MarketPort |
| B端 | C2 初判 | `BizStoredValuePlanRechargeController` | 8 | SV_; SV_RECHARGE_CREATE | StoredValuePlanRechargeAppService, StoredValueRechargeAppService, CustomerIdentityPort |
| B端 | C2 初判 | `BizStoredValuePolicyController` | 6 | managementAccess.authenticated | StoredValuePolicyAppService, StoredValuePolicyAuthorityService |
| B端 | C2 初判 | `BizStoredValueWalletController` | 2 | （无注解） | PayStoredValueWalletRequests, StoredValueWalletAppService, StoredValueCustomerProfilePort |
| C端 | C1 初判 | `MpBuyerPointsController` | 2 | （无注解） | BuyerPointsAppService |
| C端 | C2 初判 | `MpEntitlementController` | 10 | （无注解） | EntitlementCodeAppService, EntitlementQueryPort |
| C端 | C2 初判 | `MpStoredValueController` | 6 | （无注解） | StoredValueRechargeAppService, StoredValueConsumerQueryAppService, MarketPort |
| C端 | C2 初判 | `OrderPointsEarningController` | 2 | ORDER_VIEW | OrderPointsEarningViewService, OrderService |
| 运营 | C1 初判 | `OpsBankFlowController` | 1 | FINANCE_PAYOUT_EXECUTE | OpsBankFlowAppService, ImportResultVO |
| 运营 | C1 初判 | `OpsEntitlementController` | 7 | ENT_EXCEPTION_READ; ENT_EXCEPTION_RECOVER; ENT_EXCEPTION_VERIFY | EntitlementExceptionAppService |
| 运营 | C1 初判 | `OpsEntitlementLifecycleController` | 2 | ENT_EXCEPTION_READ; ENT_FREEZE | EntitlementLifecycleAppService |
| 运营 | C1 初判 | `OpsEntitlementReconciliationController` | 4 | ENT_EXCEPTION_READ; ENT_EXCEPTION_VERIFY | EntitlementReconciliationAppService |
| 运营 | C1 初判 | `OpsFeeRuleController` | 3 | FINANCE_RATE_READ; FINANCE_RATE_UPDATE | OpsFeeRuleAppService |
| 运营 | C1 初判 | `OpsOrderPointsIntegrationController` | 7 | FINANCE_SETTLE_EXECUTE; FINANCE_SETTLE_READ | OrderPointsIntegrationAppService |
| 运营 | C1 初判 | `OpsPayChannelController` | 3 | FINANCE_RATE_READ; FINANCE_RATE_UPDATE | OpsPayChannelAppService, ChannelVO |
| 运营 | C1 初判 | `OpsPayServiceController` | 3 | MERCHANT_PAY_APPLY | PayServiceApplyRequests, PayServiceApplicationService |
| 运营 | C1 初判 | `OpsPayServiceOnboardingController` | 1 | （无注解） | PayServiceOnboardingQueryService |
| 运营 | C1 初判 | `OpsPayableController` | 9 | FINANCE_INVOICE_READ; FINANCE_INVOICE_VERIFY; FINANCE_PAYOUT_EXECUTE; FINANCE_SETTLE_EXECUTE; FINANCE_SETTLE_READ | OpsPayableAppService |
| 运营 | C1 初判 | `OpsPaymentController` | 21 | PAY_APP_CREDENTIAL_REVOKE; PAY_APP_MANAGE; PAY_LOG_VIEW; PAY_NOTIFY_RESEND; PAY_ORDER_MANAGE; PAY_ORDER_VIEW; PAY_QR_MAN | OpsPaymentAppService |
| 运营 | C1 初判 | `OpsPointsController` | 2 | FINANCE_SETTLE_READ | PointsOpsAppService |
| 运营 | C1 初判 | `OpsPointsInspectionController` | 14 | FINANCE_RECON_READ; FINANCE_RECON_RESOLVE; FINANCE_SETTLE_EXECUTE; FINANCE_SETTLE_READ | PointsInspectionAppService |
| 运营 | C1 初判 | `OpsPointsPolicyController` | 11 | FINANCE_SETTLE_EXECUTE; FINANCE_SETTLE_READ | PointsConfigurationAppService |
| 运营 | C1 初判 | `OpsPointsTreasuryController` | 9 | FINANCE_PAYOUT_EXECUTE; FINANCE_SETTLE_EXECUTE; FINANCE_SETTLE_READ | PointsTreasuryAppService |
| 运营 | C1 初判 | `OpsPointsWorkbenchController` | 10 | FINANCE_PAYOUT_EXECUTE; FINANCE_SETTLE_EXECUTE; FINANCE_SETTLE_READ | PointsWorkbenchAppService |
| 运营 | C1 初判 | `OpsSettleStatsController` | 1 | FINANCE_SETTLE_READ | OpsSettleStatsAppService, StatRowVO |
| 运营 | C1 初判 | `OpsWithdrawController` | 2 | FINANCE_INVOICE_READ; FINANCE_INVOICE_VERIFY | OpsWithdrawAppService |
| 运营 | C2 初判 | `OpsChannelMessageController` | 2 | FINANCE_RECON_READ | ChannelMessageQueryService |
| 运营 | C2 初判 | `OpsPayoutController` | 3 | FINANCE_PAYOUT_EXECUTE; FINANCE_SETTLE_READ | OpsPayoutAppService, PayoutService |
| 运营 | C2 初判 | `OpsPayoutListController` | 1 | FINANCE_PAYOUT_EXECUTE | OpsPayoutListAppService, PayoutListVO, AuditLogPort |
| 运营 | C2 初判 | `OpsPointsAdjustmentController` | 3 | FINANCE_PAYOUT_EXECUTE; FINANCE_SETTLE_EXECUTE; FINANCE_SETTLE_READ | PointsOpsAccess, PointsOpsErrors, PointsAdjustmentService, UserQueryPort |
| 运营 | C2 初判 | `OpsPointsEarningRulesController` | 6 | FINANCE_SETTLE_EXECUTE; FINANCE_SETTLE_READ | PointsOpsAccess, PointsOpsErrors, PointsAdministrationService, PointsEarningRulePort |
| 运营 | C2 初判 | `OpsReconController` | 5 | FINANCE_RECON_READ; FINANCE_RECON_RESOLVE | OpsReconAppService, ReconService |
| 运营 | C2 初判 | `OpsRefundSplitBackController` | 2 | FINANCE_SETTLE_EXECUTE; FINANCE_SETTLE_READ | OpsRefundSplitBackAppService, RefundSplitBackService |
| 运营 | C2 初判 | `OpsSettleController` | 6 | FINANCE_PAYOUT_EXECUTE; FINANCE_SETTLE_EXECUTE; FINANCE_SETTLE_READ | PayoutService, SettleService, AuditLogPort |
| 运营 | C2 初判 | `OpsSettleInvoiceController` | 3 | FINANCE_INVOICE_READ; FINANCE_INVOICE_VERIFY | OpsSettleInvoiceAppService, SettleInvoiceService |

| 端 | 形态 | 接口数 |
|---|---|---|
| B端 | B 转发 | 22 |
| B端 | C1 初判 | 58 |
| B端 | C2 初判 | 89 |
| C端 | C1 初判 | 2 |
| C端 | C2 初判 | 18 |
| 运营 | C1 初判 | 110 |
| 运营 | C2 初判 | 31 |
