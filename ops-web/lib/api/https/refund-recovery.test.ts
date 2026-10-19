import { describe, expect, it, vi } from "vitest";
vi.mock("../http-client",()=>({client:{post:vi.fn(),get:vi.fn()}}));
import { client } from "../http-client";
import { afterSaleHttp } from "./aftersale";
import { refundProgress, canRecoverRefund } from "@/lib/refund-progress";
import type { AfterSale } from "@/lib/types";
describe("原退款执行恢复",()=>{
 it("权益未完仍可恢复，即使库存已经完成",()=>{
   const sale={refundExecution:{status:"SUCCEEDED",resourceStatus:"DONE",closureVersion:2,closureStatus:"PENDING",cashStatus:"CONFIRMED",benefitsStatus:"PENDING"}} as AfterSale;
   expect(refundProgress(sale)).toBe("refundBenefitsPending");expect(canRecoverRefund(sale)).toBe(true);
 });
 it("零现金准备失败或需人工核对不被权益进度覆盖",()=>{
   const sale={refundExecution:{status:"FAILED",resourceStatus:"PENDING",closureVersion:2,closureStatus:"PENDING",cashStatus:"NOT_REQUIRED"}} as AfterSale;
   expect(refundProgress(sale)).toBe("refundFailed");
   sale.refundExecution!.status="MANUAL_REQUIRED";expect(refundProgress(sale)).toBe("refundManual");
 });
 it("使用原售后号，不传强制成功或新退款金额",async()=>{vi.mocked(client.post).mockResolvedValue({afterSaleNo:"AS1",status:"REFUNDING"});await afterSaleHttp.recoverAfterSale("AS1");expect(client.post).toHaveBeenCalledWith("/ops/after-sales/AS1/recover",{});});
 it("资金和资源状态独立，并保留人工出口",()=>{const sale={refundExecution:{status:"SUCCEEDED",resourceStatus:"PENDING"}} as AfterSale;expect(refundProgress(sale)).toBe("refundResourcesPending");expect(canRecoverRefund(sale)).toBe(true);sale.refundExecution!.resourceStatus="DONE";expect(refundProgress(sale)).toBe("refundDone");expect(canRecoverRefund(sale)).toBe(false);sale.refundExecution!.status="UNKNOWN";sale.refundExecution!.errorCode="REVERSE_UNCONFIRMED";expect(refundProgress(sale)).toBe("refundManual");});
});
