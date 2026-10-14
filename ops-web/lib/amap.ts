// 高德 JS API 的加载器。**只在真正要看地图时才加载** —— 它是几百 KB 的外部脚本，
// 而运营端绝大多数页面用不到；挂在全局 layout 里会让每一页都等它。
//
// 三件容易踩的事：
//  1. JS API 2.0 **必须配安全密钥**（2021-12 之后申请的 key）。只填 key 不填密钥，
//     地图不是报错而是**一片空白**，控制台里才有 INVALID_USER_SCODE —— 所以下面缺密钥时直接不加载，
//     由调用方渲染一句人话，比让人对着白框猜强。
//  2. 安全密钥要在**脚本加载之前**挂到 window._AMapSecurityConfig，晚一步就不生效。
//  3. 同一个页面重复加载会抛「Multiple instances」，所以用一个 Promise 缓存。

const KEY = process.env.NEXT_PUBLIC_AMAP_JS_KEY ?? "";
const SCODE = process.env.NEXT_PUBLIC_AMAP_JS_SECURITY_CODE ?? "";

export type AMapReady = { ok: true; AMap: unknown } | { ok: false; reason: "no-key" | "no-scode" | "load-failed" };

let pending: Promise<AMapReady> | null = null;

export function loadAMap(): Promise<AMapReady> {
  if (pending) return pending;
  pending = new Promise<AMapReady>((resolve) => {
    if (typeof window === "undefined") return resolve({ ok: false, reason: "load-failed" });
    if (!KEY) return resolve({ ok: false, reason: "no-key" });
    if (!SCODE) return resolve({ ok: false, reason: "no-scode" });

    const w = window as unknown as { AMap?: unknown; _AMapSecurityConfig?: { securityJsCode: string } };
    if (w.AMap) return resolve({ ok: true, AMap: w.AMap });

    w._AMapSecurityConfig = { securityJsCode: SCODE };
    const el = document.createElement("script");
    el.src = `https://webapi.amap.com/maps?v=2.0&key=${encodeURIComponent(KEY)}`;
    el.async = true;
    el.onload = () => (w.AMap ? resolve({ ok: true, AMap: w.AMap }) : resolve({ ok: false, reason: "load-failed" }));
    el.onerror = () => resolve({ ok: false, reason: "load-failed" });
    document.head.appendChild(el);
  });
  return pending;
}

/** 配没配全。界面上据此决定是渲染地图还是渲染一句「还没配 key」 */
export const amapConfigured = Boolean(KEY && SCODE);
