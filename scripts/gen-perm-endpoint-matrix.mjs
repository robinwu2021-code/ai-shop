// 运营端「角色 × 端点」可访问矩阵。产物两份：
//   · docs/technical/reference/运营端-角色×端点矩阵.md   给人看
//   · packages/shared/tests/fixtures/ops-role-endpoint-matrix.json  给守卫比对
//
// **它存在的理由是权限码细化那次改造**（docs/technical/design/权限码细化-对齐清单.md）：
// 16 个粗码要拆成 64 个细码、147 处注解逐个换。判错的两种后果不对称 ——
// 判紧了有人报错（看得见），判松了没人报错（看不见）。
// 所以阶段 A 必须是**可机器验证的等价重构**：拆完这份产物应当**零 diff**。
//
// 两份来源都取自代码，不手写：
//   · 端点 → 权限码  → 扫 @PreAuthorize（注意：这个库里它写在 @XxxMapping **之后**）
//   · 角色 → 权限码  → Perms.ROLE_PERMS
//
// 与 B 端那份（gen-biz-role-matrix.mjs）的差别：那边端点→权限取自
// BizEndpointPermTest.REQUIRED，因为 /biz 有一批「有意公开」的端点，注解上看不出来。
// /ops 这边只有 3 条没有注解且都是有意的，登记在 PUBLIC 里就够，不值得再维护一张 147 行的表。
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { execSync } from "node:child_process";
import { join, dirname } from "node:path";
import { codeOf } from "./perm-endpoint-map.mjs";
import { fileURLToPath } from "node:url";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const PERMS_JAVA = join(ROOT, "backend/shop-base/src/main/java/ai/neargo/shop/auth/Perms.java");

/**
 * 有意不挂 @PreAuthorize 的端点。**改这个清单要说明理由** ——
 * 每加一条都是一个对所有登录者开放的接口。
 */
export const PUBLIC = new Map([
  ["POST /ops/auth/login", "登录本身，此刻还没有身份"],
  ["GET /ops/auth/me", "自查身份，返回的就是调用者自己"],
  ["GET /ops/menu", "菜单本来就按人切片 —— 再加一个码等于「要有权限才能知道自己有什么权限」"],
  ["POST /ops/staffs/me/password", "改**自己**的密码。要 iam:staff:update 的话，"
    + "拿一次性初始密码登进来的新员工反而改不了密码 —— 而首登强制改密正是为他准备的"],
  ["POST /ops/auth/forgot", "忘记密码。**忘了密码自然登不进来**，挂码等于这条路永远走不通。"
    + "安全性靠两处：账号不存在也返回成功（不泄露账号是否存在）+ 按账号限流"],
  ["POST /ops/auth/reset", "用邮件里的一次性重置码设新密码。同上免鉴权 —— "
    + "安全性全在那个 15 分钟、一次性、存哈希的令牌上"],
  ["GET /ops/captcha", "取一张图形验证码。它本身不泄露任何东西；"
    + "挂了码，没权限的人看到的是一张裂图而不是「无权限」，反而更难查。"
    + "权限在真正发送的 /ops/notify-logs/test-send 那步判"],
]);

/** 扫 /ops 端点 → 权限常量名 */
export function scanEndpoints(root = ROOT) {
  const files = execSync(`grep -rl '"/ops' --include='*.java' backend`, {
    cwd: root, encoding: "utf8",
  }).trim().split("\n").filter(Boolean);

  const out = [];
  for (const f of files) {
    const src = readFileSync(join(root, f), "utf8");
    const re = /@(Get|Post|Put|Delete)Mapping\(\s*(?:value\s*=\s*)?"(\/ops[^"]*)"/g;
    const marks = [];
    for (let m; (m = re.exec(src)); ) marks.push({ i: m.index, method: m[1].toUpperCase(), path: m[2] });
    for (let k = 0; k < marks.length; k++) {
      // **注解在 Mapping 之后**：窗口往后开到下一个 Mapping 为止。
      // 第一版往前找，扫出 28 个「无判权端点」——全是各文件的第一个端点，纯属方向搞反。
      const win = src.slice(marks[k].i, k + 1 < marks.length ? marks[k + 1].i : src.length);
      const pa = [...win.matchAll(/@PreAuthorize[^\n]*Perms\.(\w+)/g)].pop();
      out.push({ method: marks[k].method, path: marks[k].path, perm: pa ? pa[1] : null });
    }
  }
  out.sort((a, b) => `${a.path} ${a.method}`.localeCompare(`${b.path} ${b.method}`));
  return out;
}

/** Perms.java → { codes: 常量名→码, roles: 角色→常量名[] | "*" } */
export function parsePerms(root = ROOT) {
  const src = readFileSync(join(root, "backend/shop-base/src/main/java/ai/neargo/shop/auth/Perms.java"), "utf8");
  const codes = new Map();
  for (const m of src.matchAll(/public static final String (\w+) = "([^"]+)";/g)) codes.set(m[1], m[2]);

  const block = src.slice(src.indexOf("ROLE_PERMS = Map.ofEntries("));
  const roles = new Map();
  for (const m of block.matchAll(/Map\.entry\("(\w+)",\s*List\.of\(([\s\S]*?)\)\)/g)) {
    const vals = m[2].split(",").map((s) => s.trim()).filter(Boolean);
    roles.set(m[1], vals.includes('"*"') ? "*" : vals);
  }
  return { codes, roles };
}

/** 角色 → 可访问端点（"METHOD path"），排序后返回 */
export function buildMatrix(root = ROOT) {
  const eps = scanEndpoints(root);
  const { codes, roles } = parsePerms(root);

  const matrix = {};
  for (const [role, granted] of [...roles].sort((a, b) => a[0].localeCompare(b[0]))) {
    const has = granted === "*" ? null : new Set(granted.map((c) => codes.get(c) ?? `?${c}`));
    matrix[role] = eps
      .filter((e) => {
        // 没挂注解的端点：登录即可用，谁都能访问
        if (!e.perm) return true;
        return has === null || has.has(codes.get(e.perm) ?? `?${e.perm}`);
      })
      .map((e) => `${e.method} ${e.path}`);
  }
  return { eps, codes, roles, matrix };
}

function main() {
  const { eps, codes, roles, matrix } = buildMatrix();

  const unguarded = eps.filter((e) => !e.perm).map((e) => `${e.method} ${e.path}`);
  const unexpected = unguarded.filter((k) => !PUBLIC.has(k));
  if (unexpected.length) {
    console.error("⚠️ 这些 /ops 端点没有 @PreAuthorize，也不在 PUBLIC 里：\n  " + unexpected.join("\n  "));
  }

  const fixtures = join(ROOT, "packages/shared/tests/fixtures");
  mkdirSync(fixtures, { recursive: true });
  writeFileSync(join(fixtures, "ops-role-endpoint-matrix.json"), JSON.stringify(matrix, null, 2) + "\n");

  // ── 人看的那份 ──
  const byCode = new Map();
  for (const e of eps) {
    const c = e.perm ? codes.get(e.perm) : "（无判权）";
    if (!byCode.has(c)) byCode.set(c, []);
    byCode.get(c).push(`${e.method} ${e.path}`);
  }

  const L = [];
  L.push("# 运营端 · 角色 × 端点可访问矩阵");
  L.push("");
  // 状态行与「由 `脚本` 生成」的措辞都是 doc-standard 守卫认的格式，别改口径。
  // 这里**不打日期戳**：每次重跑都会变的日期会让产物 diff 里全是噪声，
  // 而这份产物存在的意义就是「有 diff = 有人改变了谁能访问什么」。
  L.push("> 状态：**生成物**，随代码走。");
  L.push("> **勿手改** —— 由 `node scripts/gen-perm-endpoint-matrix.mjs` 生成。");
  L.push("> 真源：`@PreAuthorize` 注解 × `Perms.ROLE_PERMS`。");
  L.push("> 守卫：`packages/shared/tests/ops-perm-matrix.test.ts` 比对 fixtures 里的基线。");
  L.push("");
  L.push("**这份产物的用途是权限码细化改造**（[方案](../design/权限码细化-对齐清单.md)）：");
  L.push("阶段 A 把 16 个粗码拆成 64 个细码，做完这份矩阵应当**零 diff**。");
  L.push("有 diff 就说明拆的过程中改变了谁能访问什么 —— 那不是重构，是收紧或放宽。");
  L.push("");
  L.push(`扫到 **${eps.length}** 个 \`/ops\` 端点，其中 **${eps.length - unguarded.length}** 个挂了判权。`);
  L.push("");
  L.push("## 一、每个角色能访问多少");
  L.push("");
  L.push("| 角色 | 可访问端点 | 占比 |");
  L.push("|---|--:|--:|");
  for (const [role, list] of Object.entries(matrix)) {
    L.push(`| \`${role}\` | ${list.length} | ${Math.round((list.length / eps.length) * 100)}% |`);
  }
  L.push("");
  L.push("> 每个角色都至少能访问 " + unguarded.length + " 条（无判权的那几条，见下）。");
  L.push("");
  L.push("## 二、权限码 → 端点");
  L.push("");
  L.push("**一个码盖多少端点，就是它有多粗。** 这一节是细化改造的工作量清单。");
  L.push("");
  for (const [code, list] of [...byCode].sort((a, b) => b[1].length - a[1].length)) {
    L.push(`### \`${code}\` — ${list.length} 条`);
    L.push("");
    L.push("```");
    for (const e of list) L.push(e);
    L.push("```");
    L.push("");
  }
  // ── 细化目标：登记表算出来的「拆完应该是什么样」──
  const target = new Map();
  for (const e of eps) {
    const k = `${e.method} ${e.path}`;
    if (PUBLIC.has(k)) continue;
    const c = codeOf(e.method, e.path) ?? "（未归属）";
    if (!target.has(c)) target.set(c, []);
    target.get(c).push(k);
  }
  L.push("## 三、细化目标：拆完之后的码 → 端点");
  L.push("");
  L.push("来自 `scripts/perm-endpoint-map.mjs` 这份登记表。**阶段 A 照着它改注解。**");
  L.push(`拆完是 **${target.size}** 个码（现在 ${byCode.size - 1} 个），最大的一个盖 ` +
    `**${Math.max(...[...target.values()].map((v) => v.length))}** 条（现在 ${Math.max(...[...byCode.values()].map((v) => v.length))} 条）。`);
  L.push("");
  L.push("| 码 | 端点数 | 端点 |");
  L.push("|---|--:|---|");
  for (const [code, list] of [...target].sort()) {
    L.push(`| \`${code}\` | ${list.length} | ${list.map((e) => `\`${e}\``).join("<br>")} |`);
  }
  L.push("");
  L.push("## 四、角色 → 权限码");
  L.push("");
  L.push("| 角色 | 权限码 |");
  L.push("|---|---|");
  for (const [role, granted] of [...roles].sort((a, b) => a[0].localeCompare(b[0]))) {
    const s = granted === "*" ? "**（全部）**" : granted.map((c) => `\`${codes.get(c) ?? c}\``).join(" · ");
    L.push(`| \`${role}\` | ${s} |`);
  }
  L.push("");

  const out = join(ROOT, "docs/technical/reference/运营端-角色×端点矩阵.md");
  writeFileSync(out, L.join("\n"));
  console.log(`✓ ${eps.length} 端点 × ${Object.keys(matrix).length} 角色 → ${out}`);
}

if (process.argv[1] && process.argv[1].endsWith("gen-perm-endpoint-matrix.mjs")) main();
