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
const confirmReceipt = vi.fn();
const cancelAfterSale = vi.fn();

vi.mock("@/api", () => ({
  api: {
    orderDetail: (...a: unknown[]) => orderDetail(...a),
    invoiceOfOrder: (...a: unknown[]) => invoiceOfOrder(...a),
    cancelOrder: (...a: unknown[]) => cancelOrder(...a),
    confirmReceipt: (...a: unknown[]) => confirmReceipt(...a),
    cancelAfterSale: (...a: unknown[]) => cancelAfterSale(...a),
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
  onShareTimeline: vi.fn(), onPageScroll: vi.fn(),
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

    const btn = w.findAll(".obar__btn").find((b) => b.text().includes("order.cancel"))!;
    await btn.trigger("tap");
    await w.vm.$nextTick();
    expect(confirmMock).toHaveBeenCalled();
  });

  it("★★ 取消被后端拒 → 说一句，不静默", async () => {
    orderDetail.mockResolvedValue(order("WAIT_PAY"));
    cancelOrder.mockRejectedValue(new Error("订单状态不允许取消"));
    const w = await render();
    const btn = w.findAll(".obar__btn").find((b) => b.text().includes("order.cancel"))!;
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
    // 按库件自己定义的插槽容器选（sh-empty 的 .empty__act），不按页面私有类名：
    // 重试按钮原来是 .empty__btn，收进 sh-empty 的 action 插槽之后那个类没了，
    // 而失败信息只说「在空 wrapper 上 trigger」，指不到「类名已经不存在」
    await w.find(".empty__act .sh-btn").trigger("tap");
    for (let i = 0; i < 8; i++) await w.vm.$nextTick();
    expect(w.text()).not.toContain("common.loadFailed");
    expect(w.text()).toContain("order.afterSale");
  });

  it("★★★ 评价过的订单不再显示「去评价」—— reviewed 后端此前不发，判据恒为真", async () => {
    orderDetail.mockResolvedValue(order("COMPLETED", { reviewed: true }));
    expect((await render()).text()).not.toContain("review.writeTitle");

    orderDetail.mockResolvedValue(order("COMPLETED", { reviewed: false }));
    expect((await render()).text()).toContain("review.writeTitle");
  });

  it("★★★ 售后进行中的卡片靠 afterSale 出现 —— 后端此前不发，整张卡永远不显示", async () => {
    orderDetail.mockResolvedValue(order("FULFILLING"));
    expect((await render()).text()).not.toContain("afterSale.status.WAIT_RETURN");

    // 商家同意退货 → WAIT_RETURN（2026-10-14 起独立状态，不再是「REFUNDING + 没运单号」）
    orderDetail.mockResolvedValue(order("FULFILLING", {
      afterSale: { afterSaleNo: "AS1", status: "WAIT_RETURN", type: "RETURN_REFUND" },
    } as never));
    const w = await render();
    expect(w.text()).toContain("afterSale.status.WAIT_RETURN");
    expect(w.text(), "等寄回 → 要给填单号的入口").toContain("afterSale.fillExpress");

    // 钱在路上（REFUNDING）：不再给填单号入口
    orderDetail.mockResolvedValue(order("FULFILLING", {
      afterSale: { afterSaleNo: "AS1", status: "REFUNDING", type: "RETURN_REFUND" },
    } as never));
    expect((await render()).text()).not.toContain("afterSale.fillExpress");
  });

  it("★★ 申请平台介入按后端的 canEscalate 显示 —— 商家超时未处理时也能申请，不止驳回", async () => {
    orderDetail.mockResolvedValue(order("FULFILLING", {
      afterSale: { afterSaleNo: "AS1", status: "APPLIED", type: "REFUND_ONLY", canEscalate: true },
    } as never));
    expect((await render()).text()).toContain("afterSale.raiseDispute");

    orderDetail.mockResolvedValue(order("FULFILLING", {
      afterSale: { afterSaleNo: "AS1", status: "APPLIED", type: "REFUND_ONLY", canEscalate: false },
    } as never));
    expect((await render()).text()).not.toContain("afterSale.raiseDispute");
  });

  it("★★★ 那串码的标签按品类走 —— 虚拟商品拿到的是兑换码，不是取货码", async () => {
    orderDetail.mockResolvedValue(order("FULFILLING", {
      verifyCode: "8812",
      items: [{ goodsNo: "G1", skuNo: "S1", title: "月卡", cover: "🎬", spec: "1 个月", price: 1500, qty: 1, type: "VIRTUAL" }],
    } as never));
    const w = await render();
    expect(w.text()).toContain("pay.redeemCode");
    expect(w.text()).not.toContain("pay.verifyCode");
  });

  it("★★ 到店核销叫核销码；自提叫取货码", async () => {
    orderDetail.mockResolvedValue(order("FULFILLING", { verifyCode: "8812", fulfillment: "STORE_VERIFY" } as never));
    expect((await render()).text()).toContain("pay.verifyCodeStore");

    orderDetail.mockResolvedValue(order("FULFILLING", { verifyCode: "8812" }));
    const w = await render();
    // 「pay.verifyCode」是「pay.verifyCodeStore」的前缀，只判包含会两边都过
    expect(w.text()).toContain("pay.verifyCode");
    expect(w.text()).not.toContain("pay.verifyCodeStore");
  });

  it("★★ 拆单提示按**子单张数**出现，不再靠一个后端没有的编号", async () => {
    orderDetail.mockResolvedValue(order("PAID", { payGroupSize: 1 }));
    expect((await render()).text()).not.toContain("order.splitHint");

    orderDetail.mockResolvedValue(order("PAID", { payGroupSize: 2 }));
    expect((await render()).text()).toContain("order.splitHint");
  });

  // ---------------------------------------------------------------- TDD-订单售后三端断点修复

  it("★★★ AC5 开票按主单号查 —— 订单视角下 orderNo 是子单号，拿它查必然 NOT_FOUND", async () => {
    orderDetail.mockResolvedValue(order("COMPLETED", { orderNo: "SUB1", payOrderNo: "PAY1" }));
    await render();
    expect(invoiceOfOrder).toHaveBeenCalledWith("PAY1");
  });

  it("★★★ AC7 主单号打开、只有一家店 → 切到那张订单（售后按子单号走）", async () => {
    const sub = order("FULFILLING", { orderNo: "SUB1", payOrderNo: "O1" });
    orderDetail.mockImplementation((no: string) =>
      Promise.resolve(no === "O1" ? order("FULFILLING", { payOrderNo: "O1", subOrders: [sub] }) : sub));
    const w = await render();
    expect(orderDetail).toHaveBeenLastCalledWith("SUB1");
    expect(w.text(), "切过去之后售后入口要在").toContain("order.afterSale");
  });

  it("★★★ AC7 主单号打开、多家店 → 不给售后入口（售后是子单粒度）", async () => {
    const subs = [order("FULFILLING", { orderNo: "SUB1" }), order("FULFILLING", { orderNo: "SUB2" })];
    orderDetail.mockResolvedValue(order("FULFILLING", { payOrderNo: "O1", subOrders: subs }));
    const w = await render();
    expect(orderDetail).toHaveBeenCalledTimes(1);
    expect(w.text()).not.toContain("order.afterSale");
  });

  it("★★ AC8 上一张售后被驳回 / 已关闭 → 可以再申请；已退款暂不放开", async () => {
    for (const [st, can] of [["REJECTED", true], ["CLOSED", true], ["REFUNDED", false], ["APPLIED", false]] as const) {
      orderDetail.mockResolvedValue(order("COMPLETED", {
        afterSale: { afterSaleNo: "AS1", status: st, type: "REFUND_ONLY", refundMinor: 100 },
      } as unknown as Partial<Order>));
      const text = (await render()).text();
      if (can) expect(text, st).toContain("order.afterSale");
      else expect(text, st).not.toContain("order.afterSale");
    }
  });

  it("★ 「再买一单」在付款后的各状态都在 —— 它不依赖履约进度", async () => {
    // 待付款不在列：吸底条两位是「去支付 + 取消」，再挤一颗就是三选一（2026-10-10 按原型重排）
    for (const st of ["PAID", "FULFILLING", "COMPLETED", "CANCELLED"] as OrderStatus[]) {
      orderDetail.mockResolvedValue(order(st));
      expect((await render()).text(), st).toContain("order.buyAgain");
    }
  });
});


describe("一期收货与售后执行", () => {
  beforeEach(() => {
    setActivePinia(createPinia()); vi.clearAllMocks();
    confirmMock.mockResolvedValue(true); invoiceOfOrder.mockResolvedValue(null);
  });
  it("签收仍是履约中，确认收货后回读服务端完成状态", async () => {
    orderDetail.mockResolvedValue(order("FULFILLING", { fulfillment: "EXPRESS" }));
    confirmReceipt.mockImplementation(async () => {
      orderDetail.mockResolvedValue(order("COMPLETED", { fulfillment: "EXPRESS" }));
      return order("COMPLETED");
    });
    const w = await render();
    const button = w.findAll("text").find(n => n.text() === "order.confirmReceipt");
    expect(button).toBeDefined();
    await button!.trigger("tap");
    for (let i=0;i<15;i++) await Promise.resolve();
    expect(confirmReceipt).toHaveBeenCalledWith("O1");
    expect(orderDetail.mock.calls.length).toBeGreaterThan(1);
    expect(w.text()).not.toContain("order.confirmReceipt"); w.unmount();
  });
  it("撤销按售后号提交并回读订单，不改写成一张售后单", async () => {
    orderDetail.mockResolvedValue(order("PAID", { afterSale: { afterSaleNo:"AS1",status:"APPLIED" } as Order["afterSale"] }));
    cancelAfterSale.mockImplementation(async () => {
      orderDetail.mockResolvedValue(order("PAID", { afterSale: { afterSaleNo:"AS1",status:"CLOSED" } as Order["afterSale"] }));
      return { status:"CLOSED" };
    });
    const w=await render();
    const button=w.findAll("text,view").find(n=>n.text()==="afterSale.withdraw");
    expect(button).toBeDefined(); await button!.trigger("tap");
    for(let i=0;i<15;i++) await Promise.resolve();
    expect(cancelAfterSale).toHaveBeenCalledWith("AS1"); expect(w.text()).toContain("afterSale.status.CLOSED"); w.unmount();
  });
  it("现金已退但资源未恢复时显示真实进度", async () => {
    orderDetail.mockResolvedValue(order("REFUNDED", { afterSale:{status:"REFUNDED",refundExecution:{status:"SUCCEEDED",resourceStatus:"PENDING"}} as Order["afterSale"] }));
    const w=await render(); expect(w.text()).toContain("afterSale.execution.resourcesPending"); w.unmount();
  });
});
