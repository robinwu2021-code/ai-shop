/**
 * 拼团买家流程（原型 p04–p08 / p12 · TDD-C端拼团买家流程）。
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import type { GroupBuy, Order } from "@shared/types";

const groupBuyDetail = vi.fn();
const orderDetail = vi.fn();
const myJoinedGroups = vi.fn();
const query: Record<string, string> = {};
const share = { yes: true };

vi.mock("@/api", () => ({
  api: {
    groupBuyDetail: (...a: unknown[]) => groupBuyDetail(...a),
    orderDetail: (...a: unknown[]) => orderDetail(...a),
    myJoinedGroups: (...a: unknown[]) => myJoinedGroups(...a),
    invoiceOfOrder: vi.fn(() => Promise.resolve(null)),
    goodsDetail: vi.fn(), cartList: vi.fn(() => Promise.resolve([])),
  },
}));
vi.mock("vue-i18n", () => ({ useI18n: () => ({ t: (k: string) => k }) }));
vi.mock("@shared/ports/share", () => ({ buildShareMessage: vi.fn(() => ({})), canNativeShare: () => share.yes }));
vi.mock("@ai-shop/ui/prompt", () => ({ confirm: vi.fn(), prompt: vi.fn() }));
vi.mock("@dcloudio/uni-app", () => ({
  onLoad: (cb: (q: Record<string, string>) => unknown) => cb(query),
  onShow: (cb: () => unknown) => cb(),
  onHide: vi.fn(), onUnload: vi.fn(), onPullDownRefresh: vi.fn(), onReachBottom: vi.fn(),
  onShareAppMessage: vi.fn(), onPageScroll: vi.fn(),
}));

import GroupPage from "@/pages/group/index.vue";
import OrderPage from "@/pages/order/index.vue";
import MyGroupsPage from "@/pages/my-groups/index.vue";
import { useUserStore } from "@/stores/user";

const FUTURE = Date.now() + 3_600_000;
function grp(over: Partial<GroupBuy> = {}): GroupBuy {
  return {
    groupNo: "GB1", goodsNo: "G1", title: "香梨", cover: "🍐", status: "OPEN", basePrice: 5000, groupPrice: 500,
    minCount: 2, joinedCount: 1, reached: false, need: 1, expireAt: FUTURE, members: [{ nickname: "王" }],
    joined: false, isOwner: false, pickupNo: "", pickupName: "", merchant: { merchantNo: "M1", name: "虹选鲜果" },
    ...over,
  } as unknown as GroupBuy;
}

const stubs = {
  "sh-scaffold": { template: "<div><slot /></div>" }, "sh-actionbar": { template: "<div><slot /></div>" },
  "sh-cover": true, "sh-icon": true, "sh-chip": true, "sh-tabs": true, "biz-group-card": { props: ["group"], template: "<div class='gc'>{{ group.groupNo }}</div>" },
  "biz-sku-row": { template: "<div><slot /></div>" },
};
async function render(C: unknown) {
  const w = mount(C as never, { global: { stubs, mocks: { $t: (k: string) => k } } });
  for (let i = 0; i < 10; i++) {
    await Promise.resolve();
    await w.vm.$nextTick();
  }
  return w;
}

describe("团页（p04–p07）", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    for (const k of Object.keys(query)) delete query[k];
    query.groupNo = "GB1";
    share.yes = true;
  });

  it("★★★ p04 付完款落到团页：说「付款成功，已开团」，主按钮只剩「邀请邻居来拼」", async () => {
    query.paid = "1";
    groupBuyDetail.mockResolvedValue(grp({ joined: true, isOwner: true }));
    const w = await render(GroupPage);
    expect(w.text()).toContain("group.paidOpened");
    expect(w.text()).toContain("group.invite");
    expect(w.text()).not.toContain("group.joinAt");
  });

  it("★★★ p05 团详情有商品行（图 + 名 + 团价 + 单买价），不再是「商品：香梨」一行字", async () => {
    groupBuyDetail.mockResolvedValue(grp());
    const w = await render(GroupPage);
    const row = w.find(".goodsrow");
    expect(row.exists()).toBe(true);
    expect(row.text()).toContain("香梨");
    expect(row.text()).toContain("group.soloPrice");
    expect(w.text()).toContain("group.joinAt");
  });

  it("★★ p06 已成团：状态轴 +「查看订单」（有 myOrderNo 才出）", async () => {
    groupBuyDetail.mockResolvedValue(grp({ status: "FORMED", joined: true, myOrderNo: "S1" }));
    const w = await render(GroupPage);
    expect(w.find(".steps").exists()).toBe(true);
    expect(w.text()).toContain("group.viewOrder");
  });

  it("★★ p07 没凑齐：付过款的人看到退款说明与两条出路", async () => {
    groupBuyDetail.mockResolvedValue(grp({ status: "FAILED", joined: true }));
    const w = await render(GroupPage);
    expect(w.text()).toContain("group.refunded");
    expect(w.text()).toContain("group.buyAlone");
    expect(w.text()).toContain("group.reopen");
  });
});

describe("订单详情的拼团进度卡（p08）", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    for (const k of Object.keys(query)) delete query[k];
    query.orderNo = "O1";
  });
  const order = (over: Partial<Order> = {}) => ({
    orderNo: "O1", status: "PAID", fulfillment: "EXPRESS", merchantNo: "M1", merchantName: "虹选鲜果",
    items: [], amount: { goodsMinor: 500, freightMinor: 0, discountMinor: 0, payableMinor: 500, paidMinor: 500 },
    timeline: [], createdAt: Date.now(), ...over,
  }) as unknown as Order;

  it("★★★ 团单画进度卡（还差几人 + 邀请）；非团单不画、也不去取团", async () => {
    orderDetail.mockResolvedValue(order({ groupNo: "GB1" }));
    groupBuyDetail.mockResolvedValue(grp({ joined: true }));
    let w = await render(OrderPage);
    expect(w.find(".grpcard").exists()).toBe(true);
    expect(w.text()).toContain("order.groupNeed");
    expect(w.text()).toContain("group.invite");

    vi.clearAllMocks();
    orderDetail.mockResolvedValue(order());
    w = await render(OrderPage);
    expect(w.find(".grpcard").exists()).toBe(false);
    expect(groupBuyDetail).not.toHaveBeenCalled();
  });
});

describe("我的拼团（p12）", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    useUserStore().token = "ctk_x";
  });

  it("★★ 按状态分栏：截止了还没结算的算「没凑齐」，不挂在拼团中", async () => {
    myJoinedGroups.mockResolvedValue([
      grp({ groupNo: "A" }),
      grp({ groupNo: "B", status: "FORMED" }),
      grp({ groupNo: "C", expireAt: Date.now() - 1000 }),
    ]);
    const w = await render(MyGroupsPage);
    expect(w.findAll(".gc").map((x) => x.text())).toEqual(["A"]);
  });
});
