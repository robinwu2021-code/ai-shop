import { describe, expect, it, vi } from "vitest";
import { createPayOpsClient, requestNoFor } from "./_api/client";
import { createHttpPayCenterApi } from "./_api/api";
import { createMockPayCenterApi } from "./_api/mock";
import { summaryText } from "./merchant-p8";

type Reply = { status?: number; data?: unknown; code?: number; reasonCode?: string; message?: string };
function httpApi(...replies: Reply[]) {
  let i = 0;
  const f = vi.fn(async (_u: string, _i?: RequestInit) => {
    const r = replies[Math.min(i++, replies.length - 1)] ?? {};
    const status = r.status ?? 200;
    const body = status >= 400 ? { code: r.code ?? status, reasonCode: r.reasonCode, message: r.message ?? "x", requestId: "r" } : { code: 0, message: "OK", data: r.data ?? {}, requestId: "r" };
    return new Response(JSON.stringify(body), { status });
  });
  return { f, api: createHttpPayCenterApi(createPayOpsClient({ baseUrl: "/pay", fetcher: f as unknown as typeof fetch })) };
}
const call = (f: ReturnType<typeof vi.fn>, i = 0) => {
  const [url, init] = f.mock.calls[i] as [string, RequestInit];
  return { url, method: init.method, body: init.body ? JSON.parse(String(init.body)) : undefined };
};

describe("P8 · HTTP 映射（near-pay da3ced2a4 契约，TDD-支付中心运营页面 §2.15）", () => {
  it("商户成员：列表 / 详情 / 补建管理员 / 状态命令都带幂等号与版本", async () => {
    const { f, api } = httpApi({ data: { records: [], total: 0 } });
    await api.merchantMembers.list({ page: 1, size: 20, status: "ACTIVE" });
    await api.merchantMembers.detail("MM/1");
    await api.merchantMembers.create({ merchantNo: "PM1", subjectType: "MCH_ACCOUNT", subjectRef: "MA1", reason: "补管理员" });
    await api.merchantMembers.command("MM1", "suspend", { expectedVersion: 3, reason: "离职" });
    expect(call(f, 0).url).toBe("/pay/merchant-members?page=1&size=20&status=ACTIVE");
    expect(call(f, 1).url).toBe("/pay/merchant-members/MM%2F1");
    expect(call(f, 2)).toMatchObject({ url: "/pay/merchant-members", method: "POST", body: { merchantNo: "PM1", subjectType: "MCH_ACCOUNT", subjectRef: "MA1", reason: "补管理员" } });
    expect(call(f, 2).body.requestNo).toMatch(/^ops-/);
    expect(call(f, 3)).toMatchObject({ url: "/pay/merchant-members/MM1/suspend", body: { expectedVersion: 3, reason: "离职" } });
  });

  it("来源关联修复：建单、审批、执行走各自路径", async () => {
    const { f, api } = httpApi({ data: {} });
    await api.repairs.list({ page: 1, size: 20 });
    await api.repairs.create({ merchantNo: "PM1", fromOperatingEntityId: "E1", toOperatingEntityId: "E2", storeNos: ["S1"], evidence: "工单", expectedVersion: 4 });
    await api.repairs.decide("SR1", "approve", { expectedVersion: 1, reason: "核对无误" });
    await api.repairs.decide("SR1", "execute", { expectedVersion: 2, reason: "执行" });
    expect(call(f, 0).url).toBe("/pay/source-association-repair-requests?page=1&size=20");
    expect(call(f, 1)).toMatchObject({ url: "/pay/source-association-repair-requests", body: { merchantNo: "PM1", storeNos: ["S1"], expectedVersion: 4 } });
    expect(call(f, 2).url).toBe("/pay/source-association-repair-requests/SR1/approve");
    expect(call(f, 3)).toMatchObject({ url: "/pay/source-association-repair-requests/SR1/execute", body: { expectedVersion: 2 } });
  });

  it("资料版本、工作台、换绑执行、草稿命令查询", async () => {
    const { f, api } = httpApi({ data: [] });
    await api.merchants.revisions("PM1", { reviewStatus: "SUBMITTED" });
    await api.merchants.workspace("PM1");
    await api.storeBindings.rebind("S1", "SB1", { expectedVersion: 2, reason: "审批已通过" });
    await api.onboardings.draftCommand("OB1", "ops-x");
    expect(call(f, 0).url).toBe("/pay/merchants/PM1/profile-revisions?reviewStatus=SUBMITTED");
    expect(call(f, 1).url).toBe("/pay/merchants/PM1/workspace");
    expect(call(f, 2)).toMatchObject({ url: "/pay/stores/S1/bindings/SB1/rebind", method: "POST", body: { expectedVersion: 2 } });
    expect(call(f, 3).url).toBe("/pay/onboardings/OB1/draft/commands/ops-x");
  });
});

describe("P8 · 写命令超时后按同一个幂等号取回执", () => {
  it("建商户 5xx：用同一个 requestNo 查回执，查到就当成功", async () => {
    const created = { merchantNo: "PM9", operatingEntityId: "E9", name: "新店", market: "TW", status: "ACTIVE" };
    const { f, api } = httpApi({ status: 503 }, { data: created });
    await expect(api.merchants.create({ operatingEntityId: "E9", name: "新店" })).resolves.toEqual(created);
    const sent = call(f, 0).body.requestNo as string;
    expect(sent).toBe(requestNoFor("/merchant-creations", { operatingEntityId: "E9", name: "新店" }));
    expect(call(f, 1)).toMatchObject({ url: `/pay/merchant-creations/by-request/${sent}`, method: "GET" });
  });

  it("业务错误（409）不查回执，原样抛出", async () => {
    const { f, api } = httpApi({ status: 409, reasonCode: "STATE_CONFLICT", message: "冲突" });
    await expect(api.merchants.create({ operatingEntityId: "E9", name: "新店" })).rejects.toThrow("冲突");
    expect(f).toHaveBeenCalledTimes(1);
  });

  it("进件草稿保存 5xx：命令已生效就回最新草稿；未生效原样抛出", async () => {
    const draft = { onboardingNo: "OB1", expectedVersion: 1, draftVersion: 5, state: "EDITING" };
    const ok = httpApi({ status: 502 }, { data: { requestNo: "x", applied: true } }, { data: draft });
    await expect(ok.api.onboardings.saveDraft("OB1", { expectedVersion: 1, expectedDraftVersion: 4, fields: { a: "1" } })).resolves.toMatchObject({ draftVersion: 5 });
    expect(call(ok.f, 1).url).toMatch(/^\/pay\/onboardings\/OB1\/draft\/commands\/ops-/);
    expect(call(ok.f, 2).url).toBe("/pay/onboardings/OB1/draft");
    const lost = httpApi({ status: 502, message: "网关超时" }, { data: { requestNo: "x", applied: false } });
    await expect(lost.api.onboardings.saveDraft("OB1", { expectedVersion: 1, expectedDraftVersion: 4, fields: {} })).rejects.toThrow("网关超时");
  });
});

describe("P8 · mock 状态机", () => {
  it("商户成员：已有生效管理员不能再补建；最后一位生效管理员不能撤销", async () => {
    const api = createMockPayCenterApi();
    await expect(api.merchantMembers.create({ merchantNo: "PM1001", subjectType: "USER", subjectRef: "U1", reason: "补" })).rejects.toThrow();
    const admin = await api.merchantMembers.detail("MM9001");
    await expect(api.merchantMembers.command("MM9001", "revoke", { expectedVersion: admin.version, reason: "撤" })).rejects.toThrow();
    const cashier = await api.merchantMembers.detail("MM9002");
    await api.merchantMembers.command("MM9002", "suspend", { expectedVersion: cashier.version, reason: "停" });
    expect((await api.merchantMembers.detail("MM9002")).record.status).toBe("SUSPENDED");
  });

  it("关联修复：审批要审批版本；执行只在已批准后", async () => {
    const api = createMockPayCenterApi();
    const d = await api.repairs.detail("SR7001");
    await expect(api.repairs.decide("SR7001", "execute", { expectedVersion: d.version, reason: "先执行" })).rejects.toThrow();
    await expect(api.repairs.decide("SR7001", "approve", { expectedVersion: 99, reason: "版本不对" })).rejects.toThrow();
    await api.repairs.decide("SR7001", "approve", { expectedVersion: d.record.approvalVersion!, reason: "通过" });
    const approved = await api.repairs.detail("SR7001");
    expect(approved.allowedActions).toEqual(["execute"]);
    await api.repairs.decide("SR7001", "execute", { expectedVersion: approved.version, reason: "执行" });
    expect((await api.repairs.detail("SR7001")).record.status).toBe("EXECUTED");
  });

  it("工作台摘要把 near-pay 的 JSON 字符串展开成短句", () => {
    expect(summaryText('{"byStatus":{"ACTIVE":1}}')).toBe('byStatus {"ACTIVE":1}');
    expect(summaryText(null)).toBe("—");
    expect(summaryText("not-json")).toBe("not-json");
  });
});
