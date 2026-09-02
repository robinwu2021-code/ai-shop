// b-app 的**门店维度**必须与声明一致（`b-app/src/shared/store-scope.ts`）。
//
// ─────────────────────────────────────────────────────────────────────────────
// 这道闸在防什么
// ─────────────────────────────────────────────────────────────────────────────
// 「这一页是不是按门店取数」此前不存在于任何地方，只隐含在
// 「调了哪些接口、后端读不读 X-Store-No」里。于是每加一个按门店的页面，
// 都要有人凭记忆想起来加那枚当前门店胶囊，而**忘了不报错**：
// 页面照常出数，只是那些数属于另一家店。
//
// 2026-09-02 实测：后端 10 个 B 端控制器按门店取数，胶囊只在 4 个页面上。
// 用户报的症状是「切了门店，『我的』顶部没变」—— 那一页顶部是主体名，
// 按定义就不随切店变；而同一页的「经营统计」确实换了数。
//
// ─────────────────────────────────────────────────────────────────────────────
// 为什么两个方向都要查
// ─────────────────────────────────────────────────────────────────────────────
// 只查「声明了要渲染」的话，清单会慢慢变成一份和代码无关的愿望列表：
// 有人删掉模板里那一行，清单还写着「这一页按门店」，而没有任何东西会响。
// 反过来只查「渲染了要声明」也不行 —— 那样漏掉整页才是最常见的错。
// **两边都查，清单才是模型，而不是注释。**
import { readFileSync, readdirSync, existsSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const ROOT = join(import.meta.dirname, "../../..");
const PAGES = join(ROOT, "b-app/src/pages");
const MODEL = join(ROOT, "b-app/src/shared/store-scope.ts");

/** 从模型文件里解出两份清单。**读源码而不是 import**：这里是 vitest 的 node 环境，
 *  而 b-app 的 ts 里有 uni-app 的全局，直接 import 会炸在无关的地方。 */
function parseRecord(src: string, name: string): Record<string, string> {
  const m = new RegExp(`export const ${name}[^=]*=\\s*\\{([\\s\\S]*?)\\n\\};`).exec(src);
  if (!m) throw new Error(`${name} 没解出来 —— 模型文件的形状变了，先改这个解析器`);
  const out: Record<string, string> = {};
  for (const line of m[1].split("\n")) {
    const kv = /^\s*"?([\w-]+)"?\s*:\s*"([\s\S]*?)",?\s*$/.exec(line);
    if (kv) out[kv[1]] = kv[2];
  }
  return out;
}

const src = readFileSync(MODEL, "utf8");
const SCOPED = parseRecord(src, "STORE_SCOPED_PAGES");
const NOT_SCOPED = parseRecord(src, "NOT_STORE_SCOPED_REASONS");

/** 这一页的全部源码（一个页面可能拆成多个文件）。 */
function pageSource(dir: string): string {
  const d = join(PAGES, dir);
  if (!existsSync(d)) return "";
  return readdirSync(d)
    .filter((f) => f.endsWith(".vue") || f.endsWith(".ts"))
    .map((f) => readFileSync(join(d, f), "utf8"))
    .join("\n");
}

const RENDERS_TAG = /<biz-store-tag\b/;

describe("B 端门店维度", () => {
  it("★★★ 声明了按门店的页面，必须渲染当前门店胶囊 —— 不标出来，人会在另一家店上动手", () => {
    const missing: string[] = [];
    for (const dir of Object.keys(SCOPED)) {
      const s = pageSource(dir);
      if (!s) {
        missing.push(`${dir}（页面目录不存在 —— 清单过期了）`);
        continue;
      }
      if (!RENDERS_TAG.test(s)) missing.push(dir);
    }
    expect(
      missing,
      "这些页面声明了按门店取数，却没有渲染 <biz-store-tag>：\n  "
      + missing.join("\n  ")
      + "\n→ 在页面模板里加一枚（只读形态），或从 store-scope.ts 的清单里去掉并说明为什么",
    ).toEqual([]);
  });

  it("★★★ 渲染了胶囊的页面，必须在清单里 —— 否则清单会慢慢变成和代码无关的愿望列表", () => {
    const undeclared: string[] = [];
    for (const dir of readdirSync(PAGES)) {
      if (SCOPED[dir]) continue;
      if (RENDERS_TAG.test(pageSource(dir))) undeclared.push(dir);
    }
    expect(
      undeclared,
      "这些页面渲染了 <biz-store-tag>，但 store-scope.ts 里没有声明：\n  "
      + undeclared.join("\n  ")
      + "\n→ 补进 STORE_SCOPED_PAGES 并写清依据（哪个后端控制器按门店取的数）",
    ).toEqual([]);
  });

  it("每条声明都要有依据；两份清单不许重叠 —— 同一页不能既「按门店」又「刻意不按」", () => {
    const noReason = Object.entries(SCOPED)
      .filter(([, why]) => !why.trim())
      .map(([dir]) => dir);
    expect(noReason, `这些声明没写依据（理由为空等于没声明）：\n  ${noReason.join("\n  ")}`)
      .toEqual([]);

    const both = Object.keys(SCOPED).filter((d) => NOT_SCOPED[d]);
    expect(both, `同时出现在两份清单里：\n  ${both.join("\n  ")}`).toEqual([]);

    // 「刻意不按」的那份也要指向真实存在的页面，否则它只是历史噪声
    const stale = Object.keys(NOT_SCOPED).filter((d) => !existsSync(join(PAGES, d)));
    expect(stale, `NOT_STORE_SCOPED_REASONS 里的页面已经不存在：\n  ${stale.join("\n  ")}`)
      .toEqual([]);
  });
});
