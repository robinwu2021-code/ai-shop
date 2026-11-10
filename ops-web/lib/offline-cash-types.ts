/** Trusted bank cash, independent of the sales quote. */
export type CashMoney={currency:string;minor:string|number};
export type CashReceipt={paymentNo:string;paymentAttemptNo:string;platformMerchantNo:string;amount:CashMoney;refundedMinor:string|number;pendingRefund:boolean;exceptionCash:boolean;closed:boolean};
export type CashRefundRequest={refundNo:string;paymentNo:string;reason:string};
export type CashPayout={actionNo:string;actualAmount:CashMoney;paidOutAt:string;payeeReference:string;bankReference:string;evidenceKey:string};
export type CashRefund={refundNo:string;paymentNo:string;amount:CashMoney;status:string;reason:string;rejection:string|null;actions:{actionNo:string;status:string;rejection:string|null}[]};
export function remainingCash(r:CashReceipt):bigint{return BigInt(r.amount.minor)-BigInt(r.refundedMinor);}
export function refundableClear(rows:CashReceipt[]):boolean{return rows.every(r=>remainingCash(r)===0n&&!r.pendingRefund);}
export type {CashRefundApi} from "./api/contracts/offline-cash";
/** The payment ledger uses scale 2 for every currency, including JPY/KRW. */
export function parseCashMajor(input:string):string|null{
 const m=input.trim().replace(/,/g,"").match(/^(\d{1,17})(?:\.(\d{0,2}))?$/);if(!m)return null;
 const minor=(m[1]+(m[2]??"").padEnd(2,"0")).replace(/^0+(?=\d)/,"");return /^(0|[1-9][0-9]{0,18})$/.test(minor)?minor:null;
}
export function cashMoneyLabel(m:CashMoney):string{
 const padded=String(m.minor).padStart(3,"0");return `${m.currency} ${padded.slice(0,-2).replace(/\B(?=(\d{3})+(?!\d))/g,",")}.${padded.slice(-2)}`;
}
