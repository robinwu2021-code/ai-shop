/**
 * 门店门户（TDD-C端门店化与门店门户 s03–s07，AC1 / AC7 / AC11）。
 *
 * 钉三件「看起来都对、其实错了也不报错」的事：
 * - 门头写的是**门店名**，不是主体名（主体名只在「经营主体与资质」那一行露面）；
 * - 买过的人先看到「我常买」一栏（老客三步下单），没买过的人没有这一栏 —— 不留一个空标题；
 * - 分类横排、商品单列，**不做左右分栏**（2026-09-29 用户定 B 版）；落款行不写店名；
 * - 头图的底只收 http(s) 图 —— emoji 封面当 image 的 src 是一张裂图；
 * - 售罄的货**照列、不藏** —— 藏起来他会以为这家店没有这件货；暂停营业的店整页压淡、给隔壁店。
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import type { FrequentItem, Goods, StoreHome } from "@shared/types";

const storeHome = vi.fn();
const frequentItems = vi.fn();

vi.mock("@/api", () => ({
  api: {
    storeHome: (...a: unknown[]) => storeHome(...a),
    frequentItems: (...a: unknown[]) => frequentItems(...a),
    storeEnter: vi.fn(() => Promise.resolve()),
    storeByCode: vi.fn(),
    reviewList: vi.fn(() => Promise.resolve([])),
    reachOpened: vi.fn(() => Promise.resolve()),
    cartList: vi.fn(() => Promise.resolve([])),
    couponList: vi.fn(() => Promise.resolve([])),
  },
}));
vi.mock("@shared/ports/share", () => ({
  buildShareMessage: vi.fn(() => ({})),
  buildShareTimeline: vi.fn(() => ({})),
  canNativeShare: () => false,
  withAttribution: (p: string) => p,
}));
vi.mock("vue-i18n", () => ({ useI18n: () => ({ t: (k: string) => k }) }));
vi.mock("@dcloudio/uni-app", () => ({
  onLoad: (cb: (q: Record<string, string>) => unknown) => cb({ no: "ST1", from: "LIST" }),
  onShow: vi.fn(), onShareAppMessage: vi.fn(), onShareTimeline: vi.fn(), onPageScroll: vi.fn(),
}));
vi.mock("@/shared/fly", () => ({ flyToCart: vi.fn(), tapPoint: () => ({ x: 0, y: 0 }) }));

import StorePage from "@/pages/store/index.vue";
import GoodsCard from "@/components/biz/biz-goods-card.vue";

function aGoods(no: string, over: Partial<Goods> = {}): Goods {
  return {
    goodsNo: no, title: `货${no}`, subtitle: "", cover: "🍐", type: "GOODS", price: 1000, sales: 1,
    categoryNo: "C1", onSale: true, fulfillments: [], specGroups: [], promotions: [], params: [],
    skus: [{ skuNo: `${no}-S`, optionValues: [], spec: "", price: 1000, stock: 10 }],
    merchant: { merchantNo: "M1", name: "虹选科技有限公司", logo: "", rating: 0, ratingCount: 0, verified: true },
    ...over,
  } as unknown as Goods;
}

function home(over: Partial<StoreHome> = {}): StoreHome {
  return {
    merchant: { merchantNo: "M1", name: "虹选科技有限公司", logo: "", rating: 0, ratingCount: 0, verified: true },
    store: { announcement: "", openHours: "08:00-20:00", address: "景田北街 12 号" },
    goods: [aGoods("G1"), aGoods("G2", { skus: [{ skuNo: "G2-S", optionValues: [], spec: "", price: 1000, stock: 0 }] } as never)],
    categories: [{ categoryNo: "C1", name: "水果", count: 2 }],
    favorited: false,
    closed: false,
    portal: {
      storeNo: "ST1", storeName: "虹选鲜果·福田店", status: "ACTIVE", isDefault: false,
      openNow: true, rating: 4.9, ratingCount: 12, distanceM: 320,
    },
    sibling: null,
    ...over,
  } as unknown as StoreHome;
}

const bought: FrequentItem = {
  goodsNo: "G1", skuNo: "G1-S", title: "货G1", cover: "", spec: "", price: 1000, lastPrice: 1000,
  times: 3, lastAt: 1, invalid: false,
} as FrequentItem;

async function render() {
  const w = mount(StorePage, {
    global: {
      // 单列行真实渲染：「售罄照列」「不写店名」的判据要看行上写了什么
      components: { "biz-goods-card": GoodsCard },
      stubs: {
        "sh-scaffold": { template: "<div><slot /></div>" },
        "sh-tabs": true, "sh-icon": true, "sh-cover": true, "sh-empty": true, "sh-sheet": true,
        "biz-shop-avatar": true, "biz-share-act": true, "biz-coupon-strip": true, "biz-cart-fab": true,
        "biz-poster": true, "biz-review": true, "scroll-view": { template: "<div><slot /></div>" },
      },
      mocks: { $t: (k: string, a?: Record<string, unknown>) => (a?.name ? `${k}:${a.name}` : k) },
    },
  });
  for (let i = 0; i < 10; i++) {
    await Promise.resolve();
    await w.vm.$nextTick();
  }
  return w;
}

describe("门店门户", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
  });

  it("★★★ 门头写门店名，页面上不出现主体名", async () => {
    storeHome.mockResolvedValue(home());
    frequentItems.mockResolvedValue([]);
    const w = await render();
    expect(w.find(".head__name").text()).toBe("虹选鲜果·福田店");
    expect(w.html()).not.toContain("虹选科技有限公司");
  });

  it("★★★ 买过的人先看到「我常买」一栏；没买过的人没有这一栏", async () => {
    storeHome.mockResolvedValue(home());
    frequentItems.mockResolvedValue([bought]);
    const old = await render();
    expect(old.find(".freq").exists()).toBe(true);
    expect(old.findAll(".freq__card")[0]!.text()).toContain("货G1");

    frequentItems.mockResolvedValue([]);
    const fresh = await render();
    expect(fresh.find(".freq").exists(), "没买过就不留一个空标题").toBe(false);
  });

  it("★★★ 不做左右分栏：分类横排（全部 + 店主货架），商品单列、行上不写店名", async () => {
    storeHome.mockResolvedValue(home());
    frequentItems.mockResolvedValue([]);
    const w = await render();
    expect(w.find(".rail").exists(), "左栏回来了").toBe(false);
    const tabs = w.findComponent({ name: "sh-tabs" });
    const items = (tabs.exists() ? tabs.props("items") : w.find(".cats sh-tabs-stub").attributes("items")) as unknown;
    expect(JSON.stringify(items)).toContain("store.allCats");
    expect(JSON.stringify(items)).toContain("水果");
    const rows = w.findAll(".list .card");
    expect(rows).toHaveLength(2);
    expect(rows[0]!.text(), "整页都是这一家店，每行再写一遍是噪声").not.toContain("虹选科技有限公司");
  });

  it("★★★ 售罄的货照列、不藏：行上写售罄，没有加号", async () => {
    storeHome.mockResolvedValue(home());
    frequentItems.mockResolvedValue([]);
    const w = await render();
    const rows = w.findAll(".list .card");
    expect(rows, "售罄的那件也要列出来").toHaveLength(2);
    const sold = rows.filter((t) => t.text().includes("goods.soldOut"));
    expect(sold).toHaveLength(1);
    expect(sold[0]!.find(".add").exists()).toBe(false);
  });

  it("★★★ 头图的底取卖得最好的那件真图；只有 emoji 封面时不给图、退回品牌色", async () => {
    storeHome.mockResolvedValue(home({
      goods: [
        aGoods("G1", { cover: "https://img.example.com/a.jpg", sales: 3 }),
        aGoods("G2", { cover: "🍐", sales: 99 }),
        aGoods("G3", { cover: "https://img.example.com/c.jpg", sales: 50 }),
      ],
    } as Partial<StoreHome>));
    frequentItems.mockResolvedValue([]);
    const w = await render();
    expect(w.find(".hero__img").attributes("src"), "emoji 那件销量最高也不能拿来当图").toContain("c.jpg");

    storeHome.mockResolvedValue(home());
    const plain = await render();
    expect(plain.find(".hero__img").exists(), "emoji 当 src 是一张裂图").toBe(false);
    expect(plain.find(".hero").exists()).toBe(true);
  });

  it("★★ 暂停营业：整页商品不可加购，并给同品牌的营业店", async () => {
    storeHome.mockResolvedValue(home({
      closed: true,
      portal: { storeNo: "ST1", storeName: "虹选鲜果·车公庙店", status: "READONLY", isDefault: false,
        openNow: null, rating: 0, ratingCount: 0, distanceM: null },
      sibling: { storeNo: "ST2", storeName: "虹选鲜果·福田店", distanceM: 2400 },
    } as Partial<StoreHome>));
    frequentItems.mockResolvedValue([]);
    const w = await render();
    expect(w.find(".shelf.is-paused").exists(), "整片商品压淡").toBe(true);
    expect(w.find(".paused__go").text()).toContain("虹选鲜果·福田店");
  });
});
