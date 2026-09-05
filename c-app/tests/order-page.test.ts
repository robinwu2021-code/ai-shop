/**
 * 订单详情页的**操作按钮出现条件**。
 *
 * <p>这一页是这条链上分支最多的一页 —— 六个操作按钮各有各的出现条件 ——
 * 而在 2026-09-05 之前它**一条组件测试都没有**。逐项复核交互清单时，
 * 两条判据被查出是错的，两条都不报错、不崩：
 *
 * <ol>
 *   <li><b>申请售后</b>的判据是 <code>["PAID","ARRIVED","SHIPPED","COMPLETED"]</code>，
 *       而 <code>ARRIVED</code>/<code>SHIPPED</code> 在状态模型重整时已经并成
 *       <code>FULFILLING</code> —— 于是**履约中的订单一直没有售后入口**，
 *       而那正是「货不对、货损了」最常被发现的时候。
 *       类型系统当时抓不到：数组字面量被推断成 <code>string[]</code>。</li>
 *   <li><b>取消订单</b>的判据是 <code>WAIT_PAY || PAID</code>，而后端状态机里
 *       <code>PAID</code> 的可迁移集合是**空的** —— 按钮显示、二次确认还承诺
 *       「库存将释放」，点下去必然报错；反过来 <code>WAIT_OFFLINE_PAY</code>
 *       后端明确允许取消，端上反倒不给入口。</li>
 * </ol>
 *
 * <p>所以这里断言的是**每个按钮在每种状态下该不该出现**，
 * 而不是「代码里有没有那个字符串」——后者正是漏掉这两条的那种判据。
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import { uniMock } from "./setup";
import type { Order, OrderStatus } from "@shared/types";

const orderDetail = vi.fn();
const invoiceOfOrder = vi.fn();
const cancelOrder = vi.fn();

vi.mock("@/api", () => ({
  api: {
    orderDetail: (...a: unknown[]) => orderDetail(...a),
    invoiceOfOrder: (...a: unknown[]) => invoiceOfOrder(...a),
    cancelOrder: (...a: unknown[]) => cancelOrder(...a),
    applyInvoice: vi.fn(),
    fillReturnExpress: vi.fn(),
    raiseDispute: vi.fn(),
  },
}));
vi.mock("vue-i18n", () => ({ useI18n: () => ({ t: (k: string) => k }) }));

const confirmMock = vi.fn(() => Promise.resolve(true));
vi.mock("@ai-shop/ui/prompt", () => ({
  confirm: (...a: unknown[]) => confirmMock(...(a as [])),
  prompt: vi.fn(() => Promise.resolve(null)),
}));

/** onLoad 要拿到 orderNo，否则 load() 直接 return，每条用例都在测一张空页面 */
vi.mock("@dcloudio/uni-app", () => ({
  onLoad: (cb: (q: Record<string, string>) => unknown) => cb({ orderNo: "O1" }),
  onShow: (cb: () => unknown) => cb(),
  onHide: vi.fn(),
  onPullDownRefresh: vi.fn(),
  onReachBottom: vi.fn(),
  onShareAppMessage: vi.fn(),
}));

import OrderPage from "@/pages/order/index.vue";

function order(status: OrderStatus, over: Partial<Order> = {}): Order {
  return {
    orderNo: "O1",
    status,
    fulfillment: "STORE_PICKUP",
    merchantNo: "M1",
    merchantName: "老张粮油店",
    items: [{ goodsNo: "G1", skuNo: "S1", title: "米", cover: "🍚", spec: "5kg", price: 2980, qty: 1, type: "GOODS" }],
    amount: {
      goodsMinor: 2980, freightMinor: 0, discountMinor: 0, payableMinor: 2980,
      paidMinor: 2980, pointsDeductMinor: 0, pointsUsed: 0, pointsEarn: 0,
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
      mocks: { $t: (k: string) => k },
    },
  });
  for (let i = 0; i < 10; i++) {
    await Promise.resolve();
    await w.vm.$nextTick();
  }
  return w;
}

describe("订单详情页的操作按钮", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    confirmMock.mockResolvedValue(true);
    invoiceOfOrder.mockResolvedValue(null);
    cancelOrder.mockImplementation(() => Promise.resolve(order("CANCELLED")));
  });

  it("★★★ 履约中的订单**要有**售后入口 —— 货不对、货损了正是这时候发现的", async () => {
    orderDetail.mockResolvedValue(order("FULFILLING"));
    const w = await render();
    expect(w.text()).toContain("order.afterSale");
  });

  it("★★ 已付款、已完成同样有售后入口", async () => {
    for (const st of ["PAID", "COMPLETED"] as OrderStatus[]) {
      orderDetail.mockResolvedValue(order(st));
      const w = await render();
      expect(w.text(), `${st} 应当有售后入口`).toContain("order.afterSale");
    }
  });

  it("★★ 待付款没有售后入口 —— 钱还没付，没有可退的", async () => {
    orderDetail.mockResolvedValue(order("WAIT_PAY"));
    const w = await render();
    expect(w.text()).not.toContain("order.afterSale");
  });

  it("★★★ 已付款**不给**取消入口 —— 后端状态机里 PAID 的可迁移集合是空的", async () => {
    orderDetail.mockResolvedValue(order("PAID"));
    const w = await render();
    expect(w.text(), "给了就是一个点下去必然报错的按钮").not.toContain("order.cancel");
  });

  it("★★★ 当面付待收款**要给**取消入口 —— 后端允许，此前端上没给", async () => {
    orderDetail.mockResolvedValue(order("WAIT_OFFLINE_PAY"));
    const w = await render();
    expect(w.text()).toContain("order.cancel");
  });

  it("★★ 待付款有取消入口，且要二次确认", async () => {
    orderDetail.mockResolvedValue(order("WAIT_PAY"));
    const w = await render();
    expect(w.text()).toContain("order.cancel");

    const btn = w.findAll(".op").find((b) => b.text().includes("order.cancel"))!;
    await btn.trigger("tap");
    await w.vm.$nextTick();
    expect(confirmMock).toHaveBeenCalled();
  });

  it("★★ 取消被后端拒 → 说一句，不静默", async () => {
    orderDetail.mockResolvedValue(order("WAIT_PAY"));
    cancelOrder.mockRejectedValue(new Error("订单状态不允许取消"));
    const w = await render();
    const btn = w.findAll(".op").find((b) => b.text().includes("order.cancel"))!;
    await btn.trigger("tap");
    for (let i = 0; i < 6; i++) await w.vm.$nextTick();
    expect(uniMock.showToast).toHaveBeenCalled();
  });

  it("★★ 已取消的订单不能开票；已完成的可以", async () => {
    orderDetail.mockResolvedValue(order("CANCELLED"));
    expect((await render()).text()).not.toContain("invoice.apply");

    orderDetail.mockResolvedValue(order("COMPLETED"));
    expect((await render()).text()).toContain("invoice.apply");
  });

  it("★★★ 拉不到订单 → 失败态 + 重试，而不是一整片白", async () => {
    orderDetail.mockRejectedValue(new Error("network down"));
    const w = await render();

    expect(w.text(), "此前整页挂在 v-if=\"order\" 上，连外壳都不渲染").toContain("common.loadFailed");
    expect(w.text()).toContain("common.retry");
    // 「订单不存在」时说「检查网络」是错的解释，后端说了话就显示它
    expect(w.text()).toContain("network down");

    // 点重试要真的再拉一次
    orderDetail.mockResolvedValue(order("PAID"));
    await w.find(".empty__btn").trigger("tap");
    for (let i = 0; i < 8; i++) await w.vm.$nextTick();
    expect(w.text()).not.toContain("common.loadFailed");
    expect(w.text()).toContain("order.afterSale");
  });

  it("★ 「再买一单」在任何状态下都在 —— 它不依赖订单状态", async () => {
    for (const st of ["WAIT_PAY", "PAID", "FULFILLING", "COMPLETED", "CANCELLED"] as OrderStatus[]) {
      orderDetail.mockResolvedValue(order(st));
      expect((await render()).text(), st).toContain("order.buyAgain");
    }
  });
});
