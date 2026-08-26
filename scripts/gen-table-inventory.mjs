// 数据库表清单。**从迁移生成，不手写。**
//
// 它替掉的那一份手工清单停在「V1 基准 · 58 表」（2026-08-09），
// 而当天真实是 153 张 —— 落后 95 张，而读的人无从知道。
// 手工清单的问题从来不是「写的时候不认真」，是**它没有变短的机制**：
// 加表的人不会想起来去改一份别的文档。
//
// 真源与 ER 图共用 `scripts/lib/ddl.mjs`（重放 db/migration/V*.sql）——
// 两份产物同源，所以不会互相矛盾。
//
// 用法：node scripts/gen-table-inventory.mjs
import { writeFileSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { readSchema } from "./lib/ddl.mjs";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const OUT = join(ROOT, "docs/technical/reference/数据库表清单.md");

/**
 * 前缀 → 域。
 *
 * ⚠️ **不要直接照抄 `gen-erd.mjs` 的那份** —— 我第一版抄了，结果 17 张表落进「未归域」：
 * 那份里写的还是 `msg_` / `rsk_`，而库里早已是 `notify_` / `risk_`（重命名迁移过）。
 * 「未归域」这一档正是为了让这种漂移**看得见**，所以它有值时要去查，不要去调分法。
 */
const DOMAINS = [
  ["usr", "消费者账号"], ["mch", "商家主体与门店"], ["cmt", "社区与自提点"],
  ["prd", "商品与类目"], ["trd", "购物车"], ["ord", "交易"], ["ful", "履约"],
  ["mkt", "营销与团购"], ["pmt", "券与活动"], ["pts", "积分"], ["stl", "结算"],
  ["mbr", "会员"], ["notify", "消息与触达"], ["cnt", "内容"], ["sys", "平台配置"],
  ["iam", "权限与账号"], ["risk", "风控"], ["rvw", "评价"], ["geo", "地理"],
  ["opr", "运营"],
];

const tables = readSchema(ROOT);
const byDomain = new Map(DOMAINS.map(([p, n]) => [p, { name: n, rows: [] }]));
const other = [];

for (const [name, def] of [...tables].sort((a, b) => a[0].localeCompare(b[0]))) {
  const prefix = name.split("_")[0];
  const bucket = byDomain.get(prefix);
  const row = { name, def };
  if (bucket) bucket.rows.push(row);
  else other.push(row);
}

/** 业务键：一张表「按什么找」。取第一个非 id 的唯一键，找不到就留空 */
function bizKey(def) {
  for (const u of def.uniques ?? []) {
    const cols = (u.cols ?? u).filter?.((c) => c !== "id" && c !== "tenant_no") ?? [];
    if (cols.length) return cols.join(" + ");
  }
  return "";
}

const lines = [];
lines.push(`# 数据库表清单（${tables.size} 张）`);
lines.push("");
// 抬头的措辞要能被 doc-standard 那道守卫认出来：它找的是「由 `<脚本>`」这个句式。
// 少一个「由」字就会被报成「标了勿手改却没有生成脚本」—— 而脚本一直好好地在那儿。
lines.push("> **自动生成，请勿手改** —— 由 `node scripts/gen-table-inventory.mjs` 生成。");
lines.push("> 真源是 `backend/shop-app/src/main/resources/db/migration/V*.sql`，");
lines.push("> 与 [数据库-ER图](./数据库-ER图.md) 共用同一个解析器（`scripts/lib/ddl.mjs`），所以两份不会互相矛盾。");
lines.push(">");
lines.push("> 它替掉的手工清单停在「V1 基准 · 58 表」，而当时真实已有 153 张 —— **落后 95 张**。");
lines.push("> 手工清单的问题不是写的时候不认真，是它**没有变短的机制**：");
lines.push("> 加表的人不会想起来去改一份别的文档。`pre-push` 现在盯着这一份。");
lines.push("");

for (const [prefix, { name, rows }] of byDomain) {
  if (!rows.length) continue;
  lines.push(`## ${name} · \`${prefix}_\`（${rows.length}）`);
  lines.push("");
  lines.push("| 表 | 说明 | 列 | 业务键 |");
  lines.push("|---|---|---|---|");
  for (const { name: t, def } of rows) {
    lines.push(`| \`${t}\` | ${def.comment || "—"} | ${def.cols.length} | ${bizKey(def) ? `\`${bizKey(def)}\`` : "—"} |`);
  }
  lines.push("");
}

if (other.length) {
  lines.push(`## 未归域（${other.length}）`);
  lines.push("");
  lines.push("> 前缀不在 DOMAINS 里。**要么归域、要么改名** —— 一张没有域的表，");
  lines.push("> 谁改它、谁为它的口径负责都是不清楚的。");
  lines.push("");
  lines.push("| 表 | 说明 | 列 |");
  lines.push("|---|---|---|");
  for (const { name: t, def } of other) {
    lines.push(`| \`${t}\` | ${def.comment || "—"} | ${def.cols.length} |`);
  }
  lines.push("");
}

writeFileSync(OUT, lines.join("\n"));
console.log(`✅ ${OUT}`);
console.log(`   ${tables.size} 张表 · ${byDomain.size} 个域 · 未归域 ${other.length}`);
