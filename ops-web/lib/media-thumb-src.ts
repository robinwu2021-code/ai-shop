/**
 * 「待回收」列表那一格缩略图的地址。
 *
 * 地址由后端给（`thumbUrl`）：COS 下公开图是 `https://img.hxmall.top/<key>!w200`，
 * 私有图（证件、售后）是 10 分钟的签名地址；本地盘下是站内相对路径 `/uploads/<key>`。
 * 这里只做一件事：相对路径补上 API 前缀（运营端与后端不同源）。
 *
 * 为什么不再自己拼：此前写的是 `${API_BASE}/uploads/${assetKey}` —— 本地盘的路径，
 * 生产切 COS 后不存在，这一列一直是裂图，运营等于在盲删。
 *
 * 没有 `thumbUrl`（mock 数据、没升级的后端）才退回老写法，免得整列空着。
 */
export function mediaThumbSrc(row: { thumbUrl?: string | null; assetKey: string }, apiBase: string): string {
  const base = apiBase.replace(/\/+$/, "");
  const url = row.thumbUrl;
  if (url) return /^https?:\/\//.test(url) ? url : `${base}${url.startsWith("/") ? "" : "/"}${url}`;
  return `${base}/uploads/${row.assetKey}`;
}
