/**
 * 商品详情页重排（2026-09-19，原型 + 用户逐条拍板）后的几条规矩。
 *
 * 首屏「已选 / 配送 / 保障」这一块改过三次，**钉的是最新那次**，别改回去：
 *   2026-09-19 v2 去掉（还没决定买就问件数；配送是订单的事）
 *   2026-09-28 v3 恢复（参照淘宝京东首屏）
 *   2026-09-29 v4 再去掉 —— 这时他在看货、还没选：已选是替他做决定，配送在结算 / 订单页确认，
 *              保障等售后整块重新设计。海报收进分享面板（TDD-C端商品详情页v3 §v4）
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
import ShareAct from "@/components/biz/biz-share-act.vue";

const nativeShare = { yes: false };

vi.mock("@/api", () => ({
  api: {
    goodsDetail: (...a: unknown[]) => goodsDetail(...a),
    couponList: vi.fn(() => Promise.resolve([])),
    // 下单页改读「我的券」与最优券试算（B1）——
    // 替身缺了它们，挂载时抛 unhandled error：用例还是绿的，闸门才会红
    myCoupons: vi.fn(() => Promise.resolve([])),
    couponBest: vi.fn(() => Promise.resolve({ discountMinor: 0, usable: [], unusable: [] })),
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
  onShareTimeline: vi.fn(), onPageScroll: vi.fn(),
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
      /*
       * 分享入口**真实渲染**，不 stub：这一条判据问的正是「两端各画出了什么」，
       * stub 掉的话它永远绿（easycom 在单测里不生效，不注册就只是个未知标签）。
       */
      components: { "biz-share-act": ShareAct },
      stubs: {
        "sh-scaffold": { template: "<div><slot /></div>" },
        "sh-actionbar": { template: "<div><slot /></div>" },
        // 分享面板的内容要真的渲染出来：判据问的是「面板里有没有生成海报 / 原生转发」
        "sh-sheet": { props: ["visible"], template: "<div v-if=\"visible\"><slot /></div>" },
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

  it("★★★ 库存 ≤ 10 说「仅剩 N 件」，> 10 不说 —— 平时的库存数对买家没有意义", async () => {
    // v2：单规格不弹面板，这句在价格下的标签里说（面板里照旧也说）
    const openSheet = async (stock: number) => {
      goodsDetail.mockResolvedValue(goods({
        skus: [{ skuNo: "S1", optionValues: ["约10斤"], spec: "约10斤", price: 5000, stock }] as never,
      }));
      return (await render()).html();
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

  it("★★★ v4 首屏没有已选 / 配送 / 保障三行（2026-09-29，第三次决定）", async () => {
    goodsDetail.mockResolvedValue(goods({
      fulfillments: [FULFILLMENT.EXPRESS, FULFILLMENT.PICKUP],
      arrivalDesc: "次日 16 点后可提",
      services: ["INSTANT_REFUND"],
      saleScope: { unlimited: false, areaNames: ["深圳市"], areaCount: 1 },
    } as Partial<Goods>));
    const w = await render();
    const html = w.html();
    expect(w.find(".buycard").exists(), "选购卡整块不出").toBe(false);
    for (const k of ["goods.rowChosen", "goods.rowShip", "goods.rowService"]) {
      expect(html, `${k} 又出现在首屏`).not.toContain(k);
    }
    // 到货说明随「配送」一起去掉（配送方式在结算页选）
    expect(html).not.toContain("次日 16 点后可提");
    // 销售区域仍在商品参数里
    const params = w.find("#sec-detail").element.nextElementSibling!;
    expect(params.textContent).toContain("goods.scopeLabel");
    expect(params.textContent).toContain("深圳市");
  });

  it("★★ v4 商家写的「售后说明」先不出 —— 售后整块重新设计之前不给半套说法", async () => {
    goodsDetail.mockResolvedValue(goods({
      params: [
        { dimNo: "D1", name: "产地", label: "云南" },
        { dimNo: "D2", name: "售后说明", label: "坏果包赔" },
      ],
    } as never));
    const html = (await render()).html();
    expect(html).toContain("云南");
    expect(html).not.toContain("坏果包赔");
  });

  it("★★★ v4 多规格也不在首屏说共几种 —— 规格面板只从底栏两颗按钮打开", async () => {
    goodsDetail.mockResolvedValue(goods({
      specGroups: [{ name: "重量", options: ["约10斤", "约5斤"] }],
      skus: [
        { skuNo: "S1", optionValues: ["约10斤"], spec: "约10斤", price: 5000, stock: 100 },
        { skuNo: "S2", optionValues: ["约5斤"], spec: "约5斤", price: 2800, stock: 100 },
      ] as never,
    }));
    const w = await render();
    expect(w.html()).not.toContain("goods.specCount");
    expect(w.find(".skuhead").exists(), "进页面不该自己弹面板").toBe(false);
    await barBtn(w, "goods.buyNow").trigger("tap");
    await w.vm.$nextTick();
    expect(w.find(".skuhead").exists(), "从底栏打开面板").toBe(true);
  });

  it("★★★ v4 标题行没有「海报」入口；海报收进分享面板（朋友圈那条路一个像素都没少）", async () => {
    goodsDetail.mockResolvedValue(goods());
    const w = await render();
    const html = w.html();
    expect(html).not.toContain("poster.act");
    expect(w.findAll(".shareact"), "标题行只剩一颗分享").toHaveLength(1);
    await w.find(".shareact").trigger("tap");
    await w.vm.$nextTick();
    expect(w.html(), "面板里要有「生成海报」").toContain("share.poster");
    expect(w.html()).toContain("share.toFriend");
  });

  it("★★★ v3 底栏：店铺 · 购物车 · 两颗按钮；左上不再有购物车（2026-09-28 用户拍板）", async () => {
    goodsDetail.mockResolvedValue(goods());
    const w = await render();
    expect(w.findAll(".actionbar__icon"), "底栏图标位：店铺 + 购物车").toHaveLength(2);
    expect(w.find(".actionbar__cart").exists()).toBe(true);
    expect(w.find(".topbar .topbar__cart").exists(), "左上只留返回").toBe(false);
    expect(w.findAll(".actionbar__add, .actionbar__buy").filter((b) => !b.element.closest(".sheetbar"))).toHaveLength(2);
  });

  it("★★ v3 没有图文详情时用主图兜底；有长图时不兜底", async () => {
    goodsDetail.mockResolvedValue(goods({ cover: "https://x/c.jpg", images: ["https://x/a.jpg"] } as Partial<Goods>));
    expect((await render()).html()).toContain("goods.detailTitle");
    goodsDetail.mockResolvedValue(goods({ cover: "https://x/c.jpg", detailImages: ["https://x/d.jpg"] } as Partial<Goods>));
    const w = await render();
    expect(w.findAll(".dt__img")).toHaveLength(1);
  });

  it("★★ v3 评价头给好评率：4、5 星之和 / 总数", async () => {
    goodsDetail.mockResolvedValue(goods({
      reviewSummary: { total: 10, avg: 4.6, dist: [0, 1, 1, 3, 5], withImages: 2, avgGoods: 4.7, avgFulfillment: 4.5, avgService: 4.6 },
    } as Partial<Goods>));
    const html = (await render()).html();
    expect(html).toContain("goods.goodRate");
  });

  it("★★ v2 评价排在参数与图文之前（原型 g02）", async () => {
    goodsDetail.mockResolvedValue(goods());
    const html = (await render()).html();
    expect(html.indexOf('id="sec-reviews"')).toBeGreaterThan(-1);
    expect(html.indexOf('id="sec-reviews"')).toBeLessThan(html.indexOf('id="sec-detail"'));
  });

  it("★★ v2 面板底部只有叫出它的那一个动作（原型 g03）", async () => {
    goodsDetail.mockResolvedValue(goods({
      specGroups: [{ name: "重量", options: ["约10斤", "约5斤"] }] as never,
      skus: [
        { skuNo: "S1", optionValues: ["约10斤"], spec: "约10斤", price: 5000, stock: 100 },
        { skuNo: "S2", optionValues: ["约5斤"], spec: "约5斤", price: 2600, stock: 100 },
      ] as never,
    }));
    const w = await render();
    await barBtn(w, "goods.buyNow").trigger("tap");
    await w.vm.$nextTick();
    const sheetBtns = w.findAll(".sheetbar .sh-btn");
    expect(sheetBtns).toHaveLength(1);
    expect(sheetBtns[0]!.text()).toContain("goods.buyNow");
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

  it("★★ 分享：小程序走原生转发，**H5 也要有出口**（§3.2 起改口径）", async () => {
    /*
     * 这条判据以前是「H5 不出分享按钮」。那时的理由是「H5 上点了什么都不发生，
     * 画出来就是死按钮」—— 对的，但结论选错了：H5 没有胶囊菜单可以兜底，
     * 于是那一端**一个分享入口都没有**。现在 H5 走复制带归因的链接，入口始终在。
     *
     * 判据也跟着换：不再问「有没有那颗 open-type 按钮」，而是问
     * 「**两端都有入口**，且小程序那一端确实是原生转发」。
     */
    goodsDetail.mockResolvedValue(goods());
    const h5 = await render();
    expect(h5.find(".shareact").exists(), "H5 上没有任何分享入口").toBe(true);
    await h5.find(".shareact").trigger("tap");
    await h5.vm.$nextTick();
    expect(h5.find(".shareact__native").exists(), "H5 画了一颗点了没反应的原生按钮").toBe(false);
    expect(h5.html(), "H5 的「发给朋友」是复制链接").toContain("share.toFriendSubH5");

    nativeShare.yes = true;
    const mp = await render();
    // v4：分享在标题旁；点开面板，「发给朋友」那一块盖着原生转发按钮
    await mp.find(".shareact").trigger("tap");
    await mp.vm.$nextTick();
    expect(mp.find(".shareact__native").exists()).toBe(true);
    expect(mp.find(".shareact__native").attributes("open-type")).toBe("share");
  });

  it("★★ 商家条不传 quiet-no-rating 时照旧说「暂无评价」—— 商家列表 / 搜索页横向比较时它有意义", () => {
    const m = { merchantNo: "M1", name: "虹选鲜果", logo: "🏪", rating: 0, ratingCount: 0, verified: false };
    const plain = mount(MerchantBar, { props: { merchant: m as never }, global: { mocks: { $t: (k: string) => k }, stubs: { "sh-rating": true } } });
    expect(plain.html()).toContain("merchant.noRating");
    const quiet = mount(MerchantBar, { props: { merchant: m as never, quietNoRating: true }, global: { mocks: { $t: (k: string) => k }, stubs: { "sh-rating": true } } });
    expect(quiet.html()).not.toContain("merchant.noRating");
  });
});
