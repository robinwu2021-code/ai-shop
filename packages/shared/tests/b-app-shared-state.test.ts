// 被多页读取的 store 状态，**必须在 App 壳上加载**，不能靠某一页顺手加载。
//
// 这条守卫来自两次同形的故障：
//
//   · `perms` 原先只有首页的 `loadStores` 会拉 —— 刷新在商品页时它是空的，
//     而空 perms 下 `can()` 全 false（fail-closed），老板的新建/编辑/上下架/改库存
//     四个按钮一起消失。**判权状态没加载 = 界面把自己锁死。**
//   · 修完 perms 三个月后，`stores` 又以完全一样的形状咬了一次：刷新在商品页时
//     门店切换条整条消失，而当前门店号还在本地存着照发 —— 页面显示的是另一家店的
//     库存，界面上却没有一处告诉你在看哪家店。
//
// 两次都不报错、都只在「不从首页进入」时出现（刷新、tabBar 直接切、深链），
// 而这三种恰恰是日常用法。所以这里把「谁负责加载」变成可执行的规则。
import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const ROOT = join(import.meta.dirname, "../../..");
const STORE = join(ROOT, "b-app/src/stores/merchant.ts");
const APP = join(ROOT, "b-app/src/App.vue");
const PAGES_DIR = join(ROOT, "b-app/src/pages");

const store = readFileSync(STORE, "utf8");
/**
 * App.vue，**去掉 mock 分支**。
 *
 * `if (USE_MOCK) { … useDemoSession() … }` 里也会把门店拉起来，
 * 于是「mock 下一切正常、真机上刷新即空」——正是这条守卫要防的那个故障本身。
 * 不剔掉它，守卫会被演示代码满足，红不起来。
 */
const app = readFileSync(APP, "utf8").replace(/if\s*\(USE_MOCK\)\s*\{[\s\S]*?\n {2}\}/g, "");

/** 每个页面的源码（含子目录 index.vue） */
function pageSources(): { name: string; src: string }[] {
  return readdirSync(PAGES_DIR)
    .map((d) => ({ name: d, path: join(PAGES_DIR, d, "index.vue") }))
    .filter((p) => {
      try {
        readFileSync(p.path);
        return true;
      } catch {
        return false;
      }
    })
    .map((p) => ({ name: p.name, src: readFileSync(p.path, "utf8") }));
}

/**
 * 允许「只在某一页加载」的状态，必须写清为什么。
 *
 * 判据是**这个状态错了会怎样**：能被一眼看出（列表是空的）就还好，
 * 静默地改变别的判断（判权、当前门店）就必须挂在壳上。
 */
const EXEMPT: Record<string, string> = {
  // 登录态本身：App.vue 里 restore() 同步恢复，不需要请求
  token: "restore() 在 App.vue 里同步读存储",
  scopeLoading: "内部去重字段，不是数据",
  storesLoading: "同上",
  // profile 由各页按需拉；它错的表现是「店名不显示」，一眼可见
  profile: "错了的表现是店名/状态不显示，一眼可见，且各页按需拉",
};

describe("b-app 共享状态", () => {
  const fields = [...store.matchAll(/^\s{4}(\w+):\s/gm)].map((m) => m[1]!);

  it("state 字段解析得到（正则失效时不要静默通过）", () => {
    expect(fields.length).toBeGreaterThan(5);
    expect(fields).toContain("perms");
    expect(fields).toContain("stores");
  });

  it("★★★ 被多页读取的状态，必须在 App.vue 上加载 —— 靠某一页加载 = 刷新即失效", () => {
    const pages = pageSources();
    const offenders: string[] = [];

    for (const f of fields) {
      if (EXEMPT[f]) continue;
      // 哪些页面读它
      const readers = pages.filter((p) => new RegExp(`merchant\\.${f}\\b`).test(p.src));
      if (readers.length < 2) continue;

      // 哪些 action 给它赋值
      const setters = [...store.matchAll(/async\s+(\w+)\s*\([^)]*\)\s*\{([\s\S]*?)\n {4}\},/g)]
        .filter(([, , body]) => new RegExp(`this\\.${f}\\s*=`).test(body!))
        .map(([, name]) => name!);
      if (!setters.length) continue; // 没有加载动作 = 纯本地状态

      // 壳上直接调了它，或壳上调的某个 ensure* 里调了它
      const ensureInApp = [...app.matchAll(/merchant\.(\w+)\(/g)].map((m) => m[1]!);
      const wired = setters.some(
        (s) =>
          ensureInApp.includes(s)
          || ensureInApp.some((e) =>
            new RegExp(`async\\s+${e}\\s*\\([^)]*\\)\\s*\\{[\\s\\S]*?this\\.${s}\\(`).test(store),
          ),
      );
      if (!wired) {
        offenders.push(
          `${f}（${readers.length} 个页面在读：${readers.map((r) => r.name).join("、")}；`
            + `加载动作 ${setters.join("/")} 没有挂在 App.vue 上）`,
        );
      }
    }

    expect(
      offenders,
      "这些状态被多个页面读，却只有某一页会加载它 ——\n"
        + "  刷新、tabBar 直接切、深链进来时它就是空的，而空值往往不报错，\n"
        + "  只是让界面少一块（perms 空 = 按钮全没；stores 空 = 门店条消失）。\n"
        + "  修：在 App.vue 里调一个幂等的 ensureXxx()，与 ensureScope/ensureStores 同一处。\n  "
        + offenders.join("\n  "),
    ).toEqual([]);
  });

  it("★★ 定义了 ensureXxx 就必须在 App.vue 里接上 —— 半截的守护比没有更糟", () => {
    const ensures = [...store.matchAll(/async\s+(ensure\w+)\s*\(/g)].map((m) => m[1]!);
    expect(ensures.length).toBeGreaterThan(0);
    const missing = ensures.filter((e) => !app.includes(`merchant.${e}(`));
    expect(
      missing,
      "这些 ensure 动作定义了却没在 App.vue 调用：" + missing.join("、"),
    ).toEqual([]);
  });
});
