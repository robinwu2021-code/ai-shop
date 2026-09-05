/**
 * 订单列表的**失败态**。
 *
 * <p>此前 `orderList` 不接异常 —— 同一个 `Promise.all` 里的 `afterSaleList`
 * 反倒接了 —— 而传输层只集中处理 401、其余错误不弹。
 * 于是一次网络抖动的表现是**一页空白，没有提示也没有重试**，
 * 而那与「你还没有订单」在屏幕上长得一模一样，可用户该做的事完全相反：
 * 一个是重试，一个是去逛逛。
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import { uniMock } from "./setup";

const orderList = vi.fn();
const afterSaleList = vi.fn();

vi.mock("@/api", () => ({
  api: {
    orderList: (...a: unknown[]) => orderList(...a),
    afterSaleList: (...a: unknown[]) => afterSaleList(...a),
  },
}));
vi.mock("vue-i18n", () => ({ useI18n: () => ({ t: (k: string) => k }) }));
vi.mock("@dcloudio/uni-app", () => ({
  onLoad: (cb: () => unknown) => cb(),
  onShow: (cb: () => unknown) => cb(),
  onHide: vi.fn(),
  onPullDownRefresh: vi.fn(),
  onReachBottom: vi.fn(),
  onShareAppMessage: vi.fn(),
}));

import OrdersPage from "@/pages/orders/index.vue";

const ORDER = {
  orderNo: "O1",
  status: "PAID",
  fulfillment: "STORE_PICKUP",
  items: [{ goodsNo: "G1", skuNo: "S1", title: "米", cover: "🍚", spec: "5kg", price: 2980, qty: 1 }],
  amount: { payableMinor: 2980 },
  createdAt: Date.now(),
};

async function render() {
  const w = mount(OrdersPage, {
    global: {
      stubs: {
        "sh-scaffold": { template: "<div><slot /></div>" },
        "sh-tabs": true,
        // 标题由它画 —— stub 里不画的话，「这一单到底在不在列表上」就断言不了
        "biz-sku-row": {
          props: ["cover", "title", "spec"],
          template: "<div><span>{{ title }}</span><slot name='right' /></div>",
        },
      },
      mocks: { $t: (k: string) => k },
    },
  });
  for (let i = 0; i < 8; i++) {
    await Promise.resolve();
    await w.vm.$nextTick();
  }
  return w;
}

describe("订单列表", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    afterSaleList.mockResolvedValue([]);
  });

  it("★★★ 拉挂了 → 失败态 + 重试，不是一页空白", async () => {
    orderList.mockRejectedValue(new Error("network down"));
    const w = await render();

    expect(w.text()).toContain("common.loadFailed");
    expect(w.text()).toContain("common.retry");
    expect(w.text(), "失败不是「没有订单」——两者该做的事相反").not.toContain("orders.empty");
    // 后端说了话就显示它：这条错误不是网络问题，告诉他去查网络是**错的**解释
    expect(w.text()).toContain("network down");
    expect(w.text()).not.toContain("common.loadFailedTip");
  });

  it("★★★ 失败与空是两回事：真的没订单时说的是空态", async () => {
    orderList.mockResolvedValue({ records: [], total: 0 });
    const w = await render();

    expect(w.text()).toContain("orders.empty");
    expect(w.text()).not.toContain("common.loadFailed");
  });

  it("★★ 点重试要真的再拉一次", async () => {
    orderList.mockRejectedValue(new Error("network down"));
    const w = await render();
    expect(orderList).toHaveBeenCalledTimes(1);

    orderList.mockResolvedValue({ records: [ORDER], total: 1 });
    await w.find(".empty__btn").trigger("tap");
    for (let i = 0; i < 8; i++) await w.vm.$nextTick();

    expect(orderList).toHaveBeenCalledTimes(2);
    expect(w.text()).not.toContain("common.loadFailed");
    expect(w.text()).toContain("米");
  });

  it("★★ 售后接口挂了不该拖垮主列表 —— 主列表才是这一页的正事", async () => {
    orderList.mockResolvedValue({ records: [ORDER], total: 1 });
    afterSaleList.mockRejectedValue(new Error("after-sale down"));
    const w = await render();

    expect(w.text()).toContain("米");
    expect(w.text()).not.toContain("common.loadFailed");
    expect(uniMock.showToast).not.toHaveBeenCalled();
  });
});
