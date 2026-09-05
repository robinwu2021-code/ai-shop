/**
 * 购物车页的**行为**回归位。
 *
 * <p>这一页在 2026-09-05 动手前查出三处会算错账的缺陷，共同点是**都不报错**：
 * <ol>
 *   <li>失效件一件都不渲染 —— `groups` 只遍历有效件，那段「说清为什么不可售」的模板
 *       一次都没渲染过。货不是灰着躺在车里，是**凭空消失**；</li>
 *   <li>底栏件数把失效件算了进去（`count` 走全部、合计走有效件），
 *       同一条底栏上的两个数不是一回事；</li>
 *   <li>数量减到 1 再点减号 → 传 0 下去，后端与 mock 都当删除，**静默删掉**。</li>
 * </ol>
 *
 * <p>三条都是「源码扫描式守卫看不见」的那一类：字段在、模板在、类型也对，
 * 只是那段 DOM 永远进不去、或者那个数不是那个意思。
 * 所以这里把页面真的挂起来，看它渲染出什么、点下去发生什么。
 *
 * <p>断言用 **i18n key** 而不是中文文案：文案随时会改，
 * 而「显示的是哪一种状态」不该随文案漂移。
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import { uniMock } from "./setup";
import { FULFILLMENT } from "@shared/utils/constants";
import type { CartItem } from "@shared/types";

const cartList = vi.fn();
const cartUpdate = vi.fn();
const cartRemove = vi.fn();

vi.mock("@/api", () => ({
  api: {
    cartList: (...a: unknown[]) => cartList(...a),
    cartAdd: vi.fn(),
    cartUpdate: (...a: unknown[]) => cartUpdate(...a),
    cartRemove: (...a: unknown[]) => cartRemove(...a),
  },
}));

vi.mock("vue-i18n", () => ({ useI18n: () => ({ t: (k: string) => k }) }));

/** 确认弹层默认「点确定」。要测「点取消」的用例自己覆盖它 */
const confirmMock = vi.fn(() => Promise.resolve(true));
const promptMock = vi.fn(() => Promise.resolve<string | null>(null));
vi.mock("@ai-shop/ui/prompt", () => ({
  confirm: (...a: unknown[]) => confirmMock(...(a as [])),
  prompt: (...a: unknown[]) => promptMock(...(a as [])),
}));

import CartPage from "@/pages/cart/index.vue";
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

/**
 * 挂载并等 `load()` 跑完。
 *
 * <p>`onShow` 在 setup.ts 里是 `vi.fn()`（不执行），所以这里**自己调一次 load**，
 * 而不是指望挂载顺手把数据拉进来 —— 指望它的话，每条用例断言的都是空页面，
 * 而空页面什么都不违反。
 */
async function render() {
  const store = useCartStore();
  await store.load();
  const w = mount(CartPage, {
    global: {
      stubs: {
        "sh-scaffold": { template: "<div><slot /></div>" },
        "sh-actionbar": { template: "<div><slot /></div>" },
        "sh-check": { props: ["modelValue"], template: "<i :data-on='String(modelValue)'></i>" },
        "sh-icon": { props: ["name"], template: "<i :data-icon='name'></i>" },
        "sh-chip": true,
        // 标题由它画 —— stub 里不画出来的话，「这一件到底在不在页面上」就断言不了
        "biz-sku-row": {
          props: ["cover", "title", "spec", "size"],
          template: "<div class='row'><span class='row__t'>{{ title }}</span><slot /></div>",
        },
      },
      mocks: { $t: (k: string, v?: Record<string, unknown>) => (v ? `${k}:${JSON.stringify(v)}` : k) },
    },
  });
  await w.vm.$nextTick();
  return { w, store };
}

describe("购物车页", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    confirmMock.mockResolvedValue(true);
    promptMock.mockResolvedValue(null);
    cartUpdate.mockImplementation(() => Promise.resolve([]));
    cartRemove.mockImplementation(() => Promise.resolve([]));
  });

  it("★★★ 失效件必须画在页面上 —— 此前 groups 只遍历有效件，它凭空消失", async () => {
    cartList.mockResolvedValue([
      item({ skuNo: "S1" }),
      item({ skuNo: "S2", title: "下架的油", invalid: true }),
    ]);
    const { w } = await render();

    expect(w.text(), "失效区的标题").toContain("cart.invalidTitle");
    expect(w.text(), "要说出是为什么").toContain("cart.invalidOffShelf");
    expect(w.text()).toContain("下架的油");
  });

  it("★★★ 售罄（available=0）也进失效区，不许留在有效区里能勾能算钱", async () => {
    cartList.mockResolvedValue([
      item({ skuNo: "S1" }),
      item({ skuNo: "S2", title: "卖光的米", available: 0 }),
    ]);
    const { w, store } = await render();

    expect(w.text()).toContain("cart.invalidSoldOut");
    expect(store.validItems.map((i) => i.skuNo)).toEqual(["S1"]);
    expect(store.selected).toEqual(["S1"]);
  });

  it("★★★ 底栏的件数是**勾选**件数，不是车里的件数", async () => {
    cartList.mockResolvedValue([
      item({ skuNo: "S1", qty: 2 }),
      item({ skuNo: "S2", qty: 3, invalid: true }),
    ]);
    const { store } = await render();

    // count 是「车里有几件」（tabBar 角标用它），含失效件
    expect(store.count).toBe(5);
    // 而底栏与结算按钮用的是这个
    expect(store.selectedCount).toBe(2);
    expect(store.selectedTotalFen).toBe(2980 * 2);
  });

  it("★★★ qty=1 时点减号不许把商品删掉 —— 那是删除，不是减数量", async () => {
    cartList.mockResolvedValue([item({ skuNo: "S1", qty: 1 })]);
    const { w } = await render();

    const minus = w.findAll(".stepper__btn")[0]!;
    expect(minus.classes(), "到底了要看得出来").toContain("is-off");
    await minus.trigger("tap");
    expect(cartUpdate, "一次都不许调 —— 调了就是传 0 下去").not.toHaveBeenCalled();
  });

  it("★★ 加号在可售库存用尽时停住", async () => {
    cartList.mockResolvedValue([item({ skuNo: "S1", qty: 2, available: 2 })]);
    const { w } = await render();

    const plus = w.findAll(".stepper__btn")[1]!;
    expect(plus.classes()).toContain("is-off");
    await plus.trigger("tap");
    expect(cartUpdate).not.toHaveBeenCalled();
  });

  it("★★ 库存没用尽时加号照常工作", async () => {
    cartList.mockResolvedValue([item({ skuNo: "S1", qty: 1, available: 9 })]);
    const { w } = await render();

    await w.findAll(".stepper__btn")[1]!.trigger("tap");
    expect(cartUpdate).toHaveBeenCalledWith("S1", 2);
  });

  it("★★ 后端没给 available 时不设上限 —— 当成 0 会让整车一件都加不了", async () => {
    cartList.mockResolvedValue([item({ skuNo: "S1", qty: 1 })]);
    const { w } = await render();

    expect(w.findAll(".stepper__btn")[1]!.classes()).not.toContain("is-off");
    await w.findAll(".stepper__btn")[1]!.trigger("tap");
    expect(cartUpdate).toHaveBeenCalledWith("S1", 2);
  });

  it("★★★ 默认勾**件数最多的那一组**，不是第一组", async () => {
    cartList.mockResolvedValue([
      // 很久以前加的一件快递商品排在前面
      item({ skuNo: "E1", fulfillment: FULFILLMENT.EXPRESS }),
      item({ skuNo: "P1", qty: 2 }),
      item({ skuNo: "P2", qty: 3 }),
    ]);
    const { store } = await render();

    expect(store.activeFulfillment).toBe(FULFILLMENT.PICKUP);
    expect([...store.selected].sort()).toEqual(["P1", "P2"]);
  });

  it("★★★ 勾另一种取货方式 → 前一组整组让位，且**要出一句话**", async () => {
    cartList.mockResolvedValue([
      item({ skuNo: "P1" }),
      item({ skuNo: "P2" }),
      item({ skuNo: "E1", fulfillment: FULFILLMENT.EXPRESS }),
    ]);
    const { w, store } = await render();
    expect([...store.selected].sort()).toEqual(["P1", "P2"]);

    // 三个勾选框：两组的组头 + 三件商品。找到快递那件的那一个
    const boxes = w.findAll(".line .box");
    await boxes[2]!.trigger("tap");

    expect(store.selected).toEqual(["E1"]);
    expect(store.activeFulfillment).toBe(FULFILLMENT.EXPRESS);
    expect(uniMock.showToast, "静默切换会让人以为刚才勾的还在").toHaveBeenCalled();
    expect(uniMock.showToast.mock.calls[0]![0].title).toContain("cart.oneFulfillmentOnly");
  });

  it("★★ 全选只作用于当前那一组，不是全车", async () => {
    cartList.mockResolvedValue([
      item({ skuNo: "P1" }),
      item({ skuNo: "P2" }),
      item({ skuNo: "E1", fulfillment: FULFILLMENT.EXPRESS }),
    ]);
    const { store } = await render();

    store.setAllInActive(false);
    expect(store.selected).toEqual([]);
    store.setAllInActive(true);
    expect([...store.selected].sort()).toEqual(["P1", "P2"]);
  });

  it("★★ 一件都没勾就点去结算 → 不跳页，说一句", async () => {
    cartList.mockResolvedValue([item({ skuNo: "S1" })]);
    const { w, store } = await render();
    store.setAllInActive(false);
    await w.vm.$nextTick();

    await w.find(".bar__btn").trigger("tap");
    expect(uniMock.navigateTo).not.toHaveBeenCalled();
    expect(uniMock.showToast.mock.calls.at(-1)![0].title).toContain("cart.pickSomething");
  });

  it("★★★ 去结算只带勾选的那几件 —— 带全部的话下一页的应付对不上底栏", async () => {
    cartList.mockResolvedValue([item({ skuNo: "S1" }), item({ skuNo: "S2" })]);
    const { w, store } = await render();

    store.toggle("S2");
    await w.vm.$nextTick();
    await w.find(".bar__btn").trigger("tap");

    const url = uniMock.navigateTo.mock.calls[0]![0].url as string;
    expect(url).toContain(`fulfillment=${FULFILLMENT.PICKUP}`);
    expect(url).toContain("skus=S1");
    expect(url).not.toContain("S2");
  });

  it("★★★ 编辑态删的是**勾出来的那些**，且删之前要问一句", async () => {
    cartList.mockResolvedValue([item({ skuNo: "S1" }), item({ skuNo: "S2" })]);
    const { w, store } = await render();

    await w.find(".topbar .sh-link").trigger("tap");
    await w.vm.$nextTick();
    await w.findAll(".line .box")[0]!.trigger("tap");

    expect(store.marked).toEqual(["S1"]);
    // 编辑态的勾不许动结算勾选：两套是两个字段
    expect([...store.selected].sort()).toEqual(["S1", "S2"]);

    await w.find(".bar__btn").trigger("tap");
    await w.vm.$nextTick();
    expect(confirmMock).toHaveBeenCalled();
    expect(cartRemove).toHaveBeenCalledWith(["S1"]);
  });

  it("★★ 删除弹层点取消 → 一件都不删", async () => {
    confirmMock.mockResolvedValue(false);
    cartList.mockResolvedValue([item({ skuNo: "S1" })]);
    const { w } = await render();

    await w.find(".topbar .sh-link").trigger("tap");
    await w.vm.$nextTick();
    await w.findAll(".line .box")[0]!.trigger("tap");
    await w.find(".bar__btn").trigger("tap");
    await w.vm.$nextTick();

    expect(cartRemove).not.toHaveBeenCalled();
  });

  it("★★ 空态要等第一次拉完 —— 不等的话冷启动会闪一下「购物车是空的」", async () => {
    cartList.mockResolvedValue([]);
    const store = useCartStore();
    const w = mount(CartPage, {
      global: {
        stubs: { "sh-scaffold": { template: "<div><slot /></div>" } },
        mocks: { $t: (k: string) => k },
      },
    });
    // 还没拉过：不许出现空态
    expect(store.loaded).toBe(false);
    expect(w.text()).not.toContain("cart.empty");

    await store.load();
    await w.vm.$nextTick();
    expect(w.text()).toContain("cart.empty");
    expect(w.text(), "空态要给出路，不是一句话就完了").toContain("cart.goShopping");
  });

  it("★★ 用户把勾全取消之后，重新 load 不许替他勾回来", async () => {
    cartList.mockResolvedValue([item({ skuNo: "S1" }), item({ skuNo: "S2" })]);
    const { store } = await render();

    store.setAllInActive(false);
    expect(store.selected).toEqual([]);
    await store.load();
    expect(store.selected, "「挑完了，一件都不要」不是「还没挑过」").toEqual([]);
  });

  it("★★ 持久化下来的勾选里混着已经不在车里的 sku → 剪掉", async () => {
    const store = useCartStore();
    store.selected = ["GONE", "S1"];
    cartList.mockResolvedValue([item({ skuNo: "S1" })]);
    await store.load();

    expect(store.selected).toEqual(["S1"]);
  });
});
