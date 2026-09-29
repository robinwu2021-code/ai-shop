/**
 * 报名表的行业口径（TDD-C端入驻意向-行业口径 AC1/AC2）。
 *
 * <p>守的是「两个列表量的不是同一件事」：`industries` 回答「平台能不能接这类商家」，
 * `intentIndustries` 回答「商家能不能表达想做这一类」。一期只开了两档，
 * 拿前者渲染报名表的话，想开餐饮的人只能选「线下零售」——
 * 而意向表的价值恰恰在于收集平台还接不了的那些。
 */
import { describe, expect, it } from "vitest";
import type { MasterData } from "@shared/types";
import {
  INDUSTRY_OTHER,
  industryName,
  industryNotOpen,
  industryOptions,
} from "@/shared/apply-industry";

function master(over: Partial<MasterData> = {}): MasterData {
  return {
    industries: [
      { industry: "RETAIL", name: "线下零售", microAllowed: true },
      { industry: "LIFE_SERVICE", name: "居民生活服务", microAllowed: true },
    ],
    intentIndustries: [
      { industry: "RETAIL", name: "线下零售", open: true },
      { industry: "LIFE_SERVICE", name: "居民生活服务", open: true },
      { industry: "CATERING", name: "餐饮", open: false },
      { industry: INDUSTRY_OTHER, name: "其他", open: false },
    ],
    subjects: [],
    channels: [],
    serviceScopes: [],
    ...over,
  } as MasterData;
}

describe("报名表的行业口径", () => {
  it("★★★ 用意向口径，且比进件口径严格更宽", () => {
    const m = master();
    const opts = industryOptions(m);
    // 两个数都要非零 —— 「意向 ⊇ 进件」对两个空集同样成立，那是最容易通过的假绿
    expect(m.industries.length).toBeGreaterThan(0);
    expect(opts.length).toBeGreaterThan(m.industries.length);
    expect(opts.map((i) => i.industry)).toEqual(
      expect.arrayContaining(m.industries.map((i) => i.industry)),
    );
    expect(opts.map((i) => i.industry)).toContain("CATERING");
  });

  it("★★★ 老后端没有意向口径时退回进件口径并标成已开放 —— 不是退回空", () => {
    const opts = industryOptions(master({ intentIndustries: undefined as never }));
    expect(opts.map((i) => i.industry)).toEqual(["RETAIL", "LIFE_SERVICE"]);
    expect(opts.every((i) => i.open)).toBe(true);
    // 退回空的话这一格根本选不了，比少几个选项糟得多
    expect(opts.length).toBeGreaterThan(0);
  });

  it("★★★ 未开放的那一档要给提示，已开放的不给", () => {
    const opts = industryOptions(master());
    expect(industryNotOpen(opts, "CATERING")).toBe(true);
    // 反向也要钉：不这么做的话「恒 true」同样能让上一条通过
    expect(industryNotOpen(opts, "RETAIL")).toBe(false);
  });

  it("★★ 没选、或认不出这个码时不提示 —— 冲着人说「还没开放」是在猜", () => {
    const opts = industryOptions(master());
    expect(industryNotOpen(opts, "")).toBe(false);
    expect(industryNotOpen(opts, undefined)).toBe(false);
    expect(industryNotOpen(opts, "NOT_A_CODE")).toBe(false);
  });

  it("★★★ 取名要从意向口径取 —— 进件口径里没有未开放的那几档，会露出裸码", () => {
    const opts = industryOptions(master());
    expect(industryName(opts, "CATERING")).toBe("餐饮");
    // 认不出就把码本身给出去，不给空 —— 空格子看着像「这一项没填」
    expect(industryName(opts, "NOT_A_CODE")).toBe("NOT_A_CODE");
    expect(industryName(opts, undefined)).toBe("");
  });
});
