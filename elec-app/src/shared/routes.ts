// 页面路由。**只在这一处写路径**，页面之间跳转一律引这里。
//
// 前缀：独立发布时为空（`/pages/...`）；测试期并进 c-app 当分包时是 `/pkg-elec`
// （由 c-app/scripts/with-elec.mjs 在构建时注入 VITE_ELEC_ROUTE_BASE）。
// 页面里不许写死 `/pages/...` —— 并包之后那些跳转会静默失败。
const BASE = import.meta.env.VITE_ELEC_ROUTE_BASE || "";

export const ROUTES = {
  home: `${BASE}/pages/home/index`,
  search: `${BASE}/pages/search/index`,
  part: `${BASE}/pages/part/index`,
  lookup: `${BASE}/pages/lookup/index`,
  rfqCreate: `${BASE}/pages/rfq-create/index`,
  rfqs: `${BASE}/pages/rfqs/index`,
  rfq: `${BASE}/pages/rfq/index`,
  supplierJoin: `${BASE}/pages/supplier-join/index`,
  supplier: `${BASE}/pages/supplier/index`,
  stockUpload: `${BASE}/pages/stock-upload/index`,
  stockPreview: `${BASE}/pages/stock-preview/index`,
  stocks: `${BASE}/pages/stocks/index`,
  stockBatches: `${BASE}/pages/stock-batches/index`,
  supplierProfile: `${BASE}/pages/supplier-profile/index`,
  dispatches: `${BASE}/pages/dispatches/index`,
  dispatch: `${BASE}/pages/dispatch/index`,
  login: `${BASE}/pages/login/index`,
  me: `${BASE}/pages/me/index`,
  history: `${BASE}/pages/history/index`,
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
 * 不用 reLaunch（找不到时也不用）：那会清掉整个栈 —— H5 上连回首页的路都没了，
 * 并进虹选时连回虹选的路也没了。找不到就原地换成它。
 */
export function backTo(path: string): void {
  const pages = getCurrentPages() as { route?: string }[];
  const at = pages.map((p) => `/${p.route ?? ""}`).lastIndexOf(path);
  if (at >= 0) uni.navigateBack({ delta: pages.length - 1 - at });
  else uni.redirectTo({ url: path });
}

/** 当前页连同参数，登录完要回得来 */
export function currentRoute(): string {
  const pages = getCurrentPages();
  const top = pages[pages.length - 1] as undefined | { route?: string; options?: Record<string, string> };
  if (!top?.route) return "";
  return withQuery(`/${top.route}`, top.options ?? {});
}
