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

// 长的写在前面：`INT` 排在 `INTEGER` 前会先匹配掉前三个字母，类型就记成了 int。
// **少一种类型 = 那一列被静默丢掉**（不是报错，是解析器眼里它不存在）——
// `geo_poi_cache.payload` 是 MEDIUMTEXT，于是实体对齐守卫报「实体多出 payload」，
// 而库里明明有这一列。加类型时顺手想一想：漏掉的那种会以「多出一个字段」的面目出现。
const COL_TYPES =
  "BIGINT|MEDIUMINT|SMALLINT|TINYINT|INTEGER|INT|VARCHAR|CHAR|DATETIME|TIMESTAMP|DATE|TIME"
  + "|MEDIUMTEXT|LONGTEXT|TINYTEXT|TEXT|JSON|DECIMAL|NUMERIC|DOUBLE|FLOAT"
  + "|MEDIUMBLOB|LONGBLOB|BLOB";
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
    // 收尾只认「行首的 `)` 一直到 `;`」——**表选项一律不作要求**。
    // 此前写死了 `\)\s*ENGINE`，于是合法的 `) COMMENT='…';`（不写 ENGINE，
    // MySQL 默认就是 InnoDB）整张表匹配不上，而且会一路吃到下一个文件，
    // 把别人的列算到它头上。V27 那三张表就是这么消失的。
    // 建表的收尾写法不该由解析器来规定 —— 它的职责是读，不是立规矩。
    //
    // 表选项**允许跨行，但有上界**（`[^;]{0,600}`）。
    //
    // 上界到底防什么，量过之后的准确说法是：**它防的是畸形/被截断的迁移文件**
    // （尾巴上再也没有 `;` 的那种）。构造「很多个 `\n)` 重试点 + 全文无分号」：
    //
    //     重试点数        500      1000     2000     4000
    //     无界           2.3ms    8.7ms   34.6ms  207.2ms   ← 翻倍即四倍，是 O(n²)
    //     {0,600}        0.9ms    1.5ms    3.1ms    8.7ms   ← 线性
    //
    // 退化的不是负向字符组自己（它一遍扫到 `;` 就停），是它与前面那个**惰性 body**
    // （`[\s\S]*?`）的组合：收尾匹配失败时 body 向后扩一格再试，每扩一格重扫一遍尾巴。
    //
    // 但只要文件尾部还有一个 `;`（也就是任何正常的 SQL），结论反过来 ——
    // 无界几乎不失败所以是 0.1ms，有界反而要在每个 `)` 点先扫满上界再回退（6ms）。
    // 三者都是毫秒级，所以这个取舍不是性能问题，是**畸形输入下的兜底**。
    //
    // 600 这个数是量出来的：全仓真实建表收尾最长 157（`) ENGINE=… COLLATE=… COMMENT='…'`），
    // 90 分位 122、中位数 93。300 的余量对中文 COMMENT 太薄。
    //
    // **超界的后果是静默吞表**，这是不要随手调小它的证据：把上界收到 {0,100}
    // （低于真实最长 157），解析器不报任何错，只解析出 **104 张而不是 145 张** ——
    // 41 张凭空消失，列被算到别的表头上。唯一会喊出来的是
    // `tests/ddl-parsable.test.ts`（「声明过的表解析器都要读得到」），
    // 那是这条上界的网。**那张网红着的时候不要动这个数**——
    // 它红过整整一段时间（6 张 msg_* 的噪音），期间任何真漏读都混在里面看不出来。
    //
    // 以上三组数据来自 2026-08-25 的一次三人复核。原来两种说法
    // （「无界会 O(n²)」与「无界不会 O(n²)」）都不够准，差别在后面还有没有分号。
    //
    // 完全不许跨行（`[^;\n]*`）也有代价，而且这个代价已经付过了：
    // V152 里两处写成
    //     ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci
    //         COMMENT='…';
    // 于是那两张表**整张匹配不上**，解析器一路吃到下一张单行收尾的表，
    // 把中间所有列（连同三条 INSERT 语句里的词）都算到 `sys_media_asset` 头上。
    // 症状是实体对齐守卫报「sys_media_asset 缺 17 列，其中三列叫 INSERT」——
    // 一条看不懂的报错，于是那条守卫就一直红着，而红着的守卫等于没有守卫。
    //
    // 加上界是第三条路：允许跨行，同时给畸形输入留了兜底。
    // **不能改 V152 本身**：它早就在生产上应用过，改文件会改掉 Flyway 校验和，
    // 线上重启会以 checksum mismatch 起不来（2026-08 已经因此停过约 4 分钟）。
    /CREATE TABLE(?: IF NOT EXISTS)? (\w+)\s*\(([\s\S]*?)\n\)([^;]{0,600});/g,
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
  // old→new，供列级 ALTER 阶段回溯：一条 `ADD COLUMN` 若发生在改名**之前**的版本、
  // 但本阶段（改名先于列 ALTER 重放）已经把旧名删了，列会静默丢。记下映射，列阶段兜住。
  const renamed = new Map();
  for (const m of sql.matchAll(/ALTER TABLE\s+(\w+)\s+RENAME TO\s+(\w+)/gi)) {
    const [, from, to] = m;
    const t = tables.get(from);
    if (!t) continue;
    t.name = to;
    tables.delete(from);
    tables.set(to, t);
    renamed.set(from, to);
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
  // 一条 ALTER 里可以有多个子句（`ADD COLUMN a …, ADD COLUMN b …`），
  // 而下面那条正则每条语句只认**第一个** —— 其余的会被 `([^;]*)` 当成尾巴吃掉。
  // 症状是「实体多出 communityNo、pickupNo」这类漂移报告，
  // 而那几列明明写在同一条 ALTER 里（V60 就是这么被报出来的）。
  // 先把多子句拆成多条单子句语句，正则那边就不用动。
  const flatSql = sql.replace(/ALTER TABLE\s+(\w+)\s+([\s\S]*?);/gi, (full, table, body) => {
    const parts = splitTopLevel(body);
    return parts.length <= 1 ? full
      : parts.map((c) => `ALTER TABLE ${table} ${c.trim()};`).join("\n");
  });

  for (const m of flatSql.matchAll(
    /ALTER TABLE\s+(\w+)\s+(ADD COLUMN|RENAME COLUMN|DROP COLUMN|MODIFY COLUMN)\s+(?:IF\s+(?:NOT\s+)?EXISTS\s+)?(\w+)(?:\s+([\w()]+))?([^;]*)/gi,
  )) {
    const [, table, opRaw, col, rest, tail] = m;
    // 直接命中优先；命中不到再看它是不是被改名了（列 ALTER 版本早于改名的情况）
    const t = tables.get(table) ?? tables.get(renamed.get(table));
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

/**
 * 按**顶层逗号**切分 ALTER 的多个子句。
 *
 * <p>括号内（`UNIQUE KEY uk_x (a, b)`）与单引号内（`COMMENT '甲, 乙'`）的逗号
 * 不是分隔符。用正则前瞻切会在注释里带逗号时切错，而切错的产物是一条
 * 语法上说得通、语义上错的列定义 —— 那种错要到建表时才炸，且报错指向别处。
 *
 * <p>与 `backend/scripts/gen-test-schema.py` 的 `_split_actions` 同一套判据。
 * 两个解析器分叉过一次（多子句 ALTER：python 认得、JS 不认），
 * 症状是漂移报告说实体「多出」几列，而那几列就写在同一条 ALTER 里。
 */
function splitTopLevel(body) {
  const parts = [];
  let buf = "";
  let depth = 0;
  let quoted = false;
  for (const ch of body) {
    if (quoted) {
      buf += ch;
      if (ch === "'") quoted = false;
      continue;
    }
    if (ch === "'") quoted = true;
    else if (ch === "(") depth++;
    else if (ch === ")") depth--;
    if (ch === "," && depth === 0) {
      parts.push(buf.trim());
      buf = "";
      continue;
    }
    buf += ch;
  }
  if (buf.trim()) parts.push(buf.trim());
  return parts;
}
