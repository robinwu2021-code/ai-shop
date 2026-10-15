import { beforeEach, describe, expect, it, vi } from "vitest";
const { post } = vi.hoisted(() => ({ post: vi.fn() }));
vi.mock("@shared/net/http-client", () => ({ http: { post }, clientTag: () => "test" }));
import { httpApi } from "@/api/http";
beforeEach(() => { post.mockReset(); });
describe("基础售后 HTTP 合同", () => {
  it("按门店子单确认收货", async () => {
    post.mockResolvedValue({ orderNo: "SUB1", status: "COMPLETED" });
    expect((await httpApi.confirmReceipt("SUB1")).status).toBe("COMPLETED");
    expect(post).toHaveBeenCalledWith("/mp/order/SUB1/confirm-receipt", {});
  });
  it("用售后号撤销，返回售后状态而非订单", async () => {
    post.mockResolvedValue({ afterSaleNo: "AS1", status: "CLOSED" });
    const result = await httpApi.cancelAfterSale("AS1");
    expect(post).toHaveBeenCalledWith("/mp/after-sale/AS1/cancel", {});
    expect(result.status).toBe("CLOSED");
  });
  it("申请全额退款省略金额与商品数量", async () => {
    post.mockResolvedValue({ status: "APPLIED" });
    const result = await httpApi.applyAfterSale("SUB1", "不需要了", [], "REFUND_ONLY", "NOT_WANTED");
    expect(post).toHaveBeenCalledWith("/mp/order/SUB1/after-sale", {
      reason: "不需要了", images: [], type: "REFUND_ONLY", reasonCode: "NOT_WANTED",
    });
    expect(result.status).toBe("APPLIED");
  });
});
