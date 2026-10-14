// 切到一个「我们没有聚落」的地方时，旧归属必须让位。
//
// 2026-10-10 小程序连生产复现：买家在深圳绑着嘉逸花园，在选择位置页搜「景滑村」
// （山西运城临猗县，是**地图 POI**，不是我们的聚落）并切过去。
// `useTransient` 里 `loadNearby` 回 0 条、`resolveLocation` 给不出 innermostNo，
// 于是 `c` 为空、`bind` 根本不执行 —— **上一次在深圳绑的那个聚落原封不动留着**。
// 而首页算查询参数是 `communityNo ? ... : regionCode`：旧聚落在，区划码就完全不参与。
// 结果顶栏写着「景滑村」，首页列的却是深圳的货（金龙鱼、五得利），
// 而那几家店的经营范围只框了嘉逸花园。不报错、不空屏，两种状态在界面上长得一模一样。
import { beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";

const SZ = { communityNo: "C-SZ", name: "嘉逸花园", regionCode: "440309", kind: "ESTATE", pickups: [] };
const NEAR_A = { communityNo: "C-A", name: "景华新村", regionCode: "440309", kind: "ESTATE", pickups: [] };
const NEAR_B = { communityNo: "C-B", name: "景华新村东区", regionCode: "440309", kind: "ESTATE", pickups: [] };

/** 山西临猗那个点：附近一个聚落都没有 */
const EMPTY_AT = { lat: 35.119258, lng: 110.812812 };
/** 深圳龙华那个点：附近有两个，而「最内层围栏」解析不出来（POI 不在任何围栏里） */
const TWO_AT = { lat: 22.654588, lng: 114.023917 };

vi.mock("@/api", () => ({
  api: {
    nearbyCommunities: (lat: number) => Promise.resolve(lat > 30 ? [] : [NEAR_A, NEAR_B]),
    resolveLocation: (latE6: number) =>
      Promise.resolve(latE6 > 30_000_000
        // 临猗：没有 innermost，只给得出区；nearest 在 25 公里外，不该被当成「他在那儿」
        ? { innermostNo: null, nearestNo: "C-FAR", regionCode: "140821", regionName: "临猗县" }
        : { innermostNo: null, regionCode: "440309", regionName: "龙华区" }),
    bindCommunity: () => Promise.resolve({}),
  },
}));
vi.mock("@/stores/user", () => ({ useUserStore: () => ({ isLogin: true }) }));

async function stores() {
  const { useLocationStore } = await import("@/stores/location");
  const { useCommunityStore } = await import("@/stores/community");
  return { location: useLocationStore(), community: useCommunityStore() };
}

describe("切到没有聚落的地方", () => {
  beforeEach(() => setActivePinia(createPinia()));

  it("★★★ 旧归属必须清掉，并换成这个点的区 —— 否则首页还按深圳列货", async () => {
    const { location, community } = await stores();
    // 前置：他本来绑着深圳的嘉逸花园
    await community.bind(SZ as never);
    expect(community.community?.communityNo).toBe("C-SZ");

    await location.useTransient(EMPTY_AT, "景滑村");

    expect(community.community,
      "这个点上没有我们的聚落，旧的那个不能继续决定商品池 —— 撤掉修复这里还是 C-SZ")
      .toBeNull();
    expect(location.coarseRegion?.code,
      "要换成他现在这儿的区，首页才回落得到真能送到的货")
      .toBe("140821");
    expect(location.transientName, "顶栏显示他挑的那个名字").toBe("景滑村");
  });

  it("★★★ 点哪个聚落就绑哪个 —— 不许「附近第一个」替他做决定", async () => {
    const { location, community } = await stores();
    /*
     * 这个点解析不出 innermostNo（他点的聚落中心常常不是该坐标的最内层围栏），
     * 旧实现于是回落到 `list[0]` = 景华新村，而他点的是景华新村东区。
     * 绑错的后果不报错：商品池换成了另一个小区的。
     */
    await location.useTransient(TWO_AT, "景华新村东区", "C-B");
    expect(community.community?.communityNo,
      "传了聚落号就按它绑；撤掉修复这里会变成 list[0]（C-A）")
      .toBe("C-B");
  });

  it("★★ 不传聚落号时行为不变：按解析/附近第一个绑", async () => {
    const { location, community } = await stores();
    await location.useTransient(TWO_AT, "某个地点");
    expect(community.community?.communityNo).toBe("C-A");
  });
});
