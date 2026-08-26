// pages.json 里注册了、**但 app 里没有一处跳得过去**的页面。
//
// 与 check-ops-orphan.mjs 是同一件事的两端：那边盯运营端「后端做了没入口」，
// 这边盯 b-app / c-app「页面做了没入口」。
//
// 为什么值得一道闸：**2026-08-26 一天之内撞了三次**。
//   · pages/my-specs/index    —— 更早那次，入口合并时只合了名字，页面从此没有门
//   · pages/income/index      —— 已经随包发到线上和真机，而没人点得到
//   · pages/sku-identity/index —— 我自己当天新写的，装到真机上才发现
//
// 三次的根因是同一个：**在 H5 上验页面时直接改 hash 进去**，
// 于是「有没有门」这件事从来不在验证路径上。它不报任何错 ——
// 页面是好的、路由是对的、包也打进去了，只是在 app 里点不到。
// 靠人记得去点是靠不住的，所以写成判据。
//
// 判据：pages.json 的每条路由，在同一个 app 的源码里必须至少有一处引用 ——
// 直接写路径、走 nav.ts 的 ROUTES.x、或者它本身是 tabBar 页。
//
// 用法：
//   node scripts/check-app-orphan.mjs           # 列出来
//   node scripts/check-app-orphan.mjs --check   # 超过基线就非零退出
import { readFileSync, readdirSync, existsSync, statSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const APPS = ["b-app", "c-app"];
const BASELINE = join(ROOT, "known-orphan-pages.txt");

/** pages.json 允许注释与尾逗号（HBuilderX 的方言），JSON.parse 吃不下 */
function looseJson(text) {
  return JSON.parse(
    text
      .replace(/\/\*[\s\S]*?\*\//g, "")
      .replace(/(^|[^:"'\\])\/\/[^\n]*/g, "$1")
      .replace(/,(\s*[}\]])/g, "$1"),
  );
}

function routesOf(app) {
  const f = join(ROOT, app, "src/pages.json");
  if (!existsSync(f)) return { pages: [], tabs: new Set() };
  const d = looseJson(readFileSync(f, "utf8"));
  const pages = (d.pages ?? []).map((p) => p.path);
  for (const sp of d.subPackages ?? []) {
    for (const p of sp.pages ?? []) pages.push(`${sp.root}/${p.path}`);
  }
  // tabBar 页天生有门（底部菜单），不算孤儿
  const tabs = new Set((d.tabBar?.list ?? []).map((t) => t.pagePath));
  return { pages, tabs };
}

/** 这个 app 下所有源码拼成一大段 —— 只做「有没有提到」的判断，不必解析 */
function sourceOf(app) {
  const out = [];
  const walk = (dir) => {
    for (const name of readdirSync(dir)) {
      if (name === "node_modules" || name === "dist" || name.startsWith(".")) continue;
      const p = join(dir, name);
      if (statSync(p).isDirectory()) walk(p);
      else if (/\.(vue|ts|js|json)$/.test(name) && name !== "pages.json") {
        out.push(readFileSync(p, "utf8"));
      }
    }
  };
  const src = join(ROOT, app, "src");
  if (existsSync(src)) walk(src);
  // 两端共用的那些也要扫：外壳（底部菜单）在 packages/ui，
  // 而 c-app 的 ROUTES 常量表在 packages/shared —— 少扫一处就会把
  // 一整个 app 的页面全报成孤儿（第一版就是这么报了 21 条假阳性）。
  for (const shared of ["packages/ui/src", "packages/shared/src"]) {
    const d = join(ROOT, shared);
    if (existsSync(d)) walk(d);
  }
  return out.join("\n");
}

/**
 * `ROUTES.foo = "/pages/foo/index"` —— 页面多数是这么被引的，
 * 引到了 `ROUTES.foo` 就等于引到了那条路径。
 *
 * <p><b>两端的常量表不在同一处</b>：b-app 在 `src/shared/nav.ts`，
 * c-app 在 `packages/shared/src/utils/constants.ts`。只读前者的话
 * c-app 的页面会整片报成孤儿 —— 第一版就是这样，21 条全是假的。
 */
function routeAliases(app) {
  const map = new Map();
  for (const f of [
    join(ROOT, app, "src/shared/nav.ts"),
    join(ROOT, "packages/shared/src/utils/constants.ts"),
  ]) {
    if (!existsSync(f)) continue;
    for (const m of readFileSync(f, "utf8").matchAll(/(\w+)\s*:\s*"(\/[^"]+)"/g)) {
      const path = m[2].replace(/^\//, "");
      if (!map.has(path)) map.set(path, m[1]);
    }
  }
  return map;
}

export function orphans() {
  const rows = [];
  for (const app of APPS) {
    const { pages, tabs } = routesOf(app);
    const src = sourceOf(app);
    const alias = routeAliases(app);
    for (const page of pages) {
      if (tabs.has(page)) continue;
      const byPath = src.includes(`/${page}`) || src.includes(page);
      const key = alias.get(page);
      // `ROUTES.foo` 与 `ROUTES\n  .foo` 都算；单独出现的 `foo:` 不算（那是 nav.ts 自己）
      const byAlias = key ? new RegExp(`ROUTES\\s*\\.\\s*${key}\\b`).test(src) : false;
      if (!byPath && !byAlias) rows.push({ app, page });
    }
  }
  return rows;
}

const rows = orphans();
const check = process.argv.includes("--check");
const known = existsSync(BASELINE)
  ? new Set(readFileSync(BASELINE, "utf8").split("\n").map((l) => l.trim())
      .filter((l) => l && !l.startsWith("#")))
  : new Set();

const id = (r) => `${r.app} ${r.page}`;
const fresh = rows.filter((r) => !known.has(id(r)));
const total = APPS.reduce((n, a) => n + routesOf(a).pages.length, 0);

console.log(`两端页面 ${total}｜有入口 ${total - rows.length}｜没有门 ${rows.length}（已知欠账 ${known.size}）`);
for (const r of rows) console.log(`   ${fresh.includes(r) ? "★新增" : "     "} ${id(r)}`);

// 基线里已经接上入口的行 —— 补完了却忘了删。名单上的条目是免检的，
// 不删的话它将来被摘掉入口也不会有人发现，而这份清单的价值全在「只准变短」。
const stale = [...known].filter((k) => !rows.some((r) => id(r) === k));
if (stale.length) {
  console.log(`\n✅ 这 ${stale.length} 条已经有入口了，把它们从基线里删掉：`);
  for (const k of stale) console.log(`      ${k}`);
}

if (check && fresh.length) {
  console.error(`\n✗ 新增了 ${fresh.length} 个「有页面没有门」。`);
  console.error("  要么给它加一个入口，要么登记进 known-orphan-pages.txt 并写明为什么。");
  console.error("  ⚠️ 这类问题不报错：页面是好的、路由是对的、包也打进去了，只是点不到。");
  process.exit(1);
}

if (check && stale.length) {
  console.error(`\n✗ 基线里有 ${stale.length} 条已经有入口了，删掉它们。`);
  console.error("  留着的话，将来那个入口被摘掉也不会有人发现。");
  process.exit(1);
}
