import { readFileSync } from 'node:fs';
import { expect, it } from 'vitest';
import { NAV } from './nav';
import { POINT_CODES } from './point-codes';
const read = (name: string) => readFileSync(new URL(`../../backend/shop-app/src/main/resources/db/migration/${name}`, import.meta.url), 'utf8');
const v657 = read('V657__ops_compact_navigation.sql');
const v658 = read('V658__ops_menu_matrix_consolidation.sql');
const NO_GRANT_OR_GATE = /\b(?:perm_code|ui_perm_code|backend_status|ui_ready|gated_by)\s*=/i;

/** Later migrations override earlier ones: the menu the database ends up with. */
function menuMoves(...sqls: string[]): Map<string, string[]> {
  const moves = new Map<string, string[]>();
  for (const sql of sqls) for (const line of sql.split('\n')) {
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

it('the database menu after V657 + V658 matches nav.ts point by point', () => {
  const moves = menuMoves(v657, v658);
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
  for (const [i, section] of NAV.entries()) {
    expect(v658).toContain(`UPDATE sys_function SET name='${section.label}',sort=${(i + 1) * 10},`);
  }
});
