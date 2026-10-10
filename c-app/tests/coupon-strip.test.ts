/**
 * 领券入口（优惠券全链路梳理 批 1）：商品页、店铺页、商家页同一个组件。
 *
 * 此前只有商品页能领，而且读老券表 —— 商家在 B 端建的券顾客在哪都领不到。
 * 后端合流之后，这里守的是「入口真的挂上了」与「商品页没丢掉首屏只渲染一次」。
 */
import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

const read = (rel: string) => readFileSync(resolve(__dirname, "..", rel), "utf-8")
  .replace(/<!--[\s\S]*?-->/g, "");

describe("三个页面挂同一个领券条", () => {
  it.each(["src/pages/goods/index.vue", "src/pages/store/index.vue", "src/pages/merchant/index.vue"])(
    "★★★ %s", (p) => {
      expect(read(p)).toContain("<biz-coupon-strip");
    });

  it("★★ 商品页把预取的券传进去 —— 组件自己晚取会把整页顶一下", () => {
    expect(read("src/pages/goods/index.vue")).toMatch(/<biz-coupon-strip[^>]*:preset="coupons"/);
  });

  it("商品页不再留一份自己的领券逻辑", () => {
    const g = read("src/pages/goods/index.vue");
    expect(g).not.toContain("function claim(");
    expect(g).not.toContain("showCoupons");
  });
});

describe("券包不重复", () => {
  it("★★★ 两份来源按券号去重", () => {
    expect(read("src/pages/coupons/index.vue")).toContain("!seen.has(u.userCouponNo)");
  });
});
