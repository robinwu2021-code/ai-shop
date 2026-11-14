import type {RetailReplenishmentApi} from '../retail-replenishment';
/** No fake success: retail stock changes only exist on the real server ledger. */
const unavailable=async():Promise<never>=>{throw Error('retailReplenishment.unavailable')};
export const retailReplenishmentMock:RetailReplenishmentApi={mRrContext:unavailable,mRrList:unavailable,mRrOriginal:unavailable,mRrReconciliation:unavailable,mRrConfigure:unavailable,mRrRegister:unavailable,mRrAdjust:unavailable,mRrReceive:unavailable,mRrClose:unavailable,mRrReturnDispatch:unavailable,mRrReturnAck:unavailable,mRrManualReceipt:unavailable};
