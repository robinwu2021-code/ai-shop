import type { AfterSale, Order } from "@shared/types";

export type MerchantOrderActionKind = "AFTER_SALE" | "RECEIVE_PAYMENT" | "FULFILL" | "VERIFY";

/** 商家订单待办行；订单事实与需要执行的动作同时返回。 */
export interface MerchantOrderWorkItem {
  /** 订单摘要 */
  order: Order;
  /** 当前订单待办动作 */
  actionKind: MerchantOrderActionKind;
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

/** 订单库存追溯：当前状态、事件投递和实物出入账事实。 */
export interface MerchantInventoryTrace {
  orderNo: string;
  syncEvents: Array<{
    aggregateId: string; eventType: string; source: string;
    status: "COMPLETED" | "PENDING" | "FAILED" | "WARNING" | "SKIPPED" | "ARCHIVED";
    outboxMode: "UNIFIED" | "LEGACY";
  }>;
  reservations: Array<{
    reservationId: string; ownerId: string; status: string; lifecycleVersion: number | null;
    outboundNo: string | null; createdAt: string | null;
    lines: Array<{
      lineNo: number; fulfillmentRef: string | null; itemId: string; locationId: string;
      reservedQty: number; issuedQty: number; releasedQty: number; outboundNo: string | null;
      issuedAt: string | null; updatedBy: string | null;
      issueLedger: Array<{ id: number; docNo: string; reasonCode: string; qtyDelta: number;
        balanceBefore: number; balanceAfter: number; operator: string | null; createdBy: string | null; occurredAt: string }>;
      returns: Array<{ receiptNo: string; afterSaleNo: string; qty: number; quality: string;
        locationId: string; inboundNo: string; createdBy: string | null; createdAt: string }>;
    }>;
  }>;
}
