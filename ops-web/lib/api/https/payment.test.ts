import { describe, it, expect, vi } from "vitest";
const http = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }));
vi.mock("../http-client", () => ({ client: http }));
import { paymentHttp } from "./payment";
const row = {
  diffNo: "D", billDate: "2026-10-18", payChannel: "WECHAT",
  diffType: "REFUND_LATE_SUCCESS", source: "SELF_CHECK", paymentNo: "PY", orderNo: "O",
  channelTxnNo: "BANK", channelAmountMinor: 100, platformAmountMinor: 100,
  status: "PENDING", resolution: "REFUND_LATE_SUCCESS_FROZEN", recoveredOrderNo: null,
  createdAt: 1000, resolvedAt: null, resolvedBy: null,
};
describe("refund recon HTTP contract", () => {
  it("maps real backend fields so a late-success issue is visible", async () => {
    http.get.mockResolvedValue({ records: [row], total: 1, page: 1, size: 20 });
    const page = await paymentHttp.listReconDiffs();
    expect(page.records[0]).toMatchObject({ type: "REFUND_LATE_SUCCESS", channel: "WECHAT",
      channelAmount: 100, platformAmount: 100, createdAt: "1970-01-01T00:00:01.000Z" });
    expect(page.total).toBe(1);
  });
  it("maps resolve and ignore responses without adding a funds action", async () => {
    http.post.mockResolvedValue({ ...row, status: "RESOLVED", resolvedAt: 2000 });
    for (const action of [paymentHttp.resolveReconDiff, paymentHttp.ignoreReconDiff]) {
      const result = await action({ diffNo: "D", resolution: "checked" });
      expect(result.type).toBe("REFUND_LATE_SUCCESS");
      expect(result.resolvedAt).toBe("1970-01-01T00:00:02.000Z");
    }
    expect(http.post).toHaveBeenCalledWith("/ops/payments/recon-diffs/D/resolve", { diffNo: "D", resolution: "checked" });
    expect(http.post).toHaveBeenCalledWith("/ops/payments/recon-diffs/D/ignore", { diffNo: "D", resolution: "checked" });
  });
});
