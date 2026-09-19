/**
 * 模糊定位时顶栏只说到区（2026-09-19 真机）。
 *
 * 人在龙华体育馆，顶栏写「棱镜·男生公寓(清湖地铁站总店)」：小程序只能 getFuzzyLocation，
 * 坐标偏几公里、只准到区，后端拿偏移点反查出的是旁边某个楼盘。用精确的名字说一个
 * 只准到区的位置 = 说假话。现在：模糊 → 区名 + 「大概位置」；精确 → 照旧说地名。
 */
import { beforeEach, describe, expect, it } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import { useLocationStore } from "@/stores/location";

const place = (name: string) => ({ name, address: null, kind: "POI", source: "MAP", stale: false }) as never;
const here = (coarse: boolean) => ({
  coords: { lat: 22.66, lng: 114.03 }, coarse, place: place("棱镜·男生公寓(清湖地铁站总店)"),
  regionName: "龙华区", at: Date.now(),
});

describe("顶栏地名 · 模糊定位", () => {
  beforeEach(() => setActivePinia(createPinia()));

  it("★★★ 模糊定位：说区名，标「大概位置」，不说反查出来的楼盘", () => {
    const s = useLocationStore();
    s.here = here(true);
    expect(s.label).toBe("龙华区");
    expect(s.approx).toBe(true);
    expect(s.hereName, "地址页 / 选点页那张卡同样只说区").toBe("龙华区");
  });

  it("★★★ 精确定位：照旧说地名，不标", () => {
    const s = useLocationStore();
    s.here = here(false);
    expect(s.label).toBe("棱镜·男生公寓(清湖地铁站总店)");
    expect(s.approx).toBe(false);
    expect(s.hereName).toBe("棱镜·男生公寓(清湖地铁站总店)");
  });

  it("★★ 用户自己挑过地址：听他的，不标大概", () => {
    const s = useLocationStore();
    s.here = here(true);
    s.active = { tag: "家", detail: "龙华体育馆", region: "" } as never;
    s.pickedByUser = true;
    expect(s.label).toBe("家");
    expect(s.approx).toBe(false);
  });

  it("★ 模糊但连区名都没有：退到原来的顺序，不空着", () => {
    const s = useLocationStore();
    s.here = { ...here(true), regionName: null };
    s.coarseRegion = { code: "440309", name: "龙华区" };
    expect(s.label).toBe("龙华区");
  });
});
