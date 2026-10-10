import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

const read = (p: string) => readFileSync(resolve(__dirname, "..", p), "utf8");

/**
 * 401 → 登录 → **回到原来那一页**。
 *
 * 此前这条链是断的：401 处理器用 `reLaunch` 送人去登录页（页面栈被清空），
 * 而登录页登完调 `navigateBack()` —— 空转，人停在登录页。
 *
 * 微信《小程序订单管理》对订单中心页的要求里点名了这个形态：
 * 「检测到无登录态则引导登录，**登录后停留在订单中心页**」，
 * 登完回不去是会被驳回的。
 */
describe("登录后回到来源页", () => {
  it("401 处理器把当前页带进 redirect 参数", () => {
    const src = read("src/App.vue");
    expect(src).toMatch(/redirect=\$\{encodeURIComponent\(back\)\}/);
    expect(src).toMatch(/function currentRoute\(\)/);
  });

  it("登录页读 redirect，并且登录成功后用它而不是 navigateBack", () => {
    const src = read("src/pages/login/index.vue");
    expect(src).toMatch(/redirect\.value\s*=\s*decodeURIComponent\(/);
    expect(src).toMatch(/setTimeout\(goBackAfterLogin,/);
    expect(src)
      .not.toMatch(/setTimeout\(\(\)\s*=>\s*uni\.navigateBack\(\),/);
  });

  it("★★★ 只放行站内路径 —— 这个值来自 URL 参数，不校验就能被构造成任意跳转", () => {
    const src = read("src/pages/login/index.vue");
    expect(src).toMatch(/startsWith\("\/pages\/"\)/);
  });

  it("★★★ 回跳用 reLaunch 不用 navigateTo —— 来源页可能是 tab 页，navigateTo 会静默失败", () => {
    const src = read("src/pages/login/index.vue");
    const fn = src.slice(src.indexOf("function goBackAfterLogin"));
    expect(fn.slice(0, 400)).toMatch(/uni\.reLaunch\(\{\s*url:\s*to\s*\}\)/);
  });
});
