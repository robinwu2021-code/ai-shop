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
/**
 * 积分资金总览。
 *
 * **三个数摆在一起是刻意的** —— 恒等式是「流通中的积分 == 池子里的钱」，
 * 分开看的话，失衡要等到有人主动比对才会发现。
 */
export interface PointsOverview {
  /** 流通中的积分（用户可用 + 待生效） */
  circulatingPoints: number;
  /** 池子余额（分）。与上一个数对不上就是失衡 */
  poolBalanceMinor: number;
  /** 本期兑付（分）：补给商家的钱 */
  periodRedeemMinor: number;
  /**
   * 按通道分的账本。**不能只看总数** ——
   * 账面是一个池子，钱实际分散在两个通道账户；
   * 一个溢一个空的时候，总数仍然是平的。
   */
  byChannel: PoolByChannel[];
}

export interface PoolByChannel {
  market: string;
  payChannel: string;
  balanceMinor: number;
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
  /** 结算基数（分）= 实付 + 平台补贴 + 积分抵扣 */
  grossMinor: number;
  /** 平台佣金（分） */
  commissionMinor: number;
  /** 自提点履约服务费（分） */
  serviceFeeMinor: number;
  /** 实付商家（分） */
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
  invoiceNo: string;
  entityNo: string;
  /** 所属账期 yyyyMM */
  period: string;
  invoiceCode: string;
  invoiceNumber: string;
  invoiceType: string;
  titleName: string;
  titleTaxNo: string;
  amountMinor: number;
  taxAmountMinor: number;
  /** 万分比 */
  taxRate: number;
  invoiceDate?: number | null;
  imageUrl?: string | null;
  /** PENDING / SUBMITTED / VERIFIED / REJECTED */
  status: string;
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
  requestNo: string;
  orderNo: string;
  /** PERSONAL / COMPANY */
  titleType: string;
  title: string;
  taxNo?: string | null;
  email?: string | null;
  amountMinor: number;
  /** PENDING / ISSUED / REJECTED */
  status: string;
  /** 已开出的发票号 */
  invoiceNo?: string | null;
  issuedAt?: number | null;
  rejectReason?: string | null;
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
export interface ReconCoverage {
  /** 渠道账单是否已接入。false 时 note 必须显示给运营 */
  channelBillConnected: boolean;
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
  outcome?: { scanned: number; resolved: number; opened: number; deferred: number } | null;
  coverage: { complete: boolean; note: string };
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
  batchNo: string;
  entityNo: string;
  payChannel: string;
  /** 本批采用的账期规则快照，如 T+1 / WEEKLY */
  settleCycle: string;
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
  billCount: number;
  grossMinor: number;
  netMinor: number;
  /**
   * 对账覆盖面。**SELF_ONLY 时界面要如实标注「仅我方自查」**，
   * 不能显示成「已对账」—— 没有对方账单时那是一句自证的话
   */
  reconScope: "SELF_ONLY" | "BOTH";
  /** 挂起原因，**直接展示给商家的原话**（含具体数字与阈值） */
  blockedReason: string | null;
  blockedAt: number | null;
  /** 挂起时限。超时自动放行并告警 —— 没有时限的挂起等于永久冻结 */
  blockExpireAt: number | null;
  /** 人工放行者；**SYSTEM_TIMEOUT = 超时自动放行**，要单独看 */
  decidedBy: string | null;
  decideRemark: string | null;
}

export type SettleBatchStatus =
  | "DRAFT"          // 开批，正在收单
  | "COLLECTED"      // 截批，本批不再接新单
  | "RECONCILING"    // 三道对账门在跑
  | "BLOCKED"        // 有未处置差异或风控命中，整批挂起
  | "RECONCILED"     // 全过，可放行
  | "RELEASED";      // 已逐单下发指令

/**
 * 商家欠款：退款追不回来时先记在账上，从后续货款里扣。
 *
 * ⚠️ **与保证金方向相反**：保证金是商家的钱（平台代管、将来要退还），
 * 欠款是商家欠平台的。两者不能合成一个数看。
 */
export interface MerchantDebt {
  entityNo: string;
  /** 当前欠款（分），恒 >= 0。0 = 没有欠款 */
  balanceMinor: number;
  txns: DebtTxn[];
}

export interface DebtTxn {
  txnNo: string;
  /** INCUR 产生 / OFFSET 货款抵扣 / DEPOSIT 保证金抵扣 / WRITE_OFF 核销 */
  txnType: "INCUR" | "OFFSET" | "DEPOSIT" | "WRITE_OFF";
  /** **有符号**：产生为正、偿还为负。靠 txnType 推方向等于把方向表达两遍 */
  amountMinor: number;
  /** 变动后余额。对账时逐笔回放用 */
  balanceAfterMinor: number;
  sourceType: string | null;
  /** 源单号。**指不出源头的欠款没法向商家解释** */
  sourceNo: string | null;
  /** OFFSET 时记从哪一批扣的 */
  batchNo: string | null;
  reason: string | null;
  at: number;
}
