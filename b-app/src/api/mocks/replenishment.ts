import type {ReplenishmentApi} from '../replenishment';
const unavailable=async():Promise<never>=>{throw Error('replenishment.unavailable')};
export const replenishmentMock:ReplenishmentApi={mRpState:unavailable,mRpCancel:unavailable,mRpContext:unavailable,mRpList:unavailable,mRpOriginal:unavailable,mRpReconciliation:unavailable,mRpCommand:unavailable,mRpConfigurationCommand:unavailable,mRpConfigure:unavailable,mRpRegister:unavailable,mRpManualRegister:unavailable,mRpReceive:unavailable,mRpClose:unavailable,mRpReturnDispatch:unavailable,mRpReturnAck:unavailable,mRpManualReceipt:unavailable};
