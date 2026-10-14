// 进销存的表只有进销存自己能写。
//
// 为什么需要一条守卫，而不是「大家注意一下」：`inv_*` 在**另一个库**里。
// 别的模块写它，不是「耦合了」这么轻——它要么根本连不上（打到平台库，报「表不存在」），
// 要么连上了却绕过了领域的全部不变式：available >= 0、变动必有流水、流水不可改。
// 三条里任何一条被绕过去，症状都不是报错，是**账悄悄地不对了**。
//
// 这条守卫是「能不能拆库」的绿灯（见 TDD-进销存模块化与独立部署 §3.3）：
// 它绿了一整年，拆库才是一次配置改动；它不存在，拆库永远是一次考古。
//
// **读是允许的**：对差、报表、运营台账都要读两边。挡的只有写。
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const ROOT = join(import.meta.dirname, "../../..");
const BACKEND = join(ROOT, "backend");
/** 唯一允许写 inv_* 的模块 */
const OWNER = join(BACKEND, "shop-inventory");

/** 写方法。`select*` 一律不算——读两边是防腐层的正常工作 */
const WRITE_CALLS = /\.(insert|insertBatch|updateById|update|deleteById|delete|applyDelta|hold|unhold)\s*\(/;

function javaFiles(dir: string, out: string[] = []): string[] {
  for (const e of readdirSync(dir)) {
    if (e === "target" || e === "node_modules") continue;
    const p = join(dir, e);
    if (statSync(p).isDirectory()) javaFiles(p, out);
    else if (e.endsWith(".java")) out.push(p);
  }
  return out;
}

const rel = (p: string) => p.slice(ROOT.length + 1);
const outsiders = javaFiles(BACKEND).filter((f) => !f.startsWith(OWNER));

describe("进销存表的写权", () => {
  it("有东西可查", () => {
    expect(outsiders.length).toBeGreaterThan(100);
  });

  /*
   * 第一道：谁拿着 inv_* 的 Mapper 还调了写方法。
   *
   * **绑到变量上，不是绑到文件上。** 原先的判据是「文件 import 了
   * inventory.mapper，就把文件里所有写调用都算上」。作者写明了那是有意的粗，
   * 理由是「价值在于它存在且会红」—— 而它后来就一直红着：
   * `InventoryFlowTest` 既要读进销存的 mapper，又要 `goodsMapper.insert(...)`
   * 造商品主数据，八行 `prd_*` 的写被算成了 `inv_*` 的越权。
   *
   * 恒红的闸门不警告任何人，它只是一层噪声掩体 —— 真有人去写 `inv_*` 的那天，
   * 报错里会多出一行，混在本来就有的八行中间。
   *
   * 精确不需要 Java 解析器：从 import 里取出 inventory 的 mapper 类型名，
   * 再找出声明成那些类型的变量名，只盯这些变量上的写调用。
   */
  it("★★★ shop-inventory 之外不许调 inv_* 的写方法 —— 绕过去的话账会悄悄不对", () => {
    const bad: string[] = [];
    for (const f of outsiders) {
      const src = readFileSync(f, "utf8");
      if (!src.includes("ai.neargo.shop.inventory.mapper")) continue;

      // import ai.neargo.shop.inventory.mapper.InvItemMapper; → 类型名 InvItemMapper
      const types = [...src.matchAll(/import\s+ai\.neargo\.shop\.inventory\.mapper\.(\w+)\s*;/g)]
        .map((m) => m[1]!);
      // 内部类写法：`import ...mapper.InventoryMappers;` 之后是 `InventoryMappers.InvItemMapper x`
      const typeAlt = types.map((t) => `(?:\\w+\\.)?${t}`).join("|");
      if (!typeAlt) continue;

      // `private final InvItemMapper itemMapper;` / `InvItemMapper itemMapper = ...`
      const vars = new Set(
        [...src.matchAll(new RegExp(`(?:${typeAlt})\\s+(\\w+)\\s*[;=,)]`, "g"))].map((m) => m[1]!),
      );
      if (!vars.size) continue;
      const onVar = new RegExp(`\\b(${[...vars].join("|")})${WRITE_CALLS.source}`);

      for (const [i, line] of src.split("\n").entries()) {
        if (line.trim().startsWith("*") || line.trim().startsWith("//")) continue;
        if (onVar.test(line)) bad.push(`${rel(f)}:${i + 1}  ${line.trim()}`);
      }
    }
    expect(
      bad,
      "这些地方在 shop-inventory 之外写了进销存的表。\n" +
        "→ 改成调 inventory 的 Service（它们保证不变式），或者只读。\n" +
        "  绕过去的后果不是报错，是余额变了而流水没有——而对账要几个月后才发现：\n  " +
        bad.join("\n  "),
    ).toEqual([]);
  });

  /*
   * 第二道：绕开 Mapper、直接写裸 SQL。
   *
   * 第一道只看得见「用了 InventoryMappers」的文件；有人自己写一句
   * `@Update("UPDATE inv_stock_balance ...")` 就完全绕过去了。
   */
  it("★★★ shop-inventory 之外的 SQL 注解里不许出现 inv_ 表的写语句", () => {
    const bad: string[] = [];
    const sqlWrite = /(UPDATE|INSERT\s+INTO|DELETE\s+FROM)\s+inv_[a-z_]+/i;
    for (const f of outsiders) {
      const src = readFileSync(f, "utf8");
      if (!/inv_[a-z_]+/.test(src)) continue;
      for (const [i, line] of src.split("\n").entries()) {
        if (sqlWrite.test(line)) bad.push(`${rel(f)}:${i + 1}  ${line.trim()}`);
      }
    }
    expect(
      bad,
      "这些地方用裸 SQL 写了进销存的表：\n  " + bad.join("\n  "),
    ).toEqual([]);
  });

  /*
   * 第三道：实体也不许在外面被写。
   *
   * 拿到 InvStockBalance 实体、改字段、再交给别人保存——绕开前两道。
   * 只挡「实例化」：`new InvXxx()` 在 shop-inventory 之外没有正当理由，
   * 读出来的对象是 Mapper 造的，不是 new 出来的。
   */
  it("★★ shop-inventory 之外不许 new 进销存的实体", () => {
    const bad: string[] = [];
    for (const f of outsiders) {
      const src = readFileSync(f, "utf8");
      for (const [i, line] of src.split("\n").entries()) {
        if (/\bnew\s+Inv[A-Z][A-Za-z]*\s*\(/.test(line)) bad.push(`${rel(f)}:${i + 1}  ${line.trim()}`);
      }
    }
    expect(bad, "这些地方在外面构造了进销存的实体：\n  " + bad.join("\n  ")).toEqual([]);
  });
});
