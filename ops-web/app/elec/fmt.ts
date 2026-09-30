// 元器件的显示格式。金额是**百万分之一元**（priceE6），按分制的通用格式化器做不了。
import type { ElecCopy } from "./copy";
import { fill } from "@/lib/use-copy";

const E6 = 1_000_000;

/** 百万分之一元 → 「6.85」「0.0015」：至少两位、最多六位小数 */
export function yuan(e6: number | null | undefined): string {
  if (e6 == null) return "—";
  const fixed = (e6 / E6).toFixed(6).replace(/0+$/, "");
  const [i, d = ""] = fixed.split(".");
  return `${Number(i).toLocaleString("en-US")}.${d.padEnd(2, "0")}`;
}

/** 带币种符号 */
export function price(e6: number | null | undefined, currency?: string | null): string {
  if (e6 == null) return "—";
  const sym = currency === "USD" ? "$" : currency === "HKD" ? "HK$" : "¥";
  return `${sym}${yuan(e6)}`;
}

/** 输入框里的元 → 百万分之一元。按字符串拼：8.2 × 1e6 用浮点算是 8199999.999999999 */
export function e6Of(input: string): number | null {
  const s = input.trim();
  if (!/^\d+(\.\d{1,6})?$/.test(s)) return null;
  const [i, d = ""] = s.split(".");
  return Number(i) * E6 + Number(d.padEnd(6, "0"));
}

export function lead(days: number | null | undefined, c: ElecCopy): string {
  if (days == null) return c.leadUnknown;
  return days === 0 ? c.spot : fill(c.leadDays, { n: days });
}

export function qty(n: number | null | undefined): string {
  return n == null ? "—" : n.toLocaleString("en-US");
}

/** ISO 时间 → 「09-30 09:12」 */
export function when(iso: string | null | undefined): string {
  if (!iso) return "—";
  return iso.slice(5, 16).replace("T", " ");
}

/** 买家的要求拼成一行。只列他真的提了的 —— 「不限」不必说 */
export function demand(r: { needInvoice: string; dcReq: string; condReq?: string | null; packingReq?: string | null;
  needByDays?: number | null; allowAlt: boolean; deliverCity?: string | null }, c: ElecCopy): string {
  const pick = (prefix: string, v?: string | null) =>
    v && v !== "ANY" ? ((c as unknown as Record<string, string>)[`${prefix}${v}`] ?? v) : "";
  return [
    pick("inv", r.needInvoice), pick("dc", r.dcReq), pick("cond", r.condReq), pick("pack", r.packingReq),
    r.needByDays ? fill(c.needBy, { n: r.needByDays }) : "", r.allowAlt ? c.allowAlt : "", r.deliverCity ?? "",
  ].filter(Boolean).join(" · ");
}
