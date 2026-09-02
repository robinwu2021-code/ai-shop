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
// ─────────────────────────────────────────────────────────────────────────────
// 2026-09-03：从「按页面」改成「按接口」
// ─────────────────────────────────────────────────────────────────────────────
// 上一版的清单是人手写的页面名，而其中**三条是错的**（coupons / coupon-issues / me
// 的数据根本不按门店，却都挂着胶囊 = 跟店主说了三次谎）。
// 现在真源是接口清单，页面按不按门店**由它调了哪些接口推出来**，这里负责核对。
//
// 三个方向都查：
//   1. 页面调了按门店的接口 → 要么标出来，要么在「刻意不标」里写清为什么；
//   2. 标了却没调 → 也红（清单会慢慢变成和代码无关的愿望列表）；
//   3. mock 必须认当前门店 —— 否则切了店界面纹丝不动，而那正是
//      「门店切换好像没做好」的样子：切店是好的，只是替身看不见它。
import { readFileSync, readdirSync, existsSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const ROOT = join(import.meta.dirname, "../../..");
const PAGES = join(ROOT, "b-app/src/pages");
const MODEL = join(ROOT, "b-app/src/shared/store-scope.ts");
const MOCK = join(ROOT, "b-app/src/api/mock.ts");

/** 从模型文件里解出一份清单。**读源码而不是 import**：这里是 vitest 的 node 环境，
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
const ENDPOINTS = parseRecord(src, "STORE_SCOPED_ENDPOINTS");
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

/** 这一页调了哪些按门店的接口。 */
function scopedCallsOf(dir: string): string[] {
  const s = pageSource(dir);
  return Object.keys(ENDPOINTS).filter((e) => new RegExp(`api\\.${e}\\b`).test(s));
}

const ALL_DIRS = readdirSync(PAGES).filter((d) => existsSync(join(PAGES, d)));
const RENDERS_TAG = /<biz-store-tag\b/;

describe("B 端门店维度", () => {
  it("★★★ 清单必须与代码算出来的一致 —— 手写的清单会慢慢变成三条谎话", () => {
    const shouldDeclare = ALL_DIRS
      .filter((d) => scopedCallsOf(d).length > 0)
      .filter((d) => !NOT_SCOPED[d])
      .sort();
    const declared = Object.keys(SCOPED).sort();

    const missing = shouldDeclare.filter((d) => !declared.includes(d));
    const stale = declared.filter((d) => !shouldDeclare.includes(d));
    expect(
      { 该标却没标: missing, 标了却不按门店: stale },
      "页面按不按门店由「它调了哪些按门店的接口」推出来（STORE_SCOPED_ENDPOINTS）。\n"
      + "  该标却没标 → 补进 STORE_SCOPED_PAGES 并加 <biz-store-tag readonly>，"
      + "或写进 NOT_STORE_SCOPED_REASONS 说明为什么不标；\n"
      + "  标了却不按门店 → 那枚胶囊在骗人（它说「这一屏属于这家店」，而数据不是），删掉它",
    ).toEqual({ 该标却没标: [], 标了却不按门店: [] });
  });

  it("★★★ 声明了按门店的页面，必须渲染当前门店胶囊 —— 不标出来，人会在另一家店上动手", () => {
    const missing = Object.keys(SCOPED).filter((dir) => {
      const s = pageSource(dir);
      return !s || !RENDERS_TAG.test(s);
    });
    expect(
      missing,
      "这些页面声明了按门店取数，却没有渲染 <biz-store-tag>：\n  "
      + missing.join("\n  ")
      + "\n→ 在页面模板里加一枚（只读形态），或从 store-scope.ts 的清单里去掉并说明为什么",
    ).toEqual([]);
  });

  it("★★★ 渲染了胶囊的页面，必须在清单里 —— 否则清单会慢慢变成和代码无关的愿望列表", () => {
    const undeclared = ALL_DIRS
      .filter((d) => !SCOPED[d])
      .filter((d) => RENDERS_TAG.test(pageSource(d)));
    expect(
      undeclared,
      "这些页面渲染了 <biz-store-tag>，但 store-scope.ts 里没有声明：\n  "
      + undeclared.join("\n  ")
      + "\n→ 补进 STORE_SCOPED_PAGES 并写清依据（哪个接口按门店取的数）",
    ).toEqual([]);
  });

  it("★★★ mock 必须认当前门店 —— 不认的话切了店界面纹丝不动，看起来就是「切店没做好」", () => {
    const mock = readFileSync(MOCK, "utf8");
    // 替身里这几个函数体必须出现读当前门店的那两个helper之一
    const blind: string[] = [];
    for (const name of Object.keys(ENDPOINTS)) {
      const m = new RegExp(`async ${name}\\s*\\([^)]*\\)\\s*\\{`).exec(mock);
      if (!m) continue;   // 这个接口 mock 还没实现，另有闸门管（mock-coverage）
      // 取到下一个 "\n  async " 之前，就是这个函数的体
      const rest = mock.slice(m.index);
      const end = rest.indexOf("\n  async ");
      const body = end < 0 ? rest : rest.slice(0, end);
      if (!/currentStoreNo\(|scopedToStore\(|scopedBalances\(|STORAGE\.storeNo/.test(body)) {
        blind.push(name);
      }
    }
    expect(
      blind,
      "这些接口后端按当前门店取数，而 mock 的实现看不见门店：\n  "
      + blind.join("\n  ")
      + "\n→ 用 mock.ts 里的 currentStoreNo() / scopedToStore() / scopedBalances()。\n"
      + "  不改的话：mock 包上切门店，这些页面一个数字都不会变 —— "
      + "而店主与我们平时验的正是 mock 包",
    ).toEqual([]);
  });

  it("每条声明都要有依据；两份清单不许重叠 —— 同一页不能既「按门店」又「刻意不按」", () => {
    const noReason = Object.entries({ ...SCOPED, ...ENDPOINTS })
      .filter(([, why]) => !why.trim())
      .map(([k]) => k);
    expect(noReason, `这些声明没写依据（理由为空等于没声明）：\n  ${noReason.join("\n  ")}`)
      .toEqual([]);

    const both = Object.keys(SCOPED).filter((d) => NOT_SCOPED[d]);
    expect(both, `同时出现在两份清单里：\n  ${both.join("\n  ")}`).toEqual([]);

    const stale = Object.keys(NOT_SCOPED).filter((d) => !existsSync(join(PAGES, d)));
    expect(stale, `NOT_STORE_SCOPED_REASONS 里的页面已经不存在：\n  ${stale.join("\n  ")}`)
      .toEqual([]);
  });
});
