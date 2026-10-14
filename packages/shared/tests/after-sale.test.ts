// 售后规则矩阵（前端）—— 与后端 AfterSaleRulesTest 同一张用例表（§4 R4）。
import { describe, it, expect } from "vitest";
import {
  applyGate, needImage, reasonsFor, isQualityReason, isFresh,
  QUALITY_REASONS, ALL_REASONS, DENY_FRESH_QUALITY_ONLY,
  type FulfillStage,
} from "../src/strategies/after-sale";

const QUALITY = "DAMAGED" as const;
const NON_QUALITY = "NOT_WANTED" as const;

describe("售后规则矩阵：五档 × 生鲜 × 质量/非质量", () => {
  it("生鲜·已揽收·非质量 → 拒", () => {
    const g = applyGate("SHIPPED", true, NON_QUALITY);
    expect(g.allow).toBe(false);
    expect(g.deny).toBe(DENY_FRESH_QUALITY_ONLY);
  });

  it("生鲜·已签收·非质量 → 拒（已签收也只限质量）", () => {
    expect(applyGate("RECEIVED", true, NON_QUALITY).allow).toBe(false);
  });

  it("生鲜·已揽收·质量 → 放行、不灰色", () => {
    const g = applyGate("SHIPPED", true, QUALITY);
    expect(g.allow).toBe(true);
    expect(g.needMerchant).toBe(false);
  });

  it("生鲜·备货中·非质量 → 放行且需商家确认", () => {
    const g = applyGate("PREPARING", true, NON_QUALITY);
    expect(g.allow).toBe(true);
    expect(g.needMerchant).toBe(true);
  });

  it("生鲜·已出单未揽收·非质量 → 放行且需商家确认", () => {
    const g = applyGate("LABELED", true, NON_QUALITY);
    expect(g.allow).toBe(true);
    expect(g.needMerchant).toBe(true);
  });

  it("普通商品·已揽收·非质量 → 放行、不灰色", () => {
    const g = applyGate("SHIPPED", false, NON_QUALITY);
    expect(g.allow).toBe(true);
    expect(g.needMerchant).toBe(false);
  });

  it("生鲜·未备货·非质量 → 放行、不灰色", () => {
    const g = applyGate("NOT_PREPARED", true, NON_QUALITY);
    expect(g.allow).toBe(true);
    expect(g.needMerchant).toBe(false);
  });

  it("图片凭证只在已签收时要", () => {
    expect(needImage("RECEIVED")).toBe(true);
    (["SHIPPED", "LABELED", "PREPARING", "NOT_PREPARED"] as FulfillStage[]).forEach((s) =>
      expect(needImage(s)).toBe(false));
  });

  it("生鲜已签收只给质量三项，其余给全部", () => {
    expect(reasonsFor("RECEIVED", true)).toEqual(QUALITY_REASONS);
    expect(reasonsFor("LABELED", true)).toEqual(ALL_REASONS);
    expect(reasonsFor("RECEIVED", false)).toEqual(ALL_REASONS);
  });

  it("isFresh / isQualityReason", () => {
    expect(isFresh(["NORMAL", "FRESH"])).toBe(true);
    expect(isFresh(["NORMAL", null])).toBe(false);
    expect(isQualityReason("EXPIRED")).toBe(true);
    expect(isQualityReason("OTHER")).toBe(false);
  });
});
