import { describe, expect, it } from 'vitest';
import { NAV, visibleLeaves } from './nav';
import { BACKEND_ROLE_PERMS } from './permissions';
import { platformNav, platformCatalog, platformSections, platformDefaultHref } from './platform-nav';

describe('platform primary navigation', () => {
  it('projects five roots and preserves the unified admission aliases', () => {
    expect(platformNav().map(s => s.key)).toEqual(['iam', 'commerce', 'customers', 'paymentMerchant', 'orders']);
    expect(NAV).toHaveLength(13);
    expect(NAV.flatMap(s => s.children ?? [])).toHaveLength(153);
    const admissions=NAV.flatMap(s=>s.children??[]).find(l=>l.href==='/merchants')!;
    expect(admissions.legacyHrefs).toEqual(expect.arrayContaining(['/merchants?tab=on-behalf','/merchants?tab=self-operated']));
    expect(admissions.anyPerms).toEqual(expect.arrayContaining(['merchant:apply:audit','merchant:apply:onbehalf','merchant:selfop:create']));
    for (const s of NAV) for (const l of s.children ?? []) expect(platformCatalog().flatMap(s => s.children ?? [])).toContainEqual(l);
    expect(platformNav()[1].children).toEqual(NAV.find(s => s.key === 'commerce')!.children);
  });
  it('uses canonical customer and payment routes without changing legacy onboarding authorization', () => {
    const nav = platformNav();
    expect(nav[2].children?.map(l => [l.href, l.perm])).toEqual([
      ['/customers?tab=overview', 'dashboard:overview:read'], ['/customers?tab=users', 'customer:user:read'],
      ['/customers?tab=members', 'member:member:read'], ['/customers?tab=stores', 'merchant:merchant:read'],
      ['/customers?tab=chains', 'merchant:merchant:read'],
    ]);
    expect(nav[3].children?.map(l => [l.href, l.perm])).toEqual([
      ['/merchants?tab=payment', 'pay:merchant:view'], ['/merchants?tab=payment-onboarding', 'pay:onboarding:view'],
    ]);
    expect(nav[4].children?.map(l => l.href)).toContain('/orders?tab=payments');
    expect(NAV.flatMap(s => s.children ?? []).find(l => l.href === '/merchants?tab=onboarding')?.perm).toBe('merchant:admission:read');
  });
  it.each(Object.entries(BACKEND_ROLE_PERMS))('preserves the authorized legacy catalog for %s without empty primary roots', (_role, perms) => {
    const legacy = NAV.flatMap(s => visibleLeaves(s, perms).map(l => l.href)).sort();
    expect(platformCatalog().flatMap(s => visibleLeaves(s, perms).map(l => l.href)).filter(h => legacy.includes(h)).sort()).toEqual(legacy);
    for (const s of platformSections(perms)) expect(visibleLeaves(s, perms).length).toBeGreaterThan(0);
  });
  it('honors exact database point grants for custom roles', () => {
    const allowed = new Set(['/customers?tab=stores', '/orders?tab=payments']);
    expect(platformSections([], allowed).map(s => s.key)).toEqual(['customers', 'orders']);
    expect(platformSections([], new Set(['/products?tab=templates']))).toEqual([]);
    expect(platformSections(['product:sku:audit'])).toEqual([]);
  });
});

it('has an explicit primary root matrix for all twelve backend roles', () => {
  const expected: Record<string, string[]> = {
    SUPER_ADMIN: ['iam', 'commerce', 'customers', 'paymentMerchant', 'orders'],
    MERCHANT_BD: ['commerce', 'customers', 'orders'],
    PRODUCT_OPS: ['iam', 'customers', 'orders'],
    CS: ['iam', 'customers', 'orders'],
    CAMPAIGN_OPS: ['customers', 'orders'],
    COMMUNITY_OPS: ['iam', 'customers', 'orders'],
    AUDITOR: ['iam', 'customers'],
    FINANCE: ['iam', 'customers', 'orders'],
    RISK: ['customers', 'orders'],
    ANALYST: [], TECH_OPS: ['iam'], ELEC_ADMIN: ['customers'],
  };
  expect(Object.keys(BACKEND_ROLE_PERMS).sort()).toEqual(Object.keys(expected).sort());
  for (const [role, perms] of Object.entries(BACKEND_ROLE_PERMS)) {
    expect(platformSections(perms).map(s => s.key), role).toEqual(expected[role]);
    const hrefs = platformSections(perms).flatMap(s => visibleLeaves(s, perms).map(l => l.href));
    if (role !== 'SUPER_ADMIN') {
      expect(hrefs).not.toContain('/customers?tab=users');
      expect(hrefs).not.toContain('/merchants?tab=payment');
      expect(hrefs).not.toContain('/merchants?tab=payment-onboarding');
      expect(hrefs).not.toContain('/merchants?tab=payment-channels');
      expect(hrefs.some(href => href === '/orders?tab=payments')).toBe(role === 'FINANCE');
    }
  }
});

it('seeds aliases from exact source point grants while retaining existing action identities', async () => {
  const { execFileSync } = await import('node:child_process');
  const { readFileSync } = await import('node:fs');
  const { PLATFORM_MENU_ALIASES } = await import('./platform-nav');
  const sql = execFileSync('node', ['scripts/gen-perm-seed.mjs'], { encoding: 'utf8' });
  const migration = readFileSync(new URL('../../backend/shop-app/src/main/resources/db/migration/V671__ops_platform_primary_navigation.sql', import.meta.url), 'utf8');
  expect(migration).not.toMatch(/\b(?:DELETE|UPDATE|TRUNCATE|ALTER|DROP)\s+(?:sys_|TABLE)/i);
  const grants = new Map<string, Set<string>>();
  for (const m of sql.matchAll(/INSERT INTO sys_role_point \([^)]*\) VALUES \('([^']+)', '([^']+)'/g)) {
    const set = grants.get(m[2]) ?? new Set<string>(); set.add(m[1]); grants.set(m[2], set);
  }
  for (const alias of PLATFORM_MENU_ALIASES) {
    expect(sql).toContain(`'${alias.pointCode}'`);
    const sourceRoles = alias.sourcePoint ? grants.get(alias.sourcePoint)! : new Set<string>();
    expect([...(grants.get(alias.pointCode) ?? [])].sort(), alias.href).toEqual([...new Set(['SUPER_ADMIN', ...sourceRoles])].sort());
    const line = migration.split('\n').find(line => line.includes('SELECT source.role_code') && line.includes(`'${alias.pointCode}'`));
    if (alias.sourcePoint) expect(line).toContain(`source.point_code='${alias.sourcePoint}'`);
    else expect(line).toBeUndefined();
  }
  expect(sql).toContain("'ACT__DASHBOARD_OVERVIEW_READ', 'OPS_DASHBOARD'");
});

it("lands on an authorized database-only alias and never falls back to an unauthorized root URL", () => {
  const allowed = new Set(['/customers?tab=stores']);
  const section = platformSections([], allowed)[0];
  expect(platformDefaultHref(section, [], allowed)).toBe('/customers?tab=stores');
  expect(platformDefaultHref({ ...section, children: section.children?.map(l => ({ ...l, soon: true })) }, [], allowed)).toBeUndefined();
});

it('selects an authorized login destination for every backend role including legacy-only analyst', async () => {
  const { platformLandingHref } = await import('./platform-nav');
  for (const [role, perms] of Object.entries(BACKEND_ROLE_PERMS)) {
    const href = platformLandingHref(perms);
    expect(href, role).toBeTruthy();
    expect(platformCatalog().flatMap(s => visibleLeaves(s, perms).map(l => l.href)), role).toContain(href);
  }
  expect(platformLandingHref(BACKEND_ROLE_PERMS.ANALYST)).toBe('/communities');
  expect(platformLandingHref(BACKEND_ROLE_PERMS.TECH_OPS)).toBe('/system?tab=market');
  expect(platformLandingHref(BACKEND_ROLE_PERMS.ELEC_ADMIN)).toBe('/customers?tab=overview');
});

it('translates every primary root, leaf and group into English and Traditional Chinese', async () => {
  const { tNav } = await import('./i18n/nav-labels');
  const labels = new Set(platformNav().flatMap(section => [section.label, ...(section.children ?? []).flatMap(leaf => [leaf.label, leaf.group ?? ''])]).filter(Boolean));
  expect([...labels].filter(label => /[\u3400-\u9fff]/.test(tNav(label, 'en')))).toEqual([]);
  const { NAV_ZH_TW } = await import('./i18n/nav-zh-TW');
  expect([...labels].filter(label => !Object.hasOwn(NAV_ZH_TW, label))).toEqual([]);
  for (const label of labels) expect(tNav(label, 'zh-TW'), label).toBeTruthy();
});
