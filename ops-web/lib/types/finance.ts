/** PointsTypeConfig 的 status 取值域，按服务端契约校验。 */
export type PointsTypeConfigStatus = "DRAFT" | "ACTIVE" | "DRAINING" | "RETIRED";

/** PointsTypeChange 的 action 取值域，按服务端契约校验。 */
export type PointsTypeChangeAction = "ACTIVATE" | "DRAIN" | "RETIRE";

/** PointsFallback 的 mode 取值域，按服务端契约校验。 */
export type PointsFallbackMode = "FIXED" | "RATIO";

/** PointsFundsTarget 的 path 取值域，按服务端契约校验。 */
export type PointsFundsTargetPath = "DIRECT" | "AGGREGATED";

/** PointsPlan 的 action 取值域，按服务端契约校验。 */
export type PointsPlanAction = "TOPUP_U" | "TOPUP_B" | "TRANSFER_U_TO_B";

/** PointsExecution 的 status 取值域，按服务端契约校验。 */
export type PointsExecutionStatus = "SUCCEEDED" | "FAILED" | "UNKNOWN";

/** PointsStatementChange 的 action 取值域，按服务端契约校验。 */
export type PointsStatementChangeAction = "CONFIRM" | "DISPUTE" | "REDRAFT" | "PREPARE_PAYOUT";

/** PointsFlowView 的 bucket 取值域，按服务端契约校验。 */
export type PointsFlowViewBucket = "U" | "C" | "D" | "B" | "RESERVED_B" | "LOCKED_U" | "LOCKED_B";

/** OrderPointsIntegrationQ 的 scene 取值域，按服务端契约校验。 */
export type OrderPointsIntegrationQScene = "ONLINE" | "OFFLINE";

/** OrderPointsIntegrationPolicy 的 mode 取值域，按服务端契约校验。 */
export type OrderPointsIntegrationPolicyMode = "DETACHED" | "ACTIVE" | "DRAINING";

/** PointsStatsQ 的 scope 取值域，按服务端契约校验。 */
export type PointsStatsQScope = "TOTAL" | "STORE" | "USER";

// 结算与资金域（矩阵 P-12）。它是「按商家拆单 + 分账」链路的收口：
// 把商家的 settleAccountReady、自提点的 serviceFeeRate、订单的 trafficSource、
// 售后的 refundSplitPending 这四个**已存在的字段**接起来。
import type { TrafficSource } from "./order";

/**
 * 结算单状态。<b>两条轨道各走各的</b>：
 * 第三方 PENDING → SPLITTING → SPLIT（分账）；
 * 自营 PENDING_RECON → CONFIRMED → PAID（对账 → 确认 → 财务付款）。
 * 合成一条会让「已完成」在两种模式下指向完全不同的事。
 */
/**
 * ⚠️ **`SPLIT` 曾经同时表示「指令已发出」与「钱已到」**，而底下调的是桩实现 ——
 * 账面显示已分账而一分钱没动。2026-08-26 拆开，到账另立 `SPLIT_CONFIRMED`。
 *
 * ⚠️ 说明写在这里而不是夹在值之间：`enum-registry` 的扫描正则只认
 * 「值行 + 行尾注释」，**整行注释会把 union 打断** ——
 * 打断之后这个枚举在登记表里会被报成「代码里已不存在」。
 */
export type SettleStatus =
  | "PENDING"        // 第三方：已生成，待下发分账指令
  | "SPLITTING"      // 指令已下发，等回执
  | "SPLIT"           // 指令已发出，等通道确认（**不是终态、也不表示钱到了**）
  | "SPLIT_CONFIRMED" // 已到账（第三方终态）—— 只能由通道回执产生
  | "OFFLINE_SETTLED" // 当面收款：钱从没进过平台，不走分账
  | "RETRYING"       // 失败重试中
  | "MANUAL"         // 转人工
  | "REVERSED"       // 已回退分账
  | "PENDING_RECON"  // 自营：待对账
  | "CONFIRMED"      // 自营：已确认应付
  | "PAID";          // 自营：已付款（自营终态）

/**
 * 允许的状态流转。<b>两条轨道互不相通</b>：第三方的单不会走到 PAID，
 * 自营的单不会走到 SPLIT —— 它们的钱根本不是同一条路径下去的。
 */
export const SETTLE_TRANSITIONS: Record<SettleStatus, SettleStatus[]> = {
  // 第三方轨道
  PENDING: ["SPLITTING", "RETRYING"],
  SPLITTING: ["SPLIT", "RETRYING"],
  RETRYING: ["SPLITTING", "MANUAL"],
  MANUAL: [],
  // SPLIT 有两条出边：等到回执 → SPLIT_CONFIRMED；退款 → REVERSED。
  // **回执没到也能回退** —— 通道那边可能正要划钱，不给这条边的话，
  // 一笔「已发出未确认」的单退款时无路可走
  SPLIT: ["SPLIT_CONFIRMED", "REVERSED"],
  SPLIT_CONFIRMED: ["REVERSED"],
  // 线下单是终态：钱从没进过平台，没有任何资金动作可做
  OFFLINE_SETTLED: [],
  REVERSED: [],
  // 自营轨道
  PENDING_RECON: ["CONFIRMED"],
  CONFIRMED: ["PAID"],
  PAID: [],
};

/**
 * 结算单：一个商家一个周期一张。
 * ⚠️ **对账恒等式**：gross = platformFee + serviceFee + net。
 * 这三个数分别来自三处（费率表、自提点配置、余数），不校验就会出现"分完了还差几分钱"。
 */
/**
 * 结算单 = <b>一个子订单一张</b>（后端 `stl_bill`）。
 *
 * ⚠️ 与旧类型的形状不同，是有意的。旧那个是**周期汇总**
 * （`period` / `orderCount` / 汇总金额），而后端从来就不是那么结算的 ——
 * 它是「订单成交即生成一张结算单」的即时模型，`/ops/settlements` 也从未实现过。
 * 页面按周期汇总的样子做了很久，而它对不上任何真实数据。
 *
 * 对齐方向是**改前端跟后端**：后端模型是钱真实的流向，
 * 周期汇总只是一个没被实现的设计稿。
 */
/** Four independent funding buckets, with non-cash store receivables reported separately. */
/**
 * 结算单：一个商家一个周期一张。
 * ⚠️ **对账恒等式**：gross = platformFee + serviceFee + net。
 * 这三个数分别来自三处（费率表、自提点配置、余数），不校验就会出现"分完了还差几分钱"。
 */
/**
 * 结算单 = <b>一个子订单一张</b>（后端 `stl_bill`）。
 *
 * ⚠️ 与旧类型的形状不同，是有意的。旧那个是**周期汇总**
 * （`period` / `orderCount` / 汇总金额），而后端从来就不是那么结算的 ——
 * 它是「订单成交即生成一张结算单」的即时模型，`/ops/settlements` 也从未实现过。
 * 页面按周期汇总的样子做了很久，而它对不上任何真实数据。
 *
 * 对齐方向是**改前端跟后端**：后端模型是钱真实的流向，
 * 周期汇总只是一个没被实现的设计稿。
 */
/** Four independent funding buckets, with non-cash store receivables reported separately. */
export interface PointsOverview {
  /** 积分类型，不按不同类型合并余额 */
  typeCode: string;
  /** 市场 */
  market: string;
  /** ISO货币代码 */
  currency: string;
  /** 金额最小单位的小数位数 */
  scale: number;
  /** 兑换比例的积分单位 */
  ratePoints: number;
  /** 兑换比例的最小货币单位 */
  rateMinor: number;
  /** 发行备款 U，最小货币单位 */
  uMinor: number;
  /** 积分负债对应资金 C，最小货币单位 */
  cMinor: number;
  /** 待兑付资金 D，最小货币单位 */
  dMinor: number;
  /** 退款备付 B，最小货币单位 */
  bMinor: number;
  /** 已承诺退款的 B 预留 */
  reservedBMinor: number;
  /** 待决提取占用的 U */
  lockedUMinor: number;
  /** 待决提取占用的 B */
  lockedBMinor: number;
  /** Store receivables are independent claims, not available cash. */
  /** 门店未回收应收 R，不属于现金 */
  receivableMinor: number;
}

/** Explicit receivable fields; no original bank instructions or posting payloads. */
export interface PointsReceivable {
  /** 分页游标 ID */
  id: number;
  /** 应收编号 */
  receivableNo: string;
  /** 门店编号 */
  storeNo: string;
  /** 应收状态 */
  state: string;
  /** 原始应收金额 */
  originalMinor: number;
  /** 已回收金额 */
  recoveredMinor: number;
  /** 待决抵销占用，仍包含在未回收金额中 */
  lockedMinor: number;
  /** 未回收金额，原始减已回收 */
  outstandingMinor: number;
  /** 当前版本 */
  version: number;
}
export interface PointsReceivablePage {
  /** 积分类型，不按不同类型合并余额 */
  typeCode: string;
  /** ISO货币代码 */
  currency: string;
  /** 金额最小单位的小数位数 */
  scale: number;
  /** 当前门店的应收记录 */
  rows: PointsReceivable[];
  /** 下一页游标 */
  next: number;
  /** 是否可能还有下一页 */
  hasMore: boolean;
}

export interface Settlement {
  /** 结算单号 */
  settleNo: string;
  /** 对应的子订单，**一条 = 一个子订单** */
  subOrderNo: string;
  /** 所属主单 */
  orderNo: string;
  /** 结算对象商家 */
  merchantNo: string;
  /**
   * 结算基数（分）= 实付 + 平台补贴 + 积分抵扣。
   *
   * **不含运费**（V367 / TDD-快递100商家寄件 §9 AC21，2026-09-29 改的口径）：
   * 运费是代收代付的钱，进了这里等于让商家为平台代收的运费付佣金。
   * 它单列在结算单的 `freight_income_minor`。
   * 口径改之前这个数是「货款 + 运费」，按它做过的报表要重对一遍。
   */
  grossMinor: number;
  /** 平台佣金（分） */
  commissionMinor: number;
  /** 自提点履约服务费（分） */
  serviceFeeMinor: number;
  /**
   * 代收的运费（分）。**不在 grossMinor 里**，所以不进佣金基数。
   * 非快递单为 0。
   */
  freightIncomeMinor: number;
  /**
   * 平台实付给快递公司、从商家收款里扣回的快递费（分）。
   *
   * **只有平台代寄才有**；商家自己填单号发货是他自付，这里是 0。
   * 判据是 `freightShipMode`，不是「这个数是不是 0」——
   * 平台代寄但还没称重回传时它也是 0。
   */
  freightCostMinor: number;
  /** PLATFORM_CALL 平台代寄 / MERCHANT_SELF 商家自寄；非快递单为空 */
  freightShipMode?: string;
  /** 实付高于代收时的原因：OVERWEIGHT / REGION_SURCHARGE / OVER_CAP。没有差额时为空 */
  freightDiffReason?: string;
  /** 实付商家（分）= 基数 − 佣金 − 服务费 + 运费收入 − 实付快递费 */
  netMinor: number;
  /** 该单的流量来源，决定适用哪一档费率 */
  trafficSource: string;
  /** 本单快照的佣金费率（万分比）。**费率改了历史单不跟着变** */
  commissionRate: number;
  /** 结算状态，两条轨道各走各的 */
  status: SettleStatus;
  /** 生成时刻（毫秒） */
  createdAt: number;
  /** 分账成功时刻；空 = 未分账 */
  splitAt?: number | null;
  /** 哪家店挣的（统计维度） */
  storeNo?: string | null;
  /** 打给哪个收款号（结算维度） */
  payMerchantNo?: string | null;
  /** 自营 / 第三方 */
  businessMode?: BusinessMode | null;
  /** 自营：进项票状态。第三方恒为 NO_INVOICE */
  invoiceStatus?: string | null;
  /** 自营：付款凭证号。空 = 尚未付款 */
  paymentRef?: string | null;
}

/**
 * 分账指令流水（后端 `stl_split_log`）。
 *
 * <b>结算单说的是「该给多少」，这里说的是「发了几条指令、成没成、失败在哪」</b>——
 * 出问题时要看的是后者。失败的记录也在这里。
 */
export interface SplitLog {
  /** 所属结算单 */
  settleNo: string;
  /** 对应的子订单 */
  subOrderNo: string;
  /** SPLIT / REVERSE / SUBSIDY / SUBSIDY_RETURN */
  splitAction: string;
  /** 该指令的金额。**补差与分账口径不同** */
  amountMinor: number;
  /** SUCCESS / FAIL */
  /** SUCCESS / FAIL */
  result: string;
  /** 平台侧幂等号 */
  requestNo: string;
  /** 通道返回的单号；失败时为空 */
  providerNo?: string | null;
  /** 失败原因。**这一列是这张表存在的意义** */
  message?: string | null;
  /** 指令时刻（毫秒） */
  createdAt: number;
}

/** 分账明细：一条 = 一个子订单。费率按 trafficSource 分档（R16）。 */
export interface SplitRecord {
  /** 分账明细单号 */
  splitNo: string;
  /** 所属结算单 */
  settleNo: string;
  /** 对应的子订单。**一条明细 = 一个子订单** */
  orderNo: string;
  /** 商家名快照 */
  merchantName: string;
  /** 该订单的流量来源，决定适用哪一档费率（R16） */
  trafficSource: TrafficSource;
  /** 该订单实付金额（分） */
  grossAmount: number;
  /** 本条实际适用的平台佣金费率（万分比），来自费率表 */
  feeRate: number;
  /** 本条的平台佣金（分） */
  platformFee: number;
  /** 履约自提点。非自提单为空 */
  pickupNo?: string;
  /** 自提点履约服务费（分）；非自提单为 0 */
  serviceFee: number;
  /** 实付商家（分）。**恒等式**：grossAmount = platformFee + serviceFee + netAmount */
  netAmount: number;
}

/**
 * 经营模式。与 {@link FeeRuleVersion} 一起构成费率的第一个维度。
 *
 * 自营：平台是销售主体，这个费率是**进销差价（毛利）**。
 * 第三方：平台是撮合方，这个费率是**服务收入（佣金）**。
 * 算法一样，记账口径不同 —— 口径由结算单上的 `businessMode` 快照决定。
 */
export type BusinessMode = "SELF_OPERATED" | "THIRD_PARTY";

/**
 * 费率适用的流量来源。
 *
 * **只有两档**，比订单上的 `TrafficSource`（还有 INVITE / CHANNEL）窄 ——
 * 后端的费率表只认这两个。写成独立类型而不是在接口里内联字面量联合，
 * 是因为内联的枚举不会被登记表扫到，等于绕过了枚举雷达（规范 §D5）。
 */
export type FeeTrafficSource = "MERCHANT_OWNED" | "PLATFORM";

/**
 * 费率的一个版本（后端 `stl_fee_rule`）。
 *
 * ⚠️ 这里与旧的 `FeeRule` 形状完全不同，是有意的。旧那个是一维（只按流量来源）、
 * 单值、原地改；**后端从未实现过它**（守卫清单里 `fee-rule` 一直挂在「整域未开工」）。
 * 真正落地的是二维 + 版本化：
 *
 * - **二维**：经营模式 × 流量来源。两者正交 —— 只按经营模式分档，
 *   等哪天想给自营也区分客流就要改表结构，而费率表最不该改结构（历史行要一直可读）。
 * - **版本化**：调费率是**插新版本**，旧版本永久保留。原地改只能回答「现在是多少」，
 *   而真正会被问到的是「上个月那批单当时按什么费率算的」。
 */
export interface FeeRuleVersion {
  /** 规则版本号 */
  ruleNo: string;
  /** 经营模式，费率的第一个维度 */
  businessMode: BusinessMode;
  /** 适用的流量来源，费率的第二个维度 */
  trafficSource: FeeTrafficSource;
  /** 万分比。500 = 5% */
  rateBp: number;
  /** 生效时刻（毫秒）。**填未来时刻 = 预约生效** */
  effectiveFrom: number;
  /** 1 = 该版本生效；0 = 已停用（回退到上一版） */
  enabled: number;
  /** 为什么调这一次 —— 回查时这句话比数字更有用 */
  remark?: string | null;
  /** 创建时间 */
  createdAt?: string;
  /** 创建人 */
  createdBy?: string;
}

/** 某时刻实际生效的费率表，键为 `${businessMode}|${trafficSource}`。 */
export type EffectiveFeeRates = Record<string, number>;

// ── 提现审批（P-12.2.1）───────────────────────────────────────────

/**
 * 提现状态。
 *
 * ⚠️ `APPROVED → PAID` **不由运营点** —— 打款结果来自渠道回执。
 * 让人手动置为"已打款"，就等于允许在钱没到账时把单子做平。
 */
export type WithdrawStatus = "PENDING" | "APPROVED" | "REJECTED" | "PAID" | "FAILED";

export const WITHDRAW_TRANSITIONS: Record<WithdrawStatus, WithdrawStatus[]> = {
  PENDING: ["APPROVED", "REJECTED"],
  // 打款成功/失败都由回执驱动，运营在界面上没有这两个动作
  APPROVED: ["PAID", "FAILED"],
  // 打款失败可以重新审批（多半是账户信息要改）
  FAILED: ["APPROVED", "REJECTED"],
  REJECTED: [],
  PAID: [],
};

export interface Withdrawal {
  /** 提现单号 */
  withdrawNo: string;
  /** 申请商家 */
  merchantNo: string;
  /** 商家名快照 */
  merchantName: string;
  /** 申请金额（分） */
  amount: number;
  /** 申请时的可提余额（分）。快照，不是实时值 —— 审批看的是申请那一刻的口径 */
  availableBalance: number;
  /** 收款账户，展示一律脱敏 */
  bankAccountMasked: string;
  /** 提现状态。**`APPROVED → PAID` 由渠道回执驱动，运营点不了** */
  status: WithdrawStatus;
  /** 申请时间 */
  appliedAt: string;
  /** 审批时间。未审为 null */
  decidedAt?: string | null;
  /** 审批人（STAFF 账号）。未审为 null */
  decidedBy?: string | null;
  /** 驳回原因 / 大额复核说明。原样回商家 B 端 */
  remark?: string | null;
}

// ── 发票与个税（P-12.2.2 / 12.2.3）────────────────────────────────

/** 抬头类型。企业抬头必须有税号，个人抬头没有 —— 这是两条不同的校验路径。 */
export type InvoiceTitleType = "COMPANY" | "PERSONAL";

export type InvoiceStatus = "PENDING" | "ISSUED" | "REJECTED";

export interface InvoiceRequest {
  /** 开票申请单号 */
  invoiceNo: string;
  /** 申请商家 */
  merchantNo: string;
  /** 商家名快照 */
  merchantName: string;
  /** 开票周期，与结算周期同口径 */
  period: string;
  /** 申请开票金额（分） */
  amount: number;
  /** 该周期已结算金额（分）。开票金额不能超过它 —— 超了就是虚开 */
  settledAmount: number;
  /** 抬头类型。企业抬头必须有税号，个人抬头没有 —— 两条不同的校验路径 */
  titleType: InvoiceTitleType;
  /** 发票抬头（公司全称或个人姓名） */
  title: string;
  /** 纳税人识别号。企业抬头必填 */
  taxNo?: string | null;
  /** 开票状态 */
  status: InvoiceStatus;
  /** 开票后的发票流水号 */
  serialNo?: string | null;
  /** 申请时间 */
  appliedAt: string;
  /** 处理时间。未处理为 null */
  decidedAt?: string | null;
  /** 驳回原因。原样回商家 B 端 */
  remark?: string | null;
}

/**
 * 个税代扣规则（P-12.2.3）。
 *
 * 只对**个人主体**商家生效：个体户与企业自行申报，平台不代扣。
 * 起征点以下不扣 —— 不设起征点会给每一笔几块钱的提现都产生一条扣税记录。
 */
/**
 * 平台开票抬头（P0-11）。**供应商照着它给平台开票** ——
 * 缺公司全称或税号，票就开不出来。
 *
 * <p>五个字段都是字符串，后端存成一条扁平 JSON 配置（`finance.invoice-title`）；
 * **默认值是五项全空而不是编一份假的** —— 空着能让人立刻发现「还没配」。
 */
export interface InvoiceTitle {
  /** 公司全称。**必填** */
  companyName: string;
  /** 纳税人识别号。**必填** */
  taxNo: string;
  /** 注册地址 */
  address: string;
  /** 注册电话 */
  phone: string;
  /** 开户行与账号 */
  bankAccount: string;
}

export interface TaxRule {
  /** 起征点（分）：单期收入低于它不代扣 */
  threshold: number;
  /** 代扣税率（万分比） */
  rate: number;
  /** 最后修改时间 */
  updatedAt: string;
  /** 最后修改人（STAFF 账号） */
  updatedBy: string;
}

/**
 * 进项票（供应商开给平台的）。自营链路专用 —— **票到才付款**。
 *
 * `titleMatched` 是后端算好的：抬头与主体名对不上时不给核验通过，
 * 而这一条**在界面上必须显示原因** —— 财务看到「不能核验」而不知道为什么，
 * 只会去问开票的人，而对方也不知道。
 */
export interface PurchaseInvoice {
  /** 平台侧的进项票记录号（不是发票上印的号） */
  invoiceNo: string;
  /** 哪家商家的票 */
  entityNo: string;
  /** 所属账期 yyyyMM */
  period: string;
  /** 发票代码，票面左上那一串 */
  invoiceCode: string;
  /** 发票号码，票面右上那一串。**与 invoiceNo 不是一回事** */
  invoiceNumber: string;
  /** 票种：专票 / 普票 / 电子票 */
  invoiceType: string;
  /** 票面抬头 */
  titleName: string;
  /** 票面税号 */
  titleTaxNo: string;
  /** 价税合计（分） */
  amountMinor: number;
  /** 其中税额（分） */
  taxAmountMinor: number;
  /** 万分比 */
  taxRate: number;
  /** 开票日期 */
  invoiceDate?: number | null;
  /** 票面影像。核验要看原件 */
  imageUrl?: string | null;
  /** PENDING / SUBMITTED / VERIFIED / REJECTED */
  status: string;
  /** 驳回原因。**要原样回商家** —— 只说「不通过」他不知道该补什么 */
  rejectReason?: string | null;
  /** 抬头与主体名是否一致。**后端算，端上不重算** —— 两处判会走岔 */
  titleMatched: boolean;
  /** 这张票覆盖了哪些结算单 */
  settleNos: string[];
}

/**
 * 买家的开票申请（`/ops/invoice-requests`）。
 *
 * ⚠️ **这个域里有三张不同的「票」，名字很近，别混：**
 *
 * | 类型 | 谁开给谁 | 决定什么 | 端点 |
 * |---|---|---|---|
 * | {@link PurchaseInvoice} 进项票 | 供应商 → 平台 | 平台能不能付款（票到付款）| `/ops/purchase-invoices` |
 * | {@link InvoiceRequest} 商家开票申请 | 平台 → 商家 | 商家的服务费发票 | `/ops/finance/invoices` |
 * | 本类型 买家开票申请 | 平台 → 买家 | 买家能不能报销 | `/ops/invoice-requests` |
 *
 * 前两个此前已有类型，本类型是补的 —— 它按订单走（`orderNo`），前两个按主体/账期走。
 */
export interface BuyerInvoiceRequest {
  /** 开票申请号 */
  requestNo: string;
  /** 针对哪一单 */
  orderNo: string;
  /** PERSONAL / COMPANY */
  titleType: string;
  /** 抬头 */
  title: string;
  /** 税号。单位抬头必填 */
  taxNo?: string | null;
  /** 发到哪个邮箱。电子票唯一的交付方式 */
  email?: string | null;
  /** 价税合计（分） */
  amountMinor: number;
  /** PENDING / ISSUED / REJECTED */
  status: string;
  /** 已开出的发票号 */
  invoiceNo?: string | null;
  /** 开出来的时刻。空 = 还没开 */
  issuedAt?: number | null;
  /** 驳回原因。**要原样回商家** —— 只说「不通过」他不知道该补什么 */
  rejectReason?: string | null;
  /** 申请时刻 */
  createdAt?: number | null;
}

/**
 * 对账的**覆盖范围说明**。
 *
 * ⚠️ 它存在的理由只有一个：**不说的话「今天没有差异」是句假话。**
 * 一期只有平台侧自查（扫我方停在 PENDING 的收款逐笔查单），
 * 渠道账单比对要等通道能力 —— 也就是说「渠道扣了钱而我方没记录」
 * 那一整类差异**现在根本看不见**。
 *
 * `note` **直接展示，不在端上写死** —— 写死的话，后端接上渠道账单之后，
 * 页面还在说「看不见」。
 */
/**
 * 渠道报文一条（`stl_channel_message`，V286 起在落数据）。
 *
 * ⚠️ **报文已脱敏**：签名、证书序列号、Authorization 都不入库，
 * 所以它<b>不能拿去重放验签</b>。列表响应里带着这句话（`note`），
 * 页面必须显示 —— 不显示的话，第一个拿它去核签名的人会得出
 * 「我们的验签实现有 bug」这个结论。
 */
/**
 * 对账的**覆盖范围说明**。
 *
 * ⚠️ 它存在的理由只有一个：**不说的话「今天没有差异」是句假话。**
 * 一期只有平台侧自查（扫我方停在 PENDING 的收款逐笔查单），
 * 渠道账单比对要等通道能力 —— 也就是说「渠道扣了钱而我方没记录」
 * 那一整类差异**现在根本看不见**。
 *
 * `note` **直接展示，不在端上写死** —— 写死的话，后端接上渠道账单之后，
 * 页面还在说「看不见」。
 */
/**
 * 渠道报文一条（`stl_channel_message`，V286 起在落数据）。
 *
 * ⚠️ **报文已脱敏**：签名、证书序列号、Authorization 都不入库，
 * 所以它<b>不能拿去重放验签</b>。列表响应里带着这句话（`note`），
 * 页面必须显示 —— 不显示的话，第一个拿它去核签名的人会得出
 * 「我们的验签实现有 bug」这个结论。
 */
export interface ChannelMessage {
  /** 通道报文编号 */
  messageNo: string;
  /** 支付通道编码 */
  payChannel: string;
  /** CALLBACK 通道推给我方 / SEND 我方发给通道 */
  msgType: string;
  /** 回调是端点路径，发送是接口坐标 */
  api: string;
  /**
   * 我方单号。**验签失败时为空** —— 那种行只能按时间和通道翻，
   * 所以按单号筛会把最该看的那几行滤掉
   */
  bizNo?: string | null;
  /** 原支付指令编号 */
  paymentNo?: string | null;
  /** RECEIVED / ACCEPTED / REJECTED / OK / FAILED */
  outcome: string;
  /** 拒绝或失败的原因，直接显示 */
  reason?: string | null;
  /** 脱敏并截断后的报文 */
  payload?: string | null;
  /** 按接口权限返回的报文头信息 */
  headers?: string | null;
  /** 服务端创建时间，Unix 毫秒 */
  createdAt?: number | null;
}

/**
 * 付款清单的一行（ADR-011 · P2）。财务拿它去网银转账。
 *
 * ⚠️ **这是全站唯一带明文账号的响应**。拿到之后立刻写进导出文件，
 * 不要存进任何全局 state、不要打日志。
 */
export interface PayoutRow {
  /**
   * 放款单号（V391）。**有号的按号回填凭证**（`payPayout`），
   * 没号的是存量老路（逐张 `payPayable`）。两条路并存到存量清零。
   */
  payoutNo?: string | null;
  /** 供应商主体号 */
  entityNo: string;
  /** 商家名。查不到时回落成主体号 */
  merchantName: string;
  /** PERSONAL_BANK_CARD 个人银行卡 / CORPORATE 对公 */
  accountType: string;
  /** 户名。已经过「必须等于营业执照主体名」的硬校验 */
  accountName: string;
  /** **明文账号**。只在这一次响应里存在 */
  accountNumber: string;
  /** 开户银行，可能为空 */
  bankName: string | null;
  /** 开户支行，可能为空 */
  bankBranch: string | null;
  /** 本次该付多少（分） */
  amountMinor: number;
  /** 这一行覆盖了几张结算单 */
  billCount: number;
  /** 银行附言。**回读银行流水时靠它勾对**，所以不是一句人话 */
  remark: string;
  /** 覆盖的结算单号。回填凭证号时按它逐张登记 */
  settleNos: string[];
}

/** 本该付、但缺条件的一行。**必须显示** —— 不然财务看不出这一期怎么少了一家 */
export interface PayoutBlockedRow {
  /** 供应商主体号 */
  entityNo: string;
  /** 商家名 */
  merchantName: string;
  /** 这一期因此没付出去多少（分） */
  amountMinor: number;
  /** 涉及几张结算单 */
  billCount: number;
  /** 为什么没进清单。给的是可执行的原因：缺票 / 未对账 / 无收款账户 */
  reason: string;
}

/** 付款清单。`totalMinor` **不含 blocked** —— 那笔钱这次付不出去 */
export interface PayoutList {
  /** 可付的 */
  rows: PayoutRow[];
  /** 被挡下的 */
  blocked: PayoutBlockedRow[];
  /** rows 的合计（分） */
  totalMinor: number;
}

/**
 * 银行流水导入的结果（TDD-供应商结算与双轨资金 §10）。
 *
 * **三个计数分开给，不合成一句「成功 N 条」**：它们对应三种完全不同的处置 ——
 * 入库的不用管；跳过的说明这份传过了（正常，不是错）；失败的要对着原始文件去看那几行。
 */
export interface BankFlowImportResult {
  /** 文件里认出来的行数 = imported + skipped + failed */
  total: number;
  /** 真正入库的 */
  imported: number;
  /** 流水号已存在、跳过的。**重复上传是常态，不是错误** */
  skipped: number;
  /** 没解析成功的行数 */
  failed: number;
  /** 失败明细。`line` 是原始文件里的行号 —— 财务要对着原文件看 */
  failures: BankFlowImportFailure[];
}

export interface BankFlowImportFailure {
  /** 原始文件里的行号（1 起，含表头） */
  line: number;
  /** 为什么没认出来。给的是可执行的原因：看不懂的日期 / 金额 / 看不出方向 */
  reason: string;
}

/**
 * 结算口径的经营统计一行（TDD-供应商结算与双轨资金 §2.1）。
 *
 * **与门店经营排行不是一回事**：那个读订单（GMV、退款率）、是最近 N 天 Top N；
 * 这里读结算单、按区间全量、三维可切。GMV 没扣佣金与手续费，两个数对不上是正常的。
 */
export interface SettleStatRow {
  /** 维度值：门店号 / 主体号 / 收款商户号。空门店为 __UNASSIGNED__ */
  dimKey: string;
  /** 展示名。查不到时回落成 dimKey 本身，**不会是空串** */
  dimName: string;
  /** 成交额（分）。**不含退款**（退款走售后与分账回退），**也不含运费**（见结算单那份的说明） */
  grossMinor: number;
  /** 平台佣金（分） */
  commissionMinor: number;
  /** 服务费（分） */
  serviceFeeMinor: number;
  /** 渠道手续费（分） */
  channelFeeMinor: number;
  /** 代收的运费（分）。**不在 grossMinor 里** */
  freightIncomeMinor: number;
  /**
   * 平台代付出去的快递费（分）。
   *
   * 与 `freightIncomeMinor` 的差额是**平台自己在快递上的盈亏** ——
   * 这个数此前没有任何地方能看到，而平台一直在垫这笔钱。
   */
  freightCostMinor: number;
  /** 商家净额（分） */
  netMinor: number;
  /** 结算单数。只给金额看不出「一笔大的还是很多笔」 */
  billCount: number;
}

/**
 * 供应商收款账户（V358，ADR-011 自营供应商模式）。
 *
 * **没有明文账号字段** —— 后端只回掩码。审核这件事本身是资金重定向：
 * 通过之后这个主体下一期的货款就打到这张卡。
 */
export interface PayoutAccount {
  /** 平台内部单号，审核时按它定位 */
  accountNo: string;
  /** 供应商主体。**账户挂主体不挂门店** —— 收款是主体的事，门店只是统计维度 */
  entityNo: string;
  /** PERSONAL_BANK_CARD 个人银行卡 / CORPORATE 对公 */
  accountType: string;
  /** 户名。**必须等于营业执照主体名** —— 后端硬校验，对不上提交就被拒 */
  accountName: string;
  /** 账号掩码，只留尾四位 */
  accountMasked: string;
  /** 开户银行。商家可不填，所以可能为空 */
  bankName?: string | null;
  /** 开户支行。同上，转账时财务据它核对 */
  bankBranch?: string | null;
  /** PENDING 待审 / ACTIVE 生效中 / REJECTED 已驳回 / DISABLED 已被新卡顶替 */
  status: string;
  /** 驳回原因，原样回商家 */
  auditRemark?: string | null;
  /** 审核时刻（毫秒）。未审为空 —— 与「审过但没写原因」是两回事 */
  auditedAt?: number | null;
}

/** 报文分页。`note` 是固定口径，**端上必须显示** */
/** 报文分页。`note` 是固定口径，**端上必须显示** */
export interface ChannelMessagePage {
  /** 本页记录 */
  records: ChannelMessage[];
  /** 符合条件的记录总数 */
  total: number;
  /** 页码，从一开始 */
  pageNo: number;
  /** 本页最大记录数 */
  size: number;
  /** 服务端补充说明 */
  note: string;
}

export interface ReconCoverage {
  /** 渠道账单是否已接入。false 时 note 必须显示给运营 */
  channelBillConnected: boolean;
  /** 说明 */
  note: string;
}

/**
 * 一条对账轴的一轮结果。
 *
 * ⚠️ **`coverage.note` 必须显示** —— 四条轴今天都只有 A 侧（我方自查），
 * 渠道账单、分账查询、银行流水三种外部数据都还没接。
 * 不说的话，「今天没有差异」对四条轴都是假话。
 *
 * `error` 非空 = **这条轴今天没跑成**。它与「零差异」在页面上长得一样、
 * 含义却完全相反，所以要单独标出来。
 */
export interface ReconAxisReport {
  /** PAYMENT 收款 / SPLIT 分账 / PAYOUT 出款 / POINTS_POOL 积分池 */
  axis: string;
  /** 结论 */
  outcome?: { scanned: number; resolved: number; opened: number; deferred: number } | null;
  /** 覆盖率 */
  coverage: { complete: boolean; note: string };
  /** 错误信息 */
  error?: string | null;
}

/** 通道费率的一个版本（后端 `sys_pay_channel_rate`）。 */
export interface PayChannelRateVersion {
  /** 规则版本号 */
  rateNo: string;
  /** 通道码，与 sys_pay_channel 同值域 */
  payChannel: string;
  /** `*` = 该通道全部支付方式 */
  payMethod: string;
  /** `*` = 全部主体形态 */
  legalForm: string;
  /** 万分比。38 = 0.38% */
  rateBp: number;
  /** 单笔最低手续费（分）。0 = 无保底 */
  minFeeMinor: number;
  /** 生效时刻（毫秒）。**填未来时刻 = 预约生效** */
  effectiveFrom: number;
  /** 停用的版本不参与取值。停用最新版 = 回退到上一版 */
  enabled?: boolean;
  /** 为什么调这一次 —— 回查时这句话比数字更有用 */
  remark?: string | null;
}

/** 一个支付通道的设置与费率。 */
export interface PayChannelSetting {
  /** 通道码，如 WECHAT / ALIPAY */
  payChannel: string;
  /** 展示名 */
  name: string;
  /** 停用只影响**新进件与新下单**，已开通的商户与在途的单不受影响 */
  enabled: boolean;
  /** JSON 数组文本，如 `["CN"]`。空 = 全市场可用 */
  markets: string | null;
  /** 结算币种，如 CNY */
  currency: string | null;
  /** 通道结算周期，如 T+1。展示与对账预期用 */
  settleCycle: string | null;
  /** 能否补差。**为 false 时该通道不开积分抵扣** —— 这是通道的事实，运营改不了 */
  supportsSubsidy: boolean;
  /** 此刻生效的那一版；**一条都没配时为 null**，要显示成「未配置」而不是 0 */
  currentRate: PayChannelRateVersion | null;
  /** 全部版本，按生效时间倒序 */
  rates: PayChannelRateVersion[];
}

/**
 * 账期批次：<b>一个主体、一个通道、一个账期，一批</b>。
 *
 * <p>批次管「能不能放」，单据管「放得成不成」——
 * 所以这一页回答的是「这家的钱卡在哪一批」，而不是「这一笔多少钱」。
 */
export interface SettleBatch {
  /** 批次号。**商家在自己的账期页上看到的是同一个号**，客服照它对话 */
  batchNo: string;
  /** 收款主体号 */
  entityNo: string;
  /** 支付通道码。**不同通道账期不同，所以不能合批** */
  payChannel: string;
  /** 本批采用的账期规则快照，如 T+1 / WEEKLY */
  settleCycle: string;
  /** 本批的收单起始时刻。与 dueAt 一起界定「这批装的是哪几天的单」 */
  periodFrom: number;
  /** T3 应结日 */
  dueAt: number;
  /** 实际放行时刻。与 dueAt 分开才答得出「晚了几天」 */
  releasedAt: number | null;
  /**
   * Tmax：通道冻结窗口到期时刻。**为 null 表示还判不了** ——
   * 冻结窗口的天数还没有书面口径，此时不该按一个猜的数报警
   */
  freezeExpireAt: number | null;
  /** DRAFT / COLLECTED / RECONCILING / BLOCKED / RECONCILED / RELEASED */
  status: SettleBatchStatus;
  /** 本批单据数 */
  billCount: number;
  /** 本批结算基数合计（分）。与单据上同名字段一个口径 —— **不含运费** */
  grossMinor: number;
  /** 本批应放款合计（分）。**放行时按这个数下发** */
  netMinor: number;
  /**
   * 对账覆盖面。**SELF_ONLY 时界面要如实标注「仅我方自查」**，
   * 不能显示成「已对账」—— 没有对方账单时那是一句自证的话
   */
  reconScope: ReconScope;
  /** 挂起原因，**直接展示给商家的原话**（含具体数字与阈值） */
  blockedReason: string | null;
  /** 挂起时刻。与 blockExpireAt 一起才看得出「还剩多久自动放行」 */
  blockedAt: number | null;
  /** 挂起时限。超时自动放行并告警 —— 没有时限的挂起等于永久冻结 */
  blockExpireAt: number | null;
  /** 人工放行者；**SYSTEM_TIMEOUT = 超时自动放行**，要单独看 */
  decidedBy: string | null;
  /** 处置时写的原因。**事后要能回答「当时凭什么放的」**，而那句话只有此刻的人写得出来 */
  decideRemark: string | null;
  /** 本批最近一笔放款的摘要（V391）。空 = 还没放款 */
  payoutStatus?: PayoutStatus | null;
  /** 凭证号（网银流水号） */
  paymentRef?: string | null;
  /** 财务登记凭证的时刻（毫秒） */
  paidAt?: number | null;
}

/**
 * 对账覆盖面。<b>SELF_ONLY 不等于「对完了」</b> ——
 * 它只是我方账内自洽，通道账单还没比过。界面要如实标注，
 * 显示成「已对账」是一句自证的话。
 */
export type ReconScope =
  | "SELF_ONLY"      // 只做了 A 侧：我方账内自洽
  | "BOTH";          // A + B 两侧：与通道账单也比过了

export type SettleBatchStatus =
  | "DRAFT"          // 开批，正在收单
  | "COLLECTED"      // 截批，本批不再接新单
  | "RECONCILING"    // 三道对账门在跑
  | "BLOCKED"        // 有未处置差异或风控命中，整批挂起
  | "RECONCILED"     // 全过，可放行
  | "RELEASED";      // 已放款：生成了放款记录（自营）或待分账（第三方）

/**
 * 放款记录（V391 / TDD-账期推进与放款记录）：**账期批次 × 收款号，一笔网银转账一条**。
 * 凭证号与银行流水挂在它上面，不再挂在逐张结算单上。
 */
export interface Payout {
  /** 放款单号（PO…） */
  payoutNo: string;
  /** 所属账期批次 */
  batchNo: string;
  /** 收款主体业务键 */
  entityNo: string;
  /** 收款号（分组键）。自营一主体一账户时为空 */
  payMerchantNo: string | null;
  /** 付款时快照：户名。银行要户名，而账号会改 */
  accountName: string | null;
  /** 付款时快照：开户行 */
  bankName: string | null;
  /** 付款时快照：支行 */
  bankBranch: string | null;
  /** 付款时快照：账号掩码。明文只在导出付款清单那一刻存在 */
  accountNoMasked: string | null;
  /** 本笔金额（分）= 组内结算单 net 之和 */
  amountMinor: number;
  /** 本笔包含几张结算单 */
  billCount: number;
  /** 币种 */
  currency: string;
  /** 放款状态。只有一个方向，见 PayoutStatus */
  status: PayoutStatus;
  /** 放款通道。一期全是 MANUAL（网银手工） */
  channel: PayoutChannel;
  /** 凭证号（网银流水号）。登记付款时必填 */
  paymentRef: string | null;
  /** 对上的银行流水号。出款对账勾上时写 */
  bankFlowNo: string | null;
  /** 导出进付款清单的时刻（毫秒） */
  exportedAt: number | null;
  /** 登记凭证的时刻（毫秒） */
  paidAt: number | null;
  /** 登记凭证的操作人 */
  paidBy: string | null;
  /** 银行流水勾上的时刻（毫秒） */
  matchedAt: number | null;
  /** 退回原因。给运营看，也给商家看 */
  failReason: string | null;
  /** 本笔包含的结算单 */
  settleNos: string[];
}

/** 放款通道。一期全是网银手工；BANK_API 预留，接口来了只换 EXPORTED→PAID 那一步 */
export type PayoutChannel = "MANUAL" | "BANK_API";

/** 只有一个方向。MATCHED 只能由出款对账轴写（银行流水勾上），不给人工入口 */
export type PayoutStatus =
  | "PENDING"    // 已生成，待导出进付款清单
  | "EXPORTED"   // 已导出，财务拿去网银
  | "PAID"       // 已登记凭证
  | "MATCHED"    // 银行流水勾上
  | "FAILED";    // 打款失败或退回

/**
 * 商家欠款：退款追不回来时先记在账上，从后续货款里扣。
 *
 * ⚠️ **与保证金方向相反**：保证金是商家的钱（平台代管、将来要退还），
 * 欠款是商家欠平台的。两者不能合成一个数看。
 */
export interface MerchantDebt {
  /** 欠款主体号 */
  entityNo: string;
  /** 当前欠款（分），恒 >= 0。0 = 没有欠款 */
  balanceMinor: number;
  /** 流水，时间倒序。**余额从流水推得出来**，两者对不上时信流水 */
  txns: DebtTxn[];
}

export interface DebtTxn {
  /** 流水号 */
  txnNo: string;
  /** 这一笔是怎么来的 */
  txnType: DebtTxnType;
  /** **有符号**：产生为正、偿还为负。靠 txnType 推方向等于把方向表达两遍 */
  amountMinor: number;
  /** 变动后余额。对账时逐笔回放用 */
  balanceAfterMinor: number;
  /** 源单类型，如 REFUND。空 = 存量数据 */
  sourceType: string | null;
  /** 源单号。**指不出源头的欠款没法向商家解释** */
  sourceNo: string | null;
  /** OFFSET 时记从哪一批扣的 */
  batchNo: string | null;
  /** 记这一笔的理由，**给人看的原话** */
  reason: string | null;
  /** 发生时刻（毫秒） */
  at: number;
}

/**
 * 欠款流水类型。<b>方向不由它推</b> ——
 * amountMinor 自带符号，靠类型再推一次方向等于把同一件事表达两遍，
 * 而两处迟早会不一致。
 */
export type DebtTxnType =
  | "INCUR"          // 产生：退款追偿追不回来，先记在账上
  | "OFFSET"         // 货款抵扣（自动）
  | "DEPOSIT"        // 保证金抵扣（人工，ADR-022 §3.3）
  | "WRITE_OFF";     // 核销（需审批）

/** Points configuration commands carry stable intent IDs; identity and time come from the server. */
export interface PointsTypeConfig {
  /** 积分类型唯一编码 */
  typeCode: string;
  /** 积分名称，运营端展示名称 */
  name: string;
  /** 市场代码 */
  market: string;
  /** ISO 货币代码 */
  currency: string;
  /** 货币最小单位精度 */
  scale: number;
  /** 积分与最小货币单位的换算比例 */
  rate: {
    /** 换算比例中的积分数量 */
    pointsUnit: number;
    /** 换算比例中的最小货币单位数量 */
    minorUnit: number;
  };
  /** 最大抵扣比例，万分比 */
  deductRatioBp: number;
  /** 账户无活动失效天数 */
  idleDays: number;
  /** 服务端业务状态 */
  status: PointsTypeConfigStatus;
}
export interface PointsChangeResult {
  /** 幂等操作请求号，重试必须复用 */
  requestId: string;
  /** 请求执行的业务动作 */
  action: string;
  /** 积分类型唯一编码 */
  typeCode: string;
  /** 服务端事件时间，Unix 毫秒 */
  at: number;
}
export interface PointsCreate {
  /** 幂等操作请求号，重试必须复用 */
  requestId: string;
  /** 类型创建参数，服务端校验 */
  command: {
    /** 积分类型唯一编码 */
    typeCode: string;
    /** 积分名称 */
    name: string;
    /** ISO 货币代码；省略时使用系统市场币种 */
    currency?: string;
    /** 每 minorUnit 个最小货币单位可兑换 pointsUnit 积分 */
    rate?: { pointsUnit: number; minorUnit: number };
  };
  /** 服务端不可用原因或操作审计原因 */
  reason: string;
}
export interface PointsTypeChange {
  /** 幂等操作请求号，重试必须复用 */
  requestId: string;
  /** 积分类型唯一编码 */
  typeCode: string;
  /** 请求执行的业务动作 */
  action: PointsTypeChangeAction;
  /** 服务端不可用原因或操作审计原因 */
  reason: string;
}
export interface PointsFallback {
  /** 不可变修订版本 */
  revision: number;
  /** 服务端参与模式或已发布回退规则模式 */
  mode: PointsFallbackMode;
  /** 回退规则配置值 */
  value: number;
}
export interface PointsFallbackChange extends Omit<PointsFallback, "revision"> {
  /** 幂等操作请求号，重试必须复用 */
  requestId: string;
  /** 积分类型唯一编码 */
  typeCode: string;
  /** 调用方预期修订号 */
  expectedRevision: number;
  /** 服务端不可用原因或操作审计原因 */
  reason: string;
}
export type PointsScope = "GLOBAL" | "REGION" | "TYPE" | "SCENE" | "CLIENT" | "MERCHANT" | "STORE" | "USER";
export type PointsFlow = "EARN" | "REDEEM" | "BOTH";
export interface PointsSwitchChange {
  /** 幂等操作请求号，重试必须复用 */
  requestId: string;
  /** 开关或统计的作用范围 */
  scope: PointsScope;
  /** 积分类型唯一编码 */
  typeCode: string;
  /** 范围对象编号，门店范围使用 storeNo */
  subjectNo: string;
  /** 是否允许新增对应业务 */
  enabled: boolean;
  /** 控制积分发放、核销或两个方向 */
  flow: PointsFlow;
  /** 服务端不可用原因或操作审计原因 */
  reason: string;
}
export interface PointsSwitchView extends Omit<PointsSwitchChange, "requestId"> {
  /** 持久记录主键 */
  id: number;
  /** 服务端取得的操作人编号 */
  actor: string;
  /** 服务端事件时间，Unix 毫秒 */
  at: number;
}
export interface PointsCursor {
  /** 积分类型唯一编码 */
  typeCode: string;
  /** 起始游标，首次为零 */
  after?: number;
  /** 本次请求的记录上限 */
  limit?: number;
}
export interface PointsSwitchPage {
  /** 本页记录 */
  rows: PointsSwitchView[];
  /** 下一页游标 */
  next: number;
  /** 是否还有后续记录 */
  hasMore: boolean;
}
export interface PointsTerms {
  /** 当前版本号，变更按版本校验 */
  version: string;
  /** 发布条款的内容摘要 */
  hash: string;
  /** 发布条款正文 */
  text: string;
}
export interface PointsTermsChange extends Omit<PointsTerms, "hash"> {
  /** 幂等操作请求号，重试必须复用 */
  requestId: string;
  /** 积分类型唯一编码 */
  typeCode: string;
  /** 服务端不可用原因或操作审计原因 */
  reason: string;
}
export interface PointsAgreement {
  /** 门店接受协议编号 */
  agreementNo: string;
  /** 积分类型唯一编码 */
  typeCode: string;
  /** 门店归属主体编号，不作为积分隔离维度 */
  merchantNo: string;
  /** 积分业务隔离的门店编号 */
  storeNo: string;
  /** 签署该门店协议的主体属主 */
  signedBy: string;
  /** 接受的条款版本 */
  termsVersion: string;
  /** 接受条款的内容摘要 */
  termsHash: string;
  /** 接受时冻结的条款正文 */
  termsText: string;
  /** 门店按单或按期结算方式 */
  settleMode: string;
  /** 冻结的直连或归集资金路径 */
  path: string;
  /** 冻结的支付通道编码 */
  channel: string;
  /** 冻结的收款账户版本 */
  accountVersion: string;
  /** 服务端处理状态 */
  state: string;
}
export interface PointsAgreementPage {
  /** 本页记录 */
  rows: {
    /** 持久记录主键 */
    id: number;
    /** 服务端冻结的门店协议快照 */
    agreement: PointsAgreement;
    /** 服务端处理状态 */
    state: string;
    /** 当前版本号，变更按版本校验 */
    version: number;
  }[];
  /** 下一页游标 */
  next: number;
  /** 是否还有后续记录 */
  hasMore: boolean;
}

export interface PointsOperation {
  /** 幂等操作请求号，重试必须复用 */
  requestId: string;
  /** 服务端接受的操作类型 */
  operation: string;
  /** 积分类型唯一编码 */
  typeCode: string;
  /** 原业务引用编号 */
  reference: string;
  /** 服务端事件时间，Unix 毫秒 */
  at: number;
}
export type PointsPoolAction = "TOPUP_U" | "TOPUP_B" | "TRANSFER_U_TO_B" | "WITHDRAW_U" | "WITHDRAW_B";
export interface PointsFundsTarget {
  /** 冻结的直连或归集资金路径 */
  path: PointsFundsTargetPath;
  /** 冻结的支付通道编码 */
  channel: string;
  /** 门店归属主体编号，不作为积分隔离维度 */
  merchantNo: string;
  /** 积分业务隔离的门店编号 */
  storeNo: string;
  /** 冻结的收款账户版本 */
  accountVersion: string;
  /** 原资金路由的通道子商户号 */
  subMerchantId: string;
  /** 核验后的通道交易号 */
  tradeNo: string | null;
  /** 原商户支付请求号 */
  outTradeNo: string | null;
  /** 订单金额，最小货币单位 */
  orderTotalMinor: number;
}
export interface PointsSubmission {
  /** 幂等操作请求号，重试必须复用 */
  requestId: string;
  /** 原资金或申请请求号 */
  requestNo: string;
  /** 积分类型唯一编码 */
  typeCode: string;
  /** 请求执行的业务动作 */
  action: PointsPoolAction;
  /** 本次资金金额，最小货币单位 */
  amountMinor: number;
  /** 已核验的资金证据编号 */
  evidenceNo: string | null;
  /** 冻结的原交易及资金路由 */
  fundsContext: PointsFundsTarget | null;
  /** 服务端不可用原因或操作审计原因 */
  reason: string;
}
export interface PointsRequestReview {
  /** 幂等操作请求号，重试必须复用 */
  requestId: string;
  /** 积分类型唯一编码 */
  typeCode: string;
  /** 原资金或申请请求号 */
  requestNo: string;
  /** 调用方预期版本号 */
  expectedVersion: number;
  /** 是否批准原申请 */
  approve: boolean;
  /** 服务端不可用原因或操作审计原因 */
  reason: string;
}
export interface PointsRequestView {
  /** 持久记录主键 */
  id: number;
  /** 原资金或申请请求号 */
  requestNo: string;
  /** 积分类型唯一编码 */
  typeCode: string;
  /** 请求执行的业务动作 */
  action: PointsPoolAction;
  /** 本次资金金额，最小货币单位 */
  amountMinor: number;
  /** 服务端处理状态 */
  state: string;
  /** 原申请人编号 */
  submittedBy: string;
  /** 服务端审核人编号 */
  approvedBy: string | null;
  /** 是否已有合格资金证据 */
  hasEvidence: boolean;
  /** 持久资金指令编号 */
  ticketNo: string | null;
  /** 当前版本号，变更按版本校验 */
  version: number;
}
export interface PointsRequestPage {
  /** 本页记录 */
  rows: PointsRequestView[];
  /** 下一页游标 */
  next: number;
  /** 是否还有后续记录 */
  hasMore: boolean;
}
export interface PointsPlan {
  /** 周期计划编号 */
  planNo: string;
  /** 积分类型唯一编码 */
  typeCode: string;
  /** 请求执行的业务动作 */
  action: PointsPlanAction;
  /** 本次资金金额，最小货币单位 */
  amountMinor: number;
  /** 计划锚点时间，Unix 毫秒 */
  anchor: string;
  /** 周期天数 */
  everyDays: number;
  /** 是否允许新增对应业务 */
  enabled: boolean;
}
export interface PointsPlanChange {
  /** 幂等操作请求号，重试必须复用 */
  requestId: string;
  /** 周期计划配置 */
  plan: PointsPlan;
  /** 调用方预期版本号 */
  expectedVersion: number;
  /** 服务端不可用原因或操作审计原因 */
  reason: string;
}
export interface PointsPlanGenerate {
  /** 幂等操作请求号，重试必须复用 */
  requestId: string;
  /** 积分类型唯一编码 */
  typeCode: string;
  /** 本次请求的记录上限 */
  limit: number;
  /** 服务端不可用原因或操作审计原因 */
  reason: string;
}
export interface PointsPlanCancellation {
  /** 幂等操作请求号，重试必须复用 */
  requestId: string;
  /** 积分类型唯一编码 */
  typeCode: string;
  /** 周期计划编号 */
  planNo: string;
  /** 本次生成的周期序号 */
  period: string;
  /** 调用方预期版本号 */
  expectedVersion: number;
  /** 服务端不可用原因或操作审计原因 */
  reason: string;
}
export interface PointsPlanReceipt extends PointsPlanCancellation {
  /** 已核验的资金证据编号 */
  evidenceNo: string;
}
export interface PointsPlanView extends PointsPlan {
  /** 持久记录主键 */
  id: number;
  /** 本次生成的周期序号 */
  period: string;
  /** 下一待生成周期序号 */
  nextPeriod: string | null;
  /** 服务端处理状态 */
  state: string;
  /** 原资金或申请请求号 */
  requestNo: string | null;
  /** 当前版本号，变更按版本校验 */
  version: number;
}
export interface PointsPlanPage {
  /** 本页记录 */
  rows: PointsPlanView[];
  /** 下一页游标 */
  next: number;
  /** 是否还有后续记录 */
  hasMore: boolean;
}
export interface PointsWithdrawal {
  /** 幂等操作请求号，重试必须复用 */
  requestId: string;
  /** 积分类型唯一编码 */
  typeCode: string;
  /** 持久资金指令编号 */
  ticketNo: string;
  /** 服务端不可用原因或操作审计原因 */
  reason: string;
}
export interface PointsExecution {
  /** 持久资金指令编号 */
  ticketNo: string;
  /** 服务端业务状态 */
  status: PointsExecutionStatus;
  /** 已核验入账金额，最小货币单位 */
  confirmedMinor: number;
  /** 是否返回原请求的幂等结果 */
  replayed: boolean;
}

export interface PointsStatementSnapshot {
  /** 门店结算单编号 */
  statementNo: string;
  /** 本次生成的周期序号 */
  period: {
    /** 积分类型唯一编码 */
    typeCode: string;
    /** 积分业务隔离的门店编号 */
    storeNo: string;
    /** 统计或结算起始时间，Unix 毫秒 */
    fromMillis: number;
    /** 统计或结算截止时间，Unix 毫秒 */
    toMillis: number;
    /** 原资金路由唯一键 */
    routeKey: string | null;
  };
  /** 本次结算引用的原持仓编号集合 */
  positionIds: string[];
  /** 结算单不可变修订记录 */
  revisions: {
    /** 修订序号 */
    number: number;
    /** 本修订包含的原持仓结算行 */
    lines: {
      /** 原持仓编号 */
      positionNo: string;
      /** 引用的原持仓版本 */
      sourceVersion: number;
      /** 积分数量 */
      points: number;
    }[];
    /** 积分数量 */
    points: number;
    /** 服务端取得的操作人编号 */
    actor: string;
    /** 服务端不可用原因或操作审计原因 */
    reason: string | null;
  }[];
  /** 服务端处理状态 */
  state: string;
  /** 争议原因，未争议时为空 */
  disputeReason: string | null;
}
export interface PointsStatementPage {
  /** 本页记录 */
  rows: {
    /** 持久记录主键 */
    id: number;
    /** 服务端冻结的账务或准备快照 */
    snapshot: PointsStatementSnapshot;
    /** 当前版本号，变更按版本校验 */
    version: number;
  }[];
  /** 下一页游标 */
  next: number;
  /** 是否还有后续记录 */
  hasMore: boolean;
}
export interface PointsStatementDraft {
  /** 幂等操作请求号，重试必须复用 */
  requestId: string;
  /** 积分类型唯一编码 */
  typeCode: string;
  /** 积分业务隔离的门店编号 */
  storeNo: string;
  /** 统计或结算起始时间，Unix 毫秒 */
  fromMillis: number;
  /** 统计或结算截止时间，Unix 毫秒 */
  toMillis: number;
  /** 服务端不可用原因或操作审计原因 */
  reason: string;
}
export interface PointsStatementChange {
  /** 幂等操作请求号，重试必须复用 */
  requestId: string;
  /** 积分类型唯一编码 */
  typeCode: string;
  /** 积分业务隔离的门店编号 */
  storeNo: string;
  /** 门店结算单编号 */
  statementNo: string;
  /** 不可变修订版本 */
  revision: number;
  /** 请求执行的业务动作 */
  action: PointsStatementChangeAction;
  /** 服务端不可用原因或操作审计原因 */
  reason: string;
}
export interface PointsReference {
  /** 积分类型唯一编码 */
  typeCode: string;
  /** 积分业务隔离的门店编号 */
  storeNo: string;
  /** 原业务引用编号 */
  reference: string;
}
export interface PointsRevision extends PointsReference {
  /** 幂等操作请求号，重试必须复用 */
  requestId: string;
  /** 调用方预期版本号 */
  expectedVersion: number;
  /** 服务端不可用原因或操作审计原因 */
  reason: string;
}
export interface PointsRefundPreparation {
  /** 退款准备编号 */
  preparationNo: string;
  /** 积分类型唯一编码 */
  typeCode: string;
  /** 不可变修订版本 */
  revision: number;
  /** 原准备输入的不可变摘要 */
  inputHash: string;
  /** 本次退回已使用积分 */
  returnedPoints: number;
  /** 本次收回待激活积分 */
  reclaimedPending: number;
  /** 本次收回可用积分 */
  reclaimedAvailable: number;
  /** 原承诺要求收回的积分总数 */
  expectedClawbackPoints: number;
  /** 现金退款中抵销的金额，最小货币单位 */
  cashOffsetMinor: number;
  /** 最终需退现金金额，最小货币单位 */
  netCashMinor: number;
  /** 按授权豁免的补收积分 */
  waivedPoints: number;
  /** 原补收资金指令，未生成时为空 */
  collectionTicket: string | null;
  /** 服务端处理状态 */
  state: string;
}
export interface PointsRefundView {
  /** 积分类型唯一编码 */
  typeCode: string;
  /** 积分业务隔离的门店编号 */
  storeNo: string;
  /** 原售后尝试编号 */
  afterSaleNo: string;
  /** 门店子订单编号 */
  subOrderNo: string;
  /** 服务端处理状态 */
  state: string;
  /** 当前版本号，变更按版本校验 */
  version: number;
  /** 本次售后冻结的退款准备 */
  preparation: PointsRefundPreparation;
}
export interface PointsReceivableRecovery {
  /** 幂等操作请求号，重试必须复用 */
  requestId: string;
  /** 积分类型唯一编码 */
  typeCode: string;
  /** 积分业务隔离的门店编号 */
  storeNo: string;
  /** 门店应收责任编号 */
  receivableNo: string;
  /** 调用方预期版本号 */
  expectedVersion: number;
  /** 本次资金金额，最小货币单位 */
  amountMinor: number;
  /** 服务端不可用原因或操作审计原因 */
  reason: string;
}
export type PointsRecoveryOperation = "PAYOUT" | "TRANSFER" | "REFUND_COLLECTION" | "REFUND_COMPENSATION";
export interface PointsFundsReference extends PointsReference {
  /** 服务端接受的操作类型 */
  operation: PointsRecoveryOperation;
}
export interface PointsFundsView extends PointsFundsReference {
  /** 服务端处理状态 */
  state: string;
  /** 当前版本号，变更按版本校验 */
  version: number;
  /** 原退款金额，最小货币单位 */
  grossMinor: number;
  /** 已抵销金额，最小货币单位 */
  offsetMinor: number;
  /** 实际现金退款金额，最小货币单位 */
  cashMinor: number;
}
export interface PointsRecovery extends PointsFundsReference {
  /** 幂等操作请求号，重试必须复用 */
  requestId: string;
  /** 调用方预期版本号 */
  expectedVersion: number;
  /** 服务端不可用原因或操作审计原因 */
  reason: string;
}
export interface PointsRecoveryExecution {
  /** 原业务引用编号 */
  reference: string;
  /** 服务端业务状态 */
  status: PointsExecutionStatus;
}
export interface PointsEventView {
  subOrderNo?: string | null;
  reason?: string | null;
  /** 持久记录主键 */
  id: number;
  /** 源事实事件编号 */
  eventNo: string;
  /** 原业务持仓或事件种类 */
  kind: string;
  /** 服务端处理状态 */
  state: string;
  /** 原事件投递次数 */
  attempt: number;
  /** 下一允许重试时间，Unix 毫秒 */
  nextAttempt: number;
  /** 当前版本号，变更按版本校验 */
  version: number;
}
export interface PointsEventPage {
  /** 本页记录 */
  rows: PointsEventView[];
  /** 下一页游标 */
  next: number;
  /** 是否还有后续记录 */
  hasMore: boolean;
}
export interface PointsEventRetry {
  /** 幂等操作请求号，重试必须复用 */
  requestId: string;
  /** 积分类型唯一编码 */
  typeCode: string;
  /** 持久源事件主键 */
  eventId: number;
  /** 调用方预期版本号 */
  expectedVersion: number;
  /** 服务端不可用原因或操作审计原因 */
  reason: string;
}

export type PointsHolder = "MINT" | "ISSUE" | "USER" | "STORE" | "RECEIVE";
export type PointsBucket = "ISSUED" | "ISSUABLE" | "PENDING" | "AVAILABLE" | "HELD" | "REFUND_RESERVED" | "RECIRCULATABLE";
export type PointsPositionKind = "GRANT" | "REDEEM" | "REFUND" | "PAYOUT" | "STATEMENT" | "TRANSFER";
export interface PointsAccountQ {
  /** 积分类型唯一编码 */
  typeCode: string;
  /** 账本持有人类型 */
  holderType: PointsHolder;
  /** 账本持有人编号，与持有人类型联合定位 */
  holderNo: string;
}
export interface PointsAccountsQ extends PointsCursor {
  /** 积分类型唯一编码，用于限定账户账本。 */
  typeCode: string;
  /** 账户持有人类别：会员或门店。 */
  holderType: PointsHolder;
  /** 按精确编号筛选账户；省略时返回该类型的账户列表。 */
  holderNo?: string;
}
export interface PointsAccountView {
  /** 服务端冻结的账务或准备快照 */
  snapshot: PointsAccountQ & {
    /** 按账本分桶保存的积分余额 */
    buckets: Partial<Record<PointsBucket, number>>;
    /** 是否冻结该账户新增使用 */
    frozen: boolean;
    /** 服务端计算的过期时间，Unix 毫秒；无则为空 */
    expireAt: number | null;
    /** 账户失效计划修订号 */
    expiryRevision: number;
    /** 最近有效账务活动时间，Unix 毫秒 */
    lastActiveAt: number;
  };
  /** 账户是否已经创建，未创建不代表查询失败 */
  exists: boolean;
  /** 当前版本号，变更按版本校验 */
  version: number;
}
export interface PointsFreeze {
  /** 幂等操作请求号，重试必须复用 */
  requestId: string;
  /** 积分类型唯一编码 */
  typeCode: string;
  /** 被操作用户编号 */
  userNo: string;
  /** 调用方预期版本号 */
  expectedVersion: number;
  /** 是否冻结该账户新增使用 */
  frozen: boolean;
  /** 服务端不可用原因或操作审计原因 */
  reason: string;
}
export interface PointsVoucherView {
  /** 持久记录主键 */
  id: number;
  /** 原记账凭证编号 */
  voucherNo: string;
  /** 原业务引用号 */
  bizRef: string;
  /** ISO 货币代码 */
  currency: string;
  /** 换算比例的积分数量 */
  ratePoints: number;
  /** 换算比例的最小货币单位数量 */
  rateMinor: number;
  /** 原凭证创建时间，Unix 毫秒 */
  createdAt: number;
}
export type PointsTransactionDirection = "EARN" | "REDEEM" | "REVERSAL";
export interface PointsTransactionsQ extends PointsCursor {
  /** 积分类型唯一编码。 */
  typeCode: string;
  /** 流水方向：发放、核销或冲正。 */
  direction: PointsTransactionDirection;
  /** 查询区间起始时间，ISO-8601 格式。 */
  from: string;
  /** 查询区间结束时间，ISO-8601 格式。 */
  to: string;
  /** 门店编号，可选的精确筛选条件。 */
  storeNo?: string;
  /** 会员编号，可选的精确筛选条件。 */
  userNo?: string;
}
export interface PointsTransactionView {
  /** 该凭证对应的业务方向。 */
  direction: PointsTransactionDirection;
  /** 本次积分业务的凭证及创建时间、比例快照。 */
  voucher: PointsVoucherView;
  /** 关联门店编号；无法归属门店时为空。 */
  storeNo: string | null;
  /** 关联会员编号；不涉及会员时为空。 */
  userNo: string | null;
  /** 本凭证所有用户分录的净积分变化；可能为零，需查看详情理解分录。 */
  pointsDelta: number;
}
export interface PointsCursorPage<T> {
  /** 本页记录 */
  rows: T[];
  /** 下一页游标 */
  next: number;
  /** 是否还有后续记录 */
  hasMore: boolean;
}
export interface PointsEntryView {
  /** 持久记录主键 */
  id: number;
  /** 不可变积分分录编号 */
  entryNo: string;
  /** 原记账凭证编号 */
  voucherNo: string;
  /** 账本持有人类型 */
  holderType: PointsHolder;
  /** 账本持有人编号，与持有人类型联合定位 */
  holderNo: string;
  /** 积分账本或资金池分桶 */
  bucket: PointsBucket;
  /** 该分录积分变化，负值表示扣减 */
  deltaPoints: number;
  /** 该分录完成后的积分余额 */
  afterPoints: number;
  /** 分录创建时间，Unix 毫秒 */
  at: number;
}
export interface PointsFlowView {
  /** 持久记录主键 */
  id: number;
  /** 不可变资金池流水号 */
  flowNo: string;
  /** 原记账凭证编号 */
  voucherNo: string;
  /** 积分账本或资金池分桶 */
  bucket: PointsFlowViewBucket;
  /** 该流水金额变化，最小货币单位 */
  deltaMinor: number;
  /** 该流水完成后金额，最小货币单位 */
  afterMinor: number;
}
export interface OrderPointsIntegrationQ {
  /** 积分业务隔离的门店编号 */
  storeNo: string;
  /** 线上或线下交易场景 */
  scene: OrderPointsIntegrationQScene;
}
export interface OrderPointsIntegrationPolicy extends OrderPointsIntegrationQ {
  /** 服务端参与模式或已发布回退规则模式 */
  mode: OrderPointsIntegrationPolicyMode;
  /** 是否接收新的返分承诺 */
  earnEnabled: boolean;
  /** 是否接收新的抵扣承诺 */
  redeemEnabled: boolean;
  /** 当前版本号，变更按版本校验 */
  version: number;
  /** 服务端取得的操作人编号 */
  actor: string | null;
  /** 服务端不可用原因或操作审计原因 */
  reason: string | null;
  /** 服务端配置变更时间，Unix 毫秒 */
  changedAt: number;
}
export interface OrderPointsIntegrationChange extends OrderPointsIntegrationQ {
  /** 幂等操作请求号，重试必须复用 */
  requestId: string;
  /** 服务端参与模式或已发布回退规则模式 */
  mode: OrderPointsIntegrationPolicyMode;
  /** 是否接收新的返分承诺 */
  earnEnabled: boolean;
  /** 是否接收新的抵扣承诺 */
  redeemEnabled: boolean;
  /** 调用方预期版本号 */
  expectedVersion: number;
  /** 服务端不可用原因或操作审计原因 */
  reason: string;
}
export interface OrderPointsInspection {
  /** 门店子订单编号 */
  subOrderNo: string;
  /** 源绑定当前阶段，无绑定为空 */
  bindingPhase: string | null;
  /** 源绑定版本，无绑定为空 */
  bindingVersion: number;
  /** 下单工作流阶段，无记录为空 */
  placementPhase: string | null;
  /** 下单工作流版本，无记录为空 */
  placementVersion: number;
  /** 原积分占用编号，不生成新编号替换 */
  originalHoldNo: string | null;
  /** 该子单所有售后执行阶段 */
  refundPhases: string[];
  /** 尚未确认送达的源事实数量 */
  pendingSourceEvents: number;
  /** 原支付证据是否核验成功 */
  paymentVerified: boolean;
  /** 只读一致性差异项，不返回敏感正文 */
  differences: string[];
}
export interface OrderPointsManualRefundPage {
  /** 本页记录 */
  rows: {
    /** 原售后尝试编号 */
    afterSaleNo: string;
    /** 门店子订单编号 */
    subOrderNo: string;
    /** 服务端创建时间，Unix 毫秒 */
    createdAt: number;
  }[];
  /** 下一页游标 */
  next: string | null;
  /** 是否还有后续记录 */
  hasMore: boolean;
}
export interface OrderPointsRefundActionResult {
  /** 原售后尝试编号 */
  afterSaleNo: string;
  /** 门店子订单编号 */
  subOrderNo: string;
  /** 服务端业务状态 */
  status: string;
}
export interface PointsPositionQ {
  /** 积分类型唯一编码 */
  typeCode: string;
  /** 原业务持仓或事件种类 */
  kind: PointsPositionKind;
  /** 原业务引用编号 */
  reference: string;
}
export interface PointsPositionView extends PointsPositionQ {
  /** 服务端处理状态 */
  state: string;
  /** 原持仓积分总数 */
  originalPoints: number;
  /** 该持仓已处理积分数 */
  processedPoints: number;
  /** 当前版本号，变更按版本校验 */
  version: number;
}
export interface PointsStatsQ {
  /** 积分类型唯一编码 */
  typeCode: string;
  /** 业务统计日期，YYYY-MM-DD */
  day: string;
  /** 开关或统计的作用范围 */
  scope?: PointsStatsQScope;
  /** 范围对象编号，门店范围使用 storeNo */
  subjectNo?: string;
}
export interface PointsStats {
  /** 积分类型唯一编码 */
  typeCode: string;
  /** 业务统计日期，YYYY-MM-DD */
  day: string;
  /** 统计范围对象编号 */
  scopeNo: string;
  /** 服务端账本计算的指标值 */
  metrics: Record<string, number>;
  /** 本轮跳过原因及数量 */
  skipReasons: Record<string, number>;
  /** 统计已处理的最大凭证主键 */
  throughVoucherId: number;
  /** 服务端统计重建时间，Unix 毫秒 */
  rebuiltAt: number;
}
export interface PointsStatsPeriod {
  /** 积分类型唯一编码。 */
  typeCode: string;
  /** 统计周期起始日期，YYYY-MM-DD。 */
  from: string;
  /** 统计周期结束日期，YYYY-MM-DD。 */
  to: string;
  /** 周期内由服务端聚合的指标。 */
  metrics: Record<string, number>;
  /** 统计数据可用的日期数量。 */
  availableDays: number;
  /** 统计数据缺失的日期数量。 */
  missingDays: number;
}
export interface PointsStatsRebuild {
  /** 幂等操作请求号，重试必须复用 */
  requestId: string;
  /** 积分类型唯一编码 */
  typeCode: string;
  /** 业务统计日期，YYYY-MM-DD */
  day: string;
  /** 服务端不可用原因或操作审计原因 */
  reason: string;
}
export interface PointsReconRun {
  /** 幂等操作请求号，重试必须复用 */
  requestId: string;
  /** 积分类型唯一编码 */
  typeCode: string;
  /** 服务端不可用原因或操作审计原因 */
  reason: string;
}
export interface PointsRecon {
  /** 积分类型唯一编码 */
  typeCode: string;
  /** 服务端一致性检查时间，Unix 毫秒 */
  checkedAt: number;
  /** 本轮有界检查是否完整 */
  complete: boolean;
  /** 只读一致性差异项，不返回敏感正文 */
  differences: {
    /** 服务端差异分类代码 */
    code: string;
    /** 原业务引用编号 */
    reference: string;
    /** 按原承诺计算的预期数量 */
    expected: number;
    /** 实际已记录数量 */
    actual: number;
  }[];
}
export type PointsEntriesQ = PointsCursor & ({ holderType: PointsHolder; holderNo: string; voucherNo?: never } | { voucherNo: string; holderType?: never; holderNo?: never });

export interface OrderPointsStoreObligations {
  /** 核验是否完整可用；false 时禁止按零义务判断 */
  known: boolean;
  /** 仍处于 ACTIVE 的场景策略数量 */
  activePolicies: number;
  /** 尚未关闭的源订单绑定数量 */
  openBindings: number;
  /** 源端义务是否全部结清 */
  sourceSettled: boolean;
  /** 支付侧义务摘要，不可用时为 null */
  pay: {
    /** 核验是否完整可用；false 时禁止按零义务判断 */
    known: boolean;
    /** 未释放的原占用数量 */
    openHolds: number;
    /** 未完成的原承诺数量 */
    pendingPromises: number;
    /** 未完成的原退款数量 */
    openRefunds: number;
    /** 未完成的门店结算单数量 */
    openStatements: number;
    /** 资金结果未知的原指令数量 */
    unknownFunds: number;
    /** 未处理完的原事件数量 */
    pendingEvents: number;
  };
}
