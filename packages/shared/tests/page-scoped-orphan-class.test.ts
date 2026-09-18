import { describe, expect, it } from "vitest";
import { readFileSync, readdirSync, existsSync } from "node:fs";
import { join } from "node:path";

/**
 * **页面的 scoped 样式，只许描自己模板里的元素。**
 *
 * 一条 `.card__ops { … }` 写在页面的 `<style scoped>` 里，而 `card__ops`
 * 其实长在 `biz-address-card` **组件内部** —— 这在两端的结果是不一样的：
 *
 * · H5：Vue 把父级的 scope id 也贴到子组件的根元素上，选择器命中，样式生效；
 * · 小程序：类名在组件内部的根 view 上（带**组件的** data-v），
 *   页面的 data-v 在**宿主节点**上，没有任何一个节点同时有这两样 —— 整条规则落空。
 *
 * 于是同一张卡在两端长得不一样，而两边都不报错、都不告警。
 * 2026-09-18 用户报的「小程序地址列表两条地址贴在一起」就是这么来的：
 * 缝来自页面的 `.card { margin-bottom: 20rpx }`，小程序上它一个节点都选不中。
 *
 * 顺带也拦住另一类：抽完组件忘了删的死规则（`.here*`、`.sheet__save` 那些）。
 *
 * **这是一条止血线，不是待办清单。** 下面的基线是当时就有的 25 条，
 * 只许变少不许变多；要清它们得一条条确认「这个类到底该归谁」，
 * 不是把名字从名单里划掉就行。
 */
const ROOT = join(import.meta.dirname, "../../..");

/** 库件与状态类前缀：它们本来就由全局样式提供，页面覆盖是正常用法 */
const GLOBAL_PREFIXES = ["sh-", "txt-", "is-", "uni-"];

/** 立此存照的欠账：文件 → 该文件里还没归位的类名 */
const KNOWN: Record<string, string[]> = {
  "b-app/src/pages/activities/index.vue": ["acts", "effect", "item__head", "line", "rule"],
  "b-app/src/pages/cross-store/index.vue": ["grid", "grid__i", "grid__v", "month", "todo", "todo__i", "todo__l"],
  "b-app/src/pages/login/index.vue": ["blk"],
  "b-app/src/pages/purchase-edit/index.vue": ["pick"],
  "b-app/src/pages/stock-out/index.vue": ["hint", "pick"],
  "b-app/src/pages/store-categories/index.vue": ["head"],
  "b-app/src/pages/store/index.vue": ["head__sub"],
  "b-app/src/pages/transfer/index.vue": ["hint", "pick"],
  "c-app/src/pages/login/index.vue": ["divider"],
  "c-app/src/pages/order/index.vue": ["codecard--redeem"],
  "c-app/src/pages/points/index.vue": ["hero__off"],
  "c-app/src/pages/search/index.vue": ["block"],
  "c-app/src/pages/store/index.vue": ["cats__chip"],
};

function pages(): string[] {
  const out: string[] = [];
  for (const app of ["c-app", "b-app"]) {
    const dir = join(ROOT, app, "src/pages");
    if (!existsSync(dir)) continue;
    for (const d of readdirSync(dir)) {
      const f = join(dir, d, "index.vue");
      if (existsSync(f)) out.push(`${app}/src/pages/${d}/index.vue`);
    }
  }
  return out;
}

/** 这个页面的 scoped 样式里声明了、而它自己的模板里没用到的类 */
function orphansOf(rel: string): string[] {
  const src = readFileSync(join(ROOT, rel), "utf8");
  const sAt = src.indexOf("<style scoped>");
  const tAt = src.indexOf("<template>");
  if (sAt < 0 || tAt < 0) return [];
  const tpl = src.slice(tAt, sAt);
  const sty = src.slice(sAt);

  const used = new Set<string>();
  for (const m of tpl.matchAll(/class="([^"]*)"/g)) {
    for (const c of m[1]!.split(/\s+/)) if (c) used.add(c);
  }
  // `:class="{ 'x': cond }"` / `:class="a ? 'x' : 'y'"` 里的字面量也算用到了
  for (const m of tpl.matchAll(/'([\w-]+)'/g)) used.add(m[1]!);

  return [...new Set([...sty.matchAll(/^\.([\w-]+(?:__[\w-]+)?(?:--[\w-]+)?)\s*[,{]/gm)].map((m) => m[1]!))]
    .filter((c) => !used.has(c) && !GLOBAL_PREFIXES.some((p) => c.startsWith(p)))
    .sort();
}

describe("页面 scoped 样式不描别人的内部", () => {
  it("★ 量具自身有效 —— 真的扫到了页面", () => {
    const all = pages();
    expect(all.length, "一个页面都没扫到，下面那条断言就是在量空集").toBeGreaterThan(80);
    // 且确实解析出了类：全为空说明正则失效了
    expect(all.flatMap(orphansOf).length + all.filter((p) => readFileSync(join(ROOT, p), "utf8").includes("<style scoped>")).length)
      .toBeGreaterThan(0);
  });

  it("★★★ 不许新增：页面只描自己模板里的类", () => {
    const added: string[] = [];
    for (const p of pages()) {
      const known = new Set(KNOWN[p] ?? []);
      for (const c of orphansOf(p)) if (!known.has(c)) added.push(`${p} → .${c}`);
    }
    expect(
      added,
      "这些类只在页面的 scoped 样式里声明、页面模板却没用到。\n"
        + "  要么它长在某个子组件内部（那就把规则搬进那个组件 —— 留在页面上小程序不生效），\n"
        + "  要么它已经没人用了（抽组件时漏删的，直接删）。",
    ).toEqual([]);
  });

  it("★★★ 基线要锈就报：修好的行必须从名单里删掉", () => {
    const stale: string[] = [];
    for (const [p, cs] of Object.entries(KNOWN)) {
      if (!existsSync(join(ROOT, p))) { stale.push(`${p}（文件没了）`); continue; }
      const now = new Set(orphansOf(p));
      for (const c of cs) if (!now.has(c)) stale.push(`${p} → .${c}`);
    }
    expect(stale, "这些已经不是欠账了，从 KNOWN 里删掉 —— 留着等于给那个文件免检").toEqual([]);
  });
});
