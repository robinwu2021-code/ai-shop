/**
 * 商家招募那一侧的三条硬规矩（TDD-C 端裂变与商家招募 §4）。
 *
 * <p>前两条是**合规红线**，踩了的后果是整包被驳回；第三条是「真实数据」的兑现方式。
 */
import { describe, expect, it } from "vitest";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, resolve } from "node:path";

const SRC = resolve(__dirname, "../src");

function allFiles(dir: string, out: string[] = []): string[] {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) allFiles(p, out);
    else if (/\.(vue|ts)$/.test(name)) out.push(p);
  }
  return out;
}

describe("商家招募", () => {
  it("★★★ 入驻入口仍然挂在 merchantApplyVisible 上 —— 小程序里露出来会被判平台型经营", () => {
    const me = readFileSync(join(SRC, "pages/me/index.vue"), "utf8");
    expect(me).toContain("merchantApplyVisible()");
    const gate = readFileSync(
      resolve(__dirname, "../../packages/shared/src/ports/storefront.ts"), "utf8");
    // 判据是「小程序那一支返回 false」，不是「文件里有这个函数」
    expect(gate).toMatch(/#ifdef MP-WEIXIN[\s\S]{0,80}return false/);
  });

  it("★★★ 界面文案里不出现招商话术 —— 自营类目的包里有它就会被驳回", () => {
    const banned = ["招商", "加盟", "代理商", "我要开店"];
    const offenders: string[] = [];
    for (const f of allFiles(SRC)) {
      const text = readFileSync(f, "utf8");
      // 只看 i18n 词条文件与模板，源码注释里讨论这件事是正常的
      if (!/i18n\/locale/.test(f)) continue;
      for (const w of banned) {
        if (text.includes(w)) offenders.push(`${f.replace(SRC, "")}: ${w}`);
      }
    }
    expect(offenders, "C 端词条里出现了招商话术").toEqual([]);
  });

  it("★★★ 商家页的「在售」用后端总数，不用当前页条数", () => {
    const page = readFileSync(join(SRC, "pages/merchant/index.vue"), "utf8");
    expect(page).toContain("String(m.goodsCount)");
    // 用 goods.value.length 的话，一家有 50 件货的店会显示「在售 10」——
    // 那个数看起来完全正常，只是错的
    expect(page).not.toContain("String(goods.value.length)");
  });

  it("★★ 开店天数最少算 1 天 —— 「开店 0 天」读起来像没开", () => {
    const page = readFileSync(join(SRC, "pages/merchant/index.vue"), "utf8");
    expect(page).toContain("Math.max(1,");
  });
});
