import { describe, expect, it, vi } from "vitest";
vi.mock("../http-client",()=>({client:{post:vi.fn(),get:vi.fn()}}));
import { client } from "../http-client";
import { afterSaleHttp } from "./aftersale";
import { refundProgress, canRecoverRefund } from "@/lib/refund-progress";
import type { AfterSale } from "@/lib/types";
describe("原退款执行恢复",()=>{
 it("使用原售后号，不传强制成功或新退款金额",async()=>{vi.mocked(client.post).mockResolvedValue({afterSaleNo:"AS1",status:"REFUNDING"});await afterSaleHttp.recoverAfterSale("AS1");expect(client.post).toHaveBeenCalledWith("/ops/after-sales/AS1/recover",{});});
 it("资金和资源状态独立，并保留人工出口",()=>{const sale={refundExecution:{status:"SUCCEEDED",resourceStatus:"PENDING"}} as AfterSale;expect(refundProgress(sale)).toBe("refundResourcesPending");expect(canRecoverRefund(sale)).toBe(true);sale.refundExecution!.resourceStatus="DONE";expect(refundProgress(sale)).toBe("refundDone");expect(canRecoverRefund(sale)).toBe(false);sale.refundExecution!.status="UNKNOWN";sale.refundExecution!.errorCode="REVERSE_UNCONFIRMED";expect(refundProgress(sale)).toBe("refundManual");});
});
