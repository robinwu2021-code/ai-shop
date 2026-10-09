/**
 * 账期块的放款摘要（TDD-账期推进与放款记录 AC8）。源码断言，理由见同目录 account-page.test.ts。
 *
 * 钉的是：三种放款状态各有一句、凭证号真的显示出来、三语齐。
 */
import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

const read = (p: string) => readFileSync(resolve(__dirname, p), "utf-8");
const income = read("../src/pages/income/index.vue");
const zh = read("../src/i18n/locale/zh-CN.ts");
const en = read("../src/i18n/locale/en.ts");
const ar = read("../src/i18n/locale/ar.ts");

describe("B 端账期块的放款摘要", () => {
  it("★★★ 文件都读到了", () => {
    for (const s of [income, zh, en, ar]) expect(s.length).toBeGreaterThan(500);
  });

  it("★★★ AC8 已打款：凭证号与日期显示出来 —— 商家拿凭证号对自己的银行到账记录", () => {
    expect(income).toContain('v-if="b.paymentRef"');
    expect(income).toContain('$t("income.batchPaid", { ref: b.paymentRef');
  });

  it("★★★ AC8 放了但还没打、打款被退回 —— 各自一句，不混成「已放款」", () => {
    // 批次状态 RELEASED 只说「放了」，没说钱到哪一步；商家问客服的正是后者
    expect(income).toContain("b.payoutStatus === 'PENDING' || b.payoutStatus === 'EXPORTED'");
    expect(income).toContain("income.batchPayoutPending");
    expect(income).toContain("b.payoutStatus === 'FAILED'");
    expect(income).toContain("income.batchPayoutFailed");
  });

  it("★★★ 三条词条三语齐，值非空", () => {
    for (const [lang, s] of Object.entries({ zh, en, ar })) {
      for (const k of ["batchPaid", "batchPayoutPending", "batchPayoutFailed"]) {
        expect(s, `${lang} 缺 ${k}`).toMatch(new RegExp(`\\b${k}:\\s*"[^"]+"`));
      }
    }
    // 已打款那句要带 {ref}：没有凭证号的「已打款」商家对不了账
    expect(zh).toMatch(/batchPaid:\s*"[^"]*\{ref\}/);
  });
});
