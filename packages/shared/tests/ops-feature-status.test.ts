import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

/**
 * 生成物 `运营端-功能清单.md` 的**状态列**必须真的有内容，且与 `nav.ts` 对得上。
 *
 * <h2>它挡的是什么</h2>
 *
 * 2026-09-09：那一列 **149 个数据行里一个值都没有**，而所有闸门都是绿的 ——
 * `check-generated-docs` 只比对「产物是不是最新」，重跑一遍得到同样的空列，
 * 于是「最新」与「正确」被混为一谈。
 *
 * 根因是生成器里一行取反的默认值：`ready = "ready: false" not in rest`。
 * 而 `nav.ts` **从不写 `ready: false`** —— 它要么写 `ready: true`，要么整个省略。
 * 判据恒为真 → 每个叶子都「就绪」→ 那一列每行都输出空串。
 *
 * <h2>为什么量具要另写一遍</h2>
 *
 * 这里**不复用生成器的解析**。共用一把尺的话，尺子本身错了就永远量不出来 ——
 * 上面那个 bug 正是生成器自己的解析错的。所以下面直接对 `nav.ts` 文本
 * 数「显式写了 `ready: true` 的叶子」，与产物里的 ✅ 行数对。
 */
const ROOT = join(import.meta.dirname, "../../..");
const NAV = join(ROOT, "ops-web/lib/nav.ts");
const DOC = join(ROOT, "docs/technical/reference/运营端-功能清单.md");

/** nav.ts 里带 href 的叶子，以及它有没有显式标 ready */
function navLeaves(): { label: string; ready: boolean }[] {
  const src = readFileSync(NAV, "utf8");
  return [...src.matchAll(/\{[^{}]*href:\s*"[^"]+"[^{}]*\}/g)]
    .map((m) => m[0])
    .filter((b) => /label:\s*"/.test(b))
    // NavSection（有 modules:）不是叶子 —— `ready` 是 NavLeaf 的字段，
    // 段自己没有这个字段。把「经营看板」这个单页段算成未就绪的叶子是量具的错，
    // 不是生成器的：生成器对单页段补 ready 是刻意的（段本身就是那一个功能）。
    .filter((b) => !/modules:/.test(b))
    .map((b) => ({
      label: b.match(/label:\s*"([^"]+)"/)![1]!,
      ready: /ready:\s*true/.test(b),
    }));
}

/** 产物里那张表的状态列 */
function docStatuses(): string[] {
  const out: string[] = [];
  for (const line of readFileSync(DOC, "utf8").split("\n")) {
    const c = line.split("|");
    // 六列表：分组 | 子菜单 | 权限码 | 可见角色 | 矩阵 | 状态
    if (c.length !== 8) continue;
    const [, , name, , , matrix, status] = c;
    if (!/^P-\d+\.\d+$|^—$/.test(matrix!.trim())) continue;
    if (name!.trim() === "子菜单") continue;
    out.push(status!.trim());
  }
  return out;
}

describe("运营端功能清单 · 状态列", () => {
  const leaves = navLeaves();
  const statuses = docStatuses();

  it("扫描面下界 —— nav 至少 100 个叶子、产物至少 100 行", () => {
    // 少了这句，正则失配就让两边都变空，而「空集相等」照样通过。今天 125 / 125。
    expect(leaves.length, `nav.ts 只解析出 ${leaves.length} 个叶子`).toBeGreaterThanOrEqual(100);
    expect(statuses.length, `产物只解析出 ${statuses.length} 行`).toBeGreaterThanOrEqual(100);
  });

  it("★★★ 状态列不许整列为空 —— 生成物「最新」不等于「有内容」", () => {
    const filled = statuses.filter((s) => s !== "").length;
    expect(
      filled,
      `${statuses.length} 行里只有 ${filled} 行有状态。\n` +
        "整列空白是一次静默的信息丢失：重跑生成器仍得到同样的空列，\n" +
        "check-generated-docs 会说「产物是最新的」——它比的是新旧，不是内容。",
    ).toBe(statuses.length);
  });

  it("★★★ 未标 ready 的叶子数，产物与 nav.ts 必须一致", () => {
    const navNotReady = leaves.filter((l) => !l.ready).length;
    const docNotReady = statuses.filter((s) => !s.startsWith("✅")).length;
    expect(
      docNotReady,
      `nav.ts 里未显式标 ready 的叶子 ${navNotReady} 个，产物里非 ✅ 的行 ${docNotReady} 个。\n` +
        "对不上先怀疑生成器对 ready 的判据：它曾经写成「没写 ready: false 就算就绪」，\n" +
        "而 nav.ts 从不写 ready: false —— 那个默认值取反让 8 个未就绪的叶子全变成了就绪。",
    ).toBe(navNotReady);
  });
});
