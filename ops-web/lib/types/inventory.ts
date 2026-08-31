// 进销存（P-18）。**独立模块、独立库** —— 与商品/电商那几个域不共表也不共类型：
// 它将来要能单独交付，混进 product.ts 的那一刻这条就先在代码里破了。
//
// 运营端这一侧**三块全只读**：运营能改商家库存的那一刻，「这个数是谁改的」
// 就多了一个答案，而商家不会知道。要改只能让商家自己改，或者走工单留痕。

/** 一条不健康的库存。`kind` 决定这一行要怎么念，也决定该找谁 */
export interface InvHealthRow {
  /** NEGATIVE 负库存 · ZERO_ON_SALE 零库存仍在架 · STALE 长期未动销 */
  kind: InvHealthKind;
  /** 哪家商家的票 */
  entityNo: string;
  /** 商家名 */
  merchantName?: string;
  /** 门店号 */
  storeNo?: string;
  /** 物料号（进销存自己的编号） */
  itemId: string;
  /** 货品名 */
  itemName: string;
  /** 规格描述。人读的，不参与匹配 */
  specText?: string;
  /** 实存 */
  onHand: number;
  /** 预留：下了单还没付钱的量 */
  reserved: number;
  /** 可用 = 实存 − 预留 */
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
  /** 物料号（进销存自己的编号） */
  itemId: string;
  /** 维度名（「颜色」「净重」） */
  name: string;
  /** 规格描述。人读的，不参与匹配 */
  specText?: string;
  /** 基本计量单位。**所有数量以它为准** */
  baseUom?: string;
  /** 实存 */
  onHand: number;
  /** 预留：下了单还没付钱的量 */
  reserved: number;
  /** 可用 = 实存 − 预留 */
  available: number;
  /** 安全库存。低于它算缺货，0 = 不设 */
  safetyStock?: number | null;
  /** 最后一次动过的时间。滞销判据 */
  lastMovedAt?: string | null;
  /** SHORTAGE 缺货 · STALE 滞销。**空数组 = 这件没事** */
  flags: string[];
}

/** 台账一行。**不可变** —— 这里永远只有查看，没有编辑 */
export interface InvLedgerRow {
  /** 行号。台账不可变，它只用来分页定位 */
  id: number;
  /** `IN` 入库 / `OUT` 出库 */
  docKind: InvDocKind;
  /** 单号 */
  docNo: string;
  /** 变动原因码 */
  reasonCode: string;
  /** 变动量。**有符号**：入库为正、出库为负 */
  qtyDelta: number;
  /** 这一行之后的结存。台账靠它自证连续，断一行就看得出来 */
  balanceAfter: number;
  /** 单位成本（分） */
  unitCostMinor?: number;
  /** 业务发生时刻。**不是落库时刻** */
  occurredAt: string;
  /** 经手人 */
  operator?: string;
}

/** 对差报告。**`clean` 是切真相源的唯一判据** —— 连续 N 天为真才准切 */
/**
 * 台账一页。**后端返回的是分页对象，不是裸数组** ——
 * `nextCursor` 由服务端给，前端不要拿「最后一行的 id」自己推：
 * 那样在同一毫秒有多笔时会漏行，而漏的那几行不会有任何报错。
 */
export interface InvLedgerPage {
  /** 本页的台账行 */
  entries: InvLedgerRow[];
  /** null = 没有下一页 */
  nextCursor?: number | null;
}

export interface InvReconReport {
  /** 扫了多少个 SKU */
  scannedSkus: number;
  /** 本轮搬动了多少条 */
  moved: number;
  /** 跳过多少个 */
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
  /** 对不上的行 */
  diffs: InvReconDiff[];
}

export interface InvReconDiff {
  /** 哪家商家的票 */
  entityNo: string;
  /** 门店号 */
  storeNo?: string;
  /** 商城侧的 SKU 号 */
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

/**
 * 一把开放对接的钥匙。**没有 secret 字段，一个都没有。**
 *
 * 库里存的是哈希，明文只在签发那一刻的响应里出现一次。列表若带上它，
 * 会让人以为丢了还能回来找 —— 而实际上只能吊销重发。
 */
export interface InvCredential {
  /** 凭据号 */
  credentialId: string;
  /** 开放接口的调用方标识 */
  appKey: string;
  /** 给人看的：这把钥匙给了谁 */
  name: string;
  /** 逗号分隔：read / stock:sync */
  scopes: string;
  /** ACTIVE / REVOKED。**吊销不删行** —— 「什么时候停的」要查得到 */
  status: string;
  /** 空 = 不过期 */
  expiresAt?: string | null;
  /** 发现「这把钥匙半年没人用了」的唯一依据 */
  lastUsedAt?: string | null;
  /** 申请时刻 */
  createdAt?: string | null;
}

/** 签发的返回。**`appSecret` 这辈子只出现这一次** */
export interface InvCredentialIssued {
  /** 凭据号 */
  credentialId: string;
  /** 开放接口的调用方标识 */
  appKey: string;
  /** 密钥。**只在签发那一次返回**，之后取不回来 */
  appSecret: string;
}

// ── 2026-08-30：从 interface 里提出来的具名类型（内联联合对工具不可见）──

/** 不健康库存的类型。**它决定这一行怎么念，也决定该找谁** */
export type InvHealthKind = "NEGATIVE" | "ZERO_ON_SALE" | "STALE";

/** 台账方向。IN 入库 / OUT 出库 —— 与 shared 的 StockDocKind 同义，归一属另一批 */
export type InvDocKind = "IN" | "OUT";
