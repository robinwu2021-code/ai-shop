// 交付状态生成器：把「三端各声明了多少端点、后端实现了多少、缺哪些」从代码里算出来，
// 写进 docs/technical/archive/三端全栈对齐清单.md 的自动区块。
//
// **为什么要生成而不是手写**：这份清单是交付跟踪的依据，手写的数字第二周就会过期，
// 而过期的跟踪表比没有跟踪表更糟 —— 它让人以为自己知道进度。
// 手写部分（结论、排期、每一项的说明）留在标记之外，不会被覆盖。
//
// 口径与 backend/scripts/api-align.py 一致：
//   · 路径参数一律折叠成 {id}（`:x` / `{x}` / `${x}` 都算同一条）——
//     参数叫什么不是契约的一部分，路径形状才是
//   · 后端要带上 @RequestMapping 前缀，否则 /biz/points/** 这类会被误判成"没实现"
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const DOC = path.join(ROOT, "docs/technical/archive/三端全栈对齐清单.md");
const BEGIN = "<!-- AUTO:BEGIN 由 npm run gen:delivery 生成，勿手改 -->";
const END = "<!-- AUTO:END -->";

/** 折叠路径参数：契约比的是形状，不是参数名 */
const norm = (p) => p.replace(/\$\{[^}]*\}/g, "{id}").replace(/[:{](\w+)\}?/g, "{id}");

/** 后端：@RequestMapping 前缀 + 各方法后缀 */
function backendPaths() {
  /*
   * Controller 不只在 shop-app：2026-08 模块合并（S7 垂直切片）之后，
   * 单域 API 跟着域走进了各自模块的 `api` 包。
   * 只扫 portal 会让「后端已实现」少算 100 多条 —— 报告照常输出，
   * 看起来像后端突然退化，实际是这个生成器瞎了。
   */
  const dirs = ["shop-app", "shop-core", "shop-merchant", "shop-settle", "shop-channel"]
    .map((m) => path.join(ROOT, "backend", m, "src/main/java/ai/neargo/shop"))
    .filter((d) => fs.existsSync(d));
  const out = new Set();
  const walk = (d) => {
    for (const e of fs.readdirSync(d, { withFileTypes: true })) {
      const p = path.join(d, e.name);
      if (e.isDirectory()) walk(p);
      else if (e.name.endsWith("Controller.java")) {
        const src = fs.readFileSync(p, "utf8");
        const base = src.match(/@RequestMapping\("([^"]+)"\)/)?.[1] ?? "";
        for (const m of src.matchAll(/@(Get|Post|Put|Delete)Mapping\("([^"]*)"\)/g)) {
          out.add(norm(base + m[2]));
        }
      }
    }
  };
  dirs.forEach(walk);
  return out;
}

/** uni-app 两端：endpoints.ts 里的 { method, path } */
function clientPaths(app) {
  const src = fs.readFileSync(path.join(ROOT, app, "src/api/endpoints.ts"), "utf8");
  return [...src.matchAll(/method:\s*"(GET|POST)",\s*\n?\s*path:\s*"([^"]+)"/g)]
    .map((m) => ({ method: m[1], path: m[2] }));
}

/** ops-web：端点散在 lib/api/https/*.ts 的 client.xxx("/ops/…") 里 */
function opsPaths() {
  const dir = path.join(ROOT, "ops-web/lib/api/https");
  const out = [];
  for (const f of fs.readdirSync(dir).filter((x) => x.endsWith(".ts"))) {
    const src = fs.readFileSync(path.join(dir, f), "utf8");
    for (const m of src.matchAll(/client\.(get|post|put|del)\(\s*[`"]([^`"]+)[`"]/g)) {
      out.push({ module: f.replace(/\.ts$/, ""), method: m[1].toUpperCase(), path: m[2] });
    }
  }
  return out;
}

const be = backendPaths();
const has = (p) => be.has(norm(p));

const apps = ["c-app", "b-app"].map((app) => {
  const eps = clientPaths(app);
  const gaps = eps.filter((e) => !has(e.path));
  return { app, total: eps.length, gaps };
});

const ops = opsPaths();
const opsByModule = new Map();
for (const e of ops) {
  const cur = opsByModule.get(e.module) ?? { total: 0, done: 0 };
  cur.total += 1;
  if (has(e.path)) cur.done += 1;
  opsByModule.set(e.module, cur);
}
const opsDone = [...opsByModule.values()].reduce((a, b) => a + b.done, 0);

const pct = (done, total) => (total ? ((done / total) * 100).toFixed(1) : "0.0");

const lines = [];
lines.push(BEGIN, "");
lines.push(`> 生成时间由 git 提交记录，不写在文里 —— 文件里写时间会让每次重跑都产生 diff。`, "");
lines.push("| 端 | 声明端点 | 后端已实现 | 覆盖率 |");
lines.push("|---|---|---|---|");
for (const a of apps) {
  const done = a.total - a.gaps.length;
  lines.push(`| \`${a.app}\` | ${a.total} | ${done} | **${pct(done, a.total)}%** |`);
}
lines.push(`| \`ops-web\` | ${ops.length} | ${opsDone} | **${pct(opsDone, ops.length)}%** |`);
lines.push(`| 后端端点合计 | —— | **${be.size}** | —— |`);
lines.push("");

for (const a of apps) {
  if (!a.gaps.length) continue;
  lines.push(`### \`${a.app}\` 端上有、后端没有（${a.gaps.length} 条）`, "");
  lines.push("| 方法 | 路径 |");
  lines.push("|---|---|");
  for (const g of a.gaps) lines.push(`| ${g.method} | \`${g.path}\` |`);
  lines.push("");
}

lines.push("### `ops-web` 按模块", "");
lines.push("| 模块 | 声明 | 后端已实现 |");
lines.push("|---|---|---|");
for (const [mod, v] of [...opsByModule].sort((a, b) => b[1].total - a[1].total)) {
  lines.push(`| ${mod} | ${v.total} | ${v.done} |`);
}
lines.push("", END);

const doc = fs.readFileSync(DOC, "utf8");
const i = doc.indexOf(BEGIN);
const j = doc.indexOf(END);
if (i < 0 || j < 0) {
  console.error(`✗ ${path.relative(ROOT, DOC)} 里找不到自动区块标记，先加上：\n${BEGIN}\n${END}`);
  process.exit(1);
}
fs.writeFileSync(DOC, doc.slice(0, i) + lines.join("\n") + doc.slice(j + END.length));

console.log(`✅ ${path.relative(ROOT, DOC)}`);
for (const a of apps) {
  console.log(`   ${a.app}: ${a.total} 条 · 缺 ${a.gaps.length}`);
}
console.log(`   ops-web: ${ops.length} 条 · 已实现 ${opsDone}`);
console.log(`   后端端点: ${be.size}`);
