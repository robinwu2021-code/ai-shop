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
const PERMS_JAVA = join(ROOT, "backend/shop-base-auth/src/main/java/ai/neargo/shop/auth/Perms.java");

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
  // ── 运营自己的收件箱（顶栏铃铛）。四条同一个理由，逐条登记以便日后单独撤销
  ["GET /ops/message", "**收件人是调用者自己**：查询按当前 userNo 裁剪，别人的本来就查不到。"
    + "挂 message:template:read 的话，收到「待审商家」通知的审核员反而打不开铃铛 —— "
    + "而那条通知正是发给他的"],
  ["GET /ops/message/unread-count", "同上，且只返回一个整数，不含任何内容"],
  ["POST /ops/message/{messageNo}/read", "把**自己的**一条标为已读。改不到别人的：写入同样按 userNo 裁剪"],
  ["POST /ops/message/read-all", "同上，批量版"],
  ["GET /ops/stream", "SSE 推送通道。**推什么由每条消息自己的权限决定** —— "
    + "任务详情本来就要 system:job:read 才看得到；在连接这一层再挂一道，"
    + "会让没有任务权限的人连不上流，连带丢掉未读数。"
    + "（此前漏在这里：生成器每次都打警告然后退出 0，而它不在 pre-push 的清单里，"
    + "于是这条警告打了一路没人看见）"],
]);

/** 扫 /ops 端点 → 权限常量名 */
export function scanEndpoints(root = ROOT) {
  const files = execSync(`grep -rl '"/ops' --include='*.java' backend`, {
    cwd: root, encoding: "utf8",
  }).trim().split("\n").filter(Boolean);

  const out = [];
  for (const f of files) {
    const src = readFileSync(join(root, f), "utf8");
    const re = /@(Get|Post|Put|Delete|Patch)Mapping\(\s*(?:value\s*=\s*)?"(\/ops[^"]*)"/g;
    const marks = [];
    for (let m; (m = re.exec(src)); ) marks.push({ i: m.index, method: m[1].toUpperCase(), path: m[2] });
    /*
     * @PreAuthorize 的位置，**两种写法都要认**：库里多数写在 @XxxMapping 之后，
     * 但 OpsSpuStdController / OpsMemberController / OpsPromotionController 写在前面。
     *
     * ⚠️ **此前按字符距离就近配对，那是错的，且错在最危险的方向上。**
     * 一个**故意不挂注解**的端点，只要它的方法体够短，隔壁方法的注解就会离它更近 ——
     * 于是它被安上邻居的权限码，在矩阵里显示成「需要某某权限」。
     * 2026-08-29 实测被安错两条：
     *   · `GET /ops/captcha`            → 被标成要 message:template:read（距离 783 字符）
     *   · `POST /ops/staffs/me/password` → 被标成要 iam:staff:update（距离 293 字符）
     * 两条都是**把敞开的端点报成有码**。而这张表存在的全部意义就是回答
     * 「谁能访问什么」—— 报错方向朝着「看起来更安全」，是最不该出的那一种。
     *
     * 现在按**行窗口**配对，并且在两个方向上各自遇到边界就停：
     *   · 另一个 Mapping   —— 那是隔壁端点的地盘
     *   · 方法签名的收尾行 —— `) {` 之后就是方法体，注解不可能在里面
     * 判据：换成这个规则之后，扫出的无码端点必须与
     * OpsEndpointPermTest.ANY_OPERATOR 那 12 条**逐条相等**（见 main() 里的断言）。
     */
    const lines = src.split("\n");
    const lineOf = (idx) => src.slice(0, idx).split("\n").length - 1;
    const isBoundary = (l) => /@(Get|Post|Put|Delete|Patch)Mapping\(/.test(l)
      || /\)\s*\{\s*$/.test(l);
    for (const mk of marks) {
      const at = lineOf(mk.i);
      let perm = null;
      for (let d = 0; d <= 6 && perm === null; d++) {
        for (const j of d === 0 ? [at] : [at - d, at + d]) {
          if (j < 0 || j >= lines.length) continue;
          // 从 mapping 走到 j，中途撞到边界就不算同一个方法的注解
          const [lo, hi] = j < at ? [j + 1, at] : [at + 1, j];
          let blocked = false;
          for (let k = lo; k < hi; k++) if (isBoundary(lines[k])) { blocked = true; break; }
          if (blocked) continue;
          const hit = /@PreAuthorize[^\n]*Perms\.(\w+)/.exec(lines[j]);
          if (hit) { perm = hit[1]; break; }
        }
      }
      out.push({ method: mk.method, path: mk.path, perm });
    }
  }
  out.sort((a, b) => `${a.path} ${a.method}`.localeCompare(`${b.path} ${b.method}`));
  return out;
}

/** Perms.java → { codes: 常量名→码, roles: 角色→常量名[] | "*" } */
export function parsePerms(root = ROOT) {
  const src = readFileSync(join(root, "backend/shop-base-auth/src/main/java/ai/neargo/shop/auth/Perms.java"), "utf8");
  const codes = new Map();
  for (const m of src.matchAll(/public static final String (\w+) = "([^"]+)";/g)) codes.set(m[1], m[2]);

  /*
   * **先剥注释再解析。**
   *
   * 这里是按逗号切 List.of(...) 的，而 ROLE_PERMS 里逐条写着「为什么给这个角色这个码」——
   * 注释一旦夹在两个码之间，它会被粘到后一个码上，那个码就再也匹配不到常量表。
   * 后果不是报错，是**那个角色在矩阵里悄悄少一个权限** ——
   * 而这张矩阵正是「谁能访问什么」的基线，于是守卫会报「他少了几个端点」，
   * 指向一个根本不存在的收紧。2026-08-27 加 system:job:read 时踩到过一次。
   */
  const block = src.slice(src.indexOf("ROLE_PERMS = Map.ofEntries("))
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .replace(/\/\/[^\n]*/g, "");
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
    console.error("✗ 这些 /ops 端点没有 @PreAuthorize，也不在 PUBLIC 里：\n  " + unexpected.join("\n  "));
    process.exitCode = 1;   // 此前只 console.error 然后退出 0 —— 警告打了一路没人看见
  }

  /*
   * **同一件事不许有两份清单。** `OpsEndpointPermTest.ANY_OPERATOR` 是被
   * Java 闸门强制对过账的那份（漏登记直接红），这里的 PUBLIC 是第二份 ——
   * 两份一旦不一致，本文的「谁能访问什么」就有一格是错的。
   * 2026-08-29 实测就差了一条（/ops/stream 只在 Java 那份里）。
   */
  const anyOperator = new Set(
    [...readFileSync(join(ROOT,
      "backend/shop-app/src/test/java/ai/neargo/shop/arch/OpsEndpointPermTest.java"), "utf8")
      .matchAll(/Map\.entry\("(\/ops[^"]*)"/g)].map((m) => m[1]));
  const pubPaths = new Set([...PUBLIC.keys()].map((k) => k.slice(k.indexOf(" ") + 1)));
  const onlyJava = [...anyOperator].filter((p) => !pubPaths.has(p));
  const onlyHere = [...pubPaths].filter((p) => !anyOperator.has(p));
  if (onlyJava.length || onlyHere.length) {
    console.error("✗ PUBLIC 与 OpsEndpointPermTest.ANY_OPERATOR 对不上："
      + (onlyJava.length ? "\n  只在 Java 那份里：" + onlyJava.join(", ") : "")
      + (onlyHere.length ? "\n  只在 PUBLIC 里：" + onlyHere.join(", ") : ""));
    process.exitCode = 1;
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
