import { readFileSync } from 'node:fs';
import { expect, it } from 'vitest';
import { NAV } from './nav';
import { POINT_CODES } from './point-codes';
const sql = readFileSync(new URL('../../backend/shop-app/src/main/resources/db/migration/V657__ops_compact_navigation.sql', import.meta.url), 'utf8');
it('moves each menu by its frozen point id without rewriting grants, permissions or gates', () => {
  expect(sql).not.toMatch(/\b(?:DELETE|TRUNCATE|DROP)\b/i);
  expect(sql).not.toMatch(/sys_role(?:_point|_member)?\b|\b(?:perm_code|ui_perm_code|backend_status|ui_ready|gated_by)\s*=/i);
  const moves = new Map<string, string[]>();
  for (const line of sql.split('\n')) {
    const update = line.match(/UPDATE sys_function_point SET function_code='([^']+)',name='([^']+)'/);
    const where = line.split('WHERE point_code')[1];
    if (update && where) for (const code of [...where.matchAll(/'([^']+)'/g)].map(m => m[1]).filter(code => code !== 'MENU')) moves.set(code, [update[1], update[2]]);
  }
  for (let i = 1; i <= 8; i++) expect(moves.get(`OPS_PAY_0${i}`)?.[0]).toBe('OPS_FINANCE');
  for (const section of NAV) for (const leaf of section.children ?? []) {
    const fn = `OPS_${section.key.toUpperCase()}`;
    const point = POINT_CODES[`${fn}\t${leaf.href}`] ?? POINT_CODES[leaf.href];
    expect(point, leaf.href).toBeDefined();
    expect(moves.get(point), leaf.href).toEqual([fn, leaf.label]);
  }
});
