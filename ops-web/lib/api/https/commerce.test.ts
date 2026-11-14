// 商城 http 层：真实 http-client + 桩 fetch。守三件事：
//   1. 凭证上传发的是**图片字节**（不是 JSON、不是 multipart），Content-Type 跟着文件走
//   2. 后端拒绝时原样抛机器原因码，**不回落 mock、不吞成成功**
//   3. 门店维度接口把 storeNo 放查询串，路径保持字面量（契约闸门按字面量比对）
import { afterEach, describe, expect, it, vi } from "vitest";
vi.mock("../../auth", () => ({ currentAuth: () => ({ token: "TOKEN", username: "op" }), scopeOf: () => ({ tenantNo: "MAIN" }), refreshPerms: vi.fn(), useAuth: { getState: () => ({ logout: vi.fn() }) } }));
vi.mock("../../notify", () => ({ notify: { error: vi.fn() } }));
vi.mock("../../stores/locale", () => ({ useLocaleStore: { getState: () => ({ locale: "zh" }) } }));
import { commerceHttp } from "./commerce";
import { ApiError } from "../error";

afterEach(() => vi.unstubAllGlobals());

const ok = (data: unknown) => vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ({ code: 0, msg: "success", data }) });

it("reads documentation and appends metadata without using confirmation endpoint",async()=>{
  const fetch=ok({testAllowed:true,confirmations:[]});vi.stubGlobal("fetch",fetch);
  await commerceHttp.offlineReceiptDocumentation("S/1","O/1");
  const input={actionNo:"docs",confirmationActionNo:"confirmed",note:"Test only",evidenceKeys:[]};
  await commerceHttp.supplementOfflineReceipt("S/1","O/1",input);
  expect(fetch.mock.calls[0]![0]).toContain("/O%2F1/receipt-documentation?storeNo=S%2F1");
  expect(fetch.mock.calls[1]![0]).toContain("/receipt-documentation?");expect(JSON.parse(fetch.mock.calls[1]![1].body)).toEqual(input);
});

describe("commerce http", () => {
  it("销售方式、销售凭据和门店按正式合同发送，无银行占位字段", async () => {
    const fetch=ok({status:"ACCEPTED"});vi.stubGlobal("fetch",fetch);
    await commerceHttp.offlineAssistedQuote("STORE-1",{lines:[{skuVersionNo:"SKU-1",quantity:1}],currency:"TWD",collectionMethod:"SALES_COLLECTION"});
    expect(JSON.parse(fetch.mock.calls[0]![1].body)).toMatchObject({collectionMethod:"SALES_COLLECTION"});
    await commerceHttp.confirmOfflinePayment("STORE-1","ORDER-1",{
      actionNo:"A1",claimNo:null,actualAmount:{currency:"TWD",minor:"19900"},actualPaidAt:"2026-11-13T00:00:00Z",
      actualReceiver:"PLATFORM",collectorNo:"SALES-1",collectionReference:"RECEIPT-1",evidenceKey:"PRIVATE",historical:false,reason:"actual receipt",
    });
    const body=JSON.parse(fetch.mock.calls[1]![1].body);
    expect(body.collectorNo).toBe("SALES-1");expect(body.collectionReference).toBe("RECEIPT-1");expect(body).not.toHaveProperty("bankReference");
    await commerceHttp.offlineCollectors("STORE-1");
    expect(fetch.mock.calls[2]![0]).toContain("/ops/biz-mall/offline/collectors?storeNo=STORE-1");
  });
  it("凭证上传发原始字节，Content-Type=image/png，assetKey 与 storeNo 在查询串", async () => {
    const fetch = ok({ assetKey: "K1" });
    vi.stubGlobal("fetch", fetch);
    const file = new Blob([new Uint8Array([137, 80, 78, 71])], { type: "image/png" });
    await commerceHttp.uploadOfflineEvidence("TW-STORE-001", "MO-1", "K1", file);
    const [url, init] = fetch.mock.calls[0]!;
    expect(url).toContain("/ops/biz-mall/offline/orders/MO-1/evidence/content?storeNo=TW-STORE-001&assetKey=K1");
    expect(init.body).toBe(file);
    expect(init.headers["Content-Type"]).toBe("image/png");
    expect(init.headers.Authorization).toBe("Bearer TOKEN");
  });

  it("looks up an original action without sending receipt details", async () => {
    const fetch = ok({ actionNo: "ACT-1", status: "ACCEPTED", paymentNo: null });
    vi.stubGlobal("fetch", fetch);
    expect((await commerceHttp.offlineAction("S1", "MO-1", "ACT-1"))?.status).toBe("ACCEPTED");
    expect(fetch.mock.calls[0]![0]).toContain("/ops/biz-mall/offline/orders/MO-1/actions/ACT-1?storeNo=S1");
    expect((fetch.mock.calls[0]![1].method ?? "GET")).toBe("GET");
    expect(fetch.mock.calls[0]![1].body).toBeUndefined();
  });

  it("普通写操作仍是 JSON", async () => {
    const fetch = ok({ claimNo: "C1", status: "DECLINED" });
    vi.stubGlobal("fetch", fetch);
    await commerceHttp.declineOfflineClaim("TW-STORE-001", "MO-1", "C1", "金额对不上");
    const [url, init] = fetch.mock.calls[0]!;
    expect(url).toContain("/ops/biz-mall/offline/orders/MO-1/claims/C1/decline?storeNo=TW-STORE-001");
    expect(init.body).toBe(JSON.stringify({ reason: "金额对不上" }));
    expect(init.headers["Content-Type"]).toBe("application/json");
  });

  it("后端拒绝：原因码原样抛出，不变成成功", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: false, status: 409, json: async () => ({ code: 10409, msg: "OFFLINE_BANK_REFERENCE_CONFLICT", data: null }) }));
    const err = await commerceHttp.confirmOfflinePayment("TW-STORE-001", "MO-1", {
      actionNo: "A1", claimNo: null, actualAmount: { currency: "TWD", minor: "69900" }, actualPaidAt: "2026-11-01T00:00:00Z",
      actualReceiver: "PLT", bankReference: "B1", evidenceKey: "K", historical: false, reason: "r",
    }).catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err.code).toBe(10409);
    expect(err.message).toBe("OFFLINE_BANK_REFERENCE_CONFLICT");
  });

  it("接口没装配（开关关着 → 404）时报错，而不是给出空目录", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: false, status: 404, json: async () => ({}) }));
    await expect(commerceHttp.mallCatalog()).rejects.toBeInstanceOf(ApiError);
  });

  it("管理查询保留停用档案的 enabled 与当前版本", async () => {
    const profile = { profileNo: "p", profileVersion: 4, enabled: false, currency: "TWD" };
    const fetch = ok(profile); vi.stubGlobal("fetch", fetch);
    expect(await commerceHttp.offlineProfile("TWD")).toEqual(profile);
    expect(fetch.mock.calls[0]![0]).toContain("/ops/biz-mall/offline/profile?currency=TWD");
  });

  it("从未配置的收款档案：data=null 原样返回 null", async () => {
    vi.stubGlobal("fetch", ok(null));
    expect(await commerceHttp.offlineProfile("TWD")).toBeNull();
  });
});
it("mall cash recovery scopes every operation and never sends payout details on lookup",async()=>{
 const fetch=ok({refundNo:"late-R1",status:"PAYOUT_ACCEPTED"});vi.stubGlobal("fetch",fetch);
 await commerceHttp.mallCashReceipts("S/1","O/1");await commerceHttp.mallCashRefunds("S/1","O/1");
 const request={refundNo:"cash-R1",paymentNo:"P1",reason:"extra cash"};await commerceHttp.requestMallCashRefund("S/1","O/1",request);
 const payout={actionNo:"A1",actualAmount:{currency:"TWD",minor:"99900"},paidOutAt:"2026-11-11T00:00:00Z",payeeReference:"ORIGINAL_PAYER",bankReference:"ACTUAL_RETURN",evidenceKey:"PRIVATE"};
 await commerceHttp.payoutMallCashRefund("S/1","O/1","late-R1",payout);await commerceHttp.recoverMallCashRefund("S/1","O/1","late-R1");
 for(const [url]of fetch.mock.calls)expect(url).toContain("storeNo=S%2F1");expect(fetch.mock.calls[0][0]).toContain("/orders/O%2F1/cash-receipts");expect(fetch.mock.calls[1][1].body).toBeUndefined();expect(JSON.parse(fetch.mock.calls[2][1].body)).toEqual(request);expect(JSON.parse(fetch.mock.calls[3][1].body)).toEqual(payout);expect(fetch.mock.calls[4][0]).toContain("/cash-refunds/late-R1/recover");expect(JSON.parse(fetch.mock.calls[4][1].body)).toEqual({});
});
it("recovers only the scoped original offline order without replaying bank evidence or release",async()=>{
 const fetch=ok({recovered:true});vi.stubGlobal("fetch",fetch);expect(await commerceHttp.recoverOfflineOrder("S/1","O/1")).toEqual({recovered:true});
 expect(fetch.mock.calls[0][0]).toContain("/ops/biz-mall/offline/orders/O%2F1/recover?storeNo=S%2F1");expect(fetch.mock.calls[0][1].method).toBe("POST");expect(JSON.parse(fetch.mock.calls[0][1].body)).toEqual({});
});
