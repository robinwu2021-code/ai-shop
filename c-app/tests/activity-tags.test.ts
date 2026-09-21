/**
 * 商品页的活动标签（优惠券全链路梳理 批 3）。
 *
 * 满减类活动此前只在下单页出现 —— 逛的时候不知道「满 50 减 8」，也就不会凑单。
 * 标签由端上按结构拼（三种语言都要用），这里守文案分支与挂载。
 */
import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import zh from "@/i18n/locale/zh-CN";

const vue = readFileSync(resolve(__dirname, "../src/pages/goods/index.vue"), "utf-8");
const promo = (zh as unknown as { promo: Record<string, string> }).promo;

describe("活动标签", () => {
  it("★★★ 商品页渲染 activityTags", () => {
    expect(vue).toContain('v-for="a in goods.activityTags ?? []"');
    expect(vue, "只有标签没有别的卖点时，那一行也要出来").toContain("!!g.activityTags?.length");
  });

  it("★★ 四种说法：按金额 / 按件数 / 新客 / 无门槛", () => {
    const at = vue.indexOf("function activityTagText");
    const body = vue.slice(at, at + 500);
    for (const k of ["promo.cutAmount", "promo.cutQty", "promo.newCut", "promo.cutAny"]) {
      expect(body).toContain(k);
    }
    expect(promo.cutAmount).toContain("{m}");
    expect(promo.cutQty).toContain("{q}");
  });
});
