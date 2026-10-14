/**
 * 结账选券传的是**用户持有的那一张**（userCouponNo），不是券模板号。
 *
 * 2026-09-21 梳理券流程时查出：确认页一直传模板号，而后端按 userCouponNo 查券 ——
 * 真后端上选任何券都回 40002、被当成「券失效」摘掉。mock 当时按模板号查，本机一路是好的，
 * 于是这个缺陷上线了两个版本没人发现。这里两头都钉住。
 */
import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { db } from "@shared/mock/db";
import { aftersaleMock } from "@/api/mocks/aftersale";

const vue = readFileSync(resolve(__dirname, "../src/pages/order-confirm/index.vue"), "utf-8");

describe("确认页选券", () => {
  it("★★★ 选中的是 userCouponNo", () => {
    expect(vue).toContain("chooseCoupon(u.userCouponNo)");
    expect(vue).not.toContain("chooseCoupon(u.coupon.couponNo)");
    expect(vue).toContain("u.userCouponNo === couponNo.value");
  });
});

describe("mock 与后端同一口径", () => {
  const preview = (couponNo: string) => aftersaleMock.orderPreview!({
    items: [{ goodsNo: "G001", skuNo: "G001S1", qty: 10 }],
    fulfillment: "STORE_PICKUP",
    couponNo,
  } as Parameters<NonNullable<typeof aftersaleMock.orderPreview>>[0]);

  it("★★★ 传 userCouponNo 能减", async () => {
    const r = await preview("UC0001");
    expect(r.amount.discountMinor).toBeGreaterThan(0);
  });

  it("★★★ 传模板号不认 —— 否则 mock 又替「传错了号」背书", async () => {
    const r = await preview(db.couponSeeds[0]!.couponNo);
    const none = await preview("");
    expect(r.amount.discountMinor).toBe(none.amount.discountMinor);
  });
});
