// 售后可用性规则（TDD-售后规则-审核与部分退款 §2.2）。
// **与后端 backend/.../trade/service/AfterSaleRules.java + FulfillStage.java 必须逐条一致** ——
// 两边共用同一张用例表（§4 R4：判据分叉会造成前端放行、后端拒绝，或反过来）。
//
// 核心：生鲜能不能退取决于「货还拦不拦得住」(FulfillStage)，不是订单状态字段。
import type { AfterSaleReason } from "../types";

/** 履约进度五档（与后端 FulfillStage 同名同义） */
export type FulfillStage =
  /** 未备货：货没动，自由退 */
  | "NOT_PREPARED"
  /** 备货中：商家已为这单采购/分拣（生鲜已付出成本）—— 灰色地带 */
  | "PREPARING"
  /** 已出单未揽收：录了运单号但快递没来取，货还在仓里 —— 灰色地带 */
  | "LABELED"
  /** 已揽收·在途：拦不住 */
  | "SHIPPED"
  /** 已签收：买家手里 */
  | "RECEIVED";

/** 质量原因：损坏 / 变质 / 过期。其余为非质量。 */
export const QUALITY_REASONS: AfterSaleReason[] = ["DAMAGED", "QUALITY", "EXPIRED"];
/** 全部原因（与后端 OrdAfterSale.REASONS 同序）。 */
export const ALL_REASONS: AfterSaleReason[] = [
  "NOT_WANTED", "DAMAGED", "MISSING", "WRONG_ITEM", "QUALITY", "EXPIRED", "OTHER",
];

/** 后端拒绝码，映射到 ErrorCode.AFTERSALE_FRESH_QUALITY_ONLY */
export const DENY_FRESH_QUALITY_ONLY = "FRESH_SHIPPED_NON_QUALITY";

export function isQualityReason(reason: AfterSaleReason): boolean {
  return QUALITY_REASONS.includes(reason);
}

/** 订单任一商品是生鲜 */
export function isFresh(itemTypes: (string | null | undefined)[]): boolean {
  return itemTypes.some((t) => t === "FRESH");
}

export interface Gate {
  /** 能否建单 */
  allow: boolean;
  /** 灰色地带：可建单但必须商家确认 */
  needMerchant: boolean;
  /** 不允许时的拒绝码 */
  deny?: string;
}

/**
 * 能否申请 + 是否灰色（§2.2 矩阵）：
 * - 生鲜 + 已揽收或已签收 + 非质量 → 拒
 * - 生鲜 + 备货中或已出单未揽收（灰色）→ 允许但需商家确认
 * - 其余 → 允许
 */
export function applyGate(stage: FulfillStage, fresh: boolean, reason: AfterSaleReason): Gate {
  const shippedOrReceived = stage === "SHIPPED" || stage === "RECEIVED";
  if (fresh && shippedOrReceived && !isQualityReason(reason)) {
    return { allow: false, needMerchant: false, deny: DENY_FRESH_QUALITY_ONLY };
  }
  if (fresh && (stage === "PREPARING" || stage === "LABELED")) {
    return { allow: true, needMerchant: true };
  }
  return { allow: true, needMerchant: false };
}

/** 已签收才需要图片凭证；未收货不显示上传框。 */
export function needImage(stage: FulfillStage): boolean {
  return stage === "RECEIVED";
}

/** 该档位可选的原因：生鲜在已揽收/已签收只剩质量三项，其余给全部。 */
export function reasonsFor(stage: FulfillStage, fresh: boolean): AfterSaleReason[] {
  const onlyQuality = fresh && (stage === "SHIPPED" || stage === "RECEIVED");
  return onlyQuality ? QUALITY_REASONS : ALL_REASONS;
}

/**
 * 从**订单详情**算出履约进度（C 端用，与后端 {@code FulfillStage.of} 同一判据的端上镜像）。
 *
 * 端上拿到的是**契约状态**（`WAIT_FULFILL` 已归一成 `PAID`），所以：
 * 已签收看 `COMPLETED`；在途看运单（`trace.status`，非 `CREATED` 即已揽收）；
 * 否则看有没有开始备货。6 小时宽限（LABELED→SHIPPED）端上不精算 —— 那是灰色与硬拒的边界，
 * 后端 apply 会权威判定；端上宁可显示成「灰色（可申请、商家确认）」也不先行硬拒。
 */
export function orderStageOf(o: {
  status: string;
  preparedAt?: number | null;
  trace?: { status: string } | null;
}): FulfillStage {
  if (o.status === "COMPLETED") return "RECEIVED";
  const w = o.trace?.status;
  if (w) return w === "CREATED" ? "LABELED" : "SHIPPED";
  return o.preparedAt != null ? "PREPARING" : "NOT_PREPARED";
}
