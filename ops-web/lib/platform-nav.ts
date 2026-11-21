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
  const pc = (key: string, group?: string) => pick(`/payment-center?tab=${key}`, group);
  const settingsRest = (settings.children ?? []).filter(l => l.group !== '基础配置');
  const lastAccess = settingsRest.map(l => l.group).lastIndexOf('权限管理');
  /*
   * 支付中心（TDD-支付中心菜单整合 §2.2，2026-11-20 起在本分支落地，原定交网关的 G4）：
   * 「商户管理」改名「支付中心」，收全部支付业务；订单中心只留经营订单；平台设置的支付渠道移走，
   * 加数据范围（权限管理）、支付配置、支付运维两组。V671 的 5 个既有入口 point_code 不变，href 由 V686 改到 /payment-center（支付中心是独立模块，商户/订单模块不再挂支付页签）。
   * 还没原生化的页面不进菜单（P3 隐藏，不灰显）。
   */
  return [
    { ...settings, label: '平台设置', pinBottom: false, children: [
      ...(settings.children ?? []).filter(l => l.group === '基础配置'),
      pick('/finance?tab=pay-channels', '渠道费率'),
      ...settingsRest.slice(0, lastAccess + 1), pc('data-scopes'), ...settingsRest.slice(lastAccess + 1),
      pc('approval-flows'), pc('settings'),
      pc('alert-events'), pc('alert-rules'), pc('channel-calls'), pc('channel-callbacks'), pc('request-logs'),
    ] },
    { ...mall, label: '平台商城', pinBottom: false },
    { key: 'customers', label: '客户管理', icon: 'Users', modules: ['dashboard', 'customer', 'member', 'merchant'], href: '/customers?tab=overview', match: ['/customers'],
      children: aliases.filter(l => l.href.startsWith('/customers')) },
    { key: 'orders', label: '订单中心', icon: 'ReceiptText', modules: ['order', 'aftersale'], href: '/orders', match: ['/orders', '/after-sales'],
      children: [pick('/orders', '经营订单'), pick('/orders?tab=exception', '经营订单'), pick('/after-sales', '经营订单')] },
    // key 沿用 paymentMerchant：菜单顺序与个人偏好按 key 存，改 key 会让已有设置失效
    { key: 'paymentMerchant', label: '支付中心', icon: 'Landmark', modules: ['pay'], href: '/payment-center', match: ['/payment-center'],
      children: [
        pc('approvals'),
        pc('merchants', '支付商户'), pc('stores'), pc('onboardings', '支付商户'), pc('merchant-changes'), pc('merchant-members'), pc('association-repairs'),
        pc('orders', '交易'), pc('exceptions', '交易'), pc('attempts'), pc('refunds'),
        pc('channels', '渠道接入'), pc('products'), pc('access'), pc('terminals'), pc('apps'),
        // P3a（V685）：费率 / 资金结算 / 对账 / 会员资产；P3b、P3c 的页面接口出来再进
        pc('channel-costs'), pc('commission-rules'),
        pc('ledger-entries'), pc('settlements'), pc('withdrawals'), pc('payouts'),
        pc('recon-diffs'),
        pc('sv-accounts'), pc('sv-transactions'),
      ] },
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
