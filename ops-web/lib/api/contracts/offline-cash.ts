import type {CashReceipt,CashRefund,CashRefundRequest,CashPayout} from "../../offline-cash-types";

/** Trusted actual bank cash; refunds are independent of quote prices and bank transfers. */
export interface CashRefundApi {
  mallCashReceipts(store:string,order:string):Promise<CashReceipt[]>;
  mallCashRefunds(store:string,order:string):Promise<CashRefund[]>;
  requestMallCashRefund(store:string,order:string,v:CashRefundRequest):Promise<CashRefund>;
  payoutMallCashRefund(store:string,order:string,refund:string,v:CashPayout):Promise<CashRefund>;
  recoverMallCashRefund(store:string,order:string,refund:string):Promise<CashRefund>;
  entityCashReceipts(entity:string,order:string):Promise<CashReceipt[]>;
  entityCashRefunds(entity:string,order:string):Promise<CashRefund[]>;
  requestEntityCashRefund(entity:string,order:string,v:CashRefundRequest):Promise<CashRefund>;
  payoutEntityCashRefund(entity:string,order:string,refund:string,v:CashPayout):Promise<CashRefund>;
  recoverEntityCashRefund(entity:string,order:string,refund:string):Promise<CashRefund>;
}
