import { ROUTES } from "@shared/utils/constants";

/** URL Link 先进入微信已识别的首页，再把看件令牌交给专用页面。 */
export function trackEntryPath(raw: unknown): string | null {
  if (typeof raw !== "string" || !/^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/.test(raw)) return null;
  return `${ROUTES.track}?t=${encodeURIComponent(raw)}`;
}
