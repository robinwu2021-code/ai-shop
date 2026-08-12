// B 端功能矩阵（角色 × 权限 × 端点）。产物：docs/technical/reference/B端功能矩阵-按角色.md
//
// 三份来源全部取自代码，**不手写**：
//   · 权限点与含义 → BizPerms 的常量与其上的注释
//   · 角色 → 权限   → BizPerms.ROLE_PERMS
//   · 端点 → 权限   → BizEndpointPermTest.REQUIRED
//
// 为什么端点那份取测试而不是扫 controller 注解：REQUIRED 是**唯一被守卫强制对过账**的
// 清单 —— 每个 /biz 端点都必须在里面有个说法，漏登记就红。扫注解看着更"直接"，
// 但它拿不到「这个端点是有意公开的」这类判断，会把 PUBLIC 也当成缺权限。
import { readFileSync, writeFileSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const PERMS = join(ROOT, "backend/shop-base/src/main/java/ai/neargo/shop/auth/BizPerms.java");
const TEST = join(ROOT, "backend/shop-app/src/test/java/ai/neargo/shop/arch/BizEndpointPermTest.java");
const OUT = join(ROOT, "docs/technical/reference/B端功能矩阵-按角色.md");

const permSrc = readFileSync(PERMS, "utf8");
const testSrc = readFileSync(TEST, "utf8");

// 权限常量 + 紧邻其上的注释当含义。取不到注释就留空 —— 不编。
//
// **不要用一个正则把「注释 + 字段」一起匹配**：`/\**([\s\S]*?)\*\//` 是惰性的，
// 但当它前面那段不是注释时，整个可选组会从**更早的**那个 `/**` 开始匹配 ——
// 于是第一个常量（RECEIVE）的含义变成了类 javadoc 的第一句
// 「B 端权限码与角色定义」，而后面每一个都是对的，看起来像手滑而不是 bug。
//
// 改成：先定位字段，再往回取「最后一个 /** … */」——注释块之间不可能嵌套，
// 所以「最后一个」必然是紧邻它的那一个。
const perms = new Map();
for (const m of permSrc.matchAll(/public static final String ([A-Z_]+) = "(biz:[a-z:]+)";/g)) {
  const before = permSrc.slice(0, m.index);
  const open = before.lastIndexOf("/**");
  const close = before.lastIndexOf("*/");
  const raw = open > -1 && close > open ? before.slice(open + 3, close) : "";
  const doc = raw.replace(/\s*\*\s?/g, " ").replace(/<[^>]+>/g, "").trim();
  perms.set(m[1], { code: m[2], doc: doc.split("。")[0].slice(0, 40) });
}

// 角色 → 权限
const roleBlock = permSrc.slice(permSrc.indexOf("ROLE_PERMS = Map.of("), permSrc.indexOf("private BizPerms()"));
const roles = new Map();
for (const m of roleBlock.matchAll(/(\w+),\s*List\.of\(([^)]*)\)/g)) {
  const key = m[1];
  const vals = m[2].split(",").map((s) => s.trim().replace(/"/g, "")).filter(Boolean);
  roles.set(key === "OWNER" ? "OWNER" : key, vals.includes("*") ? "*" : vals);
}

// 端点 → 权限
const req = new Map();
for (const m of testSrc.matchAll(/put\("([^"]+)",\s*BizPerms\.([A-Z_]+)\)/g)) req.set(m[1], m[2]);
const countOf = (p) => [...req.values()].filter((x) => x === p).length;
const has = (role, p) => roles.get(role) === "*" || (roles.get(role) || []).includes(p);

const ROLE_ORDER = ["OWNER", "MANAGER", "CLERK", "PICKER", "COURIER", "CS"].filter((r) => roles.has(r));
const ordered = [...perms.keys()].sort((a, b) => countOf(b) - countOf(a));

const L = [];
L.push("# B 端功能矩阵 · 按角色\n");
L.push("> **本文是生成的**：`node scripts/gen-biz-role-matrix.mjs`。改了 `BizPerms` 或");
L.push("> `BizEndpointPermTest.REQUIRED` 之后重跑一次，不要手改这份产物。\n");
L.push("> 三份来源：权限点取自 `BizPerms`，角色→权限取自 `BizPerms.ROLE_PERMS`，");
L.push("> 端点→权限取自 `BizEndpointPermTest.REQUIRED` —— 最后那份是唯一**被守卫强制对过账**的");
L.push("> 清单（每个 `/biz` 端点都必须在里面有个说法，漏登记就红），所以比任何手写文档都可信。\n");
L.push(`统计：**${ROLE_ORDER.length} 个角色 × ${perms.size} 个权限点 × ${req.size} 个受控端点**。\n`);
L.push("## 一、角色 × 权限\n");
L.push("`OWNER` 是 `*` —— **不是「拥有全部权限点」，是「不走这张表」**。新增权限点时 OWNER 自动有，");
L.push("其余角色需要显式加：老板不该因为上了个新功能就被锁在外面。\n");
L.push("| 权限点 | 含义 | 端点数 | " + ROLE_ORDER.join(" | ") + " |");
L.push("|---|---|---|" + "---|".repeat(ROLE_ORDER.length));
for (const p of ordered) {
  const cells = ROLE_ORDER.map((r) => (has(r, p) ? "✅" : "—"));
  L.push(`| \`${p}\` | ${perms.get(p).doc || "—"} | ${countOf(p)} | ${cells.join(" | ")} |`);
}
const ownerOnly = ordered.filter((p) => ROLE_ORDER.filter((r) => has(r, p)).length === 1);
if (ownerOnly.length) {
  L.push(`\n**只有 OWNER 能碰的 ${ownerOnly.length} 项**：${ownerOnly.map((p) => "`" + p + "`").join("、")}`);
  L.push("—— 它们是「能把钱和人改掉」的那几组，连店长都不下放。\n");
}
L.push("## 二、每个权限点覆盖的端点\n");
for (const p of ordered) {
  const eps = [...req.entries()].filter(([, v]) => v === p).map(([k]) => k).sort();
  if (!eps.length) continue;
  L.push(`### \`${p}\`　（${ROLE_ORDER.filter((r) => has(r, p)).join("、")}）\n`);
  for (const e of eps) L.push(`- \`${e}\``);
  L.push("");
}
L.push("> **空角色 = 零权限**，不是「零权限 = 全放行」——`BizPerms.can` 对空集合直接返回 false。\n");
// 自定义角色（V71）不在这张表里，而这份文档看上去像「角色的全集」——
// 不写这一句的话，下一个人会照着它去判断「某个人到底能做什么」，而那个答案会错
L.push("> ⚠️ **这里只有 6 个平台预置角色**。商家还能建自定义角色（V71 `mch_role`，");
L.push("> 权限点在 `BizPerms.assignableCodes()` 里挑，**不含 `biz:store:admin`**）——");
L.push("> 它们按主体存库，不在这份生成物里。判「某个人能做什么」要看他持有的角色，不是这张表。\n");
writeFileSync(OUT, L.join("\n") + "\n");
console.log(`✅ ${OUT}\n   ${ROLE_ORDER.length} 角色 · ${perms.size} 权限点 · ${req.size} 受控端点`);
