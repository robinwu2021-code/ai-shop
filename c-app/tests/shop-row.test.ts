/**
 * 店铺列表的一行（biz-shop-row）与店铺详情的商品格（biz-goods-tile）。
 *
 * 店铺页此前三档三副长相，零评价的店写「暂无评价」（在说「没有」，对新店是劝退），
 * 整行可点却还挂一颗「进店 ›」。店铺详情里每张商品卡都再写一遍店名。
 */
import { describe, expect, it, vi } from "vitest";
import { mount } from "@vue/test-utils";
import type { Goods, Merchant } from "@shared/types";

vi.mock("vue-i18n", () => ({ useI18n: () => ({ t: (k: string) => k }) }));

import ShopRow from "@/components/biz/biz-shop-row.vue";
import GoodsTile from "@/components/biz/biz-goods-tile.vue";

const stubs = { "sh-icon": true, "sh-cover": true, "biz-shop-avatar": true };
const mocks = { $t: (k: string) => k };
const m = (o: Partial<Merchant> = {}): Merchant =>
  ({
    merchantNo: "M1", name: "虹选鲜果", logo: "", desc: "当季鲜果", selfOperated: true, verified: true,
    rating: 4.02, ratingCount: 0, salesCount: 0, serviceScope: "CITY", distance: 0, tags: [],
    ...o,
  }) as unknown as Merchant;
const row = (props: Record<string, unknown>) => mount(ShopRow, { props, global: { stubs, mocks } });

describe("店铺行", () => {
  it("★★★ 没人评过 → 「新店」，不出分数也不出「暂无评价」", () => {
    const w = row({ merchant: m() });
    expect(w.text()).toContain("shops.newShop");
    expect(w.text()).not.toContain("★");
    expect(w.text()).not.toContain("merchant.noRating");
  });

  it("★★★ 有评价 → 一位小数的分 + 单数", () => {
    const w = row({ merchant: m({ ratingCount: 3, salesCount: 12 }) });
    expect(w.text()).toContain("4.0 ★ · shops.orders");
    expect(w.text()).not.toContain("shops.newShop");
  });

  it("★★ 自营出标、放在店名前；第三方不出", () => {
    const self = row({ merchant: m() }).text();
    expect(self.indexOf("merchant.selfOperated")).toBeLessThan(self.indexOf("虹选鲜果"));
    expect(row({ merchant: m({ selfOperated: false }) }).text()).not.toContain("merchant.selfOperated");
  });

  it("★★ 整行可点，不再挂「进店 ›」", async () => {
    const w = row({ merchant: m() });
    expect(w.text()).not.toContain("merchant.enter");
    await w.trigger("tap");
    expect(w.emitted("tap")).toHaveLength(1);
  });

  it("★ 「我买过的」用 meta 换掉第二行", () => {
    expect(row({ merchant: m(), meta: "买过 2 单" }).text()).toContain("买过 2 单");
  });
});

describe("店铺详情的商品格", () => {
  const g = (stock: number): Goods =>
    ({
      goodsNo: "G1", title: "香梨", cover: "", type: "FRESH", price: 5000, originPrice: 5500, sales: 0,
      skus: [{ skuNo: "S1", optionValues: [], spec: "", price: 5000, stock }],
      merchant: { merchantNo: "M1", name: "虹选鲜果", logo: "" },
    }) as unknown as Goods;
  const tile = (stock: number) => mount(GoodsTile, { props: { goods: g(stock) }, global: { stubs, mocks } });

  it("★★★ 不再写店名", () => {
    expect(tile(5).text()).not.toContain("虹选鲜果");
  });

  it("★★ 售罄换掉「＋」；点「＋」只发 add 不发 tap", async () => {
    expect(tile(0).find(".add").exists()).toBe(false);
    const w = tile(5);
    await w.find(".add").trigger("tap");
    expect(w.emitted("add")).toHaveLength(1);
    expect(w.emitted("tap")).toBeUndefined();
  });
});

describe("商品详情里的商家条", () => {
  it("★★ 认证是图标不是文字 chip，整条可点、不再写「进店 ›」", async () => {
    const { default: Bar } = await import("@/components/biz/biz-merchant-bar.vue");
    const w = mount(Bar, { props: { merchant: m() }, global: { stubs, mocks } });
    expect(w.text()).not.toContain("merchant.verified");
    expect(w.text()).not.toContain("merchant.enter");
    expect(w.find('sh-icon-stub[name="verified"]').exists()).toBe(true);
    expect(w.text().indexOf("merchant.selfOperated")).toBeLessThan(w.text().indexOf("虹选鲜果"));
  });
});
