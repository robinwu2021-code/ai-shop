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
