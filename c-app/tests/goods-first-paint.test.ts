/**
 * 商品详情页**点进来只渲染一次** —— 不许「先出一版、再跳一下」。
 *
 * 2026-09-19 用户报：点商品列表进详情，有一个跳动。逐次记录 DOM 变化量出来：
 * 详情先渲染（210ms），200ms 后领券行插进价格卡下面，把商家卡、规格整片推下 61px；
 * 同一刻底部按钮从「加入购物车 / 立即购买」换成「单买 / 开团」。
 * 根因是券、拼团、集单都等详情回来**之后**才去取，永远晚一拍。
 *
 * 这里把那三个接口故意安排得**比详情晚**，然后在每一个时刻检查：
 * 只要标题出来了，领券行和拼团按钮就必须已经在 —— 不存在「有标题、没领券」的那一帧。
 * 把 load() 退回「先给 goods 赋值、再去取」，第一条立刻变红。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import { FULFILLMENT } from "@shared/utils/constants";
import type { Goods } from "@shared/types";

const goodsDetail = vi.fn();
const couponList = vi.fn();
const goodsGroup = vi.fn();
const goodsBatch = vi.fn();

vi.mock("@/api", () => ({
  api: {
    goodsDetail: (...a: unknown[]) => goodsDetail(...a),
    couponList: (...a: unknown[]) => couponList(...a),
    goodsGroup: (...a: unknown[]) => goodsGroup(...a),
    goodsBatch: (...a: unknown[]) => goodsBatch(...a),
    cartList: vi.fn(() => Promise.resolve([])),
    cartAdd: vi.fn(),
    reviewList: vi.fn(() => Promise.resolve({ records: [], total: 0 })),
    toggleReviewLike: vi.fn(),
  },
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

const GOODS = {
  goodsNo: "G1", title: "香梨标题", subtitle: "", cover: "🍐", type: "GOODS",
  price: 5000, sales: 0, limitPerUser: 0, onSale: true,
  fulfillments: [FULFILLMENT.PICKUP],
  specGroups: [{ name: "重量", options: ["约10斤"] }],
  skus: [{ skuNo: "S1", optionValues: ["约10斤"], spec: "约10斤", price: 5000, stock: 100 }],
  merchant: { merchantNo: "M1", name: "虹选鲜果", logo: "🏪" },
  promotions: [], params: [],
} as unknown as Goods;

const COUPON = {
  couponNo: "C1", merchantNo: "M1", funder: "MERCHANT", type: "FULL_CUT",
  thresholdMinor: 5000, amountMinor: 500, endAt: Date.now() + 86_400_000, received: false,
};
const GROUP = { groupPrice: 500, minCount: 3, openGroups: [] };

/** 一个可以在测试里手动放行的 Promise */
function deferred<T>() {
  let resolve!: (v: T) => void;
  const p = new Promise<T>((r) => (resolve = r));
  return { p, resolve };
}

function mountPage() {
  return mount(GoodsPage, {
    global: {
      stubs: {
        "sh-scaffold": { template: "<div><slot /></div>" },
        "sh-actionbar": { template: "<div><slot /></div>" },
        "sh-sheet": { template: "<div><slot /></div>" },
        "sh-icon": true, "sh-chip": true, "sh-cover": true, "sh-rating": true,
        "biz-review": true, "biz-merchant-bar": true,
      },
      mocks: { $t: (k: string) => k },
    },
  });
}

async function flush(w: ReturnType<typeof mount>) {
  for (let i = 0; i < 8; i++) {
    await Promise.resolve();
    await w.vm.$nextTick();
  }
}

/** 这一刻的首屏：标题出来没有、领券行在不在、底部是不是拼团按钮 */
function frame(w: ReturnType<typeof mount>) {
  const html = w.html();
  return {
    title: html.includes("香梨标题"),
    // 只看**那一行**的文字：领券弹层的 title 属性里也有这个键，按整页 HTML 搜会恒为真
    couponRow: w.findAll(".row__label").some((e) => e.text() === "goods.couponRow"),
    groupBar: html.includes("goods.groupStart"),
  };
}

describe("商品详情首屏只渲染一次", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    goodsBatch.mockResolvedValue(null);
  });
  afterEach(() => vi.useRealTimers());

  it("★★★ 券与拼团比详情晚到时，也不存在「有标题、没领券 / 没拼团按钮」的那一帧", async () => {
    const coupons = deferred<unknown[]>();
    const group = deferred<unknown>();
    goodsDetail.mockResolvedValue(GOODS);      // 详情先到
    couponList.mockReturnValue(coupons.p);     // 券晚到
    goodsGroup.mockReturnValue(group.p);       // 拼团晚到

    const w = mountPage();
    await flush(w);
    // 详情已经回来了，但券和拼团还没有 —— 此刻页面不该先出一版
    const f1 = frame(w);
    expect(f1.title && !f1.couponRow, "标题出来了、领券行还没来 —— 这就是那一下跳").toBe(false);

    coupons.resolve([COUPON]);
    group.resolve(GROUP);
    await flush(w);
    const f2 = frame(w);
    expect(f2).toEqual({ title: true, couponRow: true, groupBar: true });
  });

  it("★★★ 三个补充接口都失败，页面照样出来 —— 补充信息不拖垮详情", async () => {
    goodsDetail.mockResolvedValue(GOODS);
    couponList.mockRejectedValue(new Error("500"));
    goodsGroup.mockRejectedValue(new Error("500"));
    goodsBatch.mockRejectedValue(new Error("500"));
    const w = mountPage();
    await flush(w);
    expect(frame(w)).toEqual({ title: true, couponRow: false, groupBar: false });
  });

  it("★★★ 调用本身同步抛错（接口不存在）也不能让整页空白", async () => {
    goodsDetail.mockResolvedValue(GOODS);
    couponList.mockImplementation(() => { throw new TypeError("not a function"); });
    goodsGroup.mockImplementation(() => { throw new TypeError("not a function"); });
    const w = mountPage();
    await flush(w);
    expect(frame(w).title).toBe(true);
  });

  it("★★ 补充接口一直不回，等满上限就先出页面；晚到的再补上", async () => {
    vi.useFakeTimers();
    const coupons = deferred<unknown[]>();
    goodsDetail.mockResolvedValue(GOODS);
    couponList.mockReturnValue(coupons.p);
    goodsGroup.mockResolvedValue(null);

    const w = mountPage();
    await flush(w);
    expect(frame(w).title, "还没到上限，不该先出").toBe(false);

    await vi.advanceTimersByTimeAsync(800);
    await flush(w);
    expect(frame(w)).toMatchObject({ title: true, couponRow: false });

    coupons.resolve([COUPON]);
    await flush(w);
    expect(frame(w).couponRow, "晚到的券要补上").toBe(true);
  });
});
