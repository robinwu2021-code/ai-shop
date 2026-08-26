// 进销存（P-18）。**独立模块、独立库** —— 与商品/电商那几个域不共表也不共类型：
// 它将来要能单独交付，混进 product.ts 的那一刻这条就先在代码里破了。
//
// 运营端这一侧**三块全只读**：运营能改商家库存的那一刻，「这个数是谁改的」
// 就多了一个答案，而商家不会知道。要改只能让商家自己改，或者走工单留痕。

/** 一条不健康的库存。`kind` 决定这一行要怎么念，也决定该找谁 */
export interface InvHealthRow {
  /** NEGATIVE 负库存 · ZERO_ON_SALE 零库存仍在架 · STALE 长期未动销 */
  kind: "NEGATIVE" | "ZERO_ON_SALE" | "STALE";
  entityNo: string;
  merchantName?: string;
  storeNo?: string;
  itemId: string;
  itemName: string;
  specText?: string;
  onHand: number;
  reserved: number;
  available: number;
  /** STALE 才有：多少天没动过 */
  idleDays?: number;
}

/** 台账一行。**不可变** —— 这里永远只有查看，没有编辑 */
export interface InvLedgerRow {
  id: number;
  docKind: "IN" | "OUT";
  docNo: string;
  reasonCode: string;
  qtyDelta: number;
  balanceAfter: number;
  unitCostMinor?: number;
  occurredAt: string;
  operator?: string;
}

/** 对差报告。**`clean` 是切真相源的唯一判据** —— 连续 N 天为真才准切 */
/**
 * 台账一页。**后端返回的是分页对象，不是裸数组** ——
 * `nextCursor` 由服务端给，前端不要拿「最后一行的 id」自己推：
 * 那样在同一毫秒有多笔时会漏行，而漏的那几行不会有任何报错。
 */
export interface InvLedgerPage {
  entries: InvLedgerRow[];
  /** null = 没有下一页 */
  nextCursor?: number | null;
}

export interface InvReconReport {
  scannedSkus: number;
  moved: number;
  skipped: number;
  clean: boolean;
  diffs: InvReconDiff[];
}

export interface InvReconDiff {
  entityNo: string;
  storeNo?: string;
  skuNo: string;
  /** 平台侧的数（prd_sku / prd_store_stock） */
  platformQty: number;
  /** 进销存侧的数（inv_stock_balance） */
  inventoryQty: number;
}
