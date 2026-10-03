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
  it("★★★ 入驻入口受**后端开关**控制 —— 被驳回时要能不发版就关掉", () => {
    /*
     * 这条判据以前是「小程序里恒不显示」（编译期 #ifdef）。2026-09-28 拍板要在小程序上
     * 开放商家注册 —— 那条审核风险（自营类目的包里出现入驻可能被判平台型经营）**并没有消失**，
     * 变的是谁来承担它。所以判据换成「可回滚」：显不显示由后端开关决定，
     * 真被驳回时运营在后台关一下就止血，不用重新发版、不用重新提审。
     *
     * **不许退回编译期判断** —— 那等于把这条风险变成一个只能靠发版解决的问题。
     */
    const me = readFileSync(join(SRC, "pages/me/index.vue"), "utf8");
    expect(me).toContain("merchantApplyVisible(config.features)");

    const gate = readFileSync(
      resolve(__dirname, "../../packages/shared/src/ports/storefront.ts"), "utf8");
    expect(gate, "判断退回成编译期了，开关关不掉它").not.toContain("#ifdef MP-WEIXIN");
    expect(gate).toContain('flags?.["merchant.apply.mp-visible"]');
  });

  it("★★★ 冷启动真的去拉那份开关 —— 不拉的话开关永远是默认值", () => {
    const app = readFileSync(join(SRC, "App.vue"), "utf8");
    expect(app).toContain("useConfigStore().load()");
  });

  it("★★ 提交入驻之后引导去装商家版 —— 经营动作都在 App 里", () => {
    const me = readFileSync(join(SRC, "pages/me/index.vue"), "utf8");
    expect(me).toContain("appDownloadVisible");
    /*
     * 文案与复制按钮在 2026-09-29 收进了 biz-app-download（报名表底部与提交完成页
     * 曾各有一份，加二维码要改两处）。这条断言原本 grep 页面源码里的
     * `merchant.getApp` —— 抽组件之后它在页面里不存在了，于是**这条用例红了**。
     * 红得有理由：它盯的是「有没有引导」，而引导确实搬了家。
     * 所以两头各钉一半：页面要真的挂上那颗组件，组件要真的带着那句文案。
     */
    expect(me).toContain("biz-app-download");
    const dl = readFileSync(join(SRC, "components/biz/biz-app-download.vue"), "utf8");
    expect(dl).toContain("merchant.getApp");
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
