import {managementMock} from "./management";
import { storedValueMock } from './stored-value';
// B 端 mock 的门面：把各域拼成一份完整实现。
//
// <p><b>类型标注在这里，就是那道闸</b>：`MerchantApi` 要求 228 个方法一个不少，
// 拆分时漏掉任何一个，这一行都过不了 `vue-tsc` —— 而漏掉的表现本来是
// 「页面上某个按钮点了没反应」，不会有任何报错。
import type { MerchantApi } from "../contract";
import { accountMock } from "./account";
import { storeMock } from "./store";
import { dashboardMock } from "./dashboard";
import { productMock } from "./product";
import { orderMock } from "./order";
import { groupMock } from "./group";
import { platformMock } from "./platform";
import { reviewMock } from "./review";
import { marketingMock } from "./marketing";
import { settleMock } from "./settle";
import { messageMock } from "./message";
import { inventoryMock } from "./inventory";

export const mockApi: MerchantApi = {
 mSvFinancialBrandOriginal:async()=>{throw Error("Financial operations require the real backend")},
 mSvPrepareFinancePermissions:async()=>{throw Error("Financial operations require the real backend")},
mSvFinancialBrandStart:async()=>{throw Error("Financial operations require the real backend")},
mSvFinancialBrandChange:async()=>{throw Error("Financial operations require the real backend")},
mSvFinancialBrandResume:async()=>{throw Error("Financial operations require the real backend")},

  mSvFinanceCapability:async()=>{throw Error("Financial operations require the real backend")},
  mSvFinanceResources:async()=>{throw Error("Financial operations require the real backend")},
  mSvBrandSummary:async()=>{throw Error("Financial operations require the real backend")},
  mSvStoreSummary:async()=>{throw Error("Financial operations require the real backend")},
  mSvBrandStoreBreakdown:async()=>{throw Error("Financial operations require the real backend")},
  mSvLocalIssuerSummary:async()=>{throw Error("Financial operations require the real backend")},
  mSvActivitySummary:async()=>{throw Error("Financial operations require the real backend")},
  mSvSettlementBills:async()=>{throw Error("Financial operations require the real backend")},
  mSvSettlementBill:async()=>{throw Error("Financial operations require the real backend")},
  mSvSettlementActions:async()=>{throw Error("Financial operations require the real backend")},
  mSvSettlementItems:async()=>{throw Error("Financial operations require the real backend")},
  mSvSettlementEvents:async()=>{throw Error("Financial operations require the real backend")},
  mSvSettlementPayments:async()=>{throw Error("Financial operations require the real backend")},
  mSvSettlementGenerate:async()=>{throw Error("Financial operations require the real backend")},
  mSvSettlementReconcile:async()=>{throw Error("Financial operations require the real backend")},
  mSvSettlementDispute:async()=>{throw Error("Financial operations require the real backend")},
  mSvSettlementResolveDispute:async()=>{throw Error("Financial operations require the real backend")},
  mSvSettlementAdjust:async()=>{throw Error("Financial operations require the real backend")},
  mSvSettlementRegister:async()=>{throw Error("Financial operations require the real backend")},
  mSvSettlementPaymentResolution:async()=>{throw Error("Financial operations require the real backend")},
  mSvSettlementReceipt:async()=>{throw Error("Financial operations require the real backend")},
  mSvSettlementVoid:async()=>{throw Error("Financial operations require the real backend")},

  ...managementMock,
  mSvStoreIssuerConfiguration:async()=>{throw Error('Issuer operations require the real backend')},
  mSvConfigureStoreIssuer:async()=>{throw Error('Issuer operations require the real backend')},
  mSvPreparePolicyPermissions:async()=>{throw Error("Stored-value governance requires the real backend")},
  mSvBrandActivityContext:async()=>{throw Error("Brand activities require the real backend")},
  mSvBrandActivityPresets:async()=>{throw Error("Brand activities require the real backend")},
  mSvBrandActivitySchedule:async()=>{throw Error("Brand activities require the real backend")},
  mSvBrandActivities:async()=>{throw Error("Brand activities require the real backend")},
  mSvBrandActivity:async()=>{throw Error("Brand activities require the real backend")},
  mSvBrandActivitySave:async()=>{throw Error("Brand activities require the real backend")},
  mSvBrandActivityDraftEdit:async()=>{throw Error("Brand activities require the real backend")},
  mSvBrandActivityCheck:async()=>{throw Error("Brand activities require the real backend")},
  mSvBrandActivitySubmit:async()=>{throw Error("Brand activities require the real backend")},
  mSvBrandActivityStop:async()=>{throw Error("Brand activities require the real backend")},
  mSvBrandActivityCopy:async()=>{throw Error("Brand activities require the real backend")},
  mSvActivityDraftEdit:async()=>{throw Error("Stored-value governance requires the real backend")},
  mSvActivityContext:async()=>{throw Error("Stored-value governance requires the real backend")},
  mSvActivityPresets:async()=>{throw Error("Stored-value governance requires the real backend")},
  mSvActivitySchedule:async()=>{throw Error("Stored-value governance requires the real backend")},
  mSvGovernanceCapability:async()=>({protocol:1,enabled:false}),
  mSvPolicyTargets:async()=>{throw Error("Stored-value governance requires the real backend")},
  mSvPolicies:async()=>{throw Error("Stored-value governance requires the real backend")},
  mSvPolicyCreate:async()=>{throw Error("Stored-value governance requires the real backend")},
  mSvPolicyEdit:async()=>{throw Error("Stored-value governance requires the real backend")},
  mSvEffectivePolicy:async()=>{throw Error("Stored-value governance requires the real backend")},
  mSvActivities:async()=>{throw Error("Stored-value governance requires the real backend")},
  mSvActivity:async()=>{throw Error("Stored-value governance requires the real backend")},
  mSvActivitySave:async()=>{throw Error("Stored-value governance requires the real backend")},
  mSvActivityCheck:async()=>{throw Error("Stored-value governance requires the real backend")},
  mSvActivitySubmit:async()=>{throw Error("Stored-value governance requires the real backend")},
  mSvActivityStop:async()=>{throw Error("Stored-value governance requires the real backend")},
  mSvActivityCopy:async()=>{throw Error("Stored-value governance requires the real backend")},
  mSvApprovals:async()=>{throw Error("Stored-value governance requires the real backend")},
  mSvApproval:async()=>{throw Error("Stored-value governance requires the real backend")},
  mSvApprovalDecide:async()=>{throw Error("Stored-value governance requires the real backend")},
  mSvIssuer:async()=>{throw Error('Issuer operations require the real backend')},
  mSvConfigureIssuer:async()=>{throw Error('Issuer operations require the real backend')},
  mSvIssuerImpact:async()=>{throw Error('Issuer operations require the real backend')},
  mSvIssuerVersion:async()=>{throw Error('Issuer operations require the real backend')},
  mSvIssuerEntities:async()=>{throw Error('Issuer operations require the real backend')},
  mSvWalletRecords:async()=>{throw Error("Wallet reads require the real backend")},
  mSvConsumptionCancel:async()=>{throw Error("Consumption requires the real backend")},
  mSvConsumptionResolve:async()=>{throw Error("Consumption requires the real backend")},
  mSvConsumptionPreview:async()=>{throw Error("Consumption requires the real backend")},
  mSvConsumptionOrder:async()=>{throw Error("Consumption requires the real backend")},
  mSvConsume:async()=>{throw Error("Consumption requires the real backend")},
  mSvWallets:async()=>{throw Error('Wallet selection requires the real backend')},
  mSvBrandCapability:async()=>({protocol:1,ready:false,blockers:['MOCK_UNAVAILABLE']}),
  mSvBrandActivate:async()=>{throw Error('Brand activation requires the real backend')},
  mSvStoreIssuers:async()=>{throw Error('Issuer operations require the real backend')},
  mSvRechargeOptions:async()=>{throw Error('Issuer operations require the real backend')},
  mSvIssuerRoutes:async()=>{throw Error('Issuer operations require the real backend')},
  mSvPrepareIssuer:async()=>{throw Error('Issuer operations require the real backend')},
  mSvRefreshIssuer:async()=>{throw Error('Issuer operations require the real backend')},
  mSvIssuerRecharge:async()=>{throw Error('Issuer operations require the real backend')},
  mSvIssuerCancel:async()=>{throw Error('Issuer operations require the real backend')},

  mScopeCandidates:async()=>{throw Error('Member scopes require real backend')},
  mScopeSelectCustomer:async()=>{throw Error('Member scopes require real backend')},
  mScopeEnrollMember:async()=>{throw Error('Member scopes require real backend')},
  mOrderPointsEarning: async () => [],
  ...storedValueMock,
  ...accountMock,
  ...storeMock,
  ...dashboardMock,
  ...productMock,
  ...orderMock,
  ...groupMock,
  ...platformMock,
  ...reviewMock,
  ...marketingMock,
  ...settleMock,
  ...messageMock,
  ...inventoryMock,
};
