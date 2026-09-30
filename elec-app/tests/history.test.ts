// 历史记录与底部菜单的本机记忆。uni 的存储在 node 里没有，用一个 Map 顶上 ——
// 这里测的是「记什么、怎么去重、存坏了怎么办」，不是 uni 的存储本身。
import { beforeEach, describe, expect, it, vi } from "vitest";

const store = new Map<string, unknown>();
const redirects: string[] = [];
vi.stubGlobal("uni", {
  getStorageSync: (k: string) => (store.has(k) ? store.get(k) : ""),
  setStorageSync: (k: string, v: unknown) => void store.set(k, JSON.parse(JSON.stringify(v))),
  removeStorageSync: (k: string) => void store.delete(k),
  redirectTo: ({ url }: { url: string }) => void redirects.push(url),
});

const { clearViewed, recentSearches, rememberSearch, rememberViewed, viewedParts } = await import("../src/shared/recent");
const { ELEC_TABS, lastTab, switchTab } = await import("../src/shared/tabs");
const { ROUTES } = await import("../src/shared/routes");

beforeEach(() => {
  store.clear();
  redirects.length = 0;
});

describe("看过的料号", () => {
  it("最新的在前；同一个料号再看一次挪到最前，不重复", () => {
    rememberViewed({ partNo: "P1", mpn: "STM32F103C8T6", mfr: "ST" }, 1);
    rememberViewed({ partNo: "P2", mpn: "LM358DR", mfr: "TI" }, 2);
    rememberViewed({ partNo: "P1", mpn: "STM32F103C8T6", mfr: "ST" }, 3);
    expect(viewedParts().map((p) => [p.partNo, p.at])).toEqual([["P1", 3], ["P2", 2]]);
  });

  it("最多记 50 个，挤掉最老的", () => {
    for (let i = 0; i < 55; i++) rememberViewed({ partNo: `P${i}`, mpn: `M${i}`, mfr: "" }, i);
    const list = viewedParts();
    expect(list).toHaveLength(50);
    expect(list[0].partNo).toBe("P54");
    expect(list[49].partNo).toBe("P5");
  });

  it("没有料号号的不记（详情没拉到时别写一条空的）", () => {
    rememberViewed({ partNo: "", mpn: "X", mfr: "" });
    expect(viewedParts()).toEqual([]);
  });

  it("存坏了的条目被跳过，不让整页崩", () => {
    store.set("she_elec_viewed", [null, "x", { partNo: "P1" }, { partNo: "P2", mpn: "M2", mfr: "", at: 1 }]);
    expect(viewedParts().map((p) => p.partNo)).toEqual(["P2"]);
    store.set("she_elec_viewed", "不是数组");
    expect(viewedParts()).toEqual([]);
  });

  it("清空只清看过的，搜过的还在", () => {
    rememberSearch("f103");
    rememberViewed({ partNo: "P1", mpn: "M1", mfr: "" });
    clearViewed();
    expect(viewedParts()).toEqual([]);
    expect(recentSearches()).toEqual(["f103"]);
  });
});

describe("搜过的词", () => {
  it("不分大小写去重，保留最后一次的写法；最多 20 条", () => {
    rememberSearch("stm32");
    rememberSearch("STM32");
    expect(recentSearches()).toEqual(["STM32"]);
    for (let i = 0; i < 25; i++) rememberSearch(`k${i}`);
    expect(recentSearches()).toHaveLength(20);
  });
});

describe("底部菜单", () => {
  it("四格依次是 找料 · 询价 · 供货 · 我的，各指向自己的页", () => {
    expect(ELEC_TABS.map((t) => [t.key, t.label, t.route])).toEqual([
      ["find", "找料", ROUTES.home],
      ["rfq", "询价", ROUTES.rfqs],
      ["supply", "供货", ROUTES.supplier],
      ["me", "我的", ROUTES.me],
    ]);
  });

  it("切换是原地换页（redirectTo），并记下停在哪一格", () => {
    switchTab("supply");
    expect(redirects).toEqual([ROUTES.supplier]);
    expect(lastTab()).toBe("supply");
  });

  it("没记过、或记了一个不认识的值，都当作找料", () => {
    expect(lastTab()).toBe("find");
    store.set("she_elec_tab", "buyer");
    expect(lastTab()).toBe("find");
  });
});
