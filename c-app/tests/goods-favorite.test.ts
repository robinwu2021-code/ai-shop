/**
 * 商品详情：收藏（原型 g07）与「送不到时」（原型 g05）。TDD-C端商品收藏与送达判断。
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import { FULFILLMENT } from "@shared/utils/constants";
import type { Goods } from "@shared/types";

const goodsDetail = vi.fn();
const toggleFavoriteGoods = vi.fn();

vi.mock("@/api", () => ({
  api: {
    goodsDetail: (...a: unknown[]) => goodsDetail(...a),
    toggleFavoriteGoods: (...a: unknown[]) => toggleFavoriteGoods(...a),
    couponList: vi.fn(() => Promise.resolve([])),
    goodsGroup: vi.fn(() => Promise.resolve(null)),
    goodsBatch: vi.fn(() => Promise.resolve(null)),
    cartList: vi.fn(() => Promise.resolve([])),
    cartAdd: vi.fn(),
    reviewList: vi.fn(() => Promise.resolve({ records: [], total: 0 })),
    toggleReviewLike: vi.fn(),
  },
}));
vi.mock("@shared/ports/share", () => ({ buildShareMessage: vi.fn(() => ({})), canNativeShare: () => false }));
vi.mock("vue-i18n", () => ({ useI18n: () => ({ t: (k: string) => k }) }));
vi.mock("@dcloudio/uni-app", () => ({
  onLoad: (cb: (q: Record<string, string>) => unknown) => cb({ goodsNo: "G1" }),
  onShow: vi.fn(), onHide: vi.fn(), onUnload: vi.fn(), onPullDownRefresh: vi.fn(),
  onReachBottom: vi.fn(), onShareAppMessage: vi.fn(), onPageScroll: vi.fn(),
}));
vi.mock("@/shared/fly", () => ({
  flyToCart: vi.fn(), tapPoint: () => ({ x: 0, y: 0 }), setCartAnchor: vi.fn(),
  clearCartAnchor: vi.fn(), registerCartAnchor: vi.fn(), flyState: { visible: false },
}));

import GoodsPage from "@/pages/goods/index.vue";
import { useUserStore } from "@/stores/user";
import { useCommunityStore } from "@/stores/community";

function goods(over: Partial<Goods> = {}): Goods {
  return {
    goodsNo: "G1", title: "香梨", subtitle: "", cover: "🍐", type: "GOODS", price: 5000, sales: 0,
    limitPerUser: 0, onSale: true, fulfillments: [FULFILLMENT.EXPRESS],
    specGroups: [{ name: "重量", options: ["约10斤"] }],
    skus: [{ skuNo: "S1", optionValues: ["约10斤"], spec: "约10斤", price: 5000, stock: 100 }],
    merchant: { merchantNo: "M1", name: "虹选鲜果", logo: "", rating: 0, ratingCount: 0, verified: true },
    promotions: [], params: [], saleScope: { unlimited: false, areaNames: ["深圳市"], areaCount: 1 },
    ...over,
  } as unknown as Goods;
}

async function render() {
  const w = mount(GoodsPage, {
    global: {
      stubs: {
        "sh-scaffold": { template: "<div><slot /></div>" }, "sh-actionbar": { template: "<div><slot /></div>" },
        "sh-icon": true, "sh-cover": true, "sh-rating": true, "biz-review": true,
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

const favBtn = (w: Awaited<ReturnType<typeof render>>) =>
  w.findAll(".titlerow__act").find((b) => /goods\.favorite/.test(b.text()))!;

describe("商品详情 · 收藏与送达", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
  });

  it("★★★ AC1 点收藏：以后端回的状态为准，字变「已收藏」", async () => {
    useUserStore().token = "ctk_x";
    goodsDetail.mockResolvedValue(goods({ favorited: false }));
    toggleFavoriteGoods.mockResolvedValue({ favorited: true });
    const w = await render();
    expect(favBtn(w).text()).toContain("goods.favorite");
    await favBtn(w).trigger("tap");
    await new Promise((r) => setTimeout(r, 0));
    await w.vm.$nextTick();
    expect(toggleFavoriteGoods).toHaveBeenCalledWith("G1");
    expect(favBtn(w).text()).toContain("goods.favorited");
  });

  it("★★★ AC5 送不到（deliverable=false）：说为什么、给「换地址」、按钮点不动", async () => {
    goodsDetail.mockResolvedValue(goods({ deliverable: false }));
    const w = await render();
    expect(w.text()).toContain("goods.whyOutOfScope");
    expect(w.text()).toContain("goods.changeAddress");
    const btns = w.findAll(".actionbar__add, .actionbar__buy").filter((b) => !b.element.closest(".sheetbar"));
    expect(btns.every((b) => b.classes().includes("is-disabled"))).toBe(true);
  });

  it("★★★ AC6 没判（deliverable 缺省 / null）不拦 —— 「没判」不是「送不到」", async () => {
    for (const d of [undefined, null]) {
      goodsDetail.mockResolvedValue(goods({ deliverable: d }));
      const w = await render();
      expect(w.text()).not.toContain("goods.whyOutOfScope");
      const btns = w.findAll(".actionbar__add, .actionbar__buy").filter((b) => !b.element.closest(".sheetbar"));
      expect(btns.some((b) => b.classes().includes("is-disabled"))).toBe(false);
    }
  });

  it("★★ 详情请求带上收货地址推出来的社区号；没有就不带", async () => {
    goodsDetail.mockResolvedValue(goods());
    await render();
    expect(goodsDetail).toHaveBeenLastCalledWith("G1", undefined);
    useCommunityStore().community = { communityNo: "C0001" } as never;
    await render();
    expect(goodsDetail).toHaveBeenLastCalledWith("G1", "C0001");
  });
});
