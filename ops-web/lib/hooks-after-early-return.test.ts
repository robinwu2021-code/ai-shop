import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

/**
 * **Hook 不许写在提前 return 之后。**
 *
 * 2026-09-24 线上白屏：`SecondaryNav` 在两个 `return null` 之后才调 `useRef` 与 `useScrollHint`。
 * 看板页走 return、少调两个 Hook；从看板点进商户页，同一个组件实例这次调了 ——
 * React 报 #310「Rendered more hooks than during the previous render」。
 * 直接刷新打开商户页不崩（全新挂载），所以只在「从别的页点过去」时出现，本地很难碰到。
 *
 * 本来拦它的是 eslint 的 react-hooks/rules-of-hooks —— 而 ops-web 没有 eslint 配置，
 * `next lint` 也不在 pre-push 里，那条规则从来没跑过。这里用一个够用的静态扫描顶上：
 * 顶层组件 / 自定义 Hook 的函数体第一层里，出现 `if (…) return` 之后又出现 `useXxx(` 就算违规。
 *
 * 它是启发式的：只认函数体第一层（嵌套在 JSX 回调、子函数里的 return 不算），
 * 宁可漏报也不误报。真要彻底，应该把 eslint 的那条规则接进闸门。
 */

const ROOT = join(__dirname, "..");
const DIRS = ["app", "components"];

function walk(dir: string, out: string[]) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (name === "node_modules" || name.startsWith(".")) continue;
    if (statSync(p).isDirectory()) walk(p, out);
    else if (/\.tsx?$/.test(name) && !/\.test\.tsx?$/.test(name)) out.push(p);
  }
}

const START = /^(export )?(default )?function (use[A-Z]\w*|[A-Z]\w*)\s*[<(]|^(export )?const (use[A-Z]\w*|[A-Z]\w*)\s*=\s*(\([^)]*\)|\w+)\s*=>/;
const EARLY_RETURN = /^\s*if\s*\(.*\)\s*(return\b|\{\s*return\b)/;
const HOOK_CALL = /\b(React\.)?use[A-Z]\w*\s*\(/;

export function scan(files: string[], root: string): string[] {
  const hits: string[] = [];
  for (const f of files) {
    const lines = readFileSync(f, "utf8").split("\n");
    const starts = lines.map((l, i) => (START.test(l) ? i : -1)).filter((i) => i >= 0);
    starts.push(lines.length);
    for (let k = 0; k + 1 < starts.length; k++) {
      let depth = 0;
      let early = -1;
      for (let i = starts[k]; i < starts[k + 1]; i++) {
        const code = lines[i].replace(/\/\/.*$/, "");
        if (depth === 1 && early < 0 && EARLY_RETURN.test(code)) early = i;
        if (depth === 1 && early >= 0 && HOOK_CALL.test(code) && !/^\s*(\*|\/\*)/.test(lines[i])) {
          hits.push(`${f.slice(root.length + 1)}:${i + 1}（提前 return 在第 ${early + 1} 行）`);
          break;
        }
        depth += (code.match(/\{/g) ?? []).length - (code.match(/\}/g) ?? []).length;
        if (depth <= 0 && i > starts[k]) break;
      }
    }
  }
  return hits;
}

describe("Hook 调用顺序", () => {
  const files: string[] = [];
  for (const d of DIRS) walk(join(ROOT, d), files);

  it("扫描面：app/ 与 components/ 都扫到了 —— 少扫等于「没有违规」", () => {
    expect(files.length, "一个文件都没扫到，路径错了？").toBeGreaterThan(100);
    expect(files.some((f) => f.includes("/components/layout/"))).toBe(true);
    expect(files.some((f) => f.includes("/app/merchants/"))).toBe(true);
  });

  it("★★★ 组件与自定义 Hook 里，提前 return 之后不许再调 Hook —— 否则切页时 React #310 白屏", () => {
    expect(scan(files, ROOT), "这些地方在提前 return 之后调了 Hook。"
      + "把 Hook 挪到所有 return 之前，或者把 return 之后的那部分拆成子组件（见 secondary-nav.tsx）").toEqual([]);
  });

  it("对照：扫描器认得出那种写法 —— 否则上一条恒绿", () => {
    const src = [
      "export function Bad() {",
      "  const a = 1;",
      "  if (!a) return null;",
      "  const r = React.useRef(null);",
      "  return <div ref={r} />;",
      "}",
    ].join("\n");
    expect(scanSource(src)).toEqual([4]);
  });
});

/** 对照用：扫一段源码，返回违规行号 */
function scanSource(src: string): number[] {
  const lines = src.split("\n");
  const out: number[] = [];
  let depth = 0;
  let early = -1;
  for (let i = 0; i < lines.length; i++) {
    const code = lines[i];
    if (depth === 1 && early < 0 && EARLY_RETURN.test(code)) early = i;
    if (depth === 1 && early >= 0 && HOOK_CALL.test(code)) { out.push(i + 1); break; }
    depth += (code.match(/\{/g) ?? []).length - (code.match(/\}/g) ?? []).length;
  }
  return out;
}
