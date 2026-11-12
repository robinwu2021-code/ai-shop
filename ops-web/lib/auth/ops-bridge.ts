/** Same-origin operations navigation. Credentials are never carried in URLs. */
export const SHARED_OPS_SESSION_KEY = "neargo-ops-session";
export const SHOP_BASE_PATH = process.env.NEXT_PUBLIC_BASE_PATH || "/ops-shop";
export const PAYMENT_OPS_URL = process.env.NEXT_PUBLIC_NEARPAY_OPS_URL || "/oms/";

function trustedBase(value: string, origin: string): string | null {
  try {
    if (!value || /[\\\s]/.test(value)) return null;
    const url = new URL(value, origin);
    if (url.origin !== origin || url.username || url.password || url.search || url.hash) return null;
    const path = url.pathname.replace(/\/+$/, "");
    return path && path !== "/" ? path : null;
  } catch { return null; }
}

export function paymentBasePath(origin: string, configured = PAYMENT_OPS_URL): string {
  return trustedBase(configured, origin) ?? "/oms";
}

/** Only the configured shop/payment path trees are valid return destinations. */
export function safeReturnTo(value: string | null, origin: string, shopBase = SHOP_BASE_PATH, paymentBase = PAYMENT_OPS_URL): string | null {
  if (!value || /[\\\s\u0000-\u001f\u007f]/.test(value) || /%2f|%5c/i.test(value)) return null;
  try {
    const url = new URL(value, origin);
    if (url.origin !== origin || url.username || url.password) return null;
    const bases = [trustedBase(shopBase, origin), paymentBasePath(origin, paymentBase)].filter(Boolean) as string[];
    if (!bases.some((base) => url.pathname === base || url.pathname.startsWith(`${base}/`))) return null;
    // Never bounce back into the login page itself.
    if (bases[0] && /^\/login\/?$/.test(url.pathname.slice(bases[0].length))) return null;
    return `${url.pathname}${url.search}${url.hash}`;
  } catch { return null; }
}

/** Browser replacement preserves payment query/hash and keeps credentials out of navigation. */
export function returnAfterLogin(
  value: string | null,
  location: Pick<Location, "origin" | "replace">,
  home: () => void,
  locale?: "zh" | "zh-CN" | "zh-TW" | "en",
): void {
  const target = safeReturnTo(value, location.origin);
  if (!target) { home(); return; }
  const url = new URL(target, location.origin);
  // An incoming URL hint would otherwise override the language just selected on
  // the login page. Leave unhinted deep links unchanged; they use shared storage.
  if (locale && url.searchParams.has("locale")) {
    url.searchParams.set("locale", locale === "zh" ? "zh-CN" : locale);
    location.replace(`${url.pathname}${url.search}${url.hash}`);
  } else location.replace(target);
}

export function paymentOpsHref(origin: string, locale: string, configured = PAYMENT_OPS_URL): string {
  const params = new URLSearchParams({ locale: locale === "zh" ? "zh-CN" : locale === "zh-TW" ? "zh-TW" : "en" });
  return `${paymentBasePath(origin, configured)}/?${params}`;
}

export function loginReturnHref(location: Pick<Location, "pathname" | "search" | "hash">): string {
  return `/login/?${new URLSearchParams({ returnTo: `${location.pathname}${location.search}${location.hash}` })}`;
}

export function readSharedToken(storage: Pick<Storage, "getItem">): string | null {
  try {
    const session: unknown = JSON.parse(storage.getItem(SHARED_OPS_SESSION_KEY) || "null");
    if (!session || typeof session !== "object") return null;
    const token = (session as { token?: unknown }).token;
    return typeof token === "string" && token.trim() && !/[\r\n]/.test(token) ? token : null;
  } catch { return null; }
}

export function shareOpsToken(token: string): void {
  if (typeof window === "undefined") return;
  try {
    sessionStorage.setItem(SHARED_OPS_SESSION_KEY, JSON.stringify({ token }));
    sessionStorage.removeItem("nearpay-ops-no-shared");
  } catch { /* Storage may be disabled; local login remains available. */ }
}

export function clearOpsSessions(): void {
  if (typeof window === "undefined") return;
  try {
    sessionStorage.removeItem(SHARED_OPS_SESSION_KEY);
    sessionStorage.removeItem("nearpay-ops-session");
    sessionStorage.setItem("nearpay-ops-no-shared", "1");
    localStorage.removeItem("shop-ops-auth");
  } catch { /* Logout also clears the in-memory store. */ }
}
