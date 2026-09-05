// 词汇类清单的共用真源读取。
//
// **为什么单独一个 lib**：中英对照（词条 / 实体与字典）、静态常量、依赖三份清单
// 都要从同一批文件里抽东西 —— 三份各写一套解析器，最迟在第二次改结构时就会分叉，
// 而分叉的症状是「两份清单说的不是一回事」，读的人无从判断信哪份。
// `scripts/lib/ddl.mjs` 已经是这条路上的先例（表清单与 ER 图共用）。
//
// 只用 node 内置模块：pre-push 的闸门跑在 HEAD 的干净副本里，那里没有 node_modules。
import { readFileSync, readdirSync, existsSync, statSync } from "node:fs";
import { join, relative, sep } from "node:path";

/** 走一棵目录树，回符合 `pred` 的文件（相对 root 的路径）。 */
export function walk(root, dir, pred, skip = () => false) {
  const out = [];
  const rec = (d) => {
    let ents;
    try { ents = readdirSync(d, { withFileTypes: true }); } catch { return; }
    for (const e of ents.sort((a, b) => (a.name < b.name ? -1 : 1))) {
      const p = join(d, e.name);
      if (skip(relative(root, p))) continue;
      if (e.isDirectory()) rec(p);
      else if (pred(p)) out.push(relative(root, p));
    }
  };
  if (existsSync(dir)) rec(dir);
  return out;
}

/**
 * 后端 main 源码里的 java 文件。
 *
 * 排除 `target/`（构建产物里有一份一模一样的，算两遍会让所有计数翻倍）
 * 与 `src/test/`（测试里的常量不是词汇，是夹具）。
 */
export function javaMainFiles(root) {
  return walk(
    root,
    join(root, "backend"),
    (p) => p.endsWith(".java"),
    (rel) => {
      const parts = rel.split(sep);
      return parts.includes("target") || rel.includes(`src${sep}test`);
    },
  );
}

/** 该文件属于哪个 Maven 模块（`backend/` 下第一段，pay 下取两段）。 */
export function moduleOf(rel) {
  const parts = rel.split(sep);
  if (parts[1] === "pay") return `pay/${parts[2]}`;
  return parts[1];
}

/**
 * 把 javadoc / 行注释块压成一行中文说明。
 *
 * 只取**第一句**：这些注释里成段的「为什么」是写给读代码的人的，
 * 抄进清单会让每一行都长到看不成表。第一句恰好是「这是什么」。
 */
export function firstSentence(doc) {
  if (!doc) return "";
  let s = doc
    .replace(/\r/g, "")
    .split("\n")
    .map((l) => l.replace(/^\s*\*\/?/, "").replace(/^\s*\/\*+/, "").replace(/^\s*\/\/+/, "").trim())
    .join(" ")
    .replace(/\{@link\s+#?([^}]*)\}/g, "$1")
    .replace(/\{@code\s+([^}]*)\}/g, "$1")
    .replace(/<\/?[a-z]+>/gi, "")
    .replace(/&nbsp;/g, " ")
    .replace(/\s+/g, " ")
    .trim();
  const cut = s.search(/[。！？]|\.\s|；/);
  if (cut > 0) s = s.slice(0, cut + 1).replace(/[.；]$/, "。");
  return s.replace(/\|/g, "\\|").trim();
}

/**
 * 紧贴在 `idx` 之前的注释块（javadoc、块注释或连续行注释）。
 *
 * **必须是「紧贴」的**：中间隔着代码就不算。
 * 第一版写成 `/\*\*([\s\S]*?)\*\/\s*$`，惰性组会一路吞过 `*​/` 与中间的代码，
 * 于是**从文件里第一个 javadoc 开始匹配**——`BizKey` 的 25 个常量全都挂上了
 * 类注释「业务键生成：前缀 + yyyyMMddHHmmss…」。症状是清单看起来填满了，
 * 而每一行说的都是同一句与它无关的话，比留空更糟。
 */
export function docBefore(src, idx) {
  /*
   * 注解与修饰符不算「隔着代码」：javadoc 与它说明的那个东西之间，
   * 合法地隔着 `@Data @TableName(...)` 或 `public final`。
   * **漏掉修饰符这一档的代价实测过**：`enum ErrorCode` 前面隔着一个 `public`，
   * 于是 13 个 Java 枚举里 8 个的中文说明是空的 —— 而它们每一个都写了 javadoc。
   */
  let head = src.slice(0, idx);
  for (;;) {
    const next = head
      .replace(/\s+$/, "")
      .replace(/@\w+(?:\s*\((?:[^()]|\([^()]*\))*\))?$/, "")
      .replace(/\b(?:public|protected|private|static|final|abstract|sealed|non-sealed|default)$/, "");
    if (next === head) break;
    head = next;
  }
  if (head.endsWith("*/")) {
    const start = Math.max(head.lastIndexOf("/**"), head.lastIndexOf("/*"));
    if (start >= 0) return head.slice(start, head.length - 2).replace(/^\/\*+/, "");
    return "";
  }
  const line = head.match(/((?:[ \t]*\/\/[^\n]*\n)+)\s*$/);
  if (line) return line[1];
  return "";
}

/** markdown 表格里安全的一格。 */
export function cell(s) {
  return String(s ?? "").replace(/\|/g, "\\|").replace(/\n/g, " ").trim();
}

/** 读文件，读不到回空串（清单生成器不该因为少一个可选真源就崩）。 */
export function read(p) {
  try { return readFileSync(p, "utf8"); } catch { return ""; }
}

/** 目录存在且是目录。 */
export function isDir(p) {
  try { return statSync(p).isDirectory(); } catch { return false; }
}
