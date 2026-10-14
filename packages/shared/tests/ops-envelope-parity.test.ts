// 运营端契约与后端的**返回信封**必须是同一个形状。
//
// 为什么这道闸单独存在：`ops-endpoint-exists` 只问「后端有没有这条路径」，
// `openapi-parity` 只问「契约方法有没有对应的 client 调用」——
// **两者都不看返回的是分页包还是裸数组**。
//
// 而这类缺陷 mock 挡不住：契约怎么声明，mock 就怎么返，本地永远全绿；
// 只有切到真后端那一刻才炸，形态是 `data.map is not a function`——
// 整块列表消失，不像「少了个字段」那样好认。
//
// 2026-09-02 真撞到一次：`/ops/merchants/violations` 后端一直返 `PageData`，
// 而契约声明成 `Violation[]`，信用档案抽屉与违规处置列表在真后端下整块崩掉，
// mock 返裸数组所以谁也没发现。这道闸就是那次之后加的。
import { readFileSync, readdirSync, existsSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { backendModules, assertScanScope } from "./backend-modules";

const ROOT = join(import.meta.dirname, "../../..");
const BASELINE = join(ROOT, "ops-web/known-envelope-mismatches.txt");

const norm = (p: string) => p.replace(/\$\{[^}]+\}/g, "{x}").replace(/\{\w+\}/g, "{x}");

/** ops-web https 层：方法名 → `VERB 规范化路径` */
function methodToPath(): Map<string, string> {
  const dir = join(ROOT, "ops-web/lib/api/https");
  const out = new Map<string, string>();
  if (!existsSync(dir)) return out;
  for (const f of readdirSync(dir).filter((x) => x.endsWith(".ts") && !x.includes(".test."))) {
    const src = readFileSync(join(dir, f), "utf8");
    // 与 openapi-parity 同一条正则的路径版：认 async、块体、包装调用与泛型
    const re = /(\w+):\s*(?:async\s*)?\([^)]*\)\s*=>\s*(?:\{[\s\S]{0,400}?)?(?:[\w$.]+\(\s*)*(?:await\s+)?client\.(get|post|put|del)(?:<[^(\n]*>)?\(\s*[`"]([^`"]+)/g;
    let m: RegExpExecArray | null;
    while ((m = re.exec(src))) {
      if (m[3]!.startsWith("/ops/")) out.set(m[1]!, `${m[2]!.toUpperCase()} ${norm(m[3]!)}`);
    }
  }
  return out;
}

/** ops-web 契约层：方法名 → `Promise<…>` 里声明的那个类型 */
function methodToReturn(): Map<string, string> {
  const dir = join(ROOT, "ops-web/lib/api/contracts");
  const out = new Map<string, string>();
  if (!existsSync(dir)) return out;
  for (const f of readdirSync(dir).filter((x) => x.endsWith(".ts"))) {
    const src = readFileSync(join(dir, f), "utf8");
    /*
     * `)` 与 `:` 之间**允许换行** —— 长签名会写成
     *   `listOpsMembers(q?: {...})\n    : Promise<Page<OpsMember>>;`
     * 要求两者紧挨的话，这一条匹配不上，正则会继续往下吃、
     * 与**下一个方法**的返回类型配成一对 —— 于是报出一条根本不存在的不一致，
     * 而被误报的那个方法其实完全正确。立此闸当天就踩了一次（listOpsMembers）。
     */
    for (const m of src.matchAll(/^\s{2}(\w+)\s*(?:<[^>]*>)?\([\s\S]*?\)\s*:\s*Promise<([\s\S]*?)>;\s*$/gm)) {
      out.set(m[1]!, m[2]!.trim().replace(/\s+/g, " "));
    }
  }
  return out;
}

/** 后端：`VERB 规范化路径` → 方法的 Java 返回类型 */
function pathToBackendReturn(): Map<string, string> {
  const out = new Map<string, string>();
  const walk = (dir: string) => {
    if (!existsSync(dir)) return;
    for (const e of readdirSync(dir, { withFileTypes: true })) {
      const p = join(dir, e.name);
      if (e.isDirectory()) { walk(p); continue; }
      if (!e.name.endsWith("Controller.java")) continue;
      const src = readFileSync(p, "utf8");
      const prefix = src.match(/@RequestMapping\(\s*"([^"]+)"\s*\)/)?.[1] ?? "";
      // 注解与 `public 返回类型 方法名(` 之间可能隔着 @PreAuthorize 与整段 javadoc
      const re = /@(Get|Post|Put|Delete)Mapping\(\s*(?:value\s*=\s*)?"([^"]*)"\s*\)[\s\S]{0,400}?\n\s*public\s+([\w.<>,\s[\]?]+?)\s+\w+\s*\(/g;
      let m: RegExpExecArray | null;
      while ((m = re.exec(src))) {
        const raw = m[2]!;
        const full = raw.startsWith("/ops/") ? raw : (prefix.startsWith("/ops") ? prefix + raw : null);
        if (!full) continue;
        const verb = m[1]!.toUpperCase().replace("DELETE", "DEL");
        out.set(`${verb} ${norm(full)}`, m[3]!.trim().replace(/\s+/g, " "));
      }
    }
  };
  const mods = assertScanScope(backendModules(join(ROOT, "backend")));
  for (const mod of mods) walk(join(ROOT, "backend", mod, "src/main/java/ai/neargo/shop"));
  return out;
}

type Shape = "page" | "array" | "void" | "scalar";
const tsShape = (t: string): Shape =>
  /^Page<.+>$/.test(t) ? "page" : /\[\]$/.test(t) ? "array" : t === "void" ? "void" : "scalar";
const javaShape = (t: string): Shape =>
  /^(ai\.neargo\.shop\.common\.)?PageData</.test(t) ? "page"
    : /^(java\.util\.)?(List|Set|Collection)</.test(t) || /\[\]$/.test(t) ? "array"
      : t === "void" ? "void" : "scalar";

function baseline(): Set<string> {
  if (!existsSync(BASELINE)) return new Set();
  return new Set(readFileSync(BASELINE, "utf8").split("\n")
    .map((l) => l.trim()).filter((l) => l && !l.startsWith("#")));
}

function mismatches() {
  const paths = methodToPath();
  const rets = methodToReturn();
  const backend = pathToBackendReturn();
  const bad: { name: string; detail: string }[] = [];
  let compared = 0;
  for (const [name, key] of paths) {
    const ts = rets.get(name);
    const jv = backend.get(key);
    if (!ts || !jv) continue;          // 后端还没有这条 → 归 ops-endpoint-exists 管
    compared++;
    const a = tsShape(ts);
    /*
     * 契约声明 void = 「我不读响应体」，这是正当声明，与后端返什么无关。
     * 不豁免的话 lockChannel / refreshOnboarding 这类「调完就重取」的写操作会被误报，
     * 而把它们改成跟后端同形只会逼前端去接一个自己根本不用的类型。
     */
    if (a === "void") continue;
    const b = javaShape(jv);
    if (a !== b) bad.push({ name, detail: `${name}  [${key}]  契约 ${a}(${ts}) ≠ 后端 ${b}(${jv})` });
  }
  return { bad, compared };
}

describe("运营端返回信封一致性", () => {
  it("扫得到足够多的可比对端点 —— 少扫会让这道闸静默失效", () => {
    // 「找出违规」型的闸门，少扫 = 全绿。先证明量具本身在工作
    expect(mismatches().compared).toBeGreaterThan(200);
  });

  it("★ 契约与后端的返回信封必须同形（分页包 / 数组 / 单对象）", () => {
    const { bad } = mismatches();
    const known = baseline();
    const added = bad.filter((b) => !known.has(b.name));
    expect(added.map((b) => b.detail),
      "这些端点的契约与后端返回形状对不上。**mock 挡不住这一类** —— 本地全绿，"
      + "切真后端时页面会 `data.map is not a function` 整块崩掉。\n"
      + "修法：契约改成后端的形状 → mock 跟着改 → 页面读 records。\n"
      + `确实要暂缓就登记进 ${BASELINE}（只准变短）。\n`).toEqual([]);
  });

  it("基线里已经修好的要删掉 —— 否则那条端点永远免检", () => {
    const { bad } = mismatches();
    const names = new Set(bad.map((b) => b.name));
    const stale = [...baseline()].filter((n) => !names.has(n));
    expect(stale, "这些已经不再不一致了，把它们从 known-envelope-mismatches.txt 里删掉：\n"
      + stale.join("\n")).toEqual([]);
  });
});
