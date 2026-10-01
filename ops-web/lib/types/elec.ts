// 电子元器件 · 运营端（P-19）。**独立服务** elec-svc 的 /elec/ops/**，不是主系统的 /ops/**。
//
// 逐字对着后端 record 抄：`backend/elec/elec-core/.../dto/{RfqDtos,OpsDtos,MfrDtos,SupplierDtos}.java`。
// 这是**内部面**：真名、电话、精确数量、供应商原价都在这里 —— 买家那一侧（elec-app、packages/shared 的
// 买家类型）一个字段都不许引这里的东西。
//
// 金额一律**百万分之一元**（priceE6）：0402 电阻 ¥0.0015，按分存不下。

/** 询价单状态。EXPIRED 不落库，是报价过了有效期时算出来的 */
export type ElecRfqStatus = "SUBMITTED" | "QUOTED" | "EXPIRED" | "ACCEPTED" | "CLOSED";
/** 关单原因：暂无货源（会通知买家）/ 买家不要了 / 已成交 */
export type ElecCloseReason = "NO_SOURCE" | "BUYER_CANCELLED" | "DONE";
/** 报价模式：MARKUP 加价（买家看平台价、代号 A/B/C）/ FORWARD 转发（买家看原价、匿名编号 S-XXXX） */
export type ElecPriceMode = "MARKUP" | "FORWARD";
/** 供应商状态：暂停后他的货不再给买家看 */
export type ElecSupplierStatus = "ACTIVE" | "SUSPENDED";
/** 库存筛选：全部 / 7 天内到期 / 已到期 */
export type ElecStockFilter = "ALL" | "EXPIRING" | "EXPIRED";
/** 派单结果：未看 / 看了没回 / 报了价 / 拒了 */
export type ElecDispatchStatus = "SENT" | "VIEWED" | "QUOTED" | "DECLINED";
/** 供应商报价状态。EXPIRED 是 ACTIVE 过了有效期算出来的；NOT_CHOSEN = 这一行成交给了别家 */
export type ElecOpsQuoteStatus = "ACTIVE" | "WITHDRAWN" | "ACCEPTED" | "NOT_CHOSEN" | "EXPIRED";

// ── 询报价 ──────────────────────────────────────────────────────────────

/** 平台对这一行的报价（运营录入的，已经是对买家的价） */
export interface ElecOpsLineQuote {
  /** 含税单价，百万分之一元 */
  priceE6: number;
  /** 能供多少；空 = 按要的数量 */
  qty?: number | null;
  /** 批次年份 */
  dcYear?: number | null;
  /** 交期天数，0 = 现货 */
  leadDays?: number | null;
  /** 货况 */
  cond?: string | null;
  /** 包装 */
  packing?: string | null;
  /** 这一行给买家的说明 */
  note?: string | null;
}

/** 阶梯价的一档 */
export interface ElecPriceTier {
  /** 从多少片起 */
  minQty: number;
  /** 这一档单价，百万分之一元 */
  priceE6: number;
}

/** 库里谁有这个料号的货。运营照着它报价，所以口径要全（供应商原样：原币种、原含税口径） */
export interface ElecOpsSource {
  /** 供应商号 */
  supplierNo: string;
  /** 公司名（真名） */
  companyName?: string | null;
  /** 联系电话 */
  contactPhone?: string | null;
  /** 库存行号 */
  stockNo: string;
  /** 精确数量 */
  qty: number;
  /** 批号原样 */
  dateCode?: string | null;
  /** 起订量 */
  moq?: number | null;
  /** 最小包装量 */
  spq?: number | null;
  /** 阶梯价，按数量升序 */
  tiers: ElecPriceTier[];
  /** 最低档单价 */
  priceE6?: number | null;
  /** 币种 */
  currency?: string | null;
  /** 含不含税 */
  taxIncluded: boolean;
  /** 包装 */
  packing?: string | null;
  /** 货况 */
  cond?: string | null;
  /** 交期天数 */
  leadDays?: number | null;
  /** 货源地 */
  region?: string | null;
  /** 这行库存有效到哪天。快到期的价要打折扣看 */
  validUntil?: string | null;
}

/** 这一行派给了谁、各自回了什么。**真名、原价、备注** —— 买家看到的是代号与加价后的价 */
export interface ElecOpsOffer {
  /** 派单号 */
  dispatchNo: string;
  /** 供应商号 */
  supplierNo: string;
  /** 公司名 */
  companyName?: string | null;
  /** 联系电话 */
  contactPhone?: string | null;
  /** AUTO_MATCH 库里有货自动派 / OPS 运营手工指派 / OPEN 供应商在求购大厅自己认领 */
  via: string;
  /** 派单结果 */
  dispatchStatus: ElecDispatchStatus;
  /** 拒绝原因：NO_STOCK / PRICE / OTHER */
  declineReason?: string | null;
  /** 通知送到的时间 */
  notifiedAt?: string | null;
  /** 回话的时间 */
  respondedAt?: string | null;
  /** 报了价才有 */
  quoteNo?: string | null;
  /** 供应商填的单价（他的币种与含税口径，没加价） */
  priceE6?: number | null;
  /** 币种 */
  currency?: string | null;
  /** 含不含税 */
  taxIncluded?: boolean | null;
  /** 买家看到的价（换成人民币含税、加价之后） */
  buyerPriceE6?: number | null;
  /** 能供多少 */
  qtyAvailable?: number | null;
  /** 批号 */
  dateCode?: string | null;
  /** 交期天数 */
  leadDays?: number | null;
  /** 货况 */
  cond?: string | null;
  /** 包装 */
  packing?: string | null;
  /** 起订量 */
  moq?: number | null;
  /** 报价有效到哪天 */
  validUntil?: string | null;
  /** 供应商写给平台的备注 —— 买家永远看不到 */
  remark?: string | null;
  /** 报价状态 */
  quoteStatus?: ElecOpsQuoteStatus | null;
}

/** 询价单的一行 */
export interface ElecOpsLine {
  /** 行号 */
  lineNo: number;
  /** 认到的料号；认不出为空 */
  partNo?: string | null;
  /** 买家写的料号原样 */
  mpn: string;
  /** 买家写的厂牌 */
  mfr?: string | null;
  /** 买家从厂牌列表里选的编码（ai-hxkey V6 起）；空 = 没选或自己写的、平台按原文找 */
  mfrCode?: string | null;
  /** 要几片 */
  qty: number;
  /** 目标单价，百万分之一元 */
  targetE6?: number | null;
  /** 平台的报价 */
  quote?: ElecOpsLineQuote | null;
  /** 库里谁有货。**列表页不带，详情才带** */
  sources?: ElecOpsSource[] | null;
  /** 派给了谁、各自回了什么。**列表页不带，详情才带** */
  offers?: ElecOpsOffer[] | null;
  /** 公开成求购的时刻（库里没人有货，自动派单一家都没派出去）；空 = 派出去了 */
  publicAt?: string | null;
}

/** 运营看到的询价单：买家的完整联系方式、每行库里谁有货、每家报了什么 */
export interface ElecOpsRfq {
  /** 询价单号 */
  rfqNo: string;
  /** 状态 */
  status: ElecRfqStatus;
  /** 提交时间 */
  createdAt: string;
  /** 几行 */
  lineCnt: number;
  /** 联系人 */
  contactName?: string | null;
  /** 联系电话（完整） */
  contactPhone?: string | null;
  /** 公司 */
  company?: string | null;
  /** 发票要求：NONE / VAT_NORMAL / VAT_SPECIAL */
  needInvoice: string;
  /** 批次要求：ANY / Y1 / Y2 */
  dcReq: string;
  /** 货况要求 */
  condReq?: string | null;
  /** 包装要求 */
  packingReq?: string | null;
  /** 几天内要到货 */
  needByDays?: number | null;
  /** 能不能用替代型号 */
  allowAlt: boolean;
  /** 收货城市 */
  deliverCity?: string | null;
  /** 备注 */
  remark?: string | null;
  /** 平台报价的时间 */
  quotedAt?: string | null;
  /** 谁报的价 */
  quotedBy?: string | null;
  /** 报价有效到哪天 */
  quoteValidUntil?: string | null;
  /** 给买家的说明 */
  quoteNote?: string | null;
  /** 结果通知送达买家了没有 */
  buyerNotified: boolean;
  /** 关单原因 */
  closeReason?: ElecCloseReason | null;
  /** 派给了几家（去重） */
  dispatchCnt: number;
  /** 其中几家回了话。与派出去的差得多 = 该催了 */
  respondedCnt: number;
  /** 几家报了还有效的价 */
  offerCnt: number;
  /** 逐行 */
  lines: ElecOpsLine[];
  /** 这一单的报价模式；老后端不带时按加价 */
  priceMode?: ElecPriceMode;
}

/** 录入平台报价的一行。没列进来的行 = 没找到货 */
export interface ElecQuoteLineReq {
  /** 行号 */
  lineNo: number;
  /** 含税单价，百万分之一元（必填） */
  priceE6: number;
  /** 能供多少 */
  qty?: number;
  /** 批次年份 */
  dcYear?: number;
  /** 交期天数 */
  leadDays?: number;
  /** 这一行的说明 */
  note?: string;
}

/** 录入平台报价 */
export interface ElecQuoteReq {
  /** 报价有效几天（默认 3） */
  validDays?: number;
  /** 给买家的说明（≤255 字） */
  note?: string;
  /** 报了价的行 */
  lines: ElecQuoteLineReq[];
}

/** 报价记录的一行（全部供应商报价，按时间倒序） */
export interface ElecOpsQuoteRow {
  /** 报价号 */
  quoteNo: string;
  /** 询价单号 */
  rfqNo: string;
  /** 行号 */
  lineNo: number;
  /** 料号 */
  mpn: string;
  /** 买家要几片 */
  qtyWanted: number;
  /** 供应商号 */
  supplierNo: string;
  /** 公司名 */
  companyName?: string | null;
  /** 供应商原价 */
  priceE6?: number | null;
  /** 币种 */
  currency?: string | null;
  /** 含不含税 */
  taxIncluded: boolean;
  /** 买家看到的价 */
  buyerPriceE6?: number | null;
  /** 能供多少 */
  qtyAvailable: number;
  /** 交期天数 */
  leadDays?: number | null;
  /** 有效到哪天 */
  validUntil?: string | null;
  /** 状态 */
  status: ElecOpsQuoteStatus;
  /** 报价时间 */
  createdAt: string;
}

// ── 供应商 ──────────────────────────────────────────────────────────────

/** 供应商列表的一行 */
export interface ElecOpsSupplierRow {
  /** 供应商号 */
  supplierNo: string;
  /** 公司名；点一下就成为供应商，所以可能还没填 */
  companyName?: string | null;
  /** 类型：AGENT / TRADER / FACTORY / OTHER */
  kind: string;
  /** 城市 */
  city?: string | null;
  /** 联系人 */
  contactName?: string | null;
  /** 联系电话 */
  contactPhone?: string | null;
  /** 匿名代号 */
  maskCode: string;
  /** 状态 */
  status: ElecSupplierStatus;
  /** 在售且未到期的库存行数 */
  onCount: number;
  /** 其中 7 天内到期的 */
  expiringCount: number;
  /** 最近一次确认上架 */
  lastUploadAt?: string | null;
  /** 入驻时间 */
  createdAt: string;
}

/** 近 30 天派单响应。分母是「看到的」，没看到的不怪他 */
export interface ElecDispatchStats {
  /** 统计窗口（天） */
  days: number;
  /** 派了几条 */
  sent: number;
  /** 其中看过的 */
  viewed: number;
  /** 其中回了话的 */
  responded: number;
  /** 其中报了价的 */
  quoted: number;
  /** 报价被买家选中的 */
  accepted: number;
}

/** 供应商详情 */
export interface ElecOpsSupplierDetail {
  /** 供应商号 */
  supplierNo: string;
  /** 公司名 */
  companyName?: string | null;
  /** 类型 */
  kind: string;
  /** 城市 */
  city?: string | null;
  /** 详细地址（供应商入驻/资料页填，比城市细） */
  address?: string | null;
  /** 联系人 */
  contactName?: string | null;
  /** 联系电话 */
  contactPhone?: string | null;
  /** 匿名代号 */
  maskCode: string;
  /** 状态 */
  status: ElecSupplierStatus;
  /** 最近一次暂停的理由（恢复后保留） */
  suspendReason?: string | null;
  /** 最近一次暂停的时间 */
  suspendedAt?: string | null;
  /** 在售且未到期 */
  onCount: number;
  /** 7 天内到期 */
  expiringCount: number;
  /** 在售但已过期（买家看不到） */
  expiredCount: number;
  /** 最近一次确认上架 */
  lastUploadAt?: string | null;
  /** 入驻通知送到企业微信了没有 */
  registerNotified: boolean;
  /** 入驻时间 */
  createdAt: string;
  /** 近 30 天派单响应 */
  dispatch: ElecDispatchStats;
}

/** 改资料（空字段 = 不改） */
export interface ElecSupplierReq {
  /** 公司名 */
  companyName?: string;
  /** 类型 */
  kind?: string;
  /** 城市 */
  city?: string;
  /** 详细地址 */
  address?: string;
  /** 联系人 */
  contactName?: string;
  /** 联系电话 */
  contactPhone?: string;
}

/** 一行库存（供应商原样） */
export interface ElecStockView {
  /** 库存行号 */
  stockNo: string;
  /** 料号原样 */
  mpn: string;
  /** 厂牌原样 */
  mfr?: string | null;
  /** 精确数量 */
  qty: number;
  /** 批号 */
  dateCode?: string | null;
  /** 封装 */
  packageName?: string | null;
  /** 起订量 */
  moq?: number | null;
  /** 最小包装量 */
  spq?: number | null;
  /** 阶梯价 */
  tiers: ElecPriceTier[];
  /** 最低档单价 */
  priceE6?: number | null;
  /** 币种 */
  currency?: string | null;
  /** 含不含税 */
  taxIncluded: boolean;
  /** 包装 */
  packing?: string | null;
  /** 货况 */
  cond?: string | null;
  /** 交期天数 */
  leadDays?: number | null;
  /** 货源地 */
  region?: string | null;
  /** 到期日 */
  validUntil: string;
  /** ON / EXPIRED */
  status: string;
}

// ── 料号与库存 ──────────────────────────────────────────────────────────

/** 运营搜料号的一行（与买家同一套命中，但不计入搜索需求） */
export interface ElecOpsPartRow {
  /** 料号 */
  partNo: string;
  /** 料号原样 */
  mpn: string;
  /** 厂牌代码；UNKNOWN = 认不出 */
  mfrCode: string;
  /** 厂牌名 */
  mfrName?: string | null;
  /** 厂牌认不出时第一次上传写的原文 */
  mfrNameRaw?: string | null;
  /** 封装 */
  pkg?: string | null;
  /** ACTIVE / PENDING / MERGED */
  status: string;
  /** EXACT / PREFIX / CONTAINS */
  match?: string | null;
  /** 几家有在售且未到期的库存（精确家数） */
  supplierCnt: number;
  /** 合计数量（精确） */
  totalQty: number;
  /** 买家看到的起价；没人报价为空 */
  buyerPriceFromE6?: number | null;
}

/** 料号详情：运营报价时最常看的一屏 */
export interface ElecOpsPartDetail {
  /** 料号 */
  part: ElecOpsPartRow;
  /** 描述 */
  description?: string | null;
  /** 买家看到的数量档 */
  qtyBand?: string | null;
  /** 买家看到的家数档 */
  sourceBand?: string | null;
  /** 谁有货：按数量倒序，最多 50 家 */
  sources: ElecOpsSource[];
}

/** 库存行查询的一行 */
export interface ElecOpsStockRow {
  /** 供应商号 */
  supplierNo: string;
  /** 公司名 */
  companyName?: string | null;
  /** 供应商状态 */
  supplierStatus: ElecSupplierStatus;
  /** 料号 */
  partNo?: string | null;
  /** 库存本身 */
  stock: ElecStockView;
}

// ── 基础数据 ────────────────────────────────────────────────────────────

/** 厂牌 */
export interface ElecMfrRow {
  /** 厂牌代码（建了不能改） */
  mfrCode: string;
  /** 英文名 */
  nameEn: string;
  /** 中文名 */
  nameCn?: string | null;
  /** ACTIVE / MERGED */
  status: string;
  /** 并入了哪家 */
  mergedInto?: string | null;
  /** 有几种写法指向它 */
  aliasCnt: number;
  /** 挂在它名下的料号数 */
  partCnt: number;
}

/** 加厂牌 / 改名（改名时 mfrCode 以路径为准） */
export interface ElecMfrReq {
  /** 厂牌代码，大写字母数字 2–32 位 */
  mfrCode?: string;
  /** 英文名（必填） */
  nameEn: string;
  /** 中文名 */
  nameCn?: string;
}

/** 一条别名 */
export interface ElecAliasRow {
  /** 规范化后的写法 */
  aliasNorm: string;
  /** 指向的厂牌 */
  mfrCode: string;
  /** SEED 初始种子 / OPS 运营加的 */
  source: string;
  /** 加的时间 */
  createdAt?: string | null;
  /** 谁加的 */
  createdBy?: string | null;
}

/** 加别名的结果：加别名会当场改认既有库存 */
export interface ElecAliasResult {
  /** 规范化后的写法 */
  aliasNorm: string;
  /** 指向的厂牌 */
  mfrCode: string;
  /** 从「厂牌不明」改认到这家的库存行数 */
  movedRows: number;
  /** 受影响的料号数 */
  touchedParts: number;
}

/** 表头写法看哪一份：GLOBAL 全局（种子 + 运营加的）/ LEARNED 各家供应商确认过的（按写法聚合） */
export type ElecHeaderAliasScope = "GLOBAL" | "LEARNED";

/**
 * 库存表的一个表头写法认成哪个字段（elec-svc `HeaderAliasRow`）。
 * 全局的改了当场生效；学到的是各家自己上传时确认过的，提升后对所有供应商生效。
 */
export interface ElecHeaderAliasRow {
  /** 全局写法的 id（改字段、停用用它）；学到的按写法聚合，没有 id */
  id?: number | null;
  /** 规范化后的写法（去空白标点、大写） */
  aliasNorm: string;
  /** 原文 */
  aliasRaw: string;
  /** 认成的字段：MPN MFR QTY DC PACKAGE PRICE MOQ SPQ PACKING CONDITION CURRENCY LEAD REGION（elec-svc Columns.Field） */
  field: string;
  /** SEED 种子 / OPS 运营加的 / LEARNED 各家学到的 */
  source: string;
  /** ACTIVE / DISABLED */
  status: string;
  /** 学到的：几家在用（据此决定要不要提升）；全局的为 0 */
  supplierCount: number;
  /** 全局的：最后一次改动；学到的：最近一次有供应商这么写 */
  updatedAt?: string | null;
}

/** 认不出的厂牌写法（按规范化后的写法聚合） */
export interface ElecUnknownMfrRow {
  /** 规范化后的写法 */
  aliasNorm: string;
  /** 出现最多的那个原文 */
  sample: string;
  /** 挂着这种写法的在售库存行数 */
  rowCnt: number;
  /** 几家这么写 */
  supplierCnt: number;
  /** 涉及几个料号 */
  partCnt: number;
  /** 建议的厂牌；没把握为空 */
  suggestCode?: string | null;
  /** 建议厂牌的名字 */
  suggestName?: string | null;
  /** 买家询价里这么写、又没从列表选编码的行数（ai-hxkey 询价厂牌选择 §8） */
  rfqLineCnt: number;
  /** 几个买家这么写 */
  buyerCnt: number;
  /** 最近一张这么写的询价单；只在库存里出现过为空 */
  sampleRfqNo?: string | null;
}
