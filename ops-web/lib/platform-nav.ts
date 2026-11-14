// Primary platform navigation is a projection. NAV retains every established
// route, permission and point identity for page tabs and the compatibility catalog.
import { NAV, visibleLeaves, isLeafDisabled, type NavSection, type NavLeaf } from './nav';
import aliases from './platform-menu-aliases.json';

export const PLATFORM_MENU_ALIASES = aliases;

/** Catalog includes canonical platform aliases and all established business entries. */
export function platformCatalog(nav: NavSection[] = NAV): NavSection[] {
  return nav.map(s => ({ ...s, children: [
    ...(s.children ?? []), ...aliases.filter(a => a.functionKey === s.key),
  ] }));
}

/** Product labels/order/groups are explicit and do not inherit legacy server grouping. */
export function platformNav(nav: NavSection[] = NAV): NavSection[] {
  const all = nav.flatMap(s => s.children ?? []);
  const pick = (href: string, group?: string): NavLeaf => {
    const leaf = aliases.find(l => l.href === href) ?? all.find(l => l.href === href)!;
    return group ? { ...leaf, group } : { ...leaf };
  };
  const settings = nav.find(s => s.key === 'iam')!;
  const mall = nav.find(s => s.key === 'commerce')!;
  return [
    { ...settings, label: '平台设置', pinBottom: false, children: [
      ...(settings.children ?? []).filter(l => l.group === '基础配置'),
      pick('/merchants?tab=payment-channels'), pick('/finance?tab=pay-channels', '渠道费率'),
      ...(settings.children ?? []).filter(l => l.group !== '基础配置'),
    ] },
    { ...mall, label: '平台商城', pinBottom: false },
    { key: 'customers', label: '客户管理', icon: 'Users', modules: ['dashboard', 'customer', 'member', 'merchant'], href: '/customers?tab=overview', match: ['/customers'],
      children: aliases.filter(l => l.href.startsWith('/customers')) },
    { key: 'paymentMerchant', label: '商户管理', icon: 'Landmark', modules: ['pay'], href: '/merchants?tab=payment', match: [],
      children: [pick('/merchants?tab=payment'), pick('/merchants?tab=payment-onboarding')] },
    { key: 'orders', label: '订单中心', icon: 'ReceiptText', modules: ['order', 'aftersale', 'pay'], href: '/orders', match: ['/orders', '/after-sales'],
      children: [pick('/orders', '经营订单'), pick('/orders?tab=exception', '经营订单'), pick('/after-sales', '经营订单'),
        pick('/orders?tab=payments'), pick('/orders?tab=payment-exceptions')] },
  ];
}

/** A projected root is useful only when it contains an authorized leaf. */
export function platformSections(perms?: string[], serverHrefs?: Set<string>, nav = platformNav()): NavSection[] {
  return nav.filter(s => visibleLeaves(s, perms, serverHrefs).length > 0);
}

/** Landing follows the same grant source as visibility, including custom database-only grants. */
export function platformDefaultHref(section: NavSection, perms?: string[], serverHrefs?: Set<string>): string | undefined {
  return visibleLeaves(section, perms, serverHrefs).find(l => !isLeafDisabled(l))?.href;
}

/** All roles enter an available page; roles with only legacy grants use their catalog entry. */
export function platformLandingHref(perms?: string[], serverHrefs?: Set<string>): string | undefined {
  const primary = platformSections(perms, serverHrefs);
  for (const section of primary) {
    const href = platformDefaultHref(section, perms, serverHrefs);
    if (href) return href;
  }
  return platformCatalog().flatMap(s => visibleLeaves(s, perms, serverHrefs)).find(l => !isLeafDisabled(l))?.href;
}

/** Primary pages expose only their root's tabs; established compatibility pages keep their complete tab set. */
export function platformContextTabKeys(path: string, keys: readonly string[], allowed: string[], tab: string | null,
                                      catalog: NavSection[] = platformCatalog()): string[] {
  const normalize = (value: string) => value.replace(/\/+$/, '') || '/';
  const parts = (href: string) => {
    const [pathname, query] = href.split('?');
    const search = new URLSearchParams(query);
    return { path: normalize(pathname), tab: search.get('tab'), view: search.get('view') };
  };
  const pathname = normalize(path);
  const root = platformNav().find(s => s.children?.some(l => {
    const leaf = parts(l.href);
    return leaf.path === pathname && leaf.tab === tab && leaf.view === null;
  }));
  if (!root) return allowed;
  const rootHrefs = new Set(root.children?.map(l => l.href));
  const leaves = catalog.flatMap(s => s.children ?? []);
  return allowed.filter(key => {
    const index = keys.indexOf(key);
    const explicit = leaves.find(l => { const leaf = parts(l.href); return leaf.path === pathname && leaf.tab === key; });
    const leaf = explicit ?? (index === 0 ? leaves.find(l => { const leaf = parts(l.href); return leaf.path === pathname && leaf.tab === null && leaf.view === null; }) : undefined);
    return !!leaf && rootHrefs.has(leaf.href);
  });
}
