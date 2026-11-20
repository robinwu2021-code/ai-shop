import { describe, expect, it } from 'vitest';
import { NAV, visibleLeaves } from './nav';
import { BACKEND_ROLE_PERMS } from './permissions';
import { platformNav, platformCatalog, platformSections, platformDefaultHref } from './platform-nav';

describe('platform primary navigation', () => {
  it('projects five roots and preserves the unified admission aliases', () => {
    // TDD-支付中心菜单整合：一级顺序 平台设置 / 平台商城 / 客户管理 / 订单中心 / 支付中心（key 沿用 paymentMerchant）
    expect(platformNav().map(s => s.key)).toEqual(['iam', 'commerce', 'customers', 'orders', 'paymentMerchant']);
    expect(platformNav()[4].label).toBe('支付中心');
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
    // 订单中心只留经营订单；支付订单 / 支付异常移入支付中心「交易」（point_code、href 不变）
    expect(nav[3].children?.map(l => l.href)).toEqual(['/orders', '/orders?tab=exception', '/after-sales']);
    expect(nav[4].children?.map(l => [l.href, l.group])).toEqual([
      ['/payment-center?tab=approvals', '审批'],
      ['/merchants?tab=payment', '支付商户'], ['/payment-center?tab=stores', '支付商户'], ['/merchants?tab=payment-onboarding', '支付商户'], ['/payment-center?tab=merchant-changes', '支付商户'],
      ['/orders?tab=payments', '交易'], ['/orders?tab=payment-exceptions', '交易'], ['/payment-center?tab=attempts', '交易'], ['/payment-center?tab=refunds', '交易'],
      ['/merchants?tab=payment-channels', '渠道接入'], ['/payment-center?tab=products', '渠道接入'], ['/payment-center?tab=access', '渠道接入'],
      ['/payment-center?tab=terminals', '渠道接入'], ['/payment-center?tab=apps', '渠道接入'],
      ['/payment-center?tab=channel-costs', '费率'], ['/payment-center?tab=commission-rules', '费率'],
      ['/payment-center?tab=ledger-entries', '资金结算'], ['/payment-center?tab=settlements', '资金结算'], ['/payment-center?tab=withdrawals', '资金结算'], ['/payment-center?tab=payouts', '资金结算'],
      ['/payment-center?tab=recon-diffs', '对账'],
      ['/payment-center?tab=sv-accounts', '会员资产'], ['/payment-center?tab=sv-transactions', '会员资产'],
    ]);
    // 平台设置：支付渠道移走；数据范围进权限管理；支付配置、支付运维两组
    const settingsHrefs = nav[0].children?.map(l => l.href) ?? [];
    expect(settingsHrefs).not.toContain('/merchants?tab=payment-channels');
    expect(settingsHrefs).toEqual(expect.arrayContaining(['/payment-center?tab=data-scopes', '/payment-center?tab=approval-flows', '/payment-center?tab=settings',
      '/payment-center?tab=alert-events', '/payment-center?tab=alert-rules', '/payment-center?tab=channel-calls', '/payment-center?tab=channel-callbacks', '/payment-center?tab=request-logs']));
    expect(nav[0].children?.find(l => l.href === '/payment-center?tab=data-scopes')?.group).toBe('权限管理');
    expect(NAV.flatMap(s => s.children ?? []).find(l => l.href === '/merchants?tab=onboarding')?.perm).toBe('merchant:admission:read');
  });
  it.each(Object.entries(BACKEND_ROLE_PERMS))('preserves the authorized legacy catalog for %s without empty primary roots', (_role, perms) => {
    const legacy = NAV.flatMap(s => visibleLeaves(s, perms).map(l => l.href)).sort();
    expect(platformCatalog().flatMap(s => visibleLeaves(s, perms).map(l => l.href)).filter(h => legacy.includes(h)).sort()).toEqual(legacy);
    for (const s of platformSections(perms)) expect(visibleLeaves(s, perms).length).toBeGreaterThan(0);
  });
  it('honors exact database point grants for custom roles', () => {
    const allowed = new Set(['/customers?tab=stores', '/orders?tab=payments']);
    expect(platformSections([], allowed).map(s => s.key)).toEqual(['customers', 'paymentMerchant']);
    expect(platformSections([], new Set(['/products?tab=templates']))).toEqual([]);
    expect(platformSections(['product:sku:audit'])).toEqual([]);
  });
});

it('has an explicit primary root matrix for all twelve backend roles', () => {
  const expected: Record<string, string[]> = {
    SUPER_ADMIN: ['iam', 'commerce', 'customers', 'orders', 'paymentMerchant'],
    MERCHANT_BD: ['commerce', 'customers', 'orders'],
    PRODUCT_OPS: ['iam', 'customers', 'orders'],
    CS: ['iam', 'customers', 'orders'],
    CAMPAIGN_OPS: ['customers', 'orders'],
    COMMUNITY_OPS: ['iam', 'customers', 'orders'],
    AUDITOR: ['iam', 'customers'],
    FINANCE: ['iam', 'customers', 'orders', 'paymentMerchant'],
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
  // V671（五目录别名）+ V684（支付中心 16 个页签）+ V685（P3a 10 个页签）—— 已应用的 V671 冻结，新别名的点位与授权在后两份
  const migration = ['V671__ops_platform_primary_navigation.sql', 'V684__ops_payment_center_menu.sql', 'V685__ops_payment_center_p3a_menu.sql']
    .map(f => readFileSync(new URL(`../../backend/shop-app/src/main/resources/db/migration/${f}`, import.meta.url), 'utf8')).join('\n');
  expect(migration).not.toMatch(/\b(?:DELETE|UPDATE|TRUNCATE|ALTER|DROP)\s+(?:sys_|TABLE)/i);
  const grants = new Map<string, Set<string>>();
  for (const m of sql.matchAll(/INSERT INTO sys_role_point \([^)]*\) VALUES \('([^']+)', '([^']+)'/g)) {
    const set = grants.get(m[2]) ?? new Set<string>(); set.add(m[1]); grants.set(m[2], set);
  }
  for (const alias of PLATFORM_MENU_ALIASES) {
    expect(sql).toContain(`'${alias.pointCode}'`);
    const sourceRoles = alias.sourcePoint ? grants.get(alias.sourcePoint)! : new Set<string>();
    expect([...(grants.get(alias.pointCode) ?? [])].sort(), alias.href).toEqual([...new Set(['SUPER_ADMIN', ...sourceRoles])].sort());
    // 按「授给谁」找：别的别名把它当继承来源时也会出现在行里，只认 SELECT source.role_code,'<它>'
    const line = migration.split('\n').find(line => line.includes(`SELECT source.role_code,'${alias.pointCode}'`));
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
