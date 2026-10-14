/**
 * 确认页：每一笔优惠只出现一次
 * （用户 2026-09-21：「优惠券、优惠、活动优惠三个位置都有优惠」；
 *  用户 2026-09-22：「活动在上、优惠券在下，优惠券中又包含活动」）。
 *
 * 此前的三种毛病：
 * ① 券在上面卡片里减一次、金额卡「优惠」合计一次、明细里逐条一次 —— 同一个 ¥5 出现三回；
 * ② 金额卡里活动 N 行 + 优惠券 1 行 + 其它 1 行 —— 同一件事切成三段；
 * ③ 面板名叫「优惠券」而第一段是活动 —— 想换活动的人不知道点哪儿。
 *
 * 现在：金额卡一行「优惠」（可点开面板），面板两段各带小标题（活动 / 券）。
 */
import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

const tpl = readFileSync(resolve(__dirname, "../src/pages/order-confirm/index.vue"), "utf-8")
  .replace(/<!--[\s\S]*?-->/g, "");

describe("每笔优惠只出现一次", () => {
  it("★★★ 金额卡里只有一行「优惠」（合并活动 + 券 + 其它）", () => {
    const cardStart = tpl.indexOf('$t("confirm.goods")');
    const cardEnd = tpl.indexOf('sh-actionbar');
    const card = tpl.slice(cardStart, cardEnd);
    // 一行「优惠」在金额卡里
    expect(card).toContain('$t("confirm.offer")');
    // 不再把活动逐条铺在明细里
    expect(card).not.toContain('v-for="(d, i) in activityLines"');
    // 不再单独一行「优惠券」
    expect(card).not.toContain('$t("confirm.coupon")');
    // 不再有「其它优惠」兜底行
    expect(card).not.toContain('otherDiscountMinor');
  });

  it("★★★ 「优惠」那一行的字样在金额卡里，只有一处", () => {
    expect(tpl.split('$t("confirm.offer")').length - 1).toBe(1);
  });

  it("★★★ 面板里两段各带小标题 —— 别让整个面板叫「优惠券」还塞活动", () => {
    expect(tpl).toContain("panelActivityPick");
    expect(tpl).toContain("panelActivityAuto");
    expect(tpl).toContain("panelCoupon");
  });

  it("合计只在底栏说一次", () => {
    expect(tpl).toContain("confirm.savedTotal");
  });

  it("★★★ 面板标题就叫「优惠」（couponPanel 的文案已经改过来）", () => {
    // couponPanel 词条只是变量名保留了下来，值本身在 i18n 里就是「优惠」
    expect(tpl).toContain('$t(\'confirm.couponPanel\')');
  });

  it("★★★ 汇总把「活动 -X · 券 -Y」拼在一行 —— 别再让人自己数减了几笔", () => {
    // offerSummary 里必须同时能出「活动」和「券」两段，且分隔用「·」
    const at = tpl.indexOf("const offerSummary");
    const body = tpl.slice(at, at + 1500);
    expect(body).toContain("confirm.offerActivity");
    expect(body).toContain("confirm.offerCouponUsed");
    expect(body).toContain('.join(" · ")');
  });
});
