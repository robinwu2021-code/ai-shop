import { describe, expect, it, vi } from "vitest";
import { createPayOpsClient } from "./_api/client";
import { createHttpPayCenterApi } from "./_api/api";
import { createMockPayCenterApi } from "./_api/mock";
import { isPaybyTestEntry, isSandboxEntry, trialChannelOf } from "./p11-panels";

function httpApi(data: unknown = {}) {
  const f = vi.fn(async (_u: string, _i?: RequestInit) => new Response(JSON.stringify({ code: 0, message: "OK", data, requestId: "r" }), { status: 200 }));
  return { f, api: createHttpPayCenterApi(createPayOpsClient({ baseUrl: "/pay", fetcher: f as unknown as typeof fetch })) };
}
const call = (f: ReturnType<typeof vi.fn>, i = 0) => {
  const [url, init] = f.mock.calls[i] as [string, RequestInit];
  return { url, method: init.method, body: init.body ? JSON.parse(String(init.body)) : undefined };
};
const settings = { environment: "SANDBOX" as const, allowedModes: ["PLATFORM_PROXY" as const], defaultMode: "PLATFORM_PROXY" as const, protocolVersion: "1", contractVersion: "c1", readerKitVersion: "r1", firmwareVersion: "f1", queryContractRef: "file:q", certificationRefs: { PLATFORM_PROXY: "file:cert" } };
const binding = { issuerCode: "I", issuerType: "T", channelMerchantNo: "M", channelStoreNo: "S", deviceId: "D", channelTerminalNo: "TID", payeeMemberId: null, deviceKeyRef: "vault:pos/dev", deviceKeyVersion: "1", queryCredentialRef: "vault:pos/q", queryKeyVersion: "1", activationRef: "file:act", authorizationRef: "file:auth" };

describe("P11 · 测试渠道与测试单 HTTP 映射（near-pay da3ced2a4）", () => {
  it("试运行：准备信息是 GET、建草稿带账户版本；测试单分沙箱与 PayBy 两条路径", async () => {
    const { f, api } = httpApi({});
    await api.trials.preparation("PM1", "linepay");
    await api.trials.create("PM1", "newebpay", { expectedVersion: 7, evidenceNo: "EV1", revision: 2, productCode: "NEWEBPAY_TEST_CARD_ONETIME", entry: "H5", protocolEvidence: "p", returnUrl: "https://a/r", notifyUrl: "https://a/pay/callback/newebpay/pay/PM1", validUntil: "2026-11-22T00:00:00.000Z", reason: "续期" });
    await api.testSessions.sandbox("CE1", { expiresAt: 1 });
    await api.testSessions.payby("CE2", { expiresAt: 1 });
    expect(call(f, 0)).toMatchObject({ url: "/pay/channel-accounts/PM1/linepay-trial", method: "GET" });
    expect(call(f, 1)).toMatchObject({ url: "/pay/channel-accounts/PM1/newebpay-trial", method: "POST", body: { expectedVersion: 7, entry: "H5" } });
    expect(call(f, 1).body.requestNo).toMatch(/^ops-/);
    expect(call(f, 2).url).toBe("/pay/collection-entries/CE1/sessions");
    expect(call(f, 3).url).toBe("/pay/collection-entries/CE2/payby-test-sessions");
  });

  it("入口与渠道识别：只对测试渠道给续期；测试入口与 PayBy 测试入口互斥", () => {
    expect(trialChannelOf("NEWEBPAY_TEST")).toBe("newebpay");
    expect(trialChannelOf("LINEPAY_TEST")).toBe("linepay");
    expect(trialChannelOf("NEWEBPAY")).toBeNull();
    expect(isSandboxEntry(["NEWEBPAY_TEST_CARD_ONETIME"])).toBe(true);
    expect(isSandboxEntry(["NEWEBPAY_CARD_ONETIME", "NEWEBPAY_TEST_CARD_ONETIME"])).toBe(false);
    expect(isPaybyTestEntry(["PAYBY_SGS_H5_TEST"])).toBe(true);
    expect(isSandboxEntry(["PAYBY_SGS_H5_TEST"])).toBe(false);
  });
});

describe("P11 · PayBy POS 与 SoftPOS HTTP 映射", () => {
  it("POS：草稿保存带终端版本与配置版本；核验、发布、停用、验收各自路径", async () => {
    const { f, api } = httpApi({});
    await api.pos.list("T1");
    await api.pos.save("T1", { expectedTerminalVersion: 4, configurationId: null, expectedVersion: 0, reason: "新建", settings, binding });
    await api.pos.verify("T1", 9, { expectedVersion: 1, evidenceRef: "file:rev", reason: "核验" });
    await api.pos.publish("T1", 9, { expectedVersion: 2, reason: "发布" });
    await api.posUat.start("T1", 9, { expectedVersion: 3, mode: "PLATFORM_PROXY", reason: "验收" });
    await api.posUat.recordCase("T1", "POS-UAT-1", { expectedVersion: 1, caseCode: "D01", result: "PASS", evidenceRef: "file:d01", executionNo: null, reason: "记录" });
    await api.posUat.review("T1", "POS-UAT-1", { expectedVersion: 2, evidenceRef: "file:sign", reason: "签核" });
    await api.posUat.manifest("T1", "POS-UAT-1");
    expect(call(f, 0)).toMatchObject({ url: "/pay/terminals/T1/payby-pos", method: "GET" });
    expect(call(f, 1)).toMatchObject({ url: "/pay/terminals/T1/payby-pos", method: "POST", body: { expectedTerminalVersion: 4, configurationId: null, expectedVersion: 0 } });
    expect(call(f, 2).url).toBe("/pay/terminals/T1/payby-pos/9/verify");
    expect(call(f, 3).url).toBe("/pay/terminals/T1/payby-pos/9/publish");
    expect(call(f, 4).url).toBe("/pay/terminals/T1/payby-pos/9/acceptance-runs");
    expect(call(f, 5)).toMatchObject({ url: "/pay/terminals/T1/payby-pos/acceptance-runs/POS-UAT-1/cases", body: { caseCode: "D01", result: "PASS" } });
    expect(call(f, 6).url).toBe("/pay/terminals/T1/payby-pos/acceptance-runs/POS-UAT-1/review");
    expect(call(f, 7).url).toBe("/pay/terminals/T1/payby-pos/acceptance-runs/POS-UAT-1/manifest");
  });

  it("SoftPOS：登记与撤销不带幂等号（near-pay 不收），查看按绑定号", async () => {
    const { f, api } = httpApi({});
    await api.softpos.register("T2", { packageName: "com.v.app", signatureSha256: "a".repeat(64), appVersion: "1.0", sdkVersion: "2.1", validUntil: "2027-01-01T00:00:00.000Z", reason: "登记" });
    await api.softpos.detail("T2", 77);
    await api.softpos.revoke("T2", 77, "换机");
    expect(call(f, 0)).toMatchObject({ url: "/pay/terminals/T2/softpos-bindings", method: "POST" });
    expect(call(f, 0).body.requestNo).toBeUndefined();
    expect(call(f, 1).url).toBe("/pay/terminals/T2/softpos-bindings/77");
    expect(call(f, 2)).toMatchObject({ url: "/pay/terminals/T2/softpos-bindings/77/revoke", body: { reason: "换机" } });
    expect(call(f, 2).body.requestNo).toBeUndefined();
  });
});

describe("P11 · mock 状态机（与 near-pay 同口径）", () => {
  it("POS：只有 PayBy 终端能建执行配置", async () => {
    const api = createMockPayCenterApi();
    const t = (await api.terminals.list({ page: 1, size: 20 })).records[0];
    await expect(api.pos.save(t.terminalNo, { expectedTerminalVersion: t.version, configurationId: null, expectedVersion: 0, reason: "新建", settings, binding })).rejects.toThrow(/PayBy/);
  });

  it("测试单有效期最长 30 分钟", async () => {
    const api = createMockPayCenterApi();
    await expect(api.testSessions.sandbox("CE1", { expiresAt: Date.now() + 31 * 60_000 })).rejects.toThrow();
    await expect(api.testSessions.sandbox("CE1", { expiresAt: Date.now() + 15 * 60_000 })).resolves.toMatchObject({ checkoutUrl: expect.stringContaining("https://") });
  });
});
