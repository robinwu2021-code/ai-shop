/**
 * 拖动中「谁要让位」（TDD-商品编辑页 §15 · AC24）。
 *
 * <p>拖动本身不动数组 —— 动了的话手指底下那一个会跟着重排，抖得没法看。
 * 于是让位只能靠位移画出来，而**画给谁、往哪边画**就是这一条纯函数。
 * 它错了的表现是「别的格子乱跳」或「根本没动静」，两种都只在手上看得出来。
 */
import { describe, expect, it } from "vitest";
import { slotShiftOf } from "@ai-shop/ui/drag-sort";

/** 一排六个，拖第 from 个到第 to 位时，每个各让几格 */
const row = (from: number, to: number) => [0, 1, 2, 3, 4, 5].map((i) => slotShiftOf(i, from, to));

describe("拖动让位", () => {
  it("★★★ 往后拖：中间那几个各往前让一格，被拖的那个不动", () => {
    // 1 → 4：2、3、4 各往前让一格
    expect(row(1, 4)).toEqual([0, 0, -1, -1, -1, 0]);
  });

  it("★★★ 往前拖：中间那几个各往后让一格", () => {
    // 4 → 1：1、2、3 各往后让一格
    expect(row(4, 1)).toEqual([0, 1, 1, 1, 0, 0]);
  });

  it("相邻两个互换只动一个", () => {
    expect(row(2, 3)).toEqual([0, 0, 0, -1, 0, 0]);
    expect(row(3, 2)).toEqual([0, 0, 1, 0, 0, 0]);
  });

  it("没在拖、或落点就是起点 → 一个都不让（否则抬手前整排会抖一下）", () => {
    expect(row(-1, -1)).toEqual([0, 0, 0, 0, 0, 0]);
    expect(row(2, 2)).toEqual([0, 0, 0, 0, 0, 0]);
    expect(row(2, -1)).toEqual([0, 0, 0, 0, 0, 0]);
  });
});
