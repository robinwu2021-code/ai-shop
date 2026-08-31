// 售后治理域（矩阵 P-6.1）。矩阵 §七 六条必闭合链路里，「售后赔付」是唯一三端整条全缺的。
export type AfterSaleType = "REFUND_ONLY" | "RETURN_REFUND" | "EXCHANGE";

export type AfterSaleStatus =
  | "APPLIED"      // 用户已申请，等商家处理
  | "REFUNDING"    // 已同意，退款处理中（退货退款时是「等买家寄回」）
  | "ARBITRATING"  // 争议上升，平台介入
  | "REJECTED"     // 商家驳回（用户仍可上升平台）
  | "REFUNDED"     // 已退款（终态）
  | "CLOSED";      // 关闭（终态）

/*
 * ⚠️ 这份枚举与 `ord_after_sale.status`、C 端、B 端**同名同义**。
 *
 * 它曾经是另一套名字（MERCHANT_HANDLING / PLATFORM_INTERVENE / AGREED），
 * 与后端的 REFUNDING / ARBITRATING 对不上 —— 而 ops-web 一直只跑 mock，
 * 所以这套名字从来没被真实响应打脸过。接后端时如果加一层静默映射，
 * 后果是运营端的流转表与后端状态机各说各话：界面会给出一个后端根本不接受的按钮，
 * 点下去报「状态不允许」，而运营看不出为什么。
 *
 * 删掉的 MERCHANT_HANDLING 没有任何生产者：后端同意售后是 APPLIED → REFUNDING，
 * 中间没有「商家处理中」这一档。
 */
export const AFTERSALE_TRANSITIONS: Record<AfterSaleStatus, AfterSaleStatus[]> = {
  APPLIED: ["REFUNDING", "REFUNDED", "REJECTED", "CLOSED"],
  REFUNDING: ["REFUNDED", "CLOSED"],
  // 驳回不是终点：用户可以把争议上升到平台，这是「平台介入」存在的理由
  REJECTED: ["ARBITRATING", "CLOSED"],
  ARBITRATING: ["REFUNDING", "REFUNDED", "CLOSED"],
  REFUNDED: [],
  CLOSED: [],
};

/** 责任方（P-6.1.4）。三方之外没有第四种可能：货是商家的、点是自提点的、规则是平台的。 */
export type Liability = "PLATFORM" | "MERCHANT" | "PICKUP";

/**
 * 赔付出资比例，百分比，**三者之和必须为 100**。
 * ⚠️ 矩阵 M4「售后责任归属与出资方比例」口径未定，真实后端从没实现过这个概念——
 * `ord_after_sale` 上没有存它的列，裁决台曾经收集这三个数字一起提交，
 * 点「确认裁决」时被后端静默丢弃，运营以为填了就生效。**只有 finance 域的
 * 「退款回退分账」mock 队列还在用这两个字段**（`share`/`refundSplitPending`），
 * 而那条队列本身也是 mock-only——`/ops/refund-split-backs` 后端不存在。
 * M4 有结论、这两块真正接上后端之后再把类型收紧。
 */
export interface LiabilityShare {
  /** 平台 */
  platform: number;
  /** 商家承担的份额 */
  merchant: number;
  /** 自提点承担的份额 */
  pickup: number;
}

export interface AfterSale {
  /** 售后单号 */
  afterSaleNo: string;
  /** 关联的子订单 */
  subOrderNo: string;
  /** 关联的主订单 */
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
  /** 申请退款金额（分）。裁决只决定退不退，不改这个数 */
  refundMinor: number;
  /** 用户填写的售后原因 */
  reason: string;
  /** 举证材料（照片） */
  images: string[];
  /** 裁定的责任方。平台介入后才有值 */
  liability?: Liability;
  /** 赔付出资比例。**仅 finance 域 mock 队列使用**，真实后端未接（见上方说明） */
  share?: LiabilityShare;
  /** 裁决说明：用户与商家都会看到 */
  verdict?: string;
  /**
   * E4 退款回退分账待办：finance 域「退款回退分账」mock 队列专用字段，
   * 真实后端未接（见上方说明），售后本身的裁决流程不读写它。
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
