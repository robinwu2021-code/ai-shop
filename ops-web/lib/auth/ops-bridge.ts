/** Same-origin operations navigation. Credentials are never carried in URLs. */
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

/** Fixed live payment workspaces; no caller-supplied route or credentials. */
export const PAYMENT_WORKSPACES = {
  channels: { path: "/ops/channels", permission: "pay:channel:view" },
  merchants: { path: "/merchants", permission: "pay:merchant:view" },
  onboarding: { path: "/merchants/onboarding-review", permission: "pay:onboarding:view" },
  payments: { path: "/trade/payments", permission: "pay:order:view" },
  "payment-exceptions": { path: "/trade/exceptions", permission: "pay:order:view" },
} as const;
export type PaymentWorkspace = keyof typeof PAYMENT_WORKSPACES;

export function paymentWorkspaceHref(origin: string, locale: string, workspace: PaymentWorkspace, configured = PAYMENT_OPS_URL): string {
  const url = new URL(paymentOpsHref(origin, locale, configured), origin);
  url.pathname = `${paymentBasePath(origin, configured)}${PAYMENT_WORKSPACES[workspace].path}/`;
  return `${url.pathname}${url.search}`;
}

export function loginReturnHref(location: Pick<Location, "pathname" | "search" | "hash">): string {
  return `/login/?${new URLSearchParams({ returnTo: `${location.pathname}${location.search}${location.hash}` })}`;
}

/**
 * 两端唯一的会话来源（TDD-ops-统一登录与系统切换 §3.2）：localStorage 里一个键，跨标签页、两端共读。
 * 只放令牌与用户名；角色与权限每次由各自后端现查，不信本地。
 */
export const SHARED_OPS_AUTH_KEY = "neargo-ops-auth";
/** 旧键：上线后第一次加载迁移一次，然后删除 */
const LEGACY_SESSION_KEYS = ["neargo-ops-session", "nearpay-ops-session", "nearpay-ops-no-shared"] as const;
const SHOP_CACHE_KEY = "shop-ops-auth";

export function readSharedToken(storage: Pick<Storage, "getItem">, key = SHARED_OPS_AUTH_KEY): string | null {
  try {
    const session: unknown = JSON.parse(storage.getItem(key) || "null");
    if (!session || typeof session !== "object") return null;
    const token = (session as { token?: unknown }).token;
    return typeof token === "string" && token.trim() && !/[\r\n]/.test(token) ? token : null;
  } catch { return null; }
}

function removeLegacy(): void {
  for (const key of LEGACY_SESSION_KEYS) {
    try { sessionStorage.removeItem(key); } catch { /* disabled storage */ }
  }
}

export function shareOpsToken(token: string, username = ""): void {
  if (typeof window === "undefined") return;
  try {
    localStorage.setItem(SHARED_OPS_AUTH_KEY, JSON.stringify({ token, username, issuedAt: Date.now() }));
  } catch { /* Storage may be disabled; local login remains available in this page. */ }
  removeLegacy();
}

/** 读两端共用的令牌（不可用的存储一律当没有）。 */
export function currentSharedToken(): string | null {
  if (typeof window === "undefined") return null;
  try { return readSharedToken(localStorage); } catch { return null; }
}

/**
 * 一次性迁移：新键为空、旧的商城缓存或旧的同标签页共享里有令牌 → 搬到新键。
 * 只做一次就不会再触发：之后新键要么有值，要么是退出时被删的（退出同时删商城缓存）。
 */
export function migrateLegacySession(): void {
  if (typeof window === "undefined") return;
  try {
    if (!readSharedToken(localStorage)) {
      let token: string | null = null;
      let username = "";
      try {
        const cached = JSON.parse(localStorage.getItem(SHOP_CACHE_KEY) || "null") as { state?: { token?: unknown; username?: unknown } } | null;
        if (typeof cached?.state?.token === "string" && cached.state.token) {
          token = cached.state.token;
          username = typeof cached.state.username === "string" ? cached.state.username : "";
        }
      } catch { /* malformed cache */ }
      try { token = token ?? readSharedToken(sessionStorage, "neargo-ops-session"); } catch { /* disabled */ }
      if (token) localStorage.setItem(SHARED_OPS_AUTH_KEY, JSON.stringify({ token, username, issuedAt: Date.now() }));
    }
  } catch { /* disabled storage */ }
  removeLegacy();
}

export function clearOpsSessions(): void {
  if (typeof window === "undefined") return;
  try {
    localStorage.removeItem(SHARED_OPS_AUTH_KEY);
    localStorage.removeItem(SHOP_CACHE_KEY);
  } catch { /* Logout also clears the in-memory store. */ }
  removeLegacy();
}
