#!/usr/bin/env node
/**
 * API 清单生成器 —— 供**人审核**的三端接口总表。
 *
 * 为什么不直接看 OpenAPI：三份 spec 加起来一万多行，是给机器读的。
 * 审的时候要回答的是「这个域有哪些接口、谁在用、后端做了没有、入参出参是什么」，
 * 那需要一张按域分组、一行一个接口的表。
 *
 * 三个来源合成一张表：
 *   · 契约：docs/api/openapi{,-b,-ops}.yaml（前端生成，形状的真源）
 *   · 后端：backend 的 @GetMapping/@PostMapping 扫描（实现与否）
 *   · 前端：各端 endpoints.ts（谁在调）
 *
 * 用法：npm run gen:api-index
 */
import { readFileSync, writeFileSync, readdirSync, existsSync, statSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const OUT = join(ROOT, "docs/api/API清单.md");

/** 路径参数写法统一：`:x` 与 `{x}` 视为同一条 */
const norm = (p) => p.replace(/[:{](\w+)\}?/g, "{$1}").replace(/\/$/, "") || "/";

// ---------------------------------------------------------------- 契约
/**
 * 只解析 paths 段，不引 YAML 库 —— 产物是自家生成器出的，形状可控。
 * 记录：路径 → 方法 → { summary, tag, auth, reqRef, respRef }
 */
function readSpec(file) {
  const out = [];
  if (!existsSync(file)) return out;
  const lines = readFileSync(file, "utf8").split("\n");
  let inPaths = false;
  let path = null;
  let op = null;
  for (const line of lines) {
    if (/^paths:\s*$/.test(line)) {
      inPaths = true;
      continue;
    }
    if (!inPaths) continue;
    if (/^\S/.test(line)) break; // 出了 paths 段

    const pm = line.match(/^ {2}"?(\/[^":]*)"?:\s*$/);
    if (pm) {
      path = pm[1];
      op = null;
      continue;
    }
    const mm = line.match(/^ {4}(get|post|put|delete):\s*$/);
    if (mm && path) {
      op = { path, method: mm[1].toUpperCase(), summary: "", tag: "", auth: false, req: "", resp: "" };
      out.push(op);
      continue;
    }
    if (!op) continue;
    const s = line.match(/^ {6}summary:\s*"(.*)"\s*$/);
    if (s) op.summary = s[1];
    /*
     * **`security: []` 是「不需要鉴权」，不是「有鉴权这一项」。**
     *
     * 原先这里只认键名，于是 22 个免登录端点（社区、商品、区划、类目…）
     * 在清单里全被标成 🔒 —— 而 C 端「先逛店、要下单时再登录」这条路，
     * 靠的正是这批端点游客能访问。清单把它说反了，且**每一行都说反**，
     * 一致得看不出是错的。
     */
    if (/^ {6}security:\s*$/.test(line)) op.auth = true;
    const t = line.match(/^ {8}- "(.+)"\s*$/);
    if (t && !op.tag) op.tag = t[1];
    // 段落状态机：先看当前在 requestBody 还是 responses 段，再决定 $ref 归谁。
    // 第一版靠「先出现的是入参」猜，结果两列全空 —— 生成物要么准要么别出。
    if (/^ {6}requestBody:/.test(line)) op._section = "req";
    if (/^ {6}responses:/.test(line)) op._section = "resp";
    // 键可能是 `$ref:` 也可能是 `"$ref":` —— 生成器对不同 key 的引号处理并不统一。
    // 只认一种写法的解析器不会报错，只会**整列留空**，看上去像「这些接口没定义类型」。
    // ops 那份 YAML 当初读不进 api-align.py，也是栽在同一处。
    const ref = line.match(/"?\$ref"?:\s*"#\/components\/schemas\/(\w+)"/);
    if (ref) {
      if (op._section === "req" && !op.req) op.req = ref[1];
      if (op._section === "resp" && !op.resp) op.resp = ref[1];
    }
    // 分页/数组/原始类型的响应没有顶层 $ref。留空会被读成「这个接口没定义出参」，
    // 而实际上它定义了，只是形状不是具名类型 —— 把形状记下来，别让人去猜。
    if (op._section === "resp" && !op.resp) {
      if (/records:/.test(line)) op.resp = "分页";
      else if (/^ {20,}type:\s*"array"/.test(line)) op.resp = "数组";
      else if (/^ {18,20}data:/.test(line)) op._atData = true;
      else if (op._atData) {
        const t = line.match(/type:\s*"(\w+)"/);
        if (t) {
          op.resp = t[1];
          op._atData = false;
        }
      }
    }
  }
  return out;
}

// ---------------------------------------------------------------- 后端
function backendRoutes() {
  /*
   * Controller 不只在 shop-app/portal：2026-08 的 S7 垂直切片之后，
   * 单域 API 面跟着域走进了各自模块的 `api` 包（`shop.trade.api.mp` 等）。
   * 只扫 portal 会让「后端已实现」从 200+ 掉到 90 上下 —— 而报告照常输出，
   * 看起来像后端退化了一半，实际是这个扫描器瞎了。
   * gen-delivery-status.mjs 与 api-align.py 用的是同一套目录清单，三者必须一致。
   */
  const dirs = ["shop-app", "shop-core", "shop-merchant", "shop-settle", "shop-channel"]
    .map((m) => join(ROOT, "backend", m, "src/main/java/ai/neargo/shop"))
    .filter((d) => existsSync(d));
  const out = new Set();
  if (!dirs.length) return out;
  const walk = (d) => {
    for (const f of readdirSync(d)) {
      const p = join(d, f);
      if (statSync(p).isDirectory()) walk(p);
      else if (f.endsWith(".java")) {
        const src = readFileSync(p, "utf8");
        // 类级 @RequestMapping 是前缀，方法级只写后缀 —— 只扫方法级会漏掉一半路由
        // （守卫 api-align.py 用的是同一套规则，两边算出的数必须一致）
        const base = src.match(/@RequestMapping\("([^"]+)"\)/)?.[1] ?? "";
        for (const m of src.matchAll(/@(Get|Post|Put|Delete)Mapping\(\s*(?:value\s*=\s*)?"([^"]*)"/g)) {
          const suffix = m[2];
          const path = suffix.startsWith("/") ? base + suffix : base || suffix;
          if (path.startsWith("/")) out.add(`${m[1].toUpperCase()} ${norm(path)}`);
        }
        // 无参数的 @GetMapping：路径就是类级前缀
        for (const m of src.matchAll(/@(Get|Post|Put|Delete)Mapping\s*\n/g)) {
          if (base) out.add(`${m[1].toUpperCase()} ${norm(base)}`);
        }
      }
    }
  };
  dirs.forEach(walk);
  return out;
}

// ---------------------------------------------------------------- 前端
function frontendRoutes() {
  const out = new Set();
  for (const app of ["c-app", "b-app"]) {
    const f = join(ROOT, app, "src/api/endpoints.ts");
    if (!existsSync(f)) continue;
    for (const m of readFileSync(f, "utf8").matchAll(
      /method:\s*"(GET|POST|PUT)",\s*path:\s*"([^"]+)"/g,
    )) {
      out.add(`${m[1]} ${norm(m[2])}`);
    }
  }
  const opsDir = join(ROOT, "ops-web/lib/api/https");
  if (existsSync(opsDir)) {
    for (const f of readdirSync(opsDir).filter((x) => x.endsWith(".ts"))) {
      for (const m of readFileSync(join(opsDir, f), "utf8").matchAll(
        /client\.(get|post|put)\(\s*[`"]([^`"]+)[`"]/g,
      )) {
        out.add(`${m[1].toUpperCase()} ${norm(m[2].replace(/\$\{[^}]*?(\w+)\}/g, "{$1}"))}`);
      }
    }
  }
  return out;
}

// ---------------------------------------------------------------- 组装
const DOMAINS = [
  ["C 端", "/mp", "docs/api/openapi.yaml", "c-app（消费者）"],
  ["B 端", "/biz", "docs/api/openapi-b.yaml", "b-app（商家）"],
  ["平台端", "/ops", "docs/api/openapi-ops.yaml", "ops-web（运营）"],
];

const be = backendRoutes();
const fe = frontendRoutes();
const now = process.env.SOURCE_DATE || "2026-08-06";

const md = [
  "# API 清单（三端 · 供审核）",
  "",
  `> ${now} 由 \`npm run gen:api-index\` 生成，**请勿手改**。`,
  "> 三个来源合成：契约（前端生成的 OpenAPI，形状真源）× 后端实现扫描 × 前端调用扫描。",
  "> 完整入参/出参 schema 见三份 OpenAPI；这里是给人审的总表。",
  "",
  "图例：**后端** ✅ 已实现 / ⬜ 未实现 ｜ **前端** ✅ 在调 / ⬜ 未接",
  "",
  "对照：[响应格式规范](响应格式规范.md) ｜ [三端与后端对照](三端与后端对照.md) ｜ [后端验收清单](后端验收清单.md) ｜ [项目词典](../requirements/项目词典.md)",
  "",
  "---",
  "",
];

let totals = { ops: 0, beDone: 0, feDone: 0 };

for (const [label, prefix, spec, who] of DOMAINS) {
  const ops = readSpec(join(ROOT, spec)).filter((o) => o.path.startsWith(prefix));
  const byTag = new Map();
  for (const o of ops) {
    const tag = o.tag || o.path.split("/")[2] || "其他";
    if (!byTag.has(tag)) byTag.set(tag, []);
    byTag.get(tag).push(o);
  }
  const beHit = ops.filter((o) => be.has(`${o.method} ${norm(o.path)}`)).length;
  const feHit = ops.filter((o) => fe.has(`${o.method} ${norm(o.path)}`)).length;
  totals.ops += ops.length;
  totals.beDone += beHit;
  totals.feDone += feHit;

  md.push(
    `## ${label} \`${prefix}/**\` · ${who}`,
    "",
    `共 **${ops.length}** 个接口 ｜ 后端已实现 **${beHit}**（${((beHit / ops.length) * 100).toFixed(0)}%）｜ 前端在调 **${feHit}**`,
    "",
  );

  for (const [tag, list] of [...byTag].sort((a, b) => a[0].localeCompare(b[0]))) {
    md.push(`### ${tag}（${list.length}）`, "", "| 方法 | 路径 | 说明 | 入参 | 出参 | 鉴权 | 后端 | 前端 |", "|---|---|---|---|---|:---:|:---:|:---:|");
    for (const o of list.sort((a, b) => a.path.localeCompare(b.path))) {
      const key = `${o.method} ${norm(o.path)}`;
      md.push(
        `| ${o.method} | \`${o.path}\` | ${o.summary || "—"} | ${o.req ? `\`${o.req}\`` : "—"} | ${o.resp ? `\`${o.resp}\`` : "—"} | ${o.auth ? "🔒" : "—"} | ${be.has(key) ? "✅" : "⬜"} | ${fe.has(key) ? "✅" : "⬜"} |`,
      );
    }
    md.push("");
  }
}

md.splice(
  10,
  0,
  `**合计 ${totals.ops} 个接口**：后端已实现 ${totals.beDone}（${((totals.beDone / totals.ops) * 100).toFixed(0)}%）· 前端在调 ${totals.feDone}`,
  "",
);

writeFileSync(OUT, md.join("\n"));
console.log(`✅ ${OUT}`);
console.log(`   ${totals.ops} 个接口 · 后端 ${totals.beDone} · 前端 ${totals.feDone}`);
