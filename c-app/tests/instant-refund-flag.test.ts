/**
 * 售后申请页那句「可极速退」：**说的必须是后端在做的那个判断**。
 *
 * <p>此前页面拿 `TRADE_RULES.instantRefundMaxMinor` 比金额，而那份常量是 ¥50、
 * 后端阈值是 ¥100 —— 差了一倍；常量也表达不了规则里的另两半（总开关、下单 N 小时内）。
 * 更糟的是 mock 照同一个常量算，于是<b>替身替缺陷背了书</b>：mock 下两边永远一致，
 * 而线上是一个 ¥60 的单，页面说「不会秒退」、后端秒退了。
 *
 * <p>所以这里断言的是**信息来源**，不是某个具体金额：
 * 后端说 true 就提示、说 false 就不提示，而<b>金额多少都不影响</b> ——
 * 有了这两条，谁再在页面里按金额算一遍，用例就会红。
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import type { Order } from "@shared/types";

const orderDetail = vi.fn();

vi.mock("@/api", () => ({
  api: {
    orderDetail: (...a: unknown[]) => orderDetail(...a),
    afterSaleReasons: vi.fn(() => Promise.resolve([
      "NOT_WANTED", "DAMAGED", "MISSING", "WRONG_ITEM", "QUALITY", "EXPIRED", "OTHER",
    ])),
    applyAfterSale: vi.fn(),
    uploadImage: vi.fn(),
  },
}));
vi.mock("vue-i18n", () => ({ useI18n: () => ({ t: (k: string) => k }) }));

let query: Record<string, string> = { orderNo: "O1" };
vi.mock("@dcloudio/uni-app", () => ({
  onLoad: (cb: (q: Record<string, string>) => unknown) => cb(query),
  onShow: (cb: () => unknown) => cb(),
  onHide: vi.fn(),
  onPullDownRefresh: vi.fn(),
  onReachBottom: vi.fn(),
  onShareAppMessage: vi.fn(),
  onShareTimeline: vi.fn(),
  onPageScroll: vi.fn(),
}));

import AfterSalePage from "@/pages/after-sale/index.vue";

/** 金额刻意开得很大（¥888）：它不该参与这个判断 */
function anOrder(over: Partial<Order> = {}): Order {
  return {
    orderNo: "O1",
    status: "COMPLETED",
    fulfillment: "STORE_PICKUP",
    merchantNo: "M1",
    merchantName: "老张粮油店",
    items: [{
      goodsNo: "G1", skuNo: "S1", title: "柠檬", cover: "🍋",
      spec: "1份", price: 888, qty: 1, type: "GOODS",
    }],
    amount: {
      goodsMinor: 88_800, freightMinor: 0, discountMinor: 0, payableMinor: 88_800,
      paidMinor: 88_800, pointsDeductMinor: 0, pointsUsed: 0, pointsEarn: 0,
    },
    timeline: [],
    createdAt: Date.now(),
    ...over,
  } as unknown as Order;
}

async function render() {
  const w = mount(AfterSalePage, {
    global: {
      stubs: {
        "sh-scaffold": { template: "<div><slot /></div>" },
        "sh-chip": true,
        "biz-sku-row": { props: ["cover", "title", "spec"], template: "<div><slot /></div>" },
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

describe("售后申请页 · 极速退提示", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    query = { orderNo: "O1" };
    orderDetail.mockReset();
  });

  it("★★★ 后端说会秒退 → 提示出现，金额再大也照说", async () => {
    orderDetail.mockResolvedValue(anOrder({ instantRefundEligible: true }));
    expect((await render()).html()).toContain("afterSale.instant");
  });

  it("★★★ 后端说不会 → 不许提示，金额再小也不说", async () => {
    orderDetail.mockResolvedValue(anOrder({
      instantRefundEligible: false,
      amount: {
        goodsMinor: 1, freightMinor: 0, discountMinor: 0, payableMinor: 1,
        paidMinor: 1, pointsDeductMinor: 0, pointsUsed: 0, pointsEarn: 0,
      } as unknown as Order["amount"],
    }));
    expect((await render()).html()).not.toContain("afterSale.instant");
  });

  it("★★ 字段缺着（老后端 / 列表带过来的单）→ 不提示。宁可不说，不能说错", async () => {
    orderDetail.mockResolvedValue(anOrder({ instantRefundEligible: undefined }));
    expect((await render()).html()).not.toContain("afterSale.instant");
  });
});
