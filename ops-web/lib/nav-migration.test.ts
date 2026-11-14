import { readFileSync } from 'node:fs';
import { expect, it } from 'vitest';
import { NAV } from './nav';
import { POINT_CODES } from './point-codes';
import frozenBefore from './__fixtures__/platform-reporting-before-point-codes.json';
const read = (name: string) => readFileSync(new URL(`../../backend/shop-app/src/main/resources/db/migration/${name}`, import.meta.url), 'utf8');
const v657 = read('V657__ops_compact_navigation.sql');
const v658 = read('V658__ops_menu_matrix_consolidation.sql');
const v668 = read('V668__ops_platform_settings_navigation.sql');
const v669 = read('V669__ops_platform_mall_navigation.sql');
const v670 = read('V670__ops_platform_purchase_reporting.sql');
const REPORTING_HREFS = new Set(['/mall?tab=global-orders', '/mall?tab=global-claims', '/mall?tab=cash-summary', '/mall?tab=cash-details']);
const NO_GRANT_OR_GATE = /\b(?:perm_code|ui_perm_code|backend_status|ui_ready|gated_by)\s*=/i;

/** Later migrations override earlier ones: the menu the database ends up with. */
function menuMoves(...sqls: string[]): Map<string, string[]> {
  const moves = new Map<string, string[]>();
  for (const sql of sqls) for (const line of sql.split('\n')) {
    const insert = line.match(/INSERT INTO sys_function_point.* VALUES \('([^']+)','([^']+)','([^']+)'/);
    if (insert) moves.set(insert[1], [insert[2], insert[3]]);
    const update = line.match(/UPDATE sys_function_point SET function_code='([^']+)',name='([^']+)'/);
    const where = line.split('WHERE point_code')[1];
    if (update && where) for (const code of [...where.matchAll(/'([^']+)'/g)].map(m => m[1]).filter(code => code !== 'MENU')) moves.set(code, [update[1], update[2]]);
  }
  return moves;
}

it('V657 moves each menu by its frozen point id without rewriting grants, permissions or gates', () => {
  expect(v657).not.toMatch(/\b(?:DELETE|TRUNCATE|DROP)\b/i);
  expect(v657).not.toMatch(/sys_role(?:_point|_member)?\b/i);
  expect(v657).not.toMatch(NO_GRANT_OR_GATE);
  const moves = menuMoves(v657);
  for (let i = 1; i <= 8; i++) expect(moves.get(`OPS_PAY_0${i}`)?.[0]).toBe('OPS_FINANCE');
});

it('V658 leaves every role grant, permission and gate in place except merging the duplicate refund link', () => {
  expect(v658).not.toMatch(/\b(?:TRUNCATE|DROP)\b/i);
  expect(v658).not.toMatch(NO_GRANT_OR_GATE);
  const deletes = v658.split('\n').filter(l => /^\s*DELETE\b/i.test(l));
  expect(deletes).toHaveLength(2);
  for (const d of deletes) expect(d).toContain("point_code = 'OPS_AFTERSALE__TAB_REFUND_BACK'");
  // 授权只「并」不「收」：唯一写 sys_role_point 的 INSERT 把售后点授权复制到财务同名点
  const inserts = v658.split('\n').filter(l => /^\s*INSERT INTO sys_role_point\b/i.test(l));
  expect(inserts).toHaveLength(1);
  expect(v658).toContain("SELECT a.role_code, 'OPS_FINANCE__TAB_REFUND_BACK'");
  expect(v658.indexOf('INSERT INTO sys_role_point')).toBeLessThan(v658.indexOf('DELETE FROM sys_role_point'));
});

it('the database menu after navigation migrations matches nav.ts point by point', () => {
  const moves = menuMoves(v657, v658, v668, v669, v670);
  for (const section of NAV) for (const leaf of section.children ?? []) {
    const fn = `OPS_${section.key.toUpperCase()}`;
    const point = POINT_CODES[`${fn}\t${leaf.href}`] ?? POINT_CODES[leaf.href];
    expect(point, leaf.href).toBeDefined();
    expect(moves.get(point), leaf.href).toEqual([fn, leaf.label]);
  }
  for (const fn of ['OPS_MEMBER', 'OPS_POINTS', 'OPS_MESSAGE']) {
    expect(NAV.some(s => `OPS_${s.key.toUpperCase()}` === fn), fn).toBe(false);
    expect(v658).toContain(`'${fn}'`);
  }
  const functions = new Map<string, { name: string; sort: number }>();
  for (const sql of [v657, v658, v668, v669]) for (const line of sql.split("\n")) {
    const m = line.match(/UPDATE sys_function SET name='([^']+)',sort=(\d+),.*WHERE function_code='([^']+)'/);
    if (m) functions.set(m[3], { name: m[1], sort: Number(m[2]) });
  }
  for (const section of NAV) expect(functions.get(`OPS_${section.key.toUpperCase()}`)?.name).toBe(section.label);
  expect([...NAV].sort((a, b) => functions.get(`OPS_${a.key.toUpperCase()}`)!.sort - functions.get(`OPS_${b.key.toUpperCase()}`)!.sort).map(s => s.key)).toEqual(NAV.map(s => s.key));
});

it('V668 changes presentation only for the existing settings leaves', () => {
  expect(v668).not.toMatch(/\b(?:INSERT|DELETE|TRUNCATE|DROP|ALTER)\b/i);
  expect(v668).not.toMatch(/sys_role(?:_point|_member)?\b/i);
  expect(v668).not.toMatch(NO_GRANT_OR_GATE);
  expect(menuMoves(v668).size).toBe(25);
  for (const line of v668.split("\n").filter(l => l.startsWith("UPDATE sys_function_point"))) {
    expect(line).toContain("function_code='OPS_IAM'");
    expect(line).toContain("point_type='MENU'");
    expect(line.split(" WHERE ")[0]).not.toMatch(/\b(?:href|point_type)\s*=/i);
  }
});

it('V668 settings group and sort match every settings leaf', () => {
  const details = new Map<string, { group: string; sort: number }>();
  for (const line of v668.split("\n")) {
    const match = line.match(/group_name='([^']+)',sort=(\d+),.*WHERE point_code='([^']+)'/);
    if (match) details.set(match[3], { group: match[1], sort: Number(match[2]) });
  }
  for (const [i, leaf] of NAV.find(s => s.key === 'iam')!.children!.entries()) {
    const point = POINT_CODES[`OPS_IAM\t${leaf.href}`] ?? POINT_CODES[leaf.href];
    expect(details.get(point), leaf.href).toEqual({ group: leaf.group, sort: (i + 1) * 10 });
  }
});

it('V669 preserves all permission fields and matches commerce group/name/sort', () => {
  expect(v669).not.toMatch(/\b(?:INSERT|DELETE|TRUNCATE|DROP|ALTER)\b/i);
  expect(v669).not.toMatch(/sys_role(?:_point|_member)?\b/i);
  expect(v669).not.toMatch(NO_GRANT_OR_GATE);
  expect(menuMoves(v669).size).toBe(10);
  const details = new Map<string, { name: string; group: string; sort: number }>();
  for (const line of v669.split("\n").filter(l => l.startsWith('UPDATE sys_function_point'))) {
    expect(line).toContain("function_code='OPS_COMMERCE'");
    expect(line).toContain("point_type='MENU'");
    expect(line.split(' WHERE ')[0]).not.toMatch(/\b(?:href|point_type)\s*=/i);
    const m = line.match(/name='([^']+)',group_name='([^']+)',sort=(\d+),.*WHERE point_code='([^']+)'/);
    if (m) details.set(m[4], { name: m[1], group: m[2], sort: Number(m[3]) });
  }
  for (const [i, leaf] of NAV.find(s => s.key === 'commerce')!.children!.filter(l => !REPORTING_HREFS.has(l.href)).entries()) {
    const point = POINT_CODES[`OPS_COMMERCE\t${leaf.href}`] ?? POINT_CODES[leaf.href];
    expect(details.get(point), leaf.href).toEqual({ name: leaf.label, group: leaf.group, sort: (i + 1) * 10 });
  }
});

it('V670 adds exactly four reporting menus and grants only SUPER_ADMIN without altering existing grants', () => {
  expect(v670).not.toMatch(/\b(?:UPDATE|DELETE|TRUNCATE|DROP|ALTER)\s+(?:sys_|TABLE)/i);
  const inserts = v670.split('\n').filter(l => l.startsWith('INSERT INTO sys_function_point'));
  const grants = v670.split('\n').filter(l => l.startsWith('INSERT INTO sys_role_point'));
  expect(inserts).toHaveLength(4); expect(grants).toHaveLength(4);
  const newLeaves = NAV.find(s => s.key === 'commerce')!.children!.filter(l => REPORTING_HREFS.has(l.href));
  for (const leaf of newLeaves) {
    const point = POINT_CODES[leaf.href];
    const line = inserts.find(l => l.includes(`'${point}'`))!;
    expect(line).toContain(`'OPS_COMMERCE','${leaf.label}','${leaf.group}','${leaf.href}','${leaf.perm}','${leaf.perm}'`);
    expect(line).toContain("'IMPLEMENTED',1,'P-11.2','MENU'");
    expect(grants.find(l => l.includes(`'${point}'`))).toContain(`SELECT 'SUPER_ADMIN','${point}','OPS'`);
  }
  for (const grant of grants) {
    expect(grant).toContain("role_code='SUPER_ADMIN'");
    expect(grant).toContain('NOT EXISTS');
    expect(grant).not.toMatch(/'(?:FINANCE|PLATFORM_OPS|COMMUNITY_OPS|TECH_OPS|PRODUCT_OPS)'/);
  }
});

it('retains every frozen point identity from the original151 leaves', () => {
  expect(Object.keys(frozenBefore)).toHaveLength(151);
  expect(Object.keys(POINT_CODES)).toHaveLength(155);
  for (const [href, code] of Object.entries(frozenBefore)) expect(POINT_CODES[href], href).toBe(code);
  expect(Object.keys(POINT_CODES).filter(href => !(href in frozenBefore)).sort()).toEqual([...REPORTING_HREFS].sort());
});
