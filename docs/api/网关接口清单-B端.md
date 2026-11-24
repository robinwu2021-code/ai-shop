# 网关接口清单 · B 端

> 由 `python3 scripts/gen-gateway-api-inventory.py` 生成，**请勿手改**。来源是后端源码里实际声明的入口。
> 总览、分类规则与验证清单见 [网关接口清单](网关接口清单.md)。

共 **1215** 个入口：支付 268、零售 580、餐饮 261、服务业 32、库存 45、平台与通用 29。

## 支付（268）

网关判权：能提前判 171、只要求登录 97。构建：默认构建 268。生产：已入网关 223、未部署 45。

### checkout（3）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/biz/checkout/{operationNo}/offline-receipts` | `@perm.canBiz('pay:cash:receive')` | 能提前判 | 默认构建 | 已入网关 | BizOfflineReceiptController |
| POST | `/biz/checkout/{operationNo}/offline-receipts/lookup` | `@perm.canBiz('biz:order:view')` | 能提前判 | 默认构建 | 已入网关 | BizOfflineReceiptController |
| GET | `/biz/checkout/{operationNo}/offline-receipts/{paymentOperationNo}` | `@perm.canBiz('biz:order:view')` | 能提前判 | 默认构建 | 已入网关 | BizOfflineReceiptController |

### deposit（2）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/deposit` | `@perm.canBiz('biz:finance')` | 能提前判 | 默认构建 | 已入网关 | BizDepositController |
| GET | `/biz/deposit/txns` | `@perm.canBiz('biz:finance')` | 能提前判 | 默认构建 | 已入网关 | BizDepositController |

### entitlement-issue-batches（2）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/biz/entitlement-issue-batches` | `@perm.canBiz('pay:ent:issue')` | 能提前判 | 默认构建 | 已入网关 | BizEntitlementIssueController |
| GET | `/biz/entitlement-issue-batches/{batchNo}` | `@perm.canBiz('pay:ent:read')` | 能提前判 | 默认构建 | 已入网关 | BizEntitlementIssueController |

### entitlement-templates（6）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/entitlement-templates` | `@perm.canBiz('pay:ent:template:read')` | 能提前判 | 默认构建 | 已入网关 | BizEntitlementTemplateController |
| POST | `/biz/entitlement-templates` | `@perm.canBiz('pay:ent:template:write')` | 能提前判 | 默认构建 | 已入网关 | BizEntitlementTemplateController |
| PUT | `/biz/entitlement-templates/{templateNo}/draft` | `@perm.canBiz('pay:ent:template:write')` | 能提前判 | 默认构建 | 已入网关 | BizEntitlementTemplateController |
| PUT | `/biz/entitlement-templates/{templateNo}/issue-policy` | `@perm.canBiz('pay:ent:issue:manage')` | 能提前判 | 默认构建 | 已入网关 | BizEntitlementTemplateController |
| POST | `/biz/entitlement-templates/{templateNo}/publish` | `@perm.canBiz('pay:ent:template:publish')` | 能提前判 | 默认构建 | 已入网关 | BizEntitlementTemplateController |
| PUT | `/biz/entitlement-templates/{templateNo}/status` | `@perm.canBiz('pay:ent:template:publish')` | 能提前判 | 默认构建 | 已入网关 | BizEntitlementTemplateController |

### entitlement-usage（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/entitlement-usage` | `@perm.canBiz('pay:ent:read')` | 能提前判 | 默认构建 | 已入网关 | BizEntitlementQueryController |

### entitlement-uses（4）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/biz/entitlement-uses` | `@perm.canBiz('pay:ent:use')` | 能提前判 | 默认构建 | 已入网关 | BizEntitlementUseController |
| POST | `/biz/entitlement-uses/preview` | `@perm.canBiz('pay:ent:use')` | 能提前判 | 默认构建 | 已入网关 | BizEntitlementUseController |
| GET | `/biz/entitlement-uses/{operationNo}` | `@perm.canBiz('pay:ent:read')` | 能提前判 | 默认构建 | 已入网关 | BizEntitlementUseController |
| POST | `/biz/entitlement-uses/{operationNo}/cancel` | `@perm.canBiz('pay:ent:use')` | 能提前判 | 默认构建 | 已入网关 | BizEntitlementUseController |

### entitlements（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/entitlements/{instanceNo}` | `@perm.canBiz('pay:ent:read')` | 能提前判 | 默认构建 | 已入网关 | BizEntitlementQueryController |

### invoices（19）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/invoices` | `@perm.canBiz('biz:invoice:read')` | 能提前判 | 默认构建 | 未部署 | BizInvoiceController |
| POST | `/biz/invoices` | `@perm.canBiz('biz:invoice:issue')` | 能提前判 | 默认构建 | 未部署 | BizInvoiceController |
| PUT | `/biz/invoices/buyer-preferences` | `@perm.canBiz('biz:invoice:issue')` | 能提前判 | 默认构建 | 未部署 | BizInvoiceController |
| GET | `/biz/invoices/upload-backlog` | `@perm.canBiz('biz:invoice:read')` | 能提前判 | 默认构建 | 未部署 | BizInvoiceController |
| GET | `/biz/invoices/{invoiceNo}` | `@perm.canBiz('biz:invoice:read')` | 能提前判 | 默认构建 | 未部署 | BizInvoiceController |
| POST | `/biz/invoices/{invoiceNo}/allowances` | `@perm.canBiz('biz:invoice:void')` | 能提前判 | 默认构建 | 未部署 | BizInvoiceController |
| POST | `/biz/invoices/{invoiceNo}/allowances/{allowanceNo}/void` | `@perm.canBiz('biz:invoice:void')` | 能提前判 | 默认构建 | 未部署 | BizInvoiceController |
| GET | `/biz/invoices/{invoiceNo}/deliveries` | `@perm.canBiz('biz:invoice:read')` | 能提前判 | 默认构建 | 未部署 | BizInvoiceDeliveryController |
| POST | `/biz/invoices/{invoiceNo}/deliveries` | `@perm.canBiz('biz:invoice:issue') and @perm.canBiz('biz:invoice:read') and @perm.canBiz('biz:print:export')` | 能提前判 | 默认构建 | 未部署 | BizInvoiceDeliveryController |
| POST | `/biz/invoices/{invoiceNo}/deliveries/{deliveryNo}/resends` | `@perm.canBiz('biz:invoice:issue') and @perm.canBiz('biz:invoice:read') and @perm.canBiz('biz:print:export')` | 能提前判 | 默认构建 | 未部署 | BizInvoiceDeliveryController |
| GET | `/biz/invoices/{invoiceNo}/documents` | `@perm.canBiz('biz:invoice:read') and @perm.canBiz('biz:print:export')` | 能提前判 | 默认构建 | 未部署 | BizInvoiceDocumentController |
| POST | `/biz/invoices/{invoiceNo}/documents` | `@perm.canBiz('biz:invoice:read') and @perm.canBiz('biz:print:export')` | 能提前判 | 默认构建 | 未部署 | BizInvoiceDocumentController |
| GET | `/biz/invoices/{invoiceNo}/print-facts` | `@perm.canBiz('biz:invoice:read')` | 能提前判 | 默认构建 | 未部署 | BizInvoiceController |
| POST | `/biz/invoices/{invoiceNo}/provider-checks` | `@perm.canBiz('biz:invoice:issue')` | 能提前判 | 默认构建 | 未部署 | BizInvoiceController |
| POST | `/biz/invoices/{invoiceNo}/reissue` | `@perm.canBiz('biz:invoice:issue')` | 能提前判 | 默认构建 | 未部署 | BizInvoiceController |
| POST | `/biz/invoices/{invoiceNo}/retransmissions` | `@perm.canBiz('biz:invoice:issue')` | 能提前判 | 默认构建 | 未部署 | BizInvoiceController |
| POST | `/biz/invoices/{invoiceNo}/revocation` | `@perm.canBiz('biz:invoice:void')` | 能提前判 | 默认构建 | 未部署 | BizInvoiceController |
| GET | `/biz/invoices/{invoiceNo}/timeline` | `@perm.canBiz('biz:invoice:read')` | 能提前判 | 默认构建 | 未部署 | BizInvoiceTimelineController |
| POST | `/biz/invoices/{invoiceNo}/void` | `@perm.canBiz('biz:invoice:void')` | 能提前判 | 默认构建 | 未部署 | BizInvoiceController |

### management（60）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/management/brands/{brandNo}/stored-value/activities` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizBrandStoredValueActivityController |
| POST | `/biz/management/brands/{brandNo}/stored-value/activities` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizBrandStoredValueActivityController |
| GET | `/biz/management/brands/{brandNo}/stored-value/activities/{activityNo}` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizBrandStoredValueActivityController |
| POST | `/biz/management/brands/{brandNo}/stored-value/activities/{activityNo}/checks` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizBrandStoredValueActivityController |
| POST | `/biz/management/brands/{brandNo}/stored-value/activities/{activityNo}/copies` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizBrandStoredValueActivityController |
| PUT | `/biz/management/brands/{brandNo}/stored-value/activities/{activityNo}/draft` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizBrandStoredValueActivityController |
| POST | `/biz/management/brands/{brandNo}/stored-value/activities/{activityNo}/stop-issuance` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizBrandStoredValueActivityController |
| POST | `/biz/management/brands/{brandNo}/stored-value/activities/{activityNo}/submissions` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizBrandStoredValueActivityController |
| GET | `/biz/management/brands/{brandNo}/stored-value/activity-context` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizBrandStoredValueActivityController |
| GET | `/biz/management/brands/{brandNo}/stored-value/activity-presets` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizBrandStoredValueActivityController |
| GET | `/biz/management/brands/{brandNo}/stored-value/activity-schedules` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizBrandStoredValueActivityController |
| GET | `/biz/management/brands/{brandNo}/stored-value/issuer` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizStoredValueIssuerController |
| POST | `/biz/management/brands/{brandNo}/stored-value/issuer` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizStoredValueIssuerController |
| POST | `/biz/management/brands/{brandNo}/stored-value/issuer/activation` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizStoredValueBrandActivationController |
| GET | `/biz/management/brands/{brandNo}/stored-value/store-breakdown` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizStoredValueReportController |
| GET | `/biz/management/brands/{brandNo}/stored-value/summary` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizStoredValueReportController |
| POST | `/biz/management/entities/{entityNo}/stored-value/financial-permissions` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizStoredValueSettlementController |
| GET | `/biz/management/stored-value/activities/{activityNo}/summary` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizStoredValueReportController |
| GET | `/biz/management/stored-value/activity-approvals` | 商家身份 | 只要求登录 | 默认构建 | 未部署 | BizStoredValueActivityController |
| GET | `/biz/management/stored-value/activity-approvals/{approvalNo}` | 商家身份 | 只要求登录 | 默认构建 | 未部署 | BizStoredValueActivityController |
| POST | `/biz/management/stored-value/activity-approvals/{approvalNo}/decisions` | 商家身份 | 只要求登录 | 默认构建 | 未部署 | BizStoredValueActivityController |
| GET | `/biz/management/stored-value/financial-brand-changes/{changeNo}` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizBrandFinancialChangeController |
| POST | `/biz/management/stored-value/financial-brand-changes/{changeNo}/resumptions` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizBrandFinancialChangeController |
| GET | `/biz/management/stored-value/financial-capability` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizStoredValueSettlementController |
| GET | `/biz/management/stored-value/financial-resources` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizStoredValueSettlementController |
| GET | `/biz/management/stored-value/issuers/{issuerNo}` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizStoredValueIssuerController |
| GET | `/biz/management/stored-value/issuers/{issuerNo}/collection-routes` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizStoredValueIssuerController |
| POST | `/biz/management/stored-value/issuers/{issuerNo}/configuration-checks` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizStoredValueIssuerController |
| POST | `/biz/management/stored-value/issuers/{issuerNo}/configuration-versions` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizStoredValueIssuerController |
| POST | `/biz/management/stored-value/migration-manifests` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizStoredValueMigrationController |
| POST | `/biz/management/stored-value/migration-manifests/{manifestNo}/map` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizStoredValueMigrationController |
| POST | `/biz/management/stored-value/migration-manifests/{manifestNo}/pause` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizStoredValueMigrationController |
| GET | `/biz/management/stored-value/policies` | 商家身份 | 只要求登录 | 默认构建 | 未部署 | BizStoredValuePolicyController |
| POST | `/biz/management/stored-value/policies` | 商家身份 | 只要求登录 | 默认构建 | 未部署 | BizStoredValuePolicyController |
| PUT | `/biz/management/stored-value/policies/{policyNo}` | 商家身份 | 只要求登录 | 默认构建 | 未部署 | BizStoredValuePolicyController |
| POST | `/biz/management/stored-value/policy-permissions/prepare` | 商家身份 | 只要求登录 | 默认构建 | 未部署 | BizStoredValuePolicyController |
| GET | `/biz/management/stored-value/policy-targets` | 商家身份 | 只要求登录 | 默认构建 | 未部署 | BizStoredValuePolicyController |
| GET | `/biz/management/stored-value/settlement-bills` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizStoredValueSettlementController |
| POST | `/biz/management/stored-value/settlement-bills/generations` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizStoredValueSettlementController |
| GET | `/biz/management/stored-value/settlement-bills/{billNo}` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizStoredValueSettlementController |
| GET | `/biz/management/stored-value/settlement-bills/{billNo}/actions` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizStoredValueSettlementController |
| POST | `/biz/management/stored-value/settlement-bills/{billNo}/adjustments` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizStoredValueSettlementController |
| POST | `/biz/management/stored-value/settlement-bills/{billNo}/dispute-resolutions` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizStoredValueSettlementController |
| POST | `/biz/management/stored-value/settlement-bills/{billNo}/disputes` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizStoredValueSettlementController |
| GET | `/biz/management/stored-value/settlement-bills/{billNo}/events` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizStoredValueSettlementController |
| GET | `/biz/management/stored-value/settlement-bills/{billNo}/items` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizStoredValueSettlementController |
| GET | `/biz/management/stored-value/settlement-bills/{billNo}/payment-resolutions` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizStoredValueSettlementController |
| GET | `/biz/management/stored-value/settlement-bills/{billNo}/payments` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizStoredValueSettlementController |
| POST | `/biz/management/stored-value/settlement-bills/{billNo}/payments` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizStoredValueSettlementController |
| POST | `/biz/management/stored-value/settlement-bills/{billNo}/reconciliation-confirmations` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizStoredValueSettlementController |
| GET | `/biz/management/stored-value/settlement-entity-candidates` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizStoredValueIssuerController |
| POST | `/biz/management/stored-value/settlement-payments/{paymentNo}/receipt-confirmations` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizStoredValueSettlementController |
| POST | `/biz/management/stored-value/settlement-payments/{paymentNo}/voids` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizStoredValueSettlementController |
| GET | `/biz/management/stored-value/store-issuer-summary` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizStoredValueReportController |
| GET | `/biz/management/stores/{storeNo}/stored-value/financial-brand-change-original` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizBrandFinancialChangeController |
| POST | `/biz/management/stores/{storeNo}/stored-value/financial-brand-changes` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizBrandFinancialChangeController |
| GET | `/biz/management/stores/{storeNo}/stored-value/issuer` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizStoredValueIssuerController |
| POST | `/biz/management/stores/{storeNo}/stored-value/issuer` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizStoredValueIssuerController |
| POST | `/biz/management/stores/{storeNo}/stored-value/issuer/access-refresh` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizStoredValueIssuerController |
| POST | `/biz/management/stores/{storeNo}/stored-value/issuer/prepare` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizStoredValueIssuerController |

### merchant（9）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/merchant/debt` | `@perm.canBiz('biz:finance')` | 能提前判 | 默认构建 | 已入网关 | BizMerchantController |
| POST | `/biz/merchant/onboarding` | `@perm.canBiz('biz:finance')` | 能提前判 | 默认构建 | 已入网关 | BizMerchantOnboardingController |
| GET | `/biz/merchant/pay-channel` | `@perm.canBiz('biz:finance')` | 能提前判 | 默认构建 | 已入网关 | BizMerchantController |
| GET | `/biz/merchant/pay-service` | `@perm.canBiz('biz:finance')` | 能提前判 | 默认构建 | 已入网关 | BizPayServiceController |
| POST | `/biz/merchant/pay-service/apply` | `@perm.canBiz('biz:finance')` | 能提前判 | 默认构建 | 已入网关 | BizPayServiceController |
| GET | `/biz/merchant/payment` | `@perm.canBiz('biz:finance')` | 能提前判 | 默认构建 | 已入网关 | BizMerchantController |
| POST | `/biz/merchant/payment` | `@perm.canBiz('biz:finance')` | 能提前判 | 默认构建 | 已入网关 | BizMerchantController |
| POST | `/biz/merchant/payment/store/{storeNo}` | `@perm.canBiz('biz:finance')` | 能提前判 | 默认构建 | 已入网关 | BizMerchantController |
| POST | `/biz/merchant/payment/{payChannel}/refresh` | `@perm.canBiz('biz:finance')` | 能提前判 | 默认构建 | 已入网关 | BizMerchantController |

### pay（41）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/pay/app-grant` | `@perm.canBiz('mch:pay:app-grant')` | 能提前判 | 默认构建 | 已入网关 | BizPayAppGrantController |
| POST | `/biz/pay/app-grant` | `@perm.canBiz('mch:pay:app-grant')` | 能提前判 | 默认构建 | 已入网关 | BizPayAppGrantController |
| DELETE | `/biz/pay/app-grant/{id}` | `@perm.canBiz('mch:pay:app-grant')` | 能提前判 | 默认构建 | 已入网关 | BizPayAppGrantController |
| POST | `/biz/pay/collection` | `@perm.canBiz('mch:pay:collect')` | 能提前判 | 默认构建 | 已入网关 | BizPayCollectionController |
| GET | `/biz/pay/qr-code` | `@perm.canBiz('mch:pay:qr')` | 能提前判 | 默认构建 | 已入网关 | BizPayQrCodeController |
| POST | `/biz/pay/qr-code` | `@perm.canBiz('mch:pay:qr')` | 能提前判 | 默认构建 | 已入网关 | BizPayQrCodeController |
| PUT | `/biz/pay/qr-code/{qrCode}/disabled` | `@perm.canBiz('mch:pay:qr')` | 能提前判 | 默认构建 | 已入网关 | BizPayQrCodeController |
| GET | `/biz/pay/refund` | `@perm.canAnyBiz('mch:pay:refund','mch:pay:statement')` | 能提前判 | 默认构建 | 已入网关 | BizPayRefundController |
| POST | `/biz/pay/refund` | `@perm.canBiz('mch:pay:refund')` | 能提前判 | 默认构建 | 已入网关 | BizPayRefundController |
| GET | `/biz/pay/statement` | `@perm.canBiz('mch:pay:statement')` | 能提前判 | 默认构建 | 已入网关 | BizPayStatementController |
| GET | `/biz/pay/v2/capability-progress` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizPayOnboardingController |
| GET | `/biz/pay/v2/channel-account-claims` | `@perm.canBiz('biz:finance')` | 能提前判 | 默认构建 | 已入网关 | BizChannelAccountClaimController |
| POST | `/biz/pay/v2/channel-account-claims` | `@perm.canBiz('biz:finance')` | 能提前判 | 默认构建 | 已入网关 | BizChannelAccountClaimController |
| GET | `/biz/pay/v2/channel-account-claims/by-request/{requestNo}` | `@perm.canBiz('biz:finance')` | 能提前判 | 默认构建 | 已入网关 | BizChannelAccountClaimController |
| GET | `/biz/pay/v2/channel-account-claims/{claimNo}` | `@perm.canBiz('biz:finance')` | 能提前判 | 默认构建 | 已入网关 | BizChannelAccountClaimController |
| GET | `/biz/pay/v2/channel-account-claims/{claimNo}/commands/{requestNo}` | `@perm.canBiz('biz:finance')` | 能提前判 | 默认构建 | 已入网关 | BizChannelAccountClaimController |
| GET | `/biz/pay/v2/channel-account-claims/{claimNo}/files` | `@perm.canBiz('biz:finance')` | 能提前判 | 默认构建 | 已入网关 | BizChannelAccountClaimController |
| POST | `/biz/pay/v2/channel-account-claims/{claimNo}/files` | `@perm.canBiz('biz:finance')` | 能提前判 | 默认构建 | 已入网关 | BizChannelAccountClaimController |
| GET | `/biz/pay/v2/channel-account-claims/{claimNo}/files/{fileNo}` | `@perm.canBiz('biz:finance')` | 能提前判 | 默认构建 | 已入网关 | BizChannelAccountClaimController |
| POST | `/biz/pay/v2/channel-account-claims/{claimNo}/submit` | `@perm.canBiz('biz:finance')` | 能提前判 | 默认构建 | 已入网关 | BizChannelAccountClaimController |
| GET | `/biz/pay/v2/memberships` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizPayOnboardingController |
| GET | `/biz/pay/v2/merchant-channels` | 登录即可 | 只要求登录 | 默认构建 | 未部署 | BizPayMerchantChannelController |
| POST | `/biz/pay/v2/merchant-creations` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizPayMerchantController |
| GET | `/biz/pay/v2/merchant-creations/by-request/{requestNo}` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizPayMerchantController |
| GET | `/biz/pay/v2/merchant-creations/context` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizPayMerchantController |
| GET | `/biz/pay/v2/merchant-profile-revisions` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizPayMerchantController |
| POST | `/biz/pay/v2/merchant-profile-revisions` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizPayMerchantController |
| GET | `/biz/pay/v2/merchant-profile-revisions/by-request/{requestNo}` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizPayMerchantController |
| GET | `/biz/pay/v2/onboardings` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizPayOnboardingController |
| POST | `/biz/pay/v2/onboardings` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizPayOnboardingController |
| GET | `/biz/pay/v2/onboardings/{no}` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizPayOnboardingController |
| POST | `/biz/pay/v2/onboardings/{no}/cancel` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizPayOnboardingController |
| GET | `/biz/pay/v2/onboardings/{no}/draft` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizPayOnboardingController |
| PUT | `/biz/pay/v2/onboardings/{no}/draft` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizPayOnboardingController |
| GET | `/biz/pay/v2/onboardings/{no}/draft/commands/{requestNo}` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizPayOnboardingController |
| POST | `/biz/pay/v2/onboardings/{no}/draft/confirm` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizPayOnboardingController |
| GET | `/biz/pay/v2/onboardings/{no}/files` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizPayOnboardingController |
| POST | `/biz/pay/v2/onboardings/{no}/files` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizPayOnboardingController |
| GET | `/biz/pay/v2/onboardings/{no}/files/{fileNo}` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizPayOnboardingController |
| POST | `/biz/pay/v2/onboardings/{no}/newebpay-materials` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizPayOnboardingController |
| GET | `/biz/pay/v2/onboardings/{no}/readiness` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizPayOnboardingController |

### payment（30）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/payment/app-grant` | `@perm.canBiz('mch:pay:app-grant')` | 能提前判 | 默认构建 | 已入网关 | BizPaymentController |
| POST | `/biz/payment/app-grant` | `@perm.canBiz('mch:pay:app-grant')` | 能提前判 | 默认构建 | 已入网关 | BizPaymentController |
| DELETE | `/biz/payment/app-grant/{id}` | `@perm.canBiz('mch:pay:app-grant')` | 能提前判 | 默认构建 | 已入网关 | BizPaymentController |
| GET | `/biz/payment/collect-status` | `@perm.canBiz('mch:pay:order:view')` | 能提前判 | 默认构建 | 已入网关 | BizPaymentController |
| POST | `/biz/payment/collection` | `@perm.canBiz('mch:pay:collect')` | 能提前判 | 默认构建 | 已入网关 | BizPaymentController |
| GET | `/biz/payment/events` | `@perm.canBiz('mch:pay:order:view')` | 能提前判 | 默认构建 | 已入网关 | BizPaymentEventsController |
| GET | `/biz/payment/links` | `@perm.canBiz('mch:pay:order:view')` | 能提前判 | 默认构建 | 已入网关 | BizPaymentController |
| POST | `/biz/payment/links/{payOrderNo}/reissue` | `@perm.canBiz('mch:pay:collect')` | 能提前判 | 默认构建 | 已入网关 | BizPaymentController |
| POST | `/biz/payment/links/{payOrderNo}/void` | `@perm.canBiz('mch:pay:collect')` | 能提前判 | 默认构建 | 已入网关 | BizPaymentController |
| GET | `/biz/payment/offline-records` | `@perm.canBiz('mch:pay:order:view')` | 能提前判 | 默认构建 | 已入网关 | BizOfflineRecordController |
| POST | `/biz/payment/offline-records` | `@perm.canBiz('mch:pay:offline:record')` | 能提前判 | 默认构建 | 已入网关 | BizOfflineRecordController |
| GET | `/biz/payment/offline-records/summary` | `@perm.canBiz('mch:pay:order:view')` | 能提前判 | 默认构建 | 已入网关 | BizOfflineRecordController |
| POST | `/biz/payment/offline-records/{recordNo}/correct` | `@perm.canBiz('mch:pay:offline:manage')` | 能提前判 | 默认构建 | 已入网关 | BizOfflineRecordController |
| POST | `/biz/payment/offline-records/{recordNo}/refund` | `@perm.canBiz('mch:pay:offline:manage')` | 能提前判 | 默认构建 | 未部署 | BizOfflineRecordController |
| POST | `/biz/payment/offline-records/{recordNo}/void` | `@perm.canBiz('mch:pay:offline:manage')` | 能提前判 | 默认构建 | 已入网关 | BizOfflineRecordController |
| GET | `/biz/payment/orders` | `@perm.canBiz('mch:pay:order:view')` | 能提前判 | 默认构建 | 已入网关 | BizPaymentController |
| GET | `/biz/payment/orders/lookup` | `@perm.canBiz('mch:pay:order:view')` | 能提前判 | 默认构建 | 已入网关 | BizPaymentController |
| GET | `/biz/payment/orders/summary` | `@perm.canBiz('mch:pay:statement')` | 能提前判 | 默认构建 | 已入网关 | BizPaymentController |
| GET | `/biz/payment/orders/{payOrderNo}` | `@perm.canBiz('mch:pay:order:view')` | 能提前判 | 默认构建 | 已入网关 | BizPaymentController |
| POST | `/biz/payment/orders/{payOrderNo}/query` | `@perm.canBiz('mch:pay:order:view')` | 能提前判 | 默认构建 | 已入网关 | BizPaymentController |
| GET | `/biz/payment/qr-code` | `@perm.canBiz('mch:pay:qr')` | 能提前判 | 默认构建 | 已入网关 | BizPaymentController |
| POST | `/biz/payment/qr-code` | `@perm.canBiz('mch:pay:qr')` | 能提前判 | 默认构建 | 已入网关 | BizPaymentController |
| PUT | `/biz/payment/qr-code/{qrCode}/disabled` | `@perm.canBiz('mch:pay:qr')` | 能提前判 | 默认构建 | 已入网关 | BizPaymentController |
| GET | `/biz/payment/refund` | `@perm.canAnyBiz('mch:pay:refund','mch:pay:statement')` | 能提前判 | 默认构建 | 已入网关 | BizPaymentController |
| POST | `/biz/payment/refund` | `@perm.canBiz('mch:pay:refund')` | 能提前判 | 默认构建 | 已入网关 | BizPaymentController |
| GET | `/biz/payment/statement` | `@perm.canBiz('mch:pay:statement')` | 能提前判 | 默认构建 | 已入网关 | BizPaymentController |
| GET | `/biz/payment/store-method` | `@perm.canBiz('mch:pay:store-method')` | 能提前判 | 默认构建 | 未部署 | BizPaymentController |
| PUT | `/biz/payment/store-method` | `@perm.canBiz('mch:pay:store-method')` | 能提前判 | 默认构建 | 未部署 | BizPaymentController |
| GET | `/biz/payment/store-method-preference` | `@perm.canBiz('mch:pay:store-method')` | 能提前判 | 默认构建 | 未部署 | BizPaymentController |
| PUT | `/biz/payment/store-method-preference` | `@perm.canBiz('mch:pay:store-method')` | 能提前判 | 默认构建 | 未部署 | BizPaymentController |

### payout-account（2）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/payout-account` | `@perm.canBiz('biz:finance')` | 能提前判 | 默认构建 | 已入网关 | BizPayoutAccountController |
| POST | `/biz/payout-account` | `@perm.canBiz('biz:finance')` | 能提前判 | 默认构建 | 已入网关 | BizPayoutAccountController |

### points（12）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/points/store-account` | `@perm.canBiz('biz:finance')` | 能提前判 | 默认构建 | 已入网关 | BizStorePointsController |
| GET | `/biz/points/store-agreement` | `@perm.canBiz('biz:finance')` | 能提前判 | 默认构建 | 已入网关 | BizStorePointsAgreementController |
| POST | `/biz/points/store-agreement/sign` | `@perm.canBiz('biz:finance')` | 能提前判 | 默认构建 | 已入网关 | BizStorePointsAgreementController |
| POST | `/biz/points/store-agreement/terminate` | `@perm.canBiz('biz:finance')` | 能提前判 | 默认构建 | 已入网关 | BizStorePointsAgreementController |
| GET | `/biz/points/store-entries` | `@perm.canBiz('biz:finance')` | 能提前判 | 默认构建 | 已入网关 | BizStorePointsController |
| GET | `/biz/points/store-statement` | `@perm.canBiz('biz:finance')` | 能提前判 | 默认构建 | 已入网关 | BizStorePointsController |
| POST | `/biz/points/store-statement-confirm` | `@perm.canBiz('biz:finance')` | 能提前判 | 默认构建 | 已入网关 | BizStorePointsController |
| POST | `/biz/points/store-statement-dispute` | `@perm.canBiz('biz:finance')` | 能提前判 | 默认构建 | 已入网关 | BizStorePointsController |
| GET | `/biz/points/store-statement-lines` | `@perm.canBiz('biz:finance')` | 能提前判 | 默认构建 | 已入网关 | BizStorePointsController |
| GET | `/biz/points/store-statements` | `@perm.canBiz('biz:finance')` | 能提前判 | 默认构建 | 已入网关 | BizStorePointsController |
| GET | `/biz/points/store-terms` | `@perm.canBiz('biz:finance')` | 能提前判 | 默认构建 | 已入网关 | BizStorePointsAgreementController |
| GET | `/biz/points/store-types` | `@perm.canBiz('biz:finance')` | 能提前判 | 默认构建 | 已入网关 | BizStorePointsController |

### pricing-manual-adjustments（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/biz/pricing-manual-adjustments/{no}:approve` | `@perm.canBiz('biz:finance')` | 能提前判 | 默认构建 | 已入网关 | BizPricingManualAdjustmentController |

### pricing-manual-policies（2）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/pricing-manual-policies/{currency}/{reasonCode}/{kind}` | `@perm.canBiz('biz:finance')` | 能提前判 | 默认构建 | 已入网关 | BizPricingManualAdjustmentController |
| PUT | `/biz/pricing-manual-policies/{currency}/{reasonCode}/{kind}` | `@perm.canBiz('biz:finance')` | 能提前判 | 默认构建 | 已入网关 | BizPricingManualAdjustmentController |

### service-orders（3）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/biz/service-orders/counter-quotes/{operationNo}/acceptances` | `@perm.canBiz('trade:checkout:prepare') and @perm.canBiz('biz:order:view') and @perm.canBiz('pay:cash:receive') and @perm.canBiz('trade:manual-service:confirm')` | 能提前判 | 默认构建 | 已入网关 | BizManualServiceAdmissionController |
| POST | `/biz/service-orders/counter-quotes/{operationNo}/orders` | `@perm.canBiz('trade:checkout:prepare') and @perm.canBiz('biz:order:view') and @perm.canBiz('pay:cash:receive') and @perm.canBiz('trade:manual-service:confirm')` | 能提前判 | 默认构建 | 已入网关 | BizManualServiceAdmissionController |
| POST | `/biz/service-orders/{orderNo}/manual-receipts` | `@perm.canBiz('pay:cash:receive') and @perm.canBiz('trade:manual-service:confirm')` | 能提前判 | 默认构建 | 已入网关 | BizManualServiceAdmissionController |

### settle（11）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/settle/batch` | `@perm.canBiz('biz:finance')` | 能提前判 | 默认构建 | 已入网关 | BizSettleController |
| GET | `/biz/settle/bills` | `@perm.canBiz('biz:finance')` | 能提前判 | 默认构建 | 已入网关 | BizSettleController |
| GET | `/biz/settle/bills/{settleNo}` | `@perm.canBiz('biz:finance')` | 能提前判 | 默认构建 | 已入网关 | BizSettleController |
| GET | `/biz/settle/daily-flow` | `@perm.canBiz('biz:finance')` | 能提前判 | 默认构建 | 已入网关 | BizSettleController |
| GET | `/biz/settle/income` | `@perm.canBiz('biz:finance')` | 能提前判 | 默认构建 | 已入网关 | BizSettleController |
| GET | `/biz/settle/invoice-pending` | `@perm.canBiz('biz:finance')` | 能提前判 | 默认构建 | 已入网关 | BizSettleController |
| GET | `/biz/settle/invoice-title` | `@perm.canBiz('biz:finance')` | 能提前判 | 默认构建 | 已入网关 | BizSettleController |
| GET | `/biz/settle/invoices` | `@perm.canBiz('biz:finance')` | 能提前判 | 默认构建 | 已入网关 | BizSettleController |
| POST | `/biz/settle/invoices` | `@perm.canBiz('biz:finance')` | 能提前判 | 默认构建 | 已入网关 | BizSettleController |
| GET | `/biz/settle/rate-card` | `@perm.canBiz('biz:finance')` | 能提前判 | 默认构建 | 已入网关 | BizSettleController |
| GET | `/biz/settle/statement` | `@perm.canBiz('biz:finance')` | 能提前判 | 默认构建 | 已入网关 | BizSettleController |

### stored-value（32）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/stored-value/account-directory` | `@perm.canBiz('pay:sv:account:read')` | 能提前判 | 默认构建 | 已入网关 | BizStoredValueMerchantQueryController |
| GET | `/biz/stored-value/accounts` | `@perm.canBiz('pay:sv:account:read')` | 能提前判 | 默认构建 | 已入网关 | BizStoredValueController |
| GET | `/biz/stored-value/accounts/{accountRef}` | `@perm.canBiz('pay:sv:account:read')` | 能提前判 | 默认构建 | 已入网关 | BizStoredValueMerchantQueryController |
| GET | `/biz/stored-value/brand-capability` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizStoredValueBrandActivationController |
| POST | `/biz/stored-value/consumptions` | `@perm.canBiz('pay:sv:consume:create')` | 能提前判 | 默认构建 | 已入网关 | BizStoredValueController |
| GET | `/biz/stored-value/consumptions/orders/{orderNo}` | `@perm.canBiz('pay:sv:consume:create')` | 能提前判 | 默认构建 | 已入网关 | BizStoredValueController |
| POST | `/biz/stored-value/consumptions/orders/{orderNo}/cancel` | `@perm.canBiz('pay:sv:consume:create')` | 能提前判 | 默认构建 | 已入网关 | BizStoredValueController |
| POST | `/biz/stored-value/consumptions/preview` | `@perm.canBiz('pay:sv:consume:create')` | 能提前判 | 默认构建 | 已入网关 | BizStoredValueController |
| POST | `/biz/stored-value/consumptions/resolve` | `@perm.canBiz('pay:sv:consume:create')` | 能提前判 | 默认构建 | 已入网关 | BizStoredValueController |
| GET | `/biz/stored-value/context` | `@perm.canAnyBiz('pay:sv:plan:read','pay:sv:plan:manage','pay:sv:account:read','pay:sv:recharge:create')` | 能提前判 | 默认构建 | 已入网关 | BizStoredValuePlanController |
| GET | `/biz/stored-value/customer-candidates` | `@perm.canBiz('biz:customer') and @perm.canBiz('pay:sv:recharge:create')` | 能提前判 | 默认构建 | 已入网关 | BizStoredValueCustomerController |
| POST | `/biz/stored-value/customer-selections` | `@perm.canBiz('biz:customer') and @perm.canBiz('pay:sv:recharge:create')` | 能提前判 | 默认构建 | 已入网关 | BizStoredValueCustomerController |
| GET | `/biz/stored-value/governance-capability` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizStoredValueGovernanceCapabilityController |
| POST | `/biz/stored-value/plan-recharges` | `@perm.canBiz('pay:sv:recharge:create')` | 能提前判 | 默认构建 | 已入网关 | BizStoredValuePlanRechargeController |
| POST | `/biz/stored-value/plan-recharges/cancel-create` | `@perm.canBiz('pay:sv:recharge:create')` | 能提前判 | 默认构建 | 已入网关 | BizStoredValuePlanRechargeController |
| POST | `/biz/stored-value/plan-recharges/confirm-offline` | `@perm.canBiz('pay:sv:recharge:create') and @perm.canBiz('pay:sv:recharge:receive')` | 能提前判 | 默认构建 | 已入网关 | BizStoredValuePlanRechargeController |
| POST | `/biz/stored-value/plan-recharges/resolve` | `@perm.canBiz('pay:sv:recharge:create')` | 能提前判 | 默认构建 | 已入网关 | BizStoredValuePlanRechargeController |
| GET | `/biz/stored-value/plan-recharges/{rechargeNo}` | `@perm.canAnyBiz('pay:sv:recharge:read','pay:sv:recharge:create')` | 能提前判 | 默认构建 | 已入网关 | BizStoredValuePlanRechargeController |
| POST | `/biz/stored-value/plan-recharges/{rechargeNo}/cancel` | `@perm.canBiz('pay:sv:recharge:create')` | 能提前判 | 默认构建 | 已入网关 | BizStoredValuePlanRechargeController |
| POST | `/biz/stored-value/plan-recharges/{rechargeNo}/receive` | `@perm.canBiz('pay:sv:recharge:receive')` | 能提前判 | 默认构建 | 已入网关 | BizStoredValuePlanRechargeController |
| POST | `/biz/stored-value/plan-recharges/{rechargeNo}/submit` | `@perm.canBiz('pay:sv:recharge:create')` | 能提前判 | 默认构建 | 已入网关 | BizStoredValuePlanRechargeController |
| GET | `/biz/stored-value/plans` | `@perm.canAnyBiz('pay:sv:plan:read','pay:sv:plan:manage','pay:sv:recharge:create')` | 能提前判 | 默认构建 | 已入网关 | BizStoredValuePlanController |
| POST | `/biz/stored-value/plans` | `@perm.canBiz('pay:sv:plan:manage')` | 能提前判 | 默认构建 | 已入网关 | BizStoredValuePlanController |
| GET | `/biz/stored-value/plans/{planNo}` | `@perm.canAnyBiz('pay:sv:plan:read','pay:sv:plan:manage','pay:sv:recharge:create')` | 能提前判 | 默认构建 | 已入网关 | BizStoredValuePlanController |
| PUT | `/biz/stored-value/plans/{planNo}/status` | `@perm.canBiz('pay:sv:plan:manage')` | 能提前判 | 默认构建 | 已入网关 | BizStoredValuePlanController |
| POST | `/biz/stored-value/recharges` | `@perm.canBiz('pay:sv:recharge:create')` | 能提前判 | 默认构建 | 已入网关 | BizStoredValueController |
| GET | `/biz/stored-value/recharges/{rechargeNo}` | `@perm.canBiz('pay:sv:recharge:read')` | 能提前判 | 默认构建 | 已入网关 | BizStoredValueController |
| POST | `/biz/stored-value/recharges/{rechargeNo}/cancel` | `@perm.canBiz('pay:sv:recharge:create')` | 能提前判 | 默认构建 | 已入网关 | BizStoredValueController |
| POST | `/biz/stored-value/recharges/{rechargeNo}/receive` | `@perm.canBiz('pay:sv:recharge:receive')` | 能提前判 | 默认构建 | 已入网关 | BizStoredValueController |
| POST | `/biz/stored-value/recharges/{rechargeNo}/submit` | `@perm.canBiz('pay:sv:recharge:create')` | 能提前判 | 默认构建 | 已入网关 | BizStoredValueController |
| GET | `/biz/stored-value/records` | `@perm.canBiz('pay:sv:account:read')` | 能提前判 | 默认构建 | 已入网关 | BizStoredValueMerchantQueryController |
| GET | `/biz/stored-value/records/{recordNo}` | `@perm.canBiz('pay:sv:account:read')` | 能提前判 | 默认构建 | 已入网关 | BizStoredValueMerchantQueryController |

### stores（27）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/stores/{storeNo}/receivables/{bizNo}` | `@perm.canBiz('mch:pay:order:view')` | 能提前判 | 默认构建 | 已入网关 | BizReceivableController |
| GET | `/biz/stores/{storeNo}/stored-value/activities` | 商家身份 | 只要求登录 | 默认构建 | 未部署 | BizStoredValueActivityController |
| POST | `/biz/stores/{storeNo}/stored-value/activities` | 商家身份 | 只要求登录 | 默认构建 | 未部署 | BizStoredValueActivityController |
| GET | `/biz/stores/{storeNo}/stored-value/activities/{activityNo}` | 商家身份 | 只要求登录 | 默认构建 | 未部署 | BizStoredValueActivityController |
| POST | `/biz/stores/{storeNo}/stored-value/activities/{activityNo}/checks` | 商家身份 | 只要求登录 | 默认构建 | 未部署 | BizStoredValueActivityController |
| POST | `/biz/stores/{storeNo}/stored-value/activities/{activityNo}/copies` | 商家身份 | 只要求登录 | 默认构建 | 未部署 | BizStoredValueActivityController |
| PUT | `/biz/stores/{storeNo}/stored-value/activities/{activityNo}/draft` | 商家身份 | 只要求登录 | 默认构建 | 未部署 | BizStoredValueActivityController |
| POST | `/biz/stores/{storeNo}/stored-value/activities/{activityNo}/stop-issuance` | 商家身份 | 只要求登录 | 默认构建 | 未部署 | BizStoredValueActivityController |
| POST | `/biz/stores/{storeNo}/stored-value/activities/{activityNo}/submissions` | 商家身份 | 只要求登录 | 默认构建 | 未部署 | BizStoredValueActivityController |
| GET | `/biz/stores/{storeNo}/stored-value/activity-context` | 商家身份 | 只要求登录 | 默认构建 | 未部署 | BizStoredValueActivityController |
| GET | `/biz/stores/{storeNo}/stored-value/activity-presets` | 商家身份 | 只要求登录 | 默认构建 | 未部署 | BizStoredValueActivityController |
| GET | `/biz/stores/{storeNo}/stored-value/activity-schedules` | 商家身份 | 只要求登录 | 默认构建 | 未部署 | BizStoredValueActivityController |
| GET | `/biz/stores/{storeNo}/stored-value/customer-candidates` | `@perm.canBiz('biz:customer') and @perm.canAnyBiz('pay:sv:recharge:create','pay:sv:account:read')` | 能提前判 | 默认构建 | 已入网关 | BizMemberScopeController |
| POST | `/biz/stores/{storeNo}/stored-value/customer-selections` | `@perm.canBiz('biz:customer') and @perm.canAnyBiz('pay:sv:recharge:create','pay:sv:account:read')` | 能提前判 | 默认构建 | 已入网关 | BizMemberScopeController |
| GET | `/biz/stores/{storeNo}/stored-value/customer-selections/{customerRef}/accounts` | `@perm.canBiz('pay:sv:account:read')` | 能提前判 | 默认构建 | 已入网关 | BizStoredValueWalletController |
| GET | `/biz/stores/{storeNo}/stored-value/customer-selections/{customerRef}/records` | `@perm.canBiz('pay:sv:account:read')` | 能提前判 | 默认构建 | 已入网关 | BizStoredValueWalletController |
| GET | `/biz/stores/{storeNo}/stored-value/effective-policy` | 商家身份 | 只要求登录 | 默认构建 | 未部署 | BizStoredValuePolicyController |
| POST | `/biz/stores/{storeNo}/stored-value/issuer-recharges/cancel-create` | `@perm.canBiz('pay:sv:recharge:create')` | 能提前判 | 默认构建 | 已入网关 | BizStoredValueIssuerOperationsController |
| POST | `/biz/stores/{storeNo}/stored-value/issuer-recharges/confirm-offline` | `@perm.canBiz('pay:sv:recharge:create') and @perm.canBiz('pay:sv:recharge:receive')` | 能提前判 | 默认构建 | 已入网关 | BizStoredValueIssuerOperationsController |
| POST | `/biz/stores/{storeNo}/stored-value/issuer-recharges/resolve` | `@perm.canBiz('pay:sv:recharge:create')` | 能提前判 | 默认构建 | 已入网关 | BizStoredValueIssuerOperationsController |
| GET | `/biz/stores/{storeNo}/stored-value/issuer-recharges/{rechargeNo}` | `@perm.canAnyBiz('pay:sv:recharge:read','pay:sv:recharge:create')` | 能提前判 | 默认构建 | 已入网关 | BizStoredValueIssuerOperationsController |
| POST | `/biz/stores/{storeNo}/stored-value/issuer-recharges/{rechargeNo}/cancel` | `@perm.canBiz('pay:sv:recharge:create')` | 能提前判 | 默认构建 | 已入网关 | BizStoredValueIssuerOperationsController |
| GET | `/biz/stores/{storeNo}/stored-value/issuer-records` | `@perm.canBiz('pay:sv:account:read')` | 能提前判 | 默认构建 | 已入网关 | BizStoredValueIssuerOperationsController |
| GET | `/biz/stores/{storeNo}/stored-value/issuer-records/{recordNo}` | `@perm.canBiz('pay:sv:account:read')` | 能提前判 | 默认构建 | 已入网关 | BizStoredValueIssuerOperationsController |
| GET | `/biz/stores/{storeNo}/stored-value/issuers` | `@perm.canAnyBiz('pay:sv:account:read','pay:sv:recharge:create','pay:sv:plan:manage','pay:sv:plan:read')` | 能提前判 | 默认构建 | 已入网关 | BizStoredValueIssuerOperationsController |
| GET | `/biz/stores/{storeNo}/stored-value/recharge-options` | `@perm.canBiz('pay:sv:recharge:create')` | 能提前判 | 默认构建 | 已入网关 | BizStoredValueIssuerOperationsController |
| GET | `/biz/stores/{storeNo}/stored-value/store-summary` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizStoredValueReportController |

## 零售（580）

网关判权：能提前判 451、只要求登录 123、留给入口判 6。构建：默认构建 512、插件 shop-commerce-integration 68。生产：已入网关 468、未部署 112。

### activities（5）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/activities` | `@perm.canBiz('biz:campaign')` | 能提前判 | 默认构建 | 已入网关 | BizActivityController、BizVersionedActivityController |
| POST | `/biz/activities` | `@perm.canBiz('biz:campaign')` | 能提前判 | 默认构建 | 已入网关 | BizActivityController、BizVersionedActivityController |
| GET | `/biz/activities/{activityNo}` | `@perm.canBiz('biz:campaign')` | 能提前判 | 默认构建 | 已入网关 | BizActivityController、BizVersionedActivityController |
| POST | `/biz/activities/{activityNo}/publish` | `@perm.canBiz('biz:campaign')` | 能提前判 | 默认构建 | 已入网关 | BizVersionedActivityController |
| PUT | `/biz/activities/{activityNo}/status` | `@perm.canBiz('biz:campaign')` | 能提前判 | 默认构建 | 已入网关 | BizActivityController、BizVersionedActivityController |

### activity-conflicts（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/biz/activity-conflicts` | `@perm.canBiz('biz:campaign')` | 能提前判 | 默认构建 | 已入网关 | BizActivityController |

### activity-presets（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/activity-presets` | `@perm.canBiz('biz:campaign')` | 能提前判 | 默认构建 | 已入网关 | BizActivityPresetController |

### after-sale（8）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/after-sale` | `@perm.canBiz('biz:aftersale')` | 能提前判 | 默认构建 | 已入网关 | BizAfterSaleController |
| GET | `/biz/after-sale/by-order/{subOrderNo}` | `@perm.canBiz('biz:aftersale')` | 能提前判 | 默认构建 | 已入网关 | BizAfterSaleController |
| GET | `/biz/after-sale/worklist` | `@perm.canBiz('biz:aftersale')` | 能提前判 | 默认构建 | 已入网关 | BizAfterSaleController |
| GET | `/biz/after-sale/{afterSaleNo}` | `@perm.canBiz('biz:aftersale')` | 能提前判 | 默认构建 | 已入网关 | BizAfterSaleController |
| POST | `/biz/after-sale/{afterSaleNo}/approve` | `@perm.canBiz('biz:aftersale')` | 能提前判 | 默认构建 | 已入网关 | BizAfterSaleController |
| POST | `/biz/after-sale/{afterSaleNo}/confirm-refund` | `@perm.canBiz('biz:aftersale')` | 能提前判 | 默认构建 | 已入网关 | BizAfterSaleController |
| POST | `/biz/after-sale/{afterSaleNo}/receive` | `@perm.canBiz('biz:aftersale')` | 能提前判 | 默认构建 | 已入网关 | BizAfterSaleController |
| POST | `/biz/after-sale/{afterSaleNo}/reject` | `@perm.canBiz('biz:aftersale')` | 能提前判 | 默认构建 | 已入网关 | BizAfterSaleController |

### app（6）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/app/legal-subjects` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizAppOperatingEntityController |
| GET | `/biz/app/operating-entities` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizAppOperatingEntityController |
| POST | `/biz/app/operating-entities` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizAppOperatingEntityController |
| GET | `/biz/app/operating-entities/context` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizAppOperatingEntityController |
| GET | `/biz/app/operating-entities/create-results/{key}` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizAppOperatingEntityController |
| GET | `/biz/app/operating-entities/{id}` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizAppOperatingEntityController |

### appointment-slots（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/biz/appointment-slots/{slotNo}/close` | `@perm.canBiz('biz:store')` | 能提前判 | 默认构建 | 已入网关 | BizStoreFulfillmentController |

### business-types（3）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/business-types` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizFoundationController |
| GET | `/biz/business-types/{typeCode}/feature-matrix` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizStoreFeatureController |
| GET | `/biz/business-types/{typeCode}/plans` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizFoundationController |

### campaign（3）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/campaign` | `@perm.canBiz('biz:campaign')` | 能提前判 | 默认构建 | 已入网关 | BizCampaignController |
| POST | `/biz/campaign` | `@perm.canBiz('biz:campaign')` | 能提前判 | 默认构建 | 已入网关 | BizCampaignController |
| POST | `/biz/campaign/{campaignNo}/toggle` | `@perm.canBiz('biz:campaign')` | 能提前判 | 默认构建 | 已入网关 | BizCampaignController |

### card-product-bindings（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/biz/card-product-bindings` | `@perm.canBiz('biz:goods')` | 能提前判 | 默认构建 | 已入网关 | BizCardProductBindingController |

### card-product-presets（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/card-product-presets` | `@perm.canBiz('biz:goods')` | 能提前判 | 默认构建 | 已入网关 | BizActivityPresetController |

### category（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/category/tree` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizGoodsController |

### communities（4）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/communities` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizAuthController |
| GET | `/biz/communities/applies` | `@perm.canBiz('biz:store')` | 能提前判 | 默认构建 | 已入网关 | BizCommunityApplyController |
| POST | `/biz/communities/apply` | `@perm.canBiz('biz:store')` | 能提前判 | 默认构建 | 已入网关 | BizCommunityApplyController |
| POST | `/biz/communities/from-map` | `@perm.canBiz('biz:store')` | 能提前判 | 默认构建 | 已入网关 | BizCommunityApplyController |

### context（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/context` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizPickupController |

### coupon-issues（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/coupon-issues` | `@perm.canBiz('biz:campaign')` | 能提前判 | 默认构建 | 已入网关 | BizCouponController |

### coupon-redeem（2）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/biz/coupon-redeem` | `@perm.canBiz('biz:verify')` | 能提前判 | 默认构建 | 已入网关 | BizCouponController |
| GET | `/biz/coupon-redeem/{code}` | `@perm.canBiz('biz:verify')` | 能提前判 | 默认构建 | 已入网关 | BizCouponController |

### coupons（5）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/coupons` | `@perm.canBiz('biz:campaign')` | 能提前判 | 默认构建 | 已入网关 | BizCouponController |
| POST | `/biz/coupons` | `@perm.canBiz('biz:campaign')` | 能提前判 | 默认构建 | 已入网关 | BizCouponController |
| GET | `/biz/coupons/{couponNo}` | `@perm.canBiz('biz:campaign')` | 能提前判 | 默认构建 | 已入网关 | BizCouponController |
| POST | `/biz/coupons/{couponNo}/issue` | `@perm.canBiz('biz:campaign')` | 能提前判 | 默认构建 | 已入网关 | BizCouponController |
| PUT | `/biz/coupons/{couponNo}/status` | `@perm.canBiz('biz:campaign')` | 能提前判 | 默认构建 | 已入网关 | BizCouponController |

### cross-store（2）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/cross-store/compare` | `@perm.canBiz('biz:customer')` | 能提前判 | 默认构建 | 已入网关 | BizCrossStoreController |
| GET | `/biz/cross-store/overview` | `@perm.canBiz('biz:customer')` | 能提前判 | 默认构建 | 已入网关 | BizCrossStoreController |

### customers（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/customers` | `@perm.canBiz('biz:customer')` | 能提前判 | 默认构建 | 已入网关 | BizDashboardController |

### delivery（2）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/delivery/rule` | `@perm.canBiz('biz:store')` | 能提前判 | 默认构建 | 已入网关 | BizDashboardController |
| POST | `/biz/delivery/rule` | `@perm.canBiz('biz:store')` | 能提前判 | 默认构建 | 已入网关 | BizDashboardController |

### entities（15）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/entities` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizEntityController |
| POST | `/biz/entities` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizEntityController |
| GET | `/biz/entities/create-results/{requestNo}` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizEntityController |
| POST | `/biz/entities/{entity}/channel-requests` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizEntityProfileController |
| GET | `/biz/entities/{entity}/commands/{request}` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizEntityProfileController |
| POST | `/biz/entities/{entity}/contact` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizEntityProfileController |
| POST | `/biz/entities/{entity}/files` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizEntityProfileController |
| GET | `/biz/entities/{entity}/files/{file}/preview` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizEntityProfileController |
| GET | `/biz/entities/{entity}/profile-draft` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizEntityProfileController |
| POST | `/biz/entities/{entity}/profile-draft` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizEntityProfileController |
| GET | `/biz/entities/{entity}/profile-template` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizEntityProfileController |
| POST | `/biz/entities/{entity}/recognitions` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizEntityRecognitionController |
| POST | `/biz/entities/{entity}/review-submissions` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizEntityProfileController |
| POST | `/biz/entities/{entity}/revision-discards` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizEntityProfileController |
| POST | `/biz/entities/{entity}/revisions` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizEntityProfileController |

### entity（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/entity/{entityNo}` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizEntityController |

### entity-recognitions（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/biz/entity-recognitions` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizEntityRecognitionController |

### express（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/express/companies` | `@perm.canAnyBiz('biz:ship','biz:store')` | 能提前判 | 默认构建 | 已入网关 | BizExpressCompanyController |

### feedback（2）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/biz/feedback` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizFeedbackController |
| GET | `/biz/feedback/mine` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizFeedbackController |

### freight-template（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/freight-template/list` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizExpressController |

### fulfillment（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/fulfillment/carriers` | `@perm.canBiz('biz:stock')` | 能提前判 | 默认构建 | 已入网关 | BizCarrierController |

### geo（5）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/geo/estates` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizGeoController |
| GET | `/biz/geo/estates/counts` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizGeoController |
| POST | `/biz/geo/geocode` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizGeoController |
| GET | `/biz/geo/reverse` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizGeoController |
| GET | `/biz/geo/tips` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizGeoController |

### goods（27）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/goods` | `@perm.canBiz('biz:stock')` | 能提前判 | 默认构建 | 已入网关 | BizGoodsController |
| POST | `/biz/goods/describe` | `@perm.canBiz('biz:goods')` | 能提前判 | 默认构建 | 已入网关 | BizGoodsController |
| GET | `/biz/goods/inv-mode` | `@perm.canBiz('biz:stock')` | 能提前判 | 默认构建 | 已入网关 | BizInvManagedController |
| POST | `/biz/goods/parse-text` | `@perm.canBiz('biz:goods')` | 能提前判 | 默认构建 | 已入网关 | BizGoodsController |
| POST | `/biz/goods/recognize` | `@perm.canBiz('biz:goods')` | 能提前判 | 默认构建 | 已入网关 | BizGoodsController |
| POST | `/biz/goods/save` | `@perm.canBiz('biz:goods')` | 能提前判 | 默认构建 | 已入网关 | BizGoodsController |
| POST | `/biz/goods/zip-import` | `@perm.canBiz('biz:goods')` | 能提前判 | 默认构建 | 已入网关 | BizZipImportController |
| POST | `/biz/goods/zip-plan` | `@perm.canBiz('biz:goods')` | 能提前判 | 默认构建 | 已入网关 | BizGoodsController |
| GET | `/biz/goods/{goodsNo}` | `@perm.canBiz('biz:stock')` | 能提前判 | 默认构建 | 已入网关 | BizGoodsController |
| GET | `/biz/goods/{goodsNo}/draft` | `@perm.canBiz('biz:goods')` | 能提前判 | 默认构建 | 已入网关 | BizGoodsController |
| POST | `/biz/goods/{goodsNo}/draft/discard` | `@perm.canBiz('biz:goods')` | 能提前判 | 默认构建 | 已入网关 | BizGoodsController |
| PUT | `/biz/goods/{goodsNo}/inv-mode` | `@perm.canBiz('biz:goods')` | 能提前判 | 默认构建 | 已入网关 | BizInvManagedController |
| GET | `/biz/goods/{goodsNo}/pay-mode` | `@perm.canBiz('biz:goods')` | 能提前判 | 默认构建 | 已入网关 | BizGoodsController |
| PUT | `/biz/goods/{goodsNo}/pay-mode` | `@perm.canBiz('biz:goods')` | 能提前判 | 默认构建 | 已入网关 | BizGoodsController |
| POST | `/biz/goods/{goodsNo}/presale` | `@perm.canBiz('biz:goods')` | 能提前判 | 默认构建 | 已入网关 | BizGoodsController |
| POST | `/biz/goods/{goodsNo}/publish` | `@perm.canBiz('biz:goods')` | 能提前判 | 默认构建 | 已入网关 | BizGoodsController |
| GET | `/biz/goods/{goodsNo}/publish-preview` | `@perm.canBiz('biz:goods')` | 能提前判 | 默认构建 | 已入网关 | BizGoodsController |
| GET | `/biz/goods/{goodsNo}/revisions` | `@perm.canBiz('biz:goods')` | 能提前判 | 默认构建 | 已入网关 | BizGoodsController |
| GET | `/biz/goods/{goodsNo}/revisions/{revisionNo}` | `@perm.canBiz('biz:goods')` | 能提前判 | 默认构建 | 已入网关 | BizGoodsController |
| POST | `/biz/goods/{goodsNo}/revisions/{revisionNo}/fork` | `@perm.canBiz('biz:goods')` | 能提前判 | 默认构建 | 已入网关 | BizGoodsController |
| GET | `/biz/goods/{goodsNo}/skus/{skuNo}/pricing-policy` | `@perm.canBiz('biz:stock')` | 能提前判 | 默认构建 | 已入网关 | BizGoodsPricingPolicyController |
| PUT | `/biz/goods/{goodsNo}/skus/{skuNo}/pricing-policy` | `@perm.canBiz('biz:stock')` | 能提前判 | 默认构建 | 已入网关 | BizGoodsPricingPolicyController |
| POST | `/biz/goods/{goodsNo}/stock` | `@perm.canBiz('biz:stock')` | 能提前判 | 默认构建 | 已入网关 | BizGoodsController |
| POST | `/biz/goods/{goodsNo}/store-price` | `@perm.canBiz('biz:goods')` | 能提前判 | 默认构建 | 已入网关 | BizGoodsController |
| POST | `/biz/goods/{goodsNo}/store-stock` | `@perm.canBiz('biz:stock')` | 能提前判 | 默认构建 | 已入网关 | BizGoodsController |
| POST | `/biz/goods/{goodsNo}/submit` | `@perm.canBiz('biz:goods')` | 能提前判 | 默认构建 | 已入网关 | BizGoodsController |
| POST | `/biz/goods/{goodsNo}/toggle` | `@perm.canBiz('biz:goods')` | 能提前判 | 默认构建 | 已入网关 | BizGoodsController |

### group（3）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/group/pickups` | `@perm.canBiz('biz:campaign')` | 能提前判 | 默认构建 | 已入网关 | BizGroupPickupController |
| GET | `/biz/group/{groupNo}` | `@perm.canBiz('biz:campaign')` | 能提前判 | 默认构建 | 已入网关 | BizCampaignController |
| POST | `/biz/group/{groupNo}/dissolve` | `@perm.canBiz('biz:campaign')` | 能提前判 | 默认构建 | 已入网关 | BizCampaignController |

### group-request（2）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/group-request/pool` | `@perm.canBiz('biz:campaign')` | 能提前判 | 默认构建 | 已入网关 | BizQuoteController |
| POST | `/biz/group-request/{requestNo}/quote` | `@perm.canBiz('biz:campaign')` | 能提前判 | 默认构建 | 已入网关 | BizQuoteController |

### groups（2）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/groups` | `@perm.canBiz('biz:campaign')` | 能提前判 | 默认构建 | 已入网关 | BizCampaignController |
| POST | `/biz/groups` | `@perm.canBiz('biz:campaign')` | 能提前判 | 默认构建 | 已入网关 | BizCampaignController |

### invoice-applications（9）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/invoice-applications` | `@perm.canBiz('biz:invoice:read')` | 能提前判 | 默认构建 | 未部署 | BizInvoiceApplicationController |
| POST | `/biz/invoice-applications` | `@perm.canBiz('biz:invoice:issue')` | 能提前判 | 默认构建 | 未部署 | BizInvoiceApplicationController |
| GET | `/biz/invoice-applications/{applicationNo}` | `@perm.canBiz('biz:invoice:read')` | 能提前判 | 默认构建 | 未部署 | BizInvoiceApplicationController |
| POST | `/biz/invoice-applications/{applicationNo}/cancellations` | `@perm.canBiz('biz:invoice:setup')` | 能提前判 | 默认构建 | 未部署 | BizInvoiceApplicationController |
| GET | `/biz/invoice-applications/{applicationNo}/events` | `@perm.canBiz('biz:invoice:read')` | 能提前判 | 默认构建 | 未部署 | BizInvoiceApplicationController |
| POST | `/biz/invoice-applications/{applicationNo}/issuances` | `@perm.canBiz('biz:invoice:issue')` | 能提前判 | 默认构建 | 未部署 | BizInvoiceApplicationController |
| POST | `/biz/invoice-applications/{applicationNo}/rejections` | `@perm.canBiz('biz:invoice:setup')` | 能提前判 | 默认构建 | 未部署 | BizInvoiceApplicationController |
| GET | `/biz/invoice-applications/{applicationNo}/revisions` | `@perm.canBiz('biz:invoice:read')` | 能提前判 | 默认构建 | 未部署 | BizInvoiceApplicationController |
| POST | `/biz/invoice-applications/{applicationNo}/revisions` | `@perm.canBiz('biz:invoice:issue')` | 能提前判 | 默认构建 | 未部署 | BizInvoiceApplicationController |

### invoice-channel-accounts（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/invoice-channel-accounts` | `@perm.canBiz('biz:invoice:setup')` | 能提前判 | 默认构建 | 未部署 | BizInvoiceChannelController |

### invoice-channel-applications（4）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/invoice-channel-applications` | `@perm.canBiz('biz:invoice:setup')` | 能提前判 | 默认构建 | 未部署 | BizInvoiceChannelController |
| POST | `/biz/invoice-channel-applications` | `@perm.canBiz('biz:invoice:setup')` | 能提前判 | 默认构建 | 未部署 | BizInvoiceChannelController |
| GET | `/biz/invoice-channel-applications/{applicationNo}` | `@perm.canBiz('biz:invoice:setup')` | 能提前判 | 默认构建 | 未部署 | BizInvoiceChannelController |
| POST | `/biz/invoice-channel-applications/{applicationNo}/revisions` | `@perm.canBiz('biz:invoice:setup')` | 能提前判 | 默认构建 | 未部署 | BizInvoiceChannelController |

### invoice-channel-routes（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/invoice-channel-routes` | `@perm.canBiz('biz:invoice:setup')` | 能提前判 | 默认构建 | 未部署 | BizInvoiceChannelController |

### invoice-setup（15）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/invoice-setup/profile` | `@perm.canBiz('biz:invoice:setup')` | 能提前判 | 默认构建 | 未部署 | BizInvoiceSetupController |
| PUT | `/biz/invoice-setup/profile` | `@perm.canBiz('biz:invoice:setup')` | 能提前判 | 默认构建 | 未部署 | BizInvoiceSetupController |
| POST | `/biz/invoice-setup/provider-ranges` | `@perm.canBiz('biz:invoice:setup')` | 能提前判 | 默认构建 | 未部署 | BizInvoiceSetupController |
| GET | `/biz/invoice-setup/provider-tracks` | `@perm.canBiz('biz:invoice:setup')` | 能提前判 | 默认构建 | 未部署 | BizInvoiceSetupController |
| POST | `/biz/invoice-setup/provider-tracks` | `@perm.canBiz('biz:invoice:setup')` | 能提前判 | 默认构建 | 未部署 | BizInvoiceSetupController |
| POST | `/biz/invoice-setup/provider-verification` | `@perm.canBiz('biz:invoice:setup')` | 能提前判 | 默认构建 | 未部署 | BizInvoiceSetupController |
| GET | `/biz/invoice-setup/providers` | `@perm.canBiz('biz:invoice:setup')` | 能提前判 | 默认构建 | 未部署 | BizInvoiceSetupController |
| GET | `/biz/invoice-setup/ranges` | `@perm.canBiz('biz:invoice:setup')` | 能提前判 | 默认构建 | 未部署 | BizInvoiceSetupController |
| POST | `/biz/invoice-setup/ranges` | `@perm.canBiz('biz:invoice:setup')` | 能提前判 | 默认构建 | 未部署 | BizInvoiceSetupController |
| GET | `/biz/invoice-setup/store-items` | `@perm.canBiz('biz:invoice:read')` | 能提前判 | 默认构建 | 未部署 | BizInvoiceStoreSetupController |
| POST | `/biz/invoice-setup/store-items` | `@perm.canBiz('biz:invoice:setup')` | 能提前判 | 默认构建 | 未部署 | BizInvoiceStoreSetupController |
| DELETE | `/biz/invoice-setup/store-items/{storeItemNo}` | `@perm.canBiz('biz:invoice:setup')` | 能提前判 | 默认构建 | 未部署 | BizInvoiceStoreSetupController |
| PUT | `/biz/invoice-setup/store-items/{storeItemNo}` | `@perm.canBiz('biz:invoice:setup')` | 能提前判 | 默认构建 | 未部署 | BizInvoiceStoreSetupController |
| GET | `/biz/invoice-setup/store-settings` | `@perm.canBiz('biz:invoice:read')` | 能提前判 | 默认构建 | 未部署 | BizInvoiceStoreSetupController |
| PUT | `/biz/invoice-setup/store-settings` | `@perm.canBiz('biz:invoice:setup')` | 能提前判 | 默认构建 | 未部署 | BizInvoiceStoreSetupController |

### management（32）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/management/brand-changes/{changeNo}` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizStoreOperatingContextController |
| POST | `/biz/management/brand-changes/{changeNo}/decisions` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizStoreOperatingContextController |
| GET | `/biz/management/brands` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizBrandController |
| POST | `/biz/management/brands` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizBrandController |
| GET | `/biz/management/brands/{brandNo}` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizBrandController |
| PUT | `/biz/management/brands/{brandNo}` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizBrandController |
| GET | `/biz/management/brands/{brandNo}/audit-events` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizBrandController |
| GET | `/biz/management/brands/{brandNo}/commands` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizBrandController |
| PUT | `/biz/management/brands/{brandNo}/legal-entity` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizBrandController |
| GET | `/biz/management/brands/{brandNo}/legal-entity-candidates` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizBrandController |
| GET | `/biz/management/brands/{brandNo}/legal-entity/commands` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizBrandController |
| GET | `/biz/management/brands/{brandNo}/marketing/activities` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizBrandMarketingController |
| GET | `/biz/management/brands/{brandNo}/marketing/commands` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizBrandMarketingController |
| POST | `/biz/management/brands/{brandNo}/marketing/commands` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizBrandMarketingController |
| GET | `/biz/management/brands/{brandNo}/marketing/context` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizBrandMarketingController |
| GET | `/biz/management/brands/{brandNo}/marketing/cost-rules` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizBrandMarketingController |
| GET | `/biz/management/brands/{brandNo}/marketing/cost-statements` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizBrandMarketingController |
| GET | `/biz/management/brands/{brandNo}/marketing/coupon-issues` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizBrandMarketingController |
| GET | `/biz/management/brands/{brandNo}/marketing/coupons` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizBrandMarketingController |
| POST | `/biz/management/brands/{brandNo}/status-changes` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizBrandController |
| GET | `/biz/management/brands/{brandNo}/stores` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizBrandController |
| GET | `/biz/management/context` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizManagementController |
| GET | `/biz/management/entities/{entityNo}/payment-merchants` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizManagementController |
| POST | `/biz/management/spaces` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizManagementController |
| GET | `/biz/management/spaces/{managementNo}/entities` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizManagementController |
| GET | `/biz/management/spaces/{managementNo}/grant-commands/{action}` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizManagementController |
| GET | `/biz/management/spaces/{managementNo}/grant-options` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizManagementController |
| POST | `/biz/management/spaces/{managementNo}/grants` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizManagementController |
| DELETE | `/biz/management/spaces/{managementNo}/grants/{grantNo}` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizManagementController |
| GET | `/biz/management/spaces/{managementNo}/recipients` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizManagementController |
| GET | `/biz/management/spaces/{managementNo}/resources` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizManagementController |
| GET | `/biz/management/spaces/{managementNo}/stores` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizManagementController |

### marketing（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/marketing/summary` | `@perm.canBiz('biz:campaign')` | 能提前判 | 默认构建 | 已入网关 | BizPeriodController |

### marketing-summary（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/marketing-summary` | `@perm.canBiz('biz:campaign')` | 能提前判 | 默认构建 | 已入网关 | BizActivityMarketingSummaryController |

### member-code（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/biz/member-code/resolve` | `@perm.canBiz('biz:customer')` | 能提前判 | 默认构建 | 已入网关 | BizMemberCodeController |

### member-reach（4）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/biz/member-reach/plan` | `@perm.canBiz('biz:customer')` | 能提前判 | 默认构建 | 已入网关 | BizMemberController |
| POST | `/biz/member-reach/send` | `@perm.canBiz('biz:campaign')` | 能提前判 | 默认构建 | 已入网关 | BizMemberController |
| GET | `/biz/member-reach/task` | `@perm.canBiz('biz:customer')` | 能提前判 | 默认构建 | 已入网关 | BizMemberController |
| GET | `/biz/member-reach/task/{taskNo}` | `@perm.canBiz('biz:customer')` | 能提前判 | 默认构建 | 已入网关 | BizMemberController |

### member-segments（5）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/member-segments` | `@perm.canBiz('biz:customer')` | 能提前判 | 默认构建 | 已入网关 | BizMemberController |
| POST | `/biz/member-segments` | `@perm.canBiz('biz:customer')` | 能提前判 | 默认构建 | 已入网关 | BizMemberController |
| POST | `/biz/member-segments/preview` | `@perm.canBiz('biz:customer')` | 能提前判 | 默认构建 | 已入网关 | BizMemberController |
| GET | `/biz/member-segments/{segmentNo}` | `@perm.canBiz('biz:customer')` | 能提前判 | 默认构建 | 已入网关 | BizMemberController |
| POST | `/biz/member-segments/{segmentNo}/remove` | `@perm.canBiz('biz:customer')` | 能提前判 | 默认构建 | 已入网关 | BizMemberController |

### member-settings（2）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/member-settings` | `@perm.canBiz('biz:customer')` | 能提前判 | 默认构建 | 已入网关 | BizMemberController |
| PUT | `/biz/member-settings` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 默认构建 | 已入网关 | BizMemberController |

### member-tags（5）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/member-tags` | `@perm.canBiz('biz:customer')` | 能提前判 | 默认构建 | 已入网关 | BizMemberController |
| POST | `/biz/member-tags` | `@perm.canBiz('biz:customer')` | 能提前判 | 默认构建 | 已入网关 | BizMemberController |
| PUT | `/biz/member-tags/{tagNo}` | `@perm.canBiz('biz:customer')` | 能提前判 | 默认构建 | 已入网关 | BizMemberController |
| POST | `/biz/member-tags/{tagNo}/merge` | `@perm.canBiz('biz:customer')` | 能提前判 | 默认构建 | 已入网关 | BizMemberController |
| GET | `/biz/member-tags/{tagNo}/usage` | `@perm.canBiz('biz:customer')` | 能提前判 | 默认构建 | 已入网关 | BizMemberController |

### members（8）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/members` | `@perm.canBiz('biz:customer')` | 能提前判 | 默认构建 | 已入网关 | BizMemberController |
| POST | `/biz/members` | `@perm.canBiz('biz:customer')` | 能提前判 | 默认构建 | 已入网关 | BizMemberController |
| POST | `/biz/members/audience-preview` | `@perm.canBiz('biz:customer')` | 能提前判 | 默认构建 | 已入网关 | BizMemberController |
| GET | `/biz/members/stats` | `@perm.canBiz('biz:customer')` | 能提前判 | 默认构建 | 已入网关 | BizMemberController |
| POST | `/biz/members/tags` | `@perm.canBiz('biz:customer')` | 能提前判 | 默认构建 | 已入网关 | BizMemberController |
| POST | `/biz/members/tags/batch` | `@perm.canBiz('biz:customer')` | 能提前判 | 默认构建 | 已入网关 | BizMemberController |
| GET | `/biz/members/{memberNo}` | `@perm.canBiz('biz:customer')` | 能提前判 | 默认构建 | 已入网关 | BizMemberController |
| PUT | `/biz/members/{memberNo}` | `@perm.canBiz('biz:customer')` | 能提前判 | 默认构建 | 已入网关 | BizMemberController |

### merchant（13）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/biz/merchant/agreement/accept` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizMerchantController |
| GET | `/biz/merchant/apply` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizMerchantController |
| POST | `/biz/merchant/apply` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizMerchantController |
| POST | `/biz/merchant/display-name` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizMerchantController |
| GET | `/biz/merchant/profile` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizMerchantController |
| POST | `/biz/merchant/quick-start` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizMerchantController |
| GET | `/biz/merchant/store-claims/by-request/{requestNo}` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizStoreClaimRecoveryController |
| GET | `/biz/merchant/store-claims/{claimNo}` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizStoreClaimRecoveryController |
| POST | `/biz/merchant/store-claims/{claimNo}/resume` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizStoreClaimRecoveryController |
| GET | `/biz/merchant/store-creation-context` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizStoreCreationAccessController |
| POST | `/biz/merchant/store-openings` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizMerchantController |
| GET | `/biz/merchant/store-openings/{requestNo}` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizMerchantController |
| GET | `/biz/merchant/store-setup` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizFoundationController |

### message（5）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/message` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizMessageController |
| POST | `/biz/message/read-all` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizMessageController |
| POST | `/biz/message/subscribe` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizMessageController |
| GET | `/biz/message/unread-count` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizMessageController |
| POST | `/biz/message/{messageNo}/read` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizMessageController |

### my-spec-dims（3）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/my-spec-dims` | `@perm.canBiz('biz:goods')` | 能提前判 | 默认构建 | 已入网关 | BizGoodsController |
| POST | `/biz/my-spec-dims/{dimNo}/archive` | `@perm.canBiz('biz:goods')` | 能提前判 | 默认构建 | 已入网关 | BizGoodsController |
| POST | `/biz/my-spec-dims/{dimNo}/rename` | `@perm.canBiz('biz:goods')` | 能提前判 | 默认构建 | 已入网关 | BizGoodsController |

### order（19）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/order` | `@perm.canBiz('biz:order:view')` | 能提前判 | 默认构建 | 已入网关 | BizOrderController |
| GET | `/biz/order/worklist` | `@perm.canBiz('biz:order:view')` | 能提前判 | 默认构建 | 已入网关 | BizOrderController |
| GET | `/biz/order/{orderNo}/points-earning` | `@perm.canBiz('biz:order:view')` | 能提前判 | 默认构建 | 已入网关 | OrderPointsEarningController |
| GET | `/biz/order/{subOrderNo}` | `@perm.canBiz('biz:order:view')` | 能提前判 | 默认构建 | 已入网关 | BizOrderController |
| POST | `/biz/order/{subOrderNo}/confirm-offline-pay` | `@perm.canBiz('biz:receive')` | 能提前判 | 默认构建 | 已入网关 | BizOrderController |
| POST | `/biz/order/{subOrderNo}/delivered` | `@perm.canBiz('biz:ship')` | 能提前判 | 默认构建 | 已入网关 | BizOrderController |
| GET | `/biz/order/{subOrderNo}/express` | `@perm.canBiz('biz:ship')` | 能提前判 | 默认构建 | 已入网关 | BizExpressController |
| POST | `/biz/order/{subOrderNo}/express` | `@perm.canBiz('biz:ship')` | 能提前判 | 默认构建 | 已入网关 | BizExpressController |
| POST | `/biz/order/{subOrderNo}/express/cancel` | `@perm.canBiz('biz:ship')` | 能提前判 | 默认构建 | 已入网关 | BizExpressController |
| GET | `/biz/order/{subOrderNo}/express/quotes` | `@perm.canBiz('biz:ship')` | 能提前判 | 默认构建 | 已入网关 | BizExpressController |
| POST | `/biz/order/{subOrderNo}/fulfillment-change` | `@perm.canBiz('biz:ship')` | 能提前判 | 默认构建 | 已入网关 | BizOrderController |
| GET | `/biz/order/{subOrderNo}/fulfillment-options` | `@perm.canBiz('biz:ship')` | 能提前判 | 默认构建 | 已入网关 | BizOrderController |
| GET | `/biz/order/{subOrderNo}/full` | `@perm.canBiz('biz:order:view')` | 能提前判 | 默认构建 | 已入网关 | BizOrderController |
| GET | `/biz/order/{subOrderNo}/logistics-updates` | `@perm.canBiz('biz:order:view')` | 能提前判 | 默认构建 | 已入网关 | BizOrderController |
| POST | `/biz/order/{subOrderNo}/prepare` | `@perm.canBiz('biz:ship')` | 能提前判 | 默认构建 | 已入网关 | BizOrderController |
| POST | `/biz/order/{subOrderNo}/ship` | `@perm.canBiz('biz:ship')` | 能提前判 | 默认构建 | 已入网关 | BizOrderController |
| POST | `/biz/order/{subOrderNo}/ship/precheck` | `@perm.canBiz('biz:ship')` | 能提前判 | 默认构建 | 已入网关 | BizOrderController |
| GET | `/biz/order/{subOrderNo}/trace` | `@perm.canBiz('biz:order:view')` | 能提前判 | 默认构建 | 已入网关 | BizOrderController |
| POST | `/biz/order/{subOrderNo}/waybill` | `@perm.canBiz('biz:ship')` | 能提前判 | 默认构建 | 已入网关 | BizWaybillCorrectionController |

### period（5）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/period` | `@perm.canBiz('biz:campaign')` | 能提前判 | 默认构建 | 已入网关 | BizPeriodController |
| GET | `/biz/period/{periodNo}` | `@perm.canBiz('biz:campaign')` | 能提前判 | 默认构建 | 已入网关 | BizPeriodController |
| POST | `/biz/period/{periodNo}/cutoff` | `@perm.canBiz('biz:campaign')` | 能提前判 | 默认构建 | 已入网关 | BizPeriodController |
| POST | `/biz/period/{periodNo}/decision` | `@perm.canBiz('biz:campaign')` | 能提前判 | 默认构建 | 已入网关 | BizPeriodController |
| GET | `/biz/period/{periodNo}/purchase-lines` | `@perm.canBiz('biz:campaign')` | 能提前判 | 默认构建 | 已入网关 | BizPeriodController |

### personnel（5）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/personnel` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 默认构建 | 已入网关 | BizPersonnelController |
| POST | `/biz/personnel` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 默认构建 | 已入网关 | BizPersonnelController |
| POST | `/biz/personnel/{personnelNo}/account-links` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 默认构建 | 已入网关 | BizPersonnelController |
| POST | `/biz/personnel/{personnelNo}/assignments` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 默认构建 | 已入网关 | BizPersonnelController |
| POST | `/biz/personnel/{personnelNo}/status-changes` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 默认构建 | 已入网关 | BizPersonnelController |

### pickable-props（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/pickable-props` | `@perm.canBiz('biz:goods')` | 能提前判 | 默认构建 | 已入网关 | BizGoodsController |

### pickup（8）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/biz/pickup/arrived` | `@perm.canBiz('biz:receive')` | 能提前判 | 默认构建 | 已入网关 | BizPickupController |
| GET | `/biz/pickup/orders` | `@perm.canBiz('biz:verify')` | 能提前判 | 默认构建 | 已入网关 | BizPickupController |
| GET | `/biz/pickup/overview` | `@perm.canBiz('biz:verify')` | 能提前判 | 默认构建 | 已入网关 | BizPickupController |
| GET | `/biz/pickup/picking` | `@perm.canBiz('biz:receive')` | 能提前判 | 默认构建 | 已入网关 | BizPickupController |
| POST | `/biz/pickup/verify` | `@perm.canBiz('biz:verify')` | 能提前判 | 默认构建 | 已入网关 | BizPickupController |
| POST | `/biz/pickup/verify/batch` | `@perm.canBiz('biz:verify')` | 能提前判 | 默认构建 | 已入网关 | BizPickupController |
| GET | `/biz/pickup/verify/search` | `@perm.canBiz('biz:verify')` | 能提前判 | 默认构建 | 已入网关 | BizPickupController |
| POST | `/biz/pickup/{orderNo}/report` | `@perm.canBiz('biz:receive')` | 能提前判 | 默认构建 | 已入网关 | BizPickupController |

### pickup-points（2）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/biz/pickup-points` | `@perm.canBiz('biz:store')` | 能提前判 | 默认构建 | 已入网关 | BizPickupPointController |
| GET | `/biz/pickup-points/candidates` | `@perm.canBiz('biz:store')` | 能提前判 | 默认构建 | 已入网关 | BizPickupPointController |

### plan（12）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/plan` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 默认构建 | 已入网关 | BizPlanController |
| GET | `/biz/plan/purchases` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 默认构建 | 已入网关 | BizPlanPurchaseController |
| POST | `/biz/plan/purchases` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 默认构建 | 已入网关 | BizPlanPurchaseController |
| GET | `/biz/plan/purchases/offers` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 默认构建 | 已入网关 | BizPlanPurchaseController |
| GET | `/biz/plan/purchases/{orderNo}` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 默认构建 | 已入网关 | BizPlanPurchaseController |
| POST | `/biz/plan/purchases/{orderNo}/cancel` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 默认构建 | 已入网关 | BizPlanPurchaseController |
| GET | `/biz/plan/purchases/{orderNo}/cash-refunds` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 默认构建 | 已入网关 | BizEntityPlanRefundController |
| POST | `/biz/plan/purchases/{orderNo}/claims` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 默认构建 | 已入网关 | BizPlanPurchaseController |
| POST | `/biz/plan/purchases/{orderNo}/evidence` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 默认构建 | 已入网关 | BizPlanPurchaseController |
| POST | `/biz/plan/purchases/{orderNo}/evidence/content` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 默认构建 | 已入网关 | BizPlanPurchaseController |
| GET | `/biz/plan/purchases/{orderNo}/evidence/url` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 默认构建 | 已入网关 | BizPlanPurchaseController |
| POST | `/biz/plan/trial` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 默认构建 | 已入网关 | BizPlanController |

### platform-activity（4）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/platform-activity` | `@perm.canBiz('biz:campaign')` | 能提前判 | 默认构建 | 已入网关 | BizPlatformActivityController |
| GET | `/biz/platform-activity/{activityNo}` | `@perm.canBiz('biz:campaign')` | 能提前判 | 默认构建 | 已入网关 | BizPlatformActivityController |
| POST | `/biz/platform-activity/{activityNo}/enrollment` | `@perm.canBiz('biz:campaign')` | 能提前判 | 默认构建 | 已入网关 | BizPlatformActivityController |
| POST | `/biz/platform-activity/{activityNo}/withdraw` | `@perm.canBiz('biz:campaign')` | 能提前判 | 默认构建 | 已入网关 | BizPlatformActivityController |

### pricing（4）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/pricing/fee-rules/{ruleNo}` | `@perm.canBiz('biz:pricing:config:read')` | 能提前判 | 默认构建 | 已入网关 | BizPricingFeeRuleController |
| POST | `/biz/pricing/fee-rules/{ruleNo}` | `@perm.canBiz('biz:pricing:config:publish')` | 能提前判 | 默认构建 | 已入网关 | BizPricingFeeRuleController |
| GET | `/biz/pricing/fee-scopes/{scopeNo}/adjustments` | `@perm.canBiz('biz:pricing:config:read')` | 能提前判 | 默认构建 | 已入网关 | BizPricingFeeScopeController |
| POST | `/biz/pricing/fee-scopes/{scopeNo}/adjustments/{applicationNo}:apply` | `@perm.canBiz('biz:pricing:config:publish')` | 能提前判 | 默认构建 | 已入网关 | BizPricingFeeScopeController |

### pricing-configs（9）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/biz/pricing-configs/initialize` | `@perm.canBiz('biz:pricing:config:publish')` | 能提前判 | 默认构建 | 已入网关 | BizPricingConfigController |
| GET | `/biz/pricing-configs/{no}/draft` | `@perm.canBiz('biz:pricing:config:read')` | 能提前判 | 默认构建 | 已入网关 | BizPricingConfigController |
| PUT | `/biz/pricing-configs/{no}/draft` | `@perm.canBiz('biz:pricing:config:update')` | 能提前判 | 默认构建 | 已入网关 | BizPricingConfigController |
| POST | `/biz/pricing-configs/{no}/preview` | `@perm.canBiz('biz:pricing:config:read')` | 能提前判 | 默认构建 | 已入网关 | BizPricingConfigController |
| POST | `/biz/pricing-configs/{no}/publish` | `@perm.canBiz('biz:pricing:config:publish')` | 能提前判 | 默认构建 | 已入网关 | BizPricingConfigController |
| POST | `/biz/pricing-configs/{no}/reset` | `@perm.canBiz('biz:pricing:config:update')` | 能提前判 | 默认构建 | 已入网关 | BizPricingConfigController |
| GET | `/biz/pricing-configs/{no}/revisions/{revisionNo}` | `@perm.canBiz('biz:pricing:config:read')` | 能提前判 | 默认构建 | 已入网关 | BizPricingConfigController |
| POST | `/biz/pricing-configs/{no}/rollback` | `@perm.canBiz('biz:pricing:config:publish')` | 能提前判 | 默认构建 | 已入网关 | BizPricingConfigController |
| POST | `/biz/pricing-configs/{no}/upgrade` | `@perm.canBiz('biz:pricing:config:update')` | 能提前判 | 默认构建 | 已入网关 | BizPricingConfigController |

### pricing-manual-adjustments（3）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/biz/pricing-manual-adjustments` | `@perm.canBiz('biz:campaign')` | 能提前判 | 默认构建 | 已入网关 | BizPricingManualAdjustmentController |
| GET | `/biz/pricing-manual-adjustments/{no}` | `@perm.canBiz('biz:campaign')` | 能提前判 | 默认构建 | 已入网关 | BizPricingManualAdjustmentController |
| POST | `/biz/pricing-manual-adjustments/{no}:quote` | `@perm.canBiz('biz:campaign')` | 能提前判 | 默认构建 | 已入网关 | BizPricingManualAdjustmentController |

### pricing-tax-settings（3）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/pricing-tax-settings` | `@perm.canBiz('biz:pricing:tax:read')` | 能提前判 | 默认构建 | 已入网关 | BizPricingTaxSettingController |
| PUT | `/biz/pricing-tax-settings` | `@perm.canBiz('biz:pricing:tax:update')` | 能提前判 | 默认构建 | 已入网关 | BizPricingTaxSettingController |
| POST | `/biz/pricing-tax-settings/reset` | `@perm.canBiz('biz:pricing:tax:update')` | 能提前判 | 默认构建 | 已入网关 | BizPricingTaxSettingController |

### print（70）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/print/action-logs` | `@perm.canBiz('biz:print:resolve')` | 能提前判 | 默认构建 | 已入网关 | BizPrintMonitorController |
| GET | `/biz/print/adapters` | `@perm.canBiz('biz:print:read')` | 能提前判 | 默认构建 | 已入网关 | BizPrintDeviceController |
| GET | `/biz/print/agents` | `@perm.canBiz('biz:print:device')` | 能提前判 | 默认构建 | 已入网关 | BizPrintAgentController |
| POST | `/biz/print/agents` | `@perm.canBiz('biz:print:device')` | 能提前判 | 默认构建 | 已入网关 | BizPrintAgentController |
| GET | `/biz/print/agents/{agentNo}` | `@perm.canBiz('biz:print:device')` | 能提前判 | 默认构建 | 已入网关 | BizPrintAgentController |
| POST | `/biz/print/agents/{agentNo}/credential-rotations` | `@perm.canBiz('biz:print:device') and @perm.canBiz('biz:print:resolve')` | 能提前判 | 默认构建 | 已入网关 | BizPrintAgentController |
| POST | `/biz/print/agents/{agentNo}/enrollments` | `@perm.canBiz('biz:print:device')` | 能提前判 | 默认构建 | 已入网关 | BizPrintAgentController |
| POST | `/biz/print/agents/{agentNo}/revocations` | `@perm.canBiz('biz:print:device') and @perm.canBiz('biz:print:resolve')` | 能提前判 | 默认构建 | 已入网关 | BizPrintAgentController |
| GET | `/biz/print/batches/{batchNo}` | `@perm.canBiz('biz:print:read')` | 能提前判 | 默认构建 | 已入网关 | BizPrintJobQueryController |
| GET | `/biz/print/changes` | `@perm.canBiz('biz:print:read')` | 能提前判 | 默认构建 | 已入网关 | BizPrintMonitorController |
| GET | `/biz/print/exports` | `@perm.canBiz('biz:print:export')` | 能提前判 | 默认构建 | 已入网关 | BizPrintExportController |
| POST | `/biz/print/exports` | `@perm.canBiz('biz:print:export')` | 能提前判 | 默认构建 | 已入网关 | BizPrintExportController |
| GET | `/biz/print/exports/{exportNo}` | `@perm.canBiz('biz:print:export')` | 能提前判 | 默认构建 | 已入网关 | BizPrintExportController |
| POST | `/biz/print/exports/{exportNo}/download-grants` | `@perm.canBiz('biz:print:export')` | 能提前判 | 默认构建 | 已入网关 | BizPrintExportController |
| GET | `/biz/print/health` | `@perm.canBiz('biz:print:read')` | 能提前判 | 默认构建 | 已入网关 | BizPrintMonitorController |
| GET | `/biz/print/incidents` | `@perm.canBiz('biz:print:resolve')` | 能提前判 | 默认构建 | 已入网关 | BizPrintMonitorController |
| POST | `/biz/print/incidents/{incidentNo}/acknowledgements` | `@perm.canBiz('biz:print:resolve')` | 能提前判 | 默认构建 | 已入网关 | BizPrintMonitorController |
| GET | `/biz/print/invoices/{invoiceNo}/proof-history` | `@perm.canBiz('biz:print:read')` | 能提前判 | 默认构建 | 未部署 | BizPrintJobQueryController |
| GET | `/biz/print/jobs` | `@perm.canBiz('biz:print:read')` | 能提前判 | 默认构建 | 已入网关 | BizPrintJobQueryController |
| GET | `/biz/print/jobs/{jobNo}` | `@perm.canBiz('biz:print:read')` | 能提前判 | 默认构建 | 已入网关 | BizPrintJobQueryController |
| GET | `/biz/print/jobs/{jobNo}/attempts` | `@perm.canBiz('biz:print:read')` | 能提前判 | 默认构建 | 已入网关 | BizPrintJobQueryController |
| POST | `/biz/print/jobs/{jobNo}/cancellations` | `@perm.canBiz('biz:print:submit')` | 能提前判 | 默认构建 | 已入网关 | BizPrintJobCommandController |
| GET | `/biz/print/jobs/{jobNo}/document` | `@perm.canBiz('biz:print:read')` | 能提前判 | 默认构建 | 已入网关 | BizPrintJobQueryController |
| POST | `/biz/print/jobs/{jobNo}/extra-copies` | `@perm.canBiz('biz:print:extra')` | 能提前判 | 默认构建 | 已入网关 | BizPrintJobCommandController |
| POST | `/biz/print/jobs/{jobNo}/manual-confirmations` | `@perm.canBiz('biz:print:resolve')` | 能提前判 | 默认构建 | 已入网关 | BizPrintJobCommandController |
| GET | `/biz/print/jobs/{jobNo}/receipts` | `@perm.canBiz('biz:print:resolve')` | 能提前判 | 默认构建 | 已入网关 | BizPrintJobQueryController |
| POST | `/biz/print/jobs/{jobNo}/reprints` | `@perm.canBiz('biz:print:reprint')` | 能提前判 | 默认构建 | 已入网关 | BizPrintJobCommandController |
| POST | `/biz/print/jobs/{jobNo}/result-queries` | `@perm.canBiz('biz:print:resolve')` | 能提前判 | 默认构建 | 已入网关 | BizPrintJobCommandController |
| POST | `/biz/print/jobs/{jobNo}/retries` | `@perm.canBiz('biz:print:resolve')` | 能提前判 | 默认构建 | 已入网关 | BizPrintJobCommandController |
| POST | `/biz/print/jobs/{jobNo}/stale-releases` | `@perm.canBiz('biz:print:resolve')` | 能提前判 | 默认构建 | 已入网关 | BizPrintJobCommandController |
| GET | `/biz/print/operations/{operationNo}` | `@perm.canBiz('biz:print:read')` | 能提前判 | 默认构建 | 已入网关 | BizPrintJobQueryController |
| GET | `/biz/print/orders/{orderNo}/summary` | `@perm.canBiz('biz:print:read')` | 能提前判 | 默认构建 | 已入网关 | BizPrintJobQueryController |
| GET | `/biz/print/printers` | `@perm.canBiz('biz:print:read')` | 能提前判 | 默认构建 | 已入网关 | BizPrintDeviceController |
| POST | `/biz/print/printers` | `@perm.canBiz('biz:print:device')` | 能提前判 | 默认构建 | 已入网关 | BizPrintDeviceController |
| GET | `/biz/print/printers/{printerNo}` | `@perm.canBiz('biz:print:read')` | 能提前判 | 默认构建 | 已入网关 | BizPrintDeviceController |
| PUT | `/biz/print/printers/{printerNo}` | `@perm.canBiz('biz:print:device')` | 能提前判 | 默认构建 | 已入网关 | BizPrintDeviceController |
| POST | `/biz/print/printers/{printerNo}/connection-changes` | `@perm.canBiz('biz:print:device') and @perm.canBiz('biz:print:resolve')` | 能提前判 | 默认构建 | 已入网关 | BizPrintDeviceController |
| POST | `/biz/print/printers/{printerNo}/credential-rotations` | `@perm.canBiz('biz:print:device') and @perm.canBiz('biz:print:resolve')` | 能提前判 | 默认构建 | 已入网关 | BizPrintDeviceController |
| POST | `/biz/print/printers/{printerNo}/probes` | `@perm.canBiz('biz:print:device')` | 能提前判 | 默认构建 | 已入网关 | BizPrintDeviceController |
| POST | `/biz/print/printers/{printerNo}/status-changes` | `@perm.canBiz('biz:print:device')` | 能提前判 | 默认构建 | 已入网关 | BizPrintDeviceController |
| POST | `/biz/print/printers/{printerNo}/test-jobs` | `@perm.canBiz('biz:print:device')` | 能提前判 | 默认构建 | 已入网关 | BizPrintDeviceController |
| POST | `/biz/print/printers/{printerNo}/uncertainty-resolutions` | `@perm.canBiz('biz:print:device') and @perm.canBiz('biz:print:resolve')` | 能提前判 | 默认构建 | 已入网关 | BizPrintJobCommandController |
| POST | `/biz/print/reprints` | `@perm.canBiz('biz:print:reprint')` | 能提前判 | 默认构建 | 已入网关 | BizPrintJobCommandController |
| GET | `/biz/print/requests` | `@perm.canBiz('biz:print:read')` | 能提前判 | 默认构建 | 已入网关 | BizPrintRequestController |
| POST | `/biz/print/requests` | `@perm.canBiz('biz:print:submit')` | 能提前判 | 默认构建 | 已入网关 | BizPrintRequestController |
| GET | `/biz/print/requests/{requestNo}` | `@perm.canBiz('biz:print:read')` | 能提前判 | 默认构建 | 已入网关 | BizPrintRequestController |
| POST | `/biz/print/requests/{requestNo}/generation-retries` | `@perm.canBiz('biz:print:resolve')` | 能提前判 | 默认构建 | 已入网关 | BizPrintRequestController |
| POST | `/biz/print/route-versions/{versionNo}/dry-run` | `@perm.canBiz('biz:print:config')` | 能提前判 | 默认构建 | 已入网关 | BizPrintRouteController |
| POST | `/biz/print/route-versions/{versionNo}/publish` | `@perm.canBiz('biz:print:config')` | 能提前判 | 默认构建 | 已入网关 | BizPrintRouteController |
| GET | `/biz/print/routes` | `@perm.canBiz('biz:print:config')` | 能提前判 | 默认构建 | 已入网关 | BizPrintRouteController |
| POST | `/biz/print/routes` | `@perm.canBiz('biz:print:config')` | 能提前判 | 默认构建 | 已入网关 | BizPrintRouteController |
| GET | `/biz/print/routes/{routeNo}/versions` | `@perm.canBiz('biz:print:config')` | 能提前判 | 默认构建 | 已入网关 | BizPrintRouteController |
| POST | `/biz/print/routes/{routeNo}/versions` | `@perm.canBiz('biz:print:config')` | 能提前判 | 默认构建 | 已入网关 | BizPrintRouteController |
| GET | `/biz/print/scene-policies` | `@perm.canBiz('biz:print:config')` | 能提前判 | 默认构建 | 已入网关 | BizPrintSceneController |
| PUT | `/biz/print/scene-policies/{scene}` | `@perm.canBiz('biz:print:config')` | 能提前判 | 默认构建 | 已入网关 | BizPrintSceneController |
| GET | `/biz/print/scene-policies/{scene}/versions` | `@perm.canBiz('biz:print:config')` | 能提前判 | 默认构建 | 已入网关 | BizPrintSceneController |
| GET | `/biz/print/scenes` | `@perm.canBiz('biz:print:read')` | 能提前判 | 默认构建 | 已入网关 | BizPrintSceneController |
| GET | `/biz/print/scenes/{scene}/examples` | `@perm.canBiz('biz:print:config')` | 能提前判 | 默认构建 | 已入网关 | BizPrintSceneController |
| GET | `/biz/print/scenes/{scene}/fields` | `@perm.canBiz('biz:print:config')` | 能提前判 | 默认构建 | 已入网关 | BizPrintSceneController |
| GET | `/biz/print/simulations` | `@perm.canBiz('biz:print:simulate')` | 能提前判 | 默认构建 | 已入网关 | BizPrintSimulationController |
| POST | `/biz/print/simulations` | `@perm.canBiz('biz:print:simulate')` | 能提前判 | 默认构建 | 已入网关 | BizPrintSimulationController |
| GET | `/biz/print/simulations/{simulationNo}` | `@perm.canBiz('biz:print:simulate')` | 能提前判 | 默认构建 | 已入网关 | BizPrintSimulationController |
| POST | `/biz/print/template-resolutions/preview` | `@perm.canBiz('biz:print:config')` | 能提前判 | 默认构建 | 已入网关 | BizPrintTemplateController |
| PUT | `/biz/print/template-versions/{versionNo}` | `@perm.canBiz('biz:print:config')` | 能提前判 | 默认构建 | 已入网关 | BizPrintTemplateController |
| POST | `/biz/print/template-versions/{versionNo}/preview` | `@perm.canBiz('biz:print:config')` | 能提前判 | 默认构建 | 已入网关 | BizPrintTemplateController |
| POST | `/biz/print/template-versions/{versionNo}/publish` | `@perm.canBiz('biz:print:config')` | 能提前判 | 默认构建 | 已入网关 | BizPrintTemplateController |
| GET | `/biz/print/templates` | `@perm.canBiz('biz:print:config')` | 能提前判 | 默认构建 | 已入网关 | BizPrintTemplateController |
| POST | `/biz/print/templates` | `@perm.canBiz('biz:print:config')` | 能提前判 | 默认构建 | 已入网关 | BizPrintTemplateController |
| GET | `/biz/print/templates/{templateNo}/versions` | `@perm.canBiz('biz:print:config')` | 能提前判 | 默认构建 | 已入网关 | BizPrintTemplateController |
| POST | `/biz/print/templates/{templateNo}/versions` | `@perm.canBiz('biz:print:config')` | 能提前判 | 默认构建 | 已入网关 | BizPrintTemplateController |

### push-token（2）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/biz/push-token` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizPushTokenController |
| POST | `/biz/push-token/unregister` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizPushTokenController |

### qualifications（3）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/qualifications` | `@perm.canBiz('biz:store')` | 能提前判 | 默认构建 | 已入网关 | BizMerchantController |
| POST | `/biz/qualifications/recognize` | `@perm.canBiz('biz:store')` | 能提前判 | 默认构建 | 已入网关 | BizCertController |
| POST | `/biz/qualifications/save` | `@perm.canBiz('biz:store')` | 能提前判 | 默认构建 | 已入网关 | BizMerchantController |

### quote（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/biz/quote/{quoteNo}/revise` | `@perm.canBiz('biz:campaign')` | 能提前判 | 默认构建 | 已入网关 | BizQuoteController |

### regions（5）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/regions` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizRegionController |
| POST | `/biz/regions/parse` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizRegionController |
| GET | `/biz/regions/path` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizRegionController |
| GET | `/biz/regions/search` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizRegionController |
| GET | `/biz/regions/villages` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizRegionController |

### report（3）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/report/daily` | `@perm.canBiz('biz:customer')` | 能提前判 | 默认构建 | 未部署 | BizReportController |
| GET | `/biz/report/goods` | `@perm.canBiz('biz:customer')` | 能提前判 | 默认构建 | 未部署 | BizReportController |
| GET | `/biz/report/monthly` | `@perm.canBiz('biz:customer')` | 能提前判 | 默认构建 | 未部署 | BizReportController |

### resources（2）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/resources` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 默认构建 | 已入网关 | BizResourceController |
| POST | `/biz/resources` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 默认构建 | 已入网关 | BizResourceController |

### review（5）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/review` | `@perm.canBiz('biz:review')` | 能提前判 | 默认构建 | 已入网关 | BizReviewController |
| GET | `/biz/review/appeals` | `@perm.canBiz('biz:review')` | 能提前判 | 默认构建 | 已入网关 | BizReviewController |
| GET | `/biz/review/{reviewNo}` | `@perm.canBiz('biz:review')` | 能提前判 | 默认构建 | 已入网关 | BizReviewController |
| POST | `/biz/review/{reviewNo}/appeal` | `@perm.canBiz('biz:review')` | 能提前判 | 默认构建 | 已入网关 | BizReviewController |
| POST | `/biz/review/{reviewNo}/reply` | `@perm.canBiz('biz:review')` | 能提前判 | 默认构建 | 已入网关 | BizReviewController |

### role（2）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/biz/role/{roleCode}` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 默认构建 | 已入网关 | BizStaffController |
| POST | `/biz/role/{roleCode}/delete` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 默认构建 | 已入网关 | BizStaffController |

### role-perms（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/role-perms` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 默认构建 | 已入网关 | BizStaffController |

### schedule-rules（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/biz/schedule-rules` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 默认构建 | 已入网关 | BizScheduleController |

### schedules（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/schedules` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 默认构建 | 已入网关 | BizScheduleController |

### service-orders（6）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/biz/service-orders/counter-quotes` | `@perm.canBiz('trade:checkout:prepare') and @perm.canBiz('biz:order:view')` | 能提前判 | 默认构建 | 已入网关 | BizManualServiceAdmissionController |
| GET | `/biz/service-orders/counter-quotes/{operationNo}` | `@perm.canBiz('trade:checkout:prepare') and @perm.canBiz('biz:order:view')` | 能提前判 | 默认构建 | 已入网关 | BizManualServiceAdmissionController |
| GET | `/biz/service-orders/counter-quotes/{operationNo}/state` | `@perm.canBiz('trade:checkout:prepare') and @perm.canBiz('biz:order:view')` | 能提前判 | 默认构建 | 已入网关 | BizManualServiceAdmissionController |
| POST | `/biz/service-orders/manual-quotes` | `@perm.canBiz('trade:checkout:prepare')` | 能提前判 | 默认构建 | 已入网关 | BizManualServiceAdmissionController |
| GET | `/biz/service-orders/{orderNo}/manual-admission` | `@perm.canBiz('biz:order:view')` | 能提前判 | 默认构建 | 已入网关 | BizManualServiceAdmissionController |
| GET | `/biz/service-orders/{orderNo}/manual-source` | `@perm.canBiz('biz:order:view')` | 能提前判 | 默认构建 | 已入网关 | BizManualServiceAdmissionController |

### sku-identity（4）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/biz/sku-identity/barcode` | `@perm.canBiz('biz:goods')` | 能提前判 | 默认构建 | 已入网关 | BizSkuIdentityController |
| GET | `/biz/sku-identity/export` | `@perm.canBiz('biz:goods')` | 能提前判 | 默认构建 | 已入网关 | BizSkuIdentityController |
| POST | `/biz/sku-identity/import` | `@perm.canBiz('biz:goods')` | 能提前判 | 默认构建 | 已入网关 | BizSkuIdentityController |
| POST | `/biz/sku-identity/import/plan` | `@perm.canBiz('biz:goods')` | 能提前判 | 默认构建 | 已入网关 | BizSkuIdentityController |

### spec-dims（3）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/spec-dims` | `@perm.canBiz('biz:goods')` | 能提前判 | 默认构建 | 已入网关 | BizGoodsController |
| POST | `/biz/spec-dims` | `@perm.canBiz('biz:goods')` | 能提前判 | 默认构建 | 已入网关 | BizGoodsController |
| GET | `/biz/spec-dims/{dimNo}/values` | `@perm.canBiz('biz:goods')` | 能提前判 | 默认构建 | 已入网关 | BizGoodsController |

### spec-override（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/biz/spec-override/{categoryNo}` | `@perm.canBiz('biz:goods')` | 能提前判 | 默认构建 | 已入网关 | BizGoodsController |

### spec-props（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/spec-props` | `@perm.canBiz('biz:goods')` | 能提前判 | 默认构建 | 已入网关 | BizGoodsController |

### spec-templates（2）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/spec-templates` | `@perm.canBiz('biz:goods')` | 能提前判 | 默认构建 | 已入网关 | BizGoodsController |
| POST | `/biz/spec-templates` | `@perm.canBiz('biz:goods')` | 能提前判 | 默认构建 | 已入网关 | BizGoodsController |

### spec-values（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/biz/spec-values` | `@perm.canBiz('biz:goods')` | 能提前判 | 默认构建 | 已入网关 | BizGoodsController |

### spu-std（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/spu-std` | `@perm.canBiz('biz:goods')` | 能提前判 | 默认构建 | 已入网关 | BizGoodsController |

### staff（5）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/staff` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 默认构建 | 已入网关 | BizStaffController |
| POST | `/biz/staff` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 默认构建 | 已入网关 | BizStaffController |
| GET | `/biz/staff/logs` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 默认构建 | 已入网关 | BizStaffController |
| POST | `/biz/staff/{mchAccountNo}/status` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 默认构建 | 已入网关 | BizStaffController |
| POST | `/biz/staff/{mchAccountNo}/store` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 默认构建 | 已入网关 | BizStaffController |

### store（45）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/store` | `@perm.canBiz('biz:store')` | 能提前判 | 默认构建 | 已入网关 | BizMerchantController |
| POST | `/biz/store` | `@perm.canBiz('biz:store')` | 能提前判 | 默认构建 | 已入网关 | BizMerchantController |
| POST | `/biz/store/announcement` | `@perm.canBiz('biz:store')` | 能提前判 | 默认构建 | 已入网关 | BizMerchantController |
| POST | `/biz/store/announcement/recent/remove` | `@perm.canBiz('biz:store')` | 能提前判 | 默认构建 | 已入网关 | BizMerchantController |
| POST | `/biz/store/create` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 默认构建 | 已入网关 | BizMerchantController |
| GET | `/biz/store/list` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizMerchantController |
| GET | `/biz/store/poster` | `@perm.canBiz('biz:store')` | 能提前判 | 默认构建 | 已入网关 | BizDashboardController |
| GET | `/biz/store/qrcode` | `@perm.canBiz('biz:store')` | 能提前判 | 默认构建 | 已入网关 | BizPickupController |
| POST | `/biz/store/scope-preview` | `@perm.canBiz('biz:store')` | 能提前判 | 默认构建 | 已入网关 | BizMerchantController |
| GET | `/biz/store/settings` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizStoreSettingsController |
| POST | `/biz/store/settings/basic` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 默认构建 | 已入网关 | BizStoreSettingsController |
| POST | `/biz/store/settings/contact` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 默认构建 | 已入网关 | BizStoreSettingsController |
| POST | `/biz/store/settings/hours` | `@perm.canBiz('biz:store')` | 能提前判 | 默认构建 | 已入网关 | BizStoreSettingsController |
| GET | `/biz/store/share-kit` | `@perm.canBiz('biz:store')` | 能提前判 | 默认构建 | 已入网关 | BizDashboardController |
| GET | `/biz/store/{storeNo}/categories` | `@perm.canBiz('biz:store')` | 能提前判 | 默认构建 | 已入网关 | BizMerchantController |
| POST | `/biz/store/{storeNo}/categories` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 默认构建 | 已入网关 | BizMerchantController |
| POST | `/biz/store/{storeNo}/default` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 默认构建 | 已入网关 | BizMerchantController |
| POST | `/biz/store/{storeNo}/entity` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 默认构建 | 已入网关 | BizStoreEntityController |
| GET | `/biz/store/{storeNo}/entity/relink-check` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 默认构建 | 已入网关 | BizStoreEntityController |
| GET | `/biz/store/{storeNo}/freight-template` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizExpressController |
| GET | `/biz/store/{storeNo}/offline-sale` | `@perm.canBiz('biz:stock')` | 能提前判 | 默认构建 | 已入网关 | BizOfflineSaleController |
| POST | `/biz/store/{storeNo}/offline-sale` | `@perm.canBiz('biz:stock')` | 能提前判 | 默认构建 | 已入网关 | BizOfflineSaleController |
| POST | `/biz/store/{storeNo}/offline-sale/{docNo}/revoke` | `@perm.canBiz('biz:stock')` | 能提前判 | 默认构建 | 已入网关 | BizOfflineSaleController |
| GET | `/biz/store/{storeNo}/pay-setting` | `@perm.canBiz('biz:store')` | 能提前判 | 默认构建 | 已入网关 | BizStorePayController |
| PUT | `/biz/store/{storeNo}/pay-setting` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 默认构建 | 已入网关 | BizStorePayController |
| POST | `/biz/store/{storeNo}/payment` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 默认构建 | 已入网关 | BizMerchantController |
| POST | `/biz/store/{storeNo}/rename` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 默认构建 | 已入网关 | BizMerchantController |
| GET | `/biz/store/{storeNo}/sell-rules` | `@perm.canBiz('biz:stock')` | 能提前判 | 默认构建 | 已入网关 | BizStockSyncController |
| PUT | `/biz/store/{storeNo}/sell-rules` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 默认构建 | 已入网关 | BizStockSyncController |
| GET | `/biz/store/{storeNo}/ship-setting` | `@perm.canBiz('biz:store')` | 能提前判 | 默认构建 | 已入网关 | BizStoreShipController |
| PUT | `/biz/store/{storeNo}/ship-setting` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 默认构建 | 已入网关 | BizStoreShipController |
| POST | `/biz/store/{storeNo}/slug` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 默认构建 | 已入网关 | BizMerchantController |
| POST | `/biz/store/{storeNo}/status` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 默认构建 | 已入网关 | BizMerchantController |
| GET | `/biz/store/{storeNo}/stock-alignment` | `@perm.canBiz('biz:stock')` | 能提前判 | 默认构建 | 已入网关 | BizStockSyncController |
| GET | `/biz/store/{storeNo}/stock-alignment/batch/{batchNo}` | `@perm.canBiz('biz:stock')` | 能提前判 | 默认构建 | 已入网关 | BizStockSyncController |
| POST | `/biz/store/{storeNo}/stock-alignment/batch/{batchNo}/confirm` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 默认构建 | 已入网关 | BizStockSyncController |
| POST | `/biz/store/{storeNo}/stock-alignment/confirm` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 默认构建 | 已入网关 | BizStockSyncController |
| POST | `/biz/store/{storeNo}/stock-alignment/preview` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 默认构建 | 已入网关 | BizStockSyncController |
| GET | `/biz/store/{storeNo}/stock-overview` | `@perm.canBiz('biz:stock')` | 能提前判 | 默认构建 | 已入网关 | BizStockSyncController |
| POST | `/biz/store/{storeNo}/stock-overview/batch` | `@perm.canBiz('biz:stock')` | 能提前判 | 默认构建 | 已入网关 | BizStockSyncController |
| POST | `/biz/store/{storeNo}/stock-reconcile` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 默认构建 | 已入网关 | BizStockSyncController |
| GET | `/biz/store/{storeNo}/stock-reconcile/records` | `@perm.canBiz('biz:stock')` | 能提前判 | 默认构建 | 已入网关 | BizStockSyncController |
| GET | `/biz/store/{storeNo}/stock-reconcile/{operationNo}` | `@perm.canBiz('biz:stock')` | 能提前判 | 默认构建 | 已入网关 | BizStockSyncController |
| GET | `/biz/store/{storeNo}/stock-sync` | `@perm.canBiz('biz:stock')` | 能提前判 | 默认构建 | 已入网关 | BizStockSyncController |
| PUT | `/biz/store/{storeNo}/stock-sync` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 默认构建 | 已入网关 | BizStockSyncController |

### store-orders（4）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/biz/store-orders` | `@perm.canBiz('biz:order:create')` | 能提前判 | 默认构建 | 未部署 | BizStoreOrderController |
| GET | `/biz/store-orders/{orderNo}` | `@perm.canBiz('biz:order:view')` | 能提前判 | 默认构建 | 未部署 | BizStoreOrderController |
| POST | `/biz/store-orders/{orderNo}/cancel` | `@perm.canBiz('biz:order:create')` | 能提前判 | 默认构建 | 未部署 | BizStoreOrderController |
| PUT | `/biz/store-orders/{orderNo}/invoice-data` | `@perm.canBiz('biz:invoice:issue')` | 能提前判 | 默认构建 | 未部署 | BizStoreOrderController |

### store-sales-catalog（2）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/store-sales-catalog` | `@perm.canBiz('biz:goods')` | 能提前判 | 默认构建 | 已入网关 | BizStoreSalesCatalogController |
| POST | `/biz/store-sales-catalog/versions` | `@perm.canBiz('biz:goods')` | 能提前判 | 默认构建 | 已入网关 | BizStoreSalesCatalogController |

### store-spec-dims（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/store-spec-dims` | `@perm.canBiz('biz:goods')` | 能提前判 | 默认构建 | 已入网关 | BizGoodsController |

### stores（45）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/stores/mine` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizEntityController |
| GET | `/biz/stores/{storeNo}/appointment-slots` | `@perm.canBiz('biz:store')` | 能提前判 | 默认构建 | 已入网关 | BizStoreFulfillmentController |
| POST | `/biz/stores/{storeNo}/appointment-slots` | `@perm.canBiz('biz:store')` | 能提前判 | 默认构建 | 已入网关 | BizStoreFulfillmentController |
| POST | `/biz/stores/{storeNo}/brand-changes` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizStoreOperatingContextController |
| POST | `/biz/stores/{storeNo}/brand-changes/check` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizStoreOperatingContextController |
| POST | `/biz/stores/{storeNo}/channel-after-sale-decisions` | `@perm.canBiz('biz:aftersale')` | 能提前判 | 默认构建 | 未部署 | BizChannelAfterSaleDecisionController |
| POST | `/biz/stores/{storeNo}/channel-after-sale-decisions/release-unissued` | `@perm.canBiz('biz:aftersale')` | 能提前判 | 默认构建 | 未部署 | BizChannelAfterSaleDecisionController |
| GET | `/biz/stores/{storeNo}/channel-order-policies` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 默认构建 | 未部署 | BizChannelOrderPolicyController |
| GET | `/biz/stores/{storeNo}/channel-order-policies/{channelStoreKey}` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 默认构建 | 未部署 | BizChannelOrderPolicyController |
| POST | `/biz/stores/{storeNo}/channel-order-policies/{channelStoreKey}` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 默认构建 | 未部署 | BizChannelOrderPolicyController |
| POST | `/biz/stores/{storeNo}/channel-order-policies/{channelStoreKey}/enabled` | `@perm.canBiz('biz:store')` | 能提前判 | 默认构建 | 未部署 | BizChannelOrderPolicyController |
| GET | `/biz/stores/{storeNo}/context` | `@perm.memberOfSelectedStore(#storeNo)` | 留给入口判 | 默认构建 | 已入网关 | BizFoundationController |
| GET | `/biz/stores/{storeNo}/end-access` | `@perm.canBiz(@commerceRoutePolicy.entryDuty(#endCode))` | 留给入口判 | 默认构建 | 已入网关 | CommerceEndController |
| POST | `/biz/stores/{storeNo}/end-sessions` | `@perm.canBiz(@commerceRoutePolicy.entryDuty(#body.endCode()))` | 留给入口判 | 默认构建 | 已入网关 | CommerceEndController |
| GET | `/biz/stores/{storeNo}/entitlements` | `@perm.memberOfSelectedStore(#storeNo)` | 留给入口判 | 默认构建 | 已入网关 | BizFoundationController |
| GET | `/biz/stores/{storeNo}/features` | `@perm.memberOfSelectedStore(#storeNo)` | 留给入口判 | 默认构建 | 已入网关 | BizStoreFeatureController |
| GET | `/biz/stores/{storeNo}/fulfillment` | `@perm.canBiz('biz:store')` | 能提前判 | 默认构建 | 已入网关 | BizStoreFulfillmentController |
| PUT | `/biz/stores/{storeNo}/fulfillment` | `@perm.canBiz('biz:store')` | 能提前判 | 默认构建 | 已入网关 | BizStoreFulfillmentController |
| GET | `/biz/stores/{storeNo}/fulfillment/{channel}/impact` | `@perm.canBiz('biz:store')` | 能提前判 | 默认构建 | 已入网关 | BizGoodsController |
| GET | `/biz/stores/{storeNo}/mall/offline/orders/{orderNo}/cash-refunds` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | CommerceOfflineBuyerHttpController |
| GET | `/biz/stores/{storeNo}/mall/offline/orders/{orderNo}/claims` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | CommerceOfflineBuyerHttpController |
| POST | `/biz/stores/{storeNo}/mall/offline/orders/{orderNo}/claims` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | CommerceOfflineBuyerHttpController |
| POST | `/biz/stores/{storeNo}/mall/offline/orders/{orderNo}/evidence` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | CommerceOfflineBuyerHttpController |
| POST | `/biz/stores/{storeNo}/mall/offline/orders/{orderNo}/evidence/content` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | CommerceOfflineBuyerHttpController |
| GET | `/biz/stores/{storeNo}/mall/offline/orders/{orderNo}/evidence/url` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | CommerceOfflineBuyerHttpController |
| GET | `/biz/stores/{storeNo}/mall/offline/orders/{orderNo}/instructions` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | CommerceOfflineBuyerHttpController |
| POST | `/biz/stores/{storeNo}/mall/offline/orders/{orderNo}/instructions` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | CommerceOfflineBuyerHttpController |
| POST | `/biz/stores/{storeNo}/mall/offline/quotes` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | CommerceOfflineBuyerHttpController |
| POST | `/biz/stores/{storeNo}/mall/offline/upgrade-quotes` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | CommerceOfflineBuyerHttpController |
| GET | `/biz/stores/{storeNo}/mall/orders` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | CommerceBuyerMallHttpController |
| POST | `/biz/stores/{storeNo}/mall/orders` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | CommerceBuyerMallHttpController |
| GET | `/biz/stores/{storeNo}/mall/orders/{orderNo}` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | CommerceBuyerMallHttpController |
| POST | `/biz/stores/{storeNo}/mall/orders/{orderNo}/cancel` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | CommerceBuyerMallHttpController |
| GET | `/biz/stores/{storeNo}/mall/orders/{orderNo}/payments` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | CommerceBuyerMallHttpController |
| POST | `/biz/stores/{storeNo}/mall/orders/{orderNo}/payments` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | CommerceBuyerMallHttpController |
| POST | `/biz/stores/{storeNo}/mall/orders/{orderNo}/payments/refresh` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | CommerceBuyerMallHttpController |
| POST | `/biz/stores/{storeNo}/mall/orders/{orderNo}/refund-previews` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | CommerceBuyerMallHttpController |
| POST | `/biz/stores/{storeNo}/mall/orders/{orderNo}/refunds` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | CommerceBuyerMallHttpController |
| GET | `/biz/stores/{storeNo}/mall/products` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | CommerceStorefrontHttpController |
| POST | `/biz/stores/{storeNo}/mall/quotes` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | CommerceBuyerMallHttpController |
| POST | `/biz/stores/{storeNo}/mall/upgrade-quotes` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | CommerceBuyerMallHttpController |
| POST | `/biz/stores/{storeNo}/members` | `@perm.canBiz('biz:customer')` | 能提前判 | 默认构建 | 已入网关 | BizMemberScopeController |
| GET | `/biz/stores/{storeNo}/operating-context` | 商家身份 | 只要求登录 | 默认构建 | 已入网关 | BizStoreOperatingContextController |
| GET | `/biz/stores/{storeNo}/pos/access` | `@perm.canBiz('biz:receive')` | 能提前判 | 默认构建 | 已入网关 | CommerceEndController |
| GET | `/biz/stores/{storeNo}/subscription` | `@perm.memberOfSelectedStore(#storeNo)` | 留给入口判 | 默认构建 | 已入网关 | BizFoundationController |

### x/commerce/account-operations（4）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/x/commerce/account-operations` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizAccountOperationController |
| GET | `/biz/x/commerce/account-operations/{operationNo}/attempts` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizAccountOperationController |
| POST | `/biz/x/commerce/account-operations/{operationNo}:recover` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizAccountOperationController |
| POST | `/biz/x/commerce/account-operations/{operationNo}:refresh` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizAccountOperationController |

### x/commerce/accounts（8）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/x/commerce/accounts` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizChannelAccountController |
| POST | `/biz/x/commerce/accounts` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizChannelAccountController |
| GET | `/biz/x/commerce/accounts/{accountNo}` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizChannelAccountController |
| GET | `/biz/x/commerce/accounts/{accountNo}/authorization-revisions` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizChannelAuthorizationController |
| POST | `/biz/x/commerce/accounts/{accountNo}/statement-imports` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizChannelStatementController |
| GET | `/biz/x/commerce/accounts/{accountNo}/statements` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizChannelStatementController |
| POST | `/biz/x/commerce/accounts/{accountNo}:authorize` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizChannelAuthorizationController |
| POST | `/biz/x/commerce/accounts/{accountNo}:revoke` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizChannelAuthorizationController |

### x/commerce/after-sales（2）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/x/commerce/after-sales/{caseNo}` | `@perm.canBiz('biz:aftersale')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizChannelAfterSaleController |
| POST | `/biz/x/commerce/after-sales/{caseNo}:apply-decision` | `@perm.canBiz('biz:aftersale')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizChannelAfterSaleController |

### x/commerce/catalog-scopes（5）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/x/commerce/catalog-scopes/{scopeNo}` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizCatalogScopeController |
| PUT | `/biz/x/commerce/catalog-scopes/{scopeNo}` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizCatalogScopeController |
| GET | `/biz/x/commerce/catalog-scopes/{scopeNo}/sync-policy` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizSyncPolicyController |
| PUT | `/biz/x/commerce/catalog-scopes/{scopeNo}/sync-policy` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizSyncPolicyController |
| POST | `/biz/x/commerce/catalog-scopes/{scopeNo}:preview` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizCatalogPreviewController |

### x/commerce/connections（4）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/x/commerce/connections/{connectionNo}` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizChannelConnectionController |
| PUT | `/biz/x/commerce/connections/{connectionNo}` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizChannelConnectionController |
| POST | `/biz/x/commerce/connections/{connectionNo}:set-status` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizChannelConnectionController |
| POST | `/biz/x/commerce/connections/{connectionNo}:verify` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizChannelConnectionController |

### x/commerce/inbound-events（2）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/x/commerce/inbound-events/{eventNo}/receipts` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizInboundEventController |
| POST | `/biz/x/commerce/inbound-events/{eventNo}:recover` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizInboundEventController |

### x/commerce/operations（4）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/x/commerce/operations/{operationNo}` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizChannelOperationController |
| GET | `/biz/x/commerce/operations/{operationNo}/attempts` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizStoreOperationController |
| POST | `/biz/x/commerce/operations/{operationNo}:recover` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizStoreOperationController |
| POST | `/biz/x/commerce/operations/{operationNo}:refresh` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizStoreOperationController |

### x/commerce/orders（8）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/x/commerce/orders/{channelOrderNo}` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizChannelOrderController |
| GET | `/biz/x/commerce/orders/{channelOrderNo}/fulfillments` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizChannelFulfillmentController |
| GET | `/biz/x/commerce/orders/{channelOrderNo}/lines` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizChannelOrderController |
| GET | `/biz/x/commerce/orders/{channelOrderNo}/revisions` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizChannelOrderController |
| POST | `/biz/x/commerce/orders/{channelOrderNo}:accept` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizChannelOrderController |
| POST | `/biz/x/commerce/orders/{channelOrderNo}:recover-import` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizChannelOrderController |
| POST | `/biz/x/commerce/orders/{channelOrderNo}:refresh` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizChannelOrderController |
| POST | `/biz/x/commerce/orders/{channelOrderNo}:reject` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizChannelOrderController |

### x/commerce/providers（2）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/x/commerce/providers` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizChannelProviderController |
| GET | `/biz/x/commerce/providers/{providerNo}/profiles` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizChannelProviderController |

### x/commerce/redemptions（2）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/x/commerce/redemptions/{redemptionNo}` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizChannelRedemptionController |
| POST | `/biz/x/commerce/redemptions/{redemptionNo}:reverse` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizChannelRedemptionController |

### x/commerce/resource-mappings（3）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/biz/x/commerce/resource-mappings` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizResourceMappingController |
| PUT | `/biz/x/commerce/resource-mappings/{mappingNo}` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizResourceMappingController |
| GET | `/biz/x/commerce/resource-mappings/{mappingNo}/revisions` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizResourceMappingController |

### x/commerce/stores（21）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/x/commerce/stores/{storeNo}/after-sales` | `@perm.canBiz('biz:aftersale')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizChannelAfterSaleController |
| GET | `/biz/x/commerce/stores/{storeNo}/catalog-scopes` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizCatalogScopeController |
| POST | `/biz/x/commerce/stores/{storeNo}/catalog-scopes` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizCatalogScopeController |
| GET | `/biz/x/commerce/stores/{storeNo}/connections` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizChannelConnectionController |
| POST | `/biz/x/commerce/stores/{storeNo}/connections` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizChannelConnectionController |
| POST | `/biz/x/commerce/stores/{storeNo}/fulfillment-publications` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizChannelFulfillmentController |
| GET | `/biz/x/commerce/stores/{storeNo}/inbound-events` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizInboundEventController |
| GET | `/biz/x/commerce/stores/{storeNo}/integration-health` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizChannelStatisticsController |
| GET | `/biz/x/commerce/stores/{storeNo}/operations` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizStoreOperationController |
| GET | `/biz/x/commerce/stores/{storeNo}/orders` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizChannelOrderController |
| POST | `/biz/x/commerce/stores/{storeNo}/redemption-previews` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizChannelRedemptionController |
| POST | `/biz/x/commerce/stores/{storeNo}/redemptions` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizChannelRedemptionController |
| GET | `/biz/x/commerce/stores/{storeNo}/resource-mappings` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizResourceMappingController |
| GET | `/biz/x/commerce/stores/{storeNo}/statement-lines` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizChannelStatementController |
| GET | `/biz/x/commerce/stores/{storeNo}/statistics/order-imports` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizChannelStatisticsController |
| GET | `/biz/x/commerce/stores/{storeNo}/statistics/providers` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizChannelStatisticsController |
| GET | `/biz/x/commerce/stores/{storeNo}/statistics/sync` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizChannelStatisticsController |
| POST | `/biz/x/commerce/stores/{storeNo}/stock-publications` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizChannelAvailabilityController |
| POST | `/biz/x/commerce/stores/{storeNo}/store-publications` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizChannelAvailabilityController |
| GET | `/biz/x/commerce/stores/{storeNo}/sync-runs` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizSyncRunController |
| POST | `/biz/x/commerce/stores/{storeNo}/sync-runs` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizSyncRunController |

### x/commerce/sync-runs（3）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/x/commerce/sync-runs/{runNo}` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizSyncRunController |
| GET | `/biz/x/commerce/sync-runs/{runNo}/items` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizSyncRunController |
| POST | `/biz/x/commerce/sync-runs/{runNo}:cancel-queued` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-commerce-integration | 未部署 | BizSyncRunController |

## 餐饮（261）

网关判权：能提前判 261。构建：插件 shop-industry-food 261。生产：未部署 261。

### x/food/batches（8）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/x/food/batches/{batchNo}` | `@perm.canBiz('biz:order:view')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizOrderBatchController |
| POST | `/biz/x/food/batches/{batchNo}/business-returns` | `@perm.canBiz('biz:aftersale')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessReturnController |
| GET | `/biz/x/food/batches/{batchNo}/custody-receipts` | `@perm.canBiz('biz:receive')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizDiningHandoverController |
| GET | `/biz/x/food/batches/{batchNo}/pickup-verification` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizDiningPickupVerificationController |
| POST | `/biz/x/food/batches/{batchNo}:cancel` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizOrderBatchController |
| POST | `/biz/x/food/batches/{batchNo}:confirm-packing` | `@perm.canBiz('biz:receive')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizDiningHandoverController |
| POST | `/biz/x/food/batches/{batchNo}:handover` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizDiningHandoverController |
| POST | `/biz/x/food/batches/{batchNo}:verify-pickup` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizDiningPickupVerificationController |

### x/food/bottle-custody（5）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/x/food/bottle-custody` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBottleCustodyController |
| POST | `/biz/x/food/bottle-custody` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBottleCustodyController |
| GET | `/biz/x/food/bottle-custody/{custodyNo}` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBottleCustodyController |
| GET | `/biz/x/food/bottle-custody/{custodyNo}/movements` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBottleCustodyController |
| POST | `/biz/x/food/bottle-custody/{custodyNo}/withdrawals` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBottleCustodyController |

### x/food/bundles（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| PUT | `/biz/x/food/bundles/{skuNo}/definition` | `@perm.canBiz('biz:goods')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBundleController |

### x/food/business-days（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/biz/x/food/business-days/{date}:open` | `@perm.canBiz('biz:finance')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessCloseController |

### x/food/business-operations（43）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/biz/x/food/business-operations/businessOperations/accept` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessOperationsController |
| POST | `/biz/x/food/business-operations/businessOperations/cancel` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessOperationsController |
| POST | `/biz/x/food/business-operations/businessOperations/cases` | `@perm.canBiz('biz:order:view')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessOperationsController |
| POST | `/biz/x/food/business-operations/businessOperations/evidence` | `@perm.canBiz('biz:order:view')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessOperationsController |
| POST | `/biz/x/food/business-operations/businessOperations/handover` | `@perm.canBiz('biz:order:view')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessOperationsController |
| POST | `/biz/x/food/business-operations/businessOperations/openCase` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessOperationsController |
| POST | `/biz/x/food/business-operations/businessOperations/propose` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessOperationsController |
| POST | `/biz/x/food/business-operations/businessOperations/recoverCase` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessOperationsController |
| POST | `/biz/x/food/business-operations/businessOperations/resolveCase` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessOperationsController |
| POST | `/biz/x/food/business-operations/checkCollaboration/currentLineOwner` | `@perm.canBiz('biz:order:view')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessOperationsController |
| POST | `/biz/x/food/business-operations/checkCollaboration/freeze` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessOperationsController |
| POST | `/biz/x/food/business-operations/checkCollaboration/moveBatches` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessOperationsController |
| POST | `/biz/x/food/business-operations/checkCollaboration/moveLines` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessOperationsController |
| POST | `/biz/x/food/business-operations/checkCollaboration/previewReceivable` | `@perm.canBiz('biz:order:view')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessOperationsController |
| POST | `/biz/x/food/business-operations/checkCollaboration/release` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessOperationsController |
| POST | `/biz/x/food/business-operations/checkCollaboration/releaseService` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessOperationsController |
| POST | `/biz/x/food/business-operations/checkCollaboration/sourceHash` | `@perm.canBiz('biz:order:view')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessOperationsController |
| POST | `/biz/x/food/business-operations/foodFeeSource/bind` | `@perm.canBiz('biz:pricing:config:publish')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessOperationsController |
| POST | `/biz/x/food/business-operations/foodFeeSource/bindings` | `@perm.canBiz('biz:pricing:config:read')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessOperationsController |
| POST | `/biz/x/food/business-operations/queueNotification/list` | `@perm.canBiz('biz:order:view')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessOperationsController |
| POST | `/biz/x/food/business-operations/queueNotification/recover` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessOperationsController |
| POST | `/biz/x/food/business-operations/reservationPreorder/cancel` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessOperationsController |
| POST | `/biz/x/food/business-operations/reservationPreorder/convert` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessOperationsController |
| POST | `/biz/x/food/business-operations/reservationPreorder/get` | `@perm.canBiz('biz:order:view')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessOperationsController |
| POST | `/biz/x/food/business-operations/reservationPreorder/prepareCart` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessOperationsController |
| POST | `/biz/x/food/business-operations/reservationPreorder/save` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessOperationsController |
| POST | `/biz/x/food/business-operations/serviceForm/admit` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessOperationsController |
| POST | `/biz/x/food/business-operations/serviceForm/attendees` | `@perm.canBiz('biz:order:view')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessOperationsController |
| POST | `/biz/x/food/business-operations/serviceForm/bindBatch` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessOperationsController |
| POST | `/biz/x/food/business-operations/serviceForm/book` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessOperationsController |
| POST | `/biz/x/food/business-operations/serviceForm/cancel` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessOperationsController |
| POST | `/biz/x/food/business-operations/serviceForm/enter` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessOperationsController |
| POST | `/biz/x/food/business-operations/serviceForm/exit` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessOperationsController |
| POST | `/biz/x/food/business-operations/serviceForm/get` | `@perm.canBiz('biz:order:view')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessOperationsController |
| POST | `/biz/x/food/business-operations/serviceForm/leave` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessOperationsController |
| POST | `/biz/x/food/business-operations/serviceForm/noShow` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessOperationsController |
| POST | `/biz/x/food/business-operations/serviceForm/registerOrganization` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessOperationsController |
| POST | `/biz/x/food/business-operations/serviceForm/revise` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessOperationsController |
| POST | `/biz/x/food/business-operations/serviceSlot/create` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessOperationsController |
| POST | `/biz/x/food/business-operations/serviceSlot/dispatchDue` | `@perm.canBiz('biz:order:view')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessOperationsController |
| POST | `/biz/x/food/business-operations/serviceSlot/get` | `@perm.canBiz('biz:order:view')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessOperationsController |
| POST | `/biz/x/food/business-operations/serviceSlot/reserve` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessOperationsController |
| POST | `/biz/x/food/business-operations/serviceSlot/transition` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessOperationsController |

### x/food/business-returns（5）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/x/food/business-returns/{returnNo}` | `@perm.canBiz('biz:order:view')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessReturnController |
| GET | `/biz/x/food/business-returns/{returnNo}/approval` | `@perm.canBiz('biz:order:view')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessReturnApprovalController |
| POST | `/biz/x/food/business-returns/{returnNo}:approve` | `@perm.canBiz('biz:aftersale')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessReturnApprovalController |
| POST | `/biz/x/food/business-returns/{returnNo}:cancel` | `@perm.canBiz('biz:aftersale')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessReturnController |
| POST | `/biz/x/food/business-returns/{returnNo}:link-replacement` | `@perm.canBiz('biz:aftersale')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessReturnApprovalController |

### x/food/carts（6）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/x/food/carts/{cartNo}` | `@perm.canBiz('biz:order:view')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizCartController |
| GET | `/biz/x/food/carts/{cartNo}/delivery` | `@perm.canBiz('biz:order:view')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizDiningDeliveryController |
| PUT | `/biz/x/food/carts/{cartNo}/delivery` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizDiningDeliveryController |
| PUT | `/biz/x/food/carts/{cartNo}/lines/{sourceLineRef}` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizCartController |
| POST | `/biz/x/food/carts/{cartNo}/lines/{sourceLineRef}:archive` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizCartController |
| POST | `/biz/x/food/carts/{cartNo}:archive` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizCartController |

### x/food/checks（13）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/x/food/checks/{checkNo}` | `@perm.canBiz('biz:order:view')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizCheckController |
| GET | `/biz/x/food/checks/{checkNo}/actions` | `@perm.canBiz('biz:order:view')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizCheckActionController |
| POST | `/biz/x/food/checks/{checkNo}/business-share-plans` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessShareController |
| GET | `/biz/x/food/checks/{checkNo}/business-share-plans/{planNo}` | `@perm.canBiz('biz:order:view')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessShareController |
| POST | `/biz/x/food/checks/{checkNo}/business-share-plans/{planNo}:cancel` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessShareController |
| POST | `/biz/x/food/checks/{checkNo}/business-share-plans/{planNo}:confirm` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessShareController |
| POST | `/biz/x/food/checks/{checkNo}/cart-sessions` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizCartController |
| GET | `/biz/x/food/checks/{checkNo}/courses` | `@perm.canBiz('biz:receive')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizCourseController |
| POST | `/biz/x/food/checks/{checkNo}/courses` | `@perm.canBiz('biz:receive')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizCourseController |
| PUT | `/biz/x/food/checks/{checkNo}/seats` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizCheckSeatController |
| POST | `/biz/x/food/checks/{checkNo}:clear` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizCheckController |
| POST | `/biz/x/food/checks/{checkNo}:combine-tables` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizCheckController |
| POST | `/biz/x/food/checks/{checkNo}:transfer` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizCheckController |

### x/food/closes（4）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/x/food/closes/{closeNo}` | `@perm.canBiz('biz:order:view')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessCloseController |
| GET | `/biz/x/food/closes/{closeNo}/exceptions` | `@perm.canBiz('biz:order:view')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessCloseController |
| POST | `/biz/x/food/closes/{closeNo}/exceptions/{exceptionNo}:approve-carry` | `@perm.canBiz('biz:finance')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessCloseController |
| POST | `/biz/x/food/closes/{closeNo}/exceptions/{exceptionNo}:propose-carry` | `@perm.canBiz('biz:finance')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessCloseController |

### x/food/closes:adjust（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/biz/x/food/closes:adjust` | `@perm.canBiz('biz:finance')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessCloseController |

### x/food/closes:preview（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/biz/x/food/closes:preview` | `@perm.canBiz('biz:finance')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessCloseController |

### x/food/closes:sign（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/biz/x/food/closes:sign` | `@perm.canBiz('biz:finance')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessCloseController |

### x/food/config-revisions（2）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/x/food/config-revisions` | `@perm.canBiz('biz:goods')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizConfigRevisionController |
| POST | `/biz/x/food/config-revisions/{revisionNo}:republish` | `@perm.canBiz('biz:goods')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizConfigRevisionController |

### x/food/courses（2）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/biz/x/food/courses/{courseNo}:act` | `@perm.canBiz('biz:receive')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizCourseController |
| POST | `/biz/x/food/courses/{courseNo}:fire` | `@perm.canBiz('biz:receive')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizCourseController |

### x/food/dining-devices（3）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/biz/x/food/dining-devices` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizDiningIdentityController |
| GET | `/biz/x/food/dining-devices/{deviceNo}` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizDiningIdentityController |
| POST | `/biz/x/food/dining-devices/{deviceNo}:revoke` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizDiningIdentityController |

### x/food/kitchen-events（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/x/food/kitchen-events` | `@perm.canBiz('biz:receive')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizFoodKdsController |

### x/food/kitchen-stream（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/x/food/kitchen-stream` | `@perm.canBiz('biz:receive')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizFoodKdsController |

### x/food/kitchen-subscriptions（4）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/biz/x/food/kitchen-subscriptions` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizFoodKdsController |
| POST | `/biz/x/food/kitchen-subscriptions/{subscriptionNo}/voice:acknowledge` | `@perm.canBiz('biz:receive')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizFoodStationRuntimeController |
| POST | `/biz/x/food/kitchen-subscriptions/{subscriptionNo}/voice:claim` | `@perm.canBiz('biz:receive')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizFoodStationRuntimeController |
| POST | `/biz/x/food/kitchen-subscriptions/{subscriptionNo}:revoke` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizFoodKdsController |

### x/food/menu-channel-bindings（2）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/x/food/menu-channel-bindings/{bindingNo}` | `@perm.canBiz('biz:goods')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizMenuChannelBindingController |
| PUT | `/biz/x/food/menu-channel-bindings/{bindingNo}` | `@perm.canBiz('biz:goods')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizMenuChannelBindingController |

### x/food/menus（3）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| PUT | `/biz/x/food/menus/{menuNo}` | `@perm.canBiz('biz:goods')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizMenuController |
| PUT | `/biz/x/food/menus/{menuNo}/definition` | `@perm.canBiz('biz:goods')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizMenuController |
| POST | `/biz/x/food/menus/{menuNo}:disable` | `@perm.canBiz('biz:goods')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizMenuController |

### x/food/modifiers（2）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| PUT | `/biz/x/food/modifiers/{modifierNo}` | `@perm.canBiz('biz:goods')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizModifierController |
| POST | `/biz/x/food/modifiers/{modifierNo}:publish` | `@perm.canBiz('biz:goods')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizConfigRevisionController |

### x/food/operating-reports（7）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/biz/x/food/operating-reports/exports` | `@perm.canBiz('biz:finance') and @perm.canBiz('biz:report:export')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizDiningReportExportController |
| GET | `/biz/x/food/operating-reports/exports/{exportNo}` | `@perm.canBiz('biz:finance') and @perm.canBiz('biz:report:export')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizDiningReportExportController |
| GET | `/biz/x/food/operating-reports/exports/{exportNo}/download` | `@perm.canBiz('biz:finance') and @perm.canBiz('biz:report:export')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizDiningReportExportController |
| GET | `/biz/x/food/operating-reports/inventory/realtime` | `@perm.canBiz('biz:finance') and @perm.canBiz('biz:stock')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizDiningReportController |
| GET | `/biz/x/food/operating-reports/metrics` | `@perm.canBiz('biz:finance')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizDiningReportController |
| GET | `/biz/x/food/operating-reports/realtime` | `@perm.canBiz('biz:finance')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizDiningReportController |
| GET | `/biz/x/food/operating-reports/{view:daily|period|trend|payments|discounts|refunds|profit|rankings|inventory|health}` | `@perm.canBiz('biz:finance')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizDiningReportController |

### x/food/option-groups（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| PUT | `/biz/x/food/option-groups/{groupNo}/items` | `@perm.canBiz('biz:goods')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizOptionGroupController |

### x/food/parties（3）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/biz/x/food/parties` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizDiningIdentityController |
| GET | `/biz/x/food/parties/{sessionNo}` | `@perm.canBiz('biz:order:view')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizDiningIdentityController |
| POST | `/biz/x/food/parties/{sessionNo}:revoke` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizDiningIdentityController |

### x/food/print-configuration（3）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/x/food/print-configuration` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizPrintConfigurationController |
| POST | `/biz/x/food/print-configuration/routes` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizPrintConfigurationController |
| POST | `/biz/x/food/print-configuration/templates` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizPrintConfigurationController |

### x/food/print-devices（5）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/biz/x/food/print-devices` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizPrintDeviceController |
| POST | `/biz/x/food/print-devices/{deviceNo}:acknowledge` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizPrintDeviceController |
| POST | `/biz/x/food/print-devices/{deviceNo}:claim` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizPrintDeviceController |
| POST | `/biz/x/food/print-devices/{deviceNo}:heartbeat` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizPrintDeviceController |
| POST | `/biz/x/food/print-devices/{deviceNo}:revoke` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizPrintDeviceController |

### x/food/print-jobs（5）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/x/food/print-jobs` | `@perm.canBiz('biz:order:view')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizPrintJobController |
| POST | `/biz/x/food/print-jobs` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizPrintJobController |
| GET | `/biz/x/food/print-jobs/{jobNo}` | `@perm.canBiz('biz:order:view')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizPrintJobController |
| POST | `/biz/x/food/print-jobs/{jobNo}:reprint` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizPrintJobController |
| POST | `/biz/x/food/print-jobs/{jobNo}:retry` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizPrintJobController |

### x/food/production-events（3）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/x/food/production-events/{eventNo}` | `@perm.canBiz('biz:order:view')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizProductionConsumptionController |
| POST | `/biz/x/food/production-events/{eventNo}:consume` | `@perm.canBiz('biz:stock')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizProductionConsumptionController |
| POST | `/biz/x/food/production-events/{eventNo}:reverse` | `@perm.canBiz('biz:stock')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizProductionConsumptionController |

### x/food/production-tasks（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/biz/x/food/production-tasks/{taskNo}:adjust` | `@perm.canBiz('biz:stock')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizProductionConsumptionController |

### x/food/queue-tickets（8）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/x/food/queue-tickets` | `@perm.canBiz('biz:order:view')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizQueueController |
| POST | `/biz/x/food/queue-tickets` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizQueueController |
| GET | `/biz/x/food/queue-tickets/{ticketNo}` | `@perm.canBiz('biz:order:view')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizQueueController |
| POST | `/biz/x/food/queue-tickets/{ticketNo}:call` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizQueueController |
| POST | `/biz/x/food/queue-tickets/{ticketNo}:cancel` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizQueueController |
| POST | `/biz/x/food/queue-tickets/{ticketNo}:no-show` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizQueueController |
| POST | `/biz/x/food/queue-tickets/{ticketNo}:recover-seating` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizQueueController |
| POST | `/biz/x/food/queue-tickets/{ticketNo}:seat` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizQueueController |

### x/food/queue-tickets:call-next（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/biz/x/food/queue-tickets:call-next` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizQueueController |

### x/food/quotes（2）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/x/food/quotes/{quoteNo}` | `@perm.canBiz('biz:order:view')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizDiningPricingController |
| POST | `/biz/x/food/quotes/{quoteNo}:confirm` | `@perm.canBiz('biz:order:view')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizDiningPricingController |

### x/food/recipes（3）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/x/food/recipes/{recipeNo}` | `@perm.canBiz('biz:goods')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizRecipeController |
| PUT | `/biz/x/food/recipes/{recipeNo}/definition` | `@perm.canBiz('biz:goods')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizRecipeController |
| POST | `/biz/x/food/recipes/{recipeNo}:publish` | `@perm.canBiz('biz:goods')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizConfigRevisionController |

### x/food/reports（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/x/food/reports/business-day` | `@perm.canBiz('biz:order:view')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBusinessCloseController |

### x/food/reservations（5）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/x/food/reservations/{reservationNo}` | `@perm.canBiz('biz:order:view')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizReservationController |
| POST | `/biz/x/food/reservations/{reservationNo}:arrive` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizReservationController |
| POST | `/biz/x/food/reservations/{reservationNo}:cancel` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizReservationController |
| POST | `/biz/x/food/reservations/{reservationNo}:change` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizReservationController |
| POST | `/biz/x/food/reservations/{reservationNo}:no-show` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizReservationController |

### x/food/returns（3）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/x/food/returns/{returnNo}` | `@perm.canBiz('biz:order:view')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizDiningReturnController |
| GET | `/biz/x/food/returns/{returnNo}/kitchen` | `@perm.canBiz('biz:aftersale')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizKitchenReturnController |
| POST | `/biz/x/food/returns/{returnNo}/kitchen` | `@perm.canBiz('biz:aftersale')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizKitchenReturnController |

### x/food/service-entitlements（6）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/biz/x/food/service-entitlements/banquet-plan` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizServiceEntitlementController |
| POST | `/biz/x/food/service-entitlements/confirm-attendance` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizServiceEntitlementController |
| POST | `/biz/x/food/service-entitlements/consume` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizServiceEntitlementController |
| POST | `/biz/x/food/service-entitlements/extend` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizServiceEntitlementController |
| POST | `/biz/x/food/service-entitlements/grant` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizServiceEntitlementController |
| POST | `/biz/x/food/service-entitlements/list` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizServiceEntitlementController |

### x/food/settlements（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/x/food/settlements/{settlementNo}` | `@perm.canBiz('biz:order:view')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizSettlementController |

### x/food/stations（2）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/x/food/stations/{stationNo}/runtime` | `@perm.canBiz('biz:receive')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizFoodStationRuntimeController |
| POST | `/biz/x/food/stations/{stationNo}/runtime` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizFoodStationRuntimeController |

### x/food/stores（72）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/x/food/stores/{storeNo}/batches` | `@perm.canBiz('biz:order:view')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizDiningBatchQueryController |
| POST | `/biz/x/food/stores/{storeNo}/batches` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizOrderBatchController |
| GET | `/biz/x/food/stores/{storeNo}/bundles` | `@perm.canBiz('biz:goods')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBundleController |
| POST | `/biz/x/food/stores/{storeNo}/bundles` | `@perm.canBiz('biz:goods')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBundleController |
| GET | `/biz/x/food/stores/{storeNo}/bundles/{configNo}` | `@perm.canBiz('biz:goods')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizBundleController |
| POST | `/biz/x/food/stores/{storeNo}/bundles/{configNo}:publish` | `@perm.canBiz('biz:goods')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizConfigRevisionController |
| PUT | `/biz/x/food/stores/{storeNo}/calendar/{localDate}` | `@perm.canBiz('biz:store')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizServiceCalendarController |
| POST | `/biz/x/food/stores/{storeNo}/carts` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizCartController |
| GET | `/biz/x/food/stores/{storeNo}/catalog` | `@perm.canBiz('biz:goods')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizCatalogController |
| GET | `/biz/x/food/stores/{storeNo}/checks` | `@perm.canBiz('biz:order:view')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizCheckController |
| POST | `/biz/x/food/stores/{storeNo}/checks` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizCheckController |
| GET | `/biz/x/food/stores/{storeNo}/dishes` | `@perm.canBiz('biz:goods')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizDishController |
| GET | `/biz/x/food/stores/{storeNo}/dishes/{goodsNo}` | `@perm.canBiz('biz:goods')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizDishController |
| PUT | `/biz/x/food/stores/{storeNo}/dishes/{goodsNo}` | `@perm.canBiz('biz:goods')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizDishController |
| GET | `/biz/x/food/stores/{storeNo}/kitchen/tasks` | `@perm.canBiz('biz:receive')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizKitchenTaskController |
| GET | `/biz/x/food/stores/{storeNo}/menu-channel-bindings` | `@perm.canBiz('biz:goods')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizMenuChannelBindingController |
| POST | `/biz/x/food/stores/{storeNo}/menu-channel-bindings` | `@perm.canBiz('biz:goods')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizMenuChannelBindingController |
| GET | `/biz/x/food/stores/{storeNo}/menus` | `@perm.canBiz('biz:goods')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizMenuController |
| POST | `/biz/x/food/stores/{storeNo}/menus` | `@perm.canBiz('biz:goods')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizMenuController |
| GET | `/biz/x/food/stores/{storeNo}/menus/{configNo}` | `@perm.canBiz('biz:goods')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizMenuController |
| POST | `/biz/x/food/stores/{storeNo}/menus/{configNo}:publish` | `@perm.canBiz('biz:goods')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizConfigRevisionController |
| GET | `/biz/x/food/stores/{storeNo}/modifiers` | `@perm.canBiz('biz:goods')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizModifierController |
| POST | `/biz/x/food/stores/{storeNo}/modifiers` | `@perm.canBiz('biz:goods')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizModifierController |
| GET | `/biz/x/food/stores/{storeNo}/option-groups` | `@perm.canBiz('biz:goods')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizOptionGroupController |
| POST | `/biz/x/food/stores/{storeNo}/option-groups` | `@perm.canBiz('biz:goods')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizOptionGroupController |
| GET | `/biz/x/food/stores/{storeNo}/option-groups/{configNo}` | `@perm.canBiz('biz:goods')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizOptionGroupController |
| POST | `/biz/x/food/stores/{storeNo}/option-groups/{configNo}:publish` | `@perm.canBiz('biz:goods')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizConfigRevisionController |
| POST | `/biz/x/food/stores/{storeNo}/policies` | `@perm.canBiz('biz:store')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizStoreOperationPolicyController |
| GET | `/biz/x/food/stores/{storeNo}/policy` | `@perm.canBiz('biz:store')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizStoreOperationPolicyController |
| GET | `/biz/x/food/stores/{storeNo}/production-consumptions` | `@perm.canBiz('biz:order:view')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizProductionConsumptionController |
| GET | `/biz/x/food/stores/{storeNo}/production-pending` | `@perm.canBiz('biz:stock')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizProductionConsumptionController |
| POST | `/biz/x/food/stores/{storeNo}/quotes` | `@perm.canBiz('biz:order:view')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizDiningPricingController |
| GET | `/biz/x/food/stores/{storeNo}/recipes` | `@perm.canBiz('biz:goods')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizRecipeController |
| POST | `/biz/x/food/stores/{storeNo}/recipes` | `@perm.canBiz('biz:goods')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizRecipeController |
| GET | `/biz/x/food/stores/{storeNo}/reservations` | `@perm.canBiz('biz:order:view')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizReservationController |
| POST | `/biz/x/food/stores/{storeNo}/reservations` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizReservationController |
| GET | `/biz/x/food/stores/{storeNo}/returns/{returnNo}/stock-dispositions` | `@perm.canBiz('biz:aftersale') and @perm.canBiz('biz:stock')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizFoodReturnStockController |
| POST | `/biz/x/food/stores/{storeNo}/returns/{returnNo}/stock-dispositions` | `@perm.canBiz('biz:aftersale') and @perm.canBiz('biz:stock')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizFoodReturnStockController |
| POST | `/biz/x/food/stores/{storeNo}/returns/{returnNo}/stock-dispositions/{operationNo}:retry` | `@perm.canBiz('biz:aftersale') and @perm.canBiz('biz:stock')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizFoodReturnStockController |
| GET | `/biz/x/food/stores/{storeNo}/sales-restrictions` | `@perm.canBiz('biz:goods')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizSalesRestrictionController |
| POST | `/biz/x/food/stores/{storeNo}/sales-restrictions` | `@perm.canBiz('biz:goods')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizSalesRestrictionController |
| GET | `/biz/x/food/stores/{storeNo}/sku-routes/{skuNo}` | `@perm.canBiz('biz:goods')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizStationController |
| PUT | `/biz/x/food/stores/{storeNo}/sku-routes/{skuNo}` | `@perm.canBiz('biz:goods')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizStationController |
| GET | `/biz/x/food/stores/{storeNo}/stations` | `@perm.canBiz('biz:goods')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizStationController |
| POST | `/biz/x/food/stores/{storeNo}/stations` | `@perm.canBiz('biz:goods')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizStationController |
| GET | `/biz/x/food/stores/{storeNo}/stations/{configNo}` | `@perm.canBiz('biz:goods')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizStationController |
| PUT | `/biz/x/food/stores/{storeNo}/stations/{configNo}` | `@perm.canBiz('biz:goods')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizStationController |
| POST | `/biz/x/food/stores/{storeNo}/stations/{configNo}:publish` | `@perm.canBiz('biz:goods')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizConfigRevisionController |
| GET | `/biz/x/food/stores/{storeNo}/stock-balances` | `@perm.canBiz('biz:stock')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizFoodStockController |
| POST | `/biz/x/food/stores/{storeNo}/stock-balances` | `@perm.canBiz('biz:stock')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizFoodStockController |
| GET | `/biz/x/food/stores/{storeNo}/stock-balances/{itemNo}` | `@perm.canBiz('biz:stock')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizFoodStockController |
| PUT | `/biz/x/food/stores/{storeNo}/stock-balances/{itemNo}/policy` | `@perm.canBiz('biz:stock')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizFoodStockController |
| POST | `/biz/x/food/stores/{storeNo}/stock-balances/{itemNo}:adjust` | `@perm.canBiz('biz:stock')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizFoodStockController |
| GET | `/biz/x/food/stores/{storeNo}/stock-items` | `@perm.canBiz('biz:stock')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizFoodStockController |
| POST | `/biz/x/food/stores/{storeNo}/stock-items` | `@perm.canBiz('biz:stock')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizFoodStockController |
| GET | `/biz/x/food/stores/{storeNo}/stock-items/{itemNo}` | `@perm.canBiz('biz:stock')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizFoodStockController |
| PUT | `/biz/x/food/stores/{storeNo}/stock-items/{itemNo}` | `@perm.canBiz('biz:stock')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizFoodStockController |
| GET | `/biz/x/food/stores/{storeNo}/stock-movements` | `@perm.canBiz('biz:stock')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizFoodStockController |
| GET | `/biz/x/food/stores/{storeNo}/stock-reservations/{reservationNo}` | `@perm.canBiz('biz:stock')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizFoodStockReservationController |
| GET | `/biz/x/food/stores/{storeNo}/stock-sync/config` | `@perm.canBiz('biz:stock')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizFoodStockSyncController |
| PUT | `/biz/x/food/stores/{storeNo}/stock-sync/config` | `@perm.canBiz('biz:stock')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizFoodStockSyncController |
| GET | `/biz/x/food/stores/{storeNo}/stock-sync/deliveries` | `@perm.canBiz('biz:stock')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizFoodStockSyncController |
| POST | `/biz/x/food/stores/{storeNo}/stock-sync/deliveries/{syncNo}:retry` | `@perm.canBiz('biz:stock')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizFoodStockSyncController |
| GET | `/biz/x/food/stores/{storeNo}/stock-sync/mappings` | `@perm.canBiz('biz:stock')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizFoodStockSyncController |
| PUT | `/biz/x/food/stores/{storeNo}/stock-sync/mappings/{itemNo}` | `@perm.canBiz('biz:stock')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizFoodStockSyncController |
| GET | `/biz/x/food/stores/{storeNo}/stock-sync/reconciliations/{reconciliationNo}` | `@perm.canBiz('biz:stock')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizFoodStockSyncController |
| POST | `/biz/x/food/stores/{storeNo}/stock-sync:preflight` | `@perm.canBiz('biz:stock')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizFoodStockSyncController |
| POST | `/biz/x/food/stores/{storeNo}/stock-sync:reconcile` | `@perm.canBiz('biz:stock')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizFoodStockSyncController |
| GET | `/biz/x/food/stores/{storeNo}/table-areas` | `@perm.canAnyBiz('biz:store','biz:order:view')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizTableAreaController |
| POST | `/biz/x/food/stores/{storeNo}/table-areas` | `@perm.canBiz('biz:store')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizTableAreaController |
| GET | `/biz/x/food/stores/{storeNo}/tables` | `@perm.canAnyBiz('biz:store','biz:order:view')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizTableController |
| POST | `/biz/x/food/stores/{storeNo}/tables` | `@perm.canBiz('biz:store')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizTableController |

### x/food/table-areas（2）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| PUT | `/biz/x/food/table-areas/{areaNo}` | `@perm.canBiz('biz:store')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizTableAreaController |
| POST | `/biz/x/food/table-areas/{areaNo}:set-status` | `@perm.canBiz('biz:store')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizTableAreaController |

### x/food/table-operations（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/x/food/table-operations/{operationNo}` | `@perm.canBiz('biz:order:view')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizTableOperationController |

### x/food/tables（7）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/x/food/tables/{tableNo}` | `@perm.canAnyBiz('biz:store','biz:order:view')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizTableController |
| PUT | `/biz/x/food/tables/{tableNo}` | `@perm.canBiz('biz:store')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizTableController |
| POST | `/biz/x/food/tables/{tableNo}:archive` | `@perm.canBiz('biz:store')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizTableController |
| POST | `/biz/x/food/tables/{tableNo}:revoke-token` | `@perm.canBiz('biz:store')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizTableTokenController |
| POST | `/biz/x/food/tables/{tableNo}:rotate-token` | `@perm.canBiz('biz:store')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizTableTokenController |
| POST | `/biz/x/food/tables/{tableNo}:set-status` | `@perm.canBiz('biz:store')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizTableController |
| POST | `/biz/x/food/tables/{tableNo}:share` | `@perm.canBiz('biz:verify')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizTableSharingController |

### x/food/tasks（10）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/x/food/tasks/{taskNo}/events` | `@perm.canBiz('biz:receive')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizKitchenTaskController |
| POST | `/biz/x/food/tasks/{taskNo}:fire` | `@perm.canBiz('biz:receive')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizKitchenTaskController |
| POST | `/biz/x/food/tasks/{taskNo}:hold` | `@perm.canBiz('biz:receive')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizKitchenTaskController |
| POST | `/biz/x/food/tasks/{taskNo}:loss` | `@perm.canBiz('biz:stock')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizKitchenTaskController |
| POST | `/biz/x/food/tasks/{taskNo}:ready` | `@perm.canBiz('biz:receive')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizKitchenTaskController |
| POST | `/biz/x/food/tasks/{taskNo}:remake` | `@perm.canBiz('biz:aftersale')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizKitchenTaskController |
| POST | `/biz/x/food/tasks/{taskNo}:serve` | `@perm.canBiz('biz:receive')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizKitchenTaskController |
| POST | `/biz/x/food/tasks/{taskNo}:start` | `@perm.canBiz('biz:receive')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizKitchenTaskController |
| POST | `/biz/x/food/tasks/{taskNo}:urge` | `@perm.canBiz('biz:receive')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizKitchenTaskController |
| POST | `/biz/x/food/tasks/{taskNo}:void` | `@perm.canBiz('biz:aftersale')` | 能提前判 | 插件 shop-industry-food | 未部署 | BizKitchenTaskController |

## 服务业（32）

网关判权：只要求登录 27、能提前判 5。构建：插件 shop-industry-beauty 32。生产：未部署 32。

### x/beauty/appointments（9）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/x/beauty/appointments` | 登录即可 | 只要求登录 | 插件 shop-industry-beauty | 未部署 | BizAppointmentController |
| POST | `/biz/x/beauty/appointments` | 登录即可 | 只要求登录 | 插件 shop-industry-beauty | 未部署 | BizAppointmentController |
| POST | `/biz/x/beauty/appointments/previews` | 登录即可 | 只要求登录 | 插件 shop-industry-beauty | 未部署 | BizAppointmentController |
| GET | `/biz/x/beauty/appointments/{appointmentNo}` | 登录即可 | 只要求登录 | 插件 shop-industry-beauty | 未部署 | BizAppointmentController |
| POST | `/biz/x/beauty/appointments/{appointmentNo}/cancellations` | 登录即可 | 只要求登录 | 插件 shop-industry-beauty | 未部署 | BizAppointmentController |
| POST | `/biz/x/beauty/appointments/{appointmentNo}/changes` | 登录即可 | 只要求登录 | 插件 shop-industry-beauty | 未部署 | BizAppointmentController |
| POST | `/biz/x/beauty/appointments/{appointmentNo}/changes/previews` | 登录即可 | 只要求登录 | 插件 shop-industry-beauty | 未部署 | BizAppointmentController |
| POST | `/biz/x/beauty/appointments/{appointmentNo}/no-shows` | 登录即可 | 只要求登录 | 插件 shop-industry-beauty | 未部署 | BizAppointmentController |
| POST | `/biz/x/beauty/appointments/{appointmentNo}/work-orders` | 登录即可 | 只要求登录 | 插件 shop-industry-beauty | 未部署 | BizAppointmentController |

### x/beauty/arrival-challenges（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/biz/x/beauty/arrival-challenges` | 登录即可 | 只要求登录 | 插件 shop-industry-beauty | 未部署 | BizArrivalChallengeController |

### x/beauty/arrivals（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/biz/x/beauty/arrivals` | 登录即可 | 只要求登录 | 插件 shop-industry-beauty | 未部署 | BizReceptionController |

### x/beauty/availability（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/biz/x/beauty/availability/queries` | 登录即可 | 只要求登录 | 插件 shop-industry-beauty | 未部署 | BizAvailabilityController |

### x/beauty/guest-registrations（2）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/biz/x/beauty/guest-registrations` | 登录即可 | 只要求登录 | 插件 shop-industry-beauty | 未部署 | BizGuestRegistrationController |
| POST | `/biz/x/beauty/guest-registrations/{guestNo}/claims` | 登录即可 | 只要求登录 | 插件 shop-industry-beauty | 未部署 | BizGuestRegistrationController |

### x/beauty/operations（2）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/x/beauty/operations/{operationNo}` | 登录即可 | 只要求登录 | 插件 shop-industry-beauty | 未部署 | BizServiceOperationController |
| POST | `/biz/x/beauty/operations/{operationNo}/recoveries` | 登录即可 | 只要求登录 | 插件 shop-industry-beauty | 未部署 | BizServiceOperationController |

### x/beauty/reception-exceptions（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/biz/x/beauty/reception-exceptions` | 登录即可 | 只要求登录 | 插件 shop-industry-beauty | 未部署 | BizReceptionController |

### x/beauty/reports（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/x/beauty/reports/scheduling` | 登录即可 | 只要求登录 | 插件 shop-industry-beauty | 未部署 | BizSchedulingReportController |

### x/beauty/service-definitions（2）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/x/beauty/service-definitions` | `@perm.canBiz('biz:store')` | 能提前判 | 插件 shop-industry-beauty | 未部署 | BizServiceDefinitionController |
| POST | `/biz/x/beauty/service-definitions/versions` | `@perm.canBiz('biz:store')` | 能提前判 | 插件 shop-industry-beauty | 未部署 | BizServiceDefinitionController |

### x/beauty/service-submissions（2）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/x/beauty/service-submissions/{submissionNo}` | 登录即可 | 只要求登录 | 插件 shop-industry-beauty | 未部署 | BizServiceSubmissionController |
| POST | `/biz/x/beauty/service-submissions/{submissionNo}/recovery-requests` | 登录即可 | 只要求登录 | 插件 shop-industry-beauty | 未部署 | BizServiceSubmissionController |

### x/beauty/services（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/x/beauty/services/{skuNo}` | `@perm.canBiz('biz:store')` | 能提前判 | 插件 shop-industry-beauty | 未部署 | BizServiceController |

### x/beauty/store-policies（2）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/x/beauty/store-policies` | `@perm.canBiz('biz:store')` | 能提前判 | 插件 shop-industry-beauty | 未部署 | BizStorePolicyController |
| POST | `/biz/x/beauty/store-policies/versions` | `@perm.canBiz('biz:store')` | 能提前判 | 插件 shop-industry-beauty | 未部署 | BizStorePolicyController |

### x/beauty/traces（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/x/beauty/traces/{businessNo}` | 登录即可 | 只要求登录 | 插件 shop-industry-beauty | 未部署 | BizBusinessTraceController |

### x/beauty/walk-ins（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/biz/x/beauty/walk-ins` | 登录即可 | 只要求登录 | 插件 shop-industry-beauty | 未部署 | BizReceptionController |

### x/beauty/work-orders（5）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/biz/x/beauty/work-orders` | 登录即可 | 只要求登录 | 插件 shop-industry-beauty | 未部署 | BizWorkOrderController |
| GET | `/biz/x/beauty/work-orders/{workOrderNo}` | 登录即可 | 只要求登录 | 插件 shop-industry-beauty | 未部署 | BizWorkOrderController |
| GET | `/biz/x/beauty/work-orders/{workOrderNo}/bill` | 登录即可 | 只要求登录 | 插件 shop-industry-beauty | 未部署 | BizWorkOrderController |
| POST | `/biz/x/beauty/work-orders/{workOrderNo}/service-submissions` | 登录即可 | 只要求登录 | 插件 shop-industry-beauty | 未部署 | BizWorkOrderController |
| POST | `/biz/x/beauty/work-orders/{workOrderNo}/tasks/{taskNo}/preparations` | 登录即可 | 只要求登录 | 插件 shop-industry-beauty | 未部署 | BizWorkOrderController |

## 库存（45）

网关判权：能提前判 45。构建：默认构建 45。生产：已入网关 45。

### inventory（44）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/biz/inventory/adjust` | `@perm.canBiz('biz:stock')` | 能提前判 | 默认构建 | 已入网关 | BizStockController |
| GET | `/biz/inventory/balances` | `@perm.canBiz('biz:stock')` | 能提前判 | 默认构建 | 已入网关 | BizStockController |
| GET | `/biz/inventory/category-setting` | `@perm.canBiz('biz:stock')` | 能提前判 | 默认构建 | 已入网关 | BizInvManagedController |
| PUT | `/biz/inventory/category-setting/{categoryNo}` | `@perm.canBiz('biz:goods')` | 能提前判 | 默认构建 | 已入网关 | BizInvManagedController |
| POST | `/biz/inventory/counts` | `@perm.canBiz('biz:stock')` | 能提前判 | 默认构建 | 已入网关 | BizStockDocController |
| GET | `/biz/inventory/counts/{no}` | `@perm.canBiz('biz:stock')` | 能提前判 | 默认构建 | 已入网关 | BizStockDocController |
| PUT | `/biz/inventory/counts/{no}/lines` | `@perm.canBiz('biz:stock')` | 能提前判 | 默认构建 | 已入网关 | BizStockDocController |
| POST | `/biz/inventory/counts/{no}/post` | `@perm.canBiz('biz:stock')` | 能提前判 | 默认构建 | 已入网关 | BizStockDocController |
| POST | `/biz/inventory/counts/{no}/void` | `@perm.canBiz('biz:stock')` | 能提前判 | 默认构建 | 已入网关 | BizStockDocController |
| GET | `/biz/inventory/cross-store` | `@perm.canBiz('biz:stock')` | 能提前判 | 默认构建 | 已入网关 | BizStockController |
| GET | `/biz/inventory/documents` | `@perm.canBiz('biz:stock')` | 能提前判 | 默认构建 | 已入网关 | BizStockReportController |
| GET | `/biz/inventory/export` | `@perm.canBiz('biz:customer')` | 能提前判 | 默认构建 | 已入网关 | BizStockReportController |
| GET | `/biz/inventory/food-stock-projections` | `@perm.canBiz('biz:stock')` | 能提前判 | 默认构建 | 已入网关 | BizFoodStockProjectionController |
| GET | `/biz/inventory/food-stock-projections/{sourceItemNo}` | `@perm.canBiz('biz:stock')` | 能提前判 | 默认构建 | 已入网关 | BizFoodStockProjectionController |
| POST | `/biz/inventory/inbounds` | `@perm.canBiz('biz:stock')` | 能提前判 | 默认构建 | 已入网关 | BizStockDocController |
| PUT | `/biz/inventory/inbounds/{no}` | `@perm.canBiz('biz:stock')` | 能提前判 | 默认构建 | 已入网关 | BizStockDocController |
| POST | `/biz/inventory/inbounds/{no}/post` | `@perm.canBiz('biz:stock')` | 能提前判 | 默认构建 | 已入网关 | BizStockDocController |
| POST | `/biz/inventory/inbounds/{no}/void` | `@perm.canBiz('biz:stock')` | 能提前判 | 默认构建 | 已入网关 | BizStockDocController |
| GET | `/biz/inventory/item-by-sku` | `@perm.canBiz('biz:stock')` | 能提前判 | 默认构建 | 已入网关 | BizStockController |
| GET | `/biz/inventory/items/by-barcode` | `@perm.canBiz('biz:stock')` | 能提前判 | 默认构建 | 已入网关 | BizStockController |
| GET | `/biz/inventory/items/{itemId}` | `@perm.canBiz('biz:stock')` | 能提前判 | 默认构建 | 已入网关 | BizStockController |
| GET | `/biz/inventory/items/{item}/valuation` | `@perm.canBiz('biz:stock')` | 能提前判 | 默认构建 | 已入网关 | BizInventoryValuationController |
| POST | `/biz/inventory/items/{item}/valuation` | `@perm.canBiz('biz:stock')` | 能提前判 | 默认构建 | 已入网关 | BizInventoryValuationController |
| GET | `/biz/inventory/ledger` | `@perm.canBiz('biz:stock')` | 能提前判 | 默认构建 | 已入网关 | BizStockController |
| GET | `/biz/inventory/locations` | `@perm.canBiz('biz:stock')` | 能提前判 | 默认构建 | 已入网关 | BizLocationController |
| POST | `/biz/inventory/locations` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 默认构建 | 已入网关 | BizLocationController |
| PUT | `/biz/inventory/locations/{id}/source` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 默认构建 | 已入网关 | BizLocationController |
| POST | `/biz/inventory/outbounds` | `@perm.canBiz('biz:stock')` | 能提前判 | 默认构建 | 已入网关 | BizStockDocController |
| POST | `/biz/inventory/outbounds/{no}/post` | `@perm.canBiz('biz:stock')` | 能提前判 | 默认构建 | 已入网关 | BizStockDocController |
| POST | `/biz/inventory/outbounds/{no}/void` | `@perm.canBiz('biz:stock')` | 能提前判 | 默认构建 | 已入网关 | BizStockDocController |
| GET | `/biz/inventory/pickable` | `@perm.canBiz('biz:stock')` | 能提前判 | 默认构建 | 已入网关 | BizStockController |
| GET | `/biz/inventory/report/monthly` | `@perm.canBiz('biz:customer')` | 能提前判 | 默认构建 | 已入网关 | BizStockReportController |
| GET | `/biz/inventory/report/ranking` | `@perm.canBiz('biz:customer')` | 能提前判 | 默认构建 | 已入网关 | BizStockReportController |
| PUT | `/biz/inventory/safety-stock` | `@perm.canBiz('biz:stock')` | 能提前判 | 默认构建 | 已入网关 | BizStockController |
| GET | `/biz/inventory/summary` | `@perm.canBiz('biz:stock')` | 能提前判 | 默认构建 | 已入网关 | BizStockController |
| GET | `/biz/inventory/suppliers` | `@perm.canBiz('biz:stock')` | 能提前判 | 默认构建 | 已入网关 | BizSupplierController |
| POST | `/biz/inventory/suppliers` | `@perm.canBiz('biz:stock')` | 能提前判 | 默认构建 | 已入网关 | BizSupplierController |
| PUT | `/biz/inventory/suppliers/{no}` | `@perm.canBiz('biz:stock')` | 能提前判 | 默认构建 | 已入网关 | BizSupplierController |
| POST | `/biz/inventory/suppliers/{no}/active` | `@perm.canBiz('biz:stock')` | 能提前判 | 默认构建 | 已入网关 | BizSupplierController |
| POST | `/biz/inventory/transfers` | `@perm.canBiz('biz:stock')` | 能提前判 | 默认构建 | 已入网关 | BizStockDocController |
| GET | `/biz/inventory/transfers/{no}` | `@perm.canBiz('biz:stock')` | 能提前判 | 默认构建 | 已入网关 | BizStockDocController |
| POST | `/biz/inventory/transfers/{no}/receive` | `@perm.canBiz('biz:stock')` | 能提前判 | 默认构建 | 已入网关 | BizStockDocController |
| POST | `/biz/inventory/transfers/{no}/ship` | `@perm.canBiz('biz:stock')` | 能提前判 | 默认构建 | 已入网关 | BizStockDocController |
| POST | `/biz/inventory/transfers/{no}/void` | `@perm.canBiz('biz:stock')` | 能提前判 | 默认构建 | 已入网关 | BizStockDocController |

### order（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/order/{subOrderNo}/inventory-trace` | `@perm.canBiz('biz:order:view')` | 能提前判 | 默认构建 | 已入网关 | BizOrderInventoryTraceController |

## 平台与通用（29）

网关判权：只要求登录 17、公开 8、能提前判 4。构建：默认构建 29。生产：已入网关 29。

### auth（18）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/auth/account` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizAccountController |
| POST | `/biz/auth/captcha/challenge` | 公开 | 公开 | 默认构建 | 已入网关 | BizCaptchaController |
| POST | `/biz/auth/captcha/verify` | 公开 | 公开 | 默认构建 | 已入网关 | BizCaptchaController |
| POST | `/biz/auth/deregister` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizAccountController |
| GET | `/biz/auth/deregister/precheck` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizAccountController |
| POST | `/biz/auth/email-otp/send` | 公开 | 公开 | 默认构建 | 已入网关 | BizAuthController |
| POST | `/biz/auth/email/bind` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizAccountController |
| POST | `/biz/auth/identities` | 公开 | 公开 | 默认构建 | 已入网关 | BizIdentityController |
| POST | `/biz/auth/identities/enter` | 公开 | 公开 | 默认构建 | 已入网关 | BizIdentityController |
| GET | `/biz/auth/identities/mine` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizIdentityController |
| POST | `/biz/auth/login` | 公开 | 公开 | 默认构建 | 已入网关 | BizAuthController |
| POST | `/biz/auth/logout` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizAuthController |
| POST | `/biz/auth/otp/send` | 公开 | 公开 | 默认构建 | 已入网关 | BizAuthController |
| GET | `/biz/auth/password` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizAuthController |
| POST | `/biz/auth/password` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizAuthController |
| POST | `/biz/auth/phone/change` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizAccountController |
| GET | `/biz/auth/session` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizPaySessionController |
| POST | `/biz/auth/staff-login` | 公开 | 公开 | 默认构建 | 已入网关 | BizAuthController |

### dashboard（2）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/dashboard/stats` | `@perm.canBiz('biz:customer')` | 能提前判 | 默认构建 | 已入网关 | BizDashboardController |
| GET | `/biz/dashboard/todo` | `@perm.canAnyBiz('biz:order:view','biz:receive','biz:verify','biz:ship','biz:aftersale','biz:review','biz:campaign')` | 能提前判 | 默认构建 | 已入网关 | BizDashboardController |

### notify（6）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| PUT | `/biz/notify/email` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizNotifySettingController |
| GET | `/biz/notify/setting` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizNotifySettingController |
| PUT | `/biz/notify/setting` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizNotifySettingController |
| PUT | `/biz/notify/sms-phones` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizNotifySettingController |
| PUT | `/biz/notify/wecom` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizNotifySettingController |
| POST | `/biz/notify/wecom/test` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizNotifySettingController |

### roles（2）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| GET | `/biz/roles` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 默认构建 | 已入网关 | BizStaffController |
| POST | `/biz/roles` | `@perm.canBiz('biz:store:admin')` | 能提前判 | 默认构建 | 已入网关 | BizStaffController |

### upload（1）

| 方法 | 地址 | 权限要求 | 网关判权 | 构建 | 生产 | 入口类 |
|---|---|---|---|---|---|---|
| POST | `/biz/upload/image` | 登录即可 | 只要求登录 | 默认构建 | 已入网关 | BizUploadController |
