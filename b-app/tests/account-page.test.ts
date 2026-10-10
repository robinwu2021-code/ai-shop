/**
 * 账号收进二级页（TDD-B 端账号二级页）。
 *
 * <p><b>为什么读源码而不是挂载页面</b>：b-app 没有 `@vue/test-utils`（只有 c-app 有），
 * 而给它装一个的代价不划算 —— 本仓库有一条明确的坑：在 b-app 跑 `npm i`
 * 会打断小程序构建（H5 与 pre-push 全绿，只有 mp 报 Invalid pattern）。
 * 同目录的 `order-discount-lines.test.ts`、`goods-row-acts.test.ts` 也是读源码断言。
 *
 * <p><b>代价说清楚</b>：源码断言只能证明「那几行字在文件里」，证明不了渲染结果。
 * 所以下面每一条钉的都是**搬动本身**（谁在哪一页、调的还是不是那个接口），
 * 不钉样式与交互 —— 那些源码断言本来就答不了。
 */
import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

const account = readFileSync(resolve(__dirname, "../src/pages/account/index.vue"), "utf-8");
const me = readFileSync(resolve(__dirname, "../src/pages/me/index.vue"), "utf-8");

describe("B 端账号二级页", () => {
  /*
   * **前置断言：确实读到了东西。**
   * 下面全是 `toContain` —— 文件要是读成空串，每一条 `not.toContain` 都会通过，
   * 而 `toContain` 那几条会红得莫名其妙。先把「读到了」钉死，
   * 后面的红才指得向真因。这条平时永远绿，只在路径写错那天值钱。
   */
  it("★★★ 两个页面都读到了内容", () => {
    expect(account.length).toBeGreaterThan(500);
    expect(me.length).toBeGreaterThan(500);
    expect(account).toContain("<template>");
    expect(me).toContain("<template>");
  });

  it("★★★ AC1 三行都在新页上", () => {
    expect(account).toContain('$t("me.username")');
    expect(account).toContain('$t("me.account")');
    expect(account).toContain('$t("me.password")');
  });

  it("★★★ AC2「我的」不再平铺这三行，只留一行入口", () => {
    // 三行的标签一个都不该再出现在「我的」上
    expect(me).not.toContain('$t("me.username")');
    expect(me).not.toContain('$t("me.account")');
    expect(me).not.toContain('$t("me.password")');
    // 换成一行入口
    expect(me).toContain('$t("me.accountEntry")');
    expect(me).toContain("go(ROUTES.account)");
  });

  it("★★★ AC2 入口摘要给的是身份，不是一个干巴巴的「账号」", () => {
    /*
     * 「登录账号」那一行当初存在的理由是「多店/多人时分不清此刻是哪个身份」。
     * 收进二级页之后入口若只写「账号 ›」，那个问题就被重新制造出来 ——
     * 所以摘要必须回落到能认人的东西：用户名 → 手机号。
     */
    expect(me).toContain("accountSummary");
    expect(me).toContain("merchant.profile?.displayName");
    expect(me).toContain("merchant.profile?.phone");
  });

  it("★★★ AC3 改名与改密码调的还是原来那几个接口", () => {
    expect(account).toContain("api.mSetDisplayName(");
    expect(account).toContain("api.mSetPassword(");
    expect(account).toContain("api.mHasPassword(");
    // 「我的」那边要搬干净，不留半套
    expect(me).not.toContain("mSetDisplayName");
    expect(me).not.toContain("mSetPassword");
    expect(me).not.toContain("mHasPassword");
  });

  it("★★ AC3 密码那段的两道保护没在搬动中丢掉", () => {
    // password: true —— 输密码时整屏都看得见是不行的
    expect(account).toContain("password: true");
    // 端上先挡一道 6 位，与后端 PWD_MIN_LEN 一致
    expect(account).toContain("me.passwordTooShort");
    expect(account).toContain("length < 6");
  });

  it("★★ AC4 未登录不出账号入口", () => {
    const i = me.indexOf('$t("me.accountEntry")');
    expect(i).toBeGreaterThan(-1);
    // 入口所在的那一块要挂在 isLogin 之下
    expect(me.slice(Math.max(0, i - 600), i)).toContain('v-if="merchant.isLogin"');
  });

  it("★★ 孤儿词条清掉了 —— accountSection 搬走之后没人用", () => {
    expect(me).not.toContain("accountSection");
    expect(account).not.toContain("accountSection");
  });
});
