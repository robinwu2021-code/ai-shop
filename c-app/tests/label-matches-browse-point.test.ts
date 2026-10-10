// 顶栏说的和按哪个点取货，必须是同一件事。
//
// 2026-10-10 用户在小程序上报：从地图上选了点，**列表按新点换了，顶栏没变**。
// 根因是两个 getter 口径相反 —— `browsePointE6` 把「主动挑的点」排在生效地址前面
// （它的注释还写着「优先级与 label 同序」），而 `label` 把生效地址排在前面。
// 于是坐标跟着新点走、名字跟着旧地址走：他看到的货是按那个点算的，顶栏却在说假话。
//
// 这是「切了位置顶栏没变」那个老缺陷的另一半：上次修的是 transient 被**被动定位**压过，
// 这次是被**生效地址**压过。
import { beforeEach, describe, expect, it, vi } from "vitest";
import { createPinia, setActivePinia } from "pinia";

const ADDR = {
  addressId: "A1", tag: "家", detail: "深圳某处", region: "广东省深圳市龙华区",
  latE6: 22659412, lngE6: 114039783,
};
const CM = { communityNo: "C-SZ", name: "嘉逸花园", regionCode: "440309", kind: "ESTATE", pickups: [] };

vi.mock("@/api", () => ({
  api: {
    switchActiveAddress: () => Promise.resolve(ADDR),
    nearbyCommunities: () => Promise.resolve([CM]),
    resolveLocation: () => Promise.resolve({ innermostNo: "C-SZ", regionCode: "440309" }),
    bindCommunity: () => Promise.resolve({}),
  },
}));
vi.mock("@/stores/user", () => ({ useUserStore: () => ({ isLogin: true }) }));

async function loc() {
  const { useLocationStore } = await import("@/stores/location");
  return useLocationStore();
}

describe("顶栏与取货点同序", () => {
  beforeEach(() => setActivePinia(createPinia()));

  it("★★★ 挑过地址之后再从地图选点：顶栏要说新点，不能还挂着「家」", async () => {
    const location = await loc();
    await location.switchTo("A1");
    expect(location.label, "前置：切了地址，顶栏是「家」").toBe("家");

    await location.useTransient({ lat: 35.119258, lng: 110.812812 }, "景滑村");

    expect(location.label,
      "顶栏必须跟着走 —— 撤掉修复这里还是「家」，而货已经按景滑村换过了")
      .toBe("景滑村");
    expect(location.browsePointE6?.latE6,
      "对照：取货点本来就跟着走，两者必须是同一件事")
      .toBe(35119258);
  });

  it("★★★ 反过来也对：选完点再切回地址，顶栏回到地址名", async () => {
    const location = await loc();
    await location.useTransient({ lat: 35.119258, lng: 110.812812 }, "景滑村");
    expect(location.label).toBe("景滑村");

    await location.switchTo("A1");
    expect(location.label, "switchTo 会把 transient 清掉，所以这里不会反向卡住").toBe("家");
    expect(location.browsePointE6?.latE6).toBe(ADDR.latE6);
  });

  it("★★ 切地址会清掉上一次推出来的「区」—— 否则他已经离开的地方还在筛货", async () => {
    const location = await loc();
    location.coarseRegion = { code: "140821", name: "临猗县" };
    await location.switchTo("A1");
    expect(location.coarseRegion).toBeNull();
  });
});
