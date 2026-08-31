/**
 * 生成运营端权限配置的种子 SQL（V62 的数据部分）。
 *
 * 数据来自三处**已经在维护的真源**，一行都不手写：
 *   · lib/nav.ts       菜单树 = 功能与功能点、二级分组、需求编号
 *   · lib/perm-map.ts  UI 码 → 后端码，以及 UNIMPLEMENTED 标记
 *   · Perms.java       角色 → 后端码
 *
 * 手写的清单三个月后必然过期，而这份的口径与运营端界面**同源** ——
 * 界面看到什么，库里就是什么。
 */
import { readFileSync, writeFileSync } from 'node:fs';

/*
 * 路径相对**本文件**解析，不相对 cwd。
 * 相对 cwd 的话换个目录跑就是 ENOENT —— 而更坏的情况是碰巧读到同名的别的文件，
 * 那时它会安静地生成一份错的种子。
 */
const AT = (rel) => new URL(rel, import.meta.url);
const nav = readFileSync(AT('../lib/nav.ts'), 'utf8');
const pmSrc = readFileSync(AT('../lib/perm-map.ts'), 'utf8');
const permsJava = readFileSync(
  AT('../../backend/shop-base-auth/src/main/java/ai/neargo/shop/auth/Perms.java'), 'utf8');

/**
 * href → **稳定的** point_code。
 *
 * 此前是按顺序编号（`OPS_MERCHANT_01`…），插一个叶子就让其后全部右移。
 * 而 `sys_role_point` 存的正是 point_code —— 编号一移，原本授权「认证标管理」的角色
 * 会静默变成授权「准入与保证金」：没有报错，且是**放宽**方向。
 * 运营自建角色的授权同样会跟着错位，而没有任何东西会发现。
 *
 * 改成从 href 派生之后，新增叶子只新增一行，既有授权一律不受影响。
 * href 在 nav.ts 里本来就唯一（nav.test.ts 有「叶子 href 在 section 内唯一」的守卫），
 * 所以派生结果天然唯一且稳定。
 *
 *   /merchants           → OPS_MERCHANT
 *   /merchants?tab=list  → OPS_MERCHANT__TAB_LIST
 *   /system?tab=authCode → OPS_SYSTEM__TAB_AUTHCODE
 */
const POINT_SEP = '__';
export function pointCodeOf(functionCode, href) {
  const [, qs] = href.split('?');
  const tab = new URLSearchParams(qs).get('tab');
  const view = new URLSearchParams(qs).get('view');
  const suffix = tab ? `TAB_${tab}` : view ? `VIEW_${view}` : '';
  // 大写 + 非字母数字归一成下划线：tab key 里出现 authCode / refund-back 这类写法
  const norm = (x) => x.replace(/[^A-Za-z0-9]+/g, '_').toUpperCase();
  return suffix ? `${functionCode}${POINT_SEP}${norm(suffix)}` : functionCode;
}

const q = (v) => v === null || v === undefined || v === '' ? 'NULL' : `'${String(v).replace(/'/g, "''")}'`;

// ── UI 码 → 后端码
const map = {};
for (const m of pmSrc.matchAll(/"([^"]+)":\s*(UNIMPLEMENTED|"([^"]+)")/g))
  map[m[1]] = m[2] === 'UNIMPLEMENTED' ? null : m[3];

// ── 菜单树
const secs = [];
for (const m of nav.matchAll(/key:\s*"(\w+)",\s*label:\s*"([^"]+)"([\s\S]*?)(?=key:\s*"\w+",\s*label:|$)/g)) {
  const body = m[3];
  const icon = (body.match(/icon:\s*"([^"]+)"/) || [])[1] || null;
  const href = (body.match(/href:\s*"([^"]+)"/) || [])[1] || null;
  const leaves = [...body.matchAll(/\{\s*href:\s*"([^"]+)",\s*label:\s*"([^"]+)",\s*perm:\s*"([^"]+)"([^}]*)\}/g)]
    .map((l) => ({
      href: l[1], label: l[2], perm: l[3],
      group: (l[4].match(/group:\s*"([^"]+)"/) || [])[1] || null,
      matrix: (l[4].match(/matrix:\s*"([^"]+)"/) || [])[1] || null,
      ready: /ready:\s*true/.test(l[4]),
    }));
  secs.push({ key: m[1], label: m[2], icon, href, leaves });
}

// ── 角色 → 后端码（从 Java 源码抽，与 BizEndpointPermTest 同一手法）
const consts = {};
for (const m of permsJava.matchAll(/String\s+([A-Z_]+)\s*=\s*"([^"]+)"/g)) consts[m[1]] = m[2];
const roles = {};
const block = permsJava.slice(permsJava.indexOf('ROLE_PERMS = Map.'));
for (const m of block.matchAll(/Map\.entry\("([A-Z_]+)",\s*List\.of\(([\s\S]*?)\)\)/g)) {
  const codes = new Set();
  for (const lit of m[2].matchAll(/"(\*|[a-z][a-z:_-]*)"|\b([A-Z][A-Z_]+)\b/g)) {
    if (lit[1]) codes.add(lit[1]);
    else if (lit[2] && consts[lit[2]]) codes.add(consts[lit[2]]);
  }
  roles[m[1]] = [...codes];
}
const ROLE_NAME = { SUPER_ADMIN:'超级管理员', MERCHANT_BD:'商家运营', PRODUCT_OPS:'商品运营',
  CAMPAIGN_OPS:'活动运营', COMMUNITY_OPS:'社区运营', AUDITOR:'审核员', CS:'客服',
  FINANCE:'财务', RISK:'风控', ANALYST:'数据分析', TECH_OPS:'技术运维',
  BD:'商家运营', GOODS_OPS:'商品运营', SUPPORT:'客服' };

const out = [];
out.push('-- ⚠️ 由 ops-web/scripts/gen-perm-seed.mjs 生成，**请勿手改**。');
out.push('-- 真源：lib/nav.ts × lib/perm-map.ts × Perms.java —— 改了它们要重跑生成器。');
out.push('');

// functions + points
const points = [];
secs.forEach((s, si) => {
  const fc = `OPS_${s.key.toUpperCase()}`;
  out.push(`INSERT INTO sys_function (function_code, name, end_code, icon, href, sort, enabled, created_at, updated_at) VALUES (${q(fc)}, ${q(s.label)}, 'OPS', ${q(s.icon)}, ${q(s.href)}, ${(si + 1) * 10}, 1, NOW(), NOW());`);
  s.leaves.forEach((l, li) => {
    const back = map[l.perm];
    const status = back === undefined ? 'UNMAPPED' : back === null ? 'NOT_IMPLEMENTED' : 'IMPLEMENTED';
    const pc = pointCodeOf(fc, l.href);
    points.push({ pc, perm: back || null, status });
    out.push(`INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES (${q(pc)}, ${q(fc)}, ${q(l.label)}, ${q(l.group)}, ${q(l.href)}, ${q(l.perm)}, ${q(back)}, ${q(status)}, ${l.ready ? 1 : 0}, ${q(l.matrix)}, 'MENU', ${(li + 1) * 10}, NOW(), NOW());`);
  });
});

/*
 * ── 页面内的按钮级授权（ACTION）──
 *
 * UI_PERM_MAP 有 64 个码，菜单只用到 54 个 —— 剩下 10 个挂在页面内的按钮上
 * （「编辑自提点」用 community:pickup:update → industry:manage）。
 *
 * **不收它们的话，库里「角色 → 权限码」的集合与 Perms.java 对不上**：
 * COMMUNITY_OPS 有 industry:manage，而没有任何功能点带这个码。
 * 一致性守卫第一次跑就是这么红的。
 */
const menuPerms = new Set(secs.flatMap(s => s.leaves.map(l => l.perm)));
const prefixToFn = {};
secs.forEach(s => { prefixToFn[s.key] = `OPS_${s.key.toUpperCase()}`; });
let actionSeq = 0;
for (const ui of Object.keys(map)) {
  if (menuPerms.has(ui)) continue;
  const back = map[ui];
  const status = back === null ? 'NOT_IMPLEMENTED' : 'IMPLEMENTED';
  const fc = prefixToFn[ui.split(':')[0]] || 'OPS_SYSTEM';
  // ACTION 点同样改成稳定派生：按 UI 码，不按出现顺序
  const pc = `ACT${POINT_SEP}${ui.replace(/[^A-Za-z0-9]+/g, '_').toUpperCase()}`;
  actionSeq++;
  points.push({ pc, perm: back || null, status });
  out.push(`INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES (${q(pc)}, ${q(fc)}, ${q(ui)}, '页面内操作', NULL, ${q(ui)}, ${q(back)}, ${q(status)}, 1, NULL, 'ACTION', ${900 + actionSeq}, NOW(), NOW());`);
}

/*
 * ── 兜底：Perms.ROLE_PERMS 用到、但 UI_PERM_MAP 映射不出来的后端码 ──
 *
 * **不补这一段，授权会静默蒸发**。角色→功能点是靠 perm_code 对上的：
 * 一个后端码如果没有任何功能点带它，那条授权就无处安放，
 * 于是「库里角色→权限码」少掉一截，而**没有任何报错** ——
 * 表现是那个角色调某个接口 403，界面上什么线索都没有。
 *
 * 2026-08-12 实测：权限码从 16 个细化到 68 个之后，Perms.java 长出了 22 个
 * UI_PERM_MAP 里没有对应项的码，FINANCE 一个角色就丢了 9/16。
 *
 * 这些点标 ACTION：它们没有菜单入口（前端还没给这些码做界面），
 * 但**授权必须存在** —— 后端端点是真的，只是运营端还看不到它。
 */
const coveredBackend = new Set(points.map(p => p.perm).filter(Boolean));
const roleBackendCodes = new Set(Object.values(roles).flat().filter(c => c !== '*'));
for (const code of [...roleBackendCodes].filter(c => !coveredBackend.has(c)).sort()) {
  const fc = prefixToFn[code.split(':')[0]] || 'OPS_SYSTEM';
  const pc = `ACT${POINT_SEP}${code.replace(/[^A-Za-z0-9]+/g, '_').toUpperCase()}`;
  actionSeq++;
  points.push({ pc, perm: code, status: 'IMPLEMENTED' });
  out.push(`INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES (${q(pc)}, ${q(fc)}, ${q(code)}, '仅后端', NULL, NULL, ${q(code)}, 'IMPLEMENTED', 0, NULL, 'ACTION', ${900 + actionSeq}, NOW(), NOW());`);
}

out.push('');
// roles + role_point
Object.entries(roles).forEach(([code, granted], i) => {
  // 通配（超管）在角色上标出来 —— 库里没有 `*` 这个码，见 sys_role.wildcard 的注释
  const wildcard = granted.includes('*') ? 1 : 0;
  out.push(`INSERT INTO sys_role (role_code, name, end_code, builtin, wildcard, sort, created_at, updated_at) VALUES (${q(code)}, ${q(ROLE_NAME[code] || code)}, 'OPS', 1, ${wildcard}, ${(i + 1) * 10}, NOW(), NOW());`);
  for (const p of points) {
    /*
     * 三种情况，**必须分开判**：
     *   ① granted 含 * ............................. 超管，全给
     *   ② p.perm 在 granted 里 ...................... 正常授权
     *   ③ p.perm 为 NULL 且 backend 已实现 .......... 不受权限约束，谁都能用
     *
     * ⚠️ **未实现的功能点也是 perm=NULL，但那是「谁都不能用」** ——
     * 把两者一起当成「谁都能用」的话，BD 会在菜单上看到整个结算分区
     * （它的三个未实现叶子被当成免权限项授给了所有人）。
     * 方案里专门强调过这两个 NULL 是两回事，写生成器时自己先混了一次。
     *
     * 未实现项**只授给超管**：留着关联是为了「后端补齐那天翻个状态就能用」，
     * 而超管本来就该看到全部 —— 其余角色等那天按真实权限码重算。
     */
    const free = p.perm === null && p.status === 'IMPLEMENTED';
    const ok = granted.includes('*') || (p.perm && granted.includes(p.perm)) || free;
    if (ok) out.push(`INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at) VALUES (${q(code)}, ${q(p.pc)}, 'OPS', NOW(), NOW());`);
  }
});
out.push('');
out.push('-- 人员 × 角色：把 sys_ops_staff.roles 这个 JSON 列展开成行。');
out.push('-- JSON 列查不了「哪些人有 FINANCE 角色」，而那是权限审计最常问的一句。');
out.push('--');
out.push('-- ⚠️ 存量数据在这里展开；**新账号由 DevSeeder / setStaffRole 同步写**。');
out.push('--    只靠这条迁移的话，开发库里一行都插不出来 —— 员工是应用启动后才写的，');
out.push('--    迁移执行时 sys_ops_staff 还是空的。那一版的症状是「BD 登录后菜单全空」，');
out.push('--    而库里的角色配置看着完全正常。');
for (const code of Object.keys(roles)) {
  out.push(`INSERT INTO sys_role_member (end_code, subject_no, role_code, granted_at, created_at, updated_at) SELECT 'OPS', staff_no, ${q(code)}, UNIX_TIMESTAMP()*1000, NOW(), NOW() FROM sys_ops_staff WHERE deleted = 0 AND roles LIKE ${q('%"' + code + '"%')};`);
}
/*
 * ── `--markdown`：把同一份种子渲染成文档里的数据清单 ──
 *
 * **解析自己刚生成的 SQL**，而不是另走一遍数据。看着绕，但它保证了一件事：
 * 文档描述的就是种子本身，两者不可能对不上。
 * 此前那份清单是手写的，三个月后写着「68 个功能点」而库里已经 104 个 ——
 * 一份错的清单比没有清单更坏，因为人会照着它做判断。
 */
function renderMarkdown(sql) {
  const val = (line) => [...line.matchAll(/'((?:[^']|'')*)'|\b(NULL)\b/g)]
    .map((m) => (m[2] ? null : m[1].replace(/''/g, "'")));
  const fns = [], pts = [], rls = [], rps = [];
  for (const l of sql) {
    if (l.startsWith('INSERT INTO sys_function ')) fns.push(val(l));
    else if (l.startsWith('INSERT INTO sys_function_point ')) pts.push(val(l));
    else if (l.startsWith('INSERT INTO sys_role ')) rls.push(val(l));
    else if (l.startsWith('INSERT INTO sys_role_point ')) rps.push(val(l));
  }
  // 字段序与上面的 INSERT 一致
  const P = pts.map((v) => ({ code: v[0], fn: v[1], name: v[2], group: v[3], href: v[4],
                              ui: v[5], perm: v[6], status: v[7], matrix: v[8], type: v[9] }));
  const menu = P.filter((x) => x.type === 'MENU');
  const notImpl = P.filter((x) => x.status === 'NOT_IMPLEMENTED');
  const L = [];
  L.push('<!-- BEGIN:generated · 由 `node ops-web/scripts/gen-perm-seed.mjs --doc` 产出，**请勿手改** -->');
  L.push('');
  L.push(`**${fns.length} 个功能 · ${P.length} 个功能点**（菜单项 ${menu.length} · 页面内操作 ${P.length - menu.length}）`
       + ` · 其中后端未实现 ${notImpl.length} · 二级分组 ${new Set(menu.map((x) => x.group).filter(Boolean)).size} 个`);
  L.push('');
  L.push('### `point_code` 是 href 派生的，不是序号');
  L.push('');
  L.push('`/merchants?tab=list` → `OPS_MERCHANT__TAB_LIST`。**这一条很重要**：');
  L.push('序号编码下插入一个叶子会让其后全部右移，而 `sys_role_point` 存的正是 `point_code`');
  L.push('—— 编号一移，既有授权会静默指向另一个功能，且是放宽方向。');
  L.push('');
  L.push('### sys_function（运营端 ' + fns.length + ' 行）');
  L.push('');
  L.push('| function_code | 名称 | 功能点 | 其中后端未实现 |');
  L.push('|---|---|--:|--:|');
  for (const f of fns) {
    const mine = P.filter((x) => x.fn === f[0]);
    const bad = mine.filter((x) => x.status === 'NOT_IMPLEMENTED').length;
    L.push(`| \`${f[0]}\` | ${f[1]} | ${mine.length} | ${bad ? '**' + bad + '**' : 0} |`);
  }
  L.push('');
  L.push('### sys_function_point（菜单项 ' + menu.length + ' 行）');
  L.push('');
  L.push('> 页面内操作（`ACTION`，' + (P.length - menu.length) + ' 行）不列：它们没有菜单入口，');
  L.push('> 存在的意义是**承载授权** —— 后端有码而运营端还没有界面的那些。');
  L.push('');
  L.push('| point_code | 分区 | 二级分组 | 功能点 | perm_code | 后端 |');
  L.push('|---|---|---|---|---|:--:|');
  for (const x of menu) {
    L.push(`| \`${x.code}\` | ${x.fn.replace('OPS_', '')} | ${x.group ?? '—'} | ${x.name} `
         + `| ${x.perm ? '`' + x.perm + '`' : '—'} | ${x.status === 'IMPLEMENTED' ? '✅' : '⚠️'} |`);
  }
  L.push('');
  L.push('### sys_role（' + rls.length + ' 行）与授权数');
  L.push('');
  L.push('| role_code | 名称 | 通配 | 功能点数 |');
  L.push('|---|---|:--:|--:|');
  // 角色行要取 wildcard 这个**数字**字段，而 val() 只抓引号串 —— 单独按位置解析
  for (const line of sql.filter((l) => l.startsWith('INSERT INTO sys_role '))) {
    const m = line.match(/VALUES \('([^']+)', '((?:[^']|'')*)', '[^']*', \d+, (\d+)/);
    if (!m) continue;
    const [, code, name, wildcard] = m;
    const n = rps.filter((x) => x[0] === code).length;
    L.push(`| \`${code}\` | ${name.replace(/''/g, "'")} | ${wildcard === '1' ? '✅' : ''} `
         + `| ${wildcard === '1' ? '全部' : n} |`);
  }
  L.push('');
  L.push('<!-- END:generated -->');
  return L.join('\n');
}

const args = process.argv.slice(2);
if (args.includes('--markdown') || args.includes('--doc')) {
  const md = renderMarkdown(out);
  if (args.includes('--doc')) {
    const DOC = AT('../../docs/technical/design/权限配置落库-数据库设计与数据清单.md');
    const cur = readFileSync(DOC, 'utf8');
    const b = cur.indexOf('<!-- BEGIN:generated');
    const e = cur.indexOf('<!-- END:generated -->');
    if (b < 0 || e < 0) {
      console.error('文档里找不到 <!-- BEGIN:generated --> / <!-- END:generated --> 标记');
      process.exit(1);
    }
    writeFileSync(DOC, cur.slice(0, b) + md + cur.slice(e + '<!-- END:generated -->'.length));
    console.error('已写回文档');
  } else {
    console.log(md);
  }
} else {
  console.log(out.join('\n'));
}
