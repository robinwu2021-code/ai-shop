import { expect, it, vi } from "vitest";
vi.mock("../http-client", () => ({ client: { post: vi.fn(), get: vi.fn() } }));
import { client } from "../http-client";
import { payOpsHttp } from "./payops";

it("按原业务编号恢复退款，不发送新金额或恢复订单指令", async () => {
  vi.mocked(client.post).mockResolvedValue({ status: "REFUNDING", refundErrorCode: "REVERSE_UNCONFIRMED" });
  const result = await payOpsHttp.refundOrderPaymentResolution("O /1", "P/2");
  expect(client.post).toHaveBeenCalledWith("/ops/order-payment-resolutions/O%20%2F1/P%2F2/refund");
  expect(result.status).toBe("REFUNDING");
});
it("人工队列按真实状态查询", async () => {
  await payOpsHttp.orderPaymentResolutions("REFUNDING");
  expect(client.get).toHaveBeenCalledWith("/ops/order-payment-resolutions", { status: "REFUNDING" });
});
