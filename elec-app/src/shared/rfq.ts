// 询价单的几句「人话」：列表与详情都要用，放一处。
import type { ElecRfq, ElecRfqLine } from "@shared/types";
import { CLOSE_REASON, RFQ_STATUS, dateOf, qtyOf, subtotalOf, whenOf } from "./format";

/** 「STM32F103C8T6 等 3 项」「TPS54331DR × 5,000」 */
export function rfqTitle(r: ElecRfq): string {
  const first = r.lines[0];
  if (!first) return r.rfqNo;
  return r.lines.length > 1 ? `${first.mpn} 等 ${r.lines.length} 项` : `${first.mpn} × ${qtyOf(first.qty)}`;
}

/** 列表上的状态字：暂无货源是「结果」不是「失败」，要单独说 */
export function rfqStatusText(r: ElecRfq): string {
  if (r.status === "CLOSED" && r.closeReason) return CLOSE_REASON[r.closeReason];
  return RFQ_STATUS[r.status];
}

/**
 * 状态后面那半句：待报价写**预计时间**（采购最焦虑的是不知道要等多久），
 * 已报价写有效期，结束了的给下一步。
 */
export function rfqHint(r: ElecRfq): string {
  const at = whenOf(r.createdAt);
  switch (r.status) {
    case "SUBMITTED": {
      const due = new Date(new Date(r.createdAt.replace(" ", "T")).getTime() + 24 * 3600 * 1000);
      const quotes = r.quoteCnt ? ` · 已有 ${r.quoteCnt} 家报价` : "";
      return `${at} · 预计 ${whenOf(due.toISOString())} 前${quotes}`;
    }
    case "QUOTED":
      return r.quoteValidUntil ? `${at} · 报价有效至 ${dateOf(r.quoteValidUntil)}` : at;
    case "EXPIRED":
      return `${at} · 行情变了，重新询一次`;
    case "ACCEPTED":
      return `${at} · 平台专员对接中`;
    case "CLOSED":
      return r.closeReason === "NO_SOURCE" ? `${at} · 平台没找到货，可以换个料号再询` : at;
  }
}

/** 这一行按平台报价算的小计（元）；没报价为 null */
export function lineSubtotalE6(l: ElecRfqLine): number | null {
  if (!l.quote) return null;
  return l.quote.priceE6 * (l.quote.qty ?? l.qty);
}

/** 平台报价的合计。**只算报了的行** */
export function rfqTotal(r: ElecRfq): string | null {
  const parts = r.lines.map(lineSubtotalE6).filter((x): x is number => x !== null);
  if (!parts.length) return null;
  return subtotalOf(parts.reduce((a, b) => a + b, 0), 1);
}
