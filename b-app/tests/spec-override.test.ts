import { describe, expect, it } from "vitest";
import type { SpecTemplate, StoreCategorySpecs } from "@shared/types";
import { buildSpecOverride } from "@/utils/spec-override";

/**
 * 本店规格覆盖的提交载荷。
 *
 * <p>这个函数的每一条规则都对应一次真实代价，而它们**没有一条是类型检查看得见的**：
 * 少发一个列表、把移除写成「不提交」、顺便重排了别人 —— 三样都是合法的 TypeScript，
 * 症状也都不指向这里（「我上次改的本店叫法怎么没了」）。
 *
 * <p>后端是**先清后写**：这次提交的就是这个类目下的全部状态。
 * 所以下面每一条测的都不是「函数返回了什么」，而是「漏掉它会丢什么」。
 */

/** 一个规格模板。`options` 的 code 是 wire 上的取值 */
function tpl(templateNo: string, name: string, codes: string[]): SpecTemplate {
  return {
    templateNo,
    name,
    options: codes.map((code) => ({ code, name: code })),
  } as SpecTemplate;
}

function group(dims: SpecTemplate[], props: SpecTemplate[] = []): StoreCategorySpecs {
  return { dims, props } as StoreCategorySpecs;
}

describe("本店规格覆盖 · 提交载荷", () => {
  it("★★★ 销售规格与商品参数必须一起提交 —— 只发一半，另一半的覆盖会被后端清掉", () => {
    const g = group([tpl("D1", "重量", ["500g"])], [tpl("P1", "产地", ["本地"])]);

    const out = buildSpecOverride({ g });

    // 后端先清后写：载荷里没有的 = 这家店在这个类目下没有覆盖。
    // 只发 dims 的话，P1 上「本店叫法」与档位取舍会被静默清空，而这不报错。
    expect(out.map((x) => x.dimNo).sort()).toEqual(["D1", "P1"]);
  });

  it("★★★ 移除是一条 enabled:false，不是「不提交」—— 不提交等于它自己回来了", () => {
    const g = group([tpl("D1", "重量", ["500g"]), tpl("D2", "香型", ["原味"])]);

    const out = buildSpecOverride({ g, removeDimNo: "D2" });

    const d2 = out.find((x) => x.dimNo === "D2");
    expect(d2, "被移除的那个必须仍在载荷里").toBeTruthy();
    expect(d2!.enabled, "它靠 enabled:false 表达『移除』").toBe(false);
    // 如果换成「从载荷里拿掉」，后端先清后写之后这个类目下就没有 D2 的覆盖记录，
    // 平台默认的那一条会重新生效 —— 商家会看到自己删掉的规格又出现了。
    expect(out.find((x) => x.dimNo === "D1")?.enabled).toBe(true);
  });

  it("★★ order 只给关心的那一段，没提到的按原顺序跟在后面 —— 不重排别人", () => {
    const g = group([
      tpl("D1", "一", ["a"]),
      tpl("D2", "二", ["b"]),
      tpl("D3", "三", ["c"]),
    ]);

    // 只关心把 D3 提到最前，D1/D2 的相对次序不该被动
    const out = buildSpecOverride({ g, order: ["D3"] });

    expect(out.map((x) => x.dimNo)).toEqual(["D3", "D1", "D2"]);
  });

  it("★★ 档位取舍：留下的 enabled:true、去掉的 enabled:false —— 去掉的也要在载荷里", () => {
    const g = group([tpl("D1", "重量", ["500g", "1kg", "2kg"])]);

    const out = buildSpecOverride({
      g,
      patch: { dimNo: "D1", values: ["500g", "1kg"], dropped: ["2kg"], label: "分量" },
    });

    const d1 = out.find((x) => x.dimNo === "D1")!;
    expect(d1.values).toEqual([
      { code: "500g", enabled: true },
      { code: "1kg", enabled: true },
      { code: "2kg", enabled: false },
    ]);
    expect(d1.label, "本店叫法跟着这次改动一起提交").toBe("分量");
  });

  it("★★ 没被 patch 的那些，原样带上平台的档位 —— 否则它们的档位会被清成空", () => {
    const g = group([tpl("D1", "重量", ["500g"]), tpl("D2", "香型", ["原味", "五香"])]);

    const out = buildSpecOverride({ g, patch: { dimNo: "D1", values: ["500g"], dropped: [] } });

    const d2 = out.find((x) => x.dimNo === "D2")!;
    expect(d2.values.map((v) => v.code)).toEqual(["原味", "五香"]);
  });

  it("★ 刚加进来、还不在 g 里的那一个也要进载荷", () => {
    const g = group([tpl("D1", "重量", ["500g"])]);

    const out = buildSpecOverride({ g, added: tpl("D9", "新维度", ["x"]) });

    expect(out.map((x) => x.dimNo)).toContain("D9");
  });

  it("★★ 空白的本店叫法落成 undefined，不落成空串 —— 空串会把平台原名覆盖没", () => {
    const g = group([tpl("D1", "重量", ["500g"])]);

    const out = buildSpecOverride({
      g,
      patch: { dimNo: "D1", values: ["500g"], dropped: [], label: "   " },
    });

    expect(out[0]!.label).toBeUndefined();
  });
});
