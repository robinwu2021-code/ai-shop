// B 端「功能点 × 权限码 × 角色 × 页面」矩阵。
// 产物：docs/technical/reference/B端功能点-权限码-页面.md
//
// 与 gen-biz-role-matrix.mjs 的分工：
//   · gen-biz-role-matrix  回答「谁能碰哪些端点」—— 角色视角，端点是路径
//   · 本脚本               回答「哪个功能点归哪个码、画在哪一页」—— 功能视角，端点有中文名
//
// 多出来的第四份来源是 **b-app 的页面**：`denied=` 门禁与页面里实际调的 `api.xxx`。
// 加它是因为三份后端来源解释不了 B 端最常见的那类故障 ——
// **页面门禁只写了一个码，load() 里却打了要另一个码的端点**，
// 后端正确返回 70006，前端把它渲染成「这家店什么都没有」。
// 那种错在任何一份纯后端的清单里都是隐形的，只有把页面并进来才看得见（见产物 §三）。
import { readFileSync, writeFileSync, readdirSync, existsSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const PERMS = join(ROOT, "backend/shop-base/src/main/java/ai/neargo/shop/auth/BizPerms.java");
const TEST = join(ROOT, "backend/shop-app/src/test/java/ai/neargo/shop/arch/BizEndpointPermTest.java");
const ENDPOINTS = join(ROOT, "b-app/src/api/endpoints.ts");
const PAGES = join(ROOT, "b-app/src/pages");
const OUT = join(ROOT, "docs/technical/reference/B端功能点-权限码-页面.md");

const permSrc = readFileSync(PERMS, "utf8");
const testSrc = readFileSync(TEST, "utf8");
const epSrc = readFileSync(ENDPOINTS, "utf8");

// ---------------------------------------------------------------- 一、权限码与含义
// 注释只取「紧邻字段那一段」：截到最后一个 /** 之后，否则惰性匹配会从类注释一路跨过来
// —— gen-biz-role-matrix 就是这么让 RECEIVE 的含义变成类注释第一句的。
const perms = new Map();
for (const m of permSrc.matchAll(/public static final String ([A-Z_]+) = "(biz:[a-z:]+)";/g)) {
  const before = permSrc.slice(0, m.index);
  const open = before.lastIndexOf("/**");
  const close = before.lastIndexOf("*/");
  const doc = open > -1 && close > open
    ? before.slice(open + 3, close).replace(/\s*\*\s?/g, " ").replace(/<[^>]+>/g, "").trim()
    : "";
  perms.set(m[2], { const: m[1], doc });
}

// ---------------------------------------------------------------- 二、角色 → 权限码
const roleBlock = permSrc.slice(permSrc.indexOf("ROLE_PERMS = Map.of("), permSrc.indexOf("private BizPerms()"));
const CONST2CODE = new Map([...perms].map(([code, v]) => [v.const, code]));
const roles = new Map();
for (const m of roleBlock.matchAll(/(\w+),\s*List\.of\(([^)]*)\)/g)) {
  const vals = m[2].split(",").map((s) => s.trim().replace(/"/g, "")).filter(Boolean);
  roles.set(m[1], vals.includes("*") ? "*" : vals.map((v) => CONST2CODE.get(v)).filter(Boolean));
}
const ROLE_ORDER = ["OWNER", "MANAGER", "CLERK", "PICKER", "COURIER", "CS"].filter((r) => roles.has(r));
const ROLE_CN = { OWNER: "老板", MANAGER: "店长", CLERK: "店员", PICKER: "理货员", COURIER: "配送员", CS: "客服" };
const has = (role, code) => roles.get(role) === "*" || (roles.get(role) || []).includes(code);
const who = (code) => ROLE_ORDER.filter((r) => has(r, code));

// ---------------------------------------------------------------- 三、端点 → 权限码 / 公开 / 任一
const norm = (p) => p.replace(/\{[^}]+\}/g, "{}").replace(/:[A-Za-z]+/g, "{}").replace(/\$\{[^}]+\}/g, "{}");
const required = new Map();
for (const m of testSrc.matchAll(/put\("([^"]+)",\s*BizPerms\.([A-Z_]+)\)/g)) {
  required.set(norm(m[1]), CONST2CODE.get(m[2]));
}
const cut = (from, to) => testSrc.slice(testSrc.indexOf(from), testSrc.indexOf(to));
const PUBLIC = new Set([...cut("PUBLIC = Set.of(", "端点 → 需要的权限码").matchAll(/"(\/biz\/[^"]+)"/g)].map((m) => norm(m[1])));
const ANY_OF = new Set([...cut("ANY_OF = Set.of(", "private static final Pattern").matchAll(/"(\/biz\/[^"]+)"/g)].map((m) => norm(m[1])));

// ---------------------------------------------------------------- 四、功能点（端点的中文名取自 b-app 端点表）
const features = new Map();   // 契约方法名 → {method, path, summary}
for (const m of epSrc.matchAll(/(\w+):\s*\{\s*method:\s*"(\w+)",\s*path:\s*"([^"]+)",\s*auth:\s*(\w+),\s*summary:\s*"([^"]+)"/g)) {
  features.set(m[1], { verb: m[2], path: m[3], auth: m[4] === "true", summary: m[5] });
}
// 跨多行写的那几条（prettier 折行）单独再扫一遍
for (const m of epSrc.matchAll(/(\w+):\s*\{\s*method:\s*"(\w+)",\s*\n?\s*path:\s*"([^"]+)",\s*\n?\s*auth:\s*(\w+),\s*\n?\s*summary:\s*"([^"]+)"/g)) {
  if (!features.has(m[1])) features.set(m[1], { verb: m[2], path: m[3], auth: m[4] === "true", summary: m[5] });
}

// ---------------------------------------------------------------- 五、页面 → 门禁 + 调用
const pages = [];
for (const dir of readdirSync(PAGES)) {
  const d = join(PAGES, dir);
  if (!existsSync(d)) continue;
  let src = "";
  for (const f of readdirSync(d)) src += readFileSync(join(d, f), "utf8");
  /*
   * 页面门禁。**两种写法都要认**：
   *   ① 内联   `:denied="!merchant.can('biz:order:view')"`
   *   ② 走 computed  `:denied="!canView"` + `const canView = computed(() => merchant.can("biz:finance"))`
   *
   * 只认 ① 的后果不是漏报，是**大面积假报**：②那几页会被当成「根本没有门禁」，
   * 于是每个角色都算进得来，页面里用到的每个码都成了「他打不通的请求」。
   * 2026-08-29 实测：42 条冲突里有 19 条是这么来的（income / points /
   * points-records / schedule 四页，它们的门禁一直都在）。
   * 而假报的代价不只是噪声 —— 照着它去「补门禁」会给一个已经有门禁的页面再加一层。
   */
  const gates = [...src.matchAll(/denied="!merchant\.can\('([^']+)'\)/g)].map((m) => m[1]);
  for (const m of src.matchAll(/denied="!(\w+)"/g)) {
    const via = new RegExp(`const\\s+${m[1]}\\s*=\\s*computed\\(\\(\\)\\s*=>\\s*merchant\\.can\\("([^"]+)"\\)`);
    const hit = via.exec(src);
    if (hit) gates.push(hit[1]);
  }
  const calls = [...new Set([...src.matchAll(/api\.(\w+)/g)].map((m) => m[1]))].filter((c) => features.has(c));
  pages.push({ dir, gates, calls });
}
const pageOf = new Map();   // 契约方法名 → [页面…]
for (const p of pages) for (const c of p.calls) pageOf.set(c, [...(pageOf.get(c) || []), p.dir]);

const codeOfCall = (name) => required.get(norm(features.get(name).path));
/** 这个角色进得了这一页吗（门禁全过） */
const canEnter = (role, gates) => gates.every((g) => has(role, g));
/** 进得来但会撞码的角色 → 缺哪些码 */
const clashes = (p) => {
  const need = [...new Set(p.calls.map(codeOfCall).filter(Boolean))];
  return ROLE_ORDER.filter((r) => canEnter(r, p.gates))
    .map((r) => ({ role: r, missing: need.filter((c) => !has(r, c)) }))
    .filter((x) => x.missing.length);
};

/*
 * 上面这五段是**纯解析**，导出给守卫用：
 * `packages/shared/tests/biz-page-perm.test.ts` 直接吃 `pages` 与 `clashes`，
 * 不再第二份实现一遍 —— 与 ops-perm-matrix.test.ts 吃 gen-perm-endpoint-matrix 同一形状。
 *
 * 生成产物的部分收在 main() 里，只有当脚本被直接执行时才跑。
 */
export { perms, roles, ROLE_ORDER, ROLE_CN, required, PUBLIC, ANY_OF, features, pages };
export { has, who, norm, codeOfCall, canEnter, clashes };

// ---------------------------------------------------------------- 输出
function main() {
const L = [];
const controlled = [...required.keys()];
L.push("# B 端功能点 · 权限码 · 页面\n");
L.push("状态：**已落地（六角色判权 + 13 权限码）· 产物随代码重生成**\n");
L.push("> **本文是生成的**：`node scripts/gen-biz-feature-perm-matrix.mjs`。不要手改。\n");
L.push("> 四份来源全部取自代码：权限码与含义取 `BizPerms`，角色→码取 `BizPerms.ROLE_PERMS`，");
L.push("> 端点→码取 `BizEndpointPermTest.REQUIRED`（唯一被守卫强制对过账的那份），");
L.push("> **功能点中文名与页面归属取 `b-app/src/api/endpoints.ts` 与 `b-app/src/pages/`**。\n");
L.push("> 与 [B端功能矩阵-按角色](./B端功能矩阵-按角色.md) 的分工：那份是**角色视角**");
L.push("> （谁能碰哪些路径），这份是**功能视角**（哪个功能点归哪个码、画在哪一页）。\n");
L.push(`统计：**${perms.size} 个权限码 × ${ROLE_ORDER.length} 个角色 × ${controlled.length} 个受控功能点**`);
L.push(`（另有 ${PUBLIC.size} 个登录即可、${ANY_OF.size} 个「任一权限即可」）。\n`);

// 同上：这份表按「预置角色」列，自定义角色不在其中
L.push("> ⚠️ 角色列只有 6 个平台预置角色。商家自定义角色（V71 `mch_role`）按主体存库，");
L.push("> 不在这份生成物里 —— 但它们能勾的权限点就是本表第一列（少一个 `biz:store:admin`）。\n");
L.push("## 一、权限码总表\n");
L.push("| 权限码 | 常量 | 含义 | 功能点数 | " + ROLE_ORDER.map((r) => ROLE_CN[r]).join(" | ") + " |");
L.push("|---|---|---|---|" + "---|".repeat(ROLE_ORDER.length));
const ordered = [...perms.keys()].sort(
  (a, b) => controlled.filter((e) => required.get(e) === b).length - controlled.filter((e) => required.get(e) === a).length,
);
for (const code of ordered) {
  const n = controlled.filter((e) => required.get(e) === code).length;
  const doc = (perms.get(code).doc.split("。")[0] || "—").slice(0, 34);
  L.push(`| \`${code}\` | \`${perms.get(code).const}\` | ${doc} | ${n} | `
    + ROLE_ORDER.map((r) => (has(r, code) ? "✅" : "—")).join(" | ") + " |");
}
L.push("\n> `OWNER` 是 `*`：**不走这张表**。新增权限码时老板自动有，其余角色要显式加。\n");

L.push("## 二、功能点明细（按权限码分组）\n");
L.push("「页面」列为空 = **后端有能力、b-app 没有入口**，见 §四。\n");
for (const code of ordered) {
  const rows = [...features.entries()]
    .filter(([, f]) => required.get(norm(f.path)) === code)
    .sort((a, b) => a[1].path.localeCompare(b[1].path));
  const orphan = controlled.filter((e) => required.get(e) === code
    && ![...features.values()].some((f) => norm(f.path) === e));
  if (!rows.length && !orphan.length) continue;
  L.push(`### \`${code}\`　${perms.get(code).doc.split("。")[0]}\n`);
  L.push(`**可用角色**：${who(code).map((r) => ROLE_CN[r]).join("、")}\n`);
  L.push("| 功能点 | 方法 | 端点 | 契约方法 | 页面 |");
  L.push("|---|---|---|---|---|");
  for (const [name, f] of rows) {
    L.push(`| ${f.summary} | ${f.verb} | \`${f.path}\` | \`${name}\` | ${(pageOf.get(name) || []).join("、") || "—"} |`);
  }
  for (const e of orphan) L.push(`| —（b-app 未接） | — | \`${e}\` | — | — |`);
  L.push("");
}

L.push("## 三、页面 × 门禁　—— 前端裁剪与后端判权对不对得上\n");
L.push("**页面的 `denied` 门禁必须 ⊇ 该页所有调用所需的权限码。**");
L.push("不满足时的表现不是「拒绝」而是「整页空白」：后端返回 70006，");
L.push("而页面把它 catch 成空数据或整个 `Promise.all` reject。\n");
L.push("「⚠ 会撞码」列里的角色**进得了这一页，但页面里有他打不通的请求**。\n");
L.push("| 页面 | 门禁 | 该页需要的码 | 进得来的角色 | ⚠ 会撞码 |");
L.push("|---|---|---|---|---|");
for (const p of pages.sort((a, b) => a.dir.localeCompare(b.dir))) {
  const need = [...new Set(p.calls.map(codeOfCall).filter(Boolean))];
  if (!need.length) continue;
  const enter = ROLE_ORDER.filter((r) => canEnter(r, p.gates));
  const bad = clashes(p);
  L.push(`| \`${p.dir}\` | ${p.gates.map((g) => "`" + g + "`").join("、") || "**无**"} | `
    + `${need.map((c) => "`" + c + "`").join("、")} | ${enter.map((r) => ROLE_CN[r]).join("、")} | `
    + `${bad.map((x) => ROLE_CN[x.role] + "（缺 " + x.missing.join("、") + "）").join("　") || "—"} |`);
}
L.push("\n> 「进得来的角色」只按 `denied` 门禁算，**不含页面内部按 `can()` 逐块裁的部分** ——");
L.push("> 工作台那种「每个格子跟着自己的权限走」的写法在这张表里会显示为「会撞码」，但它是对的。");
L.push("> 这张表定位问题，不定罪。\n");

L.push("## 四、后端有能力、b-app 没有入口\n");
const unwired = controlled.filter((e) => ![...features.values()].some((f) => norm(f.path) === e));
if (unwired.length) {
  L.push("| 端点 | 权限码 | 可用角色 |");
  L.push("|---|---|---|");
  for (const e of unwired.sort()) {
    L.push(`| \`${e}\` | \`${required.get(e)}\` | ${who(required.get(e)).map((r) => ROLE_CN[r]).join("、")} |`);
  }
  L.push("\n> 这些是「写了、测了、没人调用」—— 要么排期接上，要么从 `REQUIRED` 删掉。");
  L.push("> `noStaleEntries` 只拦已删除的端点，**拦不住「端点还在但没人用」**。\n");
} else {
  L.push("（无）\n");
}

const unpaged = [...features.entries()]
  .filter(([name, f]) => required.has(norm(f.path)) && !(pageOf.get(name) || []).length);
if (unpaged.length) {
  L.push("### 四之二、契约接了、页面没画\n");
  L.push("比上一张更隐蔽：`endpoints.ts` 里有、`contract.ts` 里有类型，");
  L.push("**唯独没有任何一页调用它** —— 看起来像做完了。\n");
  L.push("| 功能点 | 端点 | 契约方法 | 权限码 |");
  L.push("|---|---|---|---|");
  for (const [name, f] of unpaged.sort((a, b) => a[1].path.localeCompare(b[1].path))) {
    L.push(`| ${f.summary} | \`${f.path}\` | \`${name}\` | \`${required.get(norm(f.path))}\` |`);
  }
  L.push("");
}

L.push("## 五、登录即可（不需要授权）\n");
L.push("**空角色的人也能调**，所以这张表的每一条都要能回答「为什么它不需要权限」。\n");
L.push("| 端点 | 功能点 |");
L.push("|---|---|");
for (const e of [...PUBLIC].sort()) {
  const f = [...features.entries()].find(([, v]) => norm(v.path) === e);
  L.push(`| \`${e}\` | ${f ? f[1].summary : "—"} |`);
}
L.push("");
if (ANY_OF.size) {
  L.push("## 六、任一权限即可（汇总型端点）\n");
  L.push("一次返回好几件互不相干的事，**粒度由端上按 `perms` 裁**。");
  L.push("仍然要求「在这家店有角色」——空角色的人一样进不来。\n");
  for (const e of [...ANY_OF].sort()) {
    const f = [...features.entries()].find(([, v]) => norm(v.path) === e);
    L.push(`- \`${e}\`　${f ? f[1].summary : ""}`);
  }
  L.push("");
}

writeFileSync(OUT, L.join("\n") + "\n");
console.log(`✅ ${OUT}\n   ${perms.size} 权限码 · ${ROLE_ORDER.length} 角色 · ${controlled.length} 受控功能点 · ${pages.length} 页`);
}

if (process.argv[1] && process.argv[1].endsWith("gen-biz-feature-perm-matrix.mjs")) main();
