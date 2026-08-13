// ops-web 契约里**必填的数组字段**，后端必须真的下发。
//
// ─────────────────────────────────────────────────────────────────────────────
// 这条守卫是一次「类型全绿、真接口崩页」换来的
// ─────────────────────────────────────────────────────────────────────────────
// `ops-web/lib/types/merchant.ts` 把 `qualifications` 声明成必填 `string[]`，
// `authorize-tab.tsx` 直接 `m.qualifications.length` —— 而后端 `MerchantProfileVO`
// 里**根本没有这个字段**。真接口下 `undefined.length` 当场抛错，整个商家授权页白屏。
//
// 它能活到今天，是因为 ops-web 默认跑 mock，而 mock 里有这个字段。
// 三层检查全部放行：
//   · TypeScript —— 只保证 ops-web 自己前后一致，对后端发什么一无所知
//   · contract.test.ts —— 只比 mock 与 http 的**方法集合**，不看返回体形状
//   · type-alignment.test.ts —— 只比 shared 与 ops-web 的**枚举取值**，不比字段
//
// 已有的 biz-contract-fields 做的正是字段级比对，但它只覆盖 b-app 与 c-app
// （那两端有 endpoints.ts + contract.ts 的固定结构）。**运营端是盲区**。
//
// 为什么只查「必填数组」而不是所有字段：
//   · 少一个 `string` 字段 → 页面显示空白，一眼看得见，改起来也快
//   · 少一个必填 `T[]` 字段 → `.length` / `.map` / `.includes` **直接抛异常**，
//     整块甚至整页不渲染，而错误信息指向渲染代码，不指向契约
// 前者是显示问题，后者是崩溃。先守住会崩的那一类。
import { readFileSync, readdirSync, statSync, existsSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const ROOT = join(import.meta.dirname, "../../..");
const read = (p: string) => readFileSync(join(ROOT, p), "utf8");

function filesIn(dir: string, ext: string): string[] {
  const abs = join(ROOT, dir);
  if (!existsSync(abs)) return [];
  return readdirSync(abs).filter((f) => f.endsWith(ext)).map((f) => `${dir}/${f}`);
}

/** 方法名 → 后端路径。来自 https/*.ts 里 `name: (q) => client.get("/ops/x", q)` */
function methodPaths(): Record<string, string> {
  const out: Record<string, string> = {};
  for (const f of filesIn("ops-web/lib/api/https", ".ts")) {
    for (const m of read(f).matchAll(
      /(\w+):\s*\([^)]*\)\s*=>\s*client\.(?:get|post|put)\(\s*`?["'`]([^"'`$]+)/g,
    )) {
      // 只收静态路径；带模板变量的（`/ops/x/${no}`）在 $ 处被截断，跳过
      if (!m[2]!.includes("$")) out[m[1]!] = m[2]!;
    }
  }
  return out;
}

/** 方法名 → 契约声明的返回类型（剥掉数组与分页壳） */
function declaredTypes(): Record<string, string> {
  const out: Record<string, string> = {};
  for (const f of filesIn("ops-web/lib/api/contracts", ".ts")) {
    for (const m of read(f).matchAll(/^\s{2}(\w+)\s*\([^)]*\)\s*:\s*Promise<([^;]+)>;/gm)) {
      const t = m[2]!.trim()
        // 三种分页壳都要剥。**漏一种就整类端点配不上**，而配不上时断言恒真 ——
        // 这条守卫第一版正是因为没剥 `Page<>`，撤掉修复也不报错
        .replace(/^Page(?:d|Data|Result)?<(.*)>$/, "$1")
        .replace(/\[\]$/, "")
        .trim();
      if (/^[A-Z]\w+$/.test(t)) out[m[1]!] = t;
    }
  }
  return out;
}

/** ops-web 某个 interface 的**必填数组**字段名 */
function requiredArrayFields(type: string): string[] {
  for (const f of filesIn("ops-web/lib/types", ".ts")) {
    const src = read(f);
    // ⚠️ 不能写死 `interface X {` —— 带 `extends` 的声明会被整条漏掉。
    // 这条守卫第一版就栽在这里：`Merchant extends Archivable` 匹配不上，
    // 于是它一个字段都没比过，却报绿。
    const decl = new RegExp(`export interface ${type}(?:\\s+extends\\s+[\\w<>, ]+)?\\s*\\{`);
    const hit = decl.exec(src);
    if (!hit) continue;
    const i = hit.index;
    const body = src.slice(i, src.indexOf("\n}", i));
    // `  name: Foo[];` 必填数组；`  name?: Foo[];` 可选，不查
    return [...body.matchAll(/^\s{2}(\w+):\s*[\w<>| ]+\[\]\s*;/gm)].map((m) => m[1]!);
  }
  return [];
}

function javaFiles(dir = "backend", out: string[] = []): string[] {
  for (const e of readdirSync(join(ROOT, dir))) {
    const p = `${dir}/${e}`;
    if (e === "target" || e === "node_modules") continue;
    if (statSync(join(ROOT, p)).isDirectory()) javaFiles(p, out);
    else if (e.endsWith(".java") && !p.includes("/test/")) out.push(p);
  }
  return out;
}
const JAVA = javaFiles();

/**
 * 把后端返回类型压成 record 简名。
 *
 * **必须先去包名再剥壳**：控制器里写的是全限定名
 * `ai.neargo.shop.common.PageData<MerchantGovernService.MerchantProfileVO>` ——
 * 先剥壳的话 `^PageData<` 匹配不上，整个端点被当成「解析不到」而静默跳过，
 * 而跳过时断言恒真。这条守卫第一版就是这样报绿的。
 */
function simpleName(raw: string): string {
  let t = raw.trim();
  // ① 逐层去包名/外部类前缀：a.b.C<d.e.F> → C<F>
  t = t.replace(/(?:[A-Za-z_]\w*\.)+([A-Z]\w*)/g, "$1");
  // ② 剥掉集合与分页壳，可能嵌套（PageData<List<X>>）
  let prev = "";
  while (prev !== t) {
    prev = t;
    t = t.replace(/^(?:List|Set|Collection|Page|Paged|PageData|PageResult|ResponseEntity)<(.*)>$/, "$1").trim();
  }
  return t.replace(/\[\]$/, "").trim();
}

/**
 * 路径 → { 返回类型简名, 声明它的控制器文件 }。
 *
 * **必须记住来源文件**：`MerchantProfileVO` 在 `MerchantGovernService`（运营侧）
 * 与 `BizMerchantController`（商家侧）各有一个，字段完全不同。
 * 取第一个匹配的话会拿商家侧的 VO 去比运营侧的契约，
 * 报出 `communityNos` / `categoryCodes` 这种并不存在的缺失 ——
 * **假消息比没消息更糟**，它把真正缺的那条盖在噪音里。
 */
function backendReturns(): Record<string, { ret: string; file: string }> {
  const out: Record<string, { ret: string; file: string }> = {};
  for (const f of JAVA) {
    for (const m of read(f).matchAll(
      /@(?:Get|Post|Put)Mapping\((?:value\s*=\s*)?"([^"]+)"\)[\s\S]{0,400}?public\s+([\w.<>?, ]+?)\s+\w+\s*\(/g,
    )) {
      out[m[1]!] = { ret: simpleName(m[2]!), file: f };
    }
  }
  return out;
}

/** 两条路径共同前缀的段数 —— 同名 record 里挑「离控制器最近」的那个 */
function nearness(a: string, b: string): number {
  const x = a.split("/"), y = b.split("/");
  let n = 0;
  while (n < x.length && n < y.length && x[n] === y[n]) n++;
  return n;
}

/**
 * record 的组件名。找不到该 record 返回 null —— 与「找到但无字段」要分开。
 *
 * 同名时按控制器的 import 挑（那是编译器用的那条线索），
 * 找不到 import（同包、嵌套类）才退回按路径就近。
 */
function javaComponents(name: string, from: string): string[] | null {
  const candidates = JAVA.filter((x) => read(x).includes(`record ${name}(`));
  if (!candidates.length) return null;
  const imported = read(from).match(new RegExp(`^import\\s+([\\w.]+)\\.${name};`, "m"))?.[1];
  const byImport = imported && candidates.find((c) => c.includes(imported.replace(/\./g, "/")));
  // 嵌套在接口里的 record（MerchantGovernService.MerchantProfileVO）：
  // 控制器 import 的是外层接口，所以再按「外层类名」找一次
  const outer = read(from).match(new RegExp(`^import\\s+([\\w.]+);`, "gm")) ?? [];
  const byOuter = candidates.find((c) =>
    outer.some((line) => {
      const fq = line.replace(/^import\s+|;$/g, "");
      return c.includes(fq.replace(/\./g, "/") + ".java");
    }));
  const f = byImport ?? byOuter ?? candidates.reduce((best, c) =>
    nearness(c, from) > nearness(best, from) ? c : best);
  const src = read(f);
  const i = src.indexOf(`record ${name}(`);
  let depth = 0;
  let j = src.indexOf("(", i);
  const start = j;
  for (; j < src.length; j++) {
    if (src[j] === "(") depth++;
    else if (src[j] === ")" && --depth === 0) break;
  }
  const body = src.slice(start + 1, j).replace(/\/\*[\s\S]*?\*\//g, "");
  return [...body.matchAll(/[\w.<>?\[\], ]+?\s(\w+)\s*(?:,|$)/g)].map((m) => m[1]!);
}

/**
 * 已知不一致 —— **必须写清后果**，且只收「端上有兜底」的。
 *
 * 判据：少了这个字段页面会不会崩。会崩的不许进这张表，要么后端补，要么改成可选。
 */
const EXEMPT: Record<string, string> = {};

describe("ops-web 契约字段（必填数组）", () => {
  const paths = methodPaths();
  const types = declaredTypes();
  const backend = backendReturns();

  const pairs = Object.entries(types)
    .filter(([m]) => paths[m])
    .map(([m, t]) => ({ method: m, path: paths[m]!, opsType: t, be: backend[paths[m]!] }))
    .filter((p) => p.be);

  it("能配上足够多的端点 —— 配不上说明解析退化了，那时断言恒真", () => {
    expect(pairs.length).toBeGreaterThan(5);
  });

  it("★★ ops-web 声明为必填的数组字段，后端 VO 必须有 —— 否则 .length 直接抛异常", () => {
    const bad: string[] = [];
    for (const p of pairs) {
      const comps = javaComponents(p.be!.ret, p.be!.file);
      if (comps === null) continue; // 解析不到后端类型：交给别的守卫，这里不误报
      for (const field of requiredArrayFields(p.opsType)) {
        const key = `${p.opsType}.${field}`;
        if (EXEMPT[key]) continue;
        if (!comps.includes(field)) {
          bad.push(`${key}（${p.path} → ${p.be!.ret}）—— 后端不下发，端上 .length/.map 会抛异常`);
        }
      }
    }
    expect(bad, "这些必填数组字段后端不下发：\n  " + bad.join("\n  ")).toEqual([]);
  });
});
