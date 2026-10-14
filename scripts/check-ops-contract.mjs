#!/usr/bin/env node
/**
 * ops-web 契约对账 —— 运营端在调的每一条 `/ops/**`，后端到底有没有。
 *
 * 为什么需要这么一个东西：ops-web 的开关是 `NEXT_PUBLIC_USE_MOCK !== "0"`，
 * **默认走 mock**。于是页面永远是绿的、点得动、有数据，而后端可能根本没这个接口 ——
 * 这种缺陷不会以「报错」的形式出现，只会在真正连后端的那一天集体爆出来。
 * 单测也盖不住它：mock 层自己就是被测对象，测得越全越看不出后端缺什么。
 *
 * 比的是**形状**不是字面量：前端 `${storeNo}` 与后端 `{storeNo}` 一律抹成 `*`，
 * 因为两边的参数名经常不同（`{no}` vs `${merchantNo}`），按字面比会得到一串假阳性。
 *
 * 棘轮，不是全绿闸门（同 backend/known-failures.txt 的理由）：立起来这天有 27 条
 * 真缺口，要求「全部补齐才让过」会让它从第一天起就恒红，而恒红的闸门等于没有闸门。
 * 所以锁的是差集：
 *
 *   清单里有的      → 放行（已知欠账）
 *   清单里没有的缺口 → 拦下（这次新写的前端调用没有后端）
 *   清单里有、后端却已经实现了 → 提示删行（清单只准变短）
 *
 * 用法：
 *   node scripts/check-ops-contract.mjs           列出全部缺口（人看）
 *   node scripts/check-ops-contract.mjs --check   只判差集（闸门用）
 */
import { readFileSync, readdirSync, existsSync, statSync } from "node:fs";
import { join, resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const LIST = join(ROOT, "ops-web", "known-mock-endpoints.txt");
const CHECK = process.argv.includes("--check");

/** 路径参数一律抹成 `*`；末尾斜杠去掉。 */
const shape = (p) => p.replace(/[:{]\w+\}?/g, "*").replace(/\/$/, "") || "/";

// ─────────────────────────────────────────────────────────── 后端
/**
 * 扫 backend 下**所有**模块，不是写死的那几个。
 * gen-api-index.mjs 里那份目录清单漏了 shop-base 与 shop-notify —— 漏扫的后果不是
 * 报错，是把「后端明明有」算成「零实现」，然后有人照着这张表去补一个已经存在的接口。
 */
function backendRoutes() {
  const out = new Set();
  const walk = (d) => {
    for (const f of readdirSync(d)) {
      const p = join(d, f);
      if (statSync(p).isDirectory()) { walk(p); continue; }
      if (!f.endsWith(".java")) continue;
      const src = readFileSync(p, "utf8");
      // 类级 @RequestMapping 是前缀，方法级只写后缀 —— 只扫方法级会漏掉一半路由
      const base = src.match(/@RequestMapping\(\s*"([^"]+)"/)?.[1] ?? "";
      for (const m of src.matchAll(/@(Get|Post|Put|Delete)Mapping\(\s*(?:value\s*=\s*)?"([^"]*)"/g)) {
        const sfx = m[2];
        const path = sfx.startsWith("/") ? base + sfx : (base || sfx);
        if (path.startsWith("/")) out.add(`${m[1].toUpperCase()} ${shape(path)}`);
      }
      // 无参数的 @GetMapping：路径就是类级前缀
      for (const m of src.matchAll(/@(Get|Post|Put|Delete)Mapping\s*\r?\n/g)) {
        if (base) out.add(`${m[1].toUpperCase()} ${shape(base)}`);
      }
    }
  };
  for (const m of readdirSync(join(ROOT, "backend"))) {
    const d = join(ROOT, "backend", m, "src/main/java/ai/neargo/shop");
    if (existsSync(d)) walk(d);
  }
  return out;
}

// ─────────────────────────────────────────────────────────── 前端
/**
 * 模板串里的 `${...}` 有两种含义，混为一谈会造假：
 *   `${storeNo}`                       → 路径参数，抹成 `*`
 *   `${locked ? "lock" : "unlock"}`    → **两条不同的路由**，要展开成两条
 * 第一版把后者也当路径参数抹掉，于是结尾的 lock 与 unlock 双双变成通配，
 * 两条路由塌成同一条；后端明明都有，却被报成「零实现」。
 * （这段注释本身也踩过一次：把带通配的路径写进块注释会提前把注释关掉。）
 */
function expand(raw) {
  const m = raw.match(/\$\{[^}]*\?[^}]*\}/);
  if (!m) return [raw.replace(/\$\{[^}]*\}/g, "*")];
  const lits = [...m[0].matchAll(/["'`]([^"'`]*)["'`]/g)].map((x) => x[1]);
  if (!lits.length) return [raw.replace(/\$\{[^}]*\}/g, "*")];
  return lits.flatMap((l) => expand(raw.replace(m[0], l)));
}

function opsCalls() {
  const dir = join(ROOT, "ops-web/lib/api/https");
  const out = [];
  if (!existsSync(dir)) return out;
  for (const f of readdirSync(dir).filter((x) => x.endsWith(".ts") && !x.endsWith(".test.ts"))) {
    const src = readFileSync(join(dir, f), "utf8");
    // 反引号串与双引号串要分开匹配：模板串里合法地含有 `"lock"` 这种引号，
    // 用 `[^`"]+` 一把抓会在第一个引号处截断，把路径切掉半截 —— 而截出来的
    // 半截路径当然「后端没有」，于是一条实现好的接口被报成零实现。
    const RE = /client\.(get|post|put|delete)\(\s*(?:`([^`]*)`|"([^"]*)")/g;
    for (const m of src.matchAll(RE)) {
      for (const p of expand(m[2] ?? m[3])) {
        out.push({ file: f, key: `${m[1].toUpperCase()} ${shape(p)}` });
      }
    }
  }
  return out;
}

function readKnown() {
  if (!existsSync(LIST)) return new Set();
  return new Set(
    readFileSync(LIST, "utf8").split("\n")
      .map((l) => l.replace(/#.*$/, "").trim()).filter(Boolean),
  );
}

// ─────────────────────────────────────────────────────────── 对账
const be = backendRoutes();
if (be.size < 100) {
  // 扫不到路由 ≠ 后端什么都没实现。静默通过是这类脚本最常见的病根。
  console.error(`✗ 只扫到 ${be.size} 条后端路由 —— 扫描器坏了，不是后端空了`);
  process.exit(1);
}
const calls = opsCalls();
if (!calls.length) {
  console.error("✗ 一条 ops-web 调用都没扫到 —— 扫描器坏了，或 http 层换了写法");
  process.exit(1);
}

const byKey = new Map();
for (const c of calls) if (!byKey.has(c.key)) byKey.set(c.key, c.file);
const missing = new Map([...byKey].filter(([k]) => !be.has(k)));
const known = readKnown();
const added = [...missing.keys()].filter((k) => !known.has(k)).sort();
const done = [...known].filter((k) => !missing.has(k)).sort();

console.log(
  `后端路由 ${be.size} ｜ ops-web 调用 ${byKey.size} 条 ｜ ` +
  `后端有 ${byKey.size - missing.size} ｜ 缺 ${missing.size}（已知欠账 ${known.size}）`,
);

if (!CHECK) {
  const groups = new Map();
  for (const [k, f] of missing) {
    if (!groups.has(f)) groups.set(f, []);
    groups.get(f).push(k);
  }
  for (const [f, ks] of [...groups].sort((a, b) => b[1].length - a[1].length)) {
    const tot = [...byKey].filter(([, x]) => x === f).length;
    console.log(`\n-- ${f}   ${ks.length}/${tot} 缺`);
    ks.sort().forEach((k) => console.log(`   ${k}`));
  }
}

if (done.length) {
  console.log(`\n✓ 有 ${done.length} 条后端已经补上了，请从 ops-web/known-mock-endpoints.txt 删掉：`);
  done.forEach((k) => console.log(`    ${k}`));
}

if (added.length) {
  console.error(`\n✗ 新增 ${added.length} 条「前端在调、后端没有」：`);
  added.forEach((k) => console.error(`    ${k} （${missing.get(k)}）`));
  console.error("\n补上后端，或者——如果确实是先画界面后补接口——把它加进");
  console.error("ops-web/known-mock-endpoints.txt 并在提交信息里写清什么时候补。");
  process.exit(1);
}

console.log(CHECK ? "\n✓ 没有新增的空接口" : "");
