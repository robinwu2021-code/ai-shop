// docs/文档规范.md 的可执行部分。
//
// 规范写在文档里只是一句主张 —— 没人会在改文档时回头读它。
// 这里把其中「机器能判」的三条固化成断言，让违反规范的提交直接红掉：
//
//   1. 不用 mermaid       —— 语法写错整张图静默不渲染，本仓库为此丢过图
//   2. 图不能断链         —— md 引的 svg 必须真的在磁盘上
//   3. 生成物不手改       —— 带「勿手改」抬头的文件必须真的由某个 gen 脚本产出
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
      const viaNpm = head.match(/由\s*`npm run ([\w:-]+)`/);
      const viaPath = head.match(/由\s*`([\w./-]+\.(?:mjs|py|ts))`/);
      const ok =
        (viaNpm && scripts.includes(viaNpm[1])) ||
        (viaPath && existsSync(join(ROOT, viaPath[1])));
      if (!ok) bad.push(rel(f));
    }
    expect(bad).toEqual([]);
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
