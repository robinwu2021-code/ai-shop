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
  /** 数量档位：B1K = 1000 片以上。端上显示成「1k+」，**不是精确库存** */
  qtyBand: ElecQtyBand;
  /** 有几家有货的档位：ONE 一家 / FEW 2–4 家 / MANY 5 家以上。端上只说「1 家」「多家」 */
  sourceBand: ElecSourceBand;
  /** 含税参考起价，**百万分之一元**（0402 电阻单价 ¥0.0015，按分存做不出来）。空 = 有货但都没报价 */
  priceFromE6?: number | null;
  /** 最新批次年份。**只给年份** —— 精确批号能认出是谁家的货 */
  dcYearMax?: number | null;
}

export interface ElecPartHit {
  /** 料号在本域的业务键。询价、看详情都带它 */
  partNo: string;
  /** 原样展示的料号 */
  mpn: string;
  /** 厂牌名；厂牌未确认时是供应商写的原文，可能为空 */
  mfrName?: string | null;
  /** 厂牌是否已确认。false 时端上写「厂牌未确认」 */
  mfrKnown: boolean;
  /** 封装，如 LQFP-48 / 0402 */
  packageName?: string | null;
  /** 一句话描述（ARM Cortex-M3 MCU，64KB Flash）。第一步多半为空 —— 上传的表里没有这一列 */
  description?: string | null;
  /** 行情；**空 = 平台库里现在没货**（照样可以询价，那正是询价最有价值的时候） */
  market?: ElecMarket | null;
  /** **命中方式**（不是匹配度分数）：完全一致 / 开头一致 / 中段一致 / 相近。端上把它显示成一句人话 */
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
  /** 命中的料号，最多 30 条（边打字提示时 8 条）。按命中方式 → 有没有货 → 库存档位排序 */
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
  /** 这一行查出来什么：库里正好有 / 多家厂牌要确认 / 只有开头一致的 / 库里没有 */
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
  /** 料号，**原样传**（大小写、横杠后端会规范化）。必填 */
  mpn: string;
  /** 厂牌，选填。写了的话平台只按这家找货 */
  mfr?: string;
  /** 要几片。必填，上限十亿（再多多半是多敲了几个 0） */
  qty: number;
  /** 目标单价，百万分之一元 */
  targetE6?: number;
}

export interface ElecRfqReq {
  /** 要询的料号，1～50 行。整张 BOM 就是一次提交 */
  lines: ElecRfqLineReq[];
  /** 发票要求。**询价时就要问** —— 专票与不开票差 13%，比任何加价率都大 */
  needInvoice?: ElecInvoice;
  /** 批次要求：不限 / 一年内 / 两年内 */
  dcReq?: ElecDcReq;
  /** 收货城市。只用于判断运费与时效；供应商那一侧只看得到省 */
  deliverCity?: string;
  /** 买家公司名，选填。**供应商看不到** */
  company?: string;
  /** 联系人，选填。手机号从登录态取，不在这里传 */
  contactName?: string;
  /** 给平台的备注（要原装、急用之类） */
  remark?: string;
}

/** 平台对这一行的报价。空 = 还没报，或报价时这一行没找到货 */
export interface ElecLineQuote {
  /** 含税单价，百万分之一元 */
  priceE6: number;
  /** 平台能供多少；空 = 按要的数量给 */
  qty?: number | null;
  /** 批次年份。**只给年份** —— 精确批号能认出是谁家的货 */
  dcYear?: number | null;
  /** 交期天数，0 = 现货 */
  leadDays?: number | null;
  /** 这一行的说明（可换 CH340C 之类） */
  note?: string | null;
}

export interface ElecRfqLine {
  /** 行号，从 1 起。接受某一条报价时按它定位 */
  lineNo: number;
  /** 认到料号库里的哪个料号；认不出为空（照样能询，平台去找） */
  partNo?: string | null;
  /** 买家输的料号原样 */
  mpn: string;
  /** 买家写的厂牌原样；没写为空 */
  mfr?: string | null;
  /** 要几片 */
  qty: number;
  /** 目标单价，百万分之一元；没写为空 */
  targetE6?: number | null;
  /** 平台对这一行的报价；空 = 还没报，或报价时这一行没找到货 */
  quote?: ElecLineQuote | null;
}

export interface ElecRfq {
  /** 询价单号。买家侧用它；供应商侧用的是另一个号，两边对不上 */
  rfqNo: string;
  /** 待报价 / 已报价 / 报价已过期 / 已接受 / 已结束。**过期不落库**，是后端按有效期算出来的 */
  status: ElecRfqStatus;
  /** 提交时间 */
  createdAt: string;
  /** 几行料号 */
  lineCnt: number;
  /** 发票要求 */
  needInvoice: ElecInvoice;
  /** 批次要求 */
  dcReq: ElecDcReq;
  /** 收货城市 */
  deliverCity?: string | null;
  /** 买家公司名 */
  company?: string | null;
  /** 联系人 */
  contactName?: string | null;
  /** 提交时绑定的手机号，**掩码** —— 让他知道平台会打哪个号 */
  contactPhone: string;
  /** 给平台的备注 */
  remark?: string | null;
  /** 平台录入报价的时刻；还没报为空 */
  quotedAt?: string | null;
  /** 报价有效到哪天（含）。过了就不能再接受 —— 行情会变，过期的价平台不兑现 */
  quoteValidUntil?: string | null;
  /** 平台给买家的说明 */
  quoteNote?: string | null;
  /** 结束原因：暂无货源 / 买家不要了 / 已成交。没结束为空 */
  closeReason?: ElecCloseReason | null;
  /** 逐行的料号、数量与报价 */
  lines: ElecRfqLine[];
}

// ── 供应商（与买家同一个账号）──────────────────────────────────────────

/** AGENT 代理商 / TRADER 贸易商 / FACTORY 工厂余料 / OTHER 其他 */
export type ElecSupplierKind = "AGENT" | "TRADER" | "FACTORY" | "OTHER";

export interface ElecSupplierReq {
  /** 公司名称，选填。**点一下就成为供应商**，这些之后在资料里补 */
  companyName?: string;
  /** 类型：代理商 / 贸易商 / 工厂余料 / 其他 */
  kind?: ElecSupplierKind;
  /** 所在城市 */
  city?: string;
  /** 联系人 */
  contactName?: string;
  /** 联系手机可以与登录号不同：老板用自己的号登录，接电话的是业务员 */
  contactPhone?: string;
}

export interface ElecSupplier {
  /** 供应商号。**买家永远看不到它** */
  supplierNo: string;
  /** **点一下就成为供应商**，所以公司名可以还没填 */
  companyName?: string | null;
  /** 类型：代理商 / 贸易商 / 工厂余料 / 其他 */
  kind: ElecSupplierKind;
  /** 所在城市 */
  city?: string | null;
  /** 联系人 */
  contactName?: string | null;
  /** 联系手机。可以与登录号不同 —— 老板登录、业务员接电话 */
  contactPhone: string;
  /** 匿名代号 `S-3F7K`。以后开放身份时买家看到的就是它 */
  maskCode: string;
  /** ACTIVE 正常 / SUSPENDED 已暂停（暂停后库存不再给买家看） */
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
  /** 库存行号 */
  stockNo: string;
  /** 供应商表里写的料号原样 */
  mpn: string;
  /** 供应商表里写的厂牌原样 */
  mfr?: string | null;
  /** 供应商自己看得到精确数量 */
  qty: number;
  /** 原样批号（2338 / 23+ / 24/25） */
  dateCode?: string | null;
  /** 封装 */
  packageName?: string | null;
  /** 起订量 */
  moq?: number | null;
  /** 单价，百万分之一元。空 = 没报价 */
  priceE6?: number | null;
  /** 价格含不含税。**与币种一起决定这个价的口径** */
  taxIncluded: boolean;
  /** 到期日（含）。到了就不再给买家看 */
  validUntil: string;
  /** ON 在售 / EXPIRED 到期（端上据此提示续期） */
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
  /** 认不了的原因：没有料号 / 不像料号 / 数量读不出 / 与前面的行重复 */
  reason: "MPN_MISSING" | "MPN_INVALID" | "QTY_INVALID" | "DUPLICATE";
  /** 那一行写的料号原样，帮他在表里找到它 */
  mpn?: string | null;
}

/**
 * 上传预览。**预览时一行库存都没动**，确认之后才上架。
 */
export interface ElecBatchPreview {
  /** 这次上传的批次号。确认上架、换列映射都带它 */
  batchNo: string;
  /** 上传的文件名 */
  fileName?: string | null;
  /** 导入方式：只改表里有的 / 表里没有的下架 */
  mode: ElecImportMode;
  /** 这张表的价格含不含税 */
  taxIncluded: boolean;
  /** 表头那一行（原样），端上用它画「这几列分别是什么」 */
  headers: string[];
  /** 字段 → 列序号（从 0 起）：MPN / MFR / QTY / DC / PACKAGE / PRICE / MOQ */
  columns: Record<string, number>;
  /** 表里一共几行（不含表头） */
  rowTotal: number;
  /** 其中能上架的几行 */
  rowValid: number;
  /** 认不了的几行（不是料号、数量读不出、与前面重复） */
  rowInvalid: number;
  /** 确认后会新增几行 */
  toInsert: number;
  /** 确认后会更新几行 */
  toUpdate: number;
  /** 全量替换时将下架的行数；增量上传恒为 0 */
  toDelist: number;
  /** 与库里完全一样、不会变的几行 */
  unchanged: number;
  /** 认不了的行（最多 50 条） */
  problems: ElecRowProblem[];
  /** 将下架的料号，最多 20 个 —— 让他一眼看出「这不对，表只传了半截」 */
  delistSample: string[];
  /** PARSED 待确认 / APPLIED 已上架 */
  status: "PARSED" | "APPLIED";
}

export interface ElecRenewResult {
  /** 续期了几行 */
  renewed: number;
  /** 续到哪天（含） */
  validUntil: string;
}
