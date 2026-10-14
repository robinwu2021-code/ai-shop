/**
 * 并包小程序里**商家页面的壳**：底部菜单与导航栏标题。
 *
 * <p>页面内部的字由 `with-biz.mjs` 改写成 local scope 的 `t(`，那一半早就对了。
 * 这里管的是**交给主包库件去做的那一半** —— 两件事，两种坏法，都不报错：
 *
 * <ul>
 *   <li><b>文案</b>：`title-key` / `labelKey` 是把**键**交出去，由 `sh-scaffold` /
 *       `sh-tabbar` 翻译，而它们在主包里、查的是 c-app 的词条。c-app 没有就露裸 key
 *       （`tab.orders`），有同名的就显示**另一个意思的中文**（`tab.home`：
 *       b 端「工作台」、c 端「首页」）。</li>
 *   <li><b>跳转</b>：分包页不在 `pages.json` 的 `tabBar.list` 里，对它们调
 *       `switchTab` **静默失败** —— 不跳、不报错，像是菜单坏了。</li>
 * </ul>
 *
 * <p>两件都是 2026-10-08 体验版 0.1.94 在真机上撞到的。
 */
import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

const ROOT = resolve(__dirname, "../..");
const read = (p: string) => readFileSync(resolve(ROOT, p), "utf8");

describe("并包后商家页面的壳", () => {
  it("★★★ 底部菜单用现成文案，不靠主包的 $t 去查 b-app 的键", () => {
    const script = read("c-app/scripts/with-biz.mjs");
    // 注入 tabs 时把 labelKey 解引用成文案；查不到要当场报错，不许静默放过
    expect(script).toContain("const label = bizText(m[5]);");
    expect(script, "查不到词条要报错，不能带着裸 key 往下走").toContain("底部菜单会露裸 key");

    const tabbar = read("packages/ui/src/components/sh-tabbar.vue");
    expect(tabbar, "现成文案要优先于 $t").toContain("tab.label || $t(tab.labelKey)");
  });

  it("★★★ 分包页走 reLaunch —— switchTab 对它们静默失败", () => {
    const tabbar = read("packages/ui/src/components/sh-tabbar.vue");
    expect(tabbar).toContain('tab.nav === "reLaunch"');
    expect(tabbar, "默认仍是 switchTab：c-app 自己的 tab 是真 tabBar 页")
      .toContain("uni.switchTab({ url: tab.route })");

    const script = read("c-app/scripts/with-biz.mjs");
    expect(script, "注入的商家 tab 要标成 reLaunch").toContain('nav: "reLaunch"');
  });

  it("★★★ 翻译函数注入成 __t —— 叫 t 会被模板里的局部变量遮蔽，整页白屏", () => {
    /*
     * 模板里的 `$t` 是全局注入的，与页面脚本的局部变量从不冲突；换成 `t` 之后
     * 它就是个普通标识符，于是**任何把局部变量命名为 t 的作用域都会遮蔽它**：
     *
     *   TABS.map((t) => ({ …, label: String($t(t.labelKey)) }))   // 原样：对
     *   TABS.map((t) => ({ …, label: String(t(t.labelKey)) }))    // 换成 t：把对象当函数调
     *
     * 运行时 `t is not a function`，Vue 在渲染里抛错 → 整页 slot 不渲染、纯白，
     * 而导航栏标题还在（那是 setNavigationBarTitle 设的），看着像页面没写完。
     * 0.1.95 的订单/商品/消息三页都是它——此前被「底部菜单点不动」掩盖，没人走到过。
     */
    const script = read("c-app/scripts/with-biz.mjs");
    expect(script, "注入的翻译函数要叫 __t").toContain('out.replace(/\\$t\\(/g, "__t(")');
    expect(script).toContain("const __t = t");
    // 闸门：把 t 当箭头参数的作用域里又调用 t( —— 判据不是「模板里有裸 t(」，
    // 那会误报（b-app 本来就有一批模板直接用解构出来的 t）
    expect(script).toContain("把 t 当参数的作用域里调用 t(");
  });

  it("★★★ title-key 在构建期换成文案，并有闸门钉住不许残留", () => {
    const script = read("c-app/scripts/with-biz.mjs");
    // 两种写法都要认：静态键 → title="…"；三元 → :title="… ? '…' : '…'"
    expect(script).toContain('out.replace(/title-key="([^"]*)"/g');
    expect(script).toContain('return `title="${text}"`;');
    expect(script).toContain('return `:title="${rewritten}"`;');
    // 构建期断言：改写漏了就当场失败，而不是等真机上露出来
    expect(script).toContain("这些文件还有 title-key");

    const scaffold = read("packages/ui/src/components/sh-scaffold.vue");
    expect(scaffold, "sh-scaffold 要收得下现成文案").toContain("props.title ? props.title :");
  });
});
