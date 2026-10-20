/** PointsOrderState 的 phase 取值域，按服务端契约校验。 */
export type PointsOrderStatePhase = "NONE" | "RAW" | "ADMITTED" | "UNKNOWN" | "READY" | "RECONFIRM" | "CANCEL_REQUESTED" | "CANCELLED";

/** OrderPointsQuote 的 state 取值域，按服务端契约校验。 */
export type OrderPointsQuoteState = "AVAILABLE" | "UNAVAILABLE";

/** BuyerPointsAccount 的 status 取值域，按服务端契约校验。 */
export type BuyerPointsAccountStatus = "DRAFT" | "ACTIVE" | "DRAINING" | "RETIRED";

/** BuyerPointsEntry 的 bucket 取值域，按服务端契约校验。 */
export type BuyerPointsEntryBucket = "AVAILABLE" | "PENDING" | "HELD" | "REFUND_RESERVED";

export interface PointsOrderState {
  /** 源下单处理阶段 */
  phase: PointsOrderStatePhase;
  /** 服务端不可用原因或操作审计原因 */
  reason: string | null;
}

export interface OrderPointsQuote {
  /** 服务端处理状态 */
  state: OrderPointsQuoteState;
  /** 服务端不可用原因或操作审计原因 */
  reason: string | null;
  /** 按服务端报价使用的积分数量 */
  pointsUsed: number;
  /** 积分抵扣金额，最小货币单位 */
  deductMinor: number;
  /** 换算比例的积分数量 */
  ratePoints: number;
  /** 换算比例的最小货币单位数量 */
  rateMinor: number;
  /** 积分抵扣前的应付金额，最小货币单位 */
  basePayMinor: number;
  /** 按门店拆分的服务端报价 */
  stores: {
    /** 积分业务隔离的门店编号 */
    storeNo: string;
    /** 门店归属主体编号，不作为积分隔离维度 */
    merchantNo: string;
    /** 报价所绑定的门店策略版本 */
    policyVersion: number;
    /** 按服务端报价使用的积分数量 */
    pointsUsed: number;
    /** 积分抵扣金额，最小货币单位 */
    deductMinor: number;
  }[];
}

export interface BuyerPointsAccount {
  /** 积分名称 */
  name: string;
  /** 积分类型唯一编码 */
  typeCode: string;
  /** 市场代码 */
  market: string;
  /** ISO 货币代码 */
  currency: string;
  /** 服务端业务状态 */
  status: BuyerPointsAccountStatus;
  /** 可用积分余额 */
  balance: number;
  /** 待激活积分余额 */
  pendingBalance: number;
  /** 当前占用积分余额 */
  heldBalance: number;
  /** 换算比例的积分数量 */
  ratePoints: number;
  /** 换算比例的最小货币单位数量 */
  rateMinor: number;
  /** 最大抵扣比例，万分比 */
  deductRatioBp: number;
  /** 账户无活动失效天数 */
  idleDays: number;
  /** 是否冻结该账户新增使用 */
  frozen: boolean;
  /** 服务端计算的过期时间，Unix 毫秒；无则为空 */
  expireAt: number | null;
}

export interface BuyerPointsEntry {
  voucherNo?: string | null;
  voucherDocumentNo?: string | null;
  direction?: string | null;
  businessRef?: string | null;
  orderNo?: string | null;
  /** 不可变积分分录编号 */
  entryNo: string;
  /** 积分账本或资金池分桶 */
  bucket: BuyerPointsEntryBucket;
  /** 积分数量 */
  points: number;
  /** 该分录完成后的账本余额 */
  balanceAfter: number;
  /** 服务端事件时间，Unix 毫秒 */
  at: number;
}

export interface BuyerPointsEntryPage {
  /** 本页记录 */
  rows: BuyerPointsEntry[];
  /** 下一页游标 */
  next: number;
  /** 是否还有后续记录 */
  hasMore: boolean;
}
