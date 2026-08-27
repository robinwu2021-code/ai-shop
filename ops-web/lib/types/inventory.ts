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

/**
 * 某一个商家的一行库存余额（`BalanceVO`）。健康度页点进某一行时看的东西。
 *
 * <p>与 {@link InvHealthRow} **不是同一件事**：那边是「不知道该看谁」时的平台级扫描，
 * 这边必须先知道看哪个商家。两者共用过同一个路径名，代价是运营端照着名字接错。
 */
export interface InvBalanceRow {
  itemId: string;
  name: string;
  specText?: string;
  baseUom?: string;
  onHand: number;
  reserved: number;
  available: number;
  safetyStock?: number | null;
  lastMovedAt?: string | null;
  /** SHORTAGE 缺货 · STALE 滞销。**空数组 = 这件没事** */
  flags: string[];
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
  /**
   * 扫到了但**还没搬**的。**它必须是 0 才准切真相源** ——
   * 没搬的那些在进销存侧余额是 0，切过去就是「全都卖不了」。
   *
   * 这一列原本不存在：`moveOne` 只算不写时故意不把没搬过的算成差异，
   * `doRun` 又把它们计成既不 moved 也不 skipped，于是它们在报告里一个字都不出现，
   * 而 `clean` 只看 diffs —— 闸门守着一个它没在看的东西。
   */
  pending: number;
  /** 没有差异**且**没有待搬的。两者缺一都不算干净 */
  clean: boolean;
  diffs: InvReconDiff[];
}

export interface InvReconDiff {
  entityNo: string;
  storeNo?: string;
  skuNo: string;
  /** 平台侧的实存（prd_sku / prd_store_stock） */
  platformQty: number;
  /** 进销存侧的实存（inv_stock_balance） */
  inventoryQty: number;
  /**
   * 平台侧的预留（`locked_stock`）。
   *
   * **实存一样、预留不一样也是差异** —— 只比实存的话，
   * 「已被下单占住的货」在两边对不上会被报成干净，
   * 而切过去那些货就重新变成可售了。
   */
  platformHeld: number;
  /** 进销存侧的预留 */
  inventoryHeld: number;
}
