/**
 * 证照两页改名（TDD-B 端证照两页改名）。
 *
 * <p>改前「我的」上两行紧挨着、都叫「证照」：`资质证照` 与 `证照与账户`。
 * 而且后者**名不副实** —— 那一页从头到尾没有账户（真正的收款账户在 `payout-account`，
 * 标题「收款账户」）。店主为找账户点进去，里面一个账户都没有。
 *
 * <p><b>判据写成反向断言，不是正向。</b> 只断言新名字出现的话，漏改一处旧的照样全绿 ——
 * 而最容易漏的恰恰是**别处指路到这两页的句子**（「去『资质证照』里补齐」），
 * 那种错不报错、只让人找不到路。所以每条都钉「旧名字一次都不许再出现」。
 */
import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

const LANGS = ["zh-CN", "en", "ar"] as const;
const locales = Object.fromEntries(
  LANGS.map((l) => [l, readFileSync(resolve(__dirname, `../src/i18n/locale/${l}.ts`), "utf-8")]),
) as Record<(typeof LANGS)[number], string>;
const pagesJson = readFileSync(resolve(__dirname, "../src/pages.json"), "utf-8");

describe("B 端证照两页改名", () => {
  /*
   * 前置断言：文件真读到了。下面多数是 not.toContain —— 读成空串的话它们会全部通过，
   * 而那正是「改名漏了」最想被拦住的那一刻。
   */
  it("★★★ 三份 locale 与 pages.json 都读到了内容", () => {
    for (const l of LANGS) {
      expect(locales[l].length, `${l} 读空了`).toBeGreaterThan(1000);
    }
    expect(pagesJson).toContain("navigationBarTitleText");
  });

  it("★★★ AC1/AC2 旧名字在中文文案里一处都不剩", () => {
    expect(locales["zh-CN"]).not.toContain("证照与账户");
    expect(locales["zh-CN"]).not.toContain("资质证照");
  });

  it("★★★ AC1/AC2 新名字都在", () => {
    expect(locales["zh-CN"]).toContain("营业执照");
    expect(locales["zh-CN"]).toContain("经营资质");
  });

  it("★★★ AC3 指路话跟着改了 —— 它们指的是页面标题，不改就指向一个不存在的名字", () => {
    // 这两句分别在「建品填类目」与「上架被类目授权拦下」时出现，
    // 是店主唯一知道「该去哪儿补证」的线索
    expect(locales["zh-CN"]).toContain("在「经营资质」中查看");
    expect(locales["zh-CN"]).toContain("去「经营资质」里补齐");
  });

  it("★★★ AC1/AC2 pages.json 的导航标题与词条一致", () => {
    // 页面标题有两处真源：i18n（页内 sh-scaffold）与 pages.json（原生导航栏）。
    // 只改一处的话，小程序原生标题与页内标题会是两个名字，而 H5 上看不出来。
    expect(pagesJson).toContain('"navigationBarTitleText": "营业执照"');
    expect(pagesJson).toContain('"navigationBarTitleText": "经营资质"');
    expect(pagesJson).not.toContain('"navigationBarTitleText": "证照与账户"');
    expect(pagesJson).not.toContain('"navigationBarTitleText": "资质证照"');
  });

  it("★★★ AC4 三语都改了，没有「只改中文」的半套", () => {
    // 英文：旧的 title 用词不该再出现
    expect(locales["en"]).not.toContain("Licenses & Accounts");
    // 三份都必须有这两个键，且值不是空串
    for (const l of LANGS) {
      expect(locales[l], `${l} 缺 entities.title`).toMatch(/entities:[\s\S]{0,400}?title:\s*"[^"]+"/);
      expect(locales[l], `${l} 缺 qual.title`).toMatch(/\bqual:[\s\S]{0,400}?title:\s*"[^"]+"/);
    }
  });

  it("★★ 收款账户仍然在它自己那一页 —— 改名不许把两件事合并", () => {
    // 「证照与账户」的「账户」是假的；真账户页不动，否则这次改名就从
    // 「去掉一个误导」变成「制造一个新的」
    expect(locales["zh-CN"]).toContain("收款账户");
  });
});
