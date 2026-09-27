/**
 * 详情页的服务承诺条与参数抽屉（§3.2 / §3.4）。
 *
 * <p>两条都在守**承诺不许是空话**：
 * 服务条上那句「极速退款」由后端逐件判（价高于上限就不给），端上只显示；
 * 参数「前 4 条 + 全部」不能把剩下的弄丢 —— 抽屉里必须是完整的那一份。
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import type { Goods } from "@shared/types";

const goodsDetail = vi.fn();

vi.mock("@/api", () => ({
  api: {
    goodsDetail: (...a: unknown[]) => goodsDetail(...a),
    goodsGroup: vi.fn(async () => null),
    couponList: vi.fn(async () => []),
    goodsBatch: vi.fn(async () => null),
    reviewList: vi.fn(async () => []),
    favorited: vi.fn(async () => false),
    addCart: vi.fn(),
  },
}));
vi.mock("vue-i18n", () => ({ useI18n: () => ({ t: (k: string) => k }) }));
vi.mock("@dcloudio/uni-app", () => ({
  onLoad: (cb: (q: Record<string, string>) => unknown) => cb({ goodsNo: "G1" }),
  onShow: (cb: () => unknown) => cb(),
  onHide: vi.fn(),
  onPullDownRefresh: vi.fn(),
  onReachBottom: vi.fn(),
  onShareAppMessage: vi.fn(),
  onPageScroll: vi.fn(),
}));

import GoodsPage from "@/pages/goods/index.vue";

function aGoods(over: Partial<Goods> = {}): Goods {
  return {
    goodsNo: "G1",
    title: "柠檬",
    cover: "🍋",
    images: [],
    type: "FRESH",
    merchant: { merchantNo: "M1", name: "老张粮油店" },
    price: 1000,
    fulfillments: ["STORE_PICKUP"],
    specGroups: [],
    skus: [{ skuNo: "S1", spec: "1份", price: 1000, stock: 10, optionValues: [] }],
    sales: 0,
    params: [],
    ...over,
  } as unknown as Goods;
}

async function render() {
  const w = mount(GoodsPage, {
    global: {
      stubs: {
        "sh-scaffold": { template: "<div><slot /></div>" },
        "sh-icon": true,
        "sh-sheet": { props: ["visible"], template: "<div v-if='visible'><slot /></div>" },
        "sh-actionbar": { template: "<div><slot /></div>" },
        "biz-coupon-strip": true,
        "biz-merchant-bar": true,
        "biz-review": true,
      },
      mocks: { $t: (k: string) => k },
    },
  });
  for (let i = 0; i < 12; i++) {
    await Promise.resolve();
    await w.vm.$nextTick();
  }
  return w;
}

describe("详情页 · 服务承诺与参数", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    goodsDetail.mockReset();
  });

  it("★★★ 后端给了承诺码才显示 —— 端上不自己算「这个价能不能极速退」", async () => {
    goodsDetail.mockResolvedValue(aGoods({ services: ["INSTANT_REFUND", "PICKUP_FREE"] }));
    const html = (await render()).html();
    expect(html).toContain("goods.svcINSTANT_REFUND");
    expect(html).toContain("goods.svcPICKUP_FREE");
  });

  it("★★★ 没给码就整条不出 —— 价高于上限时挂着那四个字就是假承诺", async () => {
    goodsDetail.mockResolvedValue(aGoods({ services: [] }));
    expect((await render()).html()).not.toContain("goods.svcINSTANT_REFUND");
  });

  it("★★ 不认识的码跳过，不把原始码印给买家看", async () => {
    goodsDetail.mockResolvedValue(aGoods({ services: ["SOMETHING_NEW"] }));
    const html = (await render()).html();
    expect(html).not.toContain("SOMETHING_NEW");
    expect(html).not.toContain("goods.svcSOMETHING_NEW");
  });

  it("★★★ 参数超过 4 条时给「全部」入口 —— 剩下的不能就这么丢了", async () => {
    const params = ["产地", "保质期", "储存条件", "口感风味", "包装"].map((n, i) => ({
      dimNo: `D${i}`, name: n, code: `C${i}`, label: `V${i}`,
    }));
    goodsDetail.mockResolvedValue(aGoods({ params } as Partial<Goods>));
    const html = (await render()).html();
    expect(html).toContain("goods.paramsAll");
    // 直出的只有前 4 条：第 5 条此刻不在页面上，它在抽屉里
    expect(html).toContain("V3");
    expect(html).not.toContain("V4");
  });

  it("★★ 正好 4 条时不给「全部」入口 —— 点开只会看到同样的四行", async () => {
    const params = ["产地", "保质期", "储存条件", "口感风味"].map((n, i) => ({
      dimNo: `D${i}`, name: n, code: `C${i}`, label: `V${i}`,
    }));
    goodsDetail.mockResolvedValue(aGoods({ params } as Partial<Goods>));
    expect((await render()).html()).not.toContain("goods.paramsAll");
  });
});
