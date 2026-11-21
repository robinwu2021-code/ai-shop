import { describe, expect, it, vi } from "vitest";
import { createPayOpsClient } from "./_api/client";
import { createHttpPayCenterApi } from "./_api/api";
import { createMockPayCenterApi } from "./_api/mock";
import { buildMethodSave, initialOn } from "./store-extras";
import type { StoreMethodView } from "./_api/types";

function httpApi(data: unknown = { records: [], total: 0 }) {
  const f = vi.fn(async (_u: string, _i?: RequestInit) => new Response(JSON.stringify({ code: 0, message: "OK", data, requestId: "r" }), { status: 200 }));
  return { f, api: createHttpPayCenterApi(createPayOpsClient({ baseUrl: "/pay", fetcher: f as unknown as typeof fetch })) };
}
const call = (f: ReturnType<typeof vi.fn>, i = 0) => {
  const [url, init] = f.mock.calls[i] as [string, RequestInit];
  return { url, method: init.method, body: init.body ? JSON.parse(String(init.body)) : undefined };
};

const item = (code: string, editable: boolean, configuredEnabled: boolean) => ({
  choice: { paymentMethodCode: code, test: false }, configuredEnabled, effectiveEnabled: editable && configuredEnabled, editable, blockedReason: editable ? null : "MERCHANT", productCodes: [], channelCodes: [],
});
const view = (items: StoreMethodView["items"]): StoreMethodView => ({ storeNo: "S1", mode: "OVERRIDE", version: 4, eligibilityRevision: "rev", items });

describe("P7 · 门店支付方式的保存口径（与 near-pay StoreCollectionMethodService 一致，TDD-支付中心运营页面 §2.13）", () => {
  it("可改项全部开启 = 跟随商户，开启集合留空；不可改项不参与", () => {
    const v = view([item("CARD", true, true), item("LINEPAY", true, false), item("JKOPAY", false, false)]);
    const on = initialOn(v);
    expect([...on]).toEqual(["CARD|0"]);
    expect(buildMethodSave(v, on)).toEqual({ expectedVersion: 4, eligibilityRevision: "rev", mode: "OVERRIDE", enabledMethods: [{ paymentMethodCode: "CARD", test: false }] });
    expect(buildMethodSave(v, new Set(["CARD|0", "LINEPAY|0"]))).toEqual({ expectedVersion: 4, eligibilityRevision: "rev", mode: "INHERIT", enabledMethods: [] });
  });
});

describe("P7 · 门店收款码、支付方式、同步事件的 HTTP 映射", () => {
  it("建码不带自动幂等号（near-pay 建码非幂等）；停码带 requestNo + 版本 + 理由；码号编码", async () => {
    const { f, api } = httpApi({ qrCode: "Q1", status: "ACTIVE" });
    await api.stores.qrCodes("S1");
    await api.stores.createQrCode("S1", { kind: "TABLE", tableRef: "A12", singleLimitMinor: 100000, dailyLimitMinor: null, reason: "新桌" });
    await api.stores.disableQrCode("S1", "Q/1", { expectedVersion: 2, reason: "停用" });
    expect(call(f, 0)).toMatchObject({ url: "/pay/stores/S1/qr-codes", method: "GET" });
    expect(call(f, 1)).toEqual({ url: "/pay/stores/S1/qr-codes", method: "POST", body: { kind: "TABLE", tableRef: "A12", singleLimitMinor: 100000, dailyLimitMinor: null, reason: "新桌" } });
    expect(call(f, 2)).toMatchObject({ url: "/pay/stores/S1/qr-codes/Q%2F1/disable", body: { expectedVersion: 2, reason: "停用" } });
    expect(call(f, 2).body.requestNo).toMatch(/^ops-/);
  });

  it("支付方式读写走 method-preferences（保存是 PUT）；同步事件分页", async () => {
    const { f, api } = httpApi({ records: [], total: 0 });
    await api.stores.methods("S1");
    await api.stores.saveMethods("S1", { expectedVersion: 4, eligibilityRevision: "rev", mode: "INHERIT", enabledMethods: [] });
    await api.stores.syncEvents("S1", { page: 1, size: 20 });
    expect(call(f, 0).url).toBe("/pay/stores/S1/method-preferences");
    expect(call(f, 1)).toMatchObject({ url: "/pay/stores/S1/method-preferences", method: "PUT", body: { expectedVersion: 4, eligibilityRevision: "rev", mode: "INHERIT", enabledMethods: [] } });
    expect(call(f, 2).url).toBe("/pay/stores/S1/sync-events?page=1&size=20");
  });
});

describe("P7 · mock 状态机", () => {
  it("收款码：桌码要桌号；停码要当前版本，停过再停是幂等", async () => {
    const api = createMockPayCenterApi();
    await expect(api.stores.createQrCode("S9", { kind: "TABLE", tableRef: "", singleLimitMinor: null, dailyLimitMinor: null, reason: "x" })).rejects.toThrow();
    const c = await api.stores.createQrCode("S9", { kind: "STORE", tableRef: null, singleLimitMinor: null, dailyLimitMinor: null, reason: "开店" });
    await expect(api.stores.disableQrCode("S9", c.qrCode, { expectedVersion: 5, reason: "停" })).rejects.toThrow();
    await api.stores.disableQrCode("S9", c.qrCode, { expectedVersion: 0, reason: "停" });
    await api.stores.disableQrCode("S9", c.qrCode, { expectedVersion: 0, reason: "再停" });
    expect((await api.stores.qrCodes("S9"))[0].status).toBe("DISABLED");
  });

  it("支付方式：版本或资格变了拒绝；跟随商户不许带开启集合", async () => {
    const api = createMockPayCenterApi();
    const v = await api.stores.methods("S8");
    await expect(api.stores.saveMethods("S8", { expectedVersion: v.version + 1, eligibilityRevision: v.eligibilityRevision, mode: "INHERIT", enabledMethods: [] })).rejects.toThrow();
    await expect(api.stores.saveMethods("S8", { expectedVersion: v.version, eligibilityRevision: v.eligibilityRevision, mode: "INHERIT", enabledMethods: [v.items[0].choice] })).rejects.toThrow();
    const saved = await api.stores.saveMethods("S8", { expectedVersion: v.version, eligibilityRevision: v.eligibilityRevision, mode: "OVERRIDE", enabledMethods: [v.items[0].choice] });
    expect(saved.mode).toBe("OVERRIDE");
    expect(saved.items.filter((i) => i.effectiveEnabled).map((i) => i.choice.paymentMethodCode)).toEqual(["CARD"]);
  });
});
