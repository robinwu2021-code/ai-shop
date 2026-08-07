// 售后治理域（矩阵 P-6.1）。矩阵 §七 六条必闭合链路里，「售后赔付」是唯一三端整条全缺的。
export type AfterSaleType = "REFUND_ONLY" | "RETURN_REFUND" | "EXCHANGE";

export type AfterSaleStatus =
  | "APPLIED"             // 用户已申请，等商家处理
  | "MERCHANT_HANDLING"   // 商家处理中
  | "PLATFORM_INTERVENE"  // 争议上升，平台介入
  | "AGREED"              // 同意，待打款
  | "REJECTED"            // 商家驳回（用户仍可上升平台）
  | "REFUNDED"            // 已退款（终态）
  | "CLOSED";             // 关闭（终态）

export const AFTERSALE_TRANSITIONS: Record<AfterSaleStatus, AfterSaleStatus[]> = {
  APPLIED: ["MERCHANT_HANDLING", "AGREED", "REJECTED", "PLATFORM_INTERVENE"],
  MERCHANT_HANDLING: ["AGREED", "REJECTED", "PLATFORM_INTERVENE"],
  // 驳回不是终点：用户可以把争议上升到平台，这是"平台介入"存在的理由
  REJECTED: ["PLATFORM_INTERVENE", "CLOSED"],
  PLATFORM_INTERVENE: ["AGREED", "CLOSED"],
  AGREED: ["REFUNDED"],
  REFUNDED: [],
  CLOSED: [],
};

/** 责任方（P-6.1.4）。三方之外没有第四种可能：货是商家的、点是自提点的、规则是平台的。 */
export type Liability = "PLATFORM" | "MERCHANT" | "PICKUP";

/**
 * 赔付出资比例，百分比，**三者之和必须为 100**。
 * ⚠️ 矩阵 M4「售后责任归属与出资方比例」未定，所以这里**存结构不存结论** ——
 * 口径定了只需要给默认值，不用改模型。
 */
export interface LiabilityShare {
  /** 平台出资比例（百分比） */
  platform: number;
  /** 商家出资比例（百分比） */
  merchant: number;
  /** 自提点承接方出资比例（百分比） */
  pickup: number;
}

export interface AfterSale {
  /** 售后单号 */
  asNo: string;
  /** 关联的子订单 */
  orderNo: string;
  /** 涉事商家 */
  merchantNo: string;
  /** 商家名快照 */
  merchantName: string;
  /** 申请人昵称 */
  buyerNickname: string;
  /** 售后类型：仅退款 / 退货退款 / 换货 */
  type: AfterSaleType;
  /** 售后单状态。允许的流转见 `AFTERSALE_TRANSITIONS` */
  status: AfterSaleStatus;
  /** 申请退款金额（分）。**不得超过订单实付** —— 校验要跨域查订单。 */
  amount: number;
  /** 用户填写的售后原因 */
  reason: string;
  /** 举证材料数量（照片/聊天记录） */
  evidenceCount: number;
  /** 裁定的责任方。平台介入后才有值 */
  liability?: Liability;
  /** 赔付出资比例。口径未定（M4），先存结构 */
  share?: LiabilityShare;
  /** 裁决说明：用户与商家都会看到 */
  verdict?: string;
  /**
   * E4 退款回退分账待办：裁决完成但资金域（P-12）尚未接。
   * 留这个标记而不是假装已完成 —— 接资金域时按它补跑。
   */
  refundSplitPending?: boolean;
  /** 售后发起时间 */
  createdAt: string;
}

/** 极速退阈值（P-6.1.2）：满足条件的小额售后由系统自动通过，不占人工。 */
export interface FastRefundRule {
  /** 总开关。关掉后所有小额售后都走人工 */
  enabled: boolean;
  /** 金额上限（分），必须 > 0 */
  maxAmount: number;
  /** 下单后多少小时内可用，必须 ≥ 1（0 小时等于关掉，但看起来像开着） */
  withinHours: number;
  /** 适用品类编码，空 = 全品类 */
  categories: string[];
  /** 最后修改时间 */
  updatedAt: string;
  /** 最后修改人（STAFF 账号） */
  updatedBy: string;
}
