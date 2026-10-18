import { describe, expect, it, vi } from "vitest";

const post = vi.hoisted(() => vi.fn(async () => ({ state: "UNAVAILABLE" })));
vi.mock("@shared/net/http-client", () => ({
  clientTag: () => "MP",
  http: { post, get: vi.fn(), put: vi.fn() },
}));

import { httpApi } from "@/api/http";

describe("门店积分报价传输契约", () => {
  it("报价和下单都带同一端标识，服务端才能按同一端策略裁决", async () => {
    const req = { items: [{ goodsNo: "G", skuNo: "S", qty: 1 }],
      fulfillment: "STORE_PICKUP" as const, usePoints: 20 };
    await httpApi.orderPointsQuote(req);
    expect(post).toHaveBeenLastCalledWith("/mp/order/points-quote", req, { "X-Client": "MP" });
    await httpApi.createOrder({ ...req, idempotencyKey: "idem" });
    expect(post).toHaveBeenLastCalledWith("/mp/order", expect.objectContaining({ usePoints: 20 }),
      { "X-Client": "MP" });
  });
});
