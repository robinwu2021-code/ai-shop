// 套餐商城 mock 状态机：规则在 mock 里强制，页面写不出违规操作。
// 每条都对应 Task 5 的一条验收：编辑/发布分开的乐观锁、已发布只读、停售不改历史订单、
// 申报 ≠ 到账、核款收款主体与流水唯一、金额不符只记 MALL_UNMATCHED、作废必须逐笔处置、出款金额对齐。
import { beforeEach, describe, expect, it, vi } from "vitest";
// mock 延迟是给页面看加载态的，状态机测试不需要它
vi.mock("@/lib/api/mocks/_wait", () => ({ wait: async <T,>(v: T) => v }));
import { ApiError } from "@/lib/api/error";
import { commerceMock as api } from "@/lib/api/mocks/commerce";
import { mall, resetMall } from "./commerce";

const reason = async (p: Promise<unknown>) => {
  const e = await p.then(() => null, (x) => x);
  expect(e, "应当被拒绝").toBeInstanceOf(ApiError);
  return (e as ApiError).message;
};
const png = () => new Blob([new Uint8Array([137, 80, 78, 71])], { type: "image/png" });
const S1 = "TW-STORE-001";

async function evidence(store: string, order: string, purpose: "PLATFORM_RECEIPT" | "RECONCILIATION" | "REFUND_PROOF" = "PLATFORM_RECEIPT") {
  const t = await api.reserveOfflineEvidence(store, order, purpose);
  await api.uploadOfflineEvidence(store, order, t.assetKey, png());
  return t.assetKey;
}

beforeEach(() => resetMall());

describe("目录：草稿 → 发布", () => {
  it("新建经营类型必须停用；乐观锁错版本 → VERSION_CONFLICT；同时生成三档 Plan 定义", async () => {
    expect(await reason(api.createMallBusinessType({ typeCode: "SALON", name: "美业", sortOrder: 3, enabled: true, expectedVersion: 0, reason: "r" })))
      .toBe("INVALID_ARGUMENT");
    await api.createMallBusinessType({ typeCode: "SALON", name: "美业", sortOrder: 3, enabled: false, expectedVersion: 0, reason: "r" });
    const defs = (await api.mallPlanDefinitions()).filter((p) => p.typeCode === "SALON").map((p) => p.tier);
    expect(defs).toEqual(["FREE", "PRO", "CHAIN"]);
    expect(await reason(api.updateMallBusinessType("SALON", { name: "美业", sortOrder: 3, enabled: false, expectedVersion: 7, reason: "r" })))
      .toBe("VERSION_CONFLICT");
    // 没发布过行业目录不能启用
    expect(await reason(api.updateMallBusinessType("SALON", { name: "美业", sortOrder: 3, enabled: true, expectedVersion: 1, reason: "r" })))
      .toBe("INVALID_ARGUMENT");
  });

  it("业务端：VERIFIED 必须从部署验收清单里选；选了就由清单决定 client/route/acceptance", async () => {
    const base = { code: "END_APP_POS", entitlementCode: "END_APP_POS", name: "POS", scope: "STORE" as const, allowedBusinessTypes: ["RETAIL"],
      saleEnabled: true, bootstrapRequired: false, enabled: true, expectedVersion: 2, reason: "r" };
    expect(await reason(api.saveMallBusinessEnd({ ...base, releaseStatus: "VERIFIED", deploymentNo: null }))).toBe("DEPLOYMENT_UNAVAILABLE");
    expect(await reason(api.saveMallBusinessEnd({ ...base, releaseStatus: "VERIFIED", deploymentNo: "DEP-DEV-BIZ" }))).toBe("INVALID_REQUEST");
    await api.saveMallBusinessEnd({ ...base, releaseStatus: "VERIFIED", deploymentNo: "DEP-DEV-POS" });
    const end = (await api.mallBusinessEnds()).find((e) => e.draft?.code === "END_APP_POS")!;
    expect(end.draft?.routePolicyRef).toBe("commerce-route-v1:POS");
    expect(end.version).toBe(3);
  });

  it("FNB：起草三档 → 行业发布 → 已发布版本只读；类型才可启用", async () => {
    const ids: Record<string, { vno: string; version: number }> = {};
    const codes = { FREE: ["END_BIZ_APP"], PRO: ["END_BIZ_APP", "END_APP_POS"], CHAIN: ["END_BIZ_APP", "END_APP_POS"] };
    for (const tier of ["FREE", "PRO", "CHAIN"] as const) {
      const r = await api.saveMallPlanVersion(`FNB:${tier}`, { planVersionNo: null, typeCode: "FNB", tier, displayName: `餐饮 ${tier}`,
        summary: null, entitlementCodes: codes[tier], policyVersion: "dev", expectedVersion: 0, reason: "起草" });
      ids[tier] = { vno: r.draftVersionNo!, version: r.version! };
    }
    const defs = Object.fromEntries((await api.mallPlanDefinitions()).filter((p) => p.typeCode === "FNB").map((p) => [p.planNo!, p.version!]));
    const publish = (expectedCatalogVersion: number, operationNo: string) => api.publishMallIndustry("FNB", {
      operationNo, freePlanVersionNo: ids.FREE!.vno, proPlanVersionNo: ids.PRO!.vno, chainPlanVersionNo: ids.CHAIN!.vno,
      candidateSkuVersionNos: [], expectedCatalogVersion,
      expectedPlanVersions: Object.fromEntries(Object.values(ids).map((x) => [x.vno, x.version])),
      expectedSkuVersions: {}, expectedPlanDefinitionVersions: defs, expectedSkuDefinitionVersions: {}, reason: "首发",
    });
    expect(await reason(publish(3, "OP-X"))).toBe("VERSION_CONFLICT");
    const res = await publish(0, "OP-1");
    expect(res.catalogVersion).toBe(1);
    // 同一 operationNo 重放返回同一结果，不二次发布
    expect((await publish(0, "OP-1")).publicationNo).toBe(res.publicationNo);
    expect(await reason(api.saveMallPlanVersion("FNB:FREE", { planVersionNo: ids.FREE!.vno, typeCode: "FNB", tier: "FREE",
      displayName: "改", summary: null, entitlementCodes: ["END_BIZ_APP"], policyVersion: "dev", expectedVersion: ids.FREE!.version + 1, reason: "r" })))
      .toBe("IMMUTABLE_VERSION");
    await api.updateMallBusinessType("FNB", { name: "餐饮", sortOrder: 2, enabled: true, expectedVersion: 2, reason: "上线" });
  });

  it("高档不包含低档权益 → 发布被拒", async () => {
    const mk = (tier: "FREE" | "PRO" | "CHAIN", codes: string[]) => api.saveMallPlanVersion(`FNB:${tier}`, { planVersionNo: null, typeCode: "FNB", tier,
      displayName: tier, summary: null, entitlementCodes: codes, policyVersion: "dev", expectedVersion: 0, reason: "r" });
    const f = await mk("FREE", ["END_BIZ_APP", "END_APP_POS"]);
    const p = await mk("PRO", ["END_BIZ_APP"]);
    const c = await mk("CHAIN", ["END_BIZ_APP", "END_APP_POS"]);
    const defs = Object.fromEntries((await api.mallPlanDefinitions()).filter((x) => x.typeCode === "FNB").map((x) => [x.planNo!, x.version!]));
    expect(await reason(api.publishMallIndustry("FNB", { operationNo: "OP", freePlanVersionNo: f.draftVersionNo!, proPlanVersionNo: p.draftVersionNo!,
      chainPlanVersionNo: c.draftVersionNo!, candidateSkuVersionNos: [], expectedCatalogVersion: 0,
      expectedPlanVersions: { [f.draftVersionNo!]: 1, [p.draftVersionNo!]: 1, [c.draftVersionNo!]: 1 }, expectedSkuVersions: {},
      expectedPlanDefinitionVersions: defs, expectedSkuDefinitionVersions: {}, reason: "r" }))).toBe("INVALID_ARGUMENT");
  });

  it("SKU：退款政策只认两种；发布后只读；停售不改已落单的快照", async () => {
    const base = { skuNo: "SKU-POS-HW-1", skuVersionNo: "SV-POS-HW-1-1", title: "收银机", listPrice: { currency: "TWD", minor: "1200000" },
      price: { currency: "TWD", minor: "990000" }, allowedBusinessTypes: ["RETAIL"],
      components: [{ kind: "HARDWARE" as const, hardwareSkuNo: "HW-POS-01", unitCount: 1, allocationWeight: 1, gift: false, activationPolicy: "ON_SHIPMENT" }],
      maxPerOrder: 5, expectedVersion: 1, reason: "r" };
    expect(await reason(api.saveMallSkuVersion("MP-POS-HW", { ...base, refundPolicy: { unusedDigital: "AUTO" as never, usedDigital: "MANUAL_ONLY" } })))
      .toBe("INVALID_REFUND_POLICY");
    const pub = await api.publishMallSkuVersion("SV-POS-HW-1-1", { operationNo: "OP-S", expectedVersion: 1, expectedSkuVersion: 1, reason: "上架" });
    expect(pub.publishedVersionNos).toEqual(["SV-POS-HW-1-1"]);
    expect(await reason(api.saveMallSkuVersion("MP-POS-HW", { ...base, expectedVersion: 2, refundPolicy: { unusedDigital: "MANUAL_ONLY", usedDigital: "MANUAL_ONLY" } })))
      .toBe("IMMUTABLE_VERSION");

    const before = structuredClone((await api.mallOrder(S1, "MO-1001")).quote);
    const def = (await api.mallCatalog()).skuDefinitions!.find((d) => d.skuNo === "SKU-RETAIL-PRO-Y")!;
    await api.stopMallSkuSale("SKU-RETAIL-PRO-Y", { operationNo: "OP-STOP", expectedVersion: def.version!, reason: "停售" });
    expect((await api.mallOrder(S1, "MO-1001")).quote).toEqual(before);
    expect(await reason(api.offlineAssistedQuote("TW-STORE-004", { lines: [{ skuVersionNo: "SV-RETAIL-PRO-Y-1", quantity: 1 }], currency: "TWD" })))
      .toBe("CHECKOUT_UNAVAILABLE");
  });
});

describe("线下收款：申报 ≠ 到账", () => {
  it("档案每次修改生成新版本；已发出的指引保留原版本", async () => {
    const p = (await api.offlineProfile("TWD"))!;
    const next = await api.configureOfflineProfile({ profileNo: p.profileNo, expectedVersion: p.profileVersion, platformMerchantNo: "PLT-DEV-TW",
      currency: "TWD", bankCode: "DEV-001", bankAccount: "DEV-9", accountName: "新户名", paymentWindowVersion: "dev-48h",
      paymentWindowMs: 2 * 86_400_000, enabled: true, reason: "换账户" });
    expect(next.profileVersion).toBe(2);
    expect(await reason(api.configureOfflineProfile({ profileNo: p.profileNo, expectedVersion: 1, platformMerchantNo: "PLT-DEV-TW",
      currency: "TWD", bankCode: "X", bankAccount: "Y", accountName: "Z", paymentWindowVersion: "v", paymentWindowMs: 1000, enabled: true, reason: "r" })))
      .toBe("VERSION_CONFLICT");
    expect((await api.offlineInstructions(S1, "MO-1002")).profileVersion).toBe(1);
    expect(await reason(api.configureOfflineProfile({ profileNo: "PRF-CNY", expectedVersion: 0, platformMerchantNo: "P", currency: "CNY",
      bankCode: "B", bankAccount: "A", accountName: "N", paymentWindowVersion: "v", paymentWindowMs: 999, enabled: true, reason: "r" })))
      .toBe("OFFLINE_PROFILE_INVALID");
  });

  it("待审队列只含 SUBMITTED/REVIEWING，按 (orderNo, claimNo) 翻页", async () => {
    const first = await api.offlinePendingClaims({ limit: 1 });
    expect(first.map((x) => x.orderNo)).toEqual(["MO-1002"]);
    const second = await api.offlinePendingClaims({ limit: 5, afterOrderNo: first[0]!.orderNo, afterClaimNo: first[0]!.claim.claimNo });
    expect(second.map((x) => x.orderNo)).toEqual(["MO-2001"]);
  });

  it("拒绝申报只改申报：订单仍待付款，不能重复拒绝", async () => {
    await api.declineOfflineClaim(S1, "MO-1002", "CL-1002-1", "流水对不上");
    const d = await api.mallOrder(S1, "MO-1002");
    expect(d.summary?.paymentStatus).toBe("UNPAID");
    expect(d.summary?.order?.status).toBe("AWAITING_PAYMENT");
    expect(await reason(api.declineOfflineClaim(S1, "MO-1002", "CL-1002-1", "again"))).toBe("CLAIM_ALREADY_REVIEWED");
  });

  it("核款：收款主体不符 / 凭证没传 / 流水重复都拒；金额相符才 APPLIED 并开通", async () => {
    const key = await evidence(S1, "MO-1002");
    const body = { actionNo: "ACT-1", claimNo: "CL-1002-1", actualAmount: { currency: "TWD", minor: "69900" },
      actualPaidAt: "2026-11-01T00:00:00.000Z", actualReceiver: "PLT-DEV-TW", bankReference: "BANK-NEW-1", evidenceKey: key, historical: false, reason: "到账" };
    expect(await reason(api.confirmOfflinePayment(S1, "MO-1002", { ...body, actualReceiver: "SOMEONE" }))).toBe("OFFLINE_RECEIVER_MISMATCH");
    const reserved = await api.reserveOfflineEvidence(S1, "MO-1002", "PLATFORM_RECEIPT");
    expect(await reason(api.confirmOfflinePayment(S1, "MO-1002", { ...body, evidenceKey: reserved.assetKey }))).toBe("EVIDENCE_UNAVAILABLE");
    expect(await reason(api.confirmOfflinePayment(S1, "MO-1002", { ...body, bankReference: "DEV-BANK-REF-1005" }))).toBe("OFFLINE_BANK_REFERENCE_CONFLICT");
    const r = await api.confirmOfflinePayment(S1, "MO-1002", body);
    expect(r.status).toBe("APPLIED");
    const d = await api.mallOrder(S1, "MO-1002");
    expect([d.summary?.paymentStatus, d.summary?.fulfillmentStatus]).toEqual(["PAID", "COMPLETED"]);
    expect((await api.offlineClaims(S1, "MO-1002"))[0]!.status).toBe("CONFIRMED");
    // 同一 actionNo 重放：同结果；换参数：冲突
    expect((await api.confirmOfflinePayment(S1, "MO-1002", body)).paymentNo).toBe(r.paymentNo);
    expect(await reason(api.confirmOfflinePayment(S1, "MO-1002", { ...body, bankReference: "OTHER" }))).toBe("IDEMPOTENCY_CONFLICT");
  });

  it("金额不符 → UNMATCHED：真实收的钱照记，不发权益、不补差", async () => {
    const key = await evidence(S1, "MO-1002");
    const r = await api.confirmOfflinePayment(S1, "MO-1002", { actionNo: "ACT-2", claimNo: null, actualAmount: { currency: "TWD", minor: "50000" },
      actualPaidAt: "2026-11-01T00:00:00.000Z", actualReceiver: "PLT-DEV-TW", bankReference: "BANK-NEW-2", evidenceKey: key, historical: false, reason: "少付" });
    expect(r.status).toBe("UNMATCHED");
    const d = await api.mallOrder(S1, "MO-1002");
    expect([d.summary?.paymentStatus, d.summary?.fulfillmentStatus]).toEqual(["UNPAID", "PENDING"]);
    expect(d.paymentAttempts!.some((a) => a.errorCode === "MALL_UNMATCHED" && a.paymentNo === r.paymentNo)).toBe(true);
  });

  it("早于下单的到账必须以历史补录口径录入", async () => {
    const key = await evidence(S1, "MO-1002");
    expect(await reason(api.confirmOfflinePayment(S1, "MO-1002", { actionNo: "ACT-H", claimNo: null, actualAmount: { currency: "TWD", minor: "69900" },
      actualPaidAt: "2026-10-01T00:00:00.000Z", actualReceiver: "PLT-DEV-TW", bankReference: "BANK-H", evidenceKey: key, historical: false, reason: "r" })))
      .toBe("OFFLINE_HISTORICAL_MODE_REQUIRED");
  });

  it("作废：每一笔 MALL_UNMATCHED 都要写处置；已付不能作废", async () => {
    const S3 = "TW-STORE-003";
    const key = await evidence(S3, "MO-3001", "RECONCILIATION");
    const base = { actionNo: "VOID-1", reconciliationReference: "RECON-1", reason: "逾期未补足", evidenceKey: key };
    expect(await reason(api.voidOfflineOrder(S3, "MO-3001", { ...base, unmatchedDispositions: {} }))).toBe("OFFLINE_UNMATCHED_DISPOSITION_REQUIRED");
    const r = await api.voidOfflineOrder(S3, "MO-3001", { ...base, unmatchedDispositions: { "U-3001-1": "已联系门店，走线下退款退回" } });
    expect(r.status).toBe("APPLIED");
    expect((await api.mallOrder(S3, "MO-3001")).summary?.paymentStatus).toBe("CLOSED");

    const k5 = await evidence(S1, "MO-1005", "RECONCILIATION");
    expect(await reason(api.voidOfflineOrder(S1, "MO-1005", { ...base, actionNo: "VOID-2", evidenceKey: k5, unmatchedDispositions: {} }))).toBe("OFFLINE_ALREADY_PAID");
  });

  it("线下退款出款：金额必须等于退款意图，流水唯一，之后退款维度才算 REFUNDED", async () => {
    const key = await evidence(S1, "MO-1005", "REFUND_PROOF");
    const body = { actualAmount: { currency: "TWD", minor: "69900" }, paidOutAt: "2026-11-02T00:00:00.000Z", payeeReference: "门店账户",
      bankReference: "OUT-1", evidenceKey: key };
    expect(await reason(api.confirmOfflineRefundPayout(S1, "MO-1005", "RF-1005", { ...body, actualAmount: { currency: "TWD", minor: "60000" } })))
      .toBe("OFFLINE_PAYOUT_AMOUNT_MISMATCH");
    const r = await api.confirmOfflineRefundPayout(S1, "MO-1005", "RF-1005", body);
    expect(r.status).toBe("SUCCEEDED");
    expect((await api.mallOrder(S1, "MO-1005")).summary?.refundStatus).toBe("REFUNDED");
  });

  it("代客：报价 → 下单 → 发指引（幂等）→ 指引冻结当前档案版本", async () => {
    const q = await api.offlineAssistedQuote("TW-STORE-004", { lines: [{ skuVersionNo: "SV-RETAIL-PRO-Y-1", quantity: 1 }], currency: "TWD" });
    expect(q.payable).toEqual({ currency: "TWD", minor: "69900" });
    const o = await api.offlineAssistedPlace("TW-STORE-004", { quoteNo: q.quoteNo!, idempotencyKey: "IDEM-1", shippingAddress: null });
    expect((await api.offlineAssistedPlace("TW-STORE-004", { quoteNo: q.quoteNo!, idempotencyKey: "IDEM-1", shippingAddress: null })).orderNo).toBe(o.orderNo);
    expect(await reason(api.offlineInstructions("TW-STORE-004", o.orderNo!))).toBe("OFFLINE_INSTRUCTIONS_NOT_ISSUED");
    const i1 = await api.issueOfflineInstructions("TW-STORE-004", o.orderNo!);
    const i2 = await api.issueOfflineInstructions("TW-STORE-004", o.orderNo!);
    expect(i2).toEqual(i1);
    expect(i1.profileVersion).toBe(mall.s.profiles.TWD!.terms.profileVersion);
    // 同店第二笔数字单 → 唯一占用
    const q2 = await api.offlineAssistedQuote("TW-STORE-004", { lines: [{ skuVersionNo: "SV-RETAIL-PRO-Y-1", quantity: 1 }], currency: "TWD" });
    expect(await reason(api.offlineAssistedPlace("TW-STORE-004", { quoteNo: q2.quoteNo!, idempotencyKey: "IDEM-2", shippingAddress: null })))
      .toBe("DIGITAL_CHANGE_PENDING");
  });

  it("凭证每个键只能传一次；非图片拒绝", async () => {
    const t = await api.reserveOfflineEvidence(S1, "MO-1002", "PLATFORM_RECEIPT");
    expect(await reason(api.uploadOfflineEvidence(S1, "MO-1002", t.assetKey, new Blob(["x"], { type: "text/plain" })))).toBe("INVALID_REQUEST");
    await api.uploadOfflineEvidence(S1, "MO-1002", t.assetKey, png());
    expect(await reason(api.uploadOfflineEvidence(S1, "MO-1002", t.assetKey, png()))).toBe("EVIDENCE_ALREADY_STORED");
    expect((await api.offlineEvidenceUrl(S1, "MO-1002", t.assetKey)).url).toMatch(/^data:image\/png;base64,/);
  });
});

describe("订单与权益", () => {
  it("硬件发货：不超发，operationNo 幂等且参数不可变，全部发完履约才 COMPLETED", async () => {
    const ship = (operationNo: string, quantity: number, trackingNo = "T1") =>
      api.shipMallOrder(S1, "MO-1003", { orderComponentNo: "OC-HW-1003", operationNo, carrierCode: "DEV", trackingNo, quantity });
    expect(await reason(ship("SH-0", 3))).toBe("INVALID_REQUEST");
    expect((await ship("SH-1", 1)).summary?.fulfillmentStatus).toBe("PARTIAL");
    expect(await reason(ship("SH-1", 1, "T2"))).toBe("IDEMPOTENCY_CONFLICT");
    expect((await ship("SH-2", 1, "T2")).summary?.fulfillmentStatus).toBe("COMPLETED");
  });

  it("退款审核：review-quote → approve 绑定 reviewQuoteNo+approvalDigest，金额不超上限", async () => {
    const q = await api.reviewMallRefund(S1, "MO-1001", "RF-1001", { requestDigest: "RD-1001", reviewQuoteNo: null });
    const base = { approvalDigest: q.preview!.approvalDigest!, reviewQuoteNo: q.reviewQuoteNo!, restoreCredit: { currency: "TWD", minor: "0" }, reviewEvidence: "门店未使用" };
    expect(await reason(api.approveMallRefund(S1, "MO-1001", "RF-1001", { ...base, cashAmount: { currency: "TWD", minor: "70000" } }))).toBe("INVALID_REQUEST");
    expect(await reason(api.approveMallRefund(S1, "MO-1001", "RF-1001", { ...base, approvalDigest: "forged", cashAmount: { currency: "TWD", minor: "69900" } }))).toBe("INVALID_REQUEST");
    const r = await api.approveMallRefund(S1, "MO-1001", "RF-1001", { ...base, cashAmount: { currency: "TWD", minor: "69900" } });
    expect([r.reviewStatus, r.status]).toEqual(["APPROVED", "PENDING"]);
    expect(await reason(api.rejectMallRefund(S1, "MO-1001", "RF-1001", { requestDigest: "RD-1001", reviewEvidence: "x" }))).toBe("VERSION_CONFLICT");
  });

  it("权益来源：只能赠送可单售的独立端；只能撤销零金额赠送，付费来源要走退款", async () => {
    const sub = await api.mallSubscription(S1);
    expect(await reason(api.grantMallEnd(S1, { operationNo: "G-1", entitlementCode: "END_BIZ_APP", durationDays: 30, reason: "r" }))).toBe("INVALID_REQUEST");
    const g = await api.grantMallEnd(S1, { operationNo: "G-2", entitlementCode: "END_APP_POS", durationDays: 30, reason: "活动" });
    expect(g.entitlementVersion).toBe(sub.entitlementVersion! + 1);
    expect(await reason(api.revokeMallSource(S1, "SRC-001-PRO", { operationNo: "R-1", reason: "r", expectedVersion: g.entitlementVersion! }))).toBe("INVALID_REQUEST");
    expect(await reason(api.revokeMallSource(S1, "SRC-001-GIFT", { operationNo: "R-2", reason: "r", expectedVersion: 1 }))).toBe("VERSION_CONFLICT");
    const after = await api.revokeMallSource(S1, "SRC-001-GIFT", { operationNo: "R-3", reason: "误赠", expectedVersion: g.entitlementVersion! });
    expect(after.sources!.find((x) => x.sourceNo === "SRC-001-GIFT")!.lifecycle).toBe("REVOKED");
  });
});
