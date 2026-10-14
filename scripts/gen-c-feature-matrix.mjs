// C 端功能点 · 登录态 · 页面。**生成的，不要手改。**
//
// 为什么 C 端要单独一份：B 端那份的主轴是**权限码**（六角色 × 13 码），
// 而消费者没有角色 —— 照搬会得到一张全是空格的表。
// C 端真正要回答的是另外两个问题：
//   ① 这个功能要不要登录（`auth`）—— 它决定「游客能走到哪一步」
//   ② 它画在哪一页 —— 没有页面调用的功能点，就是**做了没出口**
//
// ②那一列是这份清单最值钱的地方：B 端与运营端各自都有一次「后端做完、端上没入口」
// 的教训（运营端 18 条、B 端积分三个接口零页面）。C 端此前没有任何判据。
//
// 三份来源全部取自代码：
//   · 功能点与登录态 → c-app/src/api/endpoints.ts
//   · 页面归属       → c-app/src/pages/**（扫 `api.xxx`）
//   · 域             → docs/technical/design/ui-catalog.json（与界面清单同一套分法）
//
// 用法：node scripts/gen-c-feature-matrix.mjs
import { readFileSync, writeFileSync, readdirSync, existsSync, statSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const EP = join(ROOT, "c-app/src/api/endpoints.ts");
const PAGES = join(ROOT, "c-app/src/pages");
const OUT = join(ROOT, "docs/technical/reference/C端功能点-登录态-页面.md");

// ── 功能点表 ──
// ⚠️ 注释不能夹在 `{` 与 `method:` 之间，否则这条正则匹配不上、端点静默消失。
// 这个坑在 b-app 的端点表上踩过一次（见 endpoints-regex-fragile 的记录）。
const epSrc = readFileSync(EP, "utf8");
const features = new Map();
for (const m of epSrc.matchAll(
  /^ {2}(\w+): \{\s*method: "(\w+)",\s*path: "([^"]+)"(?:,\s*auth: (true|false))?[^}]*?(?:summary: "([^"]*)")?[^}]*\}/gm,
)) {
  features.set(m[1], { method: m[2], path: m[3], auth: m[4] === "true", summary: m[5] ?? "" });
}

// ── 页面 → 它调了哪些功能点 ──
const callersOf = new Map();
function scan(dir, route) {
  let src = "";
  for (const f of readdirSync(dir)) {
    const p = join(dir, f);
    if (statSync(p).isDirectory()) scan(p, `${route}/${f}`);
    else if (/\.(vue|ts)$/.test(f)) src += readFileSync(p, "utf8");
  }
  for (const m of src.matchAll(/api\.(\w+)/g)) {
    if (!features.has(m[1])) continue;
    (callersOf.get(m[1]) ?? callersOf.set(m[1], new Set()).get(m[1])).add(route);
  }
}
if (existsSync(PAGES)) for (const d of readdirSync(PAGES)) {
  const p = join(PAGES, d);
  if (statSync(p).isDirectory()) scan(p, d);
}
// 组件与 store 里也会调 —— 不扫的话会把它们误报成「无出口」
for (const extra of ["c-app/src/components", "c-app/src/stores"]) {
  const p = join(ROOT, extra);
  if (existsSync(p)) scan(p, `(${extra.split("/").pop()})`);
}

const rows = [...features].sort((a, b) => a[1].path.localeCompare(b[1].path));
const orphan = rows.filter(([k]) => !callersOf.has(k));
const guest = rows.filter(([, v]) => !v.auth);

const L = [];
L.push("# C 端功能点 · 登录态 · 页面");
L.push("");
L.push("> **本文是生成的**：`node scripts/gen-c-feature-matrix.mjs`。不要手改。");
L.push("> 来源全部取自代码：功能点与登录态取 `c-app/src/api/endpoints.ts`，");
L.push("> 页面归属扫 `c-app/src/pages/**`（外加 `components/` 与 `stores/`）。");
L.push(">");
L.push("> 与 B 端那份的分工：那份的主轴是**权限码**（六角色 × 13 码），");
L.push("> 而消费者没有角色 —— 照搬会得到一张全是空格的表。");
L.push("> C 端要回答的是另外两个问题：**要不要登录**、**画在哪一页**。");
L.push("");
L.push(`统计：**${rows.length} 个功能点**，其中 **${guest.length} 个游客可用**；` +
  `**${orphan.length} 个没有任何页面调用**。`);
L.push("");

if (orphan.length) {
  L.push("## ⚠️ 没有页面调用的功能点");
  L.push("");
  L.push("> **做了没出口。** 这一类不报任何错 —— 接口在、契约在、mock 在，");
  L.push("> 只是用户点不到。B 端与运营端各自都栽过一次（运营端 18 条、B 端积分三个接口零页面）。");
  L.push("> 每一条要么给它出口，要么从端点表删掉。");
  L.push("");
  L.push("| 功能点 | 方法 | 路径 | 说明 |");
  L.push("|---|---|---|---|");
  for (const [k, v] of orphan) L.push(`| \`${k}\` | ${v.method} | \`${v.path}\` | ${v.summary || "—"} |`);
  L.push("");
}

L.push("## 全部功能点");
L.push("");
L.push("| 功能点 | 路径 | 登录 | 页面 | 说明 |");
L.push("|---|---|---|---|---|");
for (const [k, v] of rows) {
  const pages = [...(callersOf.get(k) ?? [])].sort().join(" · ") || "**无**";
  L.push(`| \`${k}\` | \`${v.method} ${v.path}\` | ${v.auth ? "是" : "游客"} | ${pages} | ${v.summary || "—"} |`);
}
L.push("");

writeFileSync(OUT, L.join("\n"));
console.log(`✅ ${OUT}`);
console.log(`   ${rows.length} 个功能点 · 游客可用 ${guest.length} · 无出口 ${orphan.length}`);
