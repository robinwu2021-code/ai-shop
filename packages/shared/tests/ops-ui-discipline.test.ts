// 运营端的界面纪律：把**此刻已经干净**的那几条钉住。
//
// 2026-08-28 第一次给 ops-web 做界面盘点（此前它一直不在 `gen-ui-lib.py` 的判据里，
// 理由写在那份清单顶上：React/Next，用不了 `sh-*`，是另一套体系 ——
// 但「不适用那套判据」被读成了「没查过」，而这两件事差得很远）。
//
// 实测下来它比两个小程序端干净得多：
//   · 24 个 `page.tsx` **全部**引用 `components/ui`
//   · 0 页自己搭 `<table>`（69 个文件用 `DataTable`）
//   · 0 页自己搭弹层（`fixed inset-0`；53 个文件用 `Drawer` / `ConfirmDialog`）
//   · 页面里 0 处写死 hex（126 处里 101 处在 `globals.css` 那份 token 定义里，
//     其余在 mock 数据、测试、文案，还有一条是解释对比度的注释）
//   · 它自己那份 DOM 体检（`app/dev/ui/audit.ts`，10 条规范）跑出来
//     **扫 1341 元素 / 164 可聚焦 · 0 处违规**
//
// **那份 DOM 体检有个结构性问题：只有人打开 `/dev/ui` 点一下才会跑。**
// 它不在任何闸门上 —— 和三份 spec 生成器同一个毛病（见那一笔提交）。
// 用无头浏览器把它挂进 pre-push 太重（那道闸门现在是几十毫秒），
// 所以这里只挑**读源码就能判**的那几条钉住。剩下的（焦点环、层级、对比度）
// 仍然要人去点，这一点如实写在这儿，别让读的人以为全都守住了。
import { readFileSync } from "node:fs";
import { globSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const ROOT = join(import.meta.dirname, "../../..");
const OPS = "ops-web";

/** dev-only 的组件总览页自己就是「把各种形状摆出来」，不受这几条约束 */
const CATALOG = /^ops-web\/app\/dev\/ui\//;

/**
 * 允许写死颜色的地方，**逐个记原因**。
 *
 * `apply-map.tsx`：高德的 `CircleMarker` 画在 canvas 上，吃的是颜色字符串，
 * 读不了 CSS 变量。而且这个蓝点是**故意不跟主色走**的 ——
 * 它要与高德默认的红色钉子一眼分得开（源码上一行的注释写着这条），
 * 换成 `--primary` 的话在 crimson / business 这类红主色皮肤下两者会撞成一个色，
 * 「查重」这件事反而更糊涂。也就是说：这里的写死是**判据的例外，不是欠账**。
 */
const HEX_OK = new Set(["ops-web/app/communities/apply-map.tsx"]);

function pages(): string[] {
  return globSync(`${OPS}/app/**/page.tsx`, { cwd: ROOT });
}

describe("运营端界面纪律", () => {
  it("★ 每个页面都从 ui 层取件，不自己从零搭", () => {
    const bad = pages().filter(
      (f) => !/components\/ui/.test(readFileSync(join(ROOT, f), "utf8")),
    );
    expect(bad, `这些页面一个 ui 层的件都没用上：\n${bad.join("\n")}`).toEqual([]);
  });

  it("★★ 页面里不许写死 hex —— 颜色走 token，否则换肤与明暗都跟不上", () => {
    const bad: string[] = [];
    for (const f of globSync(`${OPS}/app/**/*.tsx`, { cwd: ROOT })) {
      if (CATALOG.test(f) || HEX_OK.has(f)) continue;
      const src = readFileSync(join(ROOT, f), "utf8");
      // 只看代码，不看注释：badge.tsx 里那个 hex 是在解释「为什么不能用它」
      const code = src.replace(/\/\*[\s\S]*?\*\//g, "").replace(/^\s*\/\/.*$/gm, "");
      if (/#[0-9a-fA-F]{6}\b/.test(code)) bad.push(f);
    }
    expect(bad, `写死的颜色不跟皮肤、也不跟明暗：\n${bad.join("\n")}`).toEqual([]);
  });

  it("★★ 页面不许自己搭表格与弹层 —— 库里有 DataTable / Drawer / ConfirmDialog", () => {
    const bad: string[] = [];
    for (const f of globSync(`${OPS}/app/**/*.tsx`, { cwd: ROOT })) {
      if (CATALOG.test(f)) continue;
      const src = readFileSync(join(ROOT, f), "utf8");
      if (/<table\b/.test(src)) bad.push(`${f} → 自己搭了 <table>，用 DataTable`);
      // 自己搭的遮罩层：铺满视口 + 定位。库里的 Drawer/ConfirmDialog 已经处理了
      // 焦点陷阱、Esc、滚动锁与层级 —— 手搭的那份一样都不会有
      if (/fixed\s+inset-0/.test(src)) bad.push(`${f} → 自己搭了遮罩，用 Drawer / ConfirmDialog`);
    }
    expect(bad, bad.join("\n")).toEqual([]);
  });
});
