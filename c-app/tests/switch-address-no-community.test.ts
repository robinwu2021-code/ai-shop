// 切到一条「有坐标、但那儿没有我们聚落」的收货地址时，商品列表要跟着变。
//
// 2026-10-10 用户上报：从「家·嘉逸花园」（深圳，有聚落）切到「家·景滑村」
// （山西运城临猗县，有坐标但那儿没有我们的聚落），**产品列表没变**。
//
// 这是 useTransient（地图选点）那个 bug 的孪生，在切收货地址这条路
// （switchTo → syncCommunityFromActive）上一直没修：loadNearby 回空、c 为 undefined，
// 原来直接 return false 而旧归属原封不动，首页继续按旧聚落（嘉逸花园）列深圳的货。
import { beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";

const SZ = { communityNo: "C-SZ", name: "嘉逸花园", regionCode: "440309", kind: "ESTATE", pickups: [] };
/** 景滑村那条地址：有坐标，在山西临猗 */
const SX_ADDR = { addressId: "A2", tag: "家", detail: "景滑村", latE6: 35119258, lngE6: 110812812 };

vi.mock("@/api", () => ({
  api: {
    // 山西那个点：附近一个聚落都没有
    nearbyCommunities: (lat: number) => Promise.resolve(lat > 30 ? [] : [SZ]),
    resolveLocation: (latE6: number) =>
      Promise.resolve(latE6 > 30_000_000
        ? { innermostNo: null, regionCode: "140821", regionName: "临猗县" }
        : { innermostNo: "C-SZ", regionCode: "440309", regionName: "龙华区" }),
    bindCommunity: () => Promise.resolve({}),
  },
}));
vi.mock("@/stores/user", () => ({ useUserStore: () => ({ isLogin: true }) }));

async function stores() {
  const { useLocationStore } = await import("@/stores/location");
  const { useCommunityStore } = await import("@/stores/community");
  return { location: useLocationStore(), community: useCommunityStore() };
}

describe("切到没有聚落的收货地址", () => {
  beforeEach(() => setActivePinia(createPinia()));

  it("★★★ 旧归属必须清掉、按新地址的区筛 —— 否则切了地址列表不变", async () => {
    const { location, community } = await stores();
    await community.bind(SZ as never);               // 前置：绑着嘉逸花园
    expect(community.community?.communityNo).toBe("C-SZ");

    location.active = SX_ADDR as never;              // 切到景滑村那条地址
    const changed = await location.syncCommunityFromActive();

    expect(community.community,
      "景滑村那儿没有我们的聚落，旧的那个不能继续决定商品池 —— 撤掉修复这里还是 C-SZ")
      .toBeNull();
    expect(location.coarseRegion?.code,
      "要换成景滑村的区（临猗县），首页据此列这个区的货")
      .toBe("140821");
    expect(changed, "商品池确实跟着地址变了 —— 顶栏该说新地址，不该说「仍按原范围」").toBe(true);
  });

  it("★★ 真没坐标的地址：保持现状、返回 false（这一条行为不变）", async () => {
    const { location, community } = await stores();
    await community.bind(SZ as never);
    location.active = { addressId: "A3", tag: "家", detail: "手填的地址", latE6: null, lngE6: null } as never;
    const changed = await location.syncCommunityFromActive();
    expect(community.community?.communityNo, "没坐标推不出聚落，保持现状").toBe("C-SZ");
    expect(changed).toBe(false);
  });
});
