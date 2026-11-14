import { expect, it } from 'vitest';
import { NAV, findActiveSection, visibleTabKeys } from './nav';
import { tNav } from './i18n/nav-labels';
import { BACKEND_ROLE_PERMS } from './permissions';
it('keeps short translated names and separate merchant, commerce and inventory routes', () => {
  for (const section of NAV) for (const label of [section.label, ...(section.children ?? []).flatMap(l => [l.label, l.group!])].filter(Boolean)) {
    expect([...label].length, label).toBeLessThanOrEqual(4);
    expect(tNav(label, 'en'), label).not.toMatch(/[\u3400-\u9fff]/);
  }
  expect(findActiveSection('/mall')?.key).toBe('commerce');
  expect(findActiveSection('/inventory')?.key).toBe('inventory');
  expect(findActiveSection('/merchants')?.key).toBe('merchant');
  expect(findActiveSection('/merchants', ['*'], undefined, NAV, 'plans')?.key).toBe('commerce');
});
it('keeps split page tabs permission-filtered across sections', () => {
  expect(visibleTabKeys('/merchants', ['audit', 'list', 'plans', 'plan-defs'], BACKEND_ROLE_PERMS.MERCHANT_BD)).toContain('plans');
  expect(visibleTabKeys('/merchants', ['audit', 'list', 'plans', 'plan-defs'], BACKEND_ROLE_PERMS.FINANCE)).not.toContain('plans');
});

it('keeps canonical ownership for duplicate cross-links', () => {
  expect(findActiveSection('/finance', ['*'], undefined, NAV, 'refund-back')?.key).toBe('finance');
});
