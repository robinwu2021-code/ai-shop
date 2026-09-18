// **placeholder 只说这一格是什么，不举例子。**
//
// 用户 2026-09-18 直接点出来的：「门牌号（如 3 幢 2 单元 601）」这类太详细。
// 理由不是「难看」，是 placeholder 这个位置本来就承载不了例子 ——
// 它一开始打字就消失，例子最该在的那一刻恰好看不见；而它没消失的时候
// （整屏都是空格子）满屏的括号会把「这张表要填什么」淹掉。
// 真要给例子，给在格子下面的一行提示里，或者给在校验失败那一刻。
//
// **这一条闸门上原本一点痕迹都没有**：文案带不带例子，编译、类型、单测、
// 构建全都不在乎，只有人打开那一页才看得见 —— 所以它会被下一次顺手破坏。
//
// ⚠️ **扫描面必须是「真正绑在 placeholder 上的键」，不能按键名猜。**
// 第一版按 `xxxPh` / `placeholder` 这类键名去筛 —— 而用户点出来的那一条
// 键名叫 `houseNo`，压根不在扫描面里：把那句例子原样塞回去，守卫照样绿。
// 一条盖不住自己起因的守卫，比没有更糟。
import { describe, expect, it } from "vitest";
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";

const SRC = join(import.meta.dirname, "../src");
const LOCALES = ["zh-CN", "en", "ar"] as const;

/** 各语言里「举例子」的说法。**三种都要列** —— 只查中文的话，英文那份照样能塞例子 */
const EXAMPLE_MARKERS = [
  /如\s/, /（如/, /例如/, /比如/, /如「/,
  /\be\.g\.\s/i, /\bfor example\b/i,
  /مثال/,
];

function vueFiles(dir: string): string[] {
  const out: string[] = [];
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) out.push(...vueFiles(p));
    else if (name.endsWith(".vue")) out.push(p);
  }
  return out;
}

/** 模板里真正绑在 placeholder 上的那些词条键 */
function placeholderKeys(): Set<string> {
  const keys = new Set<string>();
  for (const f of vueFiles(SRC)) {
    const src = readFileSync(f, "utf8");
    for (const m of src.matchAll(/:?placeholder="[^"]*?\$t\(\s*['"]([\w.]+)['"]/g)) {
      keys.add(m[1]);
    }
  }
  return keys;
}

/** 从某份词条里取一个点号路径的值 */
function valueOf(src: string, key: string): string | null {
  const leaf = key.split(".").pop()!;
  const m = new RegExp(`^\\s*${leaf}: "([^"]*)"`, "m").exec(src);
  return m ? m[1] : null;
}

describe("placeholder 的写法", () => {
  it("★★★ placeholder 里不许举例子 —— 它一打字就消失，例子最该在的那一刻看不见", () => {
    const keys = [...placeholderKeys()].sort();
    /*
     * **对照量先验非零。** 正则写坏、或者模板换了绑定写法时，扫到 0 个键
     * 会让下面那条断言恒绿 —— 而那正是这条守卫最该说话的时候。
     */
    expect(keys.length, "一个 placeholder 键都没扫到 —— 这条守卫量的是空集")
      .toBeGreaterThan(25);

    const bad: string[] = [];
    for (const loc of LOCALES) {
      const src = readFileSync(join(SRC, `i18n/locale/${loc}.ts`), "utf8");
      for (const key of keys) {
        const v = valueOf(src, key);
        if (v && EXAMPLE_MARKERS.some((re) => re.test(v))) bad.push(`${loc}  ${key}: "${v}"`);
      }
    }
    expect(bad, `这些 placeholder 在举例子。例子放到格子下面那行提示里：\n${bad.join("\n")}`)
      .toEqual([]);
  });
});
