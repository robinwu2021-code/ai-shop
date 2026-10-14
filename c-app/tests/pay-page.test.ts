import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { mount, type VueWrapper } from "@vue/test-utils";
import type { Order, OrderStatus } from "@shared/types";

const mock = vi.hoisted(() => ({
  detail: vi.fn(), result: vi.fn(), methods: vi.fn(), init: vi.fn(), payment: vi.fn(),
  subscribe: vi.fn(), clear: vi.fn(),
  load: undefined as undefined | ((query: Record<string, string>) => void),
  hide: undefined as undefined | (() => void),
  show: undefined as undefined | (() => void),
}));
vi.mock("@/api", () => ({ api: {
  orderDetail: mock.detail, payResult: mock.result, payMethods: mock.methods, payOrder: mock.init,
  subscribeReport: vi.fn(), cancelOrder: vi.fn(),
} }));
vi.mock("@shared/ports/payment", () => ({ requestPayment: mock.payment }));
vi.mock("@shared/ports/push", () => ({ orderSubscribeTmpls: () => [], requestSubscribe: mock.subscribe }));
vi.mock("@/shared/checkout-key", () => ({ clearCheckoutKey: mock.clear }));
vi.mock("vue-i18n", () => ({ useI18n: () => ({ t: (key: string) => key }) }));
vi.mock("@ai-shop/ui/prompt", () => ({ confirm: vi.fn(() => Promise.resolve(true)) }));
vi.mock("@dcloudio/uni-app", () => ({
  onLoad: (cb: typeof mock.load) => { mock.load = cb; },
  onHide: (cb: typeof mock.hide) => { mock.hide = cb; },
  onShow: (cb: typeof mock.show) => { mock.show = cb; },
}));

import PayPage from "@/pages/pay/index.vue";

function order(no: string, status: OrderStatus, minor = 1000, extra: Partial<Order> = {}): Order {
  return {
    orderNo: no, status, fulfillment: "EXPRESS", merchantNo: "M1", merchantName: "店一",
    items: [{ goodsNo: "G1", skuNo: "S1", title: "米", cover: "", spec: "", price: minor, qty: 1, type: "GOODS" }],
    amount: { goodsMinor: minor, freightMinor: 0, discountMinor: 0, payableMinor: minor,
      paidMinor: status === "PAID" ? minor : 0, pointsDeductMinor: 0, pointsUsed: 0, pointsEarn: 0 },
    createdAt: Date.now(), timeline: [], ...extra,
  } as Order;
}

const mounted: VueWrapper[] = [];
async function flush() {
  for (let i = 0; i < 15; i++) await Promise.resolve();
}
async function render(no = "S1", auto = false) {
  const wrapper = mount(PayPage, { global: {
    mocks: { $t: (key: string) => key },
    stubs: { "sh-scaffold": { template: "<div><slot /></div>" },
      "sh-actionbar": { template: "<div><slot /></div>" } },
  } });
  mounted.push(wrapper);
  mock.load?.({ orderNo: no, auto: auto ? "1" : "0" });
  await flush();
  await wrapper.vm.$nextTick();
  return wrapper;
}
async function clickPay(wrapper: VueWrapper) {
  const button = wrapper.findAll(".sh-btn").find((node) => node.text() === "pay.payNow");
  expect(button).toBeDefined();
  await button!.trigger("tap");
  await flush();
}

describe("收银台支付资源与真实结果", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.clearAllMocks();
    mock.detail.mockResolvedValue(order("S1", "WAIT_PAY"));
    mock.result.mockResolvedValue(order("S1", "PAID"));
    mock.methods.mockResolvedValue({ configured: true, methods: [{ payChannel: "WECHAT", available: true }] });
    mock.init.mockResolvedValue({ orderNo: "P1", settled: false, payParams: { token: "provider" } });
    mock.payment.mockResolvedValue({ invoked: true, cancelled: false });
    mock.subscribe.mockResolvedValue({ accepted: [], rejected: [] });
  });
  afterEach(() => {
    mounted.splice(0).forEach((wrapper) => wrapper.unmount());
    vi.useRealTimers();
  });

  it("子单10元入口展示同一支付单30元，并按主单取支付方式", async () => {
    const one = order("S1", "WAIT_PAY", 1000, { payOrderNo: "P1" });
    const two = order("S2", "WAIT_PAY", 2000, { merchantName: "店二" });
    mock.detail.mockImplementation((no: string) => Promise.resolve(no === "S1" ? one
      : order("P1", "WAIT_PAY", 3000, { subOrders: [one, two] })));
    const wrapper = await render();
    expect(mock.detail).toHaveBeenCalledWith("P1");
    expect(mock.methods).toHaveBeenCalledWith("P1");
    expect(wrapper.text()).toContain("30.00");
    expect(wrapper.text()).toContain("店二");
  });

  it.each(["WAIT_OFFLINE_PAY", "CANCELLED", "REFUNDED"] as OrderStatus[])("%s不能显示支付成功或自动拉起付款", async (status) => {
    mock.detail.mockResolvedValue(order("S1", status));
    const wrapper = await render("S1", true);
    expect(wrapper.text()).not.toContain("pay.done");
    expect(mock.init).not.toHaveBeenCalled();
  });

  it("发起及回查统一主单号，订阅授权仍先于回查", async () => {
    mock.detail.mockResolvedValue(order("S1", "WAIT_PAY", 3000, { payOrderNo: "P1" }));
    mock.result.mockResolvedValue(order("P1", "PAID", 3000));
    const wrapper = await render();
    await clickPay(wrapper);
    await vi.advanceTimersByTimeAsync(1000);
    expect(mock.init).toHaveBeenCalledWith("P1", "WECHAT");
    expect(mock.payment).toHaveBeenCalledWith({ token: "provider" });
    expect(mock.result).toHaveBeenCalledWith("P1");
    expect(mock.subscribe.mock.invocationCallOrder[0]).toBeLessThan(mock.result.mock.invocationCallOrder[0]);
    expect(wrapper.text()).toContain("pay.done");
    expect(mock.clear).toHaveBeenCalledOnce();
  });

  it("延迟回调有界回查收敛成功", async () => {
    mock.result.mockResolvedValueOnce(order("P1", "WAIT_PAY"))
      .mockResolvedValueOnce(order("P1", "WAIT_PAY")).mockResolvedValue(order("P1", "PAID"));
    const wrapper = await render();
    await clickPay(wrapper);
    await vi.advanceTimersByTimeAsync(4000);
    expect(mock.result).toHaveBeenCalledTimes(3);
    expect(wrapper.text()).toContain("pay.done");
    await vi.advanceTimersByTimeAsync(60000);
    expect(mock.result).toHaveBeenCalledTimes(3);
  });

  it("12秒五次仍未确认，保留幂等键并允许手动刷新", async () => {
    mock.result.mockResolvedValue(order("P1", "WAIT_PAY"));
    const wrapper = await render();
    await clickPay(wrapper);
    await vi.advanceTimersByTimeAsync(12000);
    expect(mock.result).toHaveBeenCalledTimes(5);
    expect(wrapper.text()).toContain("pay.confirmPending");
    expect(wrapper.text()).toContain("pay.refreshResult");
    expect(mock.clear).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(60000);
    expect(mock.result).toHaveBeenCalledTimes(5);
    expect(mock.init).toHaveBeenCalledOnce();
  });

  it("手动刷新确认成功，且不会重新发起支付", async () => {
    mock.result.mockResolvedValue(order("P1", "WAIT_PAY"));
    const wrapper = await render();
    await clickPay(wrapper);
    await vi.advanceTimersByTimeAsync(12000);
    mock.result.mockResolvedValue(order("P1", "PAID"));
    const refresh = wrapper.findAll(".sh-btn").find((node) => node.text() === "pay.refreshResult");
    await refresh!.trigger("tap");
    await flush();
    expect(mock.result).toHaveBeenCalledTimes(6);
    expect(mock.init).toHaveBeenCalledOnce();
    expect(wrapper.text()).toContain("pay.done");
    expect(mock.clear).toHaveBeenCalledOnce();
  });

  it.each([{ invoked: true, cancelled: true }, { invoked: false, cancelled: false }])(
    "取消或唤起失败不回查或清键", async (response) => {
      mock.payment.mockResolvedValue(response);
      const wrapper = await render();
      await clickPay(wrapper);
      await vi.advanceTimersByTimeAsync(60000);
      expect(mock.result).not.toHaveBeenCalled();
      expect(mock.clear).not.toHaveBeenCalled();
      expect(wrapper.text()).toContain("pay.payNow");
    });

  it.each([
    { invoked: true, cancelled: true }, { invoked: false, cancelled: false }, { invoked: true, cancelled: false },
  ])("收银台隐藏期间返回交互结果，回来后处理而不丢失", async (response) => {
    let finish!: (value: typeof response) => void;
    mock.payment.mockImplementation(() => new Promise((resolve) => { finish = resolve; }));
    mock.result.mockResolvedValue(order("P1", "PAID"));
    const wrapper = await render();
    await clickPay(wrapper);
    mock.hide?.();
    finish(response);
    await flush();
    expect(mock.result).not.toHaveBeenCalled();
    mock.show?.();
    await flush();
    await vi.advanceTimersByTimeAsync(1000);
    if (response.cancelled || !response.invoked) {
      expect(wrapper.text()).toContain("pay.payNow");
      expect(mock.result).not.toHaveBeenCalled();
      expect(mock.clear).not.toHaveBeenCalled();
    } else {
      expect(mock.subscribe).toHaveBeenCalledOnce();
      expect(mock.result).toHaveBeenCalledOnce();
      expect(wrapper.text()).toContain("pay.done");
    }
  });

  it("旧详情请求不能覆盖新路由或替新单自动付款", async () => {
    let finish!: (value: Order) => void;
    mock.detail.mockImplementation((no: string) => no === "S1"
      ? new Promise<Order>((resolve) => { finish = resolve; }) : Promise.resolve(order("S2", "WAIT_PAY", 2000)));
    const wrapper = await render("S1", true);
    mock.load?.({ orderNo: "S2", auto: "0" });
    await flush();
    finish(order("S1", "WAIT_PAY", 1000));
    await flush();
    expect(wrapper.text()).toContain("20.00");
    expect(mock.init).not.toHaveBeenCalled();
    expect(mock.methods).toHaveBeenCalledOnce();
    expect(mock.methods).toHaveBeenCalledWith("S2");
  });

  it("隐藏页面取消等待，不继续后台回查", async () => {
    const wrapper = await render();
    await clickPay(wrapper);
    mock.hide?.();
    await vi.advanceTimersByTimeAsync(60000);
    expect(mock.result).not.toHaveBeenCalled();
    expect(mock.clear).not.toHaveBeenCalled();
  });

  it("隐藏后的迟到请求不能覆盖界面或清除结算幂等键", async () => {
    let finish!: (value: Order) => void;
    mock.result.mockImplementation(() => new Promise<Order>((resolve) => { finish = resolve; }));
    const wrapper = await render();
    await clickPay(wrapper);
    await vi.advanceTimersByTimeAsync(1000);
    expect(mock.result).toHaveBeenCalledOnce();
    mock.hide?.();
    finish(order("P1", "PAID"));
    await flush();
    expect(wrapper.text()).not.toContain("pay.done");
    expect(mock.clear).not.toHaveBeenCalled();
  });

  it("零元已结清只读真实结果，不调用通道交互", async () => {
    mock.detail.mockResolvedValue(order("S1", "WAIT_PAY", 0));
    mock.init.mockResolvedValue({ orderNo: "P1", settled: true, payParams: {} });
    mock.result.mockResolvedValue(order("P1", "PAID", 0));
    const wrapper = await render();
    await clickPay(wrapper);
    await flush();
    expect(mock.payment).not.toHaveBeenCalled();
    expect(mock.result).toHaveBeenCalledWith("P1");
    expect(wrapper.text()).toContain("pay.done");
  });
});
