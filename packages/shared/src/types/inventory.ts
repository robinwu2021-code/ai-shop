// 进销存：库存余额、单据、盘点、调拨
//
// 三端共用的契约镜像，按域切开的一份 —— 口径与切开之前逐字相同，见 `index.ts`。

// ── 进销存（P-18 / B-1…B-21）────────────────────────────────────────────
//
// **逐字对着后端的 record 抄**，不是照界面拟的。上一轮运营端就是照自拟的形状
// 接的，mock 也照那份写，于是两边自洽、mock 自查全过，而真接口一个都调不通。
// 对照：`shop-inventory/.../dto/InventoryVOs.java` 与各 `Biz*Controller` 的 Req。

/** 库存总览的三个数（`SummaryVO`）。 */
export interface StockSummary {
  /** 在管货品数 */
  itemCount: number;
  /** 缺货件数（低于安全库存） */
  shortageCount: number;
  /** 滞销件数（长期未动销） */
  staleCount: number;
  /** 待收货的调拨单数。**按单不按件** —— 收货是按单做的，给件数点不进任何一张单 */
  inTransitCount: number;
  /**
   * 还开着的那张盘点单的单号，没有就没有这个字段。
   * **给单号不给个数**：工作台的「继续盘点」要带着它跳，
   * 不带的话那一页会开一张**新的**盘点单，而按钮上写着「继续」。
   */
  openCountNo?: string | null;
}
/**
 * 一家供应商（`SupplierVO`）。进货单指向的那个**稳定对象** ——
 * 在它之前只有一个会漂的名字字符串。
 */
export interface Supplier {
  /** 档案编号 `SUP…`。**进货单存的是它，不是名字** —— 名字会改，指向不会 */
  supplierNo: string;
  /** 全名。同一商家内唯一（后端 `uk_sup_name`），重名建档会被 10409 拒 */
  name: string;
  /** 短名，单据列表上显示它 —— 长名换行会把一行撑成两行 */
  shortName?: string | null;
  /** 联系人。**只作记录**，不发通知：这一版没有给供应商推消息的通道 */
  contactName?: string | null;
  /** 联系电话。同上，给人打的，不参与任何自动流程 */
  contactPhone?: string | null;
  /** 备注。**引用平台档案时这一列仍归商家写** —— 那是他自己的话 */
  remark?: string | null;
  /** ACTIVE 在用 · ARCHIVED 已停用。**停用不删除** —— 历史单据要指得回去 */
  status: string;
  /**
   * 引用平台档案。**据此把名称与联系方式置灰** ——
   * 不看这一位的话，商家会改了才发现改不动。
   */
  fromPlatform: boolean;
}
/** 一行库存（`BalanceVO`）。 */
export interface StockBalance {
  /** 物料号。**进销存自己的编号**，与商城的 skuNo 靠 inv_item_ref 对上 —— 跨库不能外键 */
  itemId: string;
  /**
   * 平台商品的 SKU 号。**绑码要它** —— 条码的真源是 `prd_sku.barcode`，
   * 那是商品域的列，那边不认识 `itemId`。
   *
   * 空 = 这件物料没有平台映射（独立交付形态下的自有主数据），**绑不了码**。
   * 空时不要拿 `itemId` 顶替：两个域的 ID 长得都像编号，冒充了不会有人报错。
   */
  skuNo?: string;
  /** 货品名 */
  name: string;
  /** 规格描述（「10斤装」）。人读的，不参与匹配 */
  specText?: string;
  /** 基本计量单位（个/斤/箱）。**所有数量都以它为准**，收货填别的单位要先换算 */
  baseUom?: string;
  /** 实存 */
  onHand: number;
  /** 预留：别人下了单还没付钱的量 */
  reserved: number;
  /** 可用 = 实存 − 预留。预留是别人下了单还没付钱的量，付了款才真扣 */
  available: number;
  /** 安全库存。低于它算缺货 —— 0 表示不设 */
  safetyStock?: number;
  /** 最后一次动过的时间；滞销判据 */
  lastMovedAt?: string;
  /** SHORTAGE 缺货 · STALE 滞销。**空数组 = 这件没事** */
  flags: string[];
}
/** 某个物料在各库位的分布（`ItemDetailVO`）。 */
export interface StockItemDetail {
  /** 物料号。**进销存自己的编号**，与商城的 skuNo 靠 inv_item_ref 对上 —— 跨库不能外键 */
  itemId: string;
  /** 货品名 */
  name: string;
  /** 规格描述（「10斤装」）。人读的，不参与匹配 */
  specText?: string;
  /** 基本计量单位（个/斤/箱）。**所有数量都以它为准**，收货填别的单位要先换算 */
  baseUom?: string;
  /** 条码。**一个物料可以有多个**（换包装还是同一件货），这里给主条码 */
  barcode?: string;
  /** 商家自己的货号，对接 ERP 用 */
  itemCode?: string;
  /** 实存 */
  onHand: number;
  /** 预留：别人下了单还没付钱的量 */
  reserved: number;
  /** 可用 = 实存 − 预留 */
  available: number;
  /**
   * 安全库存的**默认阈值**（物料上那一级）。低于它算缺货。
   *
   * **`0` 是「不预警」不是「低于 0 才报」** —— 界面上要写成「不预警」，
   * 显示成 0 的话商家会以为是个没设好的数而去改它。
   */
  safetyStock: number;
  /**
   * 在各库位的分布。总数与上面的 onHand 一致，对不上就是有库位没登记。
   *
   * `safetyStock` 是**该库位的覆盖值**，`undefined` = 跟随上面那个默认值。
   * 与「显式设成 0」是两件事：后者是这个库位不预警。
   */
  byLocation: {
    locationId: string;
    locationName: string;
    onHand: number;
    safetyStock?: number;
  }[];
}
/**
 * 跨店总览的一行（`CrossStoreVO`）：**一件货 × 全部库位**。
 *
 * 与 `StockBalance` 的区别是维度 —— 那一条是「一件货在一个库位」，
 * 多门店商家看到的是同一件货重复 N 行；这一条把 N 行收成一行。
 */
export interface StockCrossStoreRow {
  /** 货号，点开一行取分布用的键 */
  itemId: string;
  /** 商品名 */
  name: string;
  /** 规格文案（「500g/袋」这类），同名不同规格靠它分辨 */
  specText?: string;
  /** 基本单位，数量后面跟着显示 */
  baseUom?: string;
  /** 全部库位合计 */
  onHand: number;
  /** 已被订单占用的量（合计）—— 在库但不能再卖 */
  reserved: number;
  /** 可卖量合计 = onHand - reserved。**店主真正该看的是这个数** */
  available: number;
  /**
   * **缺货的库位数**，不是缺货的件数。列表按它降序 ——
   * 五家店断了三家的那件货，比某一家店少两袋更值得先看见。
   */
  shortageLocations: number;
  /** 各库位分布，**随列表一起下发**：点开一行不再发第二次请求 */
  byLocation: {
    locationId: string;
    locationName: string;
    onHand: number;
    safetyStock?: number;
  }[];
}
/** 台账一行（`LedgerVO`）。**不可变** —— 只有查看，没有编辑 */
export interface StockLedgerRow {
  /** 行号。台账不可变，它只用来分页定位 */
  id: number;
  /** 这一行动的是哪件货。**按单查靠它** —— 只给单号的话那一屏是一列没名字的数 */
  itemId: string;
  /** 货品名。台账只存 item_id，名字是 join 出来的 —— 只给单号的话那一屏是一列没名字的数 */
  itemName: string;
  /** IN 入库 / OUT 出库 */
  docKind: StockDocKind;
  /** 单号 */
  docNo: string;
  /** 变动原因码。**盘点差异不为 0 时必填** —— 说不出原因的调整事后查不了账 */
  reasonCode: string;
  /** 本行的变动量。**有符号**：入库为正、出库为负 */
  qtyDelta: number;
  /** 这一行之后的结存。台账靠它自证连续，断一行就能看出来 */
  balanceAfter: number;
  /** 业务发生时刻。**不是落库时刻** —— 补录昨天的进货，这里是昨天 */
  occurredAt: string;
  /** 经手人 */
  operator?: string;
}
/** 台账一页（`LedgerPageVO`）。游标由服务端给，前端不要自己拿最后一行的 id 推 */
export interface StockLedgerPage {
  /** 本页的台账行 */
  entries: StockLedgerRow[];
  /** 下一页游标。**由服务端给**，前端不要自己拿最后一行的 id 推 */
  nextCursor?: number | null;
}
/** 单据中心的一行（`DocumentVO`）。四类单据长得不一样，下发的是它们的交集 */
export interface StockDocument {
  /** IN 入库 · OUT 出库 · COUNT 盘点 · TRANSFER 调拨 */
  kind: string;
  /** 单号 */
  docNo: string;
  /** DRAFT / POSTED / VOIDED，调拨还有 SHIPPED / RECEIVED */
  status: string;
  /**
   * 取值域码，用来查文案：`PURCHASE` / `SCRAP` / `RETURN_SUPPLIER` / `COUNT` / `TRANSFER`……
   *
   * **文案在端上，不在后端。** 此前这些码被后端拼进 `subtitle` 直接下发，
   * 商家看到的是 `SCRAP`；而盘点与调拨那两行是后端硬编码的中文，
   * 阿语商家看到的是中文。两种坏法藏在同一个字段里。
   */
  label?: string;
  /**
   * **只有自由文本**：供应商名、去向名、订单号、库位名。
   *
   * 再往里塞枚举的话，上面那条修的东西下一轮就长回来了。
   */
  subtitle?: string;
  /** 本单合计数量，按明细行汇总 */
  totalQty: number;
  /** 业务发生时刻。**不是落库时刻** —— 补录昨天的进货，这里是昨天 */
  occurredAt: string;
  /** 经手人 */
  operator?: string;
}
/** 进销存月报（`MonthlyVO`）。界面上要能看出 期初 + 进 − 销 − 损 ± 调 = 期末 */
export interface StockMonthly {
  /** 月份（`2026-08`） */
  month: string;
  /** 期初结存 */
  opening: number;
  /** 本月进货 */
  purchased: number;
  /** 本月销售 */
  sold: number;
  /** 本月报损 */
  lost: number;
  /** 本月盘盈盘亏。**有符号** */
  adjusted: number;
  /** 期末结存。界面上要能看出 期初 + 进 − 销 − 损 ± 调 = 期末 */
  closing: number;
  /** 算式对不对得上。**对不上要显眼**，那说明台账漏了一笔 */
  balanced: boolean;
  /**
   * 本月销售出库的成本合计（分）。**按每一笔当时的单位成本累加**，
   * 不是「销量 × 当前成本价」—— 后者在进价波动时会把上个月的账算成今天的价。
   *
   * **这不是毛利。** 毛利 = 收入 − 成本，而收入不在进销存域：
   * 出库单只带成本、不带售价（同一件货不同渠道价不一样，写进来就有了第二个真源）。
   * 要毛利得由知道收入的那一侧拿这个数去减。
   */
  soldCostMinor: number;
  /** 本月报损 + 盘亏的成本合计（分）—— 「这个月亏了多少钱」那个数 */
  lostCostMinor: number;
}
/**
 * 榜单一行（`RankVO`）。
 *
 * @remarks `qty` 两种榜含义不同：动销榜是**销量**，滞销榜是**库存量**（后端取 onHand）。
 *   同一个字段两种意思不是好设计，但那是后端已有的形状 —— 端上照它读，不自己改名。
 */
export interface StockRank {
  /** 物料号。**进销存自己的编号**，与商城的 skuNo 靠 inv_item_ref 对上 —— 跨库不能外键 */
  itemId: string;
  /** 货品名 */
  name: string;
  /** 规格描述（「10斤装」）。人读的，不参与匹配 */
  specText?: string;
  /** 数量。⚠️ **两种榜含义不同**：动销榜是销量，滞销榜是库存量 */
  qty: number;
  /**
   * 金额（分）。**滞销榜不算这个数，会是 `null`** ——
   * 后端没配 `NON_NULL`，null 是照常下发的，所以类型要允许它。
   * 兜底成 0 会让人以为这批货不值钱，而它恰恰是压着钱的那批。
   */
  costAmountMinor?: number | null;
}
/** 库位（`InvLocation`）。**仓是一种库位，不是一种门店** */
export interface StockLocation {
  /** 库位号 */
  locationId: string;
  /** 货品名 */
  name: string;
  /** STORE 门店 · WAREHOUSE 仓 · TRANSIT 在途（系统的，不可删） */
  kind: string;
  /** 门店库位对应的 storeNo */
  externalRef?: string;
  /** 发货源：设了之后这家店下单扣的是源仓的库存。**不允许接力** */
  sourceLocationId?: string;
  /** 默认库位。一个主体**恰好一个** —— 它是「没指定库位时进哪儿」的答案 */
  isDefault?: number;
  /** 状态 */
  status?: string;
}
/** 单据行（`LineReq`）。`unitCostMinor` 只有入库要 */
export interface StockLineReq {
  /** 物料号。**进销存自己的编号**，与商城的 skuNo 靠 inv_item_ref 对上 —— 跨库不能外键 */
  itemId: string;
  /** 数量。⚠️ **两种榜含义不同**：动销榜是销量，滞销榜是库存量 */
  qty: number;
  /** 本行使用的计量单位，与 baseUom 不同时后端换算 */
  uom?: string;
  /** 单位成本（分）。空 = 沿用上一次的进价 */
  unitCostMinor?: number;
}
/** 盘点填数（`StockCountService.Filled`）。差异不为 0 时 `reasonCode` 必填 */
export interface StockCountFilled {
  /** 物料号。**进销存自己的编号**，与商城的 skuNo 靠 inv_item_ref 对上 —— 跨库不能外键 */
  itemId: string;
  /** 实盘数：人数出来多少 */
  countedQty: number;
  /** 变动原因码。**盘点差异不为 0 时必填** —— 说不出原因的调整事后查不了账 */
  reasonCode?: string;
}
/**
 * 一张盘点单（`CountVO`）。**`bookQty` 是开单那一刻的快照** ——
 * 端上不要拿当前余额去顶替它：盘的过程中照常卖，用当前数算差异，
 * 中间卖掉的量会被算成盘亏，而那是一笔凭空出现的损失。
 */
export interface StockCount {
  /** 盘点单号 */
  countNo: string;
  /** COUNTING 进行中 / POSTED 已过账 */
  status: string;
  /** 库位号 */
  locationId?: string;
  /** 开始盘点的时刻 */
  startedAt?: string;
  /** 经手人 */
  operator?: string;
  /** 明细行 */
  lines: StockCountLine[];
}
export interface StockCountLine {
  /** 物料号。**进销存自己的编号**，与商城的 skuNo 靠 inv_item_ref 对上 —— 跨库不能外键 */
  itemId: string;
  /** 货品名 */
  name: string;
  /** 规格描述（「10斤装」）。人读的，不参与匹配 */
  specText?: string;
  /** 基本计量单位（个/斤/箱）。**所有数量都以它为准**，收货填别的单位要先换算 */
  baseUom?: string;
  /** 账面数：系统认为有多少 */
  bookQty: number;
  /** 还没填时是 null，**不是 0** —— 0 的意思是「盘了，一件不差」 */
  countedQty?: number | null;
  /** 差异 = 实盘 − 账面。**正负都要能填**：多了和少了是两种问题 */
  diffQty?: number | null;
  /** 变动原因码。**盘点差异不为 0 时必填** —— 说不出原因的调整事后查不了账 */
  reasonCode?: string;
}
/** 一张调拨单（`TransferVO`）。**草稿态没有行**（行在发出的那张出库单上），不是空单 */
export interface StockTransfer {
  /** 调拨单号 */
  transferNo: string;
  /** DRAFT 草稿 / SHIPPED 已发出 / RECEIVED 已收到 */
  status: string;
  /** 调出库位 */
  fromLocationId?: string;
  /** 调出库位名 */
  fromLocationName?: string;
  /** 调入库位 */
  toLocationId?: string;
  /** 调入库位名 */
  toLocationName?: string;
  /** 发货时刻。空 = 还没发 */
  shippedAt?: string;
  /** 收货时刻。空 = 在途 —— **在途的货两头都不算实存** */
  receivedAt?: string;
  /** 承运方名字快照。空 = 自己送或发货时没记 —— 不是「数据缺失」 */
  carrierName?: string;
  /** 运单号。与 carrierName 一起给收货方核对用 */
  trackingNo?: string;
  /** 本单合计数量，按明细行汇总 */
  totalQty: number;
  /** 明细行 */
  lines: { itemId: string; name: string; specText?: string; qty: number; uom?: string }[];
}
/** 库存台账的方向。IN 入库 / OUT 出库 —— 台账不可变，只有查看没有编辑 */
export type StockDocKind = "IN" | "OUT";
