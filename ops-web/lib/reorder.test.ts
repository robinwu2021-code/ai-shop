import { describe, expect, it } from "vitest";
import { canDrop, reorderWithin } from "./reorder";

describe("reorderWithin", () => {
  const L = ["a", "b", "c", "d"];

  it("★★★ 插入语义，不是交换 —— 交换会让中间的行跟着乱动", () => {
    // 把 d 拖到最前：a b c 整体后移一位，而不是 d 与 a 对调
    expect(reorderWithin(L, 3, 0)).toEqual(["d", "a", "b", "c"]);
    expect(reorderWithin(L, 0, 3)).toEqual(["b", "c", "d", "a"]);
  });

  it("★★ 相邻移动", () => {
    expect(reorderWithin(L, 1, 2)).toEqual(["a", "c", "b", "d"]);
    expect(reorderWithin(L, 2, 1)).toEqual(["a", "c", "b", "d"]);
  });

  it("★★★ 没变化时返回**原数组本身** —— 调用方据此跳过请求", () => {
    // 用 toBe 而不是 toEqual：这里要的就是引用相等
    expect(reorderWithin(L, 1, 1)).toBe(L);
    expect(reorderWithin(L, -1, 2)).toBe(L);
    expect(reorderWithin(L, 0, 99)).toBe(L);
    expect(reorderWithin([], 0, 0)).toEqual([]);
  });

  it("★★ 不改原数组", () => {
    const copy = [...L];
    reorderWithin(L, 0, 3);
    expect(L).toEqual(copy);
  });
});

describe("canDrop", () => {
  const a1 = { key: "a1", parentKey: "A" };
  const a2 = { key: "a2", parentKey: "A" };
  const b1 = { key: "b1", parentKey: "B" };

  it("★★★ 只允许同父级 —— 跨分区拖是改菜单结构，不是排序", () => {
    expect(canDrop(a1, a2)).toBe(true);
    expect(canDrop(a1, b1)).toBe(false);
  });

  it("★★ 落在自己身上不算 —— 不产生变化，却会白发一次请求", () => {
    expect(canDrop(a1, a1)).toBe(false);
  });

  it("没有拖动中的项时一律 false", () => {
    expect(canDrop(null, a1)).toBe(false);
  });

  it("★★ parentKey 用固定串而不是 undefined —— 两个 undefined 会相等，顶层项就能互相乱落", () => {
    const x = { key: "x", parentKey: "" };
    const y = { key: "y", parentKey: "" };
    expect(canDrop(x, y)).toBe(true);   // 同为顶层，允许
    expect(canDrop(x, a1)).toBe(false); // 顶层 vs A，不允许
  });
});
