import { describe, expect, it, vi } from "vitest";
import { createPayOpsClient } from "./client";
import { createHttpPayCenterApi } from "./api";
import { createMockPayCenterApi } from "./mock";

function httpApi(data: unknown = { records: [], total: 0 }) {
  const f = vi.fn(async (_u: string, _i?: RequestInit) => new Response(JSON.stringify({ code: 0, message: "OK", data, requestId: "r" }), { status: 200 }));
  return { f, api: createHttpPayCenterApi(createPayOpsClient({ baseUrl: "/pay", fetcher: f as unknown as typeof fetch })) };
}
const call = (f: ReturnType<typeof vi.fn>, i = 0) => {
  const [url, init] = f.mock.calls[i] as [string, RequestInit];
  return { url, method: init.method, body: init.body ? JSON.parse(String(init.body)) : undefined };
};

describe("HTTP 映射与 near-pay /pay-ops/v2 一致", () => {
  it("列表走 page/size 与筛选参数，解 {code,data} 信封", async () => {
    const { f, api } = httpApi({ records: [{ payOrderNo: "P1" }], total: 1, page: 1, size: 20 });
    const page = await api.orders.list({ page: 1, size: 20, status: "SUCCEEDED", needsAttention: true });
    expect(page.total).toBe(1);
    expect(call(f).url).toBe("/pay/orders?page=1&size=20&status=SUCCEEDED&needsAttention=true");
  });

  it("订单与支付异常的命令不带幂等号与版本", async () => {
    const { f, api } = httpApi(null);
    await api.orders.dismiss("P1", "重复下单处理");
    await api.exceptions.ignore(9, "已线下退回");
    expect(call(f, 0)).toEqual({ url: "/pay/orders/P1/dismiss", method: "POST", body: { reason: "重复下单处理" } });
    expect(call(f, 1)).toEqual({ url: "/pay/payment-exceptions/9/ignore", method: "POST", body: { reason: "已线下退回" } });
  });

  it("状态命令带 requestNo + expectedVersion + reason；更新走 PUT；路径参数编码", async () => {
    const { f, api } = httpApi({ resourceNo: "C1", status: "DISABLED", version: 4 });
    await api.channels.disable("C 1", { expectedVersion: 3, reason: "维护" });
    await api.stores.savePolicy("S1", { expectedVersion: 2, mode: "OVERRIDE", productCodes: ["A"], reason: "x" });
    const a = call(f, 0);
    expect(a.url).toBe("/pay/channels/C%201/disable");
    expect(a.body).toMatchObject({ expectedVersion: 3, reason: "维护" });
    expect(a.body.requestNo).toMatch(/^ops-/);
    expect(call(f, 1)).toMatchObject({ url: "/pay/stores/S1/collection-policy", method: "PUT" });
  });

  it("审批详情不按 Detail 包装解；其它详情补齐 version/allowedActions/timeline", async () => {
    const approval = { record: { approvalNo: "A1", version: 2 }, subjectTitle: "t", tasks: [], decisions: [], timeline: [], allowedActions: ["claim"] };
    const a = httpApi(approval);
    expect(await a.api.approvals.detail("A1")).toEqual(approval);
    const s = httpApi({ record: { scopeNo: "D1" } });
    expect(await s.api.dataScopes.detail("D1")).toEqual({ record: { scopeNo: "D1" }, version: 0, allowedActions: [], timeline: [] });
  });

  it("任务类命令走 /approval-tasks/{taskNo}", async () => {
    const { f, api } = httpApi({});
    await api.approvals.claim("T1", { expectedVersion: 1, reason: "认领" });
    await api.approvals.assign("T1", { expectedVersion: 2, reason: "x", assigneeNo: "u2" });
    expect(call(f, 0).url).toBe("/pay/approval-tasks/T1/claim");
    expect(call(f, 1).body).toMatchObject({ assigneeNo: "u2", expectedVersion: 2 });
  });
});

describe("mock 与 near-pay 同口径", () => {
  it("版本不对 409、状态不允许拒绝、成功版本 +1", async () => {
    const api = createMockPayCenterApi();
    const ch = (await api.channels.list({ page: 1, size: 20 })).records.find((c) => c.status === "ENABLED")!;
    await expect(api.channels.disable(ch.channelCode, { expectedVersion: ch.version + 5, reason: "x" })).rejects.toMatchObject({ reasonCode: "VERSION_CONFLICT" });
    const r = await api.channels.disable(ch.channelCode, { expectedVersion: ch.version, reason: "维护" });
    expect(r.version).toBe(ch.version + 1);
    await expect(api.channels.disable(ch.channelCode, { expectedVersion: r.version, reason: "再停" })).rejects.toMatchObject({ reasonCode: "STATE_CONFLICT" });
  });

  it("原因必填；新建版本必须为 0", async () => {
    const api = createMockPayCenterApi();
    const m = (await api.merchants.list({ page: 1, size: 20 })).records.find((x) => x.status === "ACTIVE")!;
    await expect(api.merchants.suspend(m.merchantNo, { expectedVersion: m.version, reason: " " })).rejects.toBeTruthy();
    await expect(api.dataScopes.create({ expectedVersion: 1, scopeType: "USER", subjectNo: "u", marketSet: ["TW"], entityNos: ["*"], storeNos: [], fundsAccess: "NONE", expiresAt: Date.now() + 1e9, reason: "x" })).rejects.toBeTruthy();
  });
});
