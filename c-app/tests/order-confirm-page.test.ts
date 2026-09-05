/**
 * 结算页的两处**看不见的毛病**。
 *
 * <ol>
 *   <li><b>试算竞态</b> —— 金额随地址、券、积分、支付方式一起变，每变一次问一次后端。
 *       先发的响应后到就会盖住后发的，屏幕上留下**上一次的价**：
 *       金额有、不报错、也不转圈，页面看起来完全正常。
 *       只在网络慢的那台手机上出现，本机永远复现不了 —— 所以只能在这里测。</li>
 *   <li><b>提交不了不说为什么</b> —— 五个否决条件共用同一个灰按钮，
 *       屏幕上一个字都没有。他看得见按钮点不动，却不知道该改哪儿。</li>
 * </ol>
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import { FULFILLMENT } from "@shared/utils/constants";
import type { CartItem } from "@shared/types";

/** onLoad 拿到的 query。各用例自己改（履约方式决定这一页要不要地址） */
let query: Record<string, string> = {};
vi.mock("@dcloudio/uni-app", () => ({
  onLoad: (cb: (q: Record<string, string>) => unknown) => cb(query),
  onShow: vi.fn(),
  onHide: vi.fn(),
  onPullDownRefresh: vi.fn(),
  onReachBottom: vi.fn(),
  onShareAppMessage: vi.fn(),
}));

const orderPreview = vi.fn();
const orderCapability = vi.fn();
vi.mock("@/api", () => ({
  api: {
    orderPreview: (...a: unknown[]) => orderPreview(...a),
    orderCapability: (...a: unknown[]) => orderCapability(...a),
    pointsDeductible: vi.fn(() => Promise.resolve(null)),
    couponList: vi.fn(() => Promise.resolve([])),
    pointAccount: vi.fn(() => Promise.resolve({ balance: 0 })),
    addressList: vi.fn(() => Promise.resolve([])),
    createOrder: vi.fn(),
    cartList: vi.fn(() => Promise.resolve([])),
  },
  idempotencyKey: () => "k1",
}));

vi.mock("vue-i18n", () => ({ useI18n: () => ({ t: (k: string) => k }) }));
vi.mock("@ai-shop/ui/prompt", () => ({ pick: vi.fn(), prompt: vi.fn(), confirm: vi.fn() }));

import ConfirmPage from "@/pages/order-confirm/index.vue";
import { useCartStore } from "@/stores/cart";

function item(over: Partial<CartItem> = {}): CartItem {
  return {
    goodsNo: "G1",
    skuNo: "S1",
    title: "米",
    cover: "🍚",
    spec: "5kg",
    price: 2980,
    qty: 1,
    type: "GOODS",
    fulfillment: FULFILLMENT.PICKUP,
    merchantNo: "M1",
    merchantName: "老张粮油店",
    ...over,
  } as CartItem;
}

function amount(payableMinor: number) {
  return {
    amount: {
      goodsMinor: payableMinor,
      freightMinor: 0,
      discountMinor: 0,
      pointsUsed: 0,
      pointsDeductMinor: 0,
      pointsEarn: 0,
      payableMinor,
    },
  };
}

async function render(items: CartItem[]) {
  useCartStore().items = items;
  const w = mount(ConfirmPage, {
    global: {
      stubs: {
        "sh-scaffold": { template: "<div><slot /></div>" },
        "sh-actionbar": { template: "<div><slot /></div>" },
        "sh-switch": true,
        "sh-chip": true,
        "phone-gate": true,
        PhoneGate: true,
        "biz-sku-row": {
          props: ["cover", "title", "spec", "size"],
          template: "<div><span>{{ title }}</span><slot /></div>",
        },
      },
      mocks: { $t: (k: string) => k },
    },
  });
  await settle(w);
  return w;
}

/** 等页面把 onMounted 与三个 watch 里的 await 走完 */
async function settle(w: { vm: { $nextTick: () => Promise<unknown> } }) {
  for (let i = 0; i < 12; i++) {
    await Promise.resolve();
    await w.vm.$nextTick();
  }
}

describe("结算页", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    query = {};
    orderPreview.mockResolvedValue(amount(2980));
    orderCapability.mockResolvedValue({ merchants: [], usablePayMethods: null, usablePayModes: ["ONLINE"] });
  });

  it("★★★ 迟到的试算响应不许盖住新的 —— 否则屏幕上是上一次的价", async () => {
    /*
     * 造一次真实的乱序：第一次请求（线上支付）**后**才返回，
     * 而第二次（当面付）先返回。没有那道序号闸的话，最后写进去的是 29.80。
     */
    const deferred: { resolve: (v: unknown) => void }[] = [];
    orderPreview.mockImplementation(
      () => new Promise((resolve) => deferred.push({ resolve })),
    );
    orderCapability.mockResolvedValue({
      merchants: [],
      usablePayMethods: null,
      usablePayModes: ["ONLINE", "OFFLINE"],
    });

    const w = await render([item()]);
    expect(deferred.length, "进页就该问一次").toBe(1);

    // 改支付方式 → 第二次试算
    const modes = w.findAll(".mode");
    expect(modes.length, "两种支付方式都要画出来").toBe(2);
    await modes[1]!.trigger("tap");
    await settle(w);
    expect(deferred.length).toBe(2);

    // 后发的先回，先发的后回
    deferred[1]!.resolve(amount(2500));
    await settle(w);
    deferred[0]!.resolve(amount(2980));
    await settle(w);

    // 断言**应付那一行**，不是整页文本 —— 商品单价也是 ¥29.80，
    // 拿整页去判「不含 29.80」会因为一个无关的数而永远红
    const payable = w.find(".actionbar__total").text();
    expect(payable, "显示的必须是最后一次问出来的那个数").toBe("¥25.00");
  });

  it("★★★ 提交不了要说是为什么 —— 快递单没选地址", async () => {
    query = { fulfillment: FULFILLMENT.EXPRESS };
    const w = await render([item({ fulfillment: FULFILLMENT.EXPRESS })]);

    expect(w.text()).toContain("confirm.whyNoAddress");
    expect(w.find(".actionbar__btn").classes()).toContain("is-disabled");
  });

  it("★★★ 上门单没选时段，说的是时段那句，不是地址那句", async () => {
    query = { fulfillment: FULFILLMENT.APPOINTMENT };
    const w = await render([item({ fulfillment: FULFILLMENT.APPOINTMENT })]);

    // 地址也没有，但**先说他能立刻动手改的那一条**，一次只说一条
    expect(w.text()).toContain("confirm.whyNoAddress");
    expect(w.text()).not.toContain("confirm.whyNoSlot");
  });

  it("★★★ 一件商品都没有 → 空态 + 回购物车，不是一页空白加个灰按钮", async () => {
    const w = await render([]);

    expect(w.text()).toContain("confirm.emptyItems");
    expect(w.text()).toContain("confirm.backToCart");
    // 主体一整段都不该画出来
    expect(w.text()).not.toContain("confirm.payable");
  });

  it("★★ 都齐了就不再说话，按钮也不灰", async () => {
    const w = await render([item()]);

    expect(w.text()).not.toContain("confirm.whyNoAddress");
    expect(w.find(".actionbar__btn").classes()).not.toContain("is-disabled");
  });

  it("★★ 服务端试算挂了 → 说明这是估算值，别让两个数长得一模一样", async () => {
    orderPreview.mockRejectedValue(new Error("network down"));
    const w = await render([item()]);

    expect(w.text()).toContain("confirm.estimateOnly");
  });
});
