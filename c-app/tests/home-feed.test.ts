/**
 * 首页商品流：**一件商品只出一张卡**。
 *
 * 此前首页上半截三张团卡、下半截商品流 —— 同一只香梨一屏出现两次、两个价格。
 * 现在团并进它那件商品的卡里，有团的置顶、最早截止在前（shared/home-feed.ts）。
 */
import { describe, expect, it, vi } from "vitest";
import { mount } from "@vue/test-utils";
import { buildHomeFeed, joinableGroups } from "@/shared/home-feed";
import type { Goods, GroupBuy } from "@shared/types";

vi.mock("vue-i18n", () => ({ useI18n: () => ({ t: (k: string) => k }) }));

import GoodsCard from "@/components/biz/biz-goods-card.vue";

const NOW = 1_000_000;
const g = (goodsNo: string): Goods =>
  ({
    goodsNo, title: goodsNo, subtitle: "", cover: "", type: "GOODS", price: 5000, sales: 0,
    skus: [{ skuNo: "S1", optionValues: [], spec: "", price: 5000, stock: 9 }],
    merchant: { merchantNo: "M1", name: "虹选鲜果", logo: "", selfOperated: true },
  }) as unknown as Goods;
const grp = (groupNo: string, goodsNo: string, o: Partial<GroupBuy> = {}): GroupBuy =>
  ({
    groupNo, goodsNo, groupPrice: 500, basePrice: 5000, minCount: 3, joinedCount: 1,
    need: 2, reached: false, expireAt: NOW + 3600_000, ...o,
  }) as unknown as GroupBuy;

describe("首页商品流", () => {
  it("★★★ 有团的商品只出一张卡 —— 团并进去，不再另出一张", () => {
    const feed = buildHomeFeed([g("A"), g("B")], [grp("T1", "A"), grp("T2", "A")], NOW);
    expect(feed.map((i) => i.goods.goodsNo)).toEqual(["A", "B"]);
    expect(feed[0]?.group?.count).toBe(2);
    expect(feed[1]?.group).toBeUndefined();
  });

  it("★★★ 有团的置顶，最早截止的在前；其余保持后端顺序", () => {
    const feed = buildHomeFeed(
      [g("P1"), g("A"), g("P2"), g("B")],
      [grp("T1", "A", { expireAt: NOW + 7200_000 }), grp("T2", "B", { expireAt: NOW + 600_000 })],
      NOW,
    );
    expect(feed.map((i) => i.goods.goodsNo)).toEqual(["B", "A", "P1", "P2"]);
  });

  it("★★ 「最快还差」取差人最少的团，「去拼团」进的也是它；团价取最低", () => {
    const feed = buildHomeFeed(
      [g("A")],
      [grp("T1", "A", { need: 2, groupPrice: 600 }), grp("T2", "A", { need: 1, groupPrice: 500 })],
      NOW,
    );
    expect(feed[0]?.group).toMatchObject({ need: 1, groupNo: "T2", groupPrice: 500 });
  });

  it("★★ 截止了的、满员了的团不算 —— 那件商品回到普通卡", () => {
    const gs = [grp("T1", "A", { expireAt: NOW - 1 }), grp("T2", "A", { reached: true })];
    expect(joinableGroups(gs, NOW)).toEqual([]);
    expect(buildHomeFeed([g("A")], gs, NOW)[0]?.group).toBeUndefined();
  });

  it("★ 商品流里重复的商品也只出一张", () => {
    expect(buildHomeFeed([g("A"), g("A")], [], NOW)).toHaveLength(1);
  });
});

describe("商品卡 · 团形态", () => {
  const mk = (group?: ReturnType<typeof buildHomeFeed>[number]["group"]) =>
    mount(GoodsCard, {
      props: { goods: g("A"), group },
      global: { stubs: { "sh-cover": true }, mocks: { $t: (k: string) => k } },
    });

  it("★★★ 有团：去拼团替掉「＋」，点它发 join 不发 tap", async () => {
    const group = buildHomeFeed([g("A")], [grp("T1", "A")], NOW)[0]!.group;
    const w = mk(group);
    expect(w.find(".add").exists()).toBe(false);
    expect(w.text()).toContain("home.groupJoin");
    expect(w.text()).toContain("home.groupTag");
    await w.find(".join").trigger("tap");
    expect(w.emitted("join")).toHaveLength(1);
    expect(w.emitted("tap")).toBeUndefined();
  });

  it("★★ 没团：还是普通卡", () => {
    const w = mk(undefined);
    expect(w.find(".add").exists()).toBe(true);
    expect(w.text()).not.toContain("home.groupJoin");
  });
});
