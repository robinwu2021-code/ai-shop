/**
 * 商品卡的**售罄态**与列表页那个「＋」挑哪个规格。
 *
 * <p>此前两件事叠在一起，后果是「静默加进一件当场就失效的货」：
 * <ol>
 *   <li>卡片**没有售罄态** —— 「＋」无条件画出来；</li>
 *   <li>点它加的是 <code>skus[0]</code>，**不是第一个有货的** ——
 *       而商品详情页用的是后者，同一件商品两处挑出不同的规格；</li>
 *   <li>加购这条路后端与 mock **都不校验库存**。</li>
 * </ol>
 * 三条合起来：卖光的商品照样能加进购物车，用户要到购物车的失效区才看见。
 */
import { describe, expect, it, vi } from "vitest";
import { mount } from "@vue/test-utils";
import { firstBuyableSku, goodsSoldOut } from "@shared/utils/goods";
import type { Goods } from "@shared/types";

vi.mock("vue-i18n", () => ({ useI18n: () => ({ t: (k: string) => k }) }));

import GoodsCard from "@/components/biz/biz-goods-card.vue";

function goods(stocks: number[]): Goods {
  return {
    goodsNo: "G1",
    title: "米",
    subtitle: "脆甜多汁",
    cover: "🍚",
    type: "GOODS",
    price: 2980,
    sales: 10,
    skus: stocks.map((stock, i) => ({
      skuNo: `S${i + 1}`,
      optionValues: [`${i + 1}`],
      spec: `${i + 1}`,
      price: 2980,
      stock,
    })),
    merchant: { merchantNo: "M1", name: "老张粮油店", logo: "🏪" },
  } as unknown as Goods;
}

function render(g: Goods) {
  return mount(GoodsCard, {
    props: { goods: g },
    global: { stubs: { "sh-cover": true }, mocks: { $t: (k: string) => k } },
  });
}

describe("商品卡", () => {
  it("★★★ 全部规格都没货 → 画售罄，不画那个「＋」", () => {
    const w = render(goods([0, 0]));
    expect(w.text()).toContain("goods.soldOut");
    expect(w.find(".add").exists(), "点了也没用的按钮不该画出来").toBe(false);
  });

  it("★★ 只要还有一个规格有货就照常能加", () => {
    const w = render(goods([0, 5]));
    expect(w.find(".add").exists()).toBe(true);
    expect(w.text()).not.toContain("goods.soldOut");
  });

  it("★★ 点「＋」把原始事件透传出去 —— 飞入动效要拿它的落点坐标", async () => {
    const w = render(goods([5]));
    await w.find(".add").trigger("tap");
    expect(w.emitted("add")).toBeTruthy();
  });

  it("★★★ 挑的是**第一个有货的**规格，不是下标 0", () => {
    expect(firstBuyableSku(goods([0, 0, 7])).skuNo).toBe("S3");
    expect(firstBuyableSku(goods([3, 9])).skuNo).toBe("S1");
  });

  it("★★ 都没货时回落到第一个，不抛错 —— 那种卡片已经画成售罄，走不到这里", () => {
    expect(firstBuyableSku(goods([0, 0])).skuNo).toBe("S1");
  });

  it("★★ 没拿到规格 ≠ 卖光了：`skus` 为空不算售罄", () => {
    expect(goodsSoldOut(goods([]))).toBe(false);
    expect(goodsSoldOut(goods([0]))).toBe(true);
  });
});
