// 履约调度域（矩阵 P-5.1）。平台侧只做「调度与监控」，实际核销在 B 端核销台（B-10.2）。
import type { Archivable } from "./common";
export type BatchStatus = "PLANNED" | "DISPATCHED" | "ARRIVED" | "SIGNED";

/** 有序推进，不允许跳步：没到货就签收、没签收就分拣，都会让责任判定失去依据。 */
export const BATCH_TRANSITIONS: Record<BatchStatus, BatchStatus[]> = {
  PLANNED: ["DISPATCHED"],
  DISPATCHED: ["ARRIVED"],
  ARRIVED: ["SIGNED"],
  SIGNED: [],
};

/** 到货批次与配车（P-5.1.1）。 */
export interface ArrivalBatch {
  /** 批次单号 */
  batchNo: string;
  /** 批次状态。**有序推进不允许跳步**，见 `BATCH_TRANSITIONS` */
  status: BatchStatus;
  /** 目的社区 */
  communityNo: string;
  /** 社区名快照 */
  communityName: string;
  /** 目的自提点 */
  pickupNo: string;
  /** 自提点名称快照 */
  pickupName: string;
  /** 计划到货时间 */
  planArriveAt: string;
  /** 车次/司机标识；一期人肉填，二期接运力系统 */
  vehicle: string;
  /** 本批件数 */
  itemCount: number;
  /** 涉及的商家数（跨商家拆单后，一个批次会混装多家的货） */
  merchantCount: number;
}

/** 按自提点汇总的分拣行（P-5.1.2）。只列**已签收**批次的货。 */
export interface SortingRow {
  /** 自提点单号 */
  pickupNo: string;
  /** 自提点名称 */
  pickupName: string;
  /** SKU 单号 */
  skuNo: string;
  /** 商品标题 */
  title: string;
  /** 供货商家名。一个批次会混装多家的货 */
  merchantName: string;
  /** 应分拣数量 */
  qty: number;
  /** 缺货标记回传（P-5.1.2 / B-10.3.4）：自提点上报的缺件数 */
  shortQty: number;
}

/** 核销监控（P-5.1.3）：一行 = 一个自提点当日的履约健康度。 */
export interface RedeemStat {
  /** 自提点单号 */
  pickupNo: string;
  /** 自提点名称 */
  pickupName: string;
  /** 所属社区名 */
  communityName: string;
  /** 待核销单数（已到货、还没人来取） */
  pending: number;
  /** 已核销单数 */
  redeemed: number;
  /** 逾期未取单数 */
  overdue: number;
  /** 已核销 /（已核销 + 待核销 + 逾期），0–1 */
  rate: number;
}

/** 逾期处置方式（P-5.1.4）。 */
export type OverdueAction = "POSTPONE" | "VOID";

export interface OverdueRule {
  /** 逾期处置方式：顺延 or 作废 */
  action: OverdueAction;
  /**
   * 宽限小时数。**到点即作废会直接产生客诉**，所以 VOID 也必须留宽限期（≥1）。
   * 校验在 mock/后端两侧都有，不只是表单提示。
   */
  graceHours: number;
  /** 顺延次数上限（action=POSTPONE 时有意义） */
  maxPostpone: number;
  /** 最后修改时间 */
  updatedAt: string;
  /** 最后修改人（STAFF 账号） */
  updatedBy: string;
}

// ── 快递与轨迹（P-5.2.1 / 5.2.2）────────────────────────────────────

/** 承运商。一期只接这三家，改枚举比改结构便宜。 */
export type Carrier = "SF" | "JD" | "YTO";

/**
 * 快递状态。
 *
 * `EXCEPTION` 不是终态：快递可能"疑难件"之后又派送成功。把它做成终态，
 * 运营就得手工把单子拉回来，而那本该是承运商回传的事。
 */
export type ShipmentStatus = "CREATED" | "PICKED_UP" | "IN_TRANSIT" | "DELIVERED" | "EXCEPTION";

export interface ShipmentTrace {
  /** 轨迹时间 */
  at: string;
  /** 轨迹节点描述，原样来自承运商 */
  text: string;
  /** 所在城市/网点 */
  location?: string;
}

export interface Shipment {
  /** 运单记录单号（平台侧主键，不是快递单号） */
  shipmentNo: string;
  /** 关联的子订单 */
  orderNo: string;
  /** 承运商 */
  carrier: Carrier;
  /** 承运商的快递单号 */
  waybillNo: string;
  /** 快递状态。**`EXCEPTION` 不是终态**，疑难件可能之后又派送成功 */
  status: ShipmentStatus;
  /** 收件人姓名 */
  receiver: string;
  /** 收件地区（省/市），超区判断看的就是它 */
  region: string;
  /** 建单时间 */
  createdAt: string;
  /** 最后一次轨迹更新时间 */
  updatedAt: string;
  /** 轨迹节点，按时间正序 */
  traces: ShipmentTrace[];
}

// ── 运费模板与超区（P-5.2.3）────────────────────────────────────────

/**
 * 运费模板。
 *
 * 重量一律用**克**、金额一律用**分** —— 两个都是整数，避免 0.1kg + 0.2kg 这类浮点误差
 * 在算钱的地方冒出来。
 */
export interface FreightTemplate extends Archivable {
  /** 模板单号 */
  templateNo: string;
  /** 模板名 */
  name: string;
  /** 首重（克） */
  firstWeightGram: number;
  /** 首重费（分） */
  firstFee: number;
  /** 续重单位（克） */
  addWeightGram: number;
  /** 每个续重单位的费用（分） */
  addFee: number;
  /** 满多少分免邮；0 = 不免邮 */
  freeThreshold: number;
  /** 默认模板不可删：删掉之后新商家没有模板可用 */
  isDefault: boolean;
  /** 超区规则 */
  outOfRange: OutOfRangeRule[];
  /** 最后修改时间 */
  updatedAt: string;
  /** 最后修改人（STAFF 账号） */
  updatedBy: string;
}

/** 超区处置：不配送，或加价配送。 */
export type OutOfRangeAction = "REJECT" | "SURCHARGE";

export interface OutOfRangeRule {
  /** 省或直辖市名 */
  region: string;
  /** 处置方式：不配送 or 加价配送 */
  action: OutOfRangeAction;
  /** 加价金额（分）。REJECT 时必须为 0 —— 不配送就没有"加多少钱"这回事 */
  surcharge: number;
}

// ── 第三方运力配置（P-5.2.4）──────────────────────────────────────

/**
 * 一家承运商的接入配置。
 *
 * ⚠️ 这一页配错的后果不是"显示不对"，而是**订单发不出去**：
 * 全停、启用没配密钥的、或者停掉还有在途单的那家，都会让快递链路当场断掉。
 * 所以规则全部落在 mock 层，页面写不出违规配置。
 */
export interface CarrierConfig {
  /** 承运商标识 */
  carrier: Carrier;
  /** 展示名 */
  name: string;
  /** 是否启用。**不能全停，也不能停掉还有在途单的那家** —— 会让快递链路当场断掉 */
  enabled: boolean;
  /**
   * 优先级，数字越小越优先。
   * **不允许重复** —— 同优先级时选哪家取决于数组顺序，那是隐性行为。
   */
  priority: number;
  /** 接入账号，展示一律脱敏 */
  accountMasked: string;
  /**
   * 密钥是否已配置。
   * 只存布尔而**不存密钥本身** —— 密钥不该出现在前端契约里，哪怕是脱敏的。
   */
  apiKeyConfigured: boolean;
  /** 每日截单时间 HH:mm，过点的单顺延到次日 */
  pickupCutoff: string;
  /** 承诺时效（小时） */
  slaHours: number;
  /** 最后修改时间 */
  updatedAt: string;
  /** 最后修改人（STAFF 账号） */
  updatedBy: string;
}
