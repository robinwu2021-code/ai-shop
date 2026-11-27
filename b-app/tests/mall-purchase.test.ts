import { expect, it } from "vitest";
import { mallMock as mall } from "@/api/mocks/mall";

it("门店按已发布 SKU 数量报价，硬件地址和原订单在提交重试时保持一致", async () => {
  const store = "STORE-POS";
  const front = await mall.mMallStorefront(store);
  const item = front.products!.find(p => p.product?.kind === "HARDWARE")!.skus![0]!;
  expect(item.offlineReadiness?.paymentOptions?.[0]?.payMethod).toBe("BANK_TRANSFER");
  const lines = [{ skuVersionNo: item.sku!.skuVersionNo!, quantity: 2 }];
  const quote = await mall.mMallOfflineQuote(store, { lines, currency: "TWD" });
  expect(quote.payable?.minor).toBe(String(BigInt(item.sku!.price!.minor) * 2n));
  const request = { quoteNo: quote.quoteNo!, idempotencyKey: "checkout", shippingAddress: null };
  await expect(mall.mMallPlaceOrder(store, request)).rejects.toThrow("SHIPPING_REQUIRED");
  const addressed = { ...request, shippingAddress: { recipient: "店主", phone: "12345678", countryCode: "TW", addressLine: "门店" } };
  const order = await mall.mMallPlaceOrder(store, addressed);
  expect(await mall.mMallPlaceOrder(store, addressed)).toEqual(order);
  expect(await mall.mMallOrders(store)).toHaveLength(1);
  const detail = await mall.mMallOrder(store, order.orderNo!);
  expect(detail.shippingAddress).toEqual(addressed.shippingAddress);
  expect(detail.lines?.[0]).toMatchObject({ title: item.sku!.title, quantity: 2 });
  await expect(mall.mMallPlaceOrder(store, { ...addressed, idempotencyKey: "again" })).rejects.toThrow("QUOTE_USED");
  const no = order.orderNo!;
  const instructions = await mall.mMallIssueInstructions(store, no);
  expect(instructions.instructions.transferRemark).toBe(no);
  expect((await mall.mMallOrder(store, no)).summary?.paymentStatus).toBe("PAYING");
  const proof = await mall.mMallReserveEvidence(store, no);
  const claim = { claimNo: "claim", declaredPaidAt: new Date().toISOString(), declaredAmount: instructions.payable,
    evidenceKeys: [proof.assetKey], bankReference: "transfer" };
  await expect(mall.mMallSubmitClaim(store, no, claim)).rejects.toThrow("EVIDENCE_UNAVAILABLE");
  const bytes = new Uint8Array([255,216,255]).buffer;
  await expect(mall.mMallUploadEvidence(store, "other", proof.assetKey, bytes, "image/jpeg")).rejects.toThrow("EVIDENCE_FORBIDDEN");
  await mall.mMallUploadEvidence(store, no, proof.assetKey, bytes, "image/jpeg");
  const submitted = await mall.mMallSubmitClaim(store, no, claim);
  expect(await mall.mMallSubmitClaim(store, no, claim)).toEqual(submitted);
  expect(await mall.mMallClaims(store, no)).toEqual([submitted]);
});
