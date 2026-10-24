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
  ...managementMock,
  mSvStoreIssuerConfiguration:async()=>{throw Error('Issuer operations require the real backend')},
  mSvConfigureStoreIssuer:async()=>{throw Error('Issuer operations require the real backend')},
  mSvPreparePolicyPermissions:async()=>{throw Error("Stored-value governance requires the real backend")},
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
