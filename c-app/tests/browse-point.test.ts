/**
 * 「我现在在哪儿逛」的坐标，全端一个取法（真机报的：切了位置，门店列表的附近门店没跟着变）。
 *
 * 门店列表的「附近」按距离排，而它原来只读 location.active（存着的生效地址）——
 * 用 useTransient 临时切了个位置时 active 不变，距离点就停在旧地方。
 * 顶栏地址、商品池都跟着切了，唯独这里没有，因为它读的不是同一个「我在哪」。
 * browsePointE6 把优先级定下来：主动挑的点(transient) > 生效地址 > 被动定位(here)。
 */
import { beforeEach, describe, expect, it } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import { useLocationStore } from "@/stores/location";

describe("当前浏览点 browsePointE6", () => {
  beforeEach(() => setActivePinia(createPinia()));

  it("★★★ transient 压过生效地址 —— 切了位置，距离点要跟过去", () => {
    const s = useLocationStore();
    s.active = { tag: "家", latE6: 22_600_000, lngE6: 114_000_000 } as never;
    s.transientAt = { lat: 22.70, lng: 114.10 };
    expect(s.browsePointE6).toEqual({ latE6: 22_700_000, lngE6: 114_100_000 });
  });

  it("★★★ 没切位置时用生效地址（与下单取自提点同一个点）", () => {
    const s = useLocationStore();
    s.active = { tag: "家", latE6: 22_600_000, lngE6: 114_000_000 } as never;
    expect(s.browsePointE6).toEqual({ latE6: 22_600_000, lngE6: 114_000_000 });
  });

  it("★★ 地址没坐标（微信导入/手填）时回落到被动定位 here，而不是没有点", () => {
    const s = useLocationStore();
    s.active = { tag: "家", latE6: null, lngE6: null } as never;
    s.here = { coords: { lat: 22.66, lng: 114.03 }, coarse: false, place: null,
      regionName: null, at: Date.now() };
    expect(s.browsePointE6).toEqual({ latE6: 22_660_000, lngE6: 114_030_000 });
  });

  it("★ 什么都没有就是 null —— 调用方据此不传点，后端按评分排", () => {
    const s = useLocationStore();
    expect(s.browsePointE6).toBeNull();
  });
});
