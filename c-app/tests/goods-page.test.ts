/**
 * 商品详情页的**数量上限**与**买不了的原因**。
 *
 * <p>逐项复核交互清单时查出两条，都不报错：
 * <ol>
 *   <li><b>数量只封限购、不封库存</b> —— 库存 3 件也能设成 50。加购不拦、
 *       试算也不拦（预览刻意不锁库存），一路走到提交才被后端的锁库存拒，
 *       而那时他已经填完了整页。</li>
 *   <li><b>四条禁用原因里有一条不说话</b> —— 没选预约时段时两个按钮双双变灰，
 *       而屏幕上一个字都没有。（售罄写在按钮文案里、过了截单有一枚红 chip，
 *       只有这一条什么都没有。）</li>
 * </ol>
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import { FULFILLMENT } from "@shared/utils/constants";
import type { Goods } from "@shared/types";

const goodsDetail = vi.fn();
const cartAdd = vi.fn();

vi.mock("@/api", () => ({
  api: {
    goodsDetail: (...a: unknown[]) => goodsDetail(...a),
    cartList: vi.fn(() => Promise.resolve([])),
    cartAdd: (...a: unknown[]) => cartAdd(...a),
    reviewList: vi.fn(() => Promise.resolve({ records: [], total: 0 })),
    toggleReviewLike: vi.fn(),
  },
}));
vi.mock("vue-i18n", () => ({ useI18n: () => ({ t: (k: string) => k }) }));
vi.mock("@dcloudio/uni-app", () => ({
  onLoad: (cb: (q: Record<string, string>) => unknown) => cb({ goodsNo: "G1" }),
  onShow: vi.fn(),
  onHide: vi.fn(),
  onUnload: vi.fn(),
  onPullDownRefresh: vi.fn(),
  onReachBottom: vi.fn(),
  onShareAppMessage: vi.fn(),
}));
vi.mock("@/shared/fly", () => ({
  flyToCart: vi.fn(),
  tapPoint: () => ({ x: 0, y: 0 }),
  setCartAnchor: vi.fn(),
  clearCartAnchor: vi.fn(),
  registerCartAnchor: vi.fn(),
  flyState: { visible: false },
}));

import GoodsPage from "@/pages/goods/index.vue";

function goods(over: Partial<Goods> = {}): Goods {
  return {
    goodsNo: "G1",
    title: "米",
    subtitle: "脆甜多汁",
    cover: "🍚",
    type: "GOODS",
    price: 2980,
    sales: 10,
    limitPerUser: 0,
    onSale: true,
    fulfillments: [FULFILLMENT.PICKUP],
    specGroups: [{ name: "规格", options: ["5kg"] }],
    skus: [{ skuNo: "S1", optionValues: ["5kg"], spec: "5kg", price: 2980, stock: 3 }],
    merchant: { merchantNo: "M1", name: "老张粮油店", logo: "🏪" },
    promotions: [],
    params: [],
    ...over,
  } as unknown as Goods;
}

async function render() {
  const w = mount(GoodsPage, {
    global: {
      stubs: {
        "sh-scaffold": { template: "<div><slot /></div>" },
        "sh-actionbar": { template: "<div><slot /></div>" },
        "sh-icon": true,
        "sh-chip": true,
        "sh-cover": true,
        "sh-rating": true,
        "biz-review": true,
        "biz-merchant-bar": true,
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

/** 数量步进器的两个按钮（页面里还有别的 sh-btn，按 class 精确取） */
function steppers(w: ReturnType<typeof mount>) {
  return w.findAll(".stepper__btn");
}

describe("商品详情页", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    cartAdd.mockResolvedValue([]);
  });

  it("★★★ 数量加不过可售库存 —— 此前只封限购，库存 3 件也能设成 50", async () => {
    goodsDetail.mockResolvedValue(goods());   // stock = 3
    const w = await render();
    const plus = steppers(w).at(-1)!;

    for (let i = 0; i < 8; i++) {
      await plus.trigger("tap");
      await w.vm.$nextTick();
    }
    expect(w.find(".stepper__num").text()).toBe("3");
  });

  it("★★★ 限购比库存小时按限购封 —— 两个上限取小，不是二选一", async () => {
    goodsDetail.mockResolvedValue(goods({ limitPerUser: 2 }));  // stock 3、限购 2
    const w = await render();
    const plus = steppers(w).at(-1)!;

    for (let i = 0; i < 6; i++) {
      await plus.trigger("tap");
      await w.vm.$nextTick();
    }
    expect(w.find(".stepper__num").text()).toBe("2");
  });

  it("★★ 后端没给库存时不设上限 —— 缺省当 0 会让整件商品都买不了", async () => {
    goodsDetail.mockResolvedValue(
      goods({ skus: [{ skuNo: "S1", optionValues: ["5kg"], spec: "5kg", price: 2980 }] as never }),
    );
    const w = await render();
    const plus = steppers(w).at(-1)!;

    for (let i = 0; i < 4; i++) {
      await plus.trigger("tap");
      await w.vm.$nextTick();
    }
    expect(w.find(".stepper__num").text()).toBe("5");
  });

  it("★★★ 换规格后数量要回落 —— 不回落的话屏幕上是一个当场买不成的数", async () => {
    goodsDetail.mockResolvedValue(
      goods({
        specGroups: [{ name: "规格", options: ["5kg", "10kg"] }] as never,
        skus: [
          { skuNo: "S1", optionValues: ["5kg"], spec: "5kg", price: 2980, stock: 99 },
          { skuNo: "S2", optionValues: ["10kg"], spec: "10kg", price: 5580, stock: 2 },
        ] as never,
      }),
    );
    const w = await render();
    const plus = steppers(w).at(-1)!;
    for (let i = 0; i < 5; i++) {
      await plus.trigger("tap");
      await w.vm.$nextTick();
    }
    expect(w.find(".stepper__num").text()).toBe("6");

    // 切到只剩 2 件的那个规格
    const opt = w.findAll(".sh-seg").find((o) => o.text().includes("10kg"))!;
    await opt.trigger("tap");
    await w.vm.$nextTick();
    expect(w.find(".stepper__num").text()).toBe("2");
  });

  it("★★★ 预约类没选时段 → 页面上要说是为什么", async () => {
    goodsDetail.mockResolvedValue(
      goods({ type: "SERVICE", fulfillments: [FULFILLMENT.APPOINTMENT] } as never),
    );
    const w = await render();
    expect(w.text(), "两个按钮双双变灰而一个字都没有，是此前的样子").toContain("goods.whyNoSlot");
  });

  it("★★ 售罄不重复说 —— 按钮文案已经写着「已售罄」了", async () => {
    goodsDetail.mockResolvedValue(
      goods({ skus: [{ skuNo: "S1", optionValues: ["5kg"], spec: "5kg", price: 2980, stock: 0 }] as never }),
    );
    const w = await render();
    expect(w.text()).toContain("goods.soldOut");
    expect(w.text(), "同一件事说两遍会让人以为是两个问题").not.toContain("goods.whyNoSlot");
  });

  it("★ 一切正常时不说话", async () => {
    goodsDetail.mockResolvedValue(goods());
    const w = await render();
    expect(w.find(".why").exists()).toBe(false);
  });
});
