/**
 * 权限种子 ↔ 实库 对账。
 *
 * **为什么需要它**：种子由 `nav.ts × perm-map.ts × Perms.java` 生成 ——
 * 改了源头它自己就跟着走；而库只跟着迁移走。两者之间此前**没有任何对账**，
 * 于是源头动了、迁移没跟上时，库就停在旧状态：不报错，只是某个角色在菜单里少一项，
 * 而他会以为「我没这个权限」，不会报障。
 *
 * 2026-09-09 第一次跑就查到两条存量：
 *   · `OPS_MESSAGE__TAB_FAQ`：V273 把它从 NOT_IMPLEMENTED 翻成 IMPLEMENTED，
 *     却没回填其余角色的授权（生成器注释里说「其余角色等那天按真实权限码重算」，
 *     那一天没有人来算）。客服持有 message:ticket:handle，菜单里却没有这一项。
 *   · `ACT__SYSTEM_ENV_SWITCH`：perm-map 里映射变了，没有迁移跟上。
 * 修在 `V326__perm_seed_drift_faq_envswitch.sql`。
 *
 * ⚠️ **它当不了 pre-push 闸门**：要连一个真实的库，而 CI / worktree 里没有。
 * 它是「改完权限相关的东西之后手动跑一次」的工具，与
 * `check-generated-docs`（拦产物陈了）是两件事 —— 那道闸门看的是**文件**，
 * 这里看的是**库**，产物新鲜不代表库跟上了。
 *
 * 用法（默认连本地 dev 库）：
 *   node ops-web/scripts/check-perm-seed-drift.mjs
 *   node ops-web/scripts/check-perm-seed-drift.mjs --host 1.2.3.4 --user u --pass p --db ai_shop
 *
 * 退出码：有「种子有、库里没有」的差异时非零；反向差异只提示不拦
 * （运营自建角色、界面上手工调过的授权都属于反向，那是正常的）。
 */
import { execFileSync } from "node:child_process";

const argv = process.argv.slice(2);
const opt = (name, dflt) => {
  const i = argv.indexOf(`--${name}`);
  return i >= 0 && argv[i + 1] ? argv[i + 1] : dflt;
};
const HOST = opt("host", "127.0.0.1");
const USER = opt("user", "shop");
const PASS = opt("pass", "shop");
const DB = opt("db", "ai_shop");

const AT = (rel) => new URL(rel, import.meta.url).pathname;

/** 跑生成器拿种子 —— 不读任何中间产物，避免「对的是一份陈的 SQL」 */
const seedSql = execFileSync("node", [AT("./gen-perm-seed.mjs")], {
  encoding: "utf8", maxBuffer: 32 << 20,
});

const seedGrants = new Set();
const seedPoints = new Map();
for (const l of seedSql.split("\n")) {
  if (l.startsWith("INSERT INTO sys_role_point")) {
    const m = l.match(/VALUES \('([^']+)', '([^']+)'/);
    if (m) seedGrants.add(`${m[1]}\t${m[2]}`);
  } else if (l.startsWith("INSERT INTO sys_function_point")) {
    const v = [...l.matchAll(/'((?:[^']|'')*)'|\b(NULL)\b/g)].map((m) => (m[2] ? null : m[1]));
    seedPoints.set(v[0], { perm: v[6], status: v[7] });
  }
}

const q = (sql) => execFileSync("mysql", [
  `-h${HOST}`, `-u${USER}`, `-p${PASS}`, "-N", "-B", "--default-character-set=utf8mb4", "-e", sql,
], { encoding: "utf8", maxBuffer: 32 << 20 }).trim();

const dbGrants = new Set(
  q(`SELECT role_code, point_code FROM ${DB}.sys_role_point WHERE end_code='OPS';`)
    .split("\n").filter(Boolean).map((l) => l.replace(/\s+/, "\t")),
);
const dbPoints = new Map(
  q(`SELECT point_code, IFNULL(perm_code,''), backend_status FROM ${DB}.sys_function_point;`)
    .split("\n").filter(Boolean).map((l) => {
      const [code, perm, status] = l.split("\t");
      return [code, { perm: perm || null, status }];
    }),
);

const missing = [...seedGrants].filter((g) => !dbGrants.has(g)).sort();
const extra = [...dbGrants].filter((g) => !seedGrants.has(g)).sort();
const stale = [];
for (const [code, s] of seedPoints) {
  const d = dbPoints.get(code);
  if (!d) continue;
  if (d.perm !== s.perm || d.status !== s.status) {
    stale.push(`${code}\n     库：  ${d.status} / ${d.perm ?? "NULL"}\n     种子：${s.status} / ${s.perm ?? "NULL"}`);
  }
}

console.log(`种子 ${seedGrants.size} 条授权 / ${seedPoints.size} 个功能点`);
console.log(`实库 ${dbGrants.size} 条授权 / ${dbPoints.size} 个功能点  (${HOST}/${DB})`);
console.log("");

if (stale.length) {
  console.log(`✗ 功能点状态或权限码陈了 ${stale.length} 个 —— 源头改过而没有迁移跟上：`);
  for (const s of stale) console.log("  " + s);
  console.log("");
}
if (missing.length) {
  console.log(`✗ 种子有、库里没有 ${missing.length} 条授权 —— 这些角色在菜单里少了功能：`);
  for (const m of missing) console.log("  " + m.replace("\t", " → "));
  console.log("");
}
if (extra.length) {
  // 反向差异不拦：运营自建角色、界面上手工调过的授权都会落在这里
  console.log(`ℹ 库里有、种子没有 ${extra.length} 条（运营自建角色与手工调整，正常）`);
}
if (!stale.length && !missing.length) console.log("✓ 种子与实库对得上");

process.exit(stale.length || missing.length ? 1 : 0);
