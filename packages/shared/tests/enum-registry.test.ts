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
      const src = readFileSync(f, "utf8");
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

  it("verdict 不是 OK 的条目必须写明在等什么", () => {
    const bare = ENUM_REGISTRY.filter((e) => e.verdict !== "OK" && !e.note?.trim()).map(
      (e) => e.decl,
    );
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
