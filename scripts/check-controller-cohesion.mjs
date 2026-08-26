// 一个 Controller 只装一个资源。
//
// **判据是「路径第一段的种数」，不是端点数。**
// 按端点数定阈值会逼人把一个资源硬拆成两半来达标 —— 那比不管更糟。
// 前缀数超标则不同：它**直接说明这个类装了不止一个资源**，而且指向重构方向。
//
// 由来（2026-08-26 架构评审）：96 个控制器里中位数 3~5 个端点，粒度本身健康；
// 问题在另一头 —— `BizMerchantController` 一个类 40 个端点 / 1022 行 /
// **九个不相干的节**（店铺资料、资质、送货方式、预约排期、社区提报、收款进件、
// 门店管理、员工授权、角色），路径前缀 10 个。
//
// ⚠️ 那九个节里**预约排期是本次加进去的**。加的时候理由是「和送货方式同类、
// 权限也一样」—— 理由本身没错，错在没回头看这个类已经多大了。
// 一个 1022 行的类，每个人加的时候都有一个局部合理的理由。这条守卫就是补那一眼。
//
// 用法：
//   node scripts/check-controller-cohesion.mjs           # 列出来
//   node scripts/check-controller-cohesion.mjs --check    # 超过基线就非零退出
import { readFileSync, readdirSync, statSync, existsSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const BASELINE = join(ROOT, "backend/known-fat-controllers.txt");
/**
 * 阈值 3，不是 1。
 *
 * 定 1 试过：99 个里 44 个超标，而其中大半是**同一个资源的近亲**——
 * `members` / `member-tags` / `member-settings` 是一件事的三个面，
 * 路径分段不同不代表它们是三个资源。把它们拆成三个控制器不会让谁更好找。
 *
 * 3 这个数是照着分布定的：中位控制器 3~5 个端点、1~2 个资源，
 * 3 已经是「明显装多了」。它挑出来的 8 个里，最少的也有 3 个不相干的段。
 * **判据宁可宽**：误报一多就没人看了，而真正的那几个会跟着被埋掉。
 */
const LIMIT = 3;

const files = [];
(function walk(d) {
  for (const f of readdirSync(d)) {
    const p = join(d, f);
    if (statSync(p).isDirectory()) {
      if (f === "target" || f === "test") continue;
      walk(p);
    } else if (f.endsWith("Controller.java")) files.push(p);
  }
})(join(ROOT, "backend"));

const rows = [];
for (const p of files) {
  const src = readFileSync(p, "utf8");
  if (!/@RestController\b/.test(src) || /@RestControllerAdvice/.test(src)) continue;
  const name = p.split("/").pop().replace(".java", "");

  // 类上的 @RequestMapping 前缀 + 方法上的完整路径，都归一到「第一段」
  const base = src.match(/@RequestMapping\("([^"]+)"\)/)?.[1] ?? "";
  const segs = new Set();
  for (const m of src.matchAll(/@(?:Get|Post|Put|Delete|Patch)Mapping\(\s*(?:value\s*=\s*)?"([^"]*)"/g)) {
    const full = m[1].startsWith("/") ? m[1] : `${base}/${m[1]}`;
    // 去掉端前缀（ops/biz/mp/callback），取下一段 —— 那才是「资源」
    const parts = full.split("/").filter(Boolean);
    const i = ["ops", "biz", "mp", "callback"].includes(parts[0]) ? 1 : 0;
    const seg = parts[i];
    // 路径变量与配置占位不算资源
    if (seg && !seg.startsWith("{") && !seg.startsWith("$")) segs.add(seg);
  }
  if (segs.size > LIMIT) rows.push({ name, segs: [...segs].sort() });
}

rows.sort((a, b) => b.segs.length - a.segs.length);

const known = existsSync(BASELINE)
  ? new Set(readFileSync(BASELINE, "utf8").split("\n").map((l) => l.trim())
      .filter((l) => l && !l.startsWith("#")))
  : new Set();
const fresh = rows.filter((r) => !known.has(r.name));

console.log(`控制器 ${files.length}｜装了不止一个资源的 ${rows.length}（已知欠账 ${known.size}）`);
for (const r of rows) {
  console.log(`   ${fresh.includes(r) ? "★新增" : "     "} ${String(r.segs.length).padStart(2)} 个资源  ${r.name.padEnd(30)} ${r.segs.join(" ")}`);
}

if (process.argv.includes("--check") && fresh.length) {
  console.error(`\n✗ ${fresh.length} 个控制器装了不止一个资源。`);
  console.error("  要么把新端点放到对应资源的控制器里（没有就新建一个），");
  console.error("  要么登记进 backend/known-fat-controllers.txt 并写明为什么。");
  console.error("  ⚠️ 别为了达标把一个资源硬拆成两半 —— 判据是资源数，不是端点数。");
  process.exit(1);
}
