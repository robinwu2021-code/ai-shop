import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { join } from "node:path";

/**
 * **常量 token 在小程序上要挂两遍，两遍必须一样。**
 *
 * 安卓真机微信上，`:root, .sh-root` 那条规则送不到节点上 —— 角标没有红底
 * （白字压白底＝隐形）、菜单顶部没有细线也没有投影、折扣标没有淡红底。
 * 同一份包在开发者工具模拟器上一切正常，所以这个故障只有真机看得见。
 * 2026-09-18 验出「同一批常量挂到 `page` 上当场全好」，于是 base.css 里
 * 有 `#ifdef MP-WEIXIN` 的第二份。
 *
 * **这条守卫看的是两份有没有对上。** 靠注释里那句「两处要一起改」是靠不住的：
 * 新加一个 token 只改上面那条，小程序上它就是死的，而三端里唯独小程序看得见
 * 这个差别 —— 谁也不会因为加了个变量就去翻真机。
 */
const css = readFileSync(
  join(import.meta.dirname, "../../ui/src/styles/base.css"),
  "utf8",
);

/** 取一条规则体里定义的 token 名（只算定义，`var(--x)` 的引用不算） */
function definedIn(body: string): Set<string> {
  return new Set(
    [...body.matchAll(/^\s*(--sh-[\w-]+)\s*:/gm)].map((m) => m[1]),
  );
}

/** `page { … }` 那一条（小程序专用的第二份） */
function pageRule(): string {
  const at = css.indexOf("\npage {");
  expect(at, "base.css 里找不到 `page {` 那条规则").toBeGreaterThan(-1);
  return css.slice(at, css.indexOf("\n}", at));
}

/** `:root,\n.sh-root { … }` 那几条（H5 / App 走的那一份），合并起来看 */
function rootRules(): string {
  const parts: string[] = [];
  const re = /:root,\n\.sh-root \{/g;
  for (let m = re.exec(css); m; m = re.exec(css)) {
    parts.push(css.slice(m.index, css.indexOf("\n}", m.index)));
  }
  expect(parts.length, "base.css 里找不到 `:root, .sh-root` 规则").toBeGreaterThan(0);
  return parts.join("\n");
}

describe("小程序的常量 token 第二份", () => {
  it("★★★ `page` 那一份要和 `:root, .sh-root` 定义的 token 完全一致", () => {
    const root = definedIn(rootRules());
    const page = definedIn(pageRule());
    // 扫描面自证：两边都不该是空集，否则下面两个差集恒空
    expect(root.size, "没扫到 `:root, .sh-root` 里的 token").toBeGreaterThan(20);
    expect(page.size, "没扫到 `page` 里的 token").toBeGreaterThan(20);

    const missing = [...root].filter((k) => !page.has(k)).sort();
    expect(
      missing,
      "这些 token 只写在 `:root, .sh-root` 上 —— 安卓真机微信上它们是死的，"
        + "要在 base.css 的 `#ifdef MP-WEIXIN` 那条 `page` 规则里补上同样的值",
    ).toEqual([]);

    const extra = [...page].filter((k) => !root.has(k)).sort();
    expect(
      extra,
      "这些 token 只写在小程序那一份上 —— H5 与 App 上它们不存在，"
        + "而那两端不会报错，只会取回退值或整条声明失效",
    ).toEqual([]);
  });

  it("★★★ 值也要一样 —— 只对齐名字的话，两端可以长期显示成两种颜色", () => {
    const val = (body: string) =>
      Object.fromEntries(
        [...body.matchAll(/^\s*(--sh-[\w-]+)\s*:\s*([^;]+);/gm)].map((m) => [
          m[1],
          m[2].replace(/\s+/g, " ").trim(),
        ]),
      );
    const root = val(rootRules());
    const page = val(pageRule());
    const differ = Object.keys(root)
      .filter((k) => page[k] !== undefined && page[k] !== root[k])
      .map((k) => `${k}: ${root[k]} ≠ ${page[k]}`);
    expect(differ, "两份的值对不上").toEqual([]);
  });
});
