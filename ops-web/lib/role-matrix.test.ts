import { expect, it } from 'vitest';
import type { MenuPoint } from './types';
import { roleMatrixCell } from './role-matrix';
const point: MenuPoint = { pointCode: 'P1', name: '查看', backendStatus: 'IMPLEMENTED', uiReady: true, pointType: 'MENU', sort: 1 };
it('shows exact point grants, including no grant', () => {
  expect(roleMatrixCell(point, ['P1'])).toBe('granted');
  expect(roleMatrixCell(point, ['OTHER'])).toBe('denied');
  expect(roleMatrixCell(point, [])).toBe('denied');
});
it('distinguishes unavailable, planned and backend-only actions', () => {
  expect(roleMatrixCell({ ...point, backendStatus: 'NOT_IMPLEMENTED' }, ['P1'])).toBe('unavailable');
  expect(roleMatrixCell({ ...point, backendStatus: 'UNMAPPED' }, ['P1'])).toBe('unavailable');
  expect(roleMatrixCell({ ...point, uiReady: false }, ['P1'])).toBe('planned');
  expect(roleMatrixCell({ ...point, uiReady: false, pointType: 'ACTION' }, ['P1'])).toBe('granted');
});
