// 「改为不记库存」被拦 / 要确认时，弹框里那一句怎么拼（原型 inv-managed-switch s03 / s04）。
//
// 设置页与编辑商品页走同一套判，说法也要同一套 —— 两处各写一份，迟早一处说「待收货」一处说「在途」。
// 不碰 i18n 实例：翻译函数由调用方传进来，于是它能在 node 里直接断言。
import type { InvAffectedGoods, InvMode } from "@shared/types";

type T = (key: string, params?: Record<string, unknown>) => unknown;

/** 「进货单 PO0921-003 待收货；1 笔线上订单待出库」。同一种单据按单号去重 */
export function describeBlockers(t: T, goods: InvAffectedGoods[]): string {
  const seen = new Set<string>();
  const parts: string[] = [];
  for (const g of goods) {
    for (const b of g.blockers) {
      const key = `${b.kind}:${b.docNo}`;
      if (seen.has(key)) continue;
      seen.add(key);
      parts.push(`${g.title} · ${String(t(`stockSettings.blocker.${b.kind}`, { no: b.docNo }))}`);
    }
  }
  return parts.join("；");
}

/** 「香梨 · 5 斤装 实存 30；阳光玫瑰青提 实存 12」 */
export function describeStocked(t: T, goods: InvAffectedGoods[]): string {
  return goods.map((g) => String(t("stockSettings.onHand", { title: g.title, n: g.onHand }))).join("；");
}

/** 编辑页「库存管理」那一行的字：跟随品类要把跟到的结果写进括号，不然「跟随」是句空话 */
export function invModeLabel(t: T, mode: InvMode, categoryManaged: boolean): string {
  if (mode === "ON") return String(t("invMode.on"));
  if (mode === "OFF") return String(t("invMode.off"));
  return String(t("invMode.inherit", { state: String(t(categoryManaged ? "invMode.on" : "invMode.off")) }));
}
