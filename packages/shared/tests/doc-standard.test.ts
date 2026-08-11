// docs/文档规范.md 的可执行部分。
//
// 规范写在文档里只是一句主张 —— 没人会在改文档时回头读它。
// 这里把其中「机器能判」的三条固化成断言，让违反规范的提交直接红掉：
//
//   1. 不用 mermaid       —— 语法写错整张图静默不渲染，本仓库为此丢过图
//   2. 图不能断链         —— md 引的 svg 必须真的在磁盘上
//   3. 生成物不手改       —— 带「勿手改」抬头的文件必须真的由某个 gen 脚本产出
//   4. md 之间不断链      —— 目录重整时最容易碎的就是相对路径，而断链不报错
//   5. 每篇都在索引里     —— 重整前有 28 篇文档没有任何入口，等于不存在
//
// 剩下的（四层递进、每条设计写「防住什么」）判不了，靠评审。
import { readFileSync, readdirSync, existsSync, statSync } from "node:fs";
import { join, dirname, resolve } from "node:path";
import { describe, expect, it } from "vitest";

const ROOT = join(import.meta.dirname, "../../..");
const DOCS = join(ROOT, "docs");

/** 递归收集 docs 下的 .md */
function mdFiles(dir: string, out: string[] = []): string[] {
  for (const e of readdirSync(dir)) {
    const p = join(dir, e);
    if (statSync(p).isDirectory()) mdFiles(p, out);
    else if (e.endsWith(".md")) out.push(p);
  }
  return out;
}

const FILES = mdFiles(DOCS);
const rel = (p: string) => p.slice(ROOT.length + 1);

describe("文档规范", () => {
  it("有文档可查", () => {
    expect(FILES.length).toBeGreaterThan(20);
  });

  it("不使用 mermaid —— 写错一个字符整张图不显示，而且不报错", () => {
    // 只认**行首**的围栏。规范自己要在表格里引用 `` ```mermaid `` 这个字面量
    // 来说明查的是什么，那是行内 code，不该被自己判死。
    const bad = FILES.filter((f) => /^```mermaid/m.test(readFileSync(f, "utf8"))).map(rel);
    expect(bad).toEqual([]);
  });

  it("图不断链：md 引用的 svg 必须存在", () => {
    const bad: string[] = [];
    for (const f of FILES) {
      const src = readFileSync(f, "utf8");
      // 骨架示例写在围栏块里（`![总览图](./diagrams/xxx.svg)`），不是真引用
      // 规范里的骨架示例既有围栏块，也有表格里的行内 code
      // （`` `![说明](./diagrams/xxx.svg)` ``），两种都不是真引用
      const body = src.replace(/```[\s\S]*?```/g, "").replace(/`[^`\n]*`/g, "");
      for (const m of body.matchAll(/!\[[^\]]*]\(([^)]+\.svg)\)/g)) {
        const target = resolve(dirname(f), m[1]);
        if (!existsSync(target)) bad.push(`${rel(f)} → ${m[1]}`);
      }
    }
    expect(bad).toEqual([]);
  });

  it("每张 svg 都有 title 与 desc —— 图之外要有能读的说明", () => {
    const svgs = collectSvg(DOCS);
    expect(svgs.length).toBeGreaterThan(10);
    const bad = svgs.filter((f) => {
      const s = readFileSync(f, "utf8");
      return !/<title>[^<]+<\/title>/.test(s) || !/<desc>[^<]+<\/desc>/.test(s);
    }).map(rel);
    expect(bad).toEqual([]);
  });

  it("标了「勿手改」的文档确实有生成脚本", () => {
    const pkg = JSON.parse(readFileSync(join(ROOT, "package.json"), "utf8"));
    const scripts = Object.keys(pkg.scripts ?? {});
    const bad: string[] = [];
    for (const f of FILES) {
      const head = readFileSync(f, "utf8").slice(0, 400);
      if (!head.includes("勿手改")) continue;
      // 两种写法都算数：npm 脚本名，或直接写脚本路径（部分生成器是 python，没进 package.json）
      //
      // 路径那条要容忍 `node xxx.mjs` / `python3 xxx.py` 这种**带解释器前缀**的写法 ——
      // 原先的字符类不含空格，于是「由 `node scripts/gen-tri-end-matrix.mjs` 生成」
      // 匹配不上，守卫报「没有生成脚本」，而脚本一直好好地在那儿。
      // **守卫误报比不报更坏**：它会让人去删一句正确的抬头。
      const viaNpm = head.match(/由\s*`npm run ([\w:-]+)`/);
      const viaPath = head.match(/由\s*`(?:node |python3? |npx tsx )?([\w./-]+\.(?:mjs|py|ts))`/);
      const ok =
        (viaNpm && scripts.includes(viaNpm[1])) ||
        (viaPath && existsSync(join(ROOT, viaPath[1])));
      if (!ok) bad.push(rel(f));
    }
    expect(bad).toEqual([]);
  });

  /*
   * md 之间不断链。
   *
   * svg 那条（上面）守的是图，这条守的是**文档之间的路**：docs/technical 从 108 篇
   * 平铺重整成四层目录时，一次动了 375 条相对链接 —— 而链接失效不会有任何报错，
   * 只会让点进去的人得到 404，然后他改去问人。
   */
  it("md 引用的 md 必须存在 —— 目录重整最容易碎的就是这个，而且不报错", () => {
    const broken: string[] = [];
    for (const f of FILES) {
      const body = readFileSync(f, "utf8");
      for (const m of body.matchAll(/\]\((\.{1,2}\/[^)]+?\.md)(#[^)]*)?\)/g)) {
        const target = resolve(dirname(f), m[1]!);
        if (!existsSync(target)) broken.push(`${rel(f)} → ${m[1]}`);
      }
    }
    expect(broken, "这些 md 链接指向不存在的文件：\n  " + broken.join("\n  ")).toEqual([]);
  });

  /*
   * 每篇技术文档都要在索引里。
   *
   * 重整前 docs/technical 下有 **28 篇孤儿**（没有任何文档链接到它们），
   * 其中 17 篇是仍然有效的运营端逐模块设计 —— 写了、维护着、但没人找得到。
   * 索引不是目录美化，它是「这篇文档还算不算数」的唯一入口。
   */
  it("technical 下每篇文档都出现在 README 索引里 —— 不在索引里的文档等于不存在", () => {
    const indexPath = join(DOCS, "technical/README.md");
    const index = readFileSync(indexPath, "utf8");
    const missing = FILES
      .filter((f) => rel(f).startsWith("docs/technical/"))
      .filter((f) => !/(README|ADR\/)/.test(rel(f)))
      .map((f) => f.split("/").pop()!)
      .filter((name) => !index.includes(name));
    expect(missing, "这些文档没有登记进 docs/technical/README.md：\n  " + missing.join("\n  ")).toEqual([]);
  });

  /*
   * 无状态行的文档只许减不许增。
   *
   * 规范 §四要求方案类必须有状态行，而重整时有 56 篇没有 —— 一次性补齐要逐篇确认
   * 它到底落地没有，那是评审的事，不是脚本能编的。所以设成**基线只降不升**：
   * 存量慢慢补，新增的一篇都跑不掉。
   */
  it("没有状态行的文档不许再增加（基线只降不升）", () => {
    const NO_STATUS_BASELINE = 56;
    const n = FILES
      .filter((f) => rel(f).startsWith("docs/technical/") && !rel(f).includes("/ADR/"))
      // 索引自己不是方案文档，不要求状态行
      .filter((f) => !f.endsWith("README.md"))
      .filter((f) => !/状态[：:]/.test(readFileSync(f, "utf8").split("\n").slice(0, 8).join("\n")))
      .length;
    expect(n, `无状态行的文档 ${n} 篇，基线 ${NO_STATUS_BASELINE} —— 新增文档必须写状态行（文档规范 §四）`)
      .toBeLessThanOrEqual(NO_STATUS_BASELINE);
  });
});

function collectSvg(dir: string, out: string[] = []): string[] {
  for (const e of readdirSync(dir)) {
    const p = join(dir, e);
    if (statSync(p).isDirectory()) collectSvg(p, out);
    else if (e.endsWith(".svg")) out.push(p);
  }
  return out;
}
