import { describe, expect, it } from "vitest";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join, relative } from "node:path";

/**
 * App 运行时不认的新语法。
 *
 * <p><b>为什么要一道闸门</b>：2026-10-09 真机上 `sh-trace` 的地图一开就整块不渲染，
 * logcat 一个字都没有、H5 完全正常。查到最后是 `Array.prototype.at` ——
 * App 的 JS 运行时里**没有这个方法**，抛的是 `TypeError: n.at is not a function`。
 *
 * <p>而它藏得特别深：那句 `.at(0)` 在一个 computed 里，**只有地图渲染时才求值**，
 * 于是表现成「带地图就坏」，把排查带去了原生组件、position:fixed、polyline 三个错方向，
 * 每个方向都打了一个包上真机。单是这一条就烧掉一个多小时。
 *
 * <p>`vue-tsc` 拦不住它（类型上完全合法）、H5 拦不住它（浏览器支持）、
 * 构建也不报 —— 只有真机会说话，而真机不是每次改动都跑。所以写成闸门。
 *
 * <p><b>加新条目的判据</b>：只收「类型合法、H5 可用、App 运行时没有」的那一类。
 * 能被 vue-tsc 或构建抓到的别放进来 —— 两道闸门管同一件事，坏的那天你不知道信哪个。
 */
const BANNED: { re: RegExp; what: string; instead: string }[] = [
  {
    re: /\.at\(\s*-?\d/g,
    what: "Array.prototype.at",
    instead: "用下标：`xs[0]` / `xs[xs.length - 1]`",
  },
  {
    re: /\.findLast(Index)?\(/g,
    what: "Array.prototype.findLast / findLastIndex",
    instead: "倒着遍历，或 `[...xs].reverse().find(...)`",
  },
  {
    re: /Object\.groupBy\(|Map\.groupBy\(/g,
    what: "Object.groupBy / Map.groupBy",
    instead: "用 reduce 自己分组",
  },
  {
    re: /\.toSorted\(|\.toReversed\(|\.toSpliced\(|\.with\(/g,
    what: "Array 的不可变方法（toSorted / toReversed / toSpliced / with）",
    instead: "先 `[...xs]` 再用原来的 sort / reverse / splice",
  },
];

/** 端上代码。`packages/shared` 与 `packages/ui` 会被打进三端，同样受限 */
const ROOTS = ["b-app/src", "c-app/src", "packages/ui/src", "packages/shared/src"];
const ROOT = join(import.meta.dirname, "../../..");

function walk(dir: string, out: string[] = []): string[] {
  for (const e of readdirSync(dir)) {
    const p = join(dir, e);
    if (statSync(p).isDirectory()) walk(p, out);
    else if (/\.(ts|vue)$/.test(p) && !/\.test\.ts$/.test(p)) out.push(p);
  }
  return out;
}

describe("App 运行时不认的新语法", () => {
  it("★★★ 端上代码不许用这些 —— 类型合法、H5 正常、App 上整块不渲染且无日志", () => {
    const bad: string[] = [];
    for (const r of ROOTS) {
      for (const f of walk(join(ROOT, r))) {
        // **先剥注释**：第一版把自己写的「不要用 .at(-1)」那行注释也算成了违规 ——
        // 扫注释是这类正则守卫的经典假阳性，而假报警会让人学会忽略它
        // 注释用等量换行替掉，**不要直接删** —— 删了行号就错位，而报错指错行比不报还费时间
        const blank = (m: string) => m.replace(/[^\n]/g, " ");
        const src = readFileSync(f, "utf8")
          .replace(/\/\*[\s\S]*?\*\//g, blank)
          .replace(/<!--[\s\S]*?-->/g, blank)
          .replace(/(^|[^:])\/\/[^\n]*/g, (m, p1) => p1 + blank(m.slice(p1.length)));
        for (const { re, what, instead } of BANNED) {
          for (const m of [...src.matchAll(re)]) {
            const line = src.slice(0, m.index).split("\n").length;
            bad.push(`${relative(ROOT, f)}:${line}  ${what} —— ${instead}`);
          }
        }
      }
    }
    expect(bad, `这些 API 在 App 的 JS 运行时里没有：\n${bad.join("\n")}`).toEqual([]);
  });
});
