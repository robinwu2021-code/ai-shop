// 券的几句固定说法（原型 s12–s18）：类型、规则、门槛、有效期、数量。
//
// 列表卡、详情、建券确认、发放页四处都要把同一张券说一遍 —— 各写一份的话，
// 同一张券在列表上叫「现金 · 领后 7 天有效」、在详情里叫「满减 · 7 天」，而没有任何一处报错。
import { money } from "@shared/utils/money";
import type { MerchantCoupon } from "@shared/types";

type T = (key: string, args?: Record<string, unknown>) => string;

/**
 * 券的类型：满减券 / 现金券 / 折扣券 / 商品券（免运费券后端有、B 端不建）。
 * **不是库里的一列**，由「权益方式 + 有没有门槛 + 兑换几次」推出来：
 * 满减券 = 现金 + 门槛；现金券 = 现金无门槛；商品券 = 凭券到店兑换指定商品，次数 > 1 就是次卡。
 * 与活动的对应：满减券 ≈ 满减活动、折扣券 ≈ 打折 —— 差别只在「券要领到手、活动自动生效」。
 */
export type CouponKind = "FULL_CUT" | "CASH" | "PERCENT" | "GOODS" | "FREE_SHIP";

export function couponKind(c: Pick<MerchantCoupon, "benefitMode" | "minAmountMinor">): CouponKind {
  if (c.benefitMode === "GIFT") return "GOODS";
  if (c.benefitMode === "PERCENT") return "PERCENT";
  if (c.benefitMode === "FREE_SHIP") return "FREE_SHIP";
  return (c.minAmountMinor ?? 0) > 0 ? "FULL_CUT" : "CASH";
}

/** 「满 ¥50 减 ¥5」「减 ¥5」「9 折 · 封顶 ¥20」「豆浆 1 杯 · 5 次」 */
export function couponRule(t: T, c: MerchantCoupon): string {
  const kind = couponKind(c);
  if (kind === "GOODS") {
    const g = c.benefitRef || t("couponText.ruleGoodsAny");
    return (c.timesTotal ?? 1) > 1 ? t("couponText.ruleGoodsTimes", { g, n: c.timesTotal }) : t("couponText.ruleGoods", { g });
  }
  if (kind === "PERCENT") {
    return t("couponText.rulePercent", { z: (c.benefitValue / 1000).toFixed(1).replace(/\.0$/, ""),
      cap: money(c.benefitCapMinor ?? 0) });
  }
  if (kind === "FREE_SHIP") return t("couponText.ruleFreeShip");
  if (kind === "FULL_CUT") return t("couponText.ruleFullCut", { m: money(c.minAmountMinor ?? 0), n: money(c.benefitValue) });
  return t("couponText.ruleCash", { n: money(c.benefitValue) });
}

/** 「满 ¥50 · 全店」「无门槛 · 指定商品」 */
export function couponThreshold(t: T, c: MerchantCoupon): string {
  const min = c.minAmountMinor ? t("couponText.min", { n: money(c.minAmountMinor) }) : t("couponText.noMin");
  const scope = c.scopeType && c.scopeType !== "ALL" ? t("couponText.scopeSome") : t("couponText.scopeAll");
  return `${min} · ${scope}`;
}

/** 「领后 7 天」「至 09-30」 */
export function couponValidity(t: T, c: MerchantCoupon): string {
  if (c.validityMode === "RELATIVE") return t("couponText.validRelative", { n: c.validDays ?? 7 });
  if (!c.endAt) return t("couponText.validNone");
  const d = new Date(c.endAt);
  return t("couponText.validUntil", {
    d: `${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`,
  });
}

/** 「200 张 · 每人 1 张」；不限量时只说每人 */
export function couponQuantity(t: T, c: MerchantCoupon): string {
  const per = t("couponText.perUser", { n: c.perUserLimit });
  return c.totalCount == null ? per : `${t("couponText.total", { n: c.totalCount })} · ${per}`;
}

/**
 * 列表卡的进度：次卡按核销次数（「核销 62 / 150 次」），其余按领取张数（「领 128 / 200」）。
 * 分母是空（不限量）时不画进度条。
 */
export function couponProgress(t: T, c: MerchantCoupon): { pct: number | null; text: string } {
  if ((c.timesTotal ?? 1) > 1) {
    const cap = c.totalCount == null ? null : c.totalCount * c.timesTotal;
    return {
      pct: cap ? Math.min(100, Math.round((c.usedTimes / cap) * 100)) : null,
      text: t("couponText.usedOf", { n: c.usedTimes, m: cap ?? "—" }),
    };
  }
  return {
    pct: c.totalCount ? Math.min(100, Math.round((c.receivedCount / c.totalCount) * 100)) : null,
    text: t("couponText.receivedOf", { n: c.receivedCount, m: c.totalCount ?? "—" }),
  };
}
