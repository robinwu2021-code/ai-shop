/**
 * <b>没人评过就不许显示星级。</b>
 *
 * <p>规则本来就写在契约里（`Merchant.ratingCount` 的注释：
 * 「端上按 `ratingCount === 0` 显示『暂无评价』，不要显示 0 颗星」），
 * 但没有任何东西拦着 —— 于是三处用法里有两处是裸的。
 *
 * <p>为什么要紧：后端对零评价的商家回 <b>5.0</b>（默认值，不是「大家都给了满分」）。
 * 裸显示出来就是五星好评，而商家报价那一屏正是买家挑人的地方 ——
 * 一个假的满分会直接决定他选谁，且不报错、不空白，谁也不会怀疑。
 */
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const ROOT = join(import.meta.dirname, "../../..");
const APPS = ["c-app", "b-app", "packages/ui"];

function walk(dir: string, out: string[] = []): string[] {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p, out);
    else if (p.endsWith(".vue")) out.push(p);
  }
  return out;
}

describe("评分展示", () => {
  it("★★ 每个 <sh-rating> 都要有 ratingCount 护栏（组件本身除外）", () => {
    const offenders: string[] = [];

    for (const app of APPS) {
      for (const f of walk(join(ROOT, app))) {
        if (f.endsWith("sh-rating.vue")) continue; // 组件自己不需要护栏
        const src = readFileSync(f, "utf8");
        // 逐个 <sh-rating ...> 看它自身或紧邻上文有没有 ratingCount 判断
        for (const m of src.matchAll(/<sh-rating[\s\S]{0,240}?(?:\/>|<\/sh-rating>)/g)) {
          const tag = m[0];
          const before = src.slice(Math.max(0, m.index! - 240), m.index!);
          /*
           * 两种放行：有 ratingCount 护栏，或**显式标注这是单条评价**。
           * 后者不是漏网 —— 某个人给了几星本来就没有「评价数」可言。
           * 用显式注释而不是猜变量名（`review.rating` / `r.rating` / `x.rating`）：
           * 猜错的方向是「悄悄放行一个真问题」，而那正是这条守卫要防的。
           */
          const guarded =
            /ratingCount/.test(tag)
            || /ratingCount/.test(before)
            || /single-review/.test(before);
          if (!guarded) {
            const line = src.slice(0, m.index).split("\n").length;
            offenders.push(`${f.slice(ROOT.length + 1)}:${line}`);
          }
        }
      }
    }

    expect(
      offenders,
      "这些地方没判 ratingCount 就画了星级 —— 零评价的商家会显示成五星好评：\n  "
        + offenders.join("\n  ")
        + "\n  改法：`v-if=\"x.ratingCount > 0\"`，否则显示「暂无评价」"
        + "（见 biz-merchant-bar.vue 的写法）。",
    ).toEqual([]);
  });
});
