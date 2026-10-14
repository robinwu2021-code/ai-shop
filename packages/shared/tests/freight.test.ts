import { describe, expect, it } from "vitest";
import { estimateFreight, freightFee, mergeFreight } from "../src/utils/freight";

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

// 与后端 FreightMergeTest 同一批用例、同一批数（ADR-031 §2.5 淘宝式合并）
describe("多模板合并（与后端 FreightPort#merge 对拍）", () => {
  const A = { ...FT0001, templateNo: "A" };
  const B = { templateNo: "B", name: "B", firstWeightGram: 1000, firstFee: 1000, addWeightGram: 1000, addFee: 300,
    freeThreshold: 0, rules: [] };

  it("只有一个模板：与单模板公式相同", () => {
    expect(mergeFreight([{ template: A, weightGram: 2300, goodsAmountMinor: 1000 }]).fee)
      .toBe(freightFee(2300, A)).toBe(1400);
  });

  it("两个模板：首费高的计首重，另一个全部重量只按续重", () => {
    const q = mergeFreight([{ template: A, weightGram: 1200, goodsAmountMinor: 1000 },
      { template: B, weightGram: 500, goodsAmountMinor: 1000 }]);
    expect(q.fee).toBe(1600);
    expect(q.templateNo).toBe("B");
  });

  it("包邮那份 0 元、不参与比较", () => {
    expect(mergeFreight([{ template: A, weightGram: 3000, goodsAmountMinor: 9900 },
      { template: B, weightGram: 1500, goodsAmountMinor: 500 }]).fee).toBe(1300);
  });

  it("全部包邮", () => {
    expect(mergeFreight([{ template: A, weightGram: 3000, goodsAmountMinor: 9900 }]))
      .toMatchObject({ fee: 0, free: true });
  });

  it("任一份不配送：整店拒", () => {
    expect(mergeFreight([{ template: A, weightGram: 500, goodsAmountMinor: 100 },
      { template: B, weightGram: 500, goodsAmountMinor: 100,
        hit: { region: "西藏", action: "REJECT", surcharge: 0 } }]).rejected).toBe(true);
  });

  it("加收取最高一笔、只加一次", () => {
    expect(mergeFreight([
      { template: A, weightGram: 500, goodsAmountMinor: 100, hit: { region: "新疆", action: "SURCHARGE", surcharge: 500 } },
      { template: B, weightGram: 500, goodsAmountMinor: 100, hit: { region: "新疆", action: "SURCHARGE", surcharge: 2000 } },
    ]).fee).toBe(3200);
  });
});
