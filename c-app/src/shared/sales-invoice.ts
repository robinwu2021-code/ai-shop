// 台湾电子发票（买方）的展示与格式小工具。纯函数，页面与单测共用。
import { CURRENCIES } from "@shared/utils/constants";
import { money } from "@shared/utils/format";
import type { CurrencyCode } from "@shared/types";
import type {
  BuyerInvoice,
  BuyerInvoiceStatus,
  InvoiceCarrier,
  InvoiceCarrierType,
  InvoiceChoiceKind,
  InvoiceChoiceOutcome,
} from "@/api/sales-invoice-types";

export const CARRIER_MOBILE: InvoiceCarrierType = "3J0002";
export const CARRIER_CITIZEN: InvoiceCarrierType = "CQ0001";

/**
 * 载具号码的格式（与后端同一判据，只用来**提前提示**；最终以服务端 26004 为准）。
 * 手机条码 = `/` + 7 码 [0-9A-Z.+-]；自然人凭证 = 2 个大写字母 + 14 位数字。
 */
const CARRIER_FORMAT: Record<InvoiceCarrierType, RegExp> = {
  "3J0002": /^\/[0-9A-Z.+-]{7}$/,
  CQ0001: /^[A-Z]{2}\d{14}$/,
};

export function carrierIdValid(type: InvoiceCarrierType, id: string): boolean {
  return CARRIER_FORMAT[type].test(id);
}

/** 手机条码不分大小写地输入，存的时候一律大写（条码本身只有大写） */
export function normalizeCarrierId(id: string): string {
  return id.trim().toUpperCase();
}

/** 统一编号 8 位数字 */
export function taxIdValid(v: string): boolean {
  return /^\d{8}$/.test(v);
}

/** 爱心码 3–7 位数字 */
export function donateCodeValid(v: string): boolean {
  return /^\d{3,7}$/.test(v);
}

/** 服务端「载具号码格式不对」的错误码 */
export const CARRIER_FORMAT_ERROR = 26004;

/** `AB12345678` → `AB-12345678`（发票上印的就是这个样子） */
export function formatFiscalNumber(n: string | null | undefined): string {
  if (!n) return "";
  const m = /^([A-Z]{2})(\d{8})$/.exec(n);
  return m ? `${m[1]}-${m[2]}` : n;
}

/** 期别 `11512` → 民国 115 年 11-12 月；认不出就返回 null，页面原样显示 */
export function periodParts(code: string | null | undefined): { y: number; a: number; b: number } | null {
  const m = /^(\d{3})(\d{2})$/.exec(code ?? "");
  if (!m) return null;
  const y = Number(m[1]);
  const b = Number(m[2]);
  if (b < 2 || b > 12 || b % 2) return null;
  return { y, a: b - 1, b };
}

/** 发票自带币种（TWD，2 位最小单位）；市场货币表里没有的币种按 2 位小数并带币种码展示 */
export function invoiceMoney(minor: number | null | undefined, currency?: string | null): string {
  const v = minor ?? 0;
  if (currency && currency in CURRENCIES) return money(v, currency as CurrencyCode);
  const abs = Math.abs(Math.round(v));
  const int = String(Math.floor(abs / 100)).replace(/\B(?=(\d{3})+(?!\d))/g, ",");
  return `${currency || ""} ${v < 0 ? "-" : ""}${int}.${String(abs % 100).padStart(2, "0")}`.trim();
}

/** 后端 LocalDateTime（`2026-11-21T15:30:00`）→ `2026-11-21 15:30`，不做时区换算（就是开立当地时间） */
export function invoiceTime(s: string | null | undefined): string {
  return (s ?? "").replace("T", " ").slice(0, 16);
}

/** 默认选哪种开法：有默认载具就用它，否则会员载具 */
export function defaultChoice(carriers: readonly InvoiceCarrier[]): { kind: InvoiceChoiceKind; carrierNo: string } {
  const d = carriers.find((c) => c.isDefault);
  return d ? { kind: "CARRIER", carrierNo: d.carrierNo } : { kind: "MEMBER", carrierNo: "" };
}

// 词条表。**key 不拼接**（i18n 孤儿闸靠「源码里有字面量」判断词条在用）
export const CARRIER_TYPE_KEY: Record<InvoiceCarrierType, string> = {
  "3J0002": "salesInvoice.carrierType.mobile",
  CQ0001: "salesInvoice.carrierType.citizen",
};
export const CARRIER_PH_KEY: Record<InvoiceCarrierType, string> = {
  "3J0002": "salesInvoice.mobilePh",
  CQ0001: "salesInvoice.citizenPh",
};
export const CHOICE_KEY: Record<InvoiceChoiceKind, string> = {
  MEMBER: "salesInvoice.kind.member",
  CARRIER: "salesInvoice.kind.carrier",
  CONSUMER: "salesInvoice.kind.consumer",
  TAX_ID: "salesInvoice.kind.taxId",
  DONATE: "salesInvoice.kind.donate",
};
export const CHOICE_HINT_KEY: Record<InvoiceChoiceKind, string> = {
  MEMBER: "salesInvoice.kindHint.member",
  CARRIER: "salesInvoice.kindHint.carrier",
  CONSUMER: "salesInvoice.kindHint.consumer",
  TAX_ID: "salesInvoice.kindHint.taxId",
  DONATE: "salesInvoice.kindHint.donate",
};
export const OUTCOME_KEY: Record<InvoiceChoiceOutcome, string> = {
  SAVED: "salesInvoice.outcome.saved",
  ISSUED: "salesInvoice.outcome.issued",
  ALREADY_ISSUED: "salesInvoice.outcome.alreadyIssued",
  NOT_ENABLED: "salesInvoice.outcome.notEnabled",
  MEMBER_CARRIER_UNAVAILABLE: "salesInvoice.outcome.memberUnavailable",
};
export const STATUS_KEY: Record<BuyerInvoiceStatus, string> = {
  ISSUED: "salesInvoice.status.issued",
  VOIDED: "salesInvoice.status.voided",
  REVOKING: "salesInvoice.status.revoking",
  REVOKED: "salesInvoice.status.revoked",
};

/** 发票存到哪里 / 给了谁：一句话，列表与详情共用 */
export function destinationKey(inv: Pick<BuyerInvoice, "donateCode" | "carrierType" | "buyerTaxId">): string {
  if (inv.donateCode) return "salesInvoice.dest.donate";
  if (inv.carrierType === CARRIER_MOBILE) return "salesInvoice.dest.mobile";
  if (inv.carrierType === CARRIER_CITIZEN) return "salesInvoice.dest.citizen";
  if (inv.carrierType) return "salesInvoice.dest.member";
  if (inv.buyerTaxId) return "salesInvoice.dest.taxId";
  return "salesInvoice.dest.paper";
}
