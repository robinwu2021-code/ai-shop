// 开店进度的判据（TDD-入驻分阶段与进件简化）。
//
// 这几条钉的是**「还要不要他做点什么」**，不是「库里有没有一行」。
// 两者在大多数情况下答案相同，而不同的那几种恰好是告警最容易变成噪音的地方：
// 提交完等着审的人，与从没提交过的人，看到的不该是同一条提示。
import { describe, expect, it } from "vitest";
import { payoutReady } from "../src/utils/onboarding";

describe("收款账户办完了没有", () => {
  it("一张都没有 —— 没办", () => {
    expect(payoutReady([])).toBe(false);
    expect(payoutReady(null)).toBe(false);
    expect(payoutReady(undefined)).toBe(false);
  });

  it("有生效的 —— 办完了", () => {
    expect(payoutReady([{ status: "ACTIVE" }])).toBe(true);
  });

  it("★ 待审的也算办完 —— 他该做的做完了，剩下的是运营审", () => {
    expect(payoutReady([{ status: "PENDING" }])).toBe(true);
  });

  it("★ 只有被驳回的不算 —— 他得回去重填，这条告警要亮着", () => {
    expect(payoutReady([{ status: "REJECTED" }])).toBe(false);
  });

  it("停用的不算（换卡时的旧记录）；但同时有生效的就算", () => {
    expect(payoutReady([{ status: "DISABLED" }])).toBe(false);
    expect(payoutReady([{ status: "DISABLED" }, { status: "ACTIVE" }])).toBe(true);
  });

  it("状态缺失或是没见过的值，一律当作没办 —— 宁可多提醒一次", () => {
    expect(payoutReady([{}])).toBe(false);
    expect(payoutReady([{ status: null }])).toBe(false);
    expect(payoutReady([{ status: "WHATEVER" }])).toBe(false);
  });
});
