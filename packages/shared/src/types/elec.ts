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

/** 货况：ORIGINAL 原装原包 / LOOSE 原装散新 / PULLED 拆机 / REFURB 翻新。**价差好几倍** */
export type ElecCond = "ORIGINAL" | "LOOSE" | "PULLED" | "REFURB";

/** 包装：REEL 整盘 / TRAY 托盘 / TUBE 管装 / CUT_TAPE 剪带 / BULK 散装 / BOX 盒装 */
export type ElecPacking = "REEL" | "TRAY" | "TUBE" | "CUT_TAPE" | "BULK" | "BOX";

/** 报价币种。买家面一律换算成人民币含税，只有供应商自己报价时才选 */
export type ElecCurrency = "CNY" | "USD" | "HKD";

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
  /** 参考起价**从多少片起**。只写「¥6.85 起」不说从 1000 起，按 10 片来询的人会觉得被坑 */
  priceFromQty?: number | null;
  /** 有没有现货 */
  spot: boolean;
  /** 最快交期（天）；空 = 没人说交期（**不是现货**） */
  leadDaysMin?: number | null;
  /** 这个料号在库里有哪些货况 */
  conds: ElecCond[];
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

/** 货况要求：ANY 不限 / ORIGINAL 只要原装原包 / NEW 原装即可（散新也行） */
export type ElecCondReq = "ANY" | "ORIGINAL" | "NEW";

/** 包装要求：ANY 不限 / REEL 必须整盘 / CUT_TAPE 可以剪带 */
export type ElecPackingReq = "ANY" | "REEL" | "CUT_TAPE";

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
  /** 货况要求：不限 / 只要原装原包 / 原装即可 */
  condReq?: ElecCondReq;
  /** 包装要求：不限 / 必须整盘 / 可以剪带 */
  packingReq?: ElecPackingReq;
  /** 几天内要到货；空 = 不急。**急单与常备单的价完全不同** */
  needByDays?: number;
  /** 能不能用替代 / 兼容型号（含国产替代）。很多单子卡在这里 */
  allowAlt?: boolean;
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
  /** 货况 */
  cond?: ElecCond | null;
  /** 包装 */
  packing?: ElecPacking | null;
  /** 这一行的说明（可换 CH340C 之类） */
  note?: string | null;
}

/** 这条报价是谁报的：PLATFORM 平台 / SUPPLIER 供应商。**端上不显示这个词**，只决定接受之后怎么跟进 */
export type ElecOfferFrom = "PLATFORM" | "SUPPLIER";

/**
 * 买家看到的一条报价。**没有、也不许加任何供应商字段**。
 */
export interface ElecOffer {
  /** 报价号。按行选中时带它 */
  offerNo: string;
  /** 这一行内的代号（报价 A / B / C）。**只在这一行内有意义** —— 换一行同一家就是另一个字母 */
  label: string;
  /** 含税单价，已加价、已换算成人民币。百万分之一元 */
  priceE6: number;
  /** 能供多少；少于要的数量时端上要标出来。空 = 按要的数量给 */
  qty?: number | null;
  /** 批次年份。只给年份 */
  dcYear?: number | null;
  /** 交期天数，0 = 现货；空 = 没说（不是现货） */
  leadDays?: number | null;
  /** 货况 */
  cond?: ElecCond | null;
  /** 包装 */
  packing?: ElecPacking | null;
  /** 这条报价有效到哪天（含） */
  validUntil?: string | null;
  /** 说明 */
  note?: string | null;
  /** 平台报的 / 供应商报的。端上不显示，只决定接受之后走哪条跟进流程 */
  from: ElecOfferFrom;
  /**
   * 买家选中的就是这一条（供应商报价：已成交；平台那条：整单已接受）。
   * **选中的那条不受有效期过滤** —— 过期的价不能再选，但他选过的那一条要一直看得到
   */
  picked: boolean;
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
  /** 这一行能选的全部报价：平台那条 + 供应商报的（已加价、已匿名），按价升序 */
  offers: ElecOffer[];
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
  /** 派给了几家供应商 */
  dispatchCnt: number;
  /** 有几家报了价 */
  quoteCnt: number;
  /** 发票要求 */
  needInvoice: ElecInvoice;
  /** 批次要求 */
  dcReq: ElecDcReq;
  /** 货况要求 */
  condReq?: ElecCondReq | null;
  /** 包装要求 */
  packingReq?: ElecPackingReq | null;
  /** 几天内要到货；空 = 不急 */
  needByDays?: number | null;
  /** 能不能用替代型号 */
  allowAlt: boolean;
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

/** 供应商状态：ACTIVE 正常 / SUSPENDED 已暂停（暂停后他的库存不再给买家看） */
export type ElecSupplierStatus = "ACTIVE" | "SUSPENDED";

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
  status: ElecSupplierStatus;
  /** 在售且未到期的库存行数 */
  onCount: number;
  /** 7 天内到期的在售行数 */
  expiringCount: number;
  /** 最近一次确认上架的时间；从没传过为空 */
  lastUploadAt?: string | null;
  /** 库存多少天不更新就不再给买家看 —— 端上用它解释「为什么要定期传」 */
  stockTtlDays: number;
}

/** 阶梯价的一档 */
export interface ElecPriceTier {
  /** 从多少片起 */
  minQty: number;
  /** 这一档的单价，百万分之一元 */
  priceE6: number;
}

/** 我的库存按什么筛：ALL 全部 / EXPIRING 7 天内到期 / EXPIRED 已到期 */
export type ElecStockFilter = "ALL" | "EXPIRING" | "EXPIRED";

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
  /** 最小包装量 */
  spq?: number | null;
  /** 阶梯价，按数量档升序。**元器件报价天生是阶梯的**；只有一档时就一条 */
  tiers: ElecPriceTier[];
  /** 最低档的单价，百万分之一元（= tiers 第一条）。空 = 没报价 */
  priceE6?: number | null;
  /** 币种 */
  currency?: ElecCurrency | null;
  /** 价格含不含税。**与币种一起决定这个价的口径** */
  taxIncluded: boolean;
  /** 包装 */
  packing?: ElecPacking | null;
  /** 货况 */
  cond?: ElecCond | null;
  /** 交期天数，0 = 现货；空 = 没说（**不是现货**） */
  leadDays?: number | null;
  /** 货源地 */
  region?: string | null;
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
/** 认不了的行的原因：没有料号 / 不像料号 / 数量读不出 / 与前面的行重复 */
export type ElecRowProblemReason = "MPN_MISSING" | "MPN_INVALID" | "QTY_INVALID" | "DUPLICATE";

export interface ElecRowProblem {
  /** 表里的行号，与 Excel 左边的行号一致 */
  row: number;
  /** 认不了的原因：没有料号 / 不像料号 / 数量读不出 / 与前面的行重复 */
  reason: ElecRowProblemReason;
  /** 那一行写的料号原样，帮他在表里找到它 */
  mpn?: string | null;
}

/**
 * 一处问题的码。前四种是错误（这一行不上架），后三种是警告（照常上架，但列给他看）。
 * QTY_ZERO 数量为 0：也不上架（0 不是有货），单列一码是为了说清「是 0」而不是「读不出」
 */
export type ElecIssueCode =
  | "MPN_MISSING" | "MPN_INVALID" | "QTY_INVALID" | "QTY_ZERO" | "DUPLICATE"
  | "MFR_MISSING" | "MFR_UNKNOWN" | "DC_UNPARSED";

/** 问题的级别：ERROR 这一行不上架 / WARN 照常上架 */
export type ElecIssueLevel = "ERROR" | "WARN";

/** 一处问题，定位到格 */
export interface ElecIssue {
  /** 表里的行号，与 Excel 左边的行号一致 */
  row: number;
  /** 列序号（从 0 起，端上显示成字母）；-1 = 整行的问题（与前面的行重复） */
  col: number;
  /** 那一列的表头原文 */
  header?: string | null;
  /** 那一格的原值；DUPLICATE 时是先出现的那一行的行号 */
  value?: string | null;
  /** 问题码 */
  code: ElecIssueCode;
  /** 错误不上架 / 警告照常上架 */
  level: ElecIssueLevel;
}

/**
 * 一次上传的状态：NEED_MAPPING 待指定列 / PARSED 待确认 / APPLIED 已上架 / CANCELLED 已放弃 /
 * SUPERSEDED 已作废（又传了一张）/ FAILED 解析失败 / EXPIRED 已过期（上传满 1 小时没确认）
 */
export type ElecBatchStatus =
  | "NEED_MAPPING" | "PARSED" | "APPLIED" | "CANCELLED" | "SUPERSEDED" | "FAILED" | "EXPIRED";

/** 某个字段是怎么认出来的：REMEMBERED 上次确认过的 / ALIAS 表头写法 / AI 大模型（要他核对）/ MANUAL 他自己选的 */
export type ElecColumnSource = "REMEMBERED" | "ALIAS" | "AI" | "MANUAL";

/**
 * 上传预览。**预览时一行库存都没动**，确认之后才上架；待确认的数据只在服务器内存里，到 deadline 为止。
 */
export interface ElecBatchPreview {
  /** 这次上传的批次号。确认上架、换列映射、翻看行、放弃都带它 */
  batchNo: string;
  /** 上传时选的文件名（原名） */
  fileName?: string | null;
  /** 导入方式：只改表里有的 / 表里没有的下架 */
  mode: ElecImportMode;
  /** 这张表的价格含不含税 */
  taxIncluded: boolean;
  /** 表头那一行（原样），端上用它画「这几列分别是什么」 */
  headers: string[];
  /** 表头在第几行（从 0 起）；-1 = 一列都没认出 */
  headerRow: number;
  /** 字段 → 列序号（从 0 起）：MPN / MFR / QTY / DC / PACKAGE / PRICE / MOQ … */
  columns: Record<string, number>;
  /** 字段 → 怎么认出来的。AI 认的要提示他核对 */
  columnSource: Record<string, ElecColumnSource>;
  /** 表里一共几行（不含表头） */
  rowTotal: number;
  /** 其中能上架的几行（含有警告的） */
  rowValid: number;
  /** 有错误、不上架的几行 */
  rowInvalid: number;
  /** 有警告、照常上架的几行 */
  rowWarn: number;
  /** 确认后会新增几行 */
  toInsert: number;
  /** 确认后会更新几行 */
  toUpdate: number;
  /** 全量替换时将下架的行数；增量上传恒为 0 */
  toDelist: number;
  /** 与库里完全一样、不会变的几行 */
  unchanged: number;
  /** 问题码 → 几处 */
  issueCounts: Record<string, number>;
  /** 前 100 处问题（定位到格）；全部要翻「有问题」那一类 */
  issues: ElecIssue[];
  /** **过渡字段**：老版本读它（只有错误，一行一条）。新页面读 issues */
  problems: ElecRowProblem[];
  /** 将下架的料号，最多 20 个 —— 让他一眼看出「这不对，表只传了半截」 */
  delistSample: string[];
  /** true = 下架的行数过了线，确认时要带上此刻的下架数 */
  delistConfirm: boolean;
  /** 状态 */
  status: ElecBatchStatus;
  /** 待确认的截止时刻；过了要重新上传 */
  deadline?: string | null;
  /** 上传时刻 */
  createdAt?: string | null;
  /** 确认上架的时刻 */
  appliedAt?: string | null;
}

/** 预览里一行的类别：新增 / 更新 / 未变 / 将下架 / 有问题 */
export type ElecPreviewKind = "INSERT" | "UPDATE" | "UNCHANGED" | "DELIST" | "PROBLEM";

/** 预览里的一行：**解析之后**平台读到的值，让他核对「平台是不是这么理解我的表」 */
export interface ElecPreviewRow {
  /** 表里的行号；将下架的行（表里没有）为 0 */
  row: number;
  /** 这一行属于哪一类 */
  kind: ElecPreviewKind;
  /** 料号原样 */
  mpn?: string | null;
  /** 厂牌原样 */
  mfr?: string | null;
  /** 数量 */
  qty?: number | null;
  /** 批号原样 */
  dateCode?: string | null;
  /** 封装 */
  packageName?: string | null;
  /** 起订量 */
  moq?: number | null;
  /** 最小包装量 */
  spq?: number | null;
  /** 阶梯价 */
  tiers?: ElecPriceTier[] | null;
  /** 币种 */
  currency?: string | null;
  /** 包装方式 */
  packing?: string | null;
  /** 货况 */
  cond?: string | null;
  /** 交期天数，0 = 现货 */
  leadDays?: number | null;
  /** 货在哪 */
  region?: string | null;
  /** 更新的行：变了的那几个字段的旧值（qty / dateCode / priceE6 …） */
  before?: Record<string, unknown> | null;
  /** 这一行的问题（警告也在这里） */
  issues: ElecIssue[];
}

/** 上传记录的一行 */
export interface ElecBatchSummary {
  /** 批次号 */
  batchNo: string;
  /** 上传时选的文件名 */
  fileName?: string | null;
  /** 导入方式 */
  mode: ElecImportMode;
  /** 状态 */
  status: ElecBatchStatus;
  /** 解析失败的原因（错误码的文案键），如 err.elec.upload_format */
  failCode?: string | null;
  /** 表里一共几行 */
  rowTotal: number;
  /** 能上架的几行 */
  rowValid: number;
  /** 有错误的几行 */
  rowInvalid: number;
  /** 有警告的几行 */
  rowWarn: number;
  /** 新增几行 */
  toInsert: number;
  /** 更新几行 */
  toUpdate: number;
  /** 下架几行 */
  toDelist: number;
  /** 未变几行 */
  unchanged: number;
  /** 这次认列问过大模型没有 */
  aiUsed: boolean;
  /** 原件还在不在（被清理之后为 false） */
  fileAvailable: boolean;
  /** 上传时刻 */
  createdAt?: string | null;
  /** 确认上架的时刻 */
  appliedAt?: string | null;
}

/** 确认上架。过了下架护栏的线时必须带上此刻的下架数 */
export interface ElecApplyReq {
  /** 此刻将下架的行数（预览里的 toDelist） */
  expectDelist?: number;
}

export interface ElecRenewResult {
  /** 续期了几行 */
  renewed: number;
  /** 续到哪天（含） */
  validUntil: string;
}

/** 换列映射：字段 → 列序号（从 0 起）。不导入的字段不传 */
export interface ElecRemapReq {
  /** MPN / MFR / QTY / DC / PACKAGE / PRICE / MOQ / SPQ / PACKING / CONDITION / CURRENCY / LEAD / REGION → 列序号 */
  columns: Record<string, number>;
}

// ── 派单：求购派给供应商、供应商报价（看得到求购，看不到买家）────────────

/** 派单状态：SENT 待报价 / VIEWED 看过 / QUOTED 已报价 / DECLINED 已拒绝 */
export type ElecDispatchStatus = "SENT" | "VIEWED" | "QUOTED" | "DECLINED";

/**
 * 供应商报价的状态：ACTIVE 有效 / WITHDRAWN 已撤回 / ACCEPTED 被买家选中 /
 * NOT_CHOSEN 这一行成交给了别家（一行只成交一家；这之后不能再改价）
 */
export type ElecQuoteStatus = "ACTIVE" | "WITHDRAWN" | "ACCEPTED" | "NOT_CHOSEN";

/** 拒绝的原因：NO_STOCK 没货 / PRICE 价格做不了 / OTHER 其他 */
export type ElecDeclineReason = "NO_STOCK" | "PRICE" | "OTHER";

/** 供应商自己填的那条报价（他看得到原样，买家看到的是加价并匿名之后的） */
export interface ElecSupplierQuote {
  /** 报价号 */
  quoteNo: string;
  /** 单价，百万分之一元，**按他填的币种与含税口径** */
  priceE6: number;
  /** 币种 */
  currency: ElecCurrency;
  /** 含不含税 */
  taxIncluded: boolean;
  /** 能供多少 */
  qtyAvailable: number;
  /** 批号原样 */
  dateCode?: string | null;
  /** 交期天数，0 = 现货 */
  leadDays?: number | null;
  /** 货况 */
  cond?: ElecCond | null;
  /** 包装 */
  packing?: ElecPacking | null;
  /** 起订量 */
  moq?: number | null;
  /** 有效到哪天（含） */
  validUntil: string;
  /** 给平台看的备注 */
  remark?: string | null;
  /** 有效 / 已撤回 / 被买家选中 */
  status: ElecQuoteStatus;
}

/** 派给这家供应商的一条求购。**没有买家的任何身份信息**，收货地只到省 */
export interface ElecDispatch {
  /** 供应商侧的单号。**不是询价单号** —— 两边拿不到同一个号 */
  dispatchNo: string;
  /** 待报价 / 看过 / 已报价 / 已拒绝 */
  status: ElecDispatchStatus;
  /** 派来的时间 */
  createdAt: string;
  /** 料号 */
  mpn: string;
  /** 买家写的厂牌；没写为空 */
  mfr?: string | null;
  /** 要几片 */
  qty: number;
  /** 目标单价，百万分之一元；没写为空 */
  targetE6?: number | null;
  /** 批次要求 */
  dcReq: ElecDcReq;
  /** 货况要求 */
  condReq?: ElecCondReq | null;
  /** 包装要求 */
  packingReq?: ElecPackingReq | null;
  /** 几天内要到货；空 = 不急 */
  needByDays?: number | null;
  /** 能不能用替代型号 */
  allowAlt: boolean;
  /** 发票要求 */
  needInvoice: ElecInvoice;
  /** 收货省份。只给到省 */
  deliverProvince?: string | null;
  /** 他自己库里这个料号还有多少（帮他一眼判断能不能接）；没有为空 */
  inStock?: number | null;
  /** 他报过的价；没报过为空。再报一次就是改价 */
  myQuote?: ElecSupplierQuote | null;
}

/** 供应商提交报价 */
export interface ElecSupplierQuoteReq {
  /** 单价，百万分之一元。必填 */
  priceE6: number;
  /** 币种，默认 CNY */
  currency?: ElecCurrency;
  /** 含不含税，默认含税 */
  taxIncluded?: boolean;
  /** 能供多少。必填 */
  qtyAvailable: number;
  /** 批号原样 */
  dateCode?: string;
  /** 交期天数，0 = 现货 */
  leadDays?: number;
  /** 货况 */
  cond?: ElecCond;
  /** 包装 */
  packing?: ElecPacking;
  /** 起订量 */
  moq?: number;
  /** 报价有效几天（默认 3） */
  validDays?: number;
  /** 只给平台看 */
  remark?: string;
}

/** 没货就直说：拒绝也算响应，不回才伤响应率 */
export interface ElecDeclineReq {
  /** 没货 / 价格做不了 / 其他 */
  reason?: ElecDeclineReason;
}

// ── 账号：一个登录账号在买家与供应商两面的身份与角标（GET /elec/me）────────────

/**
 * `GET /elec/me`：端上启动与回到前台时调一次，决定显示「成为供应商」还是「供应商工作台」、两面的红点。
 * **只有身份与计数，没有任何一面的内容** —— 询价走 `/elec/c/**`，求购与库存走 `/elec/b/**`。
 */
export interface ElecMe {
  /** 主系统的用户号。买家与供应商是同一个 */
  userNo: string;
  /** 绑没绑手机号。询价与成为供应商都要先绑（没绑那两个接口回 90001） */
  phoneBound: boolean;
  /** 不是供应商为 null —— 显示「成为供应商」；不为 null 显示「供应商工作台」入口 */
  supplier: ElecMeSupplier | null;
  /** 两面的红点 */
  badges: ElecMeBadges;
}

/** 供应商身份的摘要。完整档案走 `GET /elec/b/supplier` */
export interface ElecMeSupplier {
  /** 供应商号 */
  supplierNo: string;
  /** 公司名；点一下就成为供应商，所以可能还没填 */
  companyName?: string | null;
  /** ACTIVE 正常 / SUSPENDED 已暂停（只关供应商面，买家面照常；工作台进去只读） */
  status: ElecSupplierStatus;
  /** 匿名代号 `S-3F7K` */
  maskCode: string;
}

/** 红点计数。不是供应商、或被暂停时，供应商那两个恒为 0 */
export interface ElecMeBadges {
  /** 买家：还在询价中、有没看过的报价的单子数。打开询价详情即清零；供应商改价不算新 */
  rfqNewOffers: number;
  /** 供应商：派给我、还没回话的求购（待报价 + 看过没回） */
  dispatchPending: number;
  /** 供应商：7 天内到期的在售库存行数 */
  stockExpiring: number;
}
