// 电子元器件：料号、供应商库存、询价
//
// **逐字对着后端的 record 抄**，不是照界面拟的（进销存那一轮的教训：照自拟的形状接，
// 两边自洽、mock 自查全过，而真接口一个都调不通）。
// 对照：`backend/elec/elec-core/.../dto/{PartDtos,SupplierDtos,RfqDtos}.java`。
//
// ⚠️ 元器件是**独立服务**（elec-svc），路径前缀 `/elec/c/**`（买家）与 `/elec/b/**`（供应商），
// 不是 `/mp/**`。同域经 nginx 转发，端上除了路径不同，其余与主系统的接口一样。

// ── 料号与行情 ──────────────────────────────────────────────────────────

/**
 * 买家看到的库存档位。**有损是故意的**：精确数量加上批号，同行一眼就能认出是谁家的货，
 * 而供应商愿意把库存传上来的前提正是这个。
 */
export type ElecQtyBand = "B1" | "B100" | "B1K" | "B10K" | "B100K" | "B1M";

/** 有几家有货：1 家 / 2–4 家 / 5 家及以上。端上只说「1 家有货」「多家有货」 */
export type ElecSourceBand = "ONE" | "FEW" | "MANY";

/** 怎么命中的。端上把它显示成一句人话（开头一致 / 中段一致 / 相近），不是排名分数 */
export type ElecMatch = "EXACT" | "PREFIX" | "CONTAINS" | "NEAR";

/** 买家面的库存行情。**没有、也不许加任何供应商字段** */
export interface ElecMarket {
  qtyBand: ElecQtyBand;
  sourceBand: ElecSourceBand;
  /** 含税参考起价，**百万分之一元**（0402 电阻单价 ¥0.0015，按分存做不出来）。空 = 有货但都没报价 */
  priceFromE6?: number | null;
  /** 最新批次年份。**只给年份** —— 精确批号能认出是谁家的货 */
  dcYearMax?: number | null;
}

export interface ElecPartHit {
  partNo: string;
  /** 原样展示的料号 */
  mpn: string;
  /** 厂牌名；厂牌未确认时是供应商写的原文，可能为空 */
  mfrName?: string | null;
  /** 厂牌是否已确认。false 时端上写「厂牌未确认」 */
  mfrKnown: boolean;
  packageName?: string | null;
  description?: string | null;
  /** 行情；**空 = 平台库里现在没货**（照样可以询价，那正是询价最有价值的时候） */
  market?: ElecMarket | null;
  match: ElecMatch;
}

export interface ElecSearchResult {
  /** 实际拿去搜的料号（规范化之后） */
  keyword: string;
  /** 从输入里认出的厂牌（「TI TPS5433」→ 德州仪器）；没认出为空 */
  mfrFilter?: string | null;
  /**
   * 结果是**相近**的：原词一条都没命中，退到这个更短的前缀才有结果。
   * 空 = 结果就是原词命中的。端上据此写「没有找到 X，以下是相近的料号」
   */
  nearFrom?: string | null;
  hits: ElecPartHit[];
}

/**
 * 批量查的一行。
 *
 * @see ElecLookupMatch
 */
export interface ElecLookupLine {
  /** 原样一行 */
  input: string;
  /** 认出的料号原文；空 = 这一行没有像料号的词 */
  mpn?: string | null;
  /** 同一行里写的数量（「STM32F103C8T6 2000」）；没写为空 */
  qty?: number | null;
  match: ElecLookupMatch;
  /** 最像的那一条（有货的优先）；NONE 时没有 */
  hit?: ElecPartHit | null;
  /** 除 hit 以外还有几条候选 */
  others: number;
}

/**
 * AMBIGUOUS = 同一料号多家厂牌、他又没写厂牌。**不猜**：猜错了报价整单作废，
 * 而他要到收货那天才知道。
 */
export type ElecLookupMatch = "EXACT" | "AMBIGUOUS" | "PREFIX" | "NONE";

// ── 询价 ────────────────────────────────────────────────────────────────

/** NONE 不开票 / VAT_NORMAL 普票 / VAT_SPECIAL 专票 */
export type ElecInvoice = "NONE" | "VAT_NORMAL" | "VAT_SPECIAL";

/** ANY 不限 / Y1 一年内 / Y2 两年内 */
export type ElecDcReq = "ANY" | "Y1" | "Y2";

/**
 * SUBMITTED 待报价 / QUOTED 已报价 / EXPIRED 报价已过期 / ACCEPTED 已接受 / CLOSED 已结束。
 *
 * **EXPIRED 不落库**：QUOTED 且过了有效期时后端算出来的，端上照原样显示。
 */
export type ElecRfqStatus = "SUBMITTED" | "QUOTED" | "EXPIRED" | "ACCEPTED" | "CLOSED";

/** NO_SOURCE 暂无货源（会通知买家）/ BUYER_CANCELLED 买家不要了 / DONE 已成交 */
export type ElecCloseReason = "NO_SOURCE" | "BUYER_CANCELLED" | "DONE";

export interface ElecRfqLineReq {
  /** 从料号详情进来时带上；手输的料号可以没有 */
  partNo?: string;
  mpn: string;
  mfr?: string;
  qty: number;
  /** 目标单价，百万分之一元 */
  targetE6?: number;
}

export interface ElecRfqReq {
  lines: ElecRfqLineReq[];
  needInvoice?: ElecInvoice;
  dcReq?: ElecDcReq;
  deliverCity?: string;
  company?: string;
  contactName?: string;
  remark?: string;
}

/** 平台对这一行的报价。空 = 还没报，或报价时这一行没找到货 */
export interface ElecLineQuote {
  /** 含税单价，百万分之一元 */
  priceE6: number;
  qty?: number | null;
  dcYear?: number | null;
  /** 交期天数，0 = 现货 */
  leadDays?: number | null;
  note?: string | null;
}

export interface ElecRfqLine {
  lineNo: number;
  partNo?: string | null;
  mpn: string;
  mfr?: string | null;
  qty: number;
  targetE6?: number | null;
  quote?: ElecLineQuote | null;
}

export interface ElecRfq {
  rfqNo: string;
  status: ElecRfqStatus;
  createdAt: string;
  lineCnt: number;
  needInvoice: ElecInvoice;
  dcReq: ElecDcReq;
  deliverCity?: string | null;
  company?: string | null;
  contactName?: string | null;
  /** 提交时绑定的手机号，**掩码** —— 让他知道平台会打哪个号 */
  contactPhone: string;
  remark?: string | null;
  quotedAt?: string | null;
  /** 报价有效到哪天（含）。过了就不能再接受 —— 行情会变，过期的价平台不兑现 */
  quoteValidUntil?: string | null;
  /** 平台给买家的说明 */
  quoteNote?: string | null;
  closeReason?: ElecCloseReason | null;
  lines: ElecRfqLine[];
}

// ── 供应商（与买家同一个账号）──────────────────────────────────────────

/** AGENT 代理商 / TRADER 贸易商 / FACTORY 工厂余料 / OTHER 其他 */
export type ElecSupplierKind = "AGENT" | "TRADER" | "FACTORY" | "OTHER";

export interface ElecSupplierReq {
  companyName?: string;
  kind?: ElecSupplierKind;
  city?: string;
  contactName?: string;
  /** 联系手机可以与登录号不同：老板用自己的号登录，接电话的是业务员 */
  contactPhone?: string;
}

export interface ElecSupplier {
  supplierNo: string;
  /** **点一下就成为供应商**，所以公司名可以还没填 */
  companyName?: string | null;
  kind: ElecSupplierKind;
  city?: string | null;
  contactName?: string | null;
  contactPhone: string;
  /** 匿名代号 `S-3F7K`。以后开放身份时买家看到的就是它 */
  maskCode: string;
  status: "ACTIVE" | "SUSPENDED";
  /** 在售且未到期的库存行数 */
  onCount: number;
  /** 7 天内到期的在售行数 */
  expiringCount: number;
  /** 最近一次确认上架的时间；从没传过为空 */
  lastUploadAt?: string | null;
  /** 库存多少天不更新就不再给买家看 —— 端上用它解释「为什么要定期传」 */
  stockTtlDays: number;
}

/** ON 在售 / EXPIRED 到期（端上据此提示续期） */
export type ElecStockStatus = "ON" | "EXPIRED";

export interface ElecStock {
  stockNo: string;
  mpn: string;
  mfr?: string | null;
  /** 供应商自己看得到精确数量 */
  qty: number;
  /** 原样批号（2338 / 23+ / 24/25） */
  dateCode?: string | null;
  packageName?: string | null;
  moq?: number | null;
  /** 单价，百万分之一元。空 = 没报价 */
  priceE6?: number | null;
  taxIncluded: boolean;
  /** 到期日（含）。到了就不再给买家看 */
  validUntil: string;
  status: ElecStockStatus;
}

/** MERGE 只改表里有的行 / REPLACE 表里没有的下架 */
export type ElecImportMode = "MERGE" | "REPLACE";

/**
 * 认不了的行。
 * MPN_MISSING 没有料号 / MPN_INVALID 不像料号 / QTY_INVALID 数量认不出 / DUPLICATE 与前面的行重复
 */
export interface ElecRowProblem {
  /** 表里的行号，与 Excel 左边的行号一致 */
  row: number;
  reason: "MPN_MISSING" | "MPN_INVALID" | "QTY_INVALID" | "DUPLICATE";
  mpn?: string | null;
}

/**
 * 上传预览。**预览时一行库存都没动**，确认之后才上架。
 */
export interface ElecBatchPreview {
  batchNo: string;
  fileName?: string | null;
  mode: ElecImportMode;
  taxIncluded: boolean;
  /** 表头那一行（原样），端上用它画「这几列分别是什么」 */
  headers: string[];
  /** 字段 → 列序号（从 0 起）：MPN / MFR / QTY / DC / PACKAGE / PRICE / MOQ */
  columns: Record<string, number>;
  rowTotal: number;
  rowValid: number;
  rowInvalid: number;
  toInsert: number;
  toUpdate: number;
  /** 全量替换时将下架的行数；增量上传恒为 0 */
  toDelist: number;
  unchanged: number;
  /** 认不了的行（最多 50 条） */
  problems: ElecRowProblem[];
  /** 将下架的料号，最多 20 个 —— 让他一眼看出「这不对，表只传了半截」 */
  delistSample: string[];
  status: "PARSED" | "APPLIED";
}

export interface ElecRenewResult {
  renewed: number;
  validUntil: string;
}
