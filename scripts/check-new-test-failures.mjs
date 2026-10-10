#!/usr/bin/env node
/**
 * 只拦「新增的」测试失败，不拦历史欠账。
 *
 * 为什么不是「全绿才让过」：闸门立起来的那天全量有 128 条红（其中 48 条比这次
 * 改动还老）。要求全绿的闸门会从第一天起就是红的，而一个恒红的闸门等于没有闸门 ——
 * 所有人学会的第一件事就是无视它。所以这里锁的是**差集**：
 *
 *   已知失败清单里的 → 放行（欠账，另行清理）
 *   清单里没有的失败 → 拦下（这次改动引入的）
 *   清单里有、现在却绿了 → 提示把它从清单里删掉（清单只能变短）
 *
 * 清单：backend/known-failures.txt，一行一个 `类全名.方法名`。
 * 修好一条就删一行 —— 删到空文件那天，把这个脚本换成「必须全绿」。
 *
 * 用法：
 *   node scripts/check-new-test-failures.mjs [surefire-reports 目录...]
 * 不传目录时自动扫 backend/(星)/target/surefire-reports。
 */
import { readFileSync, existsSync, readdirSync, statSync } from 'node:fs';
import { join, resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const LIST = join(ROOT, 'backend', 'known-failures.txt');

/** 从 surefire 的 .txt 报告里抽出失败/出错的测试标识。 */
const FAILING = /^([A-Za-z0-9_.$]+\.[A-Za-z0-9_$]+)\s+--\s+Time elapsed.*<<<\s+(FAILURE|ERROR)!/gm;

function reportDirs(argv) {
  if (argv.length) return argv.map((d) => resolve(d));
  const out = [];
  const backend = join(ROOT, 'backend');
  if (!existsSync(backend)) return out;
  for (const m of readdirSync(backend)) {
    const d = join(backend, m, 'target', 'surefire-reports');
    if (existsSync(d) && statSync(d).isDirectory()) out.push(d);
  }
  return out;
}

function collectFailures(dirs) {
  const found = new Set();
  let sawAnyReport = false;
  for (const dir of dirs) {
    for (const f of readdirSync(dir)) {
      if (!f.endsWith('.txt')) continue;
      sawAnyReport = true;
      const text = readFileSync(join(dir, f), 'utf8');
      for (const m of text.matchAll(FAILING)) found.add(m[1]);
    }
  }
  return { found, sawAnyReport };
}

function readKnown() {
  if (!existsSync(LIST)) return new Set();
  return new Set(
    readFileSync(LIST, 'utf8')
      .split('\n')
      .map((l) => l.replace(/#.*$/, '').trim())
      .filter(Boolean),
  );
}

const dirs = reportDirs(process.argv.slice(2));
if (!dirs.length) {
  // 没有报告目录 ≠ 没有失败。静默通过是这类脚本最常见的病根。
  console.error('✗ 找不到任何 surefire-reports 目录 —— 测试根本没跑起来，不是「全绿」');
  process.exit(1);
}

const { found, sawAnyReport } = collectFailures(dirs);
if (!sawAnyReport) {
  console.error(`✗ ${dirs.length} 个目录里一个 .txt 报告都没有 —— 测试没跑`);
  process.exit(1);
}

const known = readKnown();
const added = [...found].filter((t) => !known.has(t)).sort();
const fixed = [...known].filter((t) => !found.has(t)).sort();

console.log(`本次失败 ${found.size} 条，已知欠账 ${known.size} 条`);

if (fixed.length) {
  console.log(`\n✓ 有 ${fixed.length} 条欠账已经修好了，请从 backend/known-failures.txt 里删掉：`);
  for (const t of fixed) console.log(`    ${t}`);
}

if (added.length) {
  console.error(`\n✗ 新增 ${added.length} 条失败（这次改动引入的）：`);
  for (const t of added) console.error(`    ${t}`);
  console.error('\n修掉它们，或者——如果确认是既有问题被这次改动暴露出来——把它加进');
  console.error('backend/known-failures.txt 并在提交信息里写清为什么。');
  process.exit(1);
}

console.log('\n✓ 没有新增失败');
