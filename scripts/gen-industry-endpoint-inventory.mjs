#!/usr/bin/env node
/**
 * 行业化端点盘点 —— 终局选型（P3）的**可复算底表**。
 *
 * 为什么要有它：零售基线那一版的 299 / 27 / 250 是一次性 grep 出来的，
 * **下次没人能复现**。而判定阈值就按那个分母算 —— 分母不可复算，结论就不可复核。
 *
 * 三个来源：
 *   · 端点   ← 扫 backend 的 @XxxMapping("/biz…" | "/mp…")
 *   · 消费方 ← b-app / c-app 的 endpoints.ts（静态前缀匹配，**结果是上界**）
 *   · 关系码 ← scripts/industry-endpoint-map.mjs（登记表，判据来自两份行业 TDD）
 *
 * 用法：npm run gen:industry-inventory
 */
import { readFileSync, writeFileSync } from "node:fs";
import { execSync } from "node:child_process";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { subjectOf, relationOf, CODES } from "./industry-endpoint-map.mjs";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const OUT = join(ROOT, "docs/technical/reference/行业化端点盘点.md");

/** 外部系统打进来的端点：回调地址只能有一个，恒走基座（否决项，不参与占比） */
const EXTERNAL = [/^\/callback/, /\/notify$/];

const scan = () => {
  const raw = execSync(
    `grep -rhoE '@(Get|Post|Put|Delete|Patch)Mapping\\("(/biz|/mp)[^"]*"' --include='*.java' backend | grep -v target`,
    { cwd: ROOT, encoding: "utf8", maxBuffer: 1 << 24 },
  ).trim().split("\n").filter(Boolean);
  const seen = new Set();
  return raw.map((l) => {
    const m = l.match(/@(\w+)Mapping\("([^"]+)"/);
    return { method: m[1].toUpperCase().replace("GET", "GET"), path: m[2] };
  }).filter((e) => {
    const k = `${e.method} ${e.path}`;
    if (seen.has(k)) return false;
    seen.add(k);
    return true;
  });
};

const fe = ["b-app/src/api/endpoints.ts", "c-app/src/api/endpoints.ts"]
  .map((f) => readFileSync(join(ROOT, f), "utf8")).join("\n");

/** 有没有前端调用方。静态前缀匹配 —— **假阳性会有，所以这是上界**。 */
const hasConsumer = (p) => {
  const stat = p.split("{")[0].replace(/\/$/, "");
  return stat.length > 4 && fe.includes(stat);
};

const rows = scan().map((e) => {
  const subject = subjectOf(e.path);
  const rel = relationOf(e.method, e.path);
  return {
    ...e,
    portal: e.path.startsWith("/biz") ? "biz" : "mp",
    subject,
    rw: e.method === "GET" ? "R" : "W",
    consumer: hasConsumer(e.path),
    external: EXTERNAL.some((re) => re.test(e.path)),
    ...rel,
  };
});

// ── 统计口径（零售基线 §7 定死，P3 之后不许改）──────────────────────────
const excluded = (r) => !r.consumer || r.subject === "group" || r.external;
const inDenom = rows.filter((r) => !excluded(r));
const pct = (n) => `${((n / inDenom.length) * 100).toFixed(1)}%`;

const tally = (key) => {
  const c = {};
  for (const r of inDenom) c[r[key]] = (c[r[key]] || 0) + 1;
  return c;
};
const food = tally("food"), beauty = tally("beauty");

const bySubject = {};
for (const r of inDenom) {
  const s = (bySubject[r.subject] ??= { n: 0, food: {}, beauty: {} });
  s.n++;
  s.food[r.food] = (s.food[r.food] || 0) + 1;
  s.beauty[r.beauty] = (s.beauty[r.beauty] || 0) + 1;
};

const fmt = (o) => ["S", "P", "V", "X", "N", "?"].filter((k) => o[k]).map((k) => `${k}${o[k]}`).join(" ");

const md = [];
md.push("# 行业化端点盘点（生成物，勿手改）", "");
md.push("> 由 `npm run gen:industry-inventory` 生成。关系码的登记表在 `scripts/industry-endpoint-map.mjs`，");
md.push("> 判据来自 [TDD-餐饮包](../design/TDD-餐饮包-场景与工作流.md) 与 [TDD-美业包](../design/TDD-美业包-场景与工作流.md)。", "");
md.push("## 1. 口径", "");
md.push("| | 数 |", "|---|---:|");
md.push(`| 端点总数（/biz + /mp） | ${rows.length} |`);
md.push(`| 有前端调用方（上界） | ${rows.filter((r) => r.consumer).length} |`);
md.push(`| 排除：无消费方 | ${rows.filter((r) => !r.consumer).length} |`);
md.push(`| 排除：社区团购（平台形态） | ${rows.filter((r) => r.subject === "group" && r.consumer).length} |`);
md.push(`| 排除：外部回调（否决项） | ${rows.filter((r) => r.external).length} |`);
md.push(`| **有效分母** | **${inDenom.length}** |`);
md.push(`| 读 / 写 | ${inDenom.filter((r) => r.rw === "R").length} / ${inDenom.filter((r) => r.rw === "W").length} |`, "");
md.push("## 2. 关系码分布", "");
md.push("| 码 | 含义 | 餐饮 | 占比 | 美业 | 占比 |", "|---|---|---:|---:|---:|---:|");
for (const k of ["S", "P", "V", "X", "N", "?"]) {
  if (!food[k] && !beauty[k]) continue;
  md.push(`| ${k} | ${CODES[k] ?? "**未登记**"} | ${food[k] ?? 0} | ${pct(food[k] ?? 0)} | ${beauty[k] ?? 0} | ${pct(beauty[k] ?? 0)} |`);
}
md.push("", "## 3. 按主语", "");
md.push("| subject | 有效数 | 餐饮 | 美业 |", "|---|---:|---|---|");
for (const [s, v] of Object.entries(bySubject).sort((a, b) => b[1].n - a[1].n)) {
  md.push(`| \`${s}\` | ${v.n} | ${fmt(v.food)} | ${fmt(v.beauty)} |`);
}
md.push("", "## 4. 结构差异（X）逐条", "");
md.push("| 端点 | 餐饮 | 美业 | 理由 |", "|---|---|---|---|");
for (const r of inDenom.filter((r) => r.food === "X" || r.beauty === "X")) {
  md.push(`| \`${r.method} ${r.path}\` | ${r.food} | ${r.beauty} | ${r.why ?? ""} |`);
}
const missing = rows.filter((r) => r.food === "?");
md.push("", "## 5. 未登记（必须补规则）", "");
md.push(missing.length ? missing.map((r) => `- \`${r.method} ${r.path}\``).join("\n") : "无。", "");
md.push("## 6. 排除清单：无消费方端点", "");
md.push(rows.filter((r) => !r.consumer).map((r) => `- \`${r.method} ${r.path}\``).join("\n"), "");

writeFileSync(OUT, md.join("\n"));
console.log(`✅ ${OUT}`);
console.log(`   分母 ${inDenom.length}；餐饮 ${JSON.stringify(food)}；美业 ${JSON.stringify(beauty)}`);
if (missing.length) {
  console.error(`❌ ${missing.length} 条端点没有关系码规则 —— 补 scripts/industry-endpoint-map.mjs`);
  process.exit(1);
}
