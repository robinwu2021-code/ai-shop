// 全项目词汇清单：**本地化词条 / 实体 / 取值域（字典） / 静态常量 / 依赖**，一次生成四份。
//
// ─────────────────────────────────────────────────────────────────────────────
// 为什么是生成的
// ─────────────────────────────────────────────────────────────────────────────
// 这四类东西此前各有一份手写清单，而**手写清单在这个仓库里无一例外地陈了**：
//   · `Java实体清单.md` 自称「58 实体 · 6 个 Maven 模块」——实测 188 实体 / 17 模块，
//     落后 130 个。它写的那天是对的，之后没有任何东西要求加实体的人回来改它。
//   · 取值域散在三处（Java 常量组 / Java enum / 端上联合类型），
//     `enum-registry.ts` 只登记**端上**那一处，后端那两处从来没有清单。
//   · 静态常量 945 个，没有任何一份清单。
//   · 依赖版本：后端 4 个字面量版本散在模块 pom 里（ehcache 在两处各写一遍），
//     npm 13 个包在 workspace 之间声明了不同的规格。
// 与 `数据库表清单.md` 换成生成的是同一条教训：手写清单的问题不是写的时候不认真，
// 是**它没有变短的机制**。
//
// ─────────────────────────────────────────────────────────────────────────────
// 四份产物
// ─────────────────────────────────────────────────────────────────────────────
//   中英文对照-词条.md        三端 + 后端的 i18n 词条，键 → 中 / 英 / 阿
//   中英文对照-实体与字典.md   实体 ↔ 表 ↔ 中文名；取值域（字典）三处来源合一
//   静态常量清单.md            后端 945 个 static final，按模块 → 类分组
//   依赖清单.md                Maven 与 npm 的版本真源与漂移
//   glossary.json              上面四份的结构化全量（给别的工具读）
//
// ─────────────────────────────────────────────────────────────────────────────
// 用法
// ─────────────────────────────────────────────────────────────────────────────
//   node scripts/gen-glossary.mjs           # 重新生成
//   node scripts/gen-glossary.mjs --check   # 只校验（产物与真源对不上就非零退出）
//
// 闸门挂在 `scripts/check-generated-docs.mjs` 的 GENERATORS 表里 ——
// 那道闸的判据是「跑一遍产物变不变」，比这里的 `--check` 更硬（它不信生成器自称）。
// `--check` 留着是为了本机快速自查，两者不冲突。
//
// 只用 node 内置模块：pre-push 的闸门跑在 HEAD 的干净副本里，那里没有 node_modules。
import { writeFileSync, readFileSync, existsSync, readdirSync } from "node:fs";
import { join, dirname, relative, sep } from "node:path";
import { fileURLToPath } from "node:url";
import { readSchema, MIGRATION_DIR, INVENTORY_MIGRATION_DIR } from "./lib/ddl.mjs";
import {
  walk, javaMainFiles, moduleOf, firstSentence, docBefore, cell, read,
} from "./lib/glossary-sources.mjs";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const OUT_DIR = join(ROOT, "docs/technical/reference");
const CHECK = process.argv.includes("--check");

/**
 * 扫描面断言。
 *
 * **这类清单的失败方式是「少扫」而不是「报错」** —— 目录挪了、后缀变了、
 * 正则被别的改动挤歪了，产物就安静地少一半，而生成器退出码是 0。
 * 已经在这个仓库里付过一次代价（见 `check-enums.mjs` 里 `sharedUnions.length < 20` 那段）。
 * 每一个扫描面都在这里报一个下界，塌了当场炸。
 */
function assertScope(label, n, min) {
  if (n < min) {
    console.error(`✖ ${label} 只扫到 ${n} 条（应有 ${min}+）—— 扫描面塌了，先查真源路径`);
    process.exit(1);
  }
}

// ════════════════════════════════════════════════════════════════════════════
// 一、本地化词条
// ════════════════════════════════════════════════════════════════════════════

/**
 * 三端词条。**直接 import 那份 .ts**（node ≥22 自带类型擦除），
 * 不自己写解析器 —— 手写的解析器与 vue-i18n 真正读到的结构一定会分叉，
 * 而分叉的症状是「清单说有这条、界面上却是裸 key」。
 */
async function loadLocale(rel) {
  const abs = join(ROOT, rel);
  if (!existsSync(abs)) return null;
  const mod = await import(`file://${abs}`);
  // 两种写法都有：c/b 端是 `export default {...}`，ops-web 是 `export const zh = {...}`
  return mod.default ?? mod.zh ?? mod.en ?? Object.values(mod).find((v) => v && typeof v === "object");
}

/** 拍平成 `a.b.c` → 文案。 */
function flatten(obj, prefix = "", out = new Map()) {
  for (const [k, v] of Object.entries(obj ?? {})) {
    const key = prefix ? `${prefix}.${k}` : k;
    if (v && typeof v === "object" && !Array.isArray(v)) flatten(v, key, out);
    else out.set(key, String(v));
  }
  return out;
}

/** `.properties`：后端错误码与提示语。`\uXXXX` 与续行都不用管 —— 本仓库的三份都是直白 UTF-8。 */
function loadProperties(rel) {
  const src = read(join(ROOT, rel));
  const out = new Map();
  for (const line of src.split("\n")) {
    const m = line.match(/^\s*([A-Za-z0-9_.\-]+)\s*=\s*(.*)$/);
    if (m && !line.trimStart().startsWith("#")) out.set(m[1], m[2].trim());
  }
  return out;
}

const I18N_SURFACES = [
  {
    surface: "C 端 · c-app", note: "uni-app，消费者端",
    files: { "zh-CN": "c-app/src/i18n/locale/zh-CN.ts", en: "c-app/src/i18n/locale/en.ts", ar: "c-app/src/i18n/locale/ar.ts" },
  },
  {
    surface: "B 端 · b-app", note: "uni-app，商家端",
    files: { "zh-CN": "b-app/src/i18n/locale/zh-CN.ts", en: "b-app/src/i18n/locale/en.ts", ar: "b-app/src/i18n/locale/ar.ts" },
  },
  {
    surface: "运营端 · ops-web", note: "Next.js，只有中英两语",
    files: { "zh-CN": "ops-web/lib/i18n/messages/zh.ts", en: "ops-web/lib/i18n/messages/en.ts" },
  },
  {
    surface: "后端 · shop-app", note: "服务端下发的提示语与错误文案",
    props: { "zh-CN": "backend/shop-app/src/main/resources/i18n/messages.properties", en: "backend/shop-app/src/main/resources/i18n/messages_en.properties", ar: "backend/shop-app/src/main/resources/i18n/messages_ar.properties" },
  },
];

async function collectI18n() {
  const out = [];
  for (const s of I18N_SURFACES) {
    const langs = {};
    for (const [lang, rel] of Object.entries(s.files ?? {})) {
      const obj = await loadLocale(rel);
      if (obj) langs[lang] = flatten(obj);
    }
    for (const [lang, rel] of Object.entries(s.props ?? {})) {
      langs[lang] = loadProperties(rel);
    }
    const base = langs["zh-CN"];
    if (!base) continue;
    out.push({
      surface: s.surface, note: s.note,
      source: Object.values(s.files ?? s.props ?? {}),
      langs, keys: [...base.keys()].sort(),
    });
  }
  assertScope("本地化词条 · 面", out.length, 4);
  assertScope("本地化词条 · 键", out.reduce((n, s) => n + s.keys.length, 0), 2500);
  return out;
}

// ════════════════════════════════════════════════════════════════════════════
// 二、实体
// ════════════════════════════════════════════════════════════════════════════

function collectEntities(javaFiles) {
  const rows = [];
  for (const rel of javaFiles) {
    const src = read(join(ROOT, rel));
    const at = src.search(/@TableName\(/);
    if (at < 0) continue;
    const table = src.match(/@TableName\(\s*(?:value\s*=\s*)?"([a-z0-9_]+)"/)?.[1];
    if (!table) continue;
    const cls = src.match(/(?:public\s+)?(?:final\s+|abstract\s+)?class\s+([A-Za-z0-9_]+)/)?.[1] ?? "?";
    const pkg = src.match(/^package\s+([\w.]+);/m)?.[1] ?? "";
    rows.push({
      module: moduleOf(rel), cls, table, pkg, file: rel,
      zh: firstSentence(docBefore(src, at)),
    });
  }
  rows.sort((a, b) => (a.table < b.table ? -1 : a.table > b.table ? 1 : 0));
  assertScope("实体", rows.length, 150);
  return rows;
}

// ════════════════════════════════════════════════════════════════════════════
// 三、取值域（字典）
// ════════════════════════════════════════════════════════════════════════════

/**
 * 把 java 源码里的注释与字符串内容抹成空格，**长度逐字符不变**。
 *
 * 为什么要它：下面要靠数花括号判断「这个常量属于哪个内部类」，
 * 而注释与字符串里的花括号会把计数带偏。长度不变是为了抹完之后
 * 下标仍然能对回原文（值要从原文取 —— 抹掉的正是值本身）。
 */
function maskJava(src) {
  const out = src.split("");
  let i = 0;
  const blank = (a, b) => { for (let k = a; k < b; k++) if (out[k] !== "\n") out[k] = " "; };
  while (i < src.length) {
    const two = src.slice(i, i + 2);
    if (two === "//") { const e = src.indexOf("\n", i); const end = e < 0 ? src.length : e; blank(i, end); i = end; continue; }
    if (two === "/*") { const e = src.indexOf("*/", i + 2); const end = e < 0 ? src.length : e + 2; blank(i, end); i = end; continue; }
    if (src[i] === '"' || src[i] === "'") {
      const q = src[i]; let k = i + 1;
      while (k < src.length && src[k] !== q) { if (src[k] === "\\") k++; k++; }
      blank(i + 1, Math.min(k, src.length)); i = Math.min(k + 1, src.length); continue;
    }
    i++;
  }
  return out.join("");
}

/**
 * 常量的宿主类：从常量位置往前找最近一个**还没闭合**的 `class X {`。
 *
 * 直接前向数括号比维护一整张「下标 → 类栈」表短得多，也更容易验：
 * 从 0 扫到 idx，遇 `{` 压栈（把刚见过的类名带上），遇 `}` 弹栈，
 * 栈顶带名字的那一层就是宿主。
 */
function hostClassAt(masked, idx) {
  const stack = [];
  let pendingName = null;
  const re = /\b(?:class|interface|enum|record)\s+([A-Za-z0-9_]+)|[{}]/g;
  let m;
  while ((m = re.exec(masked)) && m.index < idx) {
    if (m[0] === "{") { stack.push(pendingName); pendingName = null; }
    else if (m[0] === "}") stack.pop();
    else pendingName = m[1];
  }
  for (let i = stack.length - 1; i >= 0; i--) if (stack[i]) return stack[i];
  return null;
}

const JAVA_CONST = /(?:public|protected)\s+static\s+final\s+([A-Za-z0-9_<>,\[\]\s.]+?)\s+([A-Z][A-Z0-9_]*)\s*=\s*([^;]+);/g;

/** Java 里的常量声明（同时喂给「取值域」与「静态常量」两份清单）。 */
function collectJavaConstants(javaFiles) {
  const rows = [];
  for (const rel of javaFiles) {
    const src = read(join(ROOT, rel));
    if (!src.includes("static final")) continue;
    const masked = maskJava(src);
    for (const m of src.matchAll(JAVA_CONST)) {
      // 落在注释里的匹配丢掉：抹码后同一下标应当还是同一个字符
      if (masked[m.index] !== src[m.index]) continue;
      const value = m[3].trim().replace(/\s+/g, " ");
      rows.push({
        module: moduleOf(rel), file: rel,
        host: hostClassAt(masked, m.index) ?? rel.split(sep).pop().replace(/\.java$/, ""),
        outer: rel.split(sep).pop().replace(/\.java$/, ""),
        type: m[1].trim().replace(/\s+/g, " "),
        name: m[2], value,
        zh: firstSentence(docBefore(src, m.index)),
        /** 值是个大写字面量 = 它是**库里存的那个词**，而不是配置键或权限码 */
        isVocab: /^"[A-Z][A-Z0-9_]*"$/.test(value),
      });
    }
  }
  assertScope("Java 常量", rows.length, 700);
  return rows;
}

/** Java enum：本仓库大多用字符串常量，真 enum 只有十来个，但它们同样是取值域。 */
function collectJavaEnums(javaFiles) {
  const out = [];
  for (const rel of javaFiles) {
    const src = read(join(ROOT, rel));
    if (!/\benum\s+[A-Z]/.test(src)) continue;
    const masked = maskJava(src);
    for (const m of src.matchAll(/\benum\s+([A-Z][A-Za-z0-9_]*)\s*(?:implements [^{]+)?\{/g)) {
      if (masked[m.index] !== src[m.index]) continue;
      /*
       * 常量区从 `{` 到第一个**顶层**的 `;` 或 `}`，然后按顶层逗号切。
       *
       * **不能按行切**：第一版用 `^\s*([A-Z][A-Z0-9_]*)` 逐行取，于是
       * `enum MoveDirection { UP, DOWN }` 只报出 `UP` —— 一个写在一行里的枚举
       * 少一半取值，而清单看上去是完整的。取值域清单漏一个值的代价，
       * 与漏一整个枚举是一样的：读的人据此认为「这一列不会出现 DOWN」。
       */
      const from = m.index + m[0].length;
      let depth = 0; let end = masked.length;
      for (let i = from; i < masked.length; i++) {
        const c = masked[i];
        if (c === "(" || c === "{" || c === "[") depth++;
        else if (c === ")" || c === "]") depth--;
        else if (c === "}") { if (depth === 0) { end = i; break; } depth--; }
        else if (c === ";" && depth === 0) { end = i; break; }
      }
      /*
       * 按顶层逗号切，但**记的是下标而不是文本**：深度要在抹码后的串上数
       * （注释与字符串里的逗号不算），而取值的参数必须从原文取 ——
       * 抹码抹掉的正好是 `"err.bad_request"` 这种 msgKey 本身。
       * 第一版两件事都在抹码串上做，于是参数列全是 `10400, " "`。
       */
      const spans = [];
      let segStart = from; depth = 0;
      for (let i = from; i < end; i++) {
        const c = masked[i];
        if (c === "(" || c === "{" || c === "[") depth++;
        else if (c === ")" || c === "}" || c === "]") depth--;
        else if (c === "," && depth === 0) { spans.push([segStart, i]); segStart = i + 1; }
      }
      spans.push([segStart, end]);

      /*
       * 逐个取值都留着**参数**与**自己的注释**。
       * 参数不是装饰：`ErrorCode.BAD_REQUEST(10400, "err.bad_request")` 里那个
       * msgKey 就是 `messages*.properties` 的键 —— 有了它，「错误码字典」
       * 与「本地化词条」在这份清单里能直接对上，而不是两张各说各话的表。
       */
      const items = [];
      for (const [a, b] of spans) {
        /*
         * 名字与它的下标从**抹码串**上找：取值前面常顶着一整段块注释，
         * 而原文 `.trim()` 之后开头是 `/*` 而不是取值名，正则一条都匹配不上。
         * 第一版这么写，13 个枚举里只认出 6 个 —— 抹到 6 是因为
         * 只有那 6 个的取值前面恰好没有注释，**而它不报错**，
         * 靠的是 `assertScope` 那条下界才炸出来。
         */
        const hit = masked.slice(a, b).match(/([A-Z][A-Z0-9_]*)/);
        if (!hit) continue;
        const at = a + hit.index;
        items.push({
          name: hit[1],
          args: src.slice(at, b).match(/^[A-Z][A-Z0-9_]*\s*\(([\s\S]*)\)\s*$/)?.[1]?.replace(/\s+/g, " ").trim() ?? "",
          zh: firstSentence(docBefore(src, at)),
        });
      }
      if (!items.length) continue;
      out.push({
        source: "Java enum", module: moduleOf(rel), file: rel,
        name: m[1], values: items.map((i) => i.name), items,
        zh: firstSentence(docBefore(src, m.index)),
      });
    }
  }
  assertScope("后端 Java enum", out.length, 8);
  return out;
}

/** 端上字面量联合类型 —— 正则与 `check-enums.mjs` 保持一致，两处说的必须是同一件事。 */
function tsUnions(text) {
  const out = [];
  for (const m of text.matchAll(/export type (\w+)\s*=\s*((?:\s*\|?\s*"[^"]+"\s*(?:\/\/[^\n]*\n)?)+);/g)) {
    const values = [...m[2].matchAll(/"([^"]+)"/g)].map((x) => x[1]);
    if (values.length) out.push({ name: m[1], values, at: m.index });
  }
  return out;
}

/** 端上常量对象：`export const X = { A: "A" } as const;`，同样是 wire 契约。 */
function tsConstEnums(text) {
  const out = [];
  for (const m of text.matchAll(/export const ([A-Z][A-Z0-9_]*) = \{([^}]*)\} as const;/g)) {
    const values = [...m[2].matchAll(/:\s*"([^"]+)"/g)].map((x) => x[1]);
    if (values.length) out.push({ name: m[1], values, at: m.index });
  }
  return out;
}

const TS_SURFACES = [
  ["shared", "packages/shared/src"],
  ["c-app", "c-app/src"],
  ["b-app", "b-app/src"],
  ["ops-web", "ops-web/lib"],
];

function collectTsEnums() {
  const out = [];
  for (const [client, dir] of TS_SURFACES) {
    for (const rel of walk(ROOT, join(ROOT, dir), (p) => p.endsWith(".ts") && !p.endsWith(".test.ts"))) {
      const src = read(join(ROOT, rel));
      for (const e of [...tsUnions(src), ...tsConstEnums(src)]) {
        out.push({
          source: `TS · ${client}`, module: client, file: rel,
          name: e.name, values: e.values,
          zh: firstSentence(docBefore(src, e.at)),
        });
      }
    }
  }
  assertScope("端上取值域", out.length, 80);
  return out;
}

/** `enum-registry.ts` 的登记条目 —— 用来标注「这个取值域登记过没有」。 */
function readEnumRegistry() {
  const src = read(join(ROOT, "packages/shared/src/contract/enum-registry.ts"));
  const out = new Map();
  for (const m of src.matchAll(/\{\s*decl:\s*"([^"]+)"\s*,\s*dom:\s*"([^"]+)"\s*,\s*shape:\s*"([^"]+)"\s*,\s*verdict:\s*"([^"]+)"/g)) {
    out.set(m[1], { dom: m[2], shape: m[3], verdict: m[4] });
  }
  return out;
}

// ════════════════════════════════════════════════════════════════════════════
// 四、依赖
// ════════════════════════════════════════════════════════════════════════════

const tagOf = (s, t) => s.match(new RegExp(`<${t}>([\\s\\S]*?)</${t}>`))?.[1]?.trim() ?? null;

function collectMaven() {
  const poms = walk(ROOT, join(ROOT, "backend"), (p) => p.endsWith("pom.xml"), (rel) => rel.split(sep).includes("target"));
  const parentRel = "backend/pom.xml";
  const parentSrc = read(join(ROOT, parentRel));
  const managed = new Set();
  const dmBlock = parentSrc.match(/<dependencyManagement>([\s\S]*?)<\/dependencyManagement>/)?.[1] ?? "";
  for (const d of dmBlock.matchAll(/<dependency>([\s\S]*?)<\/dependency>/g)) {
    managed.add(`${tagOf(d[1], "groupId")}:${tagOf(d[1], "artifactId")}`);
  }
  const props = Object.fromEntries(
    [...(parentSrc.match(/<properties>([\s\S]*?)<\/properties>/)?.[1] ?? "").matchAll(/<([\w.-]+)>([^<]*)<\/\1>/g)]
      .map((m) => [m[1], m[2].trim()]),
  );

  const modules = [];
  for (const rel of poms) {
    const src = read(join(ROOT, rel));
    const body = src.replace(/<dependencyManagement>[\s\S]*?<\/dependencyManagement>/g, "");
    const deps = [];
    for (const d of body.matchAll(/<dependency>([\s\S]*?)<\/dependency>/g)) {
      const g = tagOf(d[1], "groupId"); const a = tagOf(d[1], "artifactId");
      const v = tagOf(d[1], "version"); const scope = tagOf(d[1], "scope");
      if (!a) continue;
      deps.push({ g, a, v, scope, internal: g === "ai.neargo.shop" });
    }
    modules.push({
      rel, artifact: tagOf(src.replace(/<parent>[\s\S]*?<\/parent>/, ""), "artifactId"),
      name: tagOf(src, "name"), deps,
    });
  }
  assertScope("Maven 模块", modules.length, 15);

  /** 字面量版本（既不是 `${...}` 也不是 parent 管着的）—— 这就是要收编的那一档 */
  const literals = [];
  for (const m of modules) {
    if (m.rel === parentRel) continue;
    for (const d of m.deps) {
      if (!d.v || d.v.startsWith("${")) continue;
      literals.push({ module: m.rel, ga: `${d.g}:${d.a}`, version: d.v });
    }
  }
  return { modules, managed, props, literals, parentRel };
}

const NPM_WORKSPACES = [
  "package.json", "packages/ui/package.json", "packages/shared/package.json",
  "c-app/package.json", "b-app/package.json", "ops-web/package.json", "site/package.json",
];

function collectNpm() {
  const specs = new Map(); // pkg → [{ws, section, spec}]
  const wss = [];
  for (const rel of NPM_WORKSPACES) {
    const src = read(join(ROOT, rel));
    if (!src) continue;
    const json = JSON.parse(src);
    wss.push({ rel, name: json.name, deps: json.dependencies ?? {}, dev: json.devDependencies ?? {} });
    for (const [section, key] of [["dependencies", "dependencies"], ["devDependencies", "devDependencies"], ["peerDependencies", "peerDependencies"]]) {
      for (const [pkg, spec] of Object.entries(json[key] ?? {})) {
        if (!specs.has(pkg)) specs.set(pkg, []);
        specs.get(pkg).push({ ws: rel, section, spec });
      }
    }
  }
  assertScope("npm workspace", wss.length, 6);

  /**
   * 实际装着的版本。`ws` 给了就查那个 workspace 自己的 `node_modules`。
   *
   * **嵌套副本是漂移「已经真的发生了」的证据**：npm workspaces 会把依赖提升到顶层，
   * 声明一致时全仓只有一份；有人声明了另一个大版本，npm 就在那个 workspace 下
   * 单独装一份。所以「§2.1 列出来的包有没有嵌套副本」不是文档细节，
   * 它区分的是「声明写得不一样但今天只跑一份」与「今天就在跑两份」。
   */
  const installed = (pkg, ws = null) => {
    const p = ws
      ? join(ROOT, ws.replace(/\/package\.json$/, ""), "node_modules", pkg, "package.json")
      : join(ROOT, "node_modules", pkg, "package.json");
    if (!existsSync(p)) return null;
    try { return JSON.parse(readFileSync(p, "utf8")).version; } catch { return null; }
  };
  /**
   * 漂移分两档，**混在一起数第一档就永远归不了零**。
   *
   *   版本漂移   `^19.0.0` vs `19.2.8` —— 两处说的是不同的版本，这是真问题：
   *              今天靠 npm 提升侥幸只装一份，重装或换机就可能各装各的。
   *   写法不同   `^19.2.8` vs `19.2.8` —— 同一个版本，范围符不同。
   *              ops-web 一律 caret、site 一律精确钉，那是各自的取舍，
   *              不该被算成缺陷；但列出来，免得以为已经统一过了。
   *
   * `*`（peer 占位）与 `workspace:` 两档都不算 —— 那是「随宿主」，不是另一个版本。
   */
  const floor = (spec) => spec.replace(/^[\^~=v><\s]+/, "").trim();
  const drift = [];
  const styleOnly = [];
  for (const [pkg, uses] of [...specs].sort()) {
    const real = uses.filter((u) => u.spec !== "*" && !u.spec.startsWith("workspace:"));
    if (new Set(real.map((u) => u.spec)).size <= 1) continue;
    const row = {
      pkg, uses: real, installed: installed(pkg),
      nested: real.map((u) => ({ ws: u.ws, version: installed(pkg, u.ws) })).filter((n) => n.version),
    };
    if (new Set(real.map((u) => floor(u.spec))).size > 1) drift.push(row);
    else styleOnly.push(row);
  }
  return { wss, specs, drift, styleOnly, installed };
}

// ════════════════════════════════════════════════════════════════════════════
// 产物
// ════════════════════════════════════════════════════════════════════════════

const HEAD = (title, lead) => `# ${title}

> 状态：**生成物**，随代码走。
> **勿手改** —— 由 \`node scripts/gen-glossary.mjs\` 生成，
> 闸门在 \`scripts/check-generated-docs.mjs\`（跑一遍产物变不变）。
>
${lead.split("\n").map((l) => `> ${l}`).join("\n")}
`;

function mdTerms(surfaces) {
  const L = [];
  L.push(HEAD("中英文对照 · 词条",
    "真源是各端的 i18n 词条文件本身，不是任何一份手写对照表。\n" +
    "**中文一列是词条的原文，不是翻译** —— 三端的中文文案就写在 `zh-CN` 里，\n" +
    "英文（与阿语）由它派生。所以这份表同时是「文案清单」与「翻译对照」。"));

  L.push("\n## 覆盖概览\n");
  L.push("| 面 | 说明 | 键数 | 中文 | English | العربية |");
  L.push("|---|---|---|---|---|---|");
  for (const s of surfaces) {
    const n = s.keys.length;
    const cov = (lang) => {
      const m = s.langs[lang];
      if (!m) return "—";
      const have = s.keys.filter((k) => m.has(k) && m.get(k) !== "").length;
      return have === n ? `${have}` : `**${have}**（缺 ${n - have}）`;
    };
    L.push(`| ${s.surface} | ${s.note} | ${n} | ${cov("zh-CN")} | ${cov("en")} | ${cov("ar")} |`);
  }
  L.push("");
  L.push("「缺」= 中文有这条、该语言没有。**缺一条的后果是那种语言的用户看到裸 key**");
  L.push("（`picking.qtyTitle` 这串字符直接显示在界面上），不是回退到中文。");
  L.push("三语一致由 `packages/shared` 的 `i18n-parity` 守卫盯着，这里只是把数字摆出来。");

  for (const s of surfaces) {
    L.push(`\n---\n\n## ${s.surface}\n`);
    L.push(`真源：${s.source.map((f) => `\`${f}\``).join(" · ")}\n`);
    // 按第一段命名空间分组：一屏一节，比 2000 行连成一片好查
    const groups = new Map();
    for (const k of s.keys) {
      const ns = k.includes(".") ? k.slice(0, k.indexOf(".")) : "（顶层）";
      if (!groups.has(ns)) groups.set(ns, []);
      groups.get(ns).push(k);
    }
    for (const [ns, keys] of [...groups].sort()) {
      L.push(`\n### \`${ns}\`（${keys.length}）\n`);
      const hasAr = !!s.langs.ar;
      L.push(`| 键 | 中文 | English |${hasAr ? " العربية |" : ""}`);
      L.push(`|---|---|---|${hasAr ? "---|" : ""}`);
      for (const k of keys) {
        const v = (lang) => cell(s.langs[lang]?.get(k) ?? "—");
        L.push(`| \`${k}\` | ${v("zh-CN")} | ${v("en")} |${hasAr ? ` ${v("ar")} |` : ""}`);
      }
    }
  }
  return L.join("\n") + "\n";
}

function mdEntities(entities, schema, vocab, javaEnums, tsEnums, registry, msg) {
  const L = [];
  L.push(HEAD("中英文对照 · 实体与字典",
    "两件事放一份：**实体**回答「这张表叫什么、类叫什么、中文叫什么」，\n" +
    "**字典（取值域）**回答「这一列能存哪些词、每个词中文是什么」。\n" +
    "它们是同一套领域词汇的两个切面，分成两份的话改一处必漏另一处。\n" +
    "命名的**规定**在 [项目词典](../../requirements/项目词典.md) 与\n" +
    "[全域命名基准](./全域命名基准.md) —— 那两份说应该是什么，这一份说现在是什么。"));

  // ── 实体 ──
  const byModule = new Map();
  for (const e of entities) {
    if (!byModule.has(e.module)) byModule.set(e.module, []);
    byModule.get(e.module).push(e);
  }
  L.push(`\n## 一、实体（${entities.length} 个 · ${byModule.size} 个 Maven 模块）\n`);
  L.push("| 模块 | 实体数 |");
  L.push("|---|---|");
  for (const [m, rows] of [...byModule].sort()) L.push(`| \`${m}\` | ${rows.length} |`);

  for (const [m, rows] of [...byModule].sort()) {
    L.push(`\n### \`${m}\`（${rows.length}）\n`);
    L.push("| 表 | Java 类 | 中文 | 包 |");
    L.push("|---|---|---|---|");
    for (const e of rows) {
      L.push(`| \`${e.table}\` | \`${e.cls}\` | ${cell(e.zh) || "—"} | \`${e.pkg}\` |`);
    }
  }

  // ── 实体与建表的差集 ──
  const declared = new Set(entities.map((e) => e.table));
  const inDb = new Set(schema.keys());
  const noEntity = [...inDb].filter((t) => !declared.has(t)).sort();
  const noTable = [...declared].filter((t) => !inDb.has(t)).sort();
  L.push(`\n### 差集\n`);
  L.push(`建表 ${inDb.size} 张、挂了实体 ${declared.size} 张。**差集不是缺陷清单** ——`);
  L.push("纯关系表、审计表、只被 JdbcClient 读的表本来就不需要 MyBatis 实体。");
  L.push("列在这里是为了让「本该有实体却漏了」这一类看得见（症状是那张表永远读不出来）。\n");
  L.push(`**建了表没有实体（${noEntity.length}）**：${noEntity.length ? noEntity.map((t) => `\`${t}\``).join(" · ") : "无"}\n`);
  L.push(`**有实体没建表（${noTable.length}）**：${noTable.length ? noTable.map((t) => `\`${t}\``).join(" · ") : "无"}`);
  if (noTable.length) {
    L.push("\n> 这一档要当场查：实体指着一张迁移里没有的表，**读写都不会报错，只是永远空**。");
  }

  // ── 字典 ──
  const groups = new Map();
  for (const c of vocab) {
    const key = `${c.module}::${c.host}`;
    if (!groups.has(key)) groups.set(key, { module: c.module, host: c.host, file: c.file, items: [] });
    groups.get(key).items.push(c);
  }
  const javaVocab = [...groups.values()].filter((g) => g.items.length >= 2).sort((a, b) => (a.host < b.host ? -1 : 1));

  L.push(`\n---\n\n## 二、字典 / 取值域\n`);
  L.push("同一件事在这个仓库里有三种写法，三处都要在清单里，否则「这一列能存什么」永远只答对三分之一：\n");
  L.push("| 来源 | 写法 | 数量 |");
  L.push("|---|---|---|");
  L.push(`| 后端字符串常量组 | \`public static final String X = "X"\` | ${javaVocab.length} 组 / ${javaVocab.reduce((n, g) => n + g.items.length, 0)} 个取值 |`);
  L.push(`| 后端 Java enum | \`enum X { A, B }\` | ${javaEnums.length} 个 |`);
  L.push(`| 端上类型 | \`type X = "A" \\| "B"\` 与 \`as const\` | ${tsEnums.length} 个 |`);
  L.push("");
  L.push("后端以字符串常量为主是有意的（见 `InvEnums` 的类注释）：库里存的就是这些串，");
  L.push("中间隔一层 enum 的话，反序列化失败报的是「没有这个枚举常量」，");
  L.push("而真正的问题是**库里出现了没人认识的值**。");

  L.push(`\n### 2.1 后端字符串常量组（${javaVocab.length}）\n`);
  for (const g of javaVocab) {
    L.push(`\n**\`${g.host}\`** · \`${g.module}\`${g.items[0].outer !== g.host ? ` · 内部类于 \`${g.items[0].outer}\`` : ""}\n`);
    L.push("| 取值 | 常量 | 中文 |");
    L.push("|---|---|---|");
    for (const it of g.items) {
      L.push(`| \`${it.value.replace(/"/g, "")}\` | \`${it.name}\` | ${cell(it.zh) || "—"} |`);
    }
  }

  const BIG = 10;
  const sorted = javaEnums.sort((a, b) => (a.name < b.name ? -1 : 1));
  L.push(`\n### 2.2 后端 Java enum（${sorted.length}）\n`);
  L.push("| 枚举 | 模块 | 取值 | 中文 |");
  L.push("|---|---|---|---|");
  for (const e of sorted) {
    const vals = e.values.length > BIG
      ? `${e.values.slice(0, BIG).map((v) => `\`${v}\``).join(" · ")} …共 ${e.values.length}（[展开](#${e.name.toLowerCase()}）`
      : e.values.map((v) => `\`${v}\``).join(" · ");
    L.push(`| \`${e.name}\` | \`${e.module}\` | ${vals} | ${cell(e.zh) || "—"} |`);
  }
  for (const e of sorted.filter((x) => x.values.length > BIG)) {
    L.push(`\n#### ${e.name}\n`);
    L.push(cell(e.zh) ? `${e.zh}\n` : "");
    const hasArgs = e.items.some((i) => i.args);
    /*
     * `文案` 一列从后端词条现查。**这一列是这份清单最该有的那一列**：
     * 错误码在代码里、文案在 properties 里，此前没有任何一处把两者摆在一起，
     * 于是「加了错误码忘了加文案」的症状是端上弹出一个裸 key。
     */
    L.push(`| 取值 |${hasArgs ? " 参数 |" : ""} 中文文案 | English | 说明 |`);
    L.push(`|---|${hasArgs ? "---|" : ""}---|---|---|`);
    for (const it of e.items) {
      const key = it.args.match(/"([a-z0-9_.]+)"/)?.[1];
      const zhMsg = key ? msg("zh-CN", key) : null;
      const enMsg = key ? msg("en", key) : null;
      L.push(
        `| \`${it.name}\` |${hasArgs ? ` \`${cell(it.args)}\` |` : ""}` +
        ` ${zhMsg ? cell(zhMsg) : "—"} | ${enMsg ? cell(enMsg) : "—"} | ${cell(it.zh) || "—"} |`,
      );
    }
    const missing = e.items.filter((i) => {
      const k = i.args.match(/"([a-z0-9_.]+)"/)?.[1];
      return k && !msg("zh-CN", k);
    });
    if (missing.length) {
      L.push("");
      L.push(`⚠️ ${missing.length} 个取值指着 \`messages.properties\` 里没有的键：` +
        `${missing.map((i) => `\`${i.name}\``).join(" · ")} —— 端上会直接看到裸 key。`);
    }
  }

  L.push(`\n### 2.3 端上取值域（${tsEnums.length}）\n`);
  L.push("`登记` 一列取自 `packages/shared/src/contract/enum-registry.ts` ——");
  L.push("端上每个具名枚举都必须在那张表里有一条（G1 守卫），空着的是漏登记。\n");
  L.push("| 名字 | 端 | 取值 | 领域 | 登记 | 中文 |");
  L.push("|---|---|---|---|---|---|");
  const seen = new Set();
  for (const e of tsEnums.sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : (a.module < b.module ? -1 : 1)))) {
    const k = `${e.module}:${e.name}`;
    if (seen.has(k)) continue;
    seen.add(k);
    const reg = registry.get(k);
    const vals = e.values.length > 8 ? `${e.values.slice(0, 8).map((v) => `\`${v}\``).join(" · ")} …共 ${e.values.length}` : e.values.map((v) => `\`${v}\``).join(" · ");
    L.push(`| \`${e.name}\` | ${e.module} | ${vals} | ${reg?.dom ?? "—"} | ${reg ? reg.verdict : "**未登记**"} | ${cell(e.zh) || "—"} |`);
  }
  return L.join("\n") + "\n";
}

function mdConstants(consts) {
  const L = [];
  L.push(HEAD("静态常量清单",
    "后端 main 源码里所有 `public/protected static final` 的声明。\n" +
    "**分家的判据是值的形状**：值是大写字面量的（`\"POSTED\"`、`BizKey` 的号段前缀 `\"SO\"`）\n" +
    "归 [实体与字典](./中英文对照-实体与字典.md) §2 —— 那些是「这个词代表什么」的对照表。\n" +
    "剩下的在这里：权限码、配置键、上限与阈值、外部接口路径、模板名。\n" +
    "它们的共同点是**改一个字就会静默改变行为**，而没有任何一处把它们摆在一起过。"));

  const rest = consts.filter((c) => !c.isVocab);
  const byModule = new Map();
  for (const c of rest) {
    if (!byModule.has(c.module)) byModule.set(c.module, new Map());
    const m = byModule.get(c.module);
    if (!m.has(c.host)) m.set(c.host, []);
    m.get(c.host).push(c);
  }
  L.push(`\n## 概览\n`);
  L.push(`共 ${consts.length} 个常量声明，其中 ${consts.length - rest.length} 个是取值域（在另一份里），这里 ${rest.length} 个。\n`);
  L.push("| 模块 | 类数 | 常量数 |");
  L.push("|---|---|---|");
  for (const [m, hosts] of [...byModule].sort()) {
    L.push(`| \`${m}\` | ${hosts.size} | ${[...hosts.values()].reduce((n, a) => n + a.length, 0)} |`);
  }

  for (const [m, hosts] of [...byModule].sort()) {
    L.push(`\n---\n\n## \`${m}\`\n`);
    for (const [host, items] of [...hosts].sort()) {
      L.push(`\n### \`${host}\`（${items.length}）\n`);
      L.push(`\`${items[0].file}\`\n`);
      L.push("| 常量 | 类型 | 值 | 中文 |");
      L.push("|---|---|---|---|");
      for (const it of items) {
        const v = it.value.length > 70 ? `${it.value.slice(0, 70)}…` : it.value;
        L.push(`| \`${it.name}\` | \`${it.type}\` | \`${cell(v)}\` | ${cell(it.zh) || "—"} |`);
      }
    }
  }
  return L.join("\n") + "\n";
}

function mdDeps(mvn, npm) {
  const L = [];
  L.push(HEAD("依赖清单",
    "**版本写在哪里**，以及哪些地方各写了一份。\n" +
    "一个包在两处各写一个版本号，今天是一样的、明天只改了一处 ——\n" +
    "这类漂移不报错，它表现为「本机跑得起来、换台机器行为不同」。"));

  L.push("\n## 一、Maven\n");
  L.push(`父 POM \`${mvn.parentRel}\` 继承 \`ai.neargo:neargo-parent\`（Java 21 + Spring Boot 4.0.x + MyBatis-Plus BOM）。`);
  L.push("绝大多数版本由它与 Spring Boot 的 BOM 管着 —— 下面只列**本仓库自己声明版本**的那些。\n");

  L.push("### 1.1 父 POM 管着的版本\n");
  L.push("| 属性 | 值 |");
  L.push("|---|---|");
  for (const [k, v] of Object.entries(mvn.props).sort()) L.push(`| \`${k}\` | \`${v}\` |`);
  L.push("");
  L.push("| dependencyManagement 里的 GA |");
  L.push("|---|");
  for (const ga of [...mvn.managed].sort()) L.push(`| \`${ga}\` |`);

  L.push(`\n### 1.2 模块 POM 里的字面量版本（${mvn.literals.length}）\n`);
  if (!mvn.literals.length) {
    L.push("无。**所有第三方版本都由父 POM 或 BOM 管着** —— 这一档为空是目标态：");
    L.push("模块 POM 里再出现一个写死的版本号，这一节就会长出来。");
  } else {
    L.push("这一档是要收编的：版本应当只写在父 POM 的 `dependencyManagement` 里。\n");
    L.push("| 模块 | 依赖 | 版本 |");
    L.push("|---|---|---|");
    for (const l of mvn.literals) L.push(`| \`${l.module}\` | \`${l.ga}\` | \`${l.version}\` |`);
    const dup = new Map();
    for (const l of mvn.literals) dup.set(l.ga, (dup.get(l.ga) ?? 0) + 1);
    const many = [...dup].filter(([, n]) => n > 1);
    if (many.length) {
      L.push("");
      L.push(`⚠️ 其中 ${many.map(([ga, n]) => `\`${ga}\`（${n} 处）`).join("、")} 各写了不止一份 —— 改一处漏一处的现成入口。`);
    }
  }

  L.push("\n### 1.3 模块间依赖\n");
  L.push("| 模块 | 依赖的内部模块 |");
  L.push("|---|---|");
  for (const m of mvn.modules.filter((m) => m.rel !== mvn.parentRel).sort((a, b) => (a.rel < b.rel ? -1 : 1))) {
    const inner = m.deps.filter((d) => d.internal).map((d) => `\`${d.a}\``);
    L.push(`| \`${m.artifact}\` | ${inner.length ? [...new Set(inner)].join(" · ") : "—"} |`);
  }

  L.push("\n## 二、npm（workspaces）\n");
  L.push("| workspace | 包名 | dependencies | devDependencies |");
  L.push("|---|---|---|---|");
  for (const w of npm.wss) {
    L.push(`| \`${w.rel}\` | \`${w.name ?? "—"}\` | ${Object.keys(w.deps).length} | ${Object.keys(w.dev).length} |`);
  }

  L.push(`\n### 2.1 跨 workspace 的版本漂移（${npm.drift.length}）\n`);
  if (!npm.drift.length) {
    L.push("无。同一个包在各 workspace 里声明的**版本号**一致（范围写法各自不同的见 §2.2）。");
  } else {
    L.push("这一档是**版本号本身不同**。npm workspaces 会把依赖提升到顶层，所以声明不一致时");
    L.push("有两种结局，最后一列分得开：只跑顶层那一份（代价要到重装或换机那天才付），");
    L.push("还是各 workspace 已经各装各的（代价今天就在付）。\n");
    L.push("| 包 | 顶层安装 | 各处声明 | 各自另装了一份？ |");
    L.push("|---|---|---|---|");
    for (const d of npm.drift) {
      const uses = d.uses.map((u) => `\`${u.spec}\` ${u.ws.replace("/package.json", "")}`).join("<br>");
      const nested = d.nested.length
        ? d.nested.map((n) => `${n.ws.replace("/package.json", "")} → \`${n.version}\``).join("<br>")
        : "否（今天只跑顶层那一份）";
      L.push(`| \`${d.pkg}\` | \`${d.installed ?? "未装"}\` | ${uses} | ${nested} |`);
    }
    const live = npm.drift.filter((d) => d.nested.length);
    if (live.length) {
      L.push("");
      L.push(`⚠️ ${live.map((d) => `\`${d.pkg}\``).join("、")} **今天就在跑不止一份** ——`);
      L.push("最后一列列出的每个 workspace 都在自己的 `node_modules` 里另装了一份。");
      L.push("对齐它要连着重装与逐端 typecheck 一起做，不是改一行声明就完了。");
    }
  }

  L.push(`\n### 2.2 同版本、不同范围写法（${npm.styleOnly.length}）\n`);
  if (!npm.styleOnly.length) {
    L.push("无。");
  } else {
    L.push("**这一档不是缺陷**：版本号相同，只是 ops-web 一律 caret、site 一律精确钉。");
    L.push("列出来是为了让「以为已经统一过了」这件事不成立。\n");
    L.push("| 包 | 实际安装 | 各处声明 |");
    L.push("|---|---|---|");
    for (const d of npm.styleOnly) {
      const uses = d.uses.map((u) => `\`${u.spec}\` ${u.ws.replace("/package.json", "")}`).join("<br>");
      L.push(`| \`${d.pkg}\` | \`${d.installed ?? "未装"}\` | ${uses} |`);
    }
  }

  L.push("\n### 2.3 各 workspace 的依赖\n");
  for (const w of npm.wss) {
    const all = [...Object.entries(w.deps).map(([k, v]) => [k, v, "dep"]), ...Object.entries(w.dev).map(([k, v]) => [k, v, "dev"])];
    if (!all.length) continue;
    L.push(`\n**\`${w.rel}\`**\n`);
    L.push("| 包 | 规格 | 档 | 实际安装 |");
    L.push("|---|---|---|---|");
    for (const [pkg, spec, kind] of all.sort()) {
      L.push(`| \`${pkg}\` | \`${spec}\` | ${kind} | \`${npm.installed(pkg) ?? "—"}\` |`);
    }
  }
  return L.join("\n") + "\n";
}

// ════════════════════════════════════════════════════════════════════════════

async function main() {
  const javaFiles = javaMainFiles(ROOT);
  assertScope("后端 main 源文件", javaFiles.length, 800);

  const surfaces = await collectI18n();
  const entities = collectEntities(javaFiles);
  const schema = readSchema(ROOT, [MIGRATION_DIR, INVENTORY_MIGRATION_DIR]);
  const consts = collectJavaConstants(javaFiles);
  const javaEnums = collectJavaEnums(javaFiles);
  const tsEnums = collectTsEnums();
  const registry = readEnumRegistry();
  /** 后端词条查询：错误码枚举里的 msgKey 要在这里换成真文案 */
  const backend = surfaces.find((s) => s.surface.startsWith("后端"));
  const msg = (lang, key) => backend?.langs[lang]?.get(key) ?? null;

  const mvn = collectMaven();
  const npm = collectNpm();

  const files = {
    "中英文对照-词条.md": mdTerms(surfaces),
    "中英文对照-实体与字典.md": mdEntities(entities, schema, consts.filter((c) => c.isVocab), javaEnums, tsEnums, registry, msg),
    "静态常量清单.md": mdConstants(consts),
    "依赖清单.md": mdDeps(mvn, npm),
    "glossary.json": JSON.stringify({
      generatedBy: "scripts/gen-glossary.mjs",
      i18n: surfaces.map((s) => ({
        surface: s.surface, source: s.source,
        terms: s.keys.map((k) => ({
          key: k,
          "zh-CN": s.langs["zh-CN"]?.get(k) ?? null,
          en: s.langs.en?.get(k) ?? null,
          ar: s.langs.ar?.get(k) ?? null,
        })),
      })),
      entities: entities.map((e) => ({ table: e.table, class: e.cls, zh: e.zh, module: e.module, package: e.pkg })),
      dictionaries: {
        javaConstantGroups: consts.filter((c) => c.isVocab).map((c) => ({ host: c.host, name: c.name, value: c.value.replace(/"/g, ""), zh: c.zh, module: c.module })),
        javaEnums: javaEnums.map((e) => ({ name: e.name, values: e.values, zh: e.zh, module: e.module })),
        clientEnums: tsEnums.map((e) => ({ name: e.name, client: e.module, values: e.values, zh: e.zh })),
      },
      constants: consts.filter((c) => !c.isVocab).map((c) => ({ host: c.host, name: c.name, type: c.type, value: c.value, zh: c.zh, module: c.module })),
      dependencies: {
        maven: { properties: mvn.props, managed: [...mvn.managed].sort(), literalVersions: mvn.literals },
        npm: { drift: npm.drift.map((d) => ({ pkg: d.pkg, installed: d.installed, specs: d.uses })) },
      },
    }, null, 2) + "\n",
  };

  let drifted = 0;
  for (const [name, body] of Object.entries(files)) {
    const p = join(OUT_DIR, name);
    const before = existsSync(p) ? readFileSync(p, "utf8") : null;
    if (CHECK) {
      if (before !== body) { drifted++; console.error(`✖ ${relative(ROOT, p)} 与真源对不上`); }
    } else {
      writeFileSync(p, body);
    }
  }

  if (CHECK) {
    if (drifted) {
      console.error(`\n${drifted} 份产物陈了。重跑：node scripts/gen-glossary.mjs`);
      process.exit(1);
    }
    console.log("✓ 词汇清单与真源一致");
    return;
  }
  console.log(
    `✓ 词条 ${surfaces.reduce((n, s) => n + s.keys.length, 0)} 条 / ${surfaces.length} 面 · ` +
    `实体 ${entities.length} · 取值域 ${javaEnums.length + tsEnums.length + new Set(consts.filter((c) => c.isVocab).map((c) => c.host)).size} 组 · ` +
    `常量 ${consts.filter((c) => !c.isVocab).length} · ` +
    `Maven 字面量版本 ${mvn.literals.length} · npm 漂移 ${npm.drift.length}`,
  );
}

await main();
