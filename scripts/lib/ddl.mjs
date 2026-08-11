// Flyway 迁移的唯一 DDL 解析器。
//
// ═══ 为什么必须只有一份 ═══
//
// 这套解析逻辑曾经在三个脚本里各写一遍（gen-erd / gen-model-align / gen-test-schema.py），
// 于是同一类 bug 中了三次，每次都是**静默出错**——没有报错，只是产物悄悄不对：
//
//   ① 字典序排文件：V15 排在 V2 前面 → ALTER 在 CREATE TABLE 之前重放，整批被丢弃
//   ② 不认 DROP TABLE：已删的 pts_merchant_quota 在 ER 图上又画了一版
//   ③ 不认 MODIFY COLUMN：改列注释是修正语义的主要手段，不解析就永远显示建表时的旧说法
//
// 每次只在发现问题的那一个脚本里修，另外两个继续错。**修一处等于漏两处**，
// 所以这里合成一份：任何一个消费方发现的解析缺陷，其他消费方自动一起好。
//
// python 侧（gen-test-schema.py）语言不同没法共用，改由
// packages/shared/tests/schema-lineage.test.ts 断言两边解析出的表/列集合一致。

import { readFileSync, readdirSync, existsSync } from "node:fs";
import { join } from "node:path";

export const MIGRATION_DIR = "backend/shop-app/src/main/resources/db/migration";

/** 每张表都有、不参与任何业务关系的审计列 */
export const AUDIT = new Set([
  "id", "tenant_no", "created_at", "created_by", "updated_at", "updated_by", "version", "deleted",
]);

const COL_TYPES =
  "BIGINT|VARCHAR|INT|TINYINT|SMALLINT|DATETIME|TIMESTAMP|TEXT|JSON|DECIMAL|CHAR|DOUBLE";
const NOT_A_COLUMN = /^(KEY|UNIQUE|PRIMARY|INDEX|CONSTRAINT|FULLTEXT)$/i;

/** 把迁移按**版本号数字**拼成一条时间线。字典序会把 V15 排在 V2 前面。 */
function concatMigrations(dir) {
  return readdirSync(dir)
    .filter((f) => f.endsWith(".sql"))
    .sort((a, b) => (parseInt(a.slice(1), 10) || 0) - (parseInt(b.slice(1), 10) || 0))
    .map((f) => readFileSync(join(dir, f), "utf8"))
    .join("\n");
}

const commentOf = (s) => s?.match(/COMMENT\s+'([^']*)'/)?.[1] ?? "";

/**
 * 重放全部迁移，得到**当前**的表结构。
 *
 * 返回 `Map<表名, { cols, uniques, indexes, comment }>`，
 * `cols` 是 `{ name, type, nullable, comment }` 数组，顺序即建表顺序（ADD 的追加在后）。
 *
 * @param {string} root 仓库根目录
 */
export function readSchema(root) {
  const tables = new Map();
  const dir = join(root, MIGRATION_DIR);
  if (!existsSync(dir)) return tables;
  const sql = concatMigrations(dir);

  for (const m of sql.matchAll(
    /CREATE TABLE(?: IF NOT EXISTS)? (\w+)\s*\(([\s\S]*?)\n\)\s*ENGINE([^;]*);/g,
  )) {
    const [, name, body, tail] = m;
    const cols = [];
    const uniques = new Set();
    const indexes = [];
    // 索引名 → 列规格。**DROP INDEX 是按名字删的**，不记名字就删不掉
    const indexNames = new Map();
    for (const raw of body.split("\n")) {
      const line = raw.trim();
      const u = line.match(/^UNIQUE KEY (\w+) \(([^)]*)\)/i);
      if (u) {
        u[2].split(",").forEach((c) => uniques.add(c.trim()));
        indexNames.set(u[1], null); // 唯一键：记名字以便被 DROP，但不进 indexes
        continue;
      }
      const k = line.match(/^KEY (\w+) \(([^)]*)\)/i);
      if (k) {
        const spec = k[2].replace(/\s+/g, " ");
        indexes.push(spec);
        indexNames.set(k[1], spec);
        continue;
      }
      const c = line.match(new RegExp(`^(\\w+)\\s+(${COL_TYPES})`, "i"));
      if (!c || NOT_A_COLUMN.test(c[1])) continue;
      cols.push({
        name: c[1],
        type: c[2].toLowerCase(),
        nullable: !/NOT NULL/i.test(line),
        comment: commentOf(line),
      });
    }
    tables.set(name, {
      cols,
      uniques,
      indexes,
      indexNames,
      comment: tail.match(/COMMENT\s*=?\s*'([^']*)'/)?.[1] ?? "",
    });
  }

  // 删表要跟着走，否则已废除的表会一直出现在产物里
  for (const m of sql.matchAll(/DROP TABLE(?: IF EXISTS)?\s+(\w+)/gi)) tables.delete(m[1]);

  // 表改名要先于列级 ALTER 重放：同一份迁移常常是「先改名，再往新名上加列」。
  // 不认它的话，新表名在 tables 里不存在，后续 ALTER 被 `if (!t) continue` 静默丢掉，
  // 于是那张表的列集合停在改名前 —— 报出来的是「实体多出一堆字段」，
  // 而真正的原因是这个解析器没跟上改名。
  for (const m of sql.matchAll(/ALTER TABLE\s+(\w+)\s+RENAME TO\s+(\w+)/gi)) {
    const [, from, to] = m;
    const t = tables.get(from);
    if (!t) continue;
    t.name = to;
    tables.delete(from);
    tables.set(to, t);
  }

  // 表注释也会被改。不解析的话，图上和文档里显示的永远是**建表当天**的说法 ——
  // `pts_merchant_ledger` 就一直挂着「额度 + 金额双口径」，而模型早已不是双口径了。
  for (const m of sql.matchAll(/ALTER TABLE\s+(\w+)\s+COMMENT\s*=?\s*'([^']*)'/gi)) {
    const t = tables.get(m[1]);
    if (t) t.comment = m[2];
  }

  // 建表之后独立加/删的索引。**不解析的话产物只反映建表当天的索引** ——
  // 本仓库有 19 条独立索引 DDL 一条都没被看见，而删掉的索引还留在文档里：
  // `pts_user_ledger` 的 `(user_no, biz_type, expire_at)` 在 V30 随 expire_at 一起删了，
  // 文档上却挂到了现在，读的人会照着一个不存在的索引去写查询。
  for (const m of sql.matchAll(
    /CREATE(?:\s+UNIQUE)?\s+INDEX\s+(\w+)\s+ON\s+(\w+)\s*\(([^)]*)\)/gi,
  )) {
    const [, name, table, cols] = m;
    const t = tables.get(table);
    if (!t) continue;
    const spec = cols.replace(/\s+/g, " ").trim();
    if (/UNIQUE/i.test(m[0])) cols.split(",").forEach((c) => t.uniques.add(c.trim()));
    else if (!t.indexes.includes(spec)) t.indexes.push(spec);
    t.indexNames.set(name, spec);
  }
  for (const m of sql.matchAll(/DROP INDEX\s+(\w+)\s+ON\s+(\w+)/gi)) {
    const [, name, table] = m;
    const t = tables.get(table);
    const spec = t?.indexNames?.get(name);
    if (t && spec) {
      t.indexes = t.indexes.filter((i) => i !== spec);
      t.indexNames.delete(name);
    }
  }

  // ALTER 必须重放：只看 CREATE TABLE 得到的是**已被后续迁移改过的旧结构**。
  // 真实踩到过：`ord_sub_order.pickup_code` 在 V6 改名成 `verify_code`，
  // 而报告照旧说「契约的 verifyCode 与库列 pickup_code 不一致」—— 早就一致了。
  // 反向更危险：漏看 ADD COLUMN 会把已有的列报成缺失，照着补一遍就是重复加列。
  //
  // `IF NOT EXISTS` / `IF EXISTS` 必须跳过：不跳的话 `(\w+)` 会把 **IF 当成列名**，
  // 于是解析出一列叫 `if`，而真正那一列不见了 —— SchemaDriftTest 报「列不一致」，
  // 差异里却看不出所以然（踩过一次）。
  // 幂等迁移不是可选风格：一次失败的迁移不该需要人手工进库才能重试（见 V18 的说明），
  // 所以解析器要认它，而不是逼人把 IF NOT EXISTS 去掉。
  for (const m of sql.matchAll(
    /ALTER TABLE\s+(\w+)\s+(ADD COLUMN|RENAME COLUMN|DROP COLUMN|MODIFY COLUMN)\s+(?:IF\s+(?:NOT\s+)?EXISTS\s+)?(\w+)(?:\s+([\w()]+))?([^;]*)/gi,
  )) {
    const [, table, opRaw, col, rest, tail] = m;
    const t = tables.get(table);
    if (!t) continue;
    switch (opRaw.toUpperCase()) {
      case "ADD COLUMN":
        if (!t.cols.some((c) => c.name === col)) {
          t.cols.push({
            name: col,
            type: (rest || "varchar").toLowerCase().replace(/\(.*/, ""),
            nullable: !/NOT NULL/i.test(tail ?? ""),
            comment: commentOf(tail),
          });
        }
        break;
      case "DROP COLUMN":
        t.cols = t.cols.filter((c) => c.name !== col);
        // 索引里也要摘掉。MySQL 会静默这么做，产物不跟着做的话，
        // 文档上就会出现一个引用了不存在列的索引，而读的人会照着它写查询
        t.indexes = t.indexes
          .map((i) => i.split(",").map((c) => c.trim()).filter((c) => c !== col).join(", "))
          .filter(Boolean);
        break;
      case "MODIFY COLUMN": {
        const hit = t.cols.find((c) => c.name === col);
        if (!hit) break;
        if (rest) hit.type = rest.toLowerCase().replace(/\(.*/, "");
        hit.nullable = !/NOT NULL/i.test(tail ?? "");
        // 只有真写了 COMMENT 才覆盖 —— MODIFY 常用来改类型而不重述注释
        const cm = commentOf(tail);
        if (cm) hit.comment = cm;
        break;
      }
      case "RENAME COLUMN": {
        // `RENAME COLUMN a TO b` —— rest 是 "TO"，新名在 tail 里
        const to = tail?.trim().split(/\s+/)[0];
        const hit = t.cols.find((c) => c.name === col);
        if (hit && to) hit.name = to;
        break;
      }
    }
  }
  return tables;
}

/** 只要列名的扁平视图 —— 给不关心类型/注释的消费方 */
export function readColumnNames(root) {
  const out = new Map();
  for (const [name, def] of readSchema(root)) {
    out.set(name, { cols: def.cols.map((c) => c.name), comment: def.comment });
  }
  return out;
}
