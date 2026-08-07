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

/** 时间展示（UTC → 按 locale 简写）。 */
export function fmtTime(iso?: string | null) {
  if (!iso) return "-";
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? "-" : d.toLocaleString(localeTag());
}
