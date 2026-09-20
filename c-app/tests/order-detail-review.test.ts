/**
 * 订单详情页的**提审判据** —— 它是微信《小程序订单管理》「商品订单详情path」
 * 指向的那一页，用户从微信「我-小店与卡包-小程序购物订单」点进来落在这里。
 *
 * <p>两条都是 2026-09-20 在模拟器里**看出来**的，而不是读代码读出来的：
 * 类型检查、单测、构建当时全绿。
 *
 * <ol>
 *   <li><b>「配送方式 fulfillment.null」</b> —— 微信传的是<b>主订单号</b>，
 *       而主订单（多商家的单在这一层还没拆开）的 <code>fulfillment</code> 是 null，
 *       于是 <code>$t(`fulfillment.${null}`)</code> 把一个 i18n 键原样印在界面上。
 *       从微信点进来的<b>每一单</b>都会看到它。</li>
 *   <li><b>没带单号时一整片白</b> —— 只剩标题栏，没有文字、没有重试、没有出路。
 *       平台规范写明「页面不出现加载失败等 bug」。</li>
 * </ol>
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import type { Order } from "@shared/types";

const orderDetail = vi.fn();

vi.mock("@/api", () => ({
  api: {
    orderDetail: (...a: unknown[]) => orderDetail(...a),
    invoiceOfOrder: vi.fn(() => Promise.resolve(null)),
    cancelOrder: vi.fn(),
    applyInvoice: vi.fn(),
    fillReturnExpress: vi.fn(),
    raiseDispute: vi.fn(),
  },
}));
vi.mock("vue-i18n", () => ({ useI18n: () => ({ t: (k: string) => k }) }));
vi.mock("@ai-shop/ui/prompt", () => ({
  confirm: vi.fn(() => Promise.resolve(true)),
  prompt: vi.fn(() => Promise.resolve(null)),
}));

/** 每条用例自己决定 onLoad 拿到什么 —— 「没带单号」正是要测的一种 */
let query: Record<string, string> = { orderNo: "O1" };
vi.mock("@dcloudio/uni-app", () => ({
  onLoad: (cb: (q: Record<string, string>) => unknown) => cb(query),
  onShow: (cb: () => unknown) => cb(),
  onHide: vi.fn(),
  onPullDownRefresh: vi.fn(),
  onReachBottom: vi.fn(),
  onShareAppMessage: vi.fn(),
  onPageScroll: vi.fn(),
}));

import OrderPage from "@/pages/order/index.vue";

function anOrder(over: Partial<Order> = {}): Order {
  return {
    orderNo: "O1",
    status: "COMPLETED",
    fulfillment: "STORE_PICKUP",
    merchantNo: "M1",
    merchantName: "老张粮油店",
    items: [{ goodsNo: "G1", skuNo: "S1", title: "柠檬", cover: "🍋", spec: "1份", price: 10, qty: 1, type: "GOODS" }],
    amount: {
      goodsMinor: 10, freightMinor: 0, discountMinor: 0, payableMinor: 10,
      paidMinor: 10, pointsDeductMinor: 0, pointsUsed: 0, pointsEarn: 0,
    },
    timeline: [],
    createdAt: Date.now(),
    ...over,
  } as unknown as Order;
}

async function render() {
  const w = mount(OrderPage, {
    global: {
      stubs: {
        "sh-scaffold": { template: "<div><slot /></div>" },
        "sh-chip": true,
        "biz-sku-row": { props: ["cover", "title", "spec"], template: "<div><slot /><slot name='right' /></div>" },
      },
      // $t 原样返回键名 —— 漏键时它会在 html 里现形，这正是我们要断言的
      mocks: { $t: (k: string) => k },
    },
  });
  for (let i = 0; i < 10; i++) {
    await Promise.resolve();
    await w.vm.$nextTick();
  }
  return w;
}

describe("订单详情页（微信购物订单的落点）", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    query = { orderNo: "O1" };
    orderDetail.mockReset();
  });

  it("★★★ 主订单没有履约方式时，整行不出 —— 不许把 i18n 键印给用户看", async () => {
    orderDetail.mockResolvedValue(anOrder({ fulfillment: null as unknown as Order["fulfillment"] }));
    // 微信传的是主订单号，而主订单的 fulfillment 就是 null
    const html = (await render()).html();
    expect(html).not.toContain("fulfillment.null");
    // 键在、值没有 = 半行空的，同样不行
    expect(html).not.toContain("goods.fulfillment");
  });

  it("有履约方式时照常显示 —— 上一条不能是靠把整行删掉换来的", async () => {
    orderDetail.mockResolvedValue(anOrder({ fulfillment: "EXPRESS" as Order["fulfillment"] }));
    const html = (await render()).html();
    expect(html).toContain("fulfillment.EXPRESS");
  });

  it("★★★ 没带单号时给出交代，不是一整片白", async () => {
    query = {};
    const html = (await render()).html();
    expect(html)
      .toContain("order.noOrderNo");
    expect(orderDetail).not.toHaveBeenCalled();
  });
});
