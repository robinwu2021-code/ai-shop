import { describe, expect, it, vi } from "vitest";
import { createPayOpsClient } from "./_api/client";
import { createHttpPayCenterApi } from "./_api/api";
import { createMockPayCenterApi } from "./_api/mock";

function httpApi(data: unknown = {}) {
  const f = vi.fn(async (_u: string, _i?: RequestInit) => new Response(JSON.stringify({ code: 0, message: "OK", data, requestId: "r" }), { status: 200 }));
  return { f, api: createHttpPayCenterApi(createPayOpsClient({ baseUrl: "/pay", fetcher: f as unknown as typeof fetch })) };
}
const call = (f: ReturnType<typeof vi.fn>, i = 0) => {
  const [url, init] = f.mock.calls[i] as [string, RequestInit];
  return { url, method: init.method, body: init.body ? JSON.parse(String(init.body)) : undefined };
};

describe("P10 · 凭证管理 HTTP 映射（near-pay da3ced2a4，TDD-支付中心运营页面 §2.15）", () => {
  it("目录、策略、列表、详情、访问记录、使用情况；策略保存是 PUT", async () => {
    const { f, api } = httpApi({ records: [], total: 0 });
    await api.credentials.types();
    await api.credentials.policies();
    await api.credentials.savePolicy("PAY_MERCHANT", { expectedVersion: 0, maxAgeDays: 365, warnBeforeDays: 30, overlapDays: 7, verifyBeforeEnable: true, reason: "调整" });
    await api.credentials.list({ page: 1, size: 20, ownerType: "PAY_MERCHANT" });
    await api.credentials.detail(4101);
    await api.credentials.accessLogs(4101);
    await api.credentials.bindings(30);
    expect(call(f, 0).url).toBe("/pay/credential-types");
    expect(call(f, 2)).toMatchObject({ url: "/pay/credential-policies/PAY_MERCHANT", method: "PUT", body: { expectedVersion: 0, maxAgeDays: 365 } });
    expect(call(f, 3).url).toBe("/pay/credentials?page=1&size=20&ownerType=PAY_MERCHANT");
    expect(call(f, 4).url).toBe("/pay/credentials/4101");
    expect(call(f, 5).url).toBe("/pay/credentials/4101/access-logs");
    expect(call(f, 6).url).toBe("/pay/credential-bindings?days=30");
  });

  it("★ 录入版本与生成不带自动幂等号（请求体里有密钥材料 / 生成结果只给一次）", async () => {
    const { f, api } = httpApi({});
    await api.credentials.addVersion(4101, { hashKey: "K".repeat(32), hashIv: "I".repeat(16), reason: "轮换" });
    await api.credentials.generate(4102, "轮换");
    expect(call(f, 0)).toMatchObject({ url: "/pay/credentials/4101/versions", method: "POST" });
    expect(call(f, 0).body.requestNo).toBeUndefined();
    expect(call(f, 1)).toMatchObject({ url: "/pay/credentials/4102/versions/generate", body: { reason: "轮换" } });
    expect(call(f, 1).body.requestNo).toBeUndefined();
  });

  it("核验、启用、撤销走版本路径", async () => {
    const { f, api } = httpApi({});
    await api.credentials.verify(4101, 3, { evidenceRef: "OPS-9", reason: "联调通过" });
    await api.credentials.enable(4101, 3, { approvalRef: "AP1", reason: "上线" });
    await api.credentials.revoke(4101, 2, "旧版下线");
    expect(call(f, 0)).toMatchObject({ url: "/pay/credentials/4101/versions/3/verify", body: { evidenceRef: "OPS-9" } });
    expect(call(f, 1)).toMatchObject({ url: "/pay/credentials/4101/versions/3/enable", body: { approvalRef: "AP1" } });
    expect(call(f, 2)).toMatchObject({ url: "/pay/credentials/4101/versions/2/revoke", body: { reason: "旧版下线" } });
  });

  it("审计事件：列表按资源编号筛、详情", async () => {
    const { f, api } = httpApi({ records: [], total: 0 });
    await api.audits.list({ page: 1, size: 20, resourceNo: "PM1" });
    await api.audits.detail("AU1");
    expect(call(f, 0).url).toBe("/pay/audit-events?page=1&size=20&resourceNo=PM1");
    expect(call(f, 1).url).toBe("/pay/audit-events/AU1");
  });
});

describe("P10 · mock 状态机", () => {
  it("版本：草稿 → 核验 → 启用；录入人不能自己启用；列表从不带明文", async () => {
    const api = createMockPayCenterApi();
    const page = await api.credentials.list({ page: 1, size: 20 });
    expect(JSON.stringify(page)).not.toMatch(/sk_test_mock/);
    await api.credentials.addVersion(4101, { hashKey: "K".repeat(32), hashIv: "I".repeat(16), reason: "轮换" });
    const d = await api.credentials.detail(4101);
    const draft = d.versions.find((v) => v.status === "DRAFT")!;
    await expect(api.credentials.enable(4101, draft.versionIndex, { approvalRef: null, reason: "跳过核验" })).rejects.toThrow();
    await api.credentials.verify(4101, draft.versionIndex, { evidenceRef: "OPS-1", reason: "核验" });
    await expect(api.credentials.enable(4101, draft.versionIndex, { approvalRef: "AP1", reason: "自己启用" })).rejects.toThrow();
  });

  it("生成：明文只在生成的响应里", async () => {
    const api = createMockPayCenterApi();
    const issued = await api.credentials.generate(4102, "轮换");
    expect(issued.secret).toMatch(/^sk_test_/);
    expect(JSON.stringify(await api.credentials.detail(4102))).not.toContain(issued.secret);
  });
});
