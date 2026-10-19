import { beforeEach, describe, expect, it, vi } from "vitest";
const { post } = vi.hoisted(() => ({ post: vi.fn() }));
vi.mock("@shared/net/http-client", () => ({ http: { post }, clientTag: () => "test" }));
import { httpApi } from "@/api/http";
beforeEach(() => { post.mockReset(); post.mockResolvedValue({ status: "REFUNDING" }); });
describe("基础退款 HTTP 合同", () => {
  it("收货验收逐行提交件数与品质，并传入可重试的收货单号", async () => {
    const request = { receiptNo: "RR-AS1", lines: [{ itemId: 11, receivedQty: 1, quality: "SALEABLE" as const }] };
    await httpApi.mConfirmReturn("AS1", request);
    expect(post).toHaveBeenCalledWith("/biz/after-sale/AS1/receive", request);
  });
  it("全额审核不填写金额，受理仍为处理中", async () => {
    const response = await httpApi.mApproveAfterSale("AS1", "同意", undefined, "CASH");
    expect(post).toHaveBeenCalledWith("/biz/after-sale/AS1/approve", {
      remark: "同意", refundMinor: undefined, refundMode: "CASH",
    });
    expect(response.status).toBe("REFUNDING");
  });
});
