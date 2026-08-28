// 后端有、运营端**没有出口**的 /ops 端点。
//
// 与 check-ops-contract.mjs **方向相反，两个都要跑**：
//   · check-ops-contract  运营端调了 → 后端有没有   （画了没后端 → 接真后端时 404）
//   · 本脚本              后端有了   → 运营端调没调 （做了没入口 → 功能存在但没人用得上）
//
// 只扫一边会漏掉一整类问题，而漏掉的那一类**更难发现**：
// 「画了没后端」在切真后端时会立刻报错；「做了没入口」不报任何错 ——
// 它表现为「这个功能我们做过啊」，而运营点不到。
// 2026-08-26 首次扫出 18 条，其中 13 条是整条自营应付+发票链路 ——
// 而那是当时唯一真能把钱付出去的路。
//
// 用法：
//   node scripts/check-ops-orphan.mjs           # 列出来
//   node scripts/check-ops-orphan.mjs --check   # 超过基线就非零退出
import { readFileSync, readdirSync, existsSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { scanEndpoints } from "./gen-perm-endpoint-matrix.mjs";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const HTTP_DIR = join(ROOT, "ops-web/lib/api/https");
const BASELINE = join(ROOT, "ops-web/known-orphan-endpoints.txt");

/**
 * `{no}`（后端）与 `${x}`（前端模板串）都压成 `*`，两边才比得了。
 *
 * ⚠️ **`${...}` 必须先替换**。反过来的话 `{x}` 那条会先吃掉 `${x}` 的花括号、
 * 留下一个孤立的 `$`，于是 `/a/${no}` 归一成 `/a/$*` 而后端是 `/a/*` —— 永远对不上。
 * 第一版就是这个顺序，凭空多报了 7 条「无出口」。
 */
const norm = (p) => p.replace(/\$\{[^}]*\}/g, "*").replace(/\{[^}]*\}/g, "*").replace(/\/+$/, "");

/**
 * 走不了 https 层、但确实是出口的调用点。
 *
 * **只列具体文件，不放宽成整个 `lib/`** —— 那样注释里提到一句路径也会被当成
 * 「有出口」，而这道闸的全部价值就在于它说的是真话。
 *
 * - `lib/stream.ts`：SSE 长连接。它用 `fetch` 而不是浏览器原生 `EventSource`，
 *   因为后者不支持自定义请求头，而运营端的会话在头里。正是这个写法绕开了
 *   只扫 https 目录的检测 —— `/ops/stream` 于是被报成「做了没入口」，
 *   而 `app/jobs/page.tsx` 一直在用它。
 */
const EXTRA_CALL_SITES = ["ops-web/lib/stream.ts"];

function calledPaths() {
  const out = new Set();
  const files = readdirSync(HTTP_DIR).map((f) => join(HTTP_DIR, f));
  for (const rel of EXTRA_CALL_SITES) {
    const abs = join(ROOT, rel);
    if (existsSync(abs)) files.push(abs);
  }
  for (const f of files) {
    const src = readFileSync(f, "utf8");
    // 模板串里带 ${} 的也要收 —— 它们正是带路径参数的那些
    for (const m of src.matchAll(/["`](\/ops\/[^"`\n]*)/g)) out.add(norm(m[1]));
  }
  return out;
}

export function orphans() {
  const called = [...calledPaths()];
  return scanEndpoints().filter((e) => {
    const p = norm(e.path);
    // 前缀命中也算：调用方可能写 `/ops/x` 再拼 `/${no}/y`
    return !called.some((c) => c === p || p.startsWith(c));
  });
}

const rows = orphans();
const check = process.argv.includes("--check");
const known = existsSync(BASELINE)
  ? new Set(readFileSync(BASELINE, "utf8").split("\n").map((l) => l.trim())
      .filter((l) => l && !l.startsWith("#")))
  : new Set();

const fresh = rows.filter((r) => !known.has(`${r.method} ${r.path}`));

console.log(`/ops 端点 ${scanEndpoints().length}｜运营端有出口 ${scanEndpoints().length - rows.length}｜无出口 ${rows.length}（已知欠账 ${known.size}）`);
for (const r of rows) {
  console.log(`   ${fresh.includes(r) ? "★新增" : "     "} ${r.method} ${r.path}`);
}

// 基线里已经接上出口的行 —— 补完了却忘了删。
// 与 check-controller-cohesion 的同一个漏洞：名单上的条目是免检的，
// 不删的话它将来被摘掉出口也不会有人发现，而这份清单的整个价值就是「只准变短」。
const stale = [...known].filter((k) => !rows.some((r) => `${r.method} ${r.path}` === k));

if (stale.length) {
  console.log(`\n✅ 这 ${stale.length} 条已经有出口了，把它们从基线里删掉：`);
  for (const k of stale) console.log(`      ${k}`);
}

if (check && fresh.length) {
  console.error(`\n✗ 新增了 ${fresh.length} 条「做了没入口」的端点。`);
  console.error("  要么给它加运营端出口，要么登记进 ops-web/known-orphan-endpoints.txt 并写明为什么。");
  process.exit(1);
}

if (check && stale.length) {
  console.error(`\n✗ 基线里有 ${stale.length} 条已经接上出口了，删掉它们。`);
  console.error("  留着的话，将来那个出口被摘掉也不会有人发现 —— 名单上的条目是免检的。");
  process.exit(1);
}
