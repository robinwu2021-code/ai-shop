// 词条表与代码的**双向**对账：用了没有的 key、有了没人用的 key。
//
// 已有的 `i18n-keys-exist.test.ts` 只管前一半，而且只走 `.vue`——
// 写在 `.ts` 里的 `t("x.y")`（stores、nav、api 的错误文案）它看不到。
// 这条把两个方向都盯上，扫 `.vue` 与 `.ts`。
//
// **为什么后一半也值得一道闸**（2026-08-29 加）：
// 全站盘点时发现 165 条词条没有任何人引用，其中 `customers.*` 14 条、
// `fulfillmentReach.*` 3 条整个命名空间都没人碰——是页面重写后留下的。
// 它们的害处不在体积，在于**改稿时分不清哪条还在生效**：
// 改了半天线上没变，或者以为改全了其实漏了在用的那条。
//
// 判据能成立，靠的是一件事：**这个仓库里没有一处把 key 拼出来**。
// 没有 `t("goods." + x)`，也没有 `` t(`${ns}.title`) ``——
// 变量当 key 的地方（`$t(l.key)`、`labelKey`）指向的都是源码里的字面量常量，
// 照样被扫到。真要写出拼接式的 key，这条闸就不再可靠，那时先来改它。
//
// 用法：
//   node scripts/check-i18n-orphan.mjs           # 列出来
//   node scripts/check-i18n-orphan.mjs --check   # 超出基线就非零退出
import { readFileSync, readdirSync, existsSync, statSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const APPS = [
  { app: "b-app", locale: "b-app/src/i18n/locale/zh-CN.ts", src: ["b-app/src"] },
  { app: "c-app", locale: "c-app/src/i18n/locale/zh-CN.ts", src: ["c-app/src"] },
];
// 两端共用的外壳与工具层：里面的 t("common.x") 对两端都算「用过」
const SHARED = ["packages/ui/src", "packages/shared/src"];
const BASELINE = join(ROOT, "known-orphan-i18n.txt");

/** 词条表里的全部 key（嵌套、值在下一行的都算） */
function keysOf(file) {
  const src = readFileSync(file, "utf8");
  const out = new Set();
  const stack = [];
  let depth = 0;
  for (const line of src.split("\n")) {
    const clean = line.replace(/\/\/.*$/, "");
    for (const m of clean.matchAll(/([A-Za-z_]\w*)\s*:\s*\{/g)) stack.push([depth, m[1]]);
    for (const m of clean.matchAll(/([A-Za-z_]\w*)\s*:\s*(?!\{)(\S|$)/g)) {
      const nxt = m[2] ?? "";
      if (nxt === "" || `"'\``.includes(nxt) || clean.trimEnd().endsWith(":")) {
        out.add([...stack.map(([, n]) => n), m[1]].join("."));
      }
    }
    depth += (clean.match(/\{/g)?.length ?? 0) - (clean.match(/\}/g)?.length ?? 0);
    while (stack.length && stack[stack.length - 1][0] >= depth) stack.pop();
  }
  return out;
}

function sources(dirs) {
  const out = [];
  const walk = (d) => {
    if (!existsSync(d)) return;
    for (const n of readdirSync(d)) {
      if (n === "node_modules" || n === "dist" || n.startsWith(".")) continue;
      const p = join(d, n);
      if (statSync(p).isDirectory()) walk(p);
      else if (/\.(vue|ts)$/.test(n) && !p.includes("/i18n/locale/")) out.push(p);
    }
  };
  for (const d of dirs) walk(join(ROOT, d));
  return out;
}

/**
 * 源码里出现过的 key 字面量，以及 `` t(`a.b.${x}`) `` 这类动态前缀。
 *
 * <p><b>模板与脚本要分开扫</b>：`:key="tab.key"` 这种属性值在正则眼里
 * 和一个字符串字面量长得一模一样，整片扫下来会凭空多出几十个「缺失的 key」
 * （`tab.key`、`order.receiver.phone`、`plan.skips.length`…）。
 * 所以模板里只认 `$t("…")` 与 `title-key="…"`，脚本里才认所有字面量 ——
 * 后者必须认全，因为 `labelKey: "goods.x"` 这种间接引用就藏在那儿。
 */
// `` t(`apply.status${x}`) `` 这种**不带点**的拼接一样是动态前缀。
// 早一版的正则要求前缀以 `.` 结尾，于是 `activityEdit.step${n}`、`afterSale.type${t}`、
// `apply.qual${c}` 名下的词条被整片误报成孤儿 —— 差点当成死词条删掉。
const DYNAMIC = /`([a-zA-Z][\w.]*?)\$\{/g;
const LITERAL = /["'`]([a-zA-Z][\w]*(?:\.[\w]+)+)["'`]/g;
/*
 * 模板里怎么算「引用」，是这条闸最容易写错的地方，前后错了三次：
 *
 *   1. 整片按字面量扫 —— `:key="tab.key"` 这种属性值和字符串长得一样，
 *      于是凭空多出几十个「缺失的 key」。
 *   2. 只认紧跟 `t(` 的那一个 —— `$t(a ? "x.y" : "x.z")` 整条漏掉。
 *   3. 用括号配对去截 `t(...)` 的参数 —— 嵌套两层就截不住
 *      （`$t("goods.untranslated", { s: xs.map((k) => $t(k)).join("、") })`）。
 *
 * 现在的判据换了个角度，不去理解表达式，只看**引号**：
 * Vue 模板里属性值用双引号，所以表达式内部的字符串只能是单引号或反引号；
 * 而 `:key="tab.key"` 那个双引号是**紧跟在 `=` 后面**的属性值本身。
 * 于是：单引号一律算，双引号只在不是紧跟 `=` 时算。
 */
const T_SINGLE = /'([a-zA-Z][\w]*(?:\.[\w]+)+)'/g;
const T_DOUBLE = /([^=\s])\s*"([a-zA-Z][\w]*(?:\.[\w]+)+)"/g;
const TITLE_KEY = /title-key=["']([\w.]+)["']/g;

/** 注释里出现的 key 不算引用 —— 这份仓库的注释里写满了 `order.merchantNo` 这类路径 */
function stripComments(s) {
  return s
    .replace(/\/\*[\s\S]*?\*\//g, " ")
    .replace(/(^|[^:"'`\\])\/\/[^\n]*/g, "$1")
    .replace(/<!--[\s\S]*?-->/g, " ");
}

function refsOf(files) {
  const keys = new Set();
  const prefixes = new Set();
  for (const f of files) {
    const src = stripComments(readFileSync(f, "utf8"));
    let script = src;
    if (f.endsWith(".vue")) {
      const parts = [...src.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/g)].map((m) => m[1]);
      script = parts.join("\n");
      const tpl = src.replace(/<script[^>]*>[\s\S]*?<\/script>/g, "");
      for (const m of tpl.matchAll(TITLE_KEY)) keys.add(m[1]);
      for (const m of tpl.matchAll(T_SINGLE)) keys.add(m[1]);
      for (const m of tpl.matchAll(T_DOUBLE)) keys.add(m[2]);
      for (const m of tpl.matchAll(DYNAMIC)) prefixes.add(m[1]);
    }
    for (const m of script.matchAll(LITERAL)) keys.add(m[1]);
    for (const m of script.matchAll(DYNAMIC)) prefixes.add(m[1]);
  }
  return { keys, prefixes };
}

const known = existsSync(BASELINE)
  ? new Set(
      readFileSync(BASELINE, "utf8")
        .split("\n")
        .map((l) => l.trim())
        .filter((l) => l && !l.startsWith("#")),
    )
  : new Set();

let missing = [];
let orphan = [];
for (const { app, locale, src } of APPS) {
  const file = join(ROOT, locale);
  if (!existsSync(file)) continue;
  const defined = keysOf(file);
  const { keys, prefixes } = refsOf(sources([...src, ...SHARED]));
  // 「缺 key」只看本端自己的源码：两端共用的常量表里躺着对方的 key
  //（c-app 的 `tab.cart` 出现在 packages/shared，b-app 词条里当然没有），
  // 拿共用层去比会凭空报一堆缺失。共用组件那一侧由 i18n-keys-exist 盯着。
  const own = refsOf(sources(src)).keys;
  for (const k of own) {
    // 只对「看起来像本端词条」的字面量下判断：命名空间必须在词条表里存在，
    // 否则会把 `api/goods.list`、`vue-i18n` 这类字符串一并算进来
    const ns = k.split(".")[0];
    if (![...defined].some((d) => d.startsWith(ns + "."))) continue;
    if (!defined.has(k)) missing.push(`${app} ${k}`);
  }
  for (const d of defined) {
    if (keys.has(d)) continue;
    if ([...prefixes].some((p) => d.startsWith(p))) continue;
    orphan.push(`${app} ${d}`);
  }
}

const freshOrphan = orphan.filter((o) => !known.has(o));
console.log(`词条对账：用了但没有 ${missing.length}｜有了但没人用 ${orphan.length}（已知欠账 ${known.size}）`);
for (const m of missing) console.log(`   ✗ 缺 ${m}`);
for (const o of freshOrphan) console.log(`   ★新增 ${o}`);

const stale = [...known].filter((k) => !orphan.includes(k));
if (stale.length) {
  console.log(`\n✅ 这 ${stale.length} 条已经有人用了（或已删掉），把它们从基线里删掉：`);
  for (const k of stale) console.log(`      ${k}`);
}

if (process.argv.includes("--check")) {
  let bad = false;
  if (missing.length) {
    console.error(`\n✗ ${missing.length} 个 key 在代码里被引用、词条表里却没有。`);
    console.error("  ⚠️ 这类问题不报错：那一格会把 key 本身原样显示给用户。");
    bad = true;
  }
  if (freshOrphan.length) {
    console.error(`\n✗ 新增了 ${freshOrphan.length} 条没人引用的词条。`);
    console.error("  要么接上，要么删掉，要么登记进 known-orphan-i18n.txt 并写明为什么。");
    bad = true;
  }
  if (stale.length) {
    console.error(`\n✗ 基线里有 ${stale.length} 条已经不是孤儿了，删掉它们。`);
    bad = true;
  }
  if (bad) process.exit(1);
}
