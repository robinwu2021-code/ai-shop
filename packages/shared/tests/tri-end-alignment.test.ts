import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

/**
 * C/B 端功能清单 ↔ [三端功能对齐-运营端职责推导](docs/requirements/三端功能对齐-运营端职责推导.md)。
 *
 * <h2>它挡的是什么</h2>
 *
 * 那份文档的规矩是「两端每个功能点判一次运营端欠不欠它东西」，
 * 而**漏判和「判成不需要」在文档里长得一模一样** —— 都是那一行不在表里。
 * 靠人读 178 行去发现少了哪一条，读不出来。
 *
 * <h2>为什么 ID 正则里有那个 `[a-z]?`</h2>
 *
 * 起草时栽过一次：用 `B-[0-9]+(\.[0-9]+){1,2}` 抽 B 端清单，
 * 它把 `B-6.4b`、`B-12.4b` 匹配成了 `B-6.4`/`B-12.4` 的**前缀**，
 * 于是两条真实功能点变成了两条「重复」，清单从 100 条缩成 98 条 ——
 * 而 98 条全部判到了，覆盖率看起来是 100%。
 *
 * **少扫的那两条不会以「漏了」的形式出现，会以「本来就没有」的形式出现。**
 * 所以下面每个方向都带一句下界断言：不是「违规集为空」，而是「我确实看了这么多个」。
 */
const ROOT = join(import.meta.dirname, "../../..");
const REQ = join(ROOT, "docs/requirements");
const ALIGN = join(REQ, "三端功能对齐-运营端职责推导.md");

/** 功能清单里的功能点 ID —— 行首单元格，允许 `B-6.4b` 这种字母后缀 */
function listIds(file: string, re: RegExp): string[] {
  const out = new Set<string>();
  for (const line of readFileSync(join(REQ, file), "utf8").split("\n")) {
    const m = line.match(re);
    if (m) out.add(m[1]!);
  }
  return [...out];
}

const cIds = () => listIds("C端功能清单.md", /^\|\s*`?(C-[A-Z]{2}-\d{2}[a-z]?)`?\s*\|/);
const bIds = () => listIds("B端功能清单.md", /^\|\s*`?(B-\d+(?:\.\d+){1,2}[a-z]?)`?\s*\|/);

type Row = { end: string; id: string; cls: string; why: string; at: string };

/** 对齐文档 §二 的判定行 */
function judged(): Row[] {
  const out: Row[] = [];
  for (const line of readFileSync(ALIGN, "utf8").split("\n")) {
    const m = line.match(
      /^\|\s*([CB])\s*\|\s*((?:C-[A-Z]{2}-\d{2}|B-\d+(?:\.\d+){1,2})[a-z]?)([^|]*)\|([^|]*)\|([^|]*)\|([^|]*)\|/,
    );
    if (!m) continue;
    out.push({
      end: m[1]!,
      id: m[2]!,
      cls: m[4]!.replace(/\*/g, "").trim(),
      why: m[5]!.trim(),
      at: m[6]!.trim(),
    });
  }
  return out;
}

describe("三端功能对齐 · 逐条判定的完整性", () => {
  const rows = judged();
  const ids = [...cIds(), ...bIds()];

  it("两份功能清单至少解析出 170 个功能点 —— 少扫等于全绿", () => {
    // 没有这句，正则一写窄（正是 B-6.4b 那次）受检集合就悄悄缩小，
    // 而下面「每个都判过」照样通过。今天 C 78 + B 100 = 178。
    expect(cIds().length, "C 端功能清单解析结果").toBeGreaterThanOrEqual(75);
    expect(bIds().length, "B 端功能清单解析结果").toBeGreaterThanOrEqual(95);
  });

  it("对齐文档至少解析出 170 行判定 —— 表格格式变了要在这里炸", () => {
    expect(rows.length, `只解析出 ${rows.length} 行`).toBeGreaterThanOrEqual(170);
  });

  it("★★★ 两端每个功能点都判过一次 —— 漏判与「判成不需要」在文档里长得一样", () => {
    const seen = new Set(rows.map((r) => r.id));
    const missing = ids.filter((id) => !seen.has(id));
    expect(
      missing,
      `这些功能点在两端清单里，但对齐文档没判过：\n  ${missing.join("\n  ")}\n` +
        "加了功能点就要判一次运营端欠不欠它东西，不判等于默认不需要。",
    ).toEqual([]);
  });

  it("★★★ 反向：判定行的 ID 必须在两端清单里真实存在 —— 这条才挡得住「尺子变窄」", () => {
    /*
     * 上一条只查「清单 ⊆ 判定」。把 B 端 ID 正则里的 `[a-z]?` 去掉（起草时的真实错误），
     * 受检集合从 100 缩到 98，而那 98 条全判过 —— **上一条照样绿**。做过消融，确认它抓不住。
     * 这一条查的是反方向：文档里判过 `B-6.4b`，尺子却量不出它，说明量具坏了而不是文档错了。
     */
    const known = new Set(ids);
    const ghost = rows.map((r) => r.id).filter((id) => !known.has(id));
    expect(
      ghost,
      `对齐文档判了这些 ID，但两端清单里找不到：\n  ${ghost.join("\n  ")}\n` +
        "先怀疑本文件的 ID 正则（少一个字母后缀就会这样），再怀疑文档写错了。",
    ).toEqual([]);
  });

  it("★★ 没有判两次的 —— 同一功能点两处结论不同时，没人知道哪个算数", () => {
    const n = new Map<string, number>();
    for (const r of rows) n.set(r.id, (n.get(r.id) ?? 0) + 1);
    const dup = [...n].filter(([, k]) => k > 1).map(([id, k]) => `${id} ×${k}`);
    expect(dup, `重复判定：\n  ${dup.join("\n  ")}`).toEqual([]);
  });

  it("★★ 判成「不需要」的必须写理由 —— 不写就分不清是判过还是漏了", () => {
    const bare = rows.filter((r) => r.cls === "—" && (r.why === "" || r.why === "—"));
    expect(bare.length, `受检 ${rows.filter((r) => r.cls === "—").length} 条「—」`)
      .toBe(0);
  });

  it("★★ 判成裁/定/兜的必须有落点 —— 说「该管」而不说归谁，等于没判", () => {
    const need = rows.filter((r) => ["裁", "定", "兜"].includes(r.cls));
    expect(need.length, `受检 ${need.length} 条，这个断言正在空转`).toBeGreaterThanOrEqual(90);
    const noWhere = need.filter((r) => r.at === "" || r.at === "—").map((r) => r.id);
    // 落点写「—」是允许的**一种**情况：平台端确实没有这一项，那它必须出现在 §3.2 真缺口表里。
    const gaps = readFileSync(ALIGN, "utf8").match(/### 3\.2 真缺口[\s\S]*?(?=\n### )/)?.[0] ?? "";
    const unlisted = noWhere.filter((id) => !gaps.includes(id));
    expect(
      unlisted,
      `这些判了「该管」但没写落点，也不在 §3.2 真缺口表里：\n  ${unlisted.join("\n  ")}`,
    ).toEqual([]);
  });
});
