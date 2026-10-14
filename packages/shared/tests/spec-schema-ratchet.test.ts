// 三份 OpenAPI 契约里的 schema **只许增，不许悄悄减**。
//
// ─────────────────────────────────────────────────────────────────────────────
// 这道闸在防什么（2026-09-03 实测出来的）
// ─────────────────────────────────────────────────────────────────────────────
// 三份契约由 `*/scripts/gen-openapi.mjs` 用 `ts-json-schema-generator` 生成，
// 而那个生成器**只从入口文件里声明的类型出发解引用**：跨文件的类型引用
// （`import type { X } from "@shared/types"` 再 `export type Y = X`）它解不开，
// 解不开就 **catch 掉、跳过**，不报错。
//
// 也就是说 `packages/shared/src/types/index.ts` 那 5139 行**必须待在一个文件里**——
// 这条约束此前不在任何地方写着。实测：把它按域拆成 13 个文件之后，
// b 端契约的 schema 从 276 掉到 237，**一行报错都没有**：
// 端上不会有任何提示，只有接真后端那天才会撞上一个「契约里没有这个类型」。
//
// 所以这里记一条基线。要拆那个文件（那是对的方向）就得先让生成器能跨文件解析，
// 而这道闸会告诉你有没有做到 —— 数字掉了就是没做到。
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const ROOT = join(import.meta.dirname, "../../..");

/**
 * 记录于 2026-09-03。**只准往上改**：
 * 加了类型就把数字调大（顺手确认那几个类型真的进了契约），
 * 掉下来说明有东西被静默丢掉了 —— 先去看生成器，别改这里。
 */
const BASELINE: Record<string, number> = {
  "openapi.yaml": 256,      // C 端
  "openapi-b.yaml": 276,    // B 端
  "openapi-ops.yaml": 278,  // 平台端
};

/** `components.schemas` 下的顶层键。缩进到 4 空格那一层就是 schema 名。 */
function schemaNames(file: string): string[] {
  const lines = readFileSync(join(ROOT, "docs/api", file), "utf8").split("\n");
  const start = lines.findIndex((l) => /^ {2}schemas:\s*$/.test(l));
  if (start < 0) throw new Error(`${file} 里找不到 components.schemas —— 契约的形状变了`);
  const out: string[] = [];
  for (const l of lines.slice(start + 1)) {
    if (/^\s*$/.test(l)) continue;
    // 回到 2 空格或更浅 = schemas 段结束
    if (!/^ {4}/.test(l) && /^\s{0,3}\S/.test(l)) break;
    const m = /^ {4}([A-Za-z0-9_]+):\s*$/.exec(l);
    if (m) out.push(m[1]);
  }
  return out;
}

describe("OpenAPI 契约的 schema 覆盖", () => {
  for (const [file, floor] of Object.entries(BASELINE)) {
    it(`★★★ ${file} 的 schema 不少于 ${floor} 个 —— 少了就是有类型被静默丢掉`, () => {
      const names = schemaNames(file);
      expect(
        names.length,
        `${file} 现在只有 ${names.length} 个 schema，基线是 ${floor}。\n`
        + "  生成器解不开的类型是**被 catch 掉的**，不会报错 —— 所以掉数字是唯一的信号。\n"
        + "  最常见的原因：共享类型被拆到了别的文件，而生成器只认入口文件里声明的那些。",
      ).toBeGreaterThanOrEqual(floor);
    });
  }

  it("★★ 三份契约都不许出现重名 schema —— 重名会让后一个静默覆盖前一个", () => {
    for (const file of Object.keys(BASELINE)) {
      const names = schemaNames(file);
      const dup = names.filter((n, i) => names.indexOf(n) !== i);
      expect(dup, `${file} 里有重名 schema：${dup.join(", ")}`).toEqual([]);
    }
  });
});
