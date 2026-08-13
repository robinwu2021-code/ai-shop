// 契约声明的字段，后端必须真的下发 —— **逐个字段比，不只是比类型名**。
//
// 今天一天撞了三次同一个形状，三次都不报错：
//
//   · 核销台：契约 `Order`，后端发 `PickupOrderVO`（连 orderNo 都没有）
//   · 商品详情：契约有 `titleI18n`，后端不发 → 编辑一次译文就没了
//   · 售后：契约有 `updatedAt` / `merchantReply` / `buyerNickname`，后端发的是
//     库列名或干脆没有 → 时间显示成 NaN、商家回复整块不渲染、买家永远是「—」
//
// 前两次我只对了**类型名**，于是第三次照样漏过去。字段级才是有价值的那一层：
// TypeScript 只保证「端上自己前后一致」，它对后端发什么一无所知。
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const ROOT = join(import.meta.dirname, "../../..");
const read = (p: string) => readFileSync(join(ROOT, p), "utf8");

/** 端点名 → 路径 */
function endpoints(): Record<string, string> {
  const out: Record<string, string> = {};
  for (const m of read("b-app/src/api/endpoints.ts").matchAll(
    /(\w+):\s*\{\s*method:\s*"(?:GET|POST)",\s*path:\s*"([^"]+)"/g,
  )) {
    out[m[1]!] = m[2]!;
  }
  return out;
}

/** 端点名 → 契约声明的返回类型（去掉数组/分页壳） */
function declaredTypes(): Record<string, string> {
  const out: Record<string, string> = {};
  for (const m of read("b-app/src/api/contract.ts").matchAll(
    /^\s{2}(m\w+)\s*\([^)]*\)\s*:\s*Promise<([^;]+)>;/gm,
  )) {
    const t = m[2]!.trim()
      .replace(/^PageResult<(.*)>$/, "$1")
      .replace(/\[\]$/, "")
      .trim();
    if (/^[A-Z]\w+$/.test(t)) out[m[1]!] = t;
  }
  return out;
}

/** shared 里某个 interface 的字段：{ name, optional } */
function tsFields(type: string): { name: string; optional: boolean }[] {
  const src = read("packages/shared/src/types/index.ts");
  const i = src.indexOf(`export interface ${type} {`);
  if (i < 0) return [];
  const body = src.slice(i, src.indexOf("\n}", i));
  return [...body.matchAll(/^\s{2}(\w+)(\??):\s/gm)].map((m) => ({
    name: m[1]!,
    optional: m[2] === "?",
  }));
}

/** 收集后端所有 java 源码 */
function javaFiles(dir = "backend", out: string[] = []): string[] {
  for (const e of readdirSync(join(ROOT, dir))) {
    const p = `${dir}/${e}`;
    if (statSync(join(ROOT, p)).isDirectory()) javaFiles(p, out);
    else if (e.endsWith(".java") && !p.includes("/test/")) out.push(p);
  }
  return out;
}

const JAVA = javaFiles();

/** 路径 → 该端点的返回类型简名（去掉 List/PageData 壳） */
function backendReturns(): Record<string, string> {
  const out: Record<string, string> = {};
  for (const f of JAVA) {
    for (const m of read(f).matchAll(
      /@(?:Get|Post)Mapping\((?:value\s*=\s*)?"([^"]+)"\)[\s\S]{0,400}?public\s+([\w.<>?, ]+?)\s+\w+\s*\(/g,
    )) {
      const ret = m[2]!
        .replace(/^(java\.util\.)?(List|PageData|Page)<(.*)>$/, "$3")
        .replace(/^[\w.]*\./, "")
        .trim();
      out[m[1]!] = ret;
    }
  }
  return out;
}

/** record 的组件名（含跨行签名）。找不到返回 null —— 与「找到但没有字段」要分开 */
function javaComponents(simpleName: string): string[] | null {
  for (const f of JAVA) {
    if (!f.endsWith(`/${simpleName}.java`)) continue;
    const src = read(f);
    const i = src.indexOf(`public record ${simpleName}(`);
    if (i < 0) continue;
    let depth = 0;
    let j = src.indexOf("(", i);
    const start = j;
    for (; j < src.length; j++) {
      if (src[j] === "(") depth++;
      else if (src[j] === ")") {
        depth--;
        if (depth === 0) break;
      }
    }
    const body = src.slice(start + 1, j).replace(/\/\*[\s\S]*?\*\//g, "");
    // 组件形如 `String foo` / `List<X> bar` / `long baz`
    return [...body.matchAll(/[\w.<>?\[\], ]+?\s(\w+)\s*(?:,|$)/g)].map((m) => m[1]!);
  }
  return null;
}

/**
 * 已知可以不一致的，必须写清为什么。
 *
 * 判据是**端上少了这个字段会怎样**：显示不出来一眼可见的（店名、图）还好，
 * 静默改变判断或整块不渲染的，不许进这张表。
 */
const EXEMPT: Record<string, string> = {
  "MerchantProfile.workability": "端上派生字段，不来自后端",
  "Order.idempotencyKey": "下单请求参数，回读时后端不必带",
  "Order.subOrders": "仅支付视角有；B 端子单视图为空数组",
};

describe("B 端契约字段", () => {
  const eps = endpoints();
  const decls = declaredTypes();
  const rets = backendReturns();

  it("解析到足够多的端点与后端返回类型（正则失效时不要静默通过）", () => {
    expect(Object.keys(eps).length).toBeGreaterThan(50);
    expect(Object.keys(decls).length).toBeGreaterThan(30);
    expect(Object.keys(rets).length).toBeGreaterThan(50);
  });

  it("★★★ 契约里的必填字段，后端 VO 必须真的有 —— 少一个就是屏幕上静默少一块", () => {
    const offenders: string[] = [];

    for (const [name, path] of Object.entries(decls)) {
      const url = (eps[name] ?? "").replace(/:(\w+)/g, "{$1}");
      if (!url.startsWith("/biz")) continue;
      const ret = rets[url];
      if (!ret) continue; // 后端路径没解析到：另一条守卫的事
      const comps = javaComponents(ret);
      if (!comps) continue; // 不是 record（Map/void/未知）——比不了就别假装比过

      const missing = tsFields(path)
        .filter((f) => !f.optional)
        .map((f) => f.name)
        .filter((f) => !comps.includes(f))
        .filter((f) => !EXEMPT[`${path}.${f}`]);

      if (missing.length) {
        offenders.push(`${name}（${url}）契约 ${path} 要 ${missing.join("/")}，而 ${ret} 没有`);
      }
    }

    expect(
      offenders,
      "契约声明了、后端 VO 里没有的字段 ——\n"
        + "  端上照契约写，屏幕上就静默少一块：时间显示成 NaN、整块 v-if 不渲染、\n"
        + "  或者永远是「—」。TypeScript 挡不住这类：它只保证端上自己前后一致。\n"
        + "  修：后端补字段（多数情况），或改契约并同步改页面。\n  "
        + offenders.join("\n  "),
    ).toEqual([]);
  });
});
