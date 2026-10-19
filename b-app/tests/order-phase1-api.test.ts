import { beforeEach, describe, expect, it, vi } from "vitest";
const { post } = vi.hoisted(() => ({ post: vi.fn() }));
vi.mock("@shared/net/http-client", () => ({ http: { post }, clientTag: () => "test" }));
import { httpApi } from "@/api/http";
beforeEach(() => { post.mockReset(); post.mockResolvedValue({ status: "REFUNDING" }); });
describe("基础退款 HTTP 合同", () => {
  it("收妥且可回库时明确提交 true", async () => {
    await httpApi.mConfirmReturn("AS1", true);
    expect(post).toHaveBeenCalledWith("/biz/after-sale/AS1/receive", { restockApproved: true });
  });
  it("旧调用缺省不回库", async () => {
    await httpApi.mConfirmReturn("AS1");
    expect(post).toHaveBeenCalledWith("/biz/after-sale/AS1/receive", { restockApproved: false });
  });
  it("全额审核不填写金额，受理仍为处理中", async () => {
    const response = await httpApi.mApproveAfterSale("AS1", "同意", undefined, "CASH");
    expect(post).toHaveBeenCalledWith("/biz/after-sale/AS1/approve", {
      remark: "同意", refundMinor: undefined, refundMode: "CASH",
    });
    expect(response.status).toBe("REFUNDING");
  });
});
