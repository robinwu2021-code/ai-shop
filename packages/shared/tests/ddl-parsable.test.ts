// 每一张建表都必须被 DDL 解析器读到 —— 读不到的表，下游全部静默错位。
//
// 为什么需要它：解析器（scripts/lib/ddl.mjs）用一条正则切表，
// 它要求建表以 `) ENGINE=...;` 收尾。写成 `);` 的后果**不是报错**，
// 而是那张表整个匹配不上 —— 于是：
//
//   · 这张表在 entity-alignment / schema-lineage 里凭空消失
//   · 更坏的是，**下一个迁移文件的列会被算到它头上**
//
// 实测踩过一次（V25 券发放记录）：报的是
//   「mkt_coupon_issue 缺 8 列：qualNo、entityNo、qualType…」
// 而那 8 列属于资质表（V26），与券毫无关系 —— 因为 V25 那张表没被闭合，
// 正则一路吃到了下一个文件里。
// 排查的人先怀疑自己写错了实体，再怀疑迁移顺序，最后才可能想到是收尾符号。
// **一个把人引向完全错误方向的报错，比不报还费时间。**
//
// 这条守卫在源头拦：解析器读到的表，必须与文件里实际声明的表一一对应。
//
// 只查这一件事。一开始还写了「建表内部不许有 `--` 注释」，
// 理由是「两个解析器对它的处理不一致」—— **那是猜的，而且是错的**：
// prd_store_goods（V15）表内一直有 `--` 注释，schema-lineage 从没红过。
// 一条禁止无害写法的规则会让人绕路，而绕路的成本没有任何收益抵消它。
import { readdirSync, readFileSync, existsSync } from "node:fs";
import { join } from "node:path";
// @ts-expect-error -- 生成器是 .mjs，没有类型声明；它是 DDL 解析的唯一真源
import { readColumnNames } from "../../../scripts/lib/ddl.mjs";
import { describe, expect, it } from "vitest";

const ROOT = join(import.meta.dirname, "../../..");
const MIGRATION_DIR = join(ROOT, "backend/shop-app/src/main/resources/db/migration");

/** 文件里**声明过**的建表（不管解析器读不读得到） */
function declaredTables(): Map<string, string> {
  const out = new Map<string, string>();
  if (!existsSync(MIGRATION_DIR)) return out;
  for (const f of readdirSync(MIGRATION_DIR).filter((x) => x.endsWith(".sql"))) {
    const src = readFileSync(join(MIGRATION_DIR, f), "utf8");
    for (const m of src.matchAll(/CREATE TABLE(?: IF NOT EXISTS)?\s+(\w+)/gi)) {
      out.set(m[1]!, f);
    }
  }
  return out;
}

describe("建表 DDL 必须可解析", () => {
  const declared = declaredTables();

  it("解析没失效 —— 一张表都没扫到时不能静默通过", () => {
    if (!existsSync(MIGRATION_DIR)) return; // 只装前端的场景
    expect(declared.size, "一张建表都没扫到，迁移目录变了？").toBeGreaterThan(30);
  });

  it("★★★ 每张声明过的表，解析器都必须读得到", () => {
    if (!declared.size) return;
    const parsed = new Set<string>(
      [...(readColumnNames(ROOT) as Map<string, unknown>).keys()],
    );
    const missing = [...declared.entries()]
      .filter(([t]) => !parsed.has(t))
      .map(([t, f]) => `${t}（${f}）`)
      .sort();

    expect(
      missing,
      "这些表声明了，但 DDL 解析器读不到 —— **下游会静默错位**：\n" +
        "  它们在 entity-alignment / schema-lineage 里凭空消失，\n" +
        "  而且列会被算到别的表头上，报出与那张表毫无关系的字段名。\n" +
        "\n" +
        "  最常见的原因：某张建表以 `);` 收尾。解析器要求 `) ENGINE=InnoDB ...;`\n" +
        "\n" +
        "  ⚠️ **真凶多半不是这里报出来的那张表，而是它前面那一张**：\n" +
        "  没闭合的表会让正则一路吃到下一个文件的 `) ENGINE...;`，\n" +
        "  于是被吃掉收尾的那张（也就是这里报的）反而匹配不上了。\n" +
        "  按迁移版本号顺序，往**上**一个文件找收尾写错的那张。",
    ).toEqual([]);
  });

});
