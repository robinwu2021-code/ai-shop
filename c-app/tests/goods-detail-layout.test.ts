/**
 * 商品详情页重排（2026-09-19，原型 + 用户逐条拍板）后的几条规矩。
 *
 * 每条都是「说」与「不说」的边界 —— 两个方向都要钉：
 * 该说的没说，买家少一条判断依据；不该说的说了（「已售 0」「暂无评价」「不限购」），
 * 首屏就在反复告诉他「没有」。
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import { FULFILLMENT } from "@shared/utils/constants";
import type { Goods } from "@shared/types";

const goodsDetail = vi.fn();
const cartAdd = vi.fn();
const nativeShare = { yes: false };

vi.mock("@/api", () => ({
  api: {
    goodsDetail: (...a: unknown[]) => goodsDetail(...a),
    couponList: vi.fn(() => Promise.resolve([])),
    goodsGroup: vi.fn(() => Promise.resolve(null)),
    goodsBatch: vi.fn(() => Promise.resolve(null)),
    cartList: vi.fn(() => Promise.resolve([])),
    cartAdd: (...a: unknown[]) => cartAdd(...a),
    reviewList: vi.fn(() => Promise.resolve({ records: [], total: 0 })),
    toggleReviewLike: vi.fn(),
  },
}));
vi.mock("@shared/ports/share", () => ({
  buildShareMessage: vi.fn(() => ({})),
  canNativeShare: () => nativeShare.yes,
}));
vi.mock("vue-i18n", () => ({ useI18n: () => ({ t: (k: string) => k }) }));
vi.mock("@dcloudio/uni-app", () => ({
  onLoad: (cb: (q: Record<string, string>) => unknown) => cb({ goodsNo: "G1" }),
  onShow: vi.fn(), onHide: vi.fn(), onUnload: vi.fn(),
  onPullDownRefresh: vi.fn(), onReachBottom: vi.fn(), onShareAppMessage: vi.fn(),
}));
vi.mock("@/shared/fly", () => ({
  flyToCart: vi.fn(), tapPoint: () => ({ x: 0, y: 0 }),
  setCartAnchor: vi.fn(), clearCartAnchor: vi.fn(), registerCartAnchor: vi.fn(),
  flyState: { visible: false },
}));

import GoodsPage from "@/pages/goods/index.vue";
import MerchantBar from "@/components/biz/biz-merchant-bar.vue";

function goods(over: Partial<Goods> = {}): Goods {
  return {
    goodsNo: "G1", title: "香梨", subtitle: "", cover: "🍐", type: "GOODS",
    price: 5000, sales: 0, limitPerUser: 0, onSale: true,
    fulfillments: [FULFILLMENT.PICKUP],
    specGroups: [{ name: "重量", options: ["约10斤"] }],
    skus: [{ skuNo: "S1", optionValues: ["约10斤"], spec: "约10斤", price: 5000, stock: 100 }],
    merchant: { merchantNo: "M1", name: "虹选鲜果", logo: "🏪", rating: 0, ratingCount: 0, verified: true },
    promotions: [], params: [],
    ...over,
  } as unknown as Goods;
}

async function render() {
  const w = mount(GoodsPage, {
    global: {
      stubs: {
        "sh-scaffold": { template: "<div><slot /></div>" },
        "sh-actionbar": { template: "<div><slot /></div>" },
        "sh-icon": true, "sh-chip": true, "sh-cover": true, "sh-rating": true, "biz-review": true,
      },
      mocks: { $t: (k: string) => k },
    },
  });
  for (let i = 0; i < 10; i++) {
    await Promise.resolve();
    await w.vm.$nextTick();
  }
  return w;
}

/** 底栏上的按钮（按文字认，不按位置） */
function barBtn(w: ReturnType<typeof mount>, key: string) {
  return w.findAll(".actionbar__add, .actionbar__buy").find((b) => b.text().includes(key))!;
}

describe("商品详情页重排", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    cartAdd.mockResolvedValue([]);
    nativeShare.yes = false;
  });

  it("★★★ 已售 0 不说，已售 > 0 才说 —— 零销量是个劝退信号", async () => {
    goodsDetail.mockResolvedValue(goods({ sales: 0 }));
    expect((await render()).html()).not.toContain("common.sold");
    goodsDetail.mockResolvedValue(goods({ sales: 12 }));
    expect((await render()).html()).toContain("common.sold");
  });

  it("★★★ 库存 ≤ 10 在规格面板里说「仅剩 N 件」，> 10 不说 —— 平时的库存数对买家没有意义", async () => {
    const openSheet = async (stock: number) => {
      goodsDetail.mockResolvedValue(goods({
        skus: [{ skuNo: "S1", optionValues: ["约10斤"], spec: "约10斤", price: 5000, stock }] as never,
      }));
      const w = await render();
      await w.findAll(".row").find((r) => r.text().includes("goods.chosen"))!.trigger("tap");
      await w.vm.$nextTick();
      return w.html();
    };
    expect(await openSheet(3)).toContain("goods.lowStock");
    expect(await openSheet(10)).toContain("goods.lowStock");
    expect(await openSheet(11)).not.toContain("goods.lowStock");
    // 页面本身再也不出「库存 N」
    expect(await openSheet(100)).not.toContain("goods.stock");
  });

  it("★★★ 单规格：点「加入购物车」直接加 1 件，不弹面板", async () => {
    goodsDetail.mockResolvedValue(goods());
    const w = await render();
    await barBtn(w, "goods.addCart").trigger("tap");
    await w.vm.$nextTick();
    expect(cartAdd).toHaveBeenCalledWith("G1", "S1", 1);
    expect(w.find(".skuhead").exists(), "单规格不该弹面板").toBe(false);
  });

  it("★★★ 多规格：点「加入购物车」先弹面板，不直接加", async () => {
    goodsDetail.mockResolvedValue(goods({
      specGroups: [{ name: "重量", options: ["约10斤", "约5斤"] }],
      skus: [
        { skuNo: "S1", optionValues: ["约10斤"], spec: "约10斤", price: 5000, stock: 100 },
        { skuNo: "S2", optionValues: ["约5斤"], spec: "约5斤", price: 2800, stock: 100 },
      ] as never,
    }));
    const w = await render();
    await barBtn(w, "goods.addCart").trigger("tap");
    await w.vm.$nextTick();
    expect(cartAdd).not.toHaveBeenCalled();
    expect(w.find(".skuhead").exists(), "多规格要先弹面板").toBe(true);
  });

  it("★★★ 详情页不说配送：「送至」「配送」是订单的事，只留销售范围（2026-09-19 用户拍板）", async () => {
    goodsDetail.mockResolvedValue(goods({
      fulfillments: [FULFILLMENT.EXPRESS, FULFILLMENT.PICKUP],
      arrivalDesc: "次日 16 点后可提",
      saleScope: { unlimited: false, areaNames: ["深圳市"], areaCount: 1 },
    } as Partial<Goods>));
    const html = (await render()).html();
    for (const k of ["goods.shipTo", "goods.shipVia", "goods.pickAddress", "fulfillment.", "次日 16 点后可提"]) {
      expect(html, `详情页出现了配送信息 ${k}`).not.toContain(k);
    }
    expect(html).toContain("goods.scopeShort");
    expect(html).toContain("深圳市");
  });

  it("★★ 限购只在真有限购时出现 ——「限购：不限购」是一行什么都没说的话", async () => {
    goodsDetail.mockResolvedValue(goods({ limitPerUser: 0 }));
    expect((await render()).html()).not.toContain("goods.limitLabel");
    goodsDetail.mockResolvedValue(goods({ limitPerUser: 2 }));
    expect((await render()).html()).toContain("goods.limitLabel");
  });

  it("★★ 没人评过时详情页不说「暂无评价」", async () => {
    goodsDetail.mockResolvedValue(goods());
    expect((await render()).html()).not.toContain("merchant.noRating");
  });

  it("★★ 分享：小程序里是原生分享按钮（open-type=share），H5 不出", async () => {
    goodsDetail.mockResolvedValue(goods());
    expect((await render()).find(".actionbar__share").exists()).toBe(false);
    nativeShare.yes = true;
    const w = await render();
    expect(w.find(".actionbar__share").exists()).toBe(true);
    expect(w.find(".actionbar__share").attributes("open-type")).toBe("share");
  });

  it("★★ 商家条不传 quiet-no-rating 时照旧说「暂无评价」—— 商家列表 / 搜索页横向比较时它有意义", () => {
    const m = { merchantNo: "M1", name: "虹选鲜果", logo: "🏪", rating: 0, ratingCount: 0, verified: false };
    const plain = mount(MerchantBar, { props: { merchant: m as never }, global: { mocks: { $t: (k: string) => k }, stubs: { "sh-rating": true } } });
    expect(plain.html()).toContain("merchant.noRating");
    const quiet = mount(MerchantBar, { props: { merchant: m as never, quietNoRating: true }, global: { mocks: { $t: (k: string) => k }, stubs: { "sh-rating": true } } });
    expect(quiet.html()).not.toContain("merchant.noRating");
  });
});
