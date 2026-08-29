import { describe, it, expect } from "vitest";
import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";

/*
 * 进销存那份《数据库表结构》里的**自称表数**必须等于真实表数。
 *
 * 2026-08-29 的实况：迁移里 19 张、线上库 19 张、文档正文里 19 张**一张不差**，
 * 而三处标题写的是「17 张表」。加表的人更新了清单，没更新那个数。
 *
 * 为什么值得一道闸：**自称数字是文档里最容易被信、又最不容易被核的一种断言**。
 * 清单长这样的时候没人会去数，而「17」会被直接引用到下一份文档、下一次汇报里。
 * 这份清单本身就是这么坏的 —— 它的第四节还专门列过一次「文档与实际有偏差」。
 *
 * 判据取**迁移文件**而不是线上库：闸门要能离线跑，且库里可能有人手工建的表。
 * 迁移是这个库唯一的建表入口（独立 DataSource + 独立 Flyway）。
 */
const ROOT = new URL("../../..", import.meta.url).pathname;
const DOC = join(ROOT, "docs/technical/design/进销存-数据库表结构.md");
const MIG = join(ROOT, "backend/shop-inventory/src/main/resources/db/inventory");

function tablesInMigrations(): Set<string> {
  const out = new Set<string>();
  for (const f of readdirSync(MIG).filter((n) => n.endsWith(".sql"))) {
    const sql = readFileSync(join(MIG, f), "utf8").replace(/--[^\n]*/g, "");
    for (const m of sql.matchAll(/CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?`?(inv_[a-z_]+)/gi)) {
      out.add(m[1]!.toLowerCase());
    }
    // 建完又删掉的不算（目前没有，但漏掉这条会让「只增不减」变成默认假设）
    for (const m of sql.matchAll(/DROP\s+TABLE\s+(?:IF\s+EXISTS\s+)?`?(inv_[a-z_]+)/gi)) {
      out.delete(m[1]!.toLowerCase());
    }
  }
  return out;
}

describe("进销存文档 × 迁移", () => {
  it("★★ 迁移里至少有十几张表 —— 抽不到就是正则失效，别让它空跑成绿", () => {
    expect(tablesInMigrations().size).toBeGreaterThan(12);
  });

  it("★★★ 《数据库表结构》里写的「N 张表」必须等于迁移里真实的张数", () => {
    const real = tablesInMigrations().size;
    const doc = readFileSync(DOC, "utf8");
    const claims = [...doc.matchAll(/(\d+)\s*张表/g)].map((m) => Number(m[1]));
    expect(claims.length, "文档里一处「N 张表」都没有？那这道闸没在看该看的东西").toBeGreaterThan(0);
    const wrong = [...new Set(claims)].filter((n) => n !== real);
    expect(
      wrong,
      `《进销存-数据库表结构.md》自称的表数与迁移对不上。迁移里是 ${real} 张，` +
        `文档里出现了 ${wrong.join(" / ")}。\n  ` +
        `加表时清单和这个数要一起改 —— 只改清单不改数，读的人信的是那个数。`,
    ).toEqual([]);
  });

  it("★★★ 文档正文里逐张列出的表 = 迁移里的表（多一张少一张都算）", () => {
    const real = tablesInMigrations();
    const doc = readFileSync(DOC, "utf8");
    const listed = new Set([...doc.matchAll(/\binv_[a-z_]+/g)].map((m) => m[0]));
    const missing = [...real].filter((t) => !listed.has(t)).sort();
    const extra = [...listed].filter((t) => !real.has(t)).sort();
    expect({ missing, extra }).toEqual({ missing: [], extra: [] });
  });
});
