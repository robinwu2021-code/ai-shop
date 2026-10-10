// 页面顶层块之间的留白，由**外壳**给，不由页面各写各的。
//
// 起因：进货页与库存明细页上，几张白卡连成一整块灰板 —— 看不出哪儿到哪儿是一件事。
// 查下去是系统性的：`.sh-block + .sh-block` 有 20rpx，而 `.sh-card` 之间
// 一条规则都没有。全仓 238 处 `sh-card` 里只有 24 处手写了 `sh-mb-sm`。
//
// 修法是给 `.sh-scaffold > * + *` 一条 20rpx（见 base.css 那段注释）。
// 这条守卫拦的是**回潮**：页面再在顶层块上写自己的纵向 margin，就会
//   · 压过外壳那条（页面的 scoped 选择器 specificity 更高），于是这一页
//     的间距与别处不一样 —— 而它长得像「这一页就该松一点」，没人会去量；
//   · 一页一个数，改总体密度时又回到「逐页改 90 个文件」。
//
// **横向 margin 不管**：`margin: 0 8rpx` 那类是缩进，与块间距无关。
import { readFileSync } from "node:fs";
import { globSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const ROOT = join(import.meta.dirname, "../../..");
const VOID = new Set(["input", "img", "br", "hr", "image", "icon"]);

/** 每个 class 在 sh-scaffold 里出现过的深度集合（template 与 v-if 不算一层） */
function depths(tpl: string): Map<string, Set<number>> {
  const out = new Map<string, Set<number>>();
  let started = false;
  let d = 0;
  for (const raw of tpl.split("\n")) {
    const s = raw.trim();
    if (!started) {
      if (s.includes("<sh-scaffold")) { started = true; d = 0; }
      continue;
    }
    if (s.includes("</sh-scaffold>")) break;
    const cm = /class="([^"]+)"/.exec(s);
    if (cm) {
      for (const c of cm[1]!.split(/\s+/)) {
        if (!out.has(c)) out.set(c, new Set());
        out.get(c)!.add(d);
      }
    }
    for (const m of s.matchAll(/<(\/?)([a-zA-Z][\w-]*)/g)) {
      const [, close, tag] = m;
      if (VOID.has(tag!) || tag === "template") continue;
      if (close) d -= 1;
      else if (!new RegExp(`<${tag}\\b[^>]*/>`).test(s)) d += 1;
    }
  }
  return out;
}

describe("页面顶层块的留白", () => {
  const files = globSync(join(ROOT, "b-app/src/pages/*/index.vue"));

  it("有东西可查", () => {
    expect(files.length).toBeGreaterThan(50);
  });

  it("★★ 顶层块不许自己写纵向 margin —— 写了就压过外壳那条，这一页从此和别处不一样", () => {
    const bad: string[] = [];
    for (const f of files) {
      const src = readFileSync(f, "utf8");
      const si = src.indexOf("<style");
      if (si < 0) continue;
      const [tpl, style] = [src.slice(0, si), src.slice(si)];
      for (const [cls, ds] of depths(tpl)) {
        if (cls.startsWith("sh-") || !ds.has(0)) continue;
        // 同一个类名也用在更深的层：改它会伤到嵌套处，判不了，放过
        if (ds.size > 1) continue;
        const rule = new RegExp(`\\.${cls.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}\\s*\\{([^}]*)\\}`).exec(style);
        if (!rule) continue;
        const decl = /\bmargin(-top|-bottom)?\s*:\s*([^;]+);/.exec(rule[1]!);
        if (!decl) continue;
        // margin: 0 8rpx —— 纵向已是 0，那是缩进不是间距
        const parts = decl[2]!.trim().split(/\s+/);
        if (!decl[1] && parts.length > 1 && parts[0] === "0") continue;
        bad.push(`${f.slice(ROOT.length + 1)}  .${cls}  ${decl[0]!.trim()}`);
      }
    }
    expect(
      bad,
      "这些页面在顶层块上自己写了纵向 margin。\n" +
        "→ 删掉它：`.sh-scaffold > * + *` 已经给了 20rpx（base.css）。\n" +
        "  留着的话这一页的块间距与别处不同，而它长得像「本该如此」，没人会去量：\n",
    ).toEqual([]);
  });
});
