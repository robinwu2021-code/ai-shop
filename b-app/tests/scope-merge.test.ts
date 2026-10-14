import { describe, expect, it } from "vitest";
import type { ServiceArea } from "@shared/types";
import { mergeParsed } from "../src/shared/scope-merge";

/** 文字识别的范围项并进现有清单（TDD-经营范围文字录入 AC5/AC6）。口径与选择器 addArea 同一套 */
describe("文字识别结果并进经营范围清单", () => {
  const longhua: ServiceArea = { level: "DISTRICT", refCode: "440309", name: "广东省 / 深圳市 / 龙华区", mode: "INCLUDE" };
  const nanshan: ServiceArea = { level: "DISTRICT", refCode: "440305", name: "广东省 / 深圳市 / 南山区", mode: "INCLUDE" };
  const building: ServiceArea = { level: "COMMUNITY", refCode: "C3", name: "3 栋", mode: "EXCLUDE" };

  it("默认追加：原来的都在，新的接在后面", () => {
    const r = mergeParsed([longhua], [{ mode: "INCLUDE", level: "DISTRICT", refCode: "440305", name: nanshan.name }],
      { replace: false, unlimited: false });
    expect(r.areas.map((a) => a.refCode)).toEqual(["440309", "440305"]);
    expect(r.droppedIncludes).toBe(0);
  });

  it("同一个对象只留一条，后来的方向赢 —— 不出现又纳入又排除", () => {
    const r = mergeParsed([longhua], [{ mode: "EXCLUDE", level: "DISTRICT", refCode: "440309", name: longhua.name }],
      { replace: false, unlimited: false });
    expect(r.areas).toHaveLength(1);
    expect(r.areas[0]!.mode).toBe("EXCLUDE");
  });

  it("纳入上级收掉已有的下级区划（父子只留父）", () => {
    const r = mergeParsed([longhua, nanshan], [{ mode: "INCLUDE", level: "CITY", refCode: "4403", name: "广东省 / 深圳市" }],
      { replace: false, unlimited: false });
    expect(r.areas.map((a) => a.refCode)).toEqual(["4403"]);
  });

  it("「全国，新疆西藏除外」：清掉纳入项、保留原有排除，并说出清掉了几条", () => {
    const r = mergeParsed([longhua, nanshan, building], [
      { mode: "EXCLUDE", level: "PROVINCE", refCode: "65", name: "新疆维吾尔自治区" },
      { mode: "EXCLUDE", level: "PROVINCE", refCode: "54", name: "西藏自治区" },
    ], { replace: false, unlimited: true });
    expect(r.areas.map((a) => a.refCode)).toEqual(["C3", "65", "54"]);
    expect(r.areas.every((a) => a.mode === "EXCLUDE")).toBe(true);
    expect(r.droppedIncludes).toBe(2);
  });

  it("替换：只留这次识别出的，清掉几条照样说出来", () => {
    const r = mergeParsed([longhua, building], [{ mode: "INCLUDE", level: "DISTRICT", refCode: "440305", name: nanshan.name }],
      { replace: true, unlimited: false });
    expect(r.areas.map((a) => a.refCode)).toEqual(["440305"]);
    expect(r.droppedIncludes).toBe(1);
  });

  it("不改入参（端上拿它对比「有没有改动」）", () => {
    const before = [longhua];
    mergeParsed(before, [{ mode: "INCLUDE", level: "DISTRICT", refCode: "440305", name: nanshan.name }], { replace: false, unlimited: true });
    expect(before).toEqual([longhua]);
  });
});
