// 运营端「角色 × 端点」可访问矩阵的基线守卫。
//
// **它是为权限码细化那次改造装的**（docs/technical/design/权限码细化-对齐清单.md）：
// 16 个粗码要拆成 64 个细码、147 处 @PreAuthorize 逐个换。
// 判错的两种后果不对称 —— **判紧了有人报错（看得见），判松了没人报错（看不见）**。
// 所以阶段 A 必须是可机器验证的等价重构：拆完这份矩阵**逐格相同**。
//
// 之后它继续有用：任何一次改注解、改 ROLE_PERMS，
// 「谁能访问什么」的变化都会以 diff 的形式摆出来，而不是藏在一行注解里。
// 改动是合理的就更新基线（`node scripts/gen-perm-endpoint-matrix.mjs`），
// **但那一步是显式的** —— 这正是这条守卫要的东西。
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
// @ts-expect-error 生成器是 .mjs，没有类型声明；这里只用它的三个纯函数
import { buildMatrix, scanEndpoints, PUBLIC } from "../../../scripts/gen-perm-endpoint-matrix.mjs";
// @ts-expect-error 同上
import { codeOf, RULES } from "../../../scripts/perm-endpoint-map.mjs";

const ROOT = join(import.meta.dirname, "../../..");
const BASELINE = join(ROOT, "packages/shared/tests/fixtures/ops-role-endpoint-matrix.json");

describe("运营端角色×端点矩阵", () => {
  it("★★★ 谁能访问什么，必须与基线逐格相同", () => {
    const { matrix } = buildMatrix(ROOT);
    const baseline = JSON.parse(readFileSync(BASELINE, "utf8"));

    // 逐角色比对而不是整体 deepEqual：整体比对的报错是一大坨 JSON，
    // 看不出「是哪个角色多了哪一条」——而那恰恰是唯一要看的信息。
    expect(Object.keys(matrix).sort()).toEqual(Object.keys(baseline).sort());
    for (const role of Object.keys(baseline)) {
      const added = matrix[role].filter((e: string) => !baseline[role].includes(e));
      const removed = baseline[role].filter((e: string) => !matrix[role].includes(e));
      expect(
        { role, 多出来的: added, 少掉的: removed },
        `【${role}】能访问的端点变了。\n` +
          "  多出来 = **放宽**（没人会报错，所以只有这里能发现）\n" +
          "  少掉了 = 收紧（会有人点不动，但也该是有意的）\n" +
          "  确认是有意的之后：node scripts/gen-perm-endpoint-matrix.mjs",
      ).toEqual({ role, 多出来的: [], 少掉的: [] });
    }
  });

  it("★★ 没挂 @PreAuthorize 的端点必须在 PUBLIC 里登记", () => {
    const unguarded = scanEndpoints(ROOT)
      .filter((e: { perm: string | null }) => !e.perm)
      .map((e: { method: string; path: string }) => `${e.method} ${e.path}`);

    const unexpected = unguarded.filter((k: string) => !PUBLIC.has(k));
    expect(
      unexpected,
      "这些 /ops 端点对**任何登录的运营账号**开放，且没有说明为什么。\n" +
        "  要么加 @PreAuthorize，要么在 gen-perm-endpoint-matrix.mjs 的 PUBLIC 里写清理由。",
    ).toEqual([]);
  });

  it("★★ 每个端点在细化登记表里都要有归属 —— 漏一条就是拆码时被落下的那条", () => {
    const unmapped = scanEndpoints(ROOT)
      .map((e: { method: string; path: string }) => `${e.method} ${e.path}`)
      .filter((k: string) => !PUBLIC.has(k))
      .filter((k: string) => !codeOf(k.split(" ")[0], k.slice(k.indexOf(" ") + 1)));
    expect(
      unmapped,
      "这些端点在 scripts/perm-endpoint-map.mjs 里匹配不到任何规则。\n" +
        "  权限码细化改造会逐个换注解，**没归属的那条只能保持粗码** ——\n" +
        "  于是它会悄悄成为「还留在大钥匙上」的一条，而没人知道。",
    ).toEqual([]);
  });

  it("★ 登记表里不能有死规则 —— 一条都匹配不到的规则说明路径已经变了", () => {
    const eps = scanEndpoints(ROOT)
      .map((e: { method: string; path: string }) => e)
      .filter((e: { method: string; path: string }) => !PUBLIC.has(`${e.method} ${e.path}`));
    const dead = RULES.filter(
      ([m, re]: [string, RegExp]) =>
        !eps.some((e: { method: string; path: string }) => (m === "*" || m === e.method) && re.test(e.path)),
    ).map(([m, re, code]: [string, RegExp, string]) => `${m} ${re} → ${code}`);
    expect(
      dead,
      "这些规则匹配不到任何端点。要么路径改了、要么规则被前面更宽的一条盖住了 ——\n" +
        "  两种都会让它下面写的那句理由变成谎话。",
    ).toEqual([]);
  });

  it("★ PUBLIC 里不能有已经不存在的端点 —— 名单本身也会过期", () => {
    const all = new Set(
      scanEndpoints(ROOT).map((e: { method: string; path: string }) => `${e.method} ${e.path}`),
    );
    const stale = [...PUBLIC.keys()].filter((k) => !all.has(k as string));
    expect(stale, "这几条端点没了，从 PUBLIC 删掉 —— 留着会让人以为某处仍然是敞开的").toEqual([]);
  });
});
