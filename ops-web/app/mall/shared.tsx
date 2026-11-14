"use client";

// 套餐商城各页签共用的小件：金额、状态映射、操作号、门店号输入。
//
// **三类状态分开显示**是这一页的硬要求：申报（买家说他付了）、资金（平台确认到账没有）、
// 权益（门店能不能用）。ACCEPTED 只是受理，不等于到账 —— 把它们压成一个「已付款」徽标，
// 运营就会在钱还没到的时候去给门店开通。
import * as React from "react";
import { formatMoney } from "@/lib/commerce-money";
import { humanError } from "@/lib/commerce-reason";
import type {
  MallAsyncStatus, MallCategoryNames, MallCategoryView, MallMoney, MallOfflineActionView, MallOfflineClaimStatus, MallOrderSummary,
} from "@/lib/types";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { StatusBadge, type StatusMap } from "@/components/ui/status-badge";
import type { MallCopy } from "./copy";

export type { MallCopy };

/** 金额：整数字符串格式化，不经过浮点 */
export function Money({ m }: { m: MallMoney | null | undefined }) {
  return <span className="tabular-nums">{formatMoney(m)}</span>;
}

/**
 * 幂等操作号。**一个表单打开期间只生成一次**（用 useState 初始化），
 * 重试沿用同一个号 —— 网络抖一下再点一次，后端认得出是同一件事，不会做两遍。
 */
export function newOpNo(prefix: string) {
  return `${prefix}-${Date.now().toString(36).toUpperCase()}-${Math.random().toString(36).slice(2, 6).toUpperCase()}`;
}

export const showErr = humanError;

// ── 商品分类（扁平行 → 树序 / 名称 / 路径）──

export const CATEGORY_MAX_DEPTH = 3;

/** 按当前界面语言取分类名；缺了按 zh-TW → zh-CN → en 回落 */
export function categoryName(names: MallCategoryNames | undefined, locale: string): string | null {
  const order = locale === "en" ? (["en", "zh-TW", "zh-CN"] as const) : (["zh-CN", "zh-TW", "en"] as const);
  for (const l of order) if (names?.[l]?.trim()) return names[l]!;
  return null;
}

/** 扁平行排成深度优先的树序（同级按 sortOrder、编码）；找不到上级的行挂在末尾 */
export function categoryTree(rows: MallCategoryView[]): MallCategoryView[] {
  const by = (a: MallCategoryView, b: MallCategoryView) => a.sortOrder - b.sortOrder || a.categoryCode.localeCompare(b.categoryCode);
  const codes = new Set(rows.map((r) => r.categoryCode));
  const out: MallCategoryView[] = [];
  const walk = (parent: string | null) => rows.filter((r) => (r.parentCode ?? null) === parent).sort(by)
    .forEach((r) => { if (!out.includes(r)) { out.push(r); walk(r.categoryCode); } });
  walk(null);
  rows.filter((r) => r.parentCode && !codes.has(r.parentCode)).sort(by).forEach((r) => { if (!out.includes(r)) { out.push(r); walk(r.categoryCode); } });
  return out;
}

/** code 的全部下级编码 */
export function descendantsOf(rows: MallCategoryView[], code: string): string[] {
  const kids = rows.filter((r) => r.parentCode === code).map((r) => r.categoryCode);
  return kids.flatMap((k) => [k, ...descendantsOf(rows, k)]);
}

/** 「硬体设备 › 收银设备」这样的路径名 */
export function categoryPath(rows: MallCategoryView[], code: string | undefined, locale: string): string {
  const parts: string[] = [];
  for (let r = rows.find((x) => x.categoryCode === code), hops = 0; r && hops < CATEGORY_MAX_DEPTH + 2; hops++) {
    parts.unshift(categoryName(r.names, locale) ?? r.categoryCode);
    r = r.parentCode ? rows.find((x) => x.categoryCode === r!.parentCode) : undefined;
  }
  return parts.length ? parts.join(" › ") : (code ?? "");
}

export const fmtAt = (s?: string | null) => (s ? new Date(s).toLocaleString() : "—");

export const claimMap = (c: MallCopy): StatusMap<MallOfflineClaimStatus> => ({
  SUBMITTED: { label: c.claimSubmitted, tone: "warning" },
  REVIEWING: { label: c.claimReviewing, tone: "info" },
  CONFIRMED: { label: c.claimConfirmed, tone: "success" },
  MISMATCHED: { label: c.claimMismatched, tone: "danger" },
  DECLINED: { label: c.claimDeclined, tone: "muted" },
});

export const paymentMap = (c: MallCopy): StatusMap<string> => ({
  UNPAID: { label: c.payUnpaid, tone: "warning" },
  PAYING: { label: c.payPaying, tone: "info" },
  PAYMENT_UNKNOWN: { label: c.payUnknown, tone: "danger" },
  CANCEL_PENDING: { label: c.payCancelPending, tone: "warning" },
  PAID: { label: c.payPaid, tone: "success" },
  CLOSED: { label: c.payClosed, tone: "muted" },
});

export const fulfillmentMap = (c: MallCopy): StatusMap<string> => ({
  WAIT_INVENTORY: { label: c.fulWaitInventory, tone: "muted" },
  PENDING: { label: c.fulPending, tone: "muted" },
  PROCESSING: { label: c.fulProcessing, tone: "info" },
  PARTIAL: { label: c.fulPartial, tone: "warning" },
  COMPLETED: { label: c.fulCompleted, tone: "success" },
  FAILED: { label: c.fulFailed, tone: "danger" },
});

export const refundAxisMap = (c: MallCopy): StatusMap<string> => ({
  NONE: { label: c.rfNone, tone: "muted" },
  REFUNDING: { label: c.rfRefunding, tone: "warning" },
  PARTIAL: { label: c.rfPartial, tone: "info" },
  REFUNDED: { label: c.rfRefunded, tone: "default" },
});

export const asyncMap = (c: MallCopy): StatusMap<MallAsyncStatus> => ({
  PENDING: { label: c.asyncPending, tone: "warning" },
  SUCCEEDED: { label: c.asyncSucceeded, tone: "success" },
  FAILED: { label: c.asyncFailed, tone: "muted" },
  UNKNOWN: { label: c.asyncUnknown, tone: "danger" },
});

export const saleMap = (c: MallCopy): StatusMap<string> => ({
  DRAFT: { label: c.saleDraft, tone: "muted" },
  ON_SALE: { label: c.saleOn, tone: "success" },
  OFF_SALE: { label: c.saleOff, tone: "warning" },
});

export const versionMap = (c: MallCopy): StatusMap<string> => ({
  DRAFT: { label: c.verDraft, tone: "warning" },
  PUBLISHED: { label: c.verPublished, tone: "success" },
});

export const actionMap = (c: MallCopy): StatusMap<MallOfflineActionView["status"]> => ({
  ACCEPTED: { label: c.actAccepted, tone: "warning" },
  APPLIED: { label: c.actApplied, tone: "success" },
  UNMATCHED: { label: c.actUnmatched, tone: "danger" },
  REJECTED: { label: c.actRejected, tone: "danger" },
});

/** 订单三维状态：资金 / 权益 / 退款，各自一个带标签的徽标 */
export function OrderAxes({ c, s }: { c: MallCopy; s?: MallOrderSummary }) {
  if (!s) return null;
  return (
    <span className="flex flex-wrap items-center gap-1.5">
      <span className="txt-caption text-muted-foreground">{c.axisPayment}</span>
      <StatusBadge map={paymentMap(c)} value={s.paymentStatus ?? ""} />
      <span className="txt-caption text-muted-foreground">{c.axisFulfillment}</span>
      <StatusBadge map={fulfillmentMap(c)} value={s.fulfillmentStatus ?? ""} />
      <span className="txt-caption text-muted-foreground">{c.axisRefund}</span>
      <StatusBadge map={refundAxisMap(c)} value={s.refundStatus ?? ""} />
    </span>
  );
}

/** 线下动作结果。ACCEPTED 额外挂一句「资金端未确认」 */
export function ActionResult({ c, r }: { c: MallCopy; r: MallOfflineActionView | null }) {
  if (!r) return null;
  return (
    <span className="flex flex-wrap items-center gap-2">
      <StatusBadge map={actionMap(c)} value={r.status} />
      {r.paymentNo && <code className="txt-caption">{r.paymentNo}</code>}
      {r.status === "ACCEPTED" && <Badge tone="warning">{c.actAcceptedHint}</Badge>}
    </span>
  );
}

/**
 * 门店号输入。运营不属于任何门店，门店维度的接口都要显式 storeNo ——
 * 不从登录态推，也不给一个「默认门店」（那会让人在错的店上操作而不自知）。
 */
export function StoreBar({
  c, value, onSubmit, id,
}: { c: MallCopy; value: string; onSubmit: (v: string) => void; id: string }) {
  const [draft, setDraft] = React.useState(value);
  React.useEffect(() => setDraft(value), [value]);
  return (
    <form
      className="flex flex-wrap items-center gap-2"
      onSubmit={(e) => { e.preventDefault(); onSubmit(draft.trim()); }}
    >
      <label htmlFor={id} className="txt-label text-muted-foreground">{c.storeNoLabel}</label>
      <Input id={id} className="w-56" value={draft} placeholder={c.storeNoPh} onChange={(e) => setDraft(e.target.value)} />
      <Button type="submit" size="sm" disabled={!draft.trim()}>{c.storeNoGo}</Button>
    </form>
  );
}

/** 三家台湾线上通道：**写死未接通**，不读任何可被改成 true 的配置 */
export const TW_ONLINE_CHANNELS = ["NewebPay", "LINE Pay", "JKOPay"] as const;
