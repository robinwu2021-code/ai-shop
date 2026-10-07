/**
 * 提交历史列表的派生量（TDD-商品编辑页-录入落点与发布历史 AC11）。
 *
 * <p>这一份只守一条、但是最要紧的那条：**「最新」不是「线上在售」**。
 */
import { describe, expect, it } from "vitest";
import {
  draftVersionsOf, onlineRevisionOf, statusChipOf,
} from "@/pages/goods-revisions/revisions";
import type { GoodsRevision, GoodsRevisionStatus } from "@/api/contract";

function rev(revisionNo: number, status: GoodsRevisionStatus): GoodsRevision {
  return {
    revisionNo, status, entrySource: "MANUAL", changeSummary: null,
    savedBy: "张三", savedAt: "2026-10-06 14:20",
    publishedBy: null, publishedAt: null, rejectReason: null,
  };
}

/** 真实形状：倒序，最新那一行是未发布的草稿 */
const TYPICAL = [rev(4, "DRAFT"), rev(3, "ONLINE"), rev(2, "SUPERSEDED"), rev(1, "REJECTED")];

describe("最新 ≠ 线上在售", () => {
  it("★★★ 线上在售取 ONLINE 那一行，不是 rows[0]", () => {
    // rows[0] 是 v4 草稿 —— 买家看到的是 v3
    expect(onlineRevisionOf(TYPICAL)).toBe(3);
  });

  it("从没发布过回 null，不回 0 也不回最新号", () => {
    expect(onlineRevisionOf([rev(1, "DRAFT")])).toBeNull();
  });

  it("空列表回 null", () => {
    expect(onlineRevisionOf([])).toBeNull();
  });
});

describe("编辑页横幅的两个号", () => {
  it("★★★ 草稿与线上都有时才回两个号", () => {
    expect(draftVersionsOf(TYPICAL)).toEqual({ n: 4, m: 3 });
  });

  it("只有草稿（新商品从没发布过）回 null —— 说「线上在售 v?」是假话", () => {
    expect(draftVersionsOf([rev(1, "DRAFT")])).toBeNull();
  });

  it("只有线上（没有未发布改动）回 null —— 横幅那会儿根本不该出现", () => {
    expect(draftVersionsOf([rev(1, "ONLINE")])).toBeNull();
  });
});

describe("四种状态要分得开", () => {
  it.each([
    ["ONLINE", "sh-chip--success"],
    ["DRAFT", "sh-chip--warning"],
    ["REJECTED", "sh-chip--danger"],
    ["SUPERSEDED", ""],
  ] as [GoodsRevisionStatus, string][])("%s → %s", (s, cls) => {
    expect(statusChipOf(s)).toBe(cls);
  });

  it("四种状态的类互不相同 —— 撞上一个就有两种状态长一样", () => {
    const all = (["DRAFT", "ONLINE", "SUPERSEDED", "REJECTED"] as GoodsRevisionStatus[])
      .map(statusChipOf);
    expect(new Set(all).size).toBe(4);
  });
});
