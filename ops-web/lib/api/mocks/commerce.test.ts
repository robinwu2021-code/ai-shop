import { beforeEach, expect, it } from "vitest";
import { commerceMock as api } from "./commerce";
import { mall, resetMall } from "@/lib/mock/db/commerce";
beforeEach(() => resetMall());
it("administrative profile read returns disabled state and its current version", async () => {
  const p = mall.s.profiles.TWD!; p.enabled = false; const previousVersion = p.terms.profileVersion;
  expect(await api.offlineProfile("TWD")).toEqual({ ...p.terms, enabled: false });
  expect((await api.configureOfflineProfile({ profileNo: p.terms.profileNo, expectedVersion: p.terms.profileVersion, platformMerchantNo: p.terms.platformMerchantNo, currency: "TWD", bankCode: "BANK", bankAccount: "123", accountName: "Platform", paymentWindowVersion: "w1", paymentWindowMs: 86400000, enabled: true, reason: "resume" })).profileVersion).toBe(previousVersion + 1);
  expect((await api.offlineProfile("TWD"))!.enabled).toBe(true);
});
it("seed paid stores show the effective Plan while the free store retains its bound version", async () => {
  expect((await api.mallSubscription("TW-STORE-001")).currentPlanVersionNo).toBe("PV-RETAIL-PRO-1");
  mall.s.planDefs["RETAIL:FREE"]!.currentVersionNo = "PV-RETAIL-FREE-NEW";
  expect((await api.mallSubscription("TW-STORE-002")).currentPlanVersionNo).toBe("PV-RETAIL-FREE-1");
});

it("hardware orders require contact details and reject address changes under the same idempotency key", async () => {
  await api.publishMallSkuVersion("SV-POS-HW-1-1", { operationNo: "publish-hw", expectedVersion: 1, expectedSkuVersion: 1, reason: "sell" });
  const q = await api.offlineAssistedQuote("TW-STORE-004", { lines: [{ skuVersionNo: "SV-POS-HW-1-1", quantity: 1 }], currency: "TWD" });
  const address = { recipient: "Recipient", phone: "123", countryCode: "TW", addressLine: "Taipei" };
  const request = { quoteNo: q.quoteNo!, idempotencyKey: "hw-order", shippingAddress: address };
  await expect(api.offlineAssistedPlace("TW-STORE-004", { ...request, shippingAddress: { ...address, phone: "" } })).rejects.toThrow("INVALID_REQUEST");
  const order = await api.offlineAssistedPlace("TW-STORE-004", request);
  expect((await api.offlineAssistedPlace("TW-STORE-004", request)).orderNo).toBe(order.orderNo);
  await expect(api.offlineAssistedPlace("TW-STORE-004", { ...request, shippingAddress: { ...address, addressLine: "Another" } })).rejects.toThrow("IDEMPOTENCY_CONFLICT");
});
