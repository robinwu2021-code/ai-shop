// 页面引用的 `t("x.y")`，词条里必须真的有。
//
// **这条守卫的来历（2026-08-27）**：C 端的社区选择页把十个 key 写成了
// `community.noLocation` / `community.searchHint` …，而它们实际在 `common.` 下 ——
// 于是那一屏直接把 **`community.noLocation` 这串字符原样显示给用户**。
// 它不报错、不崩、不空白，只是那行字变成了程序员的内部名字。
//
// **为什么 `i18n-parity` 管不到**：那一条比的是「三种语言的 key 集合一致」——
// 而这十个 key 在三种语言里**都缺**，缺得整整齐齐，所以它一致、它绿。
// 这是「守卫在、但这一类恰好从它眼皮底下过」的第七个：
// 前面几个是 `tsc` 不看 `.vue`、`i18n-parity` 没挂闸、皮肤守卫放行带兜底的写法、
// `NOT_IMPLEMENTED` 一档装两种东西、生成物闸门跑第二遍必绿。
//
// ⚠️ **判据本身错过三次，过程记在这儿**：我最初报「B 端 133 处」，
// 因为解析器不认**嵌套命名空间**（`store: { pickup: { built } }`）；
// 改对之后是 13 处；再修一次才到 12 —— 还漏了**值写在下一行**的多行词条
//（`upgradeHow:` 换行后才是字符串）。
// **一个太天真的判据会让现状看起来比实际糟十倍**，而那种错很容易被当成成果。
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, relative } from "node:path";
import { describe, expect, it } from "vitest";

const ROOT = join(import.meta.dirname, "../../..");
const APPS = ["b-app", "c-app"];

/** 词条文件里的全部 key（含嵌套、含值写在下一行的） */
function keysOf(file: string): Set<string> {
  let src = readFileSync(file, "utf8")
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .replace(/^\s*\/\/.*$/gm, "");
  const out = new Set<string>();
  const stack: Array<[number, string]> = [];
  let depth = 0;
  for (const line of src.split("\n")) {
    for (const m of line.matchAll(/([A-Za-z_]\w*)\s*:\s*\{/g)) stack.push([depth, m[1]!]);
    for (const m of line.matchAll(/([A-Za-z_]\w*)\s*:\s*(?!\{)(\S|$)/g)) {
      const nxt = m[2] ?? "";
      // 值同行（引号开头），或本行以 `:` 结尾（值在下一行）
      if (nxt === "" || `"'\``.includes(nxt) || line.trimEnd().endsWith(":")) {
        out.add([...stack.map(([, n]) => n), m[1]!].join("."));
      }
    }
    depth += (line.match(/\{/g)?.length ?? 0) - (line.match(/\}/g)?.length ?? 0);
    while (stack.length && stack[stack.length - 1]![0] >= depth) stack.pop();
  }
  return out;
}

function walk(dir: string, out: string[] = []): string[] {
  for (const e of readdirSync(dir)) {
    const p = join(dir, e);
    if (statSync(p).isDirectory()) walk(p, out);
    else if (p.endsWith(".vue")) out.push(p);
  }
  return out;
}

describe("词条引用", () => {
  it("★★★ 页面里 t(\"x.y\") 引用的 key，词条里必须真的有 —— 否则用户看到的是 key 本身", () => {
    const bad: string[] = [];
    for (const app of APPS) {
      const known = keysOf(join(ROOT, app, "src/i18n/locale/zh-CN.ts"));
      expect(known.size, `${app} 没解析出词条，解析器与词条写法分叉了`).toBeGreaterThan(100);
      for (const dir of ["src/pages", "src/components"]) {
        for (const f of walk(join(ROOT, app, dir))) {
          const src = readFileSync(f, "utf8").replace(/\/\*[\s\S]*?\*\//g, "");
          for (const m of src.matchAll(/\$?t\(\s*["']([a-zA-Z]\w*\.[\w.]+)["']/g)) {
            if (!known.has(m[1]!)) {
              const line = src.slice(0, m.index).split("\n").length;
              bad.push(`${relative(ROOT, f)}:${line}  t("${m[1]}")`);
            }
          }
        }
      }
    }
    expect(
      bad,
      `这些 key 在词条里不存在，界面上会直接显示 key 本身：\n${bad.join("\n")}\n` +
        `多数情况是**命名空间写错**（词条在别的段里），不是真的缺文案。`,
    ).toEqual([]);
  });
});
