import {expect,it,vi} from "vitest";
import {CashRefundAttempt} from "./cash-refund-attempt";
import {parseCashMajor,cashMoneyLabel,refundableClear,remainingCash,type CashRefund,type CashReceipt} from "./offline-cash-types";
const refund=(actions:CashRefund["actions"]=[],status="AWAITING_PAYOUT"):CashRefund=>({refundNo:"cash-R1",paymentNo:"P1",amount:{currency:"TWD",minor:"12500"},status,reason:"duplicate cash",rejection:null,actions});
it("freezes actual return and requires original lookup after a lost response",async()=>{
 const cmd={actionNo:"A1",actualAmount:{currency:"TWD",minor:"12500"},paidOutAt:"2026-11-11T00:00:00Z",payeeReference:"verified payer",bankReference:"RETURN-1",evidenceKey:"private-proof"};
 const a=new CashRefundAttempt({kind:"payout",refundNo:"cash-R1",command:cmd});cmd.actualAmount.minor="999";cmd.payeeReference="other";
 const write=vi.fn().mockRejectedValueOnce(new Error("timeout")).mockResolvedValueOnce(refund([{actionNo:"A1",status:"APPLIED",rejection:null}],"REFUNDED"));
 await expect(a.submit(write)).rejects.toThrow("timeout");await expect(a.submit(write)).rejects.toThrow("LOOKUP_REQUIRED");expect(write).toHaveBeenCalledTimes(1);
 a.lookup([refund()]);expect(a.phase).toBe("absent");await a.submit(write);expect(a.phase).toBe("done");expect(write.mock.calls[1][0].command).toMatchObject({actualAmount:{minor:"12500"},payeeReference:"verified payer",actionNo:"A1"});
});
it("accepted is not refunded and a stale missing action cannot authorize resubmission",async()=>{
 const a=new CashRefundAttempt({kind:"payout",refundNo:"cash-R1",command:{actionNo:"A1",actualAmount:{currency:"TWD",minor:"12500"},paidOutAt:"2026-11-11T00:00:00Z",payeeReference:"payer",bankReference:"B",evidenceKey:"K"}});
 await a.submit(async()=>refund([{actionNo:"A1",status:"ACCEPTED",rejection:null}],"PAYOUT_ACCEPTED"));expect(a.phase).toBe("accepted");a.lookup([refund()]);expect(a.phase).toBe("accepted");await expect(a.submit(vi.fn())).rejects.toThrow("LOOKUP_REQUIRED");
 a.lookup([refund([{actionNo:"A1",status:"REJECTED",rejection:"INVALID_PROOF"}])]);expect(a.phase).toBe("done");
});
it("refund creation uses the same request after absent lookup and never resubmits an observed refund",async()=>{
 const cmd={refundNo:"cash-R1",paymentNo:"P1",reason:"duplicate cash"};const a=new CashRefundAttempt({kind:"request",command:cmd});const write=vi.fn().mockRejectedValueOnce(new Error("timeout")).mockResolvedValue(refund());await expect(a.submit(write)).rejects.toThrow();a.lookup([]);await a.submit(write);expect(write.mock.calls[0][0]).toEqual(write.mock.calls[1][0]);a.lookup([]);expect(a.phase).toBe("done");
});
it("release uses actual bank cash and blocks partial, pending or regular receipt balances",()=>{
 const receipt:CashReceipt={paymentNo:"P",paymentAttemptNo:"T",platformMerchantNo:"PLT",amount:{currency:"TWD",minor:"900719925474099300"},refundedMinor:"900719925474099299",pendingRefund:false,exceptionCash:true,closed:false};
 expect(remainingCash(receipt)).toBe(1n);expect(refundableClear([receipt])).toBe(false);const full={...receipt,refundedMinor:receipt.amount.minor};expect(refundableClear([full])).toBe(true);expect(refundableClear([{...full,pendingRefund:true}])).toBe(false);expect(refundableClear([full,{...receipt,exceptionCash:false}])).toBe(false);
});
it("adopts canonical late refund returned by the server or found by exact original payment",async()=>{
 const a=new CashRefundAttempt({kind:"request",command:{refundNo:"cash-new",paymentNo:"P1",reason:"late cash"}});await a.submit(async()=>({...refund(),refundNo:"late-canonical"}));expect(a.phase).toBe("done");
 const lost=new CashRefundAttempt({kind:"request",command:{refundNo:"cash-lost",paymentNo:"P1",reason:"late cash"}});await expect(lost.submit(async()=>{throw new Error("timeout")})).rejects.toThrow();lost.lookup([{...refund(),refundNo:"late-canonical"}]);expect(lost.phase).toBe("done");
});

it("cash ledger keeps scale 2 for all currencies including JPY without number rounding",()=>{
 expect(cashMoneyLabel({currency:"JPY",minor:"12345"})).toBe("JPY 123.45");expect(parseCashMajor("123.45")).toBe("12345");expect(parseCashMajor("9007199254740993.01")).toBe("900719925474099301");expect(parseCashMajor("1.001")).toBeNull();
});
it.each([{actions:[]},{actions:[{actionNo:"OTHER_OPERATOR",status:"APPLIED",rejection:null}]}])("trusted REFUNDED unlocks without attributing completion to the local payout action",async ({actions})=>{
 const a=new CashRefundAttempt({kind:"payout",refundNo:"cash-R1",command:{actionNo:"LOCAL",actualAmount:{currency:"TWD",minor:"12500"},paidOutAt:"2026-11-11T00:00:00Z",payeeReference:"payer",bankReference:"B",evidenceKey:"LOCAL_PROOF"}});
 await expect(a.submit(async()=>{throw new Error("timeout")})).rejects.toThrow("timeout");
 const trusted=refund(actions,"REFUNDED");expect(a.lookup([trusted])).toBe(trusted);expect(a.phase).toBe("done");expect(trusted.actions.some(a=>a.actionNo==="LOCAL")).toBe(false);
 a.lookup([refund()]);expect(a.phase).toBe("done");const resend=vi.fn();await expect(a.submit(resend)).rejects.toThrow("LOOKUP_REQUIRED");expect(resend).not.toHaveBeenCalled();
});
