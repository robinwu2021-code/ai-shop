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
// **第二道（2026-09-19 补）：从入口一路点得到。** 上面那条只问「有没有别的页面提到我」，
// 一圈页面互相链接就全部过关 —— 会员名单 / 标签详情 / 人群详情 / 给会员发消息四页
// 正是这样：彼此互链，而工作台、我的、营销一处都不链进来，**上线两批、真机上才发现点不到**。
// 判据：以底部 tab 页与「全局代码」（App.vue、store、shared、packages 里提到的页面 —— 登录拦截、
// 推送点击这类不经页面的跳转）为起点，沿「页面 → 它用到的组件 → 它们链到的页面」走一遍，
// 走不到的就是没有门。组件里的链接只在用到它的页面可达时才算数。
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
    // 常量表 2026 年中从 constants.ts 挪成了 constants/index.ts；旧路径不在了也不报错，
    // 于是 c-app 的 ROUTES.x 一条都认不出 —— 两处都读，谁在读谁
    join(ROOT, "packages/shared/src/utils/constants.ts"),
    join(ROOT, "packages/shared/src/utils/constants/index.ts"),
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

/** 一段源码里提到了哪些页面（直接写路径，或走 ROUTES.x） */
function pageRefs(text, pages, alias) {
  const out = new Set();
  for (const page of pages) {
    if (text.includes(`/${page}`) || text.includes(`"${page}"`)) out.add(page);
    const key = alias.get(page);
    if (key && new RegExp(`ROUTES\\s*\\.\\s*${key}\\b`).test(text)) out.add(page);
  }
  return out;
}

function filesUnder(dir, re) {
  const out = [];
  if (!existsSync(dir)) return out;
  const walk = (d) => {
    for (const name of readdirSync(d)) {
      if (name === "node_modules" || name === "dist" || name.startsWith(".")) continue;
      const p = join(d, name);
      if (statSync(p).isDirectory()) walk(p);
      else if (re.test(name)) out.push(p);
    }
  };
  walk(dir);
  return out;
}

/**
 * 从入口走不到的页面。节点 = 页面（`pages/<名>/` 下所有文件算同一页）与组件；
 * 边 = 源码里提到页面路径 / ROUTES.x，以及页面或组件里用到某个组件（`<biz-xxx`、`<app-xxx`、
 * 按文件名 import）。起点 = tab 页 + 全局代码里提到的页面。
 */
export function unreachable() {
  const rows = [];
  for (const app of APPS) {
    const { pages, tabs } = routesOf(app);
    const alias = routeAliases(app);
    // 扫描面的自检：起点或别名表读空了，整张图就是错的 —— 宁可停下，别给一个看似合理的数
    if (!tabs.size) throw new Error(`${app}: pages.json 里读不到 tabBar，可达性无从算起`);
    if (!alias.size) throw new Error(`${app}: 读不到 ROUTES 常量表（nav.ts / constants），ROUTES.x 会全部认不出`);
    const srcDir = join(ROOT, app, "src");
    const all = filesUnder(srcDir, /\.(vue|ts|js)$/);
    const pageOf = (f) => {
      const rel = f.slice(srcDir.length + 1);
      const m = rel.match(/^(pages\/[^/]+)\//);
      if (!m) return null;
      return pages.find((p) => p.startsWith(`${m[1]}/`)) ?? null;
    };
    const compDir = join(srcDir, "components");
    const comps = new Map();   // 组件名（文件名去扩展名）→ 源码
    for (const f of all) {
      if (f.startsWith(compDir + "/") && f.endsWith(".vue")) {
        comps.set(f.slice(f.lastIndexOf("/") + 1, -4), readFileSync(f, "utf8"));
      }
    }
    const usesComps = (text) => [...comps.keys()].filter((c) =>
      text.includes(`<${c}`) || text.includes(`/${c}.vue`) || text.includes(`/${c}"`));

    // 每个页面（含它目录下的子文件）的源码
    const pageText = new Map(pages.map((p) => [p, ""]));
    const globalText = [];
    for (const f of all) {
      const pg = pageOf(f);
      const text = readFileSync(f, "utf8");
      if (pg) pageText.set(pg, pageText.get(pg) + "\n" + text);
      else if (!f.startsWith(compDir + "/")) globalText.push(text);
    }
    // 两端共用的外壳与常量：底部菜单、拦截器在这里（常量表本身不算「提到」—— 它只是登记）
    for (const shared of ["packages/ui/src", "packages/shared/src"]) {
      for (const f of filesUnder(join(ROOT, shared), /\.(vue|ts|js)$/)) {
        if (f.endsWith("utils/constants.ts") || f.endsWith("utils/constants/index.ts")) continue;
        globalText.push(readFileSync(f, "utf8"));
      }
    }
    const globalJoined = globalText.filter((t) => !/^\s*export const ROUTES\s*=/m.test(t)).join("\n");

    const seen = new Set();
    const queue = [];
    const visitPage = (p) => { if (!seen.has(p)) { seen.add(p); queue.push(["page", p]); } };
    const seenComp = new Set();
    const visitComp = (c) => { if (!seenComp.has(c)) { seenComp.add(c); queue.push(["comp", c]); } };
    for (const t of tabs) visitPage(t);
    for (const p of pageRefs(globalJoined, pages, alias)) visitPage(p);
    for (const c of usesComps(globalJoined)) visitComp(c);
    while (queue.length) {
      const [kind, name] = queue.shift();
      const text = kind === "page" ? pageText.get(name) ?? "" : comps.get(name) ?? "";
      for (const p of pageRefs(text, pages, alias)) visitPage(p);
      for (const c of usesComps(text)) visitComp(c);
    }
    for (const p of pages) if (!seen.has(p)) rows.push({ app, page: p });
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

// ─── 第二道：从入口一路点得到 ─────────────────────────────────────────────
const REACH_BASELINE = join(ROOT, "known-unreachable-pages.txt");
const orphanIds = new Set(rows.map(id));
// 第一道已经报过的（压根没人提到）不在这里重复报
const unreach = unreachable().filter((r) => !orphanIds.has(id(r)));
const knownReach = existsSync(REACH_BASELINE)
  ? new Set(readFileSync(REACH_BASELINE, "utf8").split("\n").map((l) => l.replace(/#.*/, "").trim())
      .filter(Boolean))
  : new Set();
const freshReach = unreach.filter((r) => !knownReach.has(id(r)));
const staleReach = [...knownReach].filter((k) => !unreach.some((r) => id(r) === k));

console.log(`\n从入口点得到｜被提到却走不到 ${unreach.length}（已知欠账 ${knownReach.size}）`);
for (const r of unreach) console.log(`   ${freshReach.includes(r) ? "★新增" : "     "} ${id(r)}`);
if (staleReach.length) {
  console.log(`\n✅ 这 ${staleReach.length} 条已经走得到了，把它们从 known-unreachable-pages.txt 删掉：`);
  for (const k of staleReach) console.log(`      ${k}`);
}
if (check && freshReach.length) {
  console.error(`\n✗ 新增了 ${freshReach.length} 个「有人链它、但从底部菜单一路点不过去」的页面。`);
  console.error("  多半是一圈页面互相链接，而圈外没有一处链进来 —— 真机上点不到，而上面那道闸是绿的。");
  console.error("  要么从已有入口挂一条路进来，要么登记进 known-unreachable-pages.txt 并写明为什么");
  console.error("  （例：只从外部进 —— 分享卡片、扫码、订阅消息的落地页）。");
  process.exit(1);
}
if (check && staleReach.length) {
  console.error(`\n✗ known-unreachable-pages.txt 里有 ${staleReach.length} 条已经走得到了，删掉它们。`);
  process.exit(1);
}
