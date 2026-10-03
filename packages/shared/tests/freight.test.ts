import { describe, expect, it } from "vitest";
import { estimateFreight, freightFee } from "../src/utils/freight";

// 与后端 FreightTemplateFlowTest#formula 同一组用例：两边任何一边改了公式，这里或那边会红
const FT0001 = {
  templateNo: "FT0001", name: "默认", firstWeightGram: 1000, firstFee: 800, addWeightGram: 500, addFee: 200,
  freeThreshold: 9900, rules: [],
};

describe("快递运费计价（与后端 FreightPort#fee 对拍）", () => {
  it("首重内收首重费；超出按续重单位向上取整", () => {
    expect(freightFee(800, FT0001)).toBe(800);
    expect(freightFee(1000, FT0001)).toBe(800);
    expect(freightFee(1001, FT0001)).toBe(1000);
    expect(freightFee(1600, FT0001)).toBe(1200);
    expect(freightFee(25_000, FT0001)).toBe(800 + 48 * 200);
  });

  it("没填重量按首重估，并标出来让端上提示补重量", () => {
    expect(estimateFreight(FT0001, null)).toEqual({ fee: 800, weighed: false });
    expect(estimateFreight(FT0001, 2500)).toEqual({ fee: 800 + 3 * 200, weighed: true });
  });
});
