// 端能力：分享 —— 裂变主入口。
// 所有分享路径必须带归因参数（merchantNo / inviterNo），否则进店归因与费率分档无从判定（ADR-004 §5.4）。
import { ATTRIBUTION } from "@shared/utils/constants";

export interface ShareParams {
  title: string;
  path: string;
  imageUrl?: string;
  merchantNo?: string;
  inviterNo?: string;
}

/** 拼出带归因参数的分享路径 */
export function withAttribution(path: string, p: ShareParams): string {
  const qs: string[] = [];
  if (p.inviterNo) qs.push(`inviterNo=${encodeURIComponent(p.inviterNo)}`);
  if (p.merchantNo) qs.push(`merchantNo=${encodeURIComponent(p.merchantNo)}`);
  if (!qs.length) return path;
  return `${path}${path.includes("?") ? "&" : "?"}${qs.join("&")}`;
}

/** 供页面 onShareAppMessage 直接返回的对象（小程序） */
export function buildShareMessage(p: ShareParams) {
  return {
    title: p.title,
    path: withAttribution(p.path, p),
    imageUrl: p.imageUrl,
  };
}

/**
 * 供页面 `onShareTimeline` 直接返回的对象（**朋友圈**）。
 *
 * <p><b>与转发给好友不是同一个形状</b>：朋友圈分享落的是「单页模式」，
 * 微信只接受 `query`（不接受 `path`）—— 落地页由当前页决定，参数从 query 走。
 * 把 `buildShareMessage` 的结果直接返回给 `onShareTimeline`，微信会**忽略那个 path**，
 * 于是归因参数一起没了：人从朋友圈进来了，却算不到任何人头上。
 *
 * <p>所以这里只拼 query：把归因参数与页面自己的参数拼成一串。
 *
 * @param params 页面自己的参数（如 `goodsNo=G1`），可空
 */
export function buildShareTimeline(p: ShareParams & { params?: string }) {
  const qs: string[] = [];
  if (p.params) qs.push(p.params);
  if (p.inviterNo) qs.push(`inviterNo=${encodeURIComponent(p.inviterNo)}`);
  if (p.merchantNo) qs.push(`merchantNo=${encodeURIComponent(p.merchantNo)}`);
  return {
    title: p.title,
    query: qs.join("&"),
    imageUrl: p.imageUrl,
  };
}

/** 归因优先级：数组靠前者胜出。规则见 shared/constants ATTRIBUTION */
export function resolveAttribution(
  candidates: Partial<Record<"INVITER" | "LEADER" | "CHANNEL", string>>,
): { source: string; no: string } | null {
  for (const key of ATTRIBUTION.priority) {
    const no = candidates[key];
    if (no) return { source: key, no };
  }
  return null;
}

/**
 * 当前端有没有原生的「转发给好友」（小程序的 `<button open-type="share">`）。
 * 页面据它决定要不要画分享按钮 —— H5 上那个按钮点了什么都不发生，画出来就是一个死按钮。
 */
/**
 * 当前端支不支持**分享到朋友圈**（`onShareTimeline`）。
 *
 * <p>与 {@link canNativeShare} 分开是因为它们不是同一件事：H5 两个都没有，
 * 而朋友圈这一条即便在小程序里也有基础库版本要求 —— 页面据它决定要不要提示
 * 「可分享到朋友圈」，而不是画一个点了没反应的东西。
 */
export function canShareTimeline(): boolean {
  let yes = false;
  // #ifdef MP-WEIXIN
  yes = true;
  // #endif
  return yes;
}

export function canNativeShare(): boolean {
  let yes = false;
  // #ifdef MP-WEIXIN
  yes = true;
  // #endif
  return yes;
}
