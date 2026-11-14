import type { MerchantApi } from '../contract';
const unavailable=async ():Promise<never>=>{throw Error('Stored value requires the real backend');};
export const storedValueMock: Pick<MerchantApi,'mSvCandidates'|'mSvSelectCustomer'|'mSvConfirmOffline'|'mSvResolveRecharge'|'mSvCancelCreate'|'mSvContext'|'mSvPlans'|'mSvPlan'|'mSvCreatePlan'|'mSvPlanStatus'|'mSvAccounts'|'mSvAccount'|'mSvRecords'|'mSvRecord'|'mSvCreateRecharge'|'mSvRecharge'|'mSvSubmit'|'mSvReceive'|'mSvCancel'>={
 mSvCandidates:unavailable,
 mSvSelectCustomer:unavailable,
 mSvConfirmOffline:unavailable,
 mSvResolveRecharge:unavailable,
 mSvCancelCreate:unavailable,
 mSvContext:unavailable,
 mSvPlans:unavailable,
 mSvPlan:unavailable,
 mSvCreatePlan:unavailable,
 mSvPlanStatus:unavailable,
 mSvAccounts:unavailable,
 mSvAccount:unavailable,
 mSvRecords:unavailable,
 mSvRecord:unavailable,
 mSvCreateRecharge:unavailable,
 mSvRecharge:unavailable,
 mSvSubmit:unavailable,
 mSvReceive:unavailable,
 mSvCancel:unavailable,
};
