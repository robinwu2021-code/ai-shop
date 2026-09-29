// 页面路由。**只在这一处写路径**，页面之间跳转一律引这里。
export const ROUTES = {
  home: "/pages/home/index",
  search: "/pages/search/index",
  part: "/pages/part/index",
  lookup: "/pages/lookup/index",
  rfqCreate: "/pages/rfq-create/index",
  rfqs: "/pages/rfqs/index",
  rfq: "/pages/rfq/index",
  supplierJoin: "/pages/supplier-join/index",
  supplier: "/pages/supplier/index",
  stockUpload: "/pages/stock-upload/index",
  stockPreview: "/pages/stock-preview/index",
  stocks: "/pages/stocks/index",
  supplierProfile: "/pages/supplier-profile/index",
  dispatches: "/pages/dispatches/index",
  dispatch: "/pages/dispatch/index",
  login: "/pages/login/index",
} as const;

/** 拼查询串。值为空的键不带 —— 否则会出现 `?partNo=undefined` */
export function withQuery(path: string, q: Record<string, string | number | undefined | null>): string {
  const s = Object.entries(q)
    .filter(([, v]) => v !== undefined && v !== null && v !== "")
    .map(([k, v]) => `${k}=${encodeURIComponent(String(v))}`)
    .join("&");
  return s ? `${path}?${s}` : path;
}

export function go(path: string, q: Record<string, string | number | undefined | null> = {}): void {
  uni.navigateTo({ url: withQuery(path, q) });
}

/**
 * 回到栈里的某一页（它 onShow 会自己刷新）；栈里没有它（冷启动落在中间页）才重开。
 * 不一律 reLaunch：那会清掉整个栈，H5 上连回首页的路都没了。
 */
export function backTo(path: string): void {
  const pages = getCurrentPages() as { route?: string }[];
  const at = pages.map((p) => `/${p.route ?? ""}`).lastIndexOf(path);
  if (at >= 0) uni.navigateBack({ delta: pages.length - 1 - at });
  else uni.reLaunch({ url: path });
}

/** 当前页连同参数，登录完要回得来 */
export function currentRoute(): string {
  const pages = getCurrentPages();
  const top = pages[pages.length - 1] as undefined | { route?: string; options?: Record<string, string> };
  if (!top?.route) return "";
  return withQuery(`/${top.route}`, top.options ?? {});
}
