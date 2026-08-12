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
import { readFileSync } from 'node:fs';

const nav = readFileSync('lib/nav.ts', 'utf8');
const pmSrc = readFileSync('lib/perm-map.ts', 'utf8');
const permsJava = readFileSync('../backend/shop-base/src/main/java/ai/neargo/shop/auth/Perms.java', 'utf8');

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
    const pc = `${fc}_${String(li + 1).padStart(2, '0')}`;
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
  const pc = `ACT_${String(++actionSeq).padStart(2, '0')}`;
  points.push({ pc, perm: back || null, status });
  out.push(`INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at) VALUES (${q(pc)}, ${q(fc)}, ${q(ui)}, '页面内操作', NULL, ${q(ui)}, ${q(back)}, ${q(status)}, 1, NULL, 'ACTION', ${900 + actionSeq}, NOW(), NOW());`);
}
out.push('');
// roles + role_point
Object.entries(roles).forEach(([code, granted], i) => {
  out.push(`INSERT INTO sys_role (role_code, name, end_code, builtin, sort, created_at, updated_at) VALUES (${q(code)}, ${q(ROLE_NAME[code] || code)}, 'OPS', 1, ${(i + 1) * 10}, NOW(), NOW());`);
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
console.log(out.join('\n'));
