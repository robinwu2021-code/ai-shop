/**
 * 确认页：每一笔优惠只出现一次（用户 2026-09-21：「优惠券、优惠、活动优惠三个位置都有优惠」）。
 *
 * 此前同一个 ¥5 在屏幕上出现三回：上面卡片里的券行、金额卡的「优惠」合计、明细里的「券「xx」」。
 * 他得自己算是不是减了三次。
 */
import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

const tpl = readFileSync(resolve(__dirname, "../src/pages/order-confirm/index.vue"), "utf-8")
  .replace(/<!--[\s\S]*?-->/g, "");

describe("每笔优惠只出现一次", () => {
  it("★★★ 券行只有一处", () => {
    expect(tpl.split('$t("confirm.coupon")').length - 1).toBe(1);
  });

  it("★★★ 券行在金额卡里（与活动、合计同一处）", () => {
    const card = tpl.indexOf('$t("confirm.goods")');
    expect(tpl.indexOf('$t("confirm.coupon")')).toBeGreaterThan(card);
  });

  it("★★★ 明细里不再逐条列券 —— 那一笔由券行说", () => {
    expect(tpl).toContain('v-for="(d, i) in activityLines"');
    expect(tpl).not.toContain('v-for="(d, i) in discountLines"');
    expect(tpl).toMatch(/const activityLines = computed\(\(\) => discountLines\.value\.filter\(\(d\) => d\.kind === "ACTIVITY"\)\)/);
  });

  it("★★★ 合计的「优惠」只兜说不出名字的那部分", () => {
    expect(tpl).toContain('v-if="otherDiscountMinor > 0"');
    expect(tpl).not.toContain('v-if="amount.discountMinor" class="amt');
  });

  it("合计只在底栏说一次", () => {
    expect(tpl).toContain("confirm.savedTotal");
  });
});
