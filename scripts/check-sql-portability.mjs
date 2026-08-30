#!/usr/bin/env node
/**
 * SQL 可移植性闸门：**新写的 SQL 必须在 MariaDB 与 MySQL 8 上都能跑。**
 *
 * 立这道闸的背景：2026-08-28 拍板「将来一定会切到 MySQL」。而当时一审计，
 * 160 张表**全部**是 `utf8mb4_uca1400_ai_ci` —— 那是 MariaDB 11.4+ 独有的排序规则，
 * MySQL 上一张表都建不起来。再加上 12 个文件用了 MariaDB 独有的
 * `CREATE INDEX IF NOT EXISTS` / `ADD COLUMN IF NOT EXISTS`。
 *
 * ⚠️ **已应用的迁移是只读的**（改一个字符 Flyway checksum 就对不上，线上起不来）。
 * 所以这道闸**不是**用来修历史的，是用来**止血**的：历史全部进基线，只准变短。
 * 历史那部分怎么收尾见 `docs/technical/design/数据库可移植性-MariaDB到MySQL.md`。
 *
 * 判据两个方向都查：
 *   · MariaDB 独有 → 切 MySQL 那天会炸
 *   · MySQL 独有   → 今天就炸（我们现在跑的是 MariaDB）
 *
 * 用法：node scripts/check-sql-portability.mjs [--check]
 */
import { readFileSync, readdirSync, statSync, existsSync } from "node:fs";
import { join, relative } from "node:path";
import { fileURLToPath } from "node:url";
import { maskSqlNoise } from "./lib/sql-mask.mjs";

const ROOT = join(fileURLToPath(import.meta.url), "../..");
const BASELINE = join(ROOT, "backend/known-sql-dialect.txt");

/**
 * 每条规则要能**指出替代写法** —— 只说「不许用」的闸门，人会绕过去而不是改对。
 *
 * `re` 用 `g` 标志，因为一个文件里可能有多处，报第一处会让人以为只有一处。
 */
const RULES = [
  // ── MariaDB 独有：切 MySQL 那天会炸 ──
  {
    id: "uca1400-collation",
    re: /utf8mb4_uca1400[a-z0-9_]*/gi,
    side: "MariaDB",
    why: "MariaDB 11.4+ 独有的 UCA 14.0.0 排序规则，MySQL 没有这个名字",
    fix: "改用 utf8mb4_unicode_520_ci（MariaDB 与 MySQL 8 都有）",
  },
  {
    id: "create-index-if-not-exists",
    re: /CREATE\s+(?:UNIQUE\s+)?INDEX\s+IF\s+NOT\s+EXISTS/gi,
    side: "MariaDB",
    why: "MySQL 的 CREATE INDEX 不接受 IF NOT EXISTS",
    fix: "Flyway 迁移本来就只跑一次，直接去掉 IF NOT EXISTS",
  },
  {
    id: "drop-index-if-exists",
    re: /DROP\s+INDEX\s+IF\s+EXISTS/gi,
    side: "MariaDB",
    why: "MySQL 的 DROP INDEX 不接受 IF EXISTS",
    fix: "去掉 IF EXISTS；不确定索引在不在就先查 information_schema",
  },
  {
    id: "alter-column-if-exists",
    re: /(?:ADD|DROP|MODIFY|CHANGE)\s+COLUMN\s+IF\s+(?:NOT\s+)?EXISTS/gi,
    side: "MariaDB",
    why: "MySQL 的 ALTER TABLE 子句不接受 IF (NOT) EXISTS",
    fix: "去掉；迁移只跑一次，幂等靠 Flyway 的版本号而不是靠 SQL",
  },
  {
    id: "mariadb-returning",
    re: /\b(?:INSERT|REPLACE|DELETE)\b[\s\S]{0,400}?\bRETURNING\b/gi,
    side: "MariaDB",
    why: "MariaDB 的 INSERT/DELETE ... RETURNING，MySQL 没有",
    fix: "改成先写后读，或用 LAST_INSERT_ID()",
  },
  {
    id: "mariadb-sequence",
    re: /\b(?:CREATE\s+SEQUENCE|NEXTVAL\s*\(|PREVIOUS\s+VALUE\s+FOR)\b/gi,
    side: "MariaDB",
    why: "MariaDB 的序列对象，MySQL 没有",
    fix: "用 AUTO_INCREMENT，或沿用项目现有的 BizKey 发号",
  },
  {
    id: "mariadb-persistent-column",
    re: /\bAS\s*\([\s\S]{1,200}?\)\s*PERSISTENT\b/gi,
    side: "MariaDB",
    why: "生成列的 PERSISTENT 是 MariaDB 拼法",
    fix: "写成 STORED —— 两边都认",
  },

  // ── MySQL 独有：今天就炸（生产跑的是 MariaDB） ──
  {
    id: "mysql-member-of",
    re: /\bMEMBER\s+OF\s*\(/gi,
    side: "MySQL",
    why: "MySQL 8.0.17 的 MEMBER OF 运算符，MariaDB 12.2 实测没有",
    fix: "用 JSON_CONTAINS()；要走索引则改用倒排子表（见商品规格参数那份设计）",
  },
  {
    id: "mysql-multi-valued-index",
    re: /CAST\s*\([\s\S]{1,120}?\bAS\b[^)]{1,60}\bARRAY\s*\)/gi,
    side: "MySQL",
    why: "MySQL 的多值索引语法，MariaDB 实测报 ERROR 4161（MDEV-25848 目标 13.2）",
    fix: "用倒排子表，并把排序键一起冗余进去 —— 实测比多值索引还快",
  },
  {
    id: "mysql-0900-collation",
    re: /utf8mb4_0900[a-z0-9_]*/gi,
    side: "MySQL",
    why: "MySQL 8 独有的 UCA 9.0.0 排序规则，MariaDB 没有",
    fix: "改用 utf8mb4_unicode_520_ci",
  },
];

/** 扫 SQL 迁移，以及 Java 里手写的 SQL（@Select/@Update/... 与文本块） */
function targets() {
  const out = [];
  (function walk(d) {
    for (const f of readdirSync(d)) {
      const p = join(d, f);
      if (statSync(p).isDirectory()) {
        if (f === "target" || f === "node_modules" || f === ".git") continue;
        walk(p);
      } else if (f.endsWith(".sql") || f.endsWith(".java")) {
        out.push(p);
      }
    }
  })(join(ROOT, "backend"));
  return out;
}

/** 行号：报「哪一行」比报「哪个文件」有用得多 —— 大迁移动辄几百行 */
function lineOf(src, idx) {
  return src.slice(0, idx).split("\n").length;
}

const hits = [];
for (const p of targets()) {
  /*
   * **先把注释与字符串盖成空格**（等长，行号不变）。
   *
   * 不这么做的话，闸门会命中「注释里写出来的那个错误示例」——
   * 而那正是这个仓库最该鼓励的写法：把「为什么不能这么写」写在离现场最近的地方。
   * 2026-08-30 真实发生：有人改完排序规则仍然红，红在他解释这次改动的那段注释上。
   *
   * 这不是放宽 —— 被盖掉的每一段都不会被数据库执行。见 lib/sql-mask.mjs。
   */
  const src = maskSqlNoise(readFileSync(p, "utf8"));
  for (const r of RULES) {
    r.re.lastIndex = 0;
    let m;
    while ((m = r.re.exec(src)) !== null) {
      hits.push({
        file: relative(ROOT, p),
        line: lineOf(src, m.index),
        id: r.id,
        side: r.side,
        why: r.why,
        fix: r.fix,
        text: m[0].replace(/\s+/g, " ").slice(0, 60),
      });
      if (m[0].length === 0) r.re.lastIndex++;   // 防零宽匹配死循环
    }
  }
}

const known = existsSync(BASELINE)
  ? new Set(readFileSync(BASELINE, "utf8").split("\n").map((l) => l.trim())
      .filter((l) => l && !l.startsWith("#")))
  : new Set();

/**
 * 基线的键是 **文件 + 规则**，不带行号。
 * 带行号的话，同一个文件里插一行注释就会让所有条目「变新」——
 * 那种闸门第二天就会被人加 `--no-verify` 绕过去。
 */
const keyOf = (h) => `${h.file}\t${h.id}`;
const fresh = hits.filter((h) => !known.has(keyOf(h)));
const freshKeys = new Set(fresh.map(keyOf));
const stale = [...known].filter((k) => !hits.some((h) => keyOf(h) === k));

const byFile = new Map();
for (const h of hits) {
  if (!byFile.has(h.file)) byFile.set(h.file, []);
  byFile.get(h.file).push(h);
}

console.log(`SQL 方言扫描｜命中 ${hits.length} 处 / ${byFile.size} 个文件（已知欠账 ${known.size} 条）`);
if (fresh.length) {
  console.log(`\n★ 新增 ${fresh.length} 处：`);
  for (const h of fresh) {
    console.log(`   ${h.file}:${h.line}`);
    console.log(`     [${h.side} 独有] ${h.text}`);
    console.log(`     为什么不行：${h.why}`);
    console.log(`     改成：${h.fix}`);
  }
}
if (stale.length) {
  console.log(`\n✅ 基线里这 ${stale.length} 条已经不再命中，删掉：`);
  for (const k of stale) console.log(`      ${k.replace("\t", "  ")}`);
}

const check = process.argv.includes("--check");
if (check && fresh.length) {
  console.error(`\n✗ ${freshKeys.size} 个文件用了只有一边认的 SQL。`);
  console.error("  我们已经拍板将来切 MySQL，所以新写的 SQL 两边都要能跑。");
  console.error("  确实非用不可（比如为了修历史）就登记进 backend/known-sql-dialect.txt 并写明为什么。");
  process.exit(1);
}
if (check && stale.length) {
  console.error(`\n✗ 基线里有 ${stale.length} 条已经不再命中了，删掉它。`);
  console.error("  留着的话那个文件将来把方言写回去也不会有人发现 —— 名单上的是免检的。");
  process.exit(1);
}
if (!fresh.length && !stale.length) console.log("\n✓ 没有新增的方言依赖");
