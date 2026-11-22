import { CURRENCIES } from "@shared/utils/constants";
import { money, toMinor } from "@shared/utils/money";
import type { CurrencyCode } from "@shared/types";
import type { SiBuyer } from "@/api/sales-invoice";

/** 发票自带币种（首期 TWD，2 位最小单位）；市场货币表里没有的币种按 2 位小数并带币种码展示。 */
export function invoiceMoney(minor: number | null | undefined, currency?: string | null): string {
  const v = minor ?? 0;
  if (!currency) return money(v);
  if (currency in CURRENCIES) return money(v, currency as CurrencyCode);
  const abs = Math.abs(Math.round(v));
  const int = String(Math.floor(abs / 100)).replace(/\B(?=(\d{3})+(?!\d))/g, ",");
  return `${currency} ${v < 0 ? "-" : ""}${int}.${String(abs % 100).padStart(2, "0")}`;
}

/** 输入的「元」→ 发票币种的最小单位。 */
export function invoiceMinor(major: string, currency?: string | null): number {
  if (currency && currency in CURRENCIES) return toMinor(major, currency as CurrencyCode);
  return Math.round(Number(major) * 100);
}

/** 买受人怎么取发票。存载具或捐赠就不印证明联；格式校验由后端统一做（错误码可辨）。 */
export type BuyerKind = "GENERAL" | "TAX_ID" | "MOBILE" | "CITIZEN" | "DONATE";
export const BUYER_KINDS: readonly BuyerKind[] = ["GENERAL", "TAX_ID", "MOBILE", "CITIZEN", "DONATE"];
export const CARRIER_MOBILE = "3J0002";
export const CARRIER_CITIZEN = "CQ0001";

export interface BuyerForm {
  taxId: string;
  name: string;
  carrierId: string;
  donateCode: string;
}

/** 按所选方式拼请求里的 buyer；缺必填项时返回 null。一般消费者不带 buyer。 */
export function buildBuyer(kind: BuyerKind, f: BuyerForm): SiBuyer | undefined | null {
  const v = (s: string) => s.trim();
  switch (kind) {
    case "GENERAL":
      return undefined;
    case "TAX_ID":
      return v(f.taxId) ? { taxId: v(f.taxId), ...(v(f.name) ? { name: v(f.name) } : {}) } : null;
    case "MOBILE":
      return v(f.carrierId) ? { carrierType: CARRIER_MOBILE, carrierId: v(f.carrierId) } : null;
    case "CITIZEN":
      return v(f.carrierId) ? { carrierType: CARRIER_CITIZEN, carrierId: v(f.carrierId) } : null;
    case "DONATE":
      return v(f.donateCode) ? { donateCode: v(f.donateCode) } : null;
  }
}
