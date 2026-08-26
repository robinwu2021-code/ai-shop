#!/usr/bin/env node
/**
 * 棘轮：**新页面不许自造库里已经有的件**。
 *
 * 判据不在这里 —— 在 `scripts/gen-ui-lib.py` 的 `ROLLED` 表里，本脚本只读它的产物
 * `docs/technical/design/ui-lib.json`。两处各写一份判据的话，改了一处另一处照样绿。
 *
 * **为什么这一刻才立**：2026-08-26 这一轮把缺件补齐了（库 14 → 27 个组件），
 * 缺口从 16 类降到 1 类。闸门要立在「刚清完」的时候 —— 早了是恒红，
 * 晚了就又长回来。上一次 `sh-tabs` 就是这么长回来的：方块 tab 收编过一次，
 * 没有闸门，半年后新页面又各画各的。
 *
 * **只管「库里有、页面没用」这一类**（rolled 里 gap !== true）。
 * 唯一的缺口「列表行」不进闸门 —— 它已论证不该做成组件（见覆盖清单 §九），
 * 把它拦下来只会让每个新列表页都被挡一次，而正确做法就是自己写。
 * **恒红的闸门是噪声掩体**，会把真失败一起藏掉。
 */
import { existsSync, readFileSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = process.argv.find((a) => a.startsWith("--root="))?.slice(7)
  ?? join(dirname(fileURLToPath(import.meta.url)), "..");
const CATALOG = join(ROOT, "docs/technical/design/ui-lib.json");
const BASELINE = join(ROOT, "known-handrolled-ui.txt");
const check = process.argv.includes("--check");

if (!existsSync(CATALOG)) {
  console.error(`✗ 找不到 ${CATALOG} —— 先跑 python3 scripts/gen-ui-lib.py`);
  process.exit(1);
}

const rows = [];
for (const p of JSON.parse(readFileSync(CATALOG, "utf8")).pages) {
  for (const r of p.rolled ?? []) {
    if (r.gap) continue;                       // 缺口不进闸门，理由见文件头
    rows.push({ id: `${p.page} ${r.id}`, label: r.label, lib: r.lib });
  }
}

const known = existsSync(BASELINE)
  ? new Set(readFileSync(BASELINE, "utf8").split("\n")
      .map((l) => l.trim()).filter((l) => l && !l.startsWith("#")))
  : new Set();

const fresh = rows.filter((r) => !known.has(r.id));
const stale = [...known].filter((k) => !rows.some((r) => r.id === k));

console.log(`自造件 ${rows.length} 处（已知欠账 ${known.size}）`);
for (const r of rows) {
  console.log(`   ${fresh.includes(r) ? "★新增" : "     "} ${r.id.padEnd(28)} ${r.label} → 用 ${r.lib}`);
}

if (stale.length) {
  console.log(`\n✅ 这 ${stale.length} 条已经不自造了，把它们从基线里删掉：`);
  for (const k of stale) console.log(`      ${k}`);
}

if (check && fresh.length) {
  console.error(`\n✗ 新增了 ${fresh.length} 处自造 —— 库里有现成的件。`);
  console.error("  要么改用上面点名的那个组件，要么登记进 known-handrolled-ui.txt 并写明为什么。");
  console.error("  ⚠️ 这类问题不报错也不难看：页面能跑、样子也过得去，只是又多了一份要各自维护的皮。");
  process.exit(1);
}
if (check && stale.length) {
  console.error(`\n✗ 基线里有 ${stale.length} 条已经修好了，删掉它们。`);
  console.error("  留着的话，那个页面将来又自造起来也不会有人发现 —— 基线只准变短。");
  process.exit(1);
}
