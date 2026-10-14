/**
 * **库件自己写死的 i18n key，两端词条都必须有。**
 *
 * <p>`packages/ui` 的组件里有一批写死的 key（`$t("common.noPermTitle")`、
 * `$t("theme.title")`…）。它们由**库件自己**翻译，而库件跑在谁的进程里，
 * 就查谁的全局词条 —— 所以 c-app 和 b-app 两份都得有，缺一份就在那一端露裸 key。
 *
 * <p>2026-10-08 真机上撞到的：`common.noPermTitle` / `noPermHint` 只有 b-app 有。
 * 平时没事（C 端没有权限体系、从不传 `denied`），但**并包小程序里跑着 b-app 的页面**，
 * 它们会传 denied，而库件在主包里查的是 c-app 的词条 ——
 * 店员点进一个没权限的页面，看到的是两行程序标识符。
 *
 * <p>这条闸比「并包」这件事更一般：只要库件写死一个 key，它就是两端的共同契约。
 */
import { describe, expect, it } from "vitest";
import { readFileSync, readdirSync } from "node:fs";
import { join, resolve } from "node:path";

const ROOT = resolve(__dirname, "../..");
const UI = join(ROOT, "packages/ui/src/components");

/** 词条文件是 TS 模块，这里只要「这个 key 路径在不在」，按文本找就够且不引入加载开销 */
function hasKey(localeSrc: string, dotted: string): boolean {
  const [ns, key] = dotted.split(".");
  const m = new RegExp(`\\b${ns}:\\s*\\{`).exec(localeSrc);
  if (!m) return false;
  // 从命名空间起向后找到配平的 }，只在这一段里找 key
  let depth = 0;
  let end = m.index + m[0].length;
  for (let i = m.index + m[0].length - 1; i < localeSrc.length; i++) {
    if (localeSrc[i] === "{") depth++;
    else if (localeSrc[i] === "}") {
      depth--;
      if (depth === 0) { end = i; break; }
    }
  }
  return new RegExp(`\\b${key}\\s*:`).test(localeSrc.slice(m.index, end));
}

describe("库件写死的 i18n key", () => {
  it("★★★ 两端词条都有 —— 缺一份就在那一端露裸 key", () => {
    const keys = new Set<string>();
    for (const f of readdirSync(UI)) {
      if (!f.endsWith(".vue")) continue;
      for (const m of readFileSync(join(UI, f), "utf8").matchAll(/\$t\("([\w.]+)"\)/g)) {
        keys.add(m[1]);
      }
    }
    // 对照量要非零：正则失效时这条用例会「全绿且什么都没查」
    expect(keys.size, "一个 key 都没扫到——正则跟不上库件的写法了").toBeGreaterThan(5);

    const locales = {
      "c-app": readFileSync(join(ROOT, "c-app/src/i18n/locale/zh-CN.ts"), "utf8"),
      "b-app": readFileSync(join(ROOT, "b-app/src/i18n/locale/zh-CN.ts"), "utf8"),
    };
    const missing: string[] = [];
    for (const k of [...keys].sort()) {
      for (const [app, src] of Object.entries(locales)) {
        if (!hasKey(src, k)) missing.push(`${app} 缺 ${k}`);
      }
    }
    expect(missing).toEqual([]);
  });
});
