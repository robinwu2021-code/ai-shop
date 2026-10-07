/**
 * 文字识别的**落点**（TDD-商品编辑页-录入落点与发布历史 §0 · AC1/AC3/AC4/AC6）。
 *
 * <p>为什么值得单独一份：报障「文字里已经有限购地区，没有更新到商品页」的根因
 * **不在后端**。`parse-text` 早就把省码回来了，端上也亮了一枚 chip —— 只是没人
 * 把它写进商品。一个只断言「chip 出现了」的测试会恒绿，所以这里一律断言
 * **plan 里那个字段的值**。
 */
import { describe, expect, it } from "vitest";
import {
  entryAfterUndo, gramsOf, mergeUndo, planTextParse, raiseEntry, type TextParseTarget,
} from "@/pages/goods-edit/text-parse";
import type { GoodsTextParse } from "@/api/requests";

/** 后端回包。默认什么都没认出来，各条测试只放自己关心的那几项 */
function parsed(over: Partial<GoodsTextParse> = {}): GoodsTextParse {
  return {
    specs: [], params: [], pricesMinor: [], weights: [], carriers: [],
    fulfillment: [], excludeRegionText: null, restrictedRegions: [],
    confidence: 1,
    ...over,
  } as GoodsTextParse;
}

/** 页面现状。默认单规格、一行空的价与重量、没勾任何省 */
function target(over: Partial<TextParseTarget> = {}): TextParseTarget {
  return {
    multi: false,
    bulkPrice: "",
    rows: [{ priceMajor: {}, nominalGram: "" }],
    market: "CNY",
    restrictedRegions: [],
    groupNames: [],
    ...over,
  };
}

describe("AC1 限购地区要落进商品，不能只亮一个 chip", () => {
  it("★★★ 省码写进 restrictedRegions（这条红过一次：值一直没落）", () => {
    const p = planTextParse(parsed({ restrictedRegions: ["65", "54", "46"] }), target());
    expect(p.restrictedRegions).toEqual(["65", "54", "46"]);
    expect(p.changed).toContain("parseRegions");
  });

  it("取并集，不覆盖商家手选的 —— 识别只会让范围更保守", () => {
    const p = planTextParse(
      parsed({ restrictedRegions: ["65"] }),
      target({ restrictedRegions: ["11", "31"] }),
    );
    expect(p.restrictedRegions).toEqual(["11", "31", "65"]);
  });

  it("没有新省要加时整项不动（undefined，不是空数组）", () => {
    // 空数组会把商家勾的省清掉 —— 这是「只填空着的」最容易破的一处
    const p = planTextParse(parsed({ restrictedRegions: ["65"] }), target({ restrictedRegions: ["65"] }));
    expect(p.restrictedRegions).toBeUndefined();
    expect(p.changed).not.toContain("parseRegions");
  });
});

describe("AC6 多规格时价格不直落第一行", () => {
  it("★★★ 多规格 → 写进「统一价格」，由人点统一填入", () => {
    const p = planTextParse(parsed({ pricesMinor: [1000] }), target({ multi: true }));
    expect(p.bulkPrice).toBe("10.00");
    expect(p.rowPrice).toBeUndefined();
    expect(p.changed).toContain("parsePriceBulk");
  });

  it("单规格 → 直落第一行", () => {
    const p = planTextParse(parsed({ pricesMinor: [1000] }), target());
    expect(p.rowPrice).toBe("10.00");
    expect(p.bulkPrice).toBeUndefined();
    expect(p.changed).toContain("parsePrice");
  });

  it("价格没变就不报「已更新」—— 边输边识别会反复跑同一段文字", () => {
    const p = planTextParse(parsed({ pricesMinor: [1000] }), target({ rows: [{ priceMajor: { CNY: "10.00" }, nominalGram: "" }] }));
    expect(p.rowPrice).toBeUndefined();
    expect(p.changed).not.toContain("parsePrice");
  });
});

describe("AC4 重量落进标称重量", () => {
  it("★★★ 取最大的那个 —— 寄走的是 4.5 斤的箱子，不是 140g 的果子", () => {
    const p = planTextParse(parsed({ weights: ["140g+", "4.5斤"] }), target());
    expect(p.nominalGram).toBe("2250");
    expect(p.changed).toContain("parseWeight");
  });

  it("所有行都已填重量时不动", () => {
    const p = planTextParse(
      parsed({ weights: ["4.5斤"] }),
      target({ rows: [{ priceMajor: {}, nominalGram: "500" }] }),
    );
    expect(p.nominalGram).toBeUndefined();
  });

  it.each([
    ["4.5斤", 2250], ["2kg", 2000], ["1公斤", 1000], ["140g", 140], ["500克", 500],
  ])("%s → %i 克", (text, grams) => {
    expect(gramsOf([text])).toBe(grams);
  });

  it("认不出单位就丢掉，不猜", () => {
    expect(gramsOf(["大号", "若干"])).toBeNull();
  });
});

describe("AC3 规格维度只列出来，不自动加", () => {
  it("★★★ 回 specPicks 等人点 —— 自动加会把价格行从一行变成 N 行", () => {
    const p = planTextParse(
      parsed({ specs: [{ name: "重量", options: ["4.5斤", "9斤"] }] }),
      target(),
    );
    expect(p.specPicks).toEqual([{ name: "重量", options: ["4.5斤", "9斤"] }]);
    // 它不该出现在「已更新」里：什么都还没改
    expect(p.changed).not.toContain("parseSpecFound");
  });

  it("已经有同名维度的不再列", () => {
    const p = planTextParse(
      parsed({ specs: [{ name: "重量", options: ["4.5斤"] }] }),
      target({ groupNames: ["重量"] }),
    );
    expect(p.specPicks).toEqual([]);
  });

  it("没有档位的维度不列 —— 一个空维度加进去只会多一行要填的", () => {
    const p = planTextParse(parsed({ specs: [{ name: "重量", options: [] }] }), target());
    expect(p.specPicks).toEqual([]);
  });
});

describe("保留现状的两条", () => {
  it("快递加进履约", () => {
    const p = planTextParse(parsed({ fulfillment: ["EXPRESS"] }), target());
    expect(p.addExpress).toBe(true);
  });

  it("承运商不落任何字段 —— 商品上没有这一格，它只亮提示", () => {
    const p = planTextParse(parsed({ carriers: ["圆通"] }), target());
    expect(p.changed).toEqual([]);
  });

  it("confidence=0 时一个字段都不动", () => {
    const p = planTextParse(
      parsed({ confidence: 0, pricesMinor: [1000], restrictedRegions: ["65"] }),
      target(),
    );
    expect(p.changed).toEqual([]);
    expect(p.rowPrice).toBeUndefined();
    expect(p.restrictedRegions).toBeUndefined();
  });
});

describe("用户给的那段原文，端到端一次", () => {
  // 「规格：单果140g+ / 净重4.5斤装10元 / 圆通快递，新疆西藏海南不发货」
  it("★★★ 价 10 元 · 快递 · 2250 克 · 三个省，一次到位", () => {
    const p = planTextParse(
      parsed({
        pricesMinor: [1000],
        weights: ["140g+", "4.5斤"],
        carriers: ["圆通"],
        fulfillment: ["EXPRESS"],
        excludeRegionText: "新疆 西藏 海南",
        restrictedRegions: ["65", "54", "46"],
        specs: [{ name: "重量", options: ["4.5斤"] }],
      }),
      target(),
    );
    expect(p.rowPrice).toBe("10.00");
    expect(p.addExpress).toBe(true);
    expect(p.nominalGram).toBe("2250");
    expect(p.restrictedRegions).toEqual(["65", "54", "46"]);
    expect(p.specPicks).toHaveLength(1);
    expect(p.changed).toEqual(["parseExpress", "parsePrice", "parseWeight", "parseRegions"]);
  });
});

describe("AC5 撤销点：一串连续识别只有一个", () => {
  const snap = (price: string) => ({ bulkPrice: price });
  const item = (v: string) => [{ labelKey: "goods.parsePrice", value: v }];

  it("★★★ 第一次有改动 → 快照是识别之前那份", () => {
    const u = mergeUndo(null, snap("识别前"), item("10.00"));
    expect(u).toEqual({ bulkPrice: "识别前", items: item("10.00") });
  });

  it("★★★ 再识别一次 → 快照仍是第一次那份，items 累加", () => {
    const first = mergeUndo(null, snap("识别前"), item("10.00"))!;
    const second = mergeUndo(first, snap("10.00"), item("12.80"));
    // 撤销要退回「我贴这段话之前」，不是「上一次防抖之前」
    expect(second!.bulkPrice).toBe("识别前");
    expect(second!.items).toHaveLength(2);
  });

  it("这次什么都没改 → 原值返回，不新建撤销点", () => {
    expect(mergeUndo(null, snap("识别前"), [])).toBeNull();
    const first = mergeUndo(null, snap("识别前"), item("10.00"))!;
    expect(mergeUndo(first, snap("10.00"), [])).toBe(first);
  });
});

describe("复核面要列出具体填了什么", () => {
  it("每一项带字段名与填进去的值", () => {
    const p = planTextParse(
      parsed({ pricesMinor: [1000], weights: ["4.5斤"], restrictedRegions: ["65"] }),
      target(),
    );
    expect(p.items).toEqual([
      { labelKey: "goods.parsePrice", value: "10.00" },
      { labelKey: "goods.nominalGram", value: "2250" },
      { labelKey: "goods.restrictedLabel", value: "65" },
    ]);
  });

  it("items 与 changed 同长 —— 少一条就是复核面漏了一项", () => {
    const p = planTextParse(
      parsed({ pricesMinor: [1000], fulfillment: ["EXPRESS"], restrictedRegions: ["65"] }),
      target(),
    );
    expect(p.items).toHaveLength(p.changed.length);
  });
});

describe("录入方式只升不降", () => {
  it("★★★ 压缩包不会被它自己触发的快速录入盖掉", () => {
    // 导压缩包 → txt 落进识别框 → 边输边识别跑起来 → 封面图跑图片识别
    let src = raiseEntry("MANUAL", "ZIP");
    src = raiseEntry(src, "QUICK_TEXT");
    src = raiseEntry(src, "IMAGE");
    // 商家心里做的是「导了个压缩包」,不是「贴了段文字」
    expect(src).toBe("ZIP");
  });

  it("手填 → 快速录入 → 图片识别，逐级升上去", () => {
    expect(raiseEntry("MANUAL", "QUICK_TEXT")).toBe("QUICK_TEXT");
    expect(raiseEntry("QUICK_TEXT", "IMAGE")).toBe("IMAGE");
  });

  it("同一个值重复记不变", () => {
    expect(raiseEntry("IMAGE", "IMAGE")).toBe("IMAGE");
  });

  it("★★★ 文字全撤了退回手填 —— 否则历史里那一列会说谎", () => {
    expect(entryAfterUndo("QUICK_TEXT")).toBe("MANUAL");
  });

  it("撤销不动图片与压缩包 —— 图还在、参数还在,痕迹没撤掉", () => {
    expect(entryAfterUndo("IMAGE")).toBe("IMAGE");
    expect(entryAfterUndo("ZIP")).toBe("ZIP");
  });
});
