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

/** 朋友圈卡片打开小程序时的场景值：「单页模式」 */
export const TIMELINE_SINGLE_PAGE_SCENE = 1154;

/**
 * 当前是不是从**朋友圈卡片**打开的「单页模式」（TDD-C端朋友圈分享修补 AC3）。
 *
 * <p>单页模式下微信**禁止登录、支付与页面跳转**，底部固定一条「前往小程序」。
 * 不判的话，从朋友圈进来的人点加购会被带去登录页 —— 跳转被微信拦掉，
 * **什么都不发生、也不报错**，他只会以为按钮坏了。
 *
 * <p>整个小程序实例都在这个模式里（点「前往小程序」是另起一个完整实例），
 * 所以取一次进入场景就够。H5 与 App 恒为 false。
 */
export function inTimelineSinglePage(): boolean {
  let yes = false;
  // #ifdef MP-WEIXIN
  try {
    yes = uni.getEnterOptionsSync().scene === TIMELINE_SINGLE_PAGE_SCENE;
  } catch {
    yes = false;
  }
  // #endif
  return yes;
}

/**
 * 分享卡片的配图（TDD-C端朋友圈分享修补 AC2）。**只放行 http(s) 地址** ——
 * 种子与演示数据里商品图常是 emoji，交给微信只会让配图整张不出、退回默认图，而不报错。
 * 不给 imageUrl 时，朋友圈卡片用的是小程序默认图，看不出是哪件货、哪家店。
 */
export function shareImageUrl(...candidates: Array<string | null | undefined>): string | undefined {
  return candidates.find((u): u is string => !!u && /^https?:\/\//.test(u));
}

/**
 * 把一张本地图片交给微信原生的图片分享菜单（发送给朋友 / 分享到朋友圈 / 收藏 / 保存）。
 * 海报发朋友圈走这条（TDD-C端朋友圈分享修补 AC1）。
 *
 * <p>uni 在微信小程序里把没封装的接口透传给 `wx`，接口不存在（老版本微信）时取到的是 undefined，
 * 那时调 `fallback`（保存到相册）。**用户在菜单里点取消不是失败**，不再替他存一张图。
 *
 * @return 是否弹出了原生菜单
 */
export function showShareImage(path: string, fallback: () => void): boolean {
  const u = uni as unknown as {
    showShareImageMenu?: (o: { path: string; fail?: (e: unknown) => void }) => void;
  };
  if (typeof u.showShareImageMenu === "function") {
    u.showShareImageMenu({ path, fail: () => undefined });
    return true;
  }
  fallback();
  return false;
}
