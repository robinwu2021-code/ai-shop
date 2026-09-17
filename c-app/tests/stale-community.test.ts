// 升级前绑过的那个聚落已经不在了，怎么办（真机 2026-09-17 报的那条）。
//
// **为什么必须是单测，不是 e2e**：模拟器里那个账号在服务端有生效地址，
// 每次首页加载都会按地址重新绑一次 —— 于是「旧归属被清掉了」这个结果，
// 走的可能是地址同步那条路，与这段代码毫无关系。我为此绿过一次假的：
// e2e 显示 storage 从「翡翠城」变成了「福民社区」，而抓请求一看，
// 它问的是福民社区，**根本没问过翡翠城**。
//
// 这里把外面那两条路都掐掉（没有服务端地址、定位也不给），
// 能清掉旧归属的就只剩要测的那一段。
import { beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import { ApiError } from "@shared/net/http-client";

const communityDetail = vi.fn();
vi.mock("@/api", () => ({
  api: {
    communityDetail: (...a: unknown[]) => communityDetail(...a),
    activeAddress: () => Promise.resolve(null),
    addressList: () => Promise.resolve([]),
    resolveLocation: () => Promise.resolve(null),
    bindCommunity: () => Promise.resolve({}),
    nearbyCommunities: () => Promise.resolve([]),
  },
}));
// 定位一律拒：这条用例要测的是「旧归属还在不在」，不是定位那一支
vi.mock("@shared/ports/location", () => ({
  getLocationDetailed: () => Promise.resolve({ ok: false, reason: "denied" }),
  getLocation: () => Promise.resolve(null),
}));

const STALE = {
  communityNo: "C0002", name: "翡翠城", address: "杭州市西湖区文二西路 200 号",
  cityCode: "3301", regionCode: "330106002", kind: "ESTATE", pickups: [],
};

async function stores() {
  const { useLocationStore } = await import("@/stores/location");
  const { useCommunityStore } = await import("@/stores/community");
  return { location: useLocationStore(), community: useCommunityStore() };
}

describe("存着的聚落已经不在了", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    communityDetail.mockReset();
  });

  it("★★★ 服务端答「没有这条」→ 清掉，人才可能被重新匹配", async () => {
    communityDetail.mockRejectedValue(new ApiError(10404, "社区不存在"));
    const { location, community } = await stores();
    community.community = STALE as never;

    await location.ensureCoarseRegion();

    expect(communityDetail).toHaveBeenCalledWith("C0002");
    expect(community.community)
      .toBeNull();
  });

  it("★★★ 网络不通 → **不清** —— 地铁里刷一下不该变成「你的位置没了」", async () => {
    communityDetail.mockRejectedValue(new Error("request:fail timeout"));
    const { location, community } = await stores();
    community.community = STALE as never;

    await location.ensureCoarseRegion();

    expect(communityDetail).toHaveBeenCalled();
    expect(community.community, "一次网络抖动把用户的位置抹了")
      .not.toBeNull();
  });

  it("★★ 聚落还在 → 不动它，也不重复问", async () => {
    communityDetail.mockResolvedValue(STALE);
    const { location, community } = await stores();
    community.community = STALE as never;

    await location.ensureCoarseRegion();
    await location.ensureCoarseRegion();
    await location.ensureCoarseRegion();

    expect(community.community).not.toBeNull();
    expect(communityDetail, "每次进首页都问一遍是白花的往返").toHaveBeenCalledTimes(1);
  });
});
