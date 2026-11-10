import type {CashRefund,CashRefundRequest,CashPayout} from "./offline-cash-types";
export type CashIntent={kind:"request";command:CashRefundRequest}|{kind:"payout";refundNo:string;command:CashPayout};
/** Sensitive payout details live only in this mounted attempt. */
export class CashRefundAttempt {
 readonly intent:CashIntent;
 phase:"ready"|"unknown"|"absent"|"accepted"|"done"="ready";
 constructor(intent:CashIntent){this.intent=intent.kind==="request"?{kind:"request",command:Object.freeze({...intent.command})}:{kind:"payout",refundNo:intent.refundNo,command:Object.freeze({...intent.command,actualAmount:Object.freeze({...intent.command.actualAmount})})};}
 private outcome(r:CashRefund){
  const i=this.intent;
  // Trusted refund completion may belong to another operator or the native late path.
  // Unlock without asserting that this local proof/action was accepted.
  if(i.kind==="payout"&&r.refundNo===i.refundNo&&r.status==="REFUNDED"){this.phase="done";return r;}
  if(i.kind==="request"){if(r.refundNo===i.command.refundNo||r.paymentNo===i.command.paymentNo)this.phase="done";}
  else {const a=r.actions.find(a=>a.actionNo===i.command.actionNo);if(a)this.phase=a.status==="ACCEPTED"?"accepted":"done";}
  return r;
 }
 async submit(write:(intent:CashIntent)=>Promise<CashRefund>){
  if(!["ready","absent"].includes(this.phase))throw new Error("LOOKUP_REQUIRED");
  this.phase="unknown";return this.outcome(await write(this.intent));
 }
 lookup(rows:CashRefund[]){
  const i=this.intent,r=rows.find(r=>i.kind==="request"?(r.refundNo===i.command.refundNo||(r.refundNo.startsWith("late-")&&r.paymentNo===i.command.paymentNo)):r.refundNo===i.refundNo);
  if(r)this.outcome(r);
  const found=i.kind==="request"?!!r:!!r?.actions.some(a=>a.actionNo===i.command.actionNo);
  // An accepted receipt may disappear from a stale replica; never authorize a repeat.
  if(!found&&this.phase!=="accepted"&&this.phase!=="done"&&(i.kind==="request"||!!r))this.phase="absent";
  return r;
 }
}
