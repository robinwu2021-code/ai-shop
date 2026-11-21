import { describe, expect, it, vi } from "vitest";
import { createPayOpsClient, requestNoFor } from "./_api/client";
import { createHttpPayCenterApi } from "./_api/api";
import { createMockPayCenterApi } from "./_api/mock";
import { prettyJson } from "./p9-panels";

type Reply = { status?: number; data?: unknown; message?: string };
function httpApi(...replies: Reply[]) {
  let i = 0;
  const f = vi.fn(async (_u: string, _i?: RequestInit) => {
    const r = replies[Math.min(i++, replies.length - 1)] ?? {};
    const status = r.status ?? 200;
    const body = status >= 400 ? { code: status, message: r.message ?? "x", requestId: "r" } : { code: 0, message: "OK", data: r.data ?? {}, requestId: "r" };
    return new Response(JSON.stringify(body), { status });
  });
  return { f, api: createHttpPayCenterApi(createPayOpsClient({ baseUrl: "/pay", fetcher: f as unknown as typeof fetch })) };
}
const call = (f: ReturnType<typeof vi.fn>, i = 0) => {
  const [url, init] = f.mock.calls[i] as [string, RequestInit];
  return { url, method: init.method, body: init.body ? JSON.parse(String(init.body)) : undefined };
};

describe("P9 · HTTP 映射（near-pay da3ced2a4 契约，TDD-支付中心运营页面 §2.15）", () => {
  it("渠道账户改坐标是 PUT；资质准备、撤销门店授权带幂等号", async () => {
    const { f, api } = httpApi({ data: {} });
    await api.channelAccounts.update("PM1", { expectedVersion: 2, channelMerchantId: "MID1", subMerchantId: null, accountMode: "MERCHANT_OWN", fundsPath: "DIRECT", parentPayMerchantNo: null, evidenceRef: null, reason: "改号" });
    await api.qualifications.prepare("PM1", { evidenceNo: "EV1", revision: 2, productCode: "NEWEBPAY_CARD_ONETIME", protocolEvidence: "p", trialEvidence: "sandbox-trial:x", enablementEvidence: "e", returnUrl: "https://a/r", notifyUrl: "https://a/pay/callback/newebpay/pay/PM1", validUntil: "2026-11-22T00:00:00.000Z", reason: "试运行" });
    await api.apps.revokeGrant("APP1", "S/1", { expectedVersion: 3, reason: "停用" });
    expect(call(f, 0)).toMatchObject({ url: "/pay/channel-accounts/PM1", method: "PUT", body: { expectedVersion: 2, channelMerchantId: "MID1", accountMode: "MERCHANT_OWN" } });
    expect(call(f, 1)).toMatchObject({ url: "/pay/channel-accounts/PM1/qualifications", method: "POST", body: { evidenceNo: "EV1", revision: 2 } });
    expect(call(f, 1).body.requestNo).toMatch(/^ops-/);
    expect(call(f, 2)).toMatchObject({ url: "/pay/apps/APP1/grants/S%2F1/revoke", body: { expectedVersion: 3 } });
  });

  it("终端凭证引用 / 退役、告警指标、储值交易详情是 GET；冲正回查不带请求体也不带幂等号", async () => {
    const { f, api } = httpApi({ data: {} });
    await api.terminals.credentialReference("T1");
    await api.terminals.retirement("T1");
    await api.alerts.metrics();
    await api.alerts.recheckReversal("AE1");
    await api.storedValue.transaction("SV1");
    expect(call(f, 0).url).toBe("/pay/terminals/T1/credential-reference");
    expect(call(f, 1).url).toBe("/pay/terminals/T1/retirement");
    expect(call(f, 2).url).toBe("/pay/alert-metrics");
    expect(call(f, 3)).toMatchObject({ url: "/pay/alert-events/AE1/recheck-reversal", method: "POST" });
    expect(call(f, 3).body?.requestNo).toBeUndefined();
    expect(call(f, 4).url).toBe("/pay/stored-value/transactions/SV1");
  });
});

describe("P9 · 认领处理超时后按幂等号取回执", () => {
  const body = { expectedVersion: 1, result: "LINKED" as const, payMerchantNo: "PM9", expectedAccountVersion: 2, reason: "核实" };
  it("回执成功：返回回执里的认领结果", async () => {
    const view = { claimNo: "CL1", state: "LINKED" };
    const { f, api } = httpApi({ status: 504 }, { data: { requestNo: "x", outcome: "SUCCEEDED", result: view, failure: null } });
    await expect(api.claims.resolve("CL1", body)).resolves.toEqual(view);
    expect(call(f, 1).url).toBe(`/pay/channel-account-claims/CL1/commands/${requestNoFor("/channel-account-claims/CL1/resolve", body)}`);
  });
  it("回执被拒：抛出回执里的原因", async () => {
    const { api } = httpApi({ status: 504 }, { data: { requestNo: "x", outcome: "REJECTED", result: null, failure: { code: 409, reasonCode: "STATE_CONFLICT", message: "账户已被认领" } } });
    await expect(api.claims.resolve("CL1", body)).rejects.toThrow("账户已被认领");
  });
});

describe("P9 · mock 状态机", () => {
  it("门店授权撤销要授权版本；已撤销再撤是幂等", async () => {
    const api = createMockPayCenterApi();
    const g = (await api.apps.grants("APP9001")).records[0];
    await expect(api.apps.revokeGrant("APP9001", g.storeNo, { expectedVersion: g.version + 5, reason: "x" })).rejects.toThrow();
    await api.apps.revokeGrant("APP9001", g.storeNo, { expectedVersion: g.version, reason: "停用" });
    await expect(api.apps.revokeGrant("APP9001", g.storeNo, { expectedVersion: 0, reason: "再撤" })).resolves.toMatchObject({ status: "REVOKED" });
  });

  it("冲正回查只对冲正待确认告警可用", async () => {
    const api = createMockPayCenterApi();
    await expect(api.alerts.recheckReversal("AE3001")).rejects.toThrow();
    expect((await api.alerts.metrics()).map((x) => x.metric)).toContain("REVERSAL_PENDING");
  });

  it("资质内容 JSON 美化；不是 JSON 就原样", () => {
    expect(prettyJson('{"a":1}')).toBe('{\n  "a": 1\n}');
    expect(prettyJson("x")).toBe("x");
    expect(prettyJson(null)).toBe("—");
  });
});
