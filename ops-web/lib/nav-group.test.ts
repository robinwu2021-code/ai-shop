import { describe, expect, it } from "vitest";
import { groupedLeaves, type NavLeaf } from "./nav";

/**
 * 分组是**集合**，不是连续段。
 *
 * 这条守卫来自一次实测崩页：库里的菜单顺序把「费率」插进了「分账结算」中间，
 * 于是渲染出两个同名小标题、React 报「two children with the same key」，
 * 而财务页整页白。
 *
 * **而菜单顺序是运营可拖的**（/iam?tab=menu 那个拖动排序）——
 * 这个状态随时能被任何一次拖动造出来。所以它不是「修一次数据」能了结的事。
 */
describe("菜单分组", () => {
  const leaf = (label: string, group?: string): NavLeaf =>
    ({ href: "/x?tab=" + label, label, group }) as NavLeaf;

  it("★★★ 同名分组被别的组隔开时要归并 —— 否则渲染出两个同名标题，React key 撞车整页白", () => {
    const segs = groupedLeaves([
      leaf("a", "分账结算"), leaf("b", "分账结算"),
      leaf("c", "费率"),
      leaf("d", "分账结算"),   // ← 被隔开的同组叶子
    ]);
    expect(segs.map((s) => s.group)).toEqual(["分账结算", "费率"]);
    expect(segs[0].leaves.map((l) => l.label)).toEqual(["a", "b", "d"]);
  });

  it("★★ 组出现在它第一个成员的位置 —— 拖动仍改得动组之间的先后，只是组不被拆开", () => {
    const segs = groupedLeaves([leaf("x", "费率"), leaf("y", "分账结算"), leaf("z", "费率")]);
    expect(segs.map((s) => s.group)).toEqual(["费率", "分账结算"]);
  });

  it("★★ 无分组的叶子仍按相邻切段 —— 它们没有名字可归并，全合成一段会打乱相对位置", () => {
    const segs = groupedLeaves([leaf("p"), leaf("q", "A"), leaf("r")]);
    expect(segs.map((s) => s.group)).toEqual([undefined, "A", undefined]);
  });
});
