// 「使用当前位置」切到别处看货，回到首页那一次 load() 不许把它顶回生效地址。
//
// 2026-09-28 小程序连生产复现：买家账号里有一条深圳的生效地址（「家」），
// 在选择位置页点「使用当前位置」切到运城 —— useTransient 绑上了运城的聚落，
// navigateBack 回首页，首页的 location.load() 按生效地址 syncCommunityFromActive，
// 又把归属绑回深圳。于是「使用当前位置」永远白点，本地与服务端都看不出发生过切换。
import { beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";

const YC = { communityNo: "C-YC", name: "高家垣社区", regionCode: "140802002", kind: "ESTATE", pickups: [] };
const SZ = { communityNo: "C-SZ", name: "棱镜·男生公寓", regionCode: "440309", kind: "ESTATE", pickups: [] };
const HOME_ADDR = { addressId: "A1", tag: "家", latE6: 22659412, lngE6: 114039783 };
const bindCommunity = vi.fn(() => Promise.resolve({}));

vi.mock("@/api", () => ({
  api: {
    activeAddress: () => Promise.resolve(HOME_ADDR),
    addressList: () => Promise.resolve([HOME_ADDR]),
    nearbyCommunities: (lat: number) => Promise.resolve(lat > 30 ? [YC] : [SZ]),
    resolveLocation: (latE6: number) =>
      Promise.resolve({ innermostNo: latE6 > 30_000_000 ? "C-YC" : "C-SZ", regionCode: "x" }),
    bindCommunity: (...a: unknown[]) => bindCommunity(...(a as [])),
  },
}));
vi.mock("@/stores/user", () => ({ useUserStore: () => ({ isLogin: true }) }));

async function stores() {
  const { useLocationStore } = await import("@/stores/location");
  const { useCommunityStore } = await import("@/stores/community");
  return { location: useLocationStore(), community: useCommunityStore() };
}

describe("临时位置撑得过回首页那一次 load()", () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    bindCommunity.mockClear();
  });

  it("★★★ 使用当前位置切到运城 → load() 之后仍是运城，不被生效地址（深圳）顶回去", async () => {
    const { location, community } = await stores();
    await location.useTransient({ lat: 35.026, lng: 111.007 });
    expect(community.community?.communityNo).toBe("C-YC");

    await location.load();

    expect(community.community?.communityNo, "被生效地址顶回了深圳").toBe("C-YC");
    expect(bindCommunity).not.toHaveBeenCalledWith("C-SZ", undefined);
  });

  it("★★ 对照：没有临时位置时，load() 照旧按生效地址同步归属", async () => {
    const { location, community } = await stores();
    await location.load();
    expect(community.community?.communityNo).toBe("C-SZ");
  });
});
