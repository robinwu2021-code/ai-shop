import type { MerchantApi } from '../contract';
const unavailable=async ():Promise<never>=>{throw Error('Stored value requires the real backend');};
export const storedValueMock: Pick<MerchantApi,'mSvResolveRecharge'|'mSvCancelCreate'|'mSvContext'|'mSvPlans'|'mSvPlan'|'mSvCreatePlan'|'mSvPlanStatus'|'mSvAccounts'|'mSvAccount'|'mSvRecords'|'mSvRecord'|'mSvCreateRecharge'|'mSvRecharge'|'mSvSubmit'|'mSvReceive'|'mSvCancel'>={
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
