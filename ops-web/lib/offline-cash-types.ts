/** Trusted bank cash, independent of the sales quote. */
export type CashMoney={
  /** 真实资金币种代码；不得回落为订单报价币种。 */
  currency:string;
  /** 最小金额单位整数（数字或整数字符串）；所有币种固定两位小数。 */
  minor:string|number;
};
export type CashReceipt={
  /** 资金底座确认的原始真实支付号。 */
  paymentNo:string;
  /** 原始支付尝试编号，用于核对该笔实收来源。 */
  paymentAttemptNo:string;
  /** 实际收款的平台商户号。 */
  platformMerchantNo:string;
  /** 可信真实实收金额，独立于销售报价。 */
  amount:CashMoney;
  /** 该笔实收已核实退回的最小金额单位累计值。 */
  refundedMinor:string|number;
  /** 是否仍有未完成退款；为 true 时不可解除订单占用。 */
  pendingRefund:boolean;
  /** 是否为异常实收；正常已交付权益对应款不能走异常款退款。 */
  exceptionCash:boolean;
  /** 资金底座是否已通过银行对账关闭该笔收款。 */
  closed:boolean;
};
export type CashRefundRequest={
  /** 退款申请幂等号，须为 cash- 加 UUID，最多 64 字符；可能返回已有 late- 规范退款号。 */
  refundNo:string;
  /** 所退款项的可信原始支付号；金额由服务器冻结剩余可退实收。 */
  paymentNo:string;
  /** 申请退回异常实收的审计理由。 */
  reason:string;
};
export type CashPayout={
  /** 本次实际退回核验动作幂等号；未知结果必须查询并沿用原请求。 */
  actionNo:string;
  /** 实际银行退回金额，必须匹配该退款冻结的真实金额与币种。 */
  actualAmount:CashMoney;
  /** 实际银行退回时间，ISO 8601 UTC 秒精度。 */
  paidOutAt:string;
  /** 运营已核实的原付款方标识，仅用于私有核验，不返回买家。 */
  payeeReference:string;
  /** 实际银行退款流水参考号；录入本字段不发起银行转账。 */
  bankReference:string;
  /** 本订单 REFUND_PROOF 用途的独立私有退款凭证资产键。 */
  evidenceKey:string;
};
export type CashRefund={
  /** 服务器规范退款号；包括 cash- 申请和已有 late- 自动补偿退款。 */
  refundNo:string;
  /** 退款绑定的原始真实支付号。 */
  paymentNo:string;
  /** 本次冻结的真实应退金额，不能以销售报价代替。 */
  amount:CashMoney;
  /** 退款阶段 PREPARED/AWAITING_PAYOUT/PAYOUT_ACCEPTED/REFUNDED/REJECTED；仅 REFUNDED 证明已退回。 */
  status:string;
  /** 原始退款申请理由。 */
  reason:string;
  /** 退款协调或银行核验拒绝原因；无拒绝时为 null。 */
  rejection:string|null;
  /** 买家安全的原核验动作状态集合；不包含原付款方、银行流水或平台凭证。 */
  actions:{
    /** 原实际退回核验动作号。 */
    actionNo:string;
    /** 核验状态 ACCEPTED/APPLIED/REJECTED；ACCEPTED 不证明退款完成。 */
    status:string;
    /** 本动作明确拒绝原因；无拒绝时为 null。 */
    rejection:string|null;
  }[];
};
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
