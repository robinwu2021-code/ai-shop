import type { AfterSale, Order } from "@shared/types";

/** 商家订单待办行；订单事实与需要执行的动作同时返回。 */
export interface MerchantOrderWorkItem {
  /** 订单摘要 */
  order: Order;
  /** 当前订单待办动作 */
  actionKind: "AFTER_SALE" | "RECEIVE_PAYMENT" | "FULFILL" | "VERIFY";
  /** 待办优先级 */
  priority: number;
  /** 关联售后单号 */
  afterSaleNo: string | null;
  /** 关联售后单状态 */
  afterSaleStatus: string | null;
}

/** 门店订单可切换的履约选项。 */
export interface MerchantFulfillmentOption {
  /** 履约方式 */
  fulfillment: string;
  /** 当前门店是否允许选择 */
  allowed: boolean;
  /** 不允许时的原因 */
  reason: string | null;
}

/** 当前履约方式及可选项。 */
export interface MerchantFulfillmentOptions {
  /** 门店子订单号 */
  subOrderNo: string;
  /** 当前履约方式 */
  currentFulfillment: string;
  /** 并发更新版本 */
  version: number;
  /** 本门店可选的履约方式 */
  options: MerchantFulfillmentOption[];
}

/** 履约方式变更结果。 */
export interface MerchantFulfillmentChange {
  /** 更新后的订单摘要 */
  order: Order;
  /** 新的并发更新版本 */
  version: number;
}

/** 售后工作台行；订单摘要用于展示关联商品与金额。 */
export interface MerchantAfterSaleWorkItem {
  /** 售后单 */
  afterSale: AfterSale;
  /** 所属订单 */
  order: Order;
}
