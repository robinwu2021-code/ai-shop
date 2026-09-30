// 元器件的显示格式。价格是**百万分之一元**（0402 电阻 ¥0.0015，按分存做不出来），
// 所以不能借 @shared/utils/money 的分制格式化。
import type {
  ElecCond, ElecCondReq, ElecDcReq, ElecInvoice, ElecPacking, ElecPackingReq, ElecQtyBand,
  ElecSourceBand, ElecMatch, ElecRfqStatus, ElecCloseReason, ElecDispatchStatus, ElecSupplierKind,
  ElecCurrency, ElecRowProblemReason, ElecDeclineReason, ElecIssue, ElecIssueCode, ElecBatchStatus,
  ElecColumnSource,
} from "@shared/types";

const E6 = 1_000_000;

/** 百万分之一元 → 「6.85」「0.0015」。至少两位小数，最多六位，末尾的 0 去掉 */
export function yuanOf(e6: number): string {
  const v = e6 / E6;
  const fixed = v.toFixed(6).replace(/0+$/, "");
  const [i, d = ""] = fixed.split(".");
  return `${Number(i).toLocaleString("en-US")}.${d.padEnd(2, "0")}`;
}

/** 带币种符号：¥6.85 / $1.20 / HK$3.40 */
export function priceOf(e6: number | null | undefined, currency: ElecCurrency = "CNY"): string {
  if (e6 === null || e6 === undefined) return "—";
  const sym = currency === "USD" ? "$" : currency === "HKD" ? "HK$" : "¥";
  return `${sym}${yuanOf(e6)}`;
}

/** 输入框里的元 → 百万分之一元。认不出返回 null（不是 0 —— 0 元是一个真实的价） */
export function e6Of(input: string): number | null {
  const s = input.trim();
  if (!/^\d+(\.\d{1,6})?$/.test(s)) return null;
  const [i, d = ""] = s.split(".");
  return Number(i) * E6 + Number(d.padEnd(6, "0"));
}

/** 金额合计：单价(E6) × 数量 → 元，保留两位 */
export function subtotalOf(e6: number, qty: number): string {
  return (Math.round((e6 * qty) / 10_000) / 100).toLocaleString("en-US", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
}

export function qtyOf(n: number | null | undefined): string {
  return n === null || n === undefined ? "—" : n.toLocaleString("en-US");
}

/** 「今天 09:12」「昨天 18:03」「9 月 27 日」「2025 年 9 月 27 日」 */
export function whenOf(iso: string | null | undefined): string {
  if (!iso) return "";
  const d = new Date(iso.replace(" ", "T"));
  if (Number.isNaN(d.getTime())) return iso;
  const now = new Date();
  const hm = `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
  const day0 = (x: Date) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime();
  const diff = Math.round((day0(now) - day0(d)) / 86_400_000);
  if (diff === 0) return `今天 ${hm}`;
  if (diff === 1) return `昨天 ${hm}`;
  const md = `${d.getMonth() + 1} 月 ${d.getDate()} 日`;
  return d.getFullYear() === now.getFullYear() ? md : `${d.getFullYear()} 年 ${md}`;
}

/** 「今天」「昨天」「3 天前」。统计格里放不下时分 */
export function agoOf(iso: string | null | undefined): string {
  if (!iso) return "";
  const d = new Date(iso.replace(" ", "T"));
  if (Number.isNaN(d.getTime())) return iso;
  const now = new Date();
  const day0 = (x: Date) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime();
  const diff = Math.round((day0(now) - day0(d)) / 86_400_000);
  return diff <= 0 ? "今天" : diff === 1 ? "昨天" : `${diff} 天前`;
}

/** 「10 月 2 日」。只给日期的字段（有效期、到期日） */
export function dateOf(ymd: string | null | undefined): string {
  if (!ymd) return "";
  const [y, m, d] = ymd.slice(0, 10).split("-").map(Number);
  if (!y || !m || !d) return ymd;
  return y === new Date().getFullYear() ? `${m} 月 ${d} 日` : `${y} 年 ${m} 月 ${d} 日`;
}

/** 到那天（含）还剩几天。今天到期 = 0；已过 = 负数 */
export function daysLeft(ymd: string | null | undefined): number | null {
  if (!ymd) return null;
  const [y, m, d] = ymd.slice(0, 10).split("-").map(Number);
  if (!y || !m || !d) return null;
  const now = new Date();
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  return Math.round((new Date(y, m - 1, d).getTime() - today) / 86_400_000);
}

/** 交期：0 → 现货；7 → 交期 7 天；空 → 交期未说（**空不是现货**） */
export function leadOf(days: number | null | undefined): string {
  if (days === null || days === undefined) return "交期未说";
  return days === 0 ? "现货" : `交期 ${days} 天`;
}

// ── 取值域 → 人话。每个域一张表，端上别处不许再写一遍 ──────────────────

export const QTY_BAND: Record<ElecQtyBand, string> = {
  B1: "少量", B100: "100+", B1K: "1k+", B10K: "10k+", B100K: "100k+", B1M: "1M+",
};

export const SOURCE_BAND: Record<ElecSourceBand, string> = { ONE: "1 家有货", FEW: "多家有货", MANY: "多家有货" };

export const MATCH: Record<ElecMatch, string> = {
  EXACT: "完全一致", PREFIX: "开头一致", CONTAINS: "中段一致", NEAR: "相近",
};

export const INVOICE: Record<ElecInvoice, string> = {
  NONE: "不开票", VAT_NORMAL: "增值税普票", VAT_SPECIAL: "增值税专票",
};

export const DC_REQ: Record<ElecDcReq, string> = { ANY: "批次不限", Y1: "一年内", Y2: "两年内" };

export const COND_REQ: Record<ElecCondReq, string> = { ANY: "货况不限", ORIGINAL: "只要原装原包", NEW: "原装即可" };

export const PACKING_REQ: Record<ElecPackingReq, string> = { ANY: "包装不限", REEL: "必须整盘", CUT_TAPE: "可以剪带" };

export const COND: Record<ElecCond, string> = {
  ORIGINAL: "原装原包", LOOSE: "原装散新", PULLED: "拆机", REFURB: "翻新",
};

export const PACKING: Record<ElecPacking, string> = {
  REEL: "整盘", TRAY: "托盘", TUBE: "管装", CUT_TAPE: "剪带", BULK: "散装", BOX: "盒装",
};

export const RFQ_STATUS: Record<ElecRfqStatus, string> = {
  SUBMITTED: "待报价", QUOTED: "已报价", EXPIRED: "报价已过期", ACCEPTED: "已接受", CLOSED: "已结束",
};

export const CLOSE_REASON: Record<ElecCloseReason, string> = {
  NO_SOURCE: "暂无货源", BUYER_CANCELLED: "你已取消", DONE: "已成交",
};

export const DISPATCH_STATUS: Record<ElecDispatchStatus, string> = {
  SENT: "待报价", VIEWED: "待报价", QUOTED: "已报价", DECLINED: "已拒绝",
};

export const DECLINE_REASON: Record<ElecDeclineReason, string> = {
  NO_STOCK: "没货", PRICE: "价格做不了", OTHER: "其他原因",
};

export const SUPPLIER_KIND: Record<ElecSupplierKind, string> = {
  AGENT: "代理商", TRADER: "贸易商", FACTORY: "工厂余料", OTHER: "其他",
};

export const ROW_PROBLEM: Record<ElecRowProblemReason, string> = {
  MPN_MISSING: "没有料号", MPN_INVALID: "不像料号", QTY_INVALID: "数量读不出", DUPLICATE: "与前面的行重复",
};

/** 问题码 → 人话。与后端导出表里的「问题」列同一套（IssueText.java） */
export const ISSUE: Record<ElecIssueCode, string> = {
  MPN_MISSING: "没有料号", MPN_INVALID: "不像料号", QTY_INVALID: "数量读不出", QTY_ZERO: "数量为 0",
  DUPLICATE: "与第 {0} 行重复", MFR_MISSING: "没写厂牌", MFR_UNKNOWN: "厂牌认不出", DC_UNPARSED: "批号读不出年份",
};

/** 一处问题的人话，定位到格：「D12（数量）读不出：约2千」「第 7 行与第 3 行重复」 */
export function issueText(x: ElecIssue): string {
  const what = ISSUE[x.code] ?? x.code;
  if (x.code === "DUPLICATE") return `第 ${x.row} 行${what.replace("{0}", String(x.value ?? ""))}`;
  const where = x.col < 0 ? `第 ${x.row} 行` : `${colLetter(x.col)}${x.row}${x.header ? `（${x.header}）` : ""}`;
  return x.value ? `${where}${what}：${x.value}` : `${where}${what}`;
}

export const BATCH_STATUS: Record<ElecBatchStatus, string> = {
  NEED_MAPPING: "待选列", PARSED: "待确认", APPLIED: "已上架", CANCELLED: "已放弃",
  SUPERSEDED: "已作废", FAILED: "解析失败", EXPIRED: "已过期",
};

/** 字段来源的角标。只有 AI 认的需要他核对，别的不打扰 */
export const COLUMN_SOURCE: Record<ElecColumnSource, string> = {
  REMEMBERED: "上次", ALIAS: "", AI: "AI 识别，请核对", MANUAL: "",
};

/** 上传表的列含义（字段码 → 人话）。顺序就是列映射里的展示顺序 */
export const COLUMN_FIELDS: { key: string; label: string; required?: boolean }[] = [
  { key: "MPN", label: "料号", required: true },
  { key: "MFR", label: "厂牌" },
  { key: "QTY", label: "数量", required: true },
  { key: "DC", label: "批号" },
  { key: "PRICE", label: "单价" },
  { key: "PACKAGE", label: "封装" },
  { key: "MOQ", label: "起订量" },
  { key: "SPQ", label: "最小包装量" },
  { key: "PACKING", label: "包装" },
  { key: "CONDITION", label: "货况" },
  { key: "CURRENCY", label: "币种" },
  { key: "LEAD", label: "交期" },
  { key: "REGION", label: "货源地" },
];

/** 列序号 → Excel 列字母：0 → A，26 → AA */
export function colLetter(i: number): string {
  let s = "";
  let n = i + 1;
  while (n > 0) {
    const r = (n - 1) % 26;
    s = String.fromCharCode(65 + r) + s;
    n = Math.floor((n - 1) / 26);
  }
  return s;
}
