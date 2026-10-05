import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";
import { loginMethods } from "@shared/ports/auth";

/**
 * 密码登录在 C 端真的有入口（C-AC-08 · AC8）。
 *
 * <h2>为什么这条守卫值得存在</h2>
 * <p>后端的密码登录早就通了：`GRANT_PASSWORD` 分支、`usr_identity` 的
 * PASSWORD 凭证、按手机号的防撞库限流、以及「查无此人也报 10456」那种
 * 防账号探测的细节 —— 整条链都在。而 `loginMethods()` 的密码方式要
 * `opts.withPassword`，登录页调的却是**不带参**的版本。
 *
 * <p>于是「能设密码」而「设了登不进来」。这类缺陷零报错、闸门全绿，
 * 只有真去点一遍才看得见 —— 正是本仓库记过的「配置屏可能没人读」。
 *
 * <h2>两条断言各管一半</h2>
 * <ul>
 *   <li>策略层：`withPassword: true` 真的把 PASSWORD 放进列表（而默认不放）</li>
 *   <li>接线层：登录页真的传了那个开关，且**不是用 `.find()` 只取一个**</li>
 * </ul>
 * 只有前一条的话，策略对而页面没接；只有后一条的话，页面接了而策略没给。
 */
describe("C 端密码登录的入口", () => {
  const page = readFileSync(
    resolve(__dirname, "../src/pages/login/index.vue"),
    "utf8",
  );

  it("策略层：withPassword 才给 PASSWORD，默认不给", () => {
    // 默认不给 —— 这一半同样要断言：恒给的实现会让上面那条恒绿
    expect(loginMethods().map((m) => m.id)).not.toContain("PASSWORD");
    expect(loginMethods({ withPassword: true }).map((m) => m.id)).toContain("PASSWORD");
  });

  it("接线层：登录页打开了那个开关", () => {
    expect(page).toMatch(/loginMethods\(\s*\{[^}]*withPassword:\s*true/);
  });

  it("接线层：要手机号的方式按 filter 取，不是 find —— find 会静默只渲染第一个", () => {
    /*
     * 这一条是上一条的配套。页面原来写的是
     *   const phoneMethod = computed(() => methods.find((m) => m.needsPhone));
     * 那时列表里只有一个要手机号的方式，所以看不出问题。
     * 一旦多出密码方式，`.find()` 会静默只渲染验证码那一个 ——
     * 开关打开了、方式也在列表里，而界面上一个字都没变。
     */
    expect(page).toMatch(/\.filter\(\(m\)\s*=>\s*m\.needsPhone\)/);
    expect(page).not.toMatch(/\.find\(\(m\)\s*=>\s*m\.needsPhone\)/);
  });

  it("两种方式的文案词条都在（策略只给 key，文案由各端自己提供）", () => {
    const keys = loginMethods({ withPassword: true })
      .filter((m) => m.needsPhone)
      .map((m) => m.labelKey);
    expect(keys.length).toBe(2);
    for (const lang of ["zh-CN", "en", "ar"]) {
      const src = readFileSync(
        resolve(__dirname, `../src/i18n/locale/${lang}.ts`),
        "utf8",
      );
      for (const key of keys) {
        // labelKey 形如 login.byPassword —— 词条文件里是嵌套的，只比最后一段
        const leaf = key.split(".").pop()!;
        expect(src, `${lang} 缺 ${key}`).toContain(`${leaf}:`);
      }
    }
  });
});
