import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";
import { useLocaleStore } from "@/lib/stores/locale";
import { DEFAULT_CURRENCY, MINOR_UNIT } from "@/lib/constants";

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

const TAG: Record<string, string> = { zh: "zh-CN" };
// 当前 locale 的 Intl tag（非 React 处读 store 快照；随下次渲染生效）。
function localeTag() {
  return TAG[useLocaleStore.getState().locale] ?? "zh-CN";
}

/**
 * 金额展示。**入参是最小货币单位整数（分）**，与契约一致（见 lib/types/common.ts）——
 * 页面拿到的 payAmount 是 12800，不是 128。在这里除以 100，别在页面里各除各的。
 * 多市场（矩阵 P-17.1.3）时 currency 由数据带下来，不要在页面写死。
 */
export function money(minorAmount: number, currency = DEFAULT_CURRENCY) {
  return new Intl.NumberFormat(localeTag(), { style: "currency", currency }).format((minorAmount ?? 0) / MINOR_UNIT);
}

/**
 * 时间展示（UTC → 按 locale 简写）。
 *
 * **同时收 ISO 串与毫秒时间戳**：后端的时间字段一律是 `long`（毫秒），
 * 而这里此前只声明 `string` —— 运行时 `new Date(number)` 照样能用，
 * 所以这个谎言一直没被发现，代价是类型层说不出真话。
 * 放宽签名而不是改后端：毫秒是后端全域的一致口径。
 */
export function fmtTime(at?: string | number | null) {
  if (at === null || at === undefined || at === "") return "-";
  const d = new Date(at);
  return Number.isNaN(d.getTime()) ? "-" : d.toLocaleString(localeTag());
}
