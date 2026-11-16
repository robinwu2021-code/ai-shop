import { expect, it, vi } from 'vitest';
import { BACKEND_ROLE_PERMS } from './permissions';
import { platformCatalog, PLATFORM_MENU_ALIASES } from './platform-nav';
import { visibleLeaves } from './nav';
import { POINT_CODES } from './point-codes';
const auth = vi.hoisted(() => ({ perms: ['*'] as string[] }));
vi.mock('@/lib/api/mocks/_wait', () => ({ wait: async <T>(value: T) => value }));
vi.mock('@/lib/auth', async original => ({ ...await original<typeof import('./auth')>(), currentAuth: () => auth }));
import { dashboardMock } from './api/mocks/dashboard';

it.each(Object.entries(BACKEND_ROLE_PERMS))('mock menu agrees with canonical and legacy catalog for %s', async (_role, perms) => {
  auth.perms = perms;
  const menu = await dashboardMock.menu();
  const leaves = menu.flatMap(f => f.points);
  expect(leaves.map(p => p.href).sort()).toEqual(platformCatalog().flatMap(s => visibleLeaves(s, perms).map(l => l.href)).sort());
  for (const point of leaves) {
    expect(point.pointCode, point.href!).toBe(PLATFORM_MENU_ALIASES.find(a => a.href === point.href)?.pointCode ?? POINT_CODES[point.href!]);
  }
});
