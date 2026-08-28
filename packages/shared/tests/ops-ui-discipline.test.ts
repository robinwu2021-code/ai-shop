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
//
// ⚠️ **2026-08-28 订正一条我自己报错的结论。** 上面这份盘点里原本写着
// 「它自己那份 DOM 体检（`app/dev/ui/audit.ts`）扫 1341 元素 / 164 可聚焦 · **0 处违规**」。
// 那个 0 是真的，但**覆盖面不是整个运营端**：`audit.ts` 的 `specimens()` 只取
// `[data-specimen]` 盒子里的元素 —— 也就是 `/dev/ui` 那个**组件画廊**，
// **不是那 24 个真实页面**。我把「画廊干净」讲成了「运营端干净」。
//
// 照它同一套判据去扫真实页面，当场 **62 处裸可聚焦元素没有焦点环**
//（58 个原生标签 + 4 个 Next `<Link>`）。而 Tailwind preflight 把浏览器默认焦点环
// 抹掉了、`globals.css` 里又没有全局兜底 —— 实测 `outline: none` 且 `box-shadow: none`，
// **键盘聚焦完全无痕**。62 处已全部补上 `focus-ring`，浏览器逐页复验 0 无环。
//
// 「一个只覆盖一半的清单，最危险的不是漏，是它把『没查过』呈现成『查过了没问题』」——
// 这句话就写在 `known-handrolled-ui.txt` 顶上，而我这次栽在自己引用过的那句话上。
//
// **那份 DOM 体检还有个结构性问题：只有人打开 `/dev/ui` 点一下才会跑。**
// 它不在任何闸门上 —— 和三份 spec 生成器同一个毛病（见那一笔提交）。
// 用无头浏览器把它挂进 pre-push 太重（那道闸门现在是几十毫秒），
// 但 `audit.ts` 十条规则里有六条**读的是 class 字符串、不是 computed 样式** ——
// 那些根本不需要浏览器，直接从 JSX 源码就能判。下面把它们静态化。
// 真正还得靠人点的只剩**对比度**与**圆角实测值**（那两条要 computed style）。
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

const FOCUSABLE_TAGS = ["button", "input", "select", "textarea", "a", "Link"];
const HAS_RING = /(^|[\s"'`])focus-ring([\s"'`]|$)|focus-visible:(ring|outline-|shadow)/;

/**
 * 扫出 JSX 里 `<tag ...>` 的属性文本。
 *
 * **必须认花括号与引号**：用 `<tag[^>]*>` 这种写法，`onClick={() => x}` 里的 `>`
 * 会把属性文本从中间截断，于是 `className` 读成空 —— 而空 className 恰好等于
 * 「没有焦点环」，误报一大片。写这道闸时就是这样先错了一版。
 * 顺带去掉注释：`// 原生 <input type="date">` 这种会被当成真元素。
 */
function jsxElements(src: string, tags: string[]) {
  const clean = src.replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, " "))
                   .replace(/(^|[^:])\/\/[^\n]*/g, (m) => m.replace(/[^\n]/g, " "));
  const out: { tag: string; attrs: string; line: number }[] = [];
  for (const m of clean.matchAll(new RegExp(`<(${tags.join("|")})(?=[\\s/>])`, "g"))) {
    let i = m.index! + m[0].length, depth = 0, q: string | null = null;
    while (i < clean.length) {
      const c = clean[i];
      if (q) { if (c === q && clean[i - 1] !== "\\") q = null; }
      else if (c === '"' || c === "'" || c === "`") q = c;
      else if (c === "{") depth++;
      else if (c === "}") depth--;
      else if (c === ">" && depth === 0) break;
      i++;
    }
    out.push({ tag: m[1], attrs: clean.slice(m.index! + m[0].length, i),
               line: clean.slice(0, m.index).split("\n").length });
  }
  return out;
}

/** 取 className= 的值（含 `{cn(...)}` 这种表达式，按花括号配对） */
function classAttr(attrs: string): string {
  const m = /className=/.exec(attrs);
  if (!m) return "";
  const i = m.index + m[0].length;
  if (attrs[i] === '"') { const j = attrs.indexOf('"', i + 1); return j > 0 ? attrs.slice(i + 1, j) : ""; }
  if (attrs[i] === "{") {
    let d = 0;
    for (let j = i; j < attrs.length; j++) {
      if (attrs[j] === "{") d++;
      else if (attrs[j] === "}" && --d === 0) return attrs.slice(i, j + 1);
    }
  }
  return "";
}

/** 在所有 className 字面量里找命中；给了 tags 就只看那些标签上的 */
function scanClasses(re: RegExp, keep: (m: RegExpMatchArray) => boolean, tags?: string[]): string[] {
  const bad: string[] = [];
  for (const f of globSync(`${OPS}/**/*.tsx`, { cwd: ROOT })) {
    if (CATALOG.test(f) || f.includes("node_modules")) continue;
    const src = readFileSync(join(ROOT, f), "utf8");
    const els = jsxElements(src, tags ?? ["[A-Za-z][\\w.]*"]);
    for (const el of els) {
      for (const m of classAttr(el.attrs).matchAll(re)) {
        if (keep(m)) bad.push(`${f}:${el.line} <${el.tag}> ${m[0].trim()}`);
      }
    }
  }
  return bad;
}

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

  /*
   * 下面四条**照抄 `ops-web/app/dev/ui/audit.ts` 的判据**，不另立一套 ——
   * 两处判据用两套口径，迟早对不上（这个仓库栽过）。那边读运行时的 class 字符串，
   * 这边读 JSX 源码里的 class 字面量，规则本身逐字一致。
   *
   * **覆盖面要说清楚**：静态扫只看得见 `className` 里的**字面量**部分。
   * 类名如果整个由变量拼出来（`className={someVar}`），这里看不到 —— 那种情况少，
   * 但存在。所以这几条是「拦住新写的违规」，不是「证明一处都没有」。
   */
  it("★★★ 可聚焦元素必须有焦点环 —— Tailwind preflight 抹掉了浏览器默认环，没有兜底", () => {
    const bad: string[] = [];
    for (const f of globSync(`${OPS}/**/*.tsx`, { cwd: ROOT })) {
      if (CATALOG.test(f) || f.includes("node_modules")) continue;
      for (const el of jsxElements(readFileSync(join(ROOT, f), "utf8"), FOCUSABLE_TAGS)) {
        if (el.tag === "a" && !/\bhref\b/.test(el.attrs)) continue;
        // `<Link>` 渲染成 <a>，同样可聚焦。第一版漏了它，浏览器里还剩 33 个无环
        if (el.attrs.includes("data-audit-skip")) continue;
        if (HAS_RING.test(classAttr(el.attrs))) continue;
        bad.push(`${f}:${el.line} <${el.tag}>`);
      }
    }
    expect(bad, `这些元素键盘聚焦时完全无痕（加 focus-ring）：\n  ${bad.join("\n  ")}`).toEqual([]);
  });

  it("★★ 浮层层级走 z-[var(--z-…)]，不写死数字", () => {
    const bad = scanClasses(/(?:^|\s)z-\[?(\d+)\]?(?:\s|$)/g, (m) => Number(m[1]) >= 20);
    expect(bad, `写死的层级早晚互相压：\n  ${bad.join("\n  ")}`).toEqual([]);
  });

  it("★★ 阴影只有 shadow-card / shadow-pop 两档", () => {
    const bad = scanClasses(/(?:^|\s)shadow-(sm|md|lg|xl|2xl|inner)(?:\s|$)/g, () => true);
    expect(bad, `不在档上的阴影：\n  ${bad.join("\n  ")}`).toEqual([]);
  });

  it("★★ 过渡时长走 var(--dur)，控件高走 var(--ctl-h) —— 否则密度与动效开关失效", () => {
    const bad = [
      ...scanClasses(/(?:^|\s)duration-(\d+)(?:\s|$)/g, () => true),
      ...scanClasses(/(?:^|\s)h-(8|9|10|11)(?:\s|$)/g, () => true, FOCUSABLE_TAGS),
    ];
    expect(bad, `写死的时长/控件高：\n  ${bad.join("\n  ")}`).toEqual([]);
  });
});
