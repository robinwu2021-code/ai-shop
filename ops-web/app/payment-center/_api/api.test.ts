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

describe("P2 映射", () => {
  it("退款走 shop 后端 /ops/payments/refunds（near-pay v1 对公网不可达）：列表与复核原样转给退款端口", async () => {
    const v2 = createPayOpsClient({ baseUrl: "/pay/v2", fetcher: vi.fn() as unknown as typeof fetch });
    const port = { list: vi.fn(async () => [{ payRefundNo: "R1" }]), review: vi.fn(async () => ({ payRefundNo: "R1" })) };
    const api = createHttpPayCenterApi(v2, { refunds: port as never });
    expect(await api.refunds.list({ status: "PENDING_REVIEW" })).toEqual([{ payRefundNo: "R1" }]);
    expect(port.list).toHaveBeenCalledWith({ status: "PENDING_REVIEW", limit: 200 });
    await api.refunds.review("R1", { approve: true, reason: "核对无误" });
    expect(port.review).toHaveBeenCalledWith("R1", { approve: true, reason: "核对无误" });
  });

  it("签发密钥、生成激活码、挂失不带幂等号；商户变更固定 profileStatus=SUBMITTED", async () => {
    const { f, api } = httpApi({ records: [], total: 0 });
    await api.apps.issueCredential("A1", { credentialType: "APP_SECRET", reason: "x" });
    await api.terminals.activationCode("T1");
    await api.terminals.lost("T1");
    await api.merchantChanges.list({ page: 1, size: 20 });
    expect(call(f, 0)).toEqual({ url: "/pay/apps/A1/credentials", method: "POST", body: { credentialType: "APP_SECRET", reason: "x" } });
    expect(call(f, 1).body).toEqual({});
    expect(call(f, 2).url).toBe("/pay/terminals/T1/lost");
    expect(call(f, 3).url).toBe("/pay/merchants?page=1&size=20&profileStatus=SUBMITTED");
  });

});

describe("P3a 映射（near-pay docs/api/handbook/08）", () => {
  it("提现审批带 decision、出款回执带 result/paymentRef、对账关闭带 outcome，都有幂等号", async () => {
    const { f, api } = httpApi({ resourceNo: "x", status: "OK", version: 2 });
    await api.withdrawals.review("WD1", { expectedVersion: 1, reason: "核对无误", decision: "APPROVE" });
    await api.payouts.receipt("PO1", { expectedVersion: 1, reason: "已转账", result: "PAID", paymentRef: "TWB-1" });
    await api.reconDiffs.close("RD1", { expectedVersion: 1, reason: "已补单", outcome: "RESOLVED" });
    expect(call(f, 0)).toMatchObject({ url: "/pay/withdrawals/WD1/review", method: "POST", body: { decision: "APPROVE", expectedVersion: 1 } });
    expect(call(f, 1).body).toMatchObject({ result: "PAID", paymentRef: "TWB-1" });
    expect(call(f, 2)).toMatchObject({ url: "/pay/reconciliation-differences/RD1/close", body: { outcome: "RESOLVED" } });
    for (let i = 0; i < 3; i++) expect(call(f, i).body.requestNo).toMatch(/^ops-/);
  });

  it("渠道成本调价是 PUT 追加新版本；覆盖范围与版本列表走各自路径", async () => {
    const { f, api } = httpApi({ record: { costNo: "CR2" }, version: 1 });
    await api.channelCosts.revise("CR1", { expectedVersion: 3, reason: "合同调价", payChannel: "NEWEBPAY", rateBps: 250, effectiveFrom: 1 });
    await api.reconDiffs.coverage();
    await api.channelCosts.versions("CR1");
    expect(call(f, 0)).toMatchObject({ url: "/pay/channel-costs/CR1", method: "PUT", body: { expectedVersion: 3, rateBps: 250 } });
    expect(call(f, 1).url).toBe("/pay/reconciliation-differences/coverage");
    expect(call(f, 2).url).toBe("/pay/channel-costs/CR1/versions");
  });

  it("mock：被他人认领的差异不能认领；提现超余额拒绝；渠道成本状态按维度计算", async () => {
    const api = createMockPayCenterApi();
    const diffs = (await api.reconDiffs.list({ page: 1, size: 20 })).records;
    const claimedByOther = (await Promise.all(diffs.map((d) => api.reconDiffs.detail(d.differenceNo)))).find((d) => d.record.claimedBy && d.record.claimedBy !== "admin")!;
    await expect(api.reconDiffs.claim(claimedByOther.record.difference.differenceNo, { expectedVersion: 1, reason: "认领" })).rejects.toBeTruthy();
    const costs = (await api.channelCosts.list({ page: 1, size: 20 })).records.filter((x) => x.payChannel === "NEWEBPAY" && x.payMethod === "CARD");
    expect(costs.map((x) => x.status).sort()).toEqual(["ACTIVE", "SUPERSEDED"]);
  });
});

describe("P4 映射（商户与进件工作区，near-pay /pay-ops/v2）", () => {
  it("代填资料：草稿 PUT 带 requestNo 与版本；提交固定 fields={}", async () => {
    const { f, api } = httpApi({ version: 1, state: "EDITING", profile: {}, filledFields: [], revisionNo: null });
    await api.merchants.saveAssisted("PM1", { expectedVersion: 0, fields: { legalName: "台北早餐" } });
    await api.merchants.submitAssisted("PM1", { expectedVersion: 1 });
    expect(call(f, 0)).toMatchObject({ url: "/pay/merchants/PM1/assisted-profile/draft", method: "PUT", body: { expectedVersion: 0, fields: { legalName: "台北早餐" } } });
    expect((call(f, 0).body as { requestNo?: string }).requestNo).toMatch(/^ops-/);
    expect(call(f, 1)).toMatchObject({ url: "/pay/merchants/PM1/assisted-profile/submit", method: "POST", body: { expectedVersion: 1, fields: {} } });
  });

  it("新建商户、收款策略 PUT、核验关联、提交审批", async () => {
    const { f, api } = httpApi({});
    await api.merchants.create({ operatingEntityId: "EN1", name: "台北早餐" });
    await api.merchants.savePolicy("PM1", { expectedVersion: 2, mode: "CUSTOM", productCodes: ["P1"], reason: "r" });
    await api.merchants.verifyLink("PM1", "SL1", { expectedVersion: 1, result: "VERIFIED", reason: "r" });
    await api.approvals.submit({ subjectType: "PLATFORM_MERCHANT_RESUME", subjectRef: "PM1", subjectRevision: 3, action: "RESUME", reason: "r" });
    expect(call(f, 0)).toMatchObject({ url: "/pay/merchant-creations", method: "POST", body: { operatingEntityId: "EN1", name: "台北早餐" } });
    expect(call(f, 1)).toMatchObject({ url: "/pay/merchants/PM1/collection-policy", method: "PUT" });
    expect(call(f, 2).url).toBe("/pay/merchants/PM1/source-links/SL1/verify");
    expect(call(f, 3)).toMatchObject({ url: "/pay/approvals", body: { subjectType: "PLATFORM_MERCHANT_RESUME", subjectRef: "PM1", subjectRevision: 3, action: "RESUME" } });
  });

  it("进件草稿：PUT 草稿；确认与提交固定 fields={}；封存不带 requestNo（near-pay 不收）", async () => {
    const { f, api } = httpApi({});
    await api.onboardings.saveDraft("O1", { expectedVersion: 2, expectedDraftVersion: 5, fields: { CONTACT_NAME: "王" } });
    await api.onboardings.confirmDraft("O1", { expectedVersion: 2, expectedDraftVersion: 6 });
    await api.onboardings.submitDraft("O1", { expectedVersion: 2, expectedDraftVersion: 7 });
    await api.onboardings.seal("O1", { expectedVersion: 2, fieldCode: "BankAccount", secret: "0012345678" });
    expect(call(f, 0)).toMatchObject({ url: "/pay/onboardings/O1/draft", method: "PUT", body: { expectedDraftVersion: 5, fields: { CONTACT_NAME: "王" } } });
    expect(call(f, 1)).toMatchObject({ url: "/pay/onboardings/O1/draft/confirm", body: { fields: {} } });
    expect(call(f, 2)).toMatchObject({ url: "/pay/onboardings/O1/draft/submit", body: { fields: {} } });
    expect(call(f, 3)).toEqual({ url: "/pay/onboardings/O1/newebpay-materials", method: "POST", body: { expectedVersion: 2, fieldCode: "BankAccount", secret: "0012345678" } });
  });

  it("mock：代填资料首存必须有法定名称；进件附件写进草稿、确认前要必填齐", async () => {
    const api = createMockPayCenterApi();
    await expect(api.merchants.saveAssisted("PM1001", { expectedVersion: 0, fields: { industry: "餐饮" } })).rejects.toBeTruthy();
    const ob = "OB4001";
    const d0 = await api.onboardings.draft(ob);
    await expect(api.onboardings.confirmDraft(ob, { expectedVersion: d0.expectedVersion, expectedDraftVersion: d0.draftVersion })).rejects.toBeTruthy();
    const up = await api.onboardings.upload(ob, { slot: "LICENSE_1", expectedVersion: d0.expectedVersion, expectedDraftVersion: d0.draftVersion }, new File(["%PDF-"], "a.pdf", { type: "application/pdf" }));
    expect(up.draft.filledFields).toContain("LICENSE_1");
    expect(up.draft.missing).not.toContain("LICENSE_1");
  });
});
