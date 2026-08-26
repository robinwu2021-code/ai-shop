// 字阶规范测试。
//
// **背景**：c-app 曾有 33 个不同字号（19–104rpx），字重只声明过 500/600/700
// —— 600 与 700 合计 97 处、500 仅 1 处，也就是全站没有一处显式的常规字重。
// 结果首页 ≥12px 的文本里 76% 是粗体：读者眼里没有轻重之分，等于全都不重要。
//
// 字阶立在 packages/ui/src/styles/base.css（.txt-* 八个类、七档字号），
// 规则一句话：**700 只给价格，600 只给标题与按钮，其余一律 400**。
// 下面把这条规则里能机器判定的部分变成断言 —— 注释拦不住第 28 个页面。
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const ROOT = join(import.meta.dirname, "../../..");
const BASE_CSS = join(ROOT, "packages/ui/src/styles/base.css");
const VUE_ROOTS = ["c-app", "b-app", "packages/ui"];

/** 字阶允许的字号（rpx）。改这里之前先想清楚：多一档就是多一次「这两个到底差在哪」。 */
const SCALE = [24, 26, 28, 30, 34, 40, 48];

/**
 * 豁免：与「文字排版」无关的字号。
 *
 * 这些选择器里的 font-size 撑的是**图形**不是文字 —— emoji 当占位图用时，
 * 字号就是图的尺寸（`.rv__img` 是评价配图、`.fly__text` 是加购飞入的那个 emoji）。
 * 把它们并进字阶，等于让「图多大」去迁就「字多大」。
 * 真实图片上线后这些会换成 <image>，豁免项也就随之消失。
 */
const NON_TEXT = /(?:cover|icon|emoji|avatar|logo|badge__n|sign|grip|dot|__img\b|fly__)/i;

function walk(dir: string, out: string[] = []): string[] {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) walk(p, out);
    else if (p.endsWith(".vue")) out.push(p);
  }
  return out;
}

const vueFiles = VUE_ROOTS.flatMap((r) => walk(join(ROOT, r, "src")));

function styleBlocks(src: string): string {
  return [...src.matchAll(/<style[^>]*>([\s\S]*?)<\/style>/g)].map((m) => m[1]!).join("\n");
}

/** 把样式块按「选择器 { 声明 }」切开，好把违规定位到具体的类名。 */
function rules(css: string): { sel: string; body: string }[] {
  return [...css.matchAll(/([^{}]+)\{([^{}]*)\}/g)].map((m) => ({
    sel: m[1]!.trim().replace(/\s+/g, " "),
    body: m[2]!,
  }));
}

const rel = (f: string) => f.slice(ROOT.length + 1);

describe("字阶", () => {
  it("有文件可扫（否则下面全是空转）", () => {
    expect(vueFiles.length).toBeGreaterThan(20);
  });

  it("base.css 里八个 .txt-* 类齐全 —— 它们是字阶的唯一落点", () => {
    const css = readFileSync(BASE_CSS, "utf8");
    const missing = ["hero", "display", "price", "title", "strong", "body", "sub", "caption"]
      .filter((n) => !new RegExp(`\\.txt-${n}\\s*\\{`).test(css));
    expect(missing, `base.css 缺这几档：${missing.join(", ")}`).toEqual([]);
  });

  it("页面与组件的字号必须落在字阶上（图标/emoji 占位除外）", () => {
    const offenders: string[] = [];
    for (const f of vueFiles) {
      for (const r of rules(styleBlocks(readFileSync(f, "utf8")))) {
        if (NON_TEXT.test(r.sel)) continue;
        for (const m of r.body.matchAll(/font-size:\s*(\d+(?:\.\d+)?)rpx/g)) {
          const v = Number(m[1]);
          if (!SCALE.includes(v)) offenders.push(`${rel(f)}  ${r.sel}  ${v}rpx`);
        }
      }
    }
    expect(offenders, `不在字阶（${SCALE.join("/")}rpx）上的字号：\n${offenders.join("\n")}`).toEqual([]);
  });

  it("**700 只给价格** —— 别的东西要突出，靠颜色和留白，不靠再加一道粗体", () => {
    const offenders: string[] = [];
    for (const f of vueFiles) {
      for (const r of rules(styleBlocks(readFileSync(f, "utf8")))) {
        if (!/font-weight:\s*(700|800|900|bold)/.test(r.body)) continue;
        // 价格类选择器：price / now / amount / total / 以及金额专用的 sh-num。
        // **`amt` 与 `due` 是 2026-08-26 补的**：income / points 的 `.amt`、
        // order 的 `.due`（应收）都是 `money(...)` 渲染出来的金额，而名单只认全称，
        // 于是四处真价格被当成违规报了出来。**一条报四个假的断言等于没有断言** ——
        // 真正那一处（sh-sheet 的标题用了 700）就淹在里面。
        if (/(price|__now|amount|amt|due|total|money|sum|fee)\b/i.test(r.sel)) continue;
        if (NON_TEXT.test(r.sel)) continue;
        offenders.push(`${rel(f)}  ${r.sel}`);
      }
    }
    expect(offenders, `这些不是价格，却用了 700：\n${offenders.join("\n")}`).toEqual([]);
  });

  it("字重只有 400 / 600 / 700 三种 —— 500 与 800 属于「差不多但对不上」的那一类", () => {
    const offenders: string[] = [];
    for (const f of vueFiles) {
      for (const r of rules(styleBlocks(readFileSync(f, "utf8")))) {
        for (const m of r.body.matchAll(/font-weight:\s*(\d+)/g)) {
          if (!["400", "600", "700"].includes(m[1]!)) offenders.push(`${rel(f)}  ${r.sel}  ${m[1]}`);
        }
      }
    }
    expect(offenders, `字重不在 400/600/700：\n${offenders.join("\n")}`).toEqual([]);
  });

  // 圆角与字阶是同一类问题：tokens.ts 的注释写着「组件层只许用这五个（规范测试拦截）」，
  // 但此前只校验了 uno.config 与 tokens.ts 一致，**没有任何东西拦页面里的散值** ——
  // 于是实际跑出 12 / 18 / 20 / 22 / 28 / 40 六个档外值共 24 处。
  it("圆角只用 token 五档 —— 差 4rpx 的两个圆角，没人分得出，只会让人各写各的", () => {
    const allowed = [16, 24, 32, 44];
    const offenders: string[] = [];
    for (const f of [...vueFiles, BASE_CSS]) {
      const css = f.endsWith(".css") ? readFileSync(f, "utf8") : styleBlocks(readFileSync(f, "utf8"));
      for (const r of rules(css)) {
        for (const m of r.body.matchAll(/border-radius:\s*(\d+)rpx/g)) {
          if (!allowed.includes(Number(m[1]))) offenders.push(`${rel(f)}  ${r.sel}  ${m[1]}rpx`);
        }
      }
    }
    expect(offenders, `不在圆角五档（${allowed.join("/")}rpx / full）上：\n${offenders.join("\n")}`).toEqual([]);
  });

  it("不许写 letter-spacing —— 同一个类要同时承载中/英/阿三种文字，少一个轴就少三种试错", () => {
    const offenders: string[] = [];
    for (const f of vueFiles) {
      for (const r of rules(styleBlocks(readFileSync(f, "utf8")))) {
        // 正字距是「拉开」，用在验证码、单号这类逐字读的地方，是另一回事
        for (const m of r.body.matchAll(/letter-spacing:\s*(-[\d.]+)/g)) {
          offenders.push(`${rel(f)}  ${r.sel}  ${m[1]}`);
        }
      }
    }
    expect(offenders, `负字距（中文小字号下会让笔画粘连）：\n${offenders.join("\n")}`).toEqual([]);
  });
});
