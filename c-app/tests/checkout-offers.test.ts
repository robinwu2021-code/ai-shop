/**
 * 下单页：顾客可以换活动、不参加；没动过就照最省组合来（优惠券全链路梳理 批 2）。
 *
 * 守四件事：
 * ① 没动过 → 套用后端建议的组合（进来先看到最低价）；动过 → 不再替他改；
 * ② 选择在预览和下单两处都带上 —— 只带一处，确认页与实付就是两个数；
 * ③ 选的活动不成立（40035）→ 清掉选择、说一句，不装作没事；
 * ④ mock 里「不参加」真的不减 —— 否则本机替一个没接上的开关背书。
 */
import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { aftersaleMock } from "@/api/mocks/aftersale";
import { ACTIVITY_NONE } from "@shared/utils/constants";

const vue = readFileSync(resolve(__dirname, "../src/pages/order-confirm/index.vue"), "utf-8");
const code = vue.replace(/\/\*[\s\S]*?\*\//g, "").replace(/<!--[\s\S]*?-->/g, "").replace(/\/\/[^\n]*/g, "");

function body(name: string, len = 600): string {
  const at = code.indexOf(name);
  expect(at, `${name} 不见了`).toBeGreaterThan(-1);
  return code.slice(at, at + len);
}

describe("默认最省、动过不改", () => {
  it("★★★ 只有没动过才套用建议", () => {
    expect(code).toContain("if (!touched.value && applySuggestion(p.offers)) return;");
  });

  it("★★★ 选券、选活动都算「动过」", () => {
    expect(body("function chooseCoupon", 120)).toContain("touched.value = true");
    expect(body("function chooseActivity", 200)).toContain("touched.value = true");
  });

  it("换回最省组合要把「动过」清掉 —— 否则下一次预览又不跟建议了", () => {
    expect(body("function useSuggestion", 120)).toContain("touched.value = false");
  });

  it("活动选择变了要重新问价", () => {
    expect(code).toContain("JSON.stringify(activityChoices.value)]");
  });
});

describe("预览与下单都带上选择", () => {
  it("★★★ 两处都传 activityChoices", () => {
    expect(code.split("activityChoices: choicesPayload()").length - 1).toBe(2);
  });
});

describe("选的活动不成立", () => {
  it("★★ 预览收到 40035：清掉选择并提示", () => {
    const b = body("e.code === ACTIVITY_CHOICE_UNAVAILABLE", 300);
    expect(b).toContain("activityChoices.value = {}");
    expect(b).toContain("confirm.failActivity");
  });

  it("下单收到 40035 说人话", () => {
    expect(body("function submitFailText", 600)).toContain("ACTIVITY_CHOICE_UNAVAILABLE");
  });
});

describe("mock 与后端同一口径", () => {
  const preview = (extra: Record<string, unknown> = {}) => aftersaleMock.orderPreview!({
    items: [{ goodsNo: "G001", skuNo: "G001S1", qty: 2 }],
    fulfillment: "STORE_PICKUP",
    ...extra,
  } as Parameters<NonNullable<typeof aftersaleMock.orderPreview>>[0]);

  it("★★★ 默认参加、给出选项与建议", async () => {
    const r = await preview();
    expect(r.offers?.merchants[0]?.options[0]?.activityNo).toBe("MOCK-CUT-5");
    expect(r.discountLines?.some((d) => d.kind === "ACTIVITY")).toBe(true);
  });

  it("★★★ 选了不参加就真的不减", async () => {
    const on = await preview();
    const merchantNo = on.offers!.merchants[0]!.merchantNo;
    const off = await preview({ activityChoices: [{ merchantNo, activityNo: ACTIVITY_NONE }] });
    expect(off.discountLines?.some((d) => d.kind === "ACTIVITY")).toBe(false);
    expect(off.offers?.merchants[0]?.chosen).toBe(ACTIVITY_NONE);
    expect(off.amount.discountMinor).toBeLessThan(on.amount.discountMinor);
  });
});
