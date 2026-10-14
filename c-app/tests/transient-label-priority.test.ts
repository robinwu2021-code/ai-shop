/**
 * 切换浏览位置后，顶栏要跟着换（真机报的：切了位置商品变了，顶栏地址没变）。
 *
 * 根因是 `label` 的优先级：用户在选点页主动挑了「逛这儿」(transientAt) 是一个**明确动作**，
 * 和「挑了一条地址」(pickedByUser) 同级，都该压过**被动定位** here。但原来 transient 排在
 * here 之后 —— 首页一加载 ensureHere() 就把 here 填上，于是 transient 永远轮不到，
 * 顶栏一直显示定位到的地名，而商品已经按切过去的社区换了。两种状态显示成同一个样子。
 */
import { beforeEach, describe, expect, it } from "vitest";
import { createPinia, setActivePinia } from "pinia";
import { useLocationStore } from "@/stores/location";

const place = (name: string) => ({ name, address: null, kind: "POI", source: "MAP", stale: false }) as never;
const hereAt = (coarse: boolean) => ({
  coords: { lat: 22.66, lng: 114.03 }, coarse, place: place("定位到的某楼盘"),
  regionName: "龙华区", at: Date.now(),
});

describe("顶栏地名 · 主动切位置压过被动定位", () => {
  beforeEach(() => setActivePinia(createPinia()));

  it("★★★ 切到某社区（transient）要压过精确定位 here —— 否则顶栏永远显示定位地名", () => {
    const s = useLocationStore();
    s.here = hereAt(false);                 // 首页 ensureHere() 填的被动定位
    s.transientAt = { lat: 22.70, lng: 114.10 };
    s.transientName = "嘉逸花园";            // 用户在选点页挑的那个
    expect(s.label).toBe("嘉逸花园");
    expect(s.approx, "主动挑的是精确意图，别标「大概位置」").toBe(false);
  });

  it("★★★ transient 也要压过模糊定位 here.coarse，且不标大概", () => {
    const s = useLocationStore();
    s.here = hereAt(true);                  // 模糊定位，here.coarse=true
    s.transientAt = { lat: 22.70, lng: 114.10 };
    s.transientName = "翠景花园";
    expect(s.label).toBe("翠景花园");
    expect(s.approx).toBe(false);
  });

  it("★★ transientName 为空时仍回落到 here —— 「用当前位置」那条路不该被顶成空", () => {
    // chooseHere 没有具体地名（name=""），解析不出聚落名时 transientName 为空：
    // 此时该显示定位到的地名，而不是空着
    const s = useLocationStore();
    s.here = hereAt(false);
    s.transientAt = { lat: 22.66, lng: 114.03 };
    s.transientName = "";
    expect(s.label).toBe("定位到的某楼盘");
  });

  it("★★★ 显式挑过的地址仍然压过一切（pickedByUser 不被 transient 顶掉）", () => {
    const s = useLocationStore();
    s.here = hereAt(false);
    s.active = { tag: "家", detail: "", region: "" } as never;
    s.pickedByUser = true;
    // switchTo 会清掉 transientAt，这里构造成互斥的现实态
    s.transientAt = null;
    expect(s.label).toBe("家");
  });
});
