/**
 * 端点路径里「资源段 + {id}」的单复数，**按域各自一致**。
 *
 * 2026-08-06 的《契约漂移清单》定过一条「一律单数」，依据是当时在 C 端量到的
 * 「后端 65:0 也是单数」。2026-08-28 重量了一次，实况已经不是那样：
 *
 *     前缀        单数   复数   复数占比
 *     /mp   C端     24     1       4%
 *     /biz  B端     27    29      51%     ← 真正的混乱在这里
 *     /ops  平台端   7   140      95%
 *
 * 所以判据改成**按域**：
 *   · `/mp` 单数 —— 已经落地，只需防回潮
 *   · `/biz` 单数 —— 27:29 没有多数派，只能挑一个；跟同族 `/mp` 走
 *     （同一套客户端、同一套契约生成器、同一种消费方式）
 *   · `/ops` 复数 —— **它不是 140 条违规，它内部是一致的**。把 140 条线上路径
 *     改成单数，收益是命名统一，代价是动后端控制器、SecurityConfig、权限矩阵
 *     与数据域注册表。承认它是另一套约定，并同样立闸门防止单数混进去。
 *
 * **这道闸拦的是新增**，存量在 `known-plural-paths.txt` 里挂着；
 * 别为了让它变短去改线上路径 —— 那正是这条判据刻意不做的事。
 */
import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { join } from "node:path";

const ROOT = join(__dirname, "../../..");

/** 天然不可数或本来就以 s 结尾的段，两边都不算 */
const EXEMPT = new Set(["goods", "address", "business", "status", "express", "news"]);

const SPECS = [
  ["docs/api/openapi.yaml", "C 端"],
  ["docs/api/openapi-b.yaml", "B 端"],
  ["docs/api/openapi-ops.yaml", "平台端"],
] as const;

function paths(file: string): string[] {
  const s = readFileSync(join(ROOT, file), "utf8");
  const i = s.indexOf("\npaths:");
  if (i < 0) return [];
  return [...s.slice(i).matchAll(/^ {2}"?(\/[^"\s:]+)"?:\s*$/gm)].map((m) => m[1]);
}

/** 看着像复数吗。`ss` 结尾（address / business）不算 */
const looksPlural = (seg: string) => /^[a-z][a-z-]*s$/.test(seg) && !seg.endsWith("ss");

/** 「资源段 + {id}」的所有出现，连同它该是单数还是复数 */
function offenders(): string[] {
  const out: string[] = [];
  for (const [file] of SPECS) {
    for (const p of paths(file)) {
      const segs = p.split("/");
      const wantPlural = segs[1] === "ops"; // 平台端用复数，其余单数
      for (let i = 0; i < segs.length - 1; i++) {
        const seg = segs[i];
        if (!seg || seg.startsWith("{") || EXEMPT.has(seg)) continue;
        if (!segs[i + 1].startsWith("{")) continue;
        if (looksPlural(seg) !== wantPlural) out.push(`${p}  ${seg}`);
      }
    }
  }
  return [...new Set(out)].sort();
}

describe("端点路径：资源段的单复数按域一致", () => {
  it("★★ 新端点必须跟随本域的约定（/mp /biz 单数 · /ops 复数）", () => {
    const known = new Set(
      readFileSync(join(ROOT, "known-plural-paths.txt"), "utf8")
        .split("\n").map((l) => l.trim()).filter((l) => l && !l.startsWith("#")),
    );
    const now = offenders();
    const fresh = now.filter((x) => !known.has(x));
    expect(
      fresh,
      `这些新端点与本域约定不符（/mp /biz 要单数、/ops 要复数）：\n  ${fresh.join("\n  ")}`,
    ).toEqual([]);
  });

  it("★ 清单只许变短 —— 修好了不删，那条路径就永远免检", () => {
    const known = [
      ...new Set(
        readFileSync(join(ROOT, "known-plural-paths.txt"), "utf8")
          .split("\n").map((l) => l.trim()).filter((l) => l && !l.startsWith("#")),
      ),
    ];
    const now = new Set(offenders());
    const stale = known.filter((k) => !now.has(k)).sort();
    expect(stale, `这几条已经改好了，从 known-plural-paths.txt 里删掉：\n  ${stale.join("\n  ")}`).toEqual([]);
  });
});
