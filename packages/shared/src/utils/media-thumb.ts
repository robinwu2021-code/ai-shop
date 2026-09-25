// 列表用缩略图（ADR-026，TDD-图片走服务器与流量切换 T6）。
//
// 为什么非做不可：经应用服务器出图（img.hxmall.top）受 5 Mbps 带宽限制，约 600 KB/s，接口和图片共用。
// 线上实测一张原图 910 KB，一屏列表摆几张就要好几秒；375 宽的缩略图只有 22 KB。
//
// 写法 `<地址>!w<宽度>`：nginx（img.hxmall.top）与数据万象样式（cdn.hxmall.top，分隔符 !）都认这一种，
// 所以切换出口时这里不用改。宽度只有三档 —— nginx 那边只放行这三档，别的宽度一律 404、不回源，
// 防止任意宽度把缓存和 CPU 撑爆。

/** 我们自己的两个图片出口。只有它们认 `!wNNN`；别家地址（微信头像、种子里的 emoji、本地临时路径）原样返回。 */
export const MEDIA_HOSTS = ["img.hxmall.top", "cdn.hxmall.top"] as const;

export const THUMB_WIDTHS = [200, 375, 750] as const;
export type ThumbWidth = (typeof THUMB_WIDTHS)[number];

const HOST_RE = new RegExp(`^https://(?:${MEDIA_HOSTS.map((h) => h.replace(/\./g, "\\.")).join("|")})/`);

/**
 * 给图片地址挂上缩略图后缀。
 *
 * - 不是我们的出口 → 原样返回；
 * - 已经带了 `!w…` → 原样返回（不叠两次，叠了 nginx 认不出来就是 404）；
 * - 地址带查询串（签名地址）→ 原样返回：签名算进了路径，改路径签名就失效。
 */
export function thumb(url: string | null | undefined, w: ThumbWidth): string {
  if (!url) return url ?? "";
  if (!HOST_RE.test(url) || url.includes("?") || /![wW]\d+$/.test(url)) return url;
  return `${url}!w${w}`;
}
