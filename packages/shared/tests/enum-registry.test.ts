// 枚举登记表的两条守卫。
//
// G1 覆盖率：端上每一个具名枚举都必须在 enum-registry.ts 里有一条。
// G2 状态词：STATUS 型枚举的取值必须取自词典的 L1 状态词表，或已显式申报。
//
// ─────────────────────────────────────────────────────────────────────────────
// 为什么需要 G1
// ─────────────────────────────────────────────────────────────────────────────
// 此前唯一的枚举守卫是 glossary.test.ts 的 MUST_COVER —— 手工列的 8 个，
// 而端上共 121 个，覆盖率 6.6%。且那 8 个**全部是出过事之后补进来的**：
// 幸存者偏差，守住的永远是上一次的事故。
//
// G1 把「新增一个枚举」从零成本变成必须登记。它不判断好坏 —— 那是 verdict 字段的事 ——
// 它只保证**没有枚举能在雷达之外出现**。这是唯一能阻止 121 变成 150 的机制。
//
// ─────────────────────────────────────────────────────────────────────────────
// 为什么 L1 表放在 markdown 而不是代码里
// ─────────────────────────────────────────────────────────────────────────────
// 因为词典是规范，规范的修改要经过评审。放进代码就成了「顺手改一行」，
// 而这份表的价值恰恰来自它不容易被改。守卫读 markdown，代码服从它。
import { readFileSync, readdirSync, existsSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { ENUM_REGISTRY } from "../src/contract/enum-registry";

const ROOT = join(import.meta.dirname, "../../..");
const GLOSSARY = readFileSync(join(ROOT, "docs/requirements/项目词典.md"), "utf8");

function walk(dir: string, ext: string, out: string[] = []): string[] {
  if (!existsSync(dir)) return out;
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, e.name);
    if (e.isDirectory()) {
      if (!/node_modules|target|\.next|dist/.test(p)) walk(p, ext, out);
    } else if (p.endsWith(ext)) out.push(p);
  }
  return out;
}

/** 端上所有具名枚举：字面量联合类型 + 全大写常量对象 */
function declaredEnums(): { decl: string; values: string[] }[] {
  const out: { decl: string; values: string[] }[] = [];
  const scan = (files: string[], tag: string) => {
    for (const f of files) {
      /*
       * 先剥掉块注释再扫。
       *
       * 不剥的话，成员之间夹了 JSDoc 的多行联合类型（每个取值上面挂一段说明 ——
       * 这个仓库很常见）会整条匹配不上，扫描**静默漏掉**它。
       * 这个盲区是被本文件第二检（登记表里不能有代码中不存在的条目）抓到的：
       * 改名后登记表里的新名字扫不出来，于是被报成「已不存在」。
       * 单向的守卫会漏，双向的不会 —— 这正是那一检存在的理由。
       */
      const src = readFileSync(f, "utf8").replace(/\/\*[\s\S]*?\*\//g, "");
      for (const m of src.matchAll(
        /export type (\w+)\s*=\s*((?:\s*\|?\s*"[^"]+"\s*(?:\/\/[^\n]*)?\n?)+);/g,
      )) {
        const values = [...m[2]!.matchAll(/"([^"]+)"/g)].map((x) => x[1]!);
        if (values.length >= 2) out.push({ decl: `${tag}:${m[1]}`, values });
      }
      for (const m of src.matchAll(/export const ([A-Z][A-Z0-9_]*) = \{([^}]*)\} as const;/g)) {
        const values = [...m[2]!.matchAll(/:\s*"([^"]+)"/g)].map((x) => x[1]!);
        if (values.length >= 2 && values.every((v) => /^[A-Z][A-Z0-9_]*$/.test(v)))
          out.push({ decl: `${tag}:${m[1]}`, values });
      }
    }
  };
  scan(
    walk(join(ROOT, "packages/shared/src"), ".ts").filter(
      (f) =>
        !f.includes("/mock/") &&
        // 登记表自身的元类型（Verdict）不是业务枚举 —— 它描述的是登记状态，不是 wire 契约。
        // 第一次跑守卫时它就被抓到了，算是个不错的自证：扫描是真的在扫。
        !f.endsWith("contract/enum-registry.ts"),
    ),
    "shared",
  );
  scan(
    walk(join(ROOT, "ops-web/lib"), ".ts").filter(
      (f) => !f.endsWith(".test.ts") && !f.includes("/mock/"),
    ),
    "ops-web",
  );
  scan(walk(join(ROOT, "c-app/src/api"), ".ts"), "c-app");
  scan(walk(join(ROOT, "b-app/src/api"), ".ts"), "b-app");
  return out;
}

/** 词典 §11 的 L1 状态词表 —— markdown 是权威 */
function statusWords(): Set<string> {
  const sec = GLOSSARY.match(/## 11\. Status words[\s\S]*?\n(?=## 12\.)/);
  if (!sec) throw new Error("词典里找不到「## 11. Status words」一节 —— 改标题要同步改本测试");
  // 表格第二列的反引号标识符就是用词
  const words = [...sec[0].matchAll(/^\|[^|]*\|\s*`([A-Z_]+)`\s*\|/gm)].map((m) => m[1]!);
  if (words.length < 10) throw new Error(`L1 表只解析出 ${words.length} 个词，解析写挂了`);
  return new Set(words);
}

describe("枚举登记表", () => {
  const declared = declaredEnums();
  const registry = new Map(ENUM_REGISTRY.map((e) => [e.decl, e]));

  it("G1 · 端上每个具名枚举都必须登记（新增枚举不能绕过雷达）", () => {
    const missing = declared.map((d) => d.decl).filter((d) => !registry.has(d));
    expect(
      missing,
      "这些枚举没有登记：\n  " +
        missing.join("\n  ") +
        "\n\n在 packages/shared/src/contract/enum-registry.ts 里补一条。" +
        "登记要回答：属于哪个领域、是状态型还是分类型、现状判定是什么。\n" +
        "这一步的成本是刻意的 —— 此前新增枚举零成本，于是三端积到了 121 个，" +
        "其中 8 组同名不同义、8 组异名同义。",
    ).toEqual([]);
  });

  it("G1 · 登记表里不能有代码中已不存在的条目（防止表本身腐烂）", () => {
    const live = new Set(declared.map((d) => d.decl));
    const stale = [...registry.keys()].filter((d) => !live.has(d));
    expect(
      stale,
      `以下条目在代码里已不存在（改名了？删了？）：${stale.join(", ")}\n` +
        "一份记录着不存在的东西的登记表，比没有登记表更糟 —— 它会让人以为已经盘过了。",
    ).toEqual([]);
  });

  it("G2 · STATUS 型枚举的取值必须取自 L1 状态词表，或已申报", () => {
    const L1 = statusWords();
    const offenders: string[] = [];
    for (const d of declared) {
      const e = registry.get(d.decl);
      if (!e || e.shape !== "STATUS") continue;
      const undeclared = d.values.filter(
        (v) => /^[A-Z][A-Z0-9_]*$/.test(v) && !L1.has(v) && !(e.words ?? []).includes(v),
      );
      if (undeclared.length) offenders.push(`${d.decl}: ${undeclared.join(", ")}`);
    }
    expect(
      offenders,
      "这些状态取值既不在词典 §11 的 L1 表内，也没有在登记表的 words 里申报：\n  " +
        offenders.join("\n  ") +
        "\n\n两条路：改成 L1 里的词（多数情况该这么做），" +
        "或在 words 里申报为领域特有词。\n" +
        "申报不是豁免 —— 它逼着你先回答「这个词为什么不能用 L1 里的」。",
    ).toEqual([]);
  });

  /**
   * 规范 §D 第 5 条：禁止在 interface 里内联字面量联合。
   *
   * <p>内联的枚举**对所有工具不可见** —— 登记表登记不到、对账工具比对不到、
   * 改名时必漏一处。这不是洁癖：具名化这 24 处的过程本身就暴露了三对异名同义
   * （`feeMode` ↔ ops 的 `PickupFeeMode`、风控 `subjectType` ↔ ops 的 `SubjectType`、
   * `type: "REFUND_ONLY" | "RETURN_REFUND"` ↔ 已有的 `AfterSaleType`），
   * 还挖出一处 `interface FunnelStep { step: FunnelStep }` 的自我引用。
   * 它们内联时一个都看不见。
   */
  it("规范 §D5 · 不允许在 interface 里内联字面量联合类型", () => {
    const offenders: string[] = [];
    const scan = (files: string[], tag: string) => {
      for (const f of files) {
        // 登记表自身的 shape 字段是元数据，不是业务枚举
        if (f.endsWith("contract/enum-registry.ts")) continue;
        readFileSync(f, "utf8")
          .split("\n")
          .forEach((line, i) => {
            const m = line.match(/^\s+(\w+)\??:\s*(?:"[A-Z][A-Z0-9_]*"\s*\|\s*)+"[A-Z][A-Z0-9_]*"\s*;/);
            if (m) offenders.push(`${tag} ${f.split("/").slice(-2).join("/")}:${i + 1}  ${m[1]}`);
          });
      }
    };
    scan(walk(join(ROOT, "packages/shared/src"), ".ts").filter((f) => !f.includes("/mock/")), "shared");
    scan(
      walk(join(ROOT, "ops-web/lib"), ".ts").filter(
        (f) => !f.endsWith(".test.ts") && !f.includes("/mock/"),
      ),
      "ops-web",
    );
    scan(walk(join(ROOT, "c-app/src/api"), ".ts"), "c-app");
    scan(walk(join(ROOT, "b-app/src/api"), ".ts"), "b-app");
    expect(
      offenders,
      "这些字段内联了字面量联合，要提取成具名类型：\n  " +
        offenders.join("\n  ") +
        "\n\n内联的枚举对所有工具不可见 —— 登记不到、对账不到、改名必漏。",
    ).toEqual([]);
  });

  /**
   * 未复核条目的**棘轮**：只许降不许升。
   *
   * <p>为什么不设成「必须为 0」：54 条不可能一次做完，而设成 0 会让人
   * 把它改成 `OK` 了事 —— 那就退回到本表建起来时的状态
   * （96 条 OK 里只有 4 条真的有人看过）。棘轮让进度单向前进且可度量。
   *
   * <p>降到新低时**要把基线跟着调低**，否则棘轮就松了。这一步是刻意的手工动作：
   * 它逼着改的人确认「这批真的复核完了」，而不是顺手让测试变绿。
   */
  /*
 * 2026-08-11：54 → 0，全部复核完。
 *
 * 基线保持 0 意味着**新增枚举必须当场复核**，不能再留给以后 ——
 * 而这正是这张表当初要解决的问题：「新增一个枚举」的成本曾经是零。
 */
const UNREVIEWED_BASELINE = 0;

  it(`未复核条目只许减少（当前基线 ${UNREVIEWED_BASELINE} 条）`, () => {
    const n = ENUM_REGISTRY.filter((e) => e.verdict === "UNREVIEWED").length;
    expect(
      n,
      n > UNREVIEWED_BASELINE
        ? `未复核条目从 ${UNREVIEWED_BASELINE} 涨到了 ${n} —— 新增枚举请直接复核后登记，别留给以后`
        : `未复核已降到 ${n} 条，把 UNREVIEWED_BASELINE 改成这个数（这一步是手工的：` +
            "它逼你确认这批真的复核完了)",
    ).toBe(Math.min(n, UNREVIEWED_BASELINE));
  });

  it("复核过的条目（OK）不该再有 UNREVIEWED 的兄弟同名 —— 同一个概念要么都看了要么都没看", () => {
    const byName = new Map<string, Set<string>>();
    for (const e of ENUM_REGISTRY) {
      const name = e.decl.split(":")[1]!;
      if (!byName.has(name)) byName.set(name, new Set());
      byName.get(name)!.add(e.verdict);
    }
    const mixed = [...byName]
      .filter(([, v]) => v.has("OK") && v.has("UNREVIEWED"))
      .map(([n]) => n);
    expect(
      mixed,
      `以下同名枚举一端看过、另一端没看：${mixed.join(", ")}\n` +
        "跨端同名正是缺陷高发处（8 组同名不同义都是这么来的），只看一端等于没看。",
    ).toEqual([]);
  });

  it("verdict 不是 OK 的条目必须写明在等什么", () => {
    // UNREVIEWED 不需要理由 —— 它的含义本身就是「还没人看过」
    const bare = ENUM_REGISTRY.filter(
      (e) => e.verdict !== "OK" && e.verdict !== "UNREVIEWED" && !e.note?.trim(),
    ).map((e) => e.decl);
    expect(
      bare,
      `以下条目标了待办但没写理由：${bare.join(", ")}\n` +
        "没有理由的待办等于没有待办 —— 下一个人看到只会把它改成 OK。",
    ).toEqual([]);
  });

  it("申报的领域特有词不能是 L1 里已有的（那说明填错了）", () => {
    const L1 = statusWords();
    const wrong = ENUM_REGISTRY.filter((e) => (e.words ?? []).some((w) => L1.has(w))).map(
      (e) => `${e.decl}: ${(e.words ?? []).filter((w) => L1.has(w)).join(", ")}`,
    );
    expect(wrong, `这些词在 L1 表里，不该出现在 words 申报中：\n  ${wrong.join("\n  ")}`).toEqual(
      [],
    );
  });
});
