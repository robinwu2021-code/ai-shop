// `chipsFrom` 的回归测试。
//
// 起因：第一版只遍历直接子节点，而页面普遍把筛选器包在条件片段里
// （`{tab === "x" && (<>…</>)}`），于是 iam / groups / marketing 等多 tab 页
// **整页的筛选 chip 静默消失**，而 orders / merchants 这种直接挂的页面是好的 ——
// "一半页面好使"的失效靠肉眼几乎发现不了，所以用测试钉住。
import { describe, expect, it } from "vitest";
import { createElement, Fragment } from "react";
import { chipsFrom, type FilterChip } from "@/components/ui/filter-chip";

/** 造一个带 toChip 的假筛选控件。 */
function makeFilter(value: string) {
  const C = (() => null) as ((p: { value: string }) => null) & {
    toChip?: (p: { value: string }) => FilterChip | null;
  };
  C.toChip = (p) => (p.value ? { name: "状态", label: p.value, clear: () => {} } : null);
  return createElement(C, { value });
}

describe("chipsFrom", () => {
  it("直接子节点", () => {
    expect(chipsFrom([makeFilter("A"), makeFilter("B")]).map((c) => c.label)).toEqual(["A", "B"]);
  });

  it("**包在 Fragment 里也要找得到** —— 这正是当初静默失效的形态", () => {
    const frag = createElement(Fragment, null, makeFilter("A"), makeFilter("B"));
    expect(chipsFrom(frag).map((c) => c.label)).toEqual(["A", "B"]);
  });

  it("条件渲染出来的 false / null 不算数，也不该抛错", () => {
    expect(chipsFrom([false, null, undefined, makeFilter("A")]).map((c) => c.label)).toEqual(["A"]);
  });

  it("值为空的控件不出 chip（没筛就没得回显）", () => {
    expect(chipsFrom([makeFilter(""), makeFilter("A")]).map((c) => c.label)).toEqual(["A"]);
  });

  it("嵌两层容器也能找到（工具条里偶有 div 包一层）", () => {
    const inner = createElement(Fragment, null, makeFilter("A"));
    const outer = createElement("div", null, inner);
    expect(chipsFrom(outer).map((c) => c.label)).toEqual(["A"]);
  });
});
