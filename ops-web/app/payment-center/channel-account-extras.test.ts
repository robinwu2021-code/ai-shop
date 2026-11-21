import { describe, expect, it, vi } from "vitest";
import { createPayOpsClient } from "./_api/client";
import { createHttpPayCenterApi } from "./_api/api";
import { createMockPayCenterApi } from "./_api/mock";
import { sourceEventSummary, sourceEventTitle } from "./merchant-workspace";

function httpApi(data: unknown = { records: [], total: 0 }) {
  const f = vi.fn(async (_u: string, _i?: RequestInit) => new Response(JSON.stringify({ code: 0, message: "OK", data, requestId: "r" }), { status: 200 }));
  return { f, api: createHttpPayCenterApi(createPayOpsClient({ baseUrl: "/pay", fetcher: f as unknown as typeof fetch })) };
}
const call = (f: ReturnType<typeof vi.fn>, i = 0) => {
  const [url, init] = f.mock.calls[i] as [string, RequestInit];
  return { url, method: init.method, body: init.body ? JSON.parse(String(init.body)) : undefined };
};

describe("P6 · 渠道账户转移与资质的 HTTP 映射（near-pay /pay-ops/v2，TDD-支付中心运营页面 §2.12）", () => {
  it("转移：列表按账户筛，发起 / 审核 / 驳回 / 执行都带幂等号、版本与理由", async () => {
    const { f, api } = httpApi({ resourceNo: "CT1", status: "APPROVED", version: 2 });
    await api.transfers.list({ payMerchantNo: "PM1", page: 1, size: 50 });
    await api.transfers.create({ payMerchantNo: "PM1", fromMerchantNo: "M1", toMerchantNo: "M2", evidence: "合同 A-1", expectedVersion: 3 });
    await api.transfers.approve("CT 1", { expectedVersion: 1, reason: "核对无误" });
    await api.transfers.reject("CT1", { expectedVersion: 1, reason: "证明不足" });
    await api.transfers.execute("CT1", { expectedVersion: 2, reason: "执行" });
    expect(call(f, 0).url).toBe("/pay/channel-account-transfers?payMerchantNo=PM1&page=1&size=50");
    expect(call(f, 1)).toMatchObject({ url: "/pay/channel-account-transfers", method: "POST", body: { payMerchantNo: "PM1", fromMerchantNo: "M1", toMerchantNo: "M2", expectedVersion: 3 } });
    expect(call(f, 1).body.requestNo).toMatch(/^ops-/);
    expect(call(f, 2)).toMatchObject({ url: "/pay/channel-account-transfers/CT%201/approve", body: { expectedVersion: 1, reason: "核对无误" } });
    expect(call(f, 3).url).toBe("/pay/channel-account-transfers/CT1/reject");
    expect(call(f, 4)).toMatchObject({ url: "/pay/channel-account-transfers/CT1/execute", body: { expectedVersion: 2 } });
  });

  it("资质：按账户列版本、详情按 ref（含冒号）编码、导入与吊销", async () => {
    const { f, api } = httpApi([]);
    await api.qualifications.ofAccount("PM1");
    await api.qualifications.detail("EV1:2");
    await api.qualifications.importVersion({ evidenceNo: "EV1", revision: 2, channel: "NEWEBPAY", purpose: "PRODUCTION", payloadJson: "{}", validUntil: "2027-01-01T00:00:00.000Z", reason: "导入" });
    await api.qualifications.revoke("EV1:2", "证据失效");
    expect(call(f, 0)).toMatchObject({ url: "/pay/channel-accounts/PM1/qualifications", method: "GET" });
    expect(call(f, 1).url).toBe("/pay/channel-qualifications/EV1%3A2");
    expect(call(f, 2)).toMatchObject({ url: "/pay/channel-qualifications", method: "POST", body: { evidenceNo: "EV1", revision: 2, payloadJson: "{}" } });
    expect(call(f, 3)).toMatchObject({ url: "/pay/channel-qualifications/EV1%3A2/revoke", body: { reason: "证据失效" } });
  });
});

describe("P6 · 转移与资质的 mock 状态机", () => {
  it("转移：待审核 → 通过 → 执行；进行中不能再发起", async () => {
    const api = createMockPayCenterApi();
    const created = await api.transfers.create({ payMerchantNo: "PMX", fromMerchantNo: "M1", toMerchantNo: "M2", evidence: "合同", expectedVersion: 1 });
    expect(created.allowedActions).toEqual(["approve", "reject"]);
    await expect(api.transfers.create({ payMerchantNo: "PMX", fromMerchantNo: "M1", toMerchantNo: "M3", evidence: "合同", expectedVersion: 1 })).rejects.toThrow();
    const no = created.record.transferNo;
    await api.transfers.approve(no, { expectedVersion: 1, reason: "ok" });
    expect((await api.transfers.detail(no)).allowedActions).toEqual(["execute"]);
    await api.transfers.execute(no, { expectedVersion: 2, reason: "go" });
    expect((await api.transfers.detail(no)).record.status).toBe("EXECUTED");
  });

  it("资质：内容须为 JSON；导入为草稿，吊销后不能再吊销", async () => {
    const api = createMockPayCenterApi();
    const base = { evidenceNo: "EV9", revision: 1, channel: "NEWEBPAY", purpose: "PRODUCTION", validUntil: "2027-01-01T00:00:00.000Z", reason: "导入" };
    await expect(api.qualifications.importVersion({ ...base, payloadJson: "not json" })).rejects.toThrow();
    const v = await api.qualifications.importVersion({ ...base, payloadJson: JSON.stringify({ coordinates: { payMerchantNo: "PMQ" } }) });
    expect(v.status).toBe("DRAFT");
    expect((await api.qualifications.ofAccount("PMQ")).map((x) => x.ref)).toEqual(["EV9:1"]);
    await api.qualifications.revoke("EV9:1", "失效");
    await expect(api.qualifications.revoke("EV9:1", "再吊销")).rejects.toThrow();
  });
});

describe("P6 · 商户来源事件的显示（near-pay 实际字段）", () => {
  const m = { eventOutcome_APPLIED: "已生效", eventBy: "操作人 {who}" };
  it("事件 = 来源系统 · 能力；摘要 = 结果 · 操作人", () => {
    const r = { sourceSystem: "AI_SHOP", capabilityType: "FIAT", outcome: "APPLIED", actorSubject: "ST-NEARA" };
    expect(sourceEventTitle(r)).toBe("AI_SHOP · FIAT");
    expect(sourceEventSummary(r, m)).toBe("已生效 · 操作人 ST-NEARA");
  });
  it("后端给了 summary 就用它；什么都没有时是 —；不认识的结果原样显示", () => {
    expect(sourceEventSummary({ summary: "手工补录" }, m)).toBe("手工补录");
    expect(sourceEventSummary({}, m)).toBe("—");
    expect(sourceEventSummary({ outcome: "SKIPPED" }, m)).toBe("SKIPPED");
    expect(sourceEventTitle({ eventType: "LEGACY" })).toBe("LEGACY");
  });
});
