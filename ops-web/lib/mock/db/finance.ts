// 结算与资金 mock（P-12）。覆盖五种状态与两类失败（可重试 / 已超时），
// 否则「重试上限」与「超时兜底」两条规则在页面上验不到。
import type { BuyerInvoiceRequest, FeeRuleVersion, PurchaseInvoice, Settlement, SplitLog } from "@/lib/types";

/**
 * 结算单：**一个子订单一张**，与后端 `stl_bill` 同形。
 *
 * 此前这里是周期汇总（period / orderCount），而后端从来不是那么结算的 ——
 * 那份 mock 好看但对不上任何真实数据。
 */
export const settlements: Settlement[] = [
  /*
   * ── 自营应付账款三档。**各档都要有** ──
   * 只造「待付款」的话，「票还没到所以付不了」那条分支永远看不见 ——
   * 而它恰恰是这一页最要紧的规则（票到付款）。与上面造两种小微形态同一个理由。
   */
  {
    // ① 待对账：还没人认这个数，付不了也收不了票
    settleNo: "ST9101", subOrderNo: "SUB2026082601", orderNo: "SO2026082601", merchantNo: "M801",
    grossMinor: 128_000, commissionMinor: 6_400, serviceFeeMinor: 1_920, netMinor: 119_680,
    trafficSource: "PLATFORM", commissionRate: 500, status: "PENDING_RECON",
    createdAt: 1_756_166_400_000, storeNo: "ST801", payMerchantNo: null,
    businessMode: "SELF_OPERATED", invoiceStatus: "PENDING_INVOICE",
  },
  {
    // ② 已对账、票还没到 → **点「登记付款」应当被拦**，这一条是这页的主角
    settleNo: "ST9102", subOrderNo: "SUB2026082602", orderNo: "SO2026082602", merchantNo: "M802",
    grossMinor: 96_000, commissionMinor: 4_800, serviceFeeMinor: 1_440, netMinor: 89_760,
    trafficSource: "PLATFORM", commissionRate: 500, status: "CONFIRMED",
    createdAt: 1_756_080_000_000, storeNo: "ST802", payMerchantNo: null,
    businessMode: "SELF_OPERATED", invoiceStatus: "PENDING_INVOICE",
  },
  {
    // ③ 无票供应商：不进发票流程，但**要在列表上标出来** ——
    //    让财务在付款前就看见「这笔付出去是不能列支的」，而不是月末报税才发现
    settleNo: "ST9103", subOrderNo: "SUB2026082603", orderNo: "SO2026082603", merchantNo: "M803",
    grossMinor: 24_000, commissionMinor: 1_200, serviceFeeMinor: 360, netMinor: 22_440,
    trafficSource: "PLATFORM", commissionRate: 500, status: "CONFIRMED",
    createdAt: 1_755_993_600_000, storeNo: "ST803", payMerchantNo: null,
    businessMode: "SELF_OPERATED", invoiceStatus: "NO_INVOICE",
  },
  {
    // 自带客流 → 佣金 0（R16 建议值）
    settleNo: "ST9001", subOrderNo: "SUB2026080501", orderNo: "SO2026080501", merchantNo: "M903",
    grossMinor: 1_780, commissionMinor: 0, serviceFeeMinor: 27, netMinor: 1_753,
    trafficSource: "MERCHANT_OWNED", commissionRate: 0, status: "PENDING",
    createdAt: 1_754_438_400_000, storeNo: "ST001", payMerchantNo: "PM_M903",
    businessMode: "THIRD_PARTY", invoiceStatus: "NO_INVOICE",
  },
  {
    settleNo: "ST9002", subOrderNo: "SUB2026080502", orderNo: "SO2026080502", merchantNo: "M902",
    grossMinor: 3_980, commissionMinor: 199, serviceFeeMinor: 60, netMinor: 3_721,
    trafficSource: "PLATFORM", commissionRate: 500, status: "SPLIT",
    createdAt: 1_754_352_000_000, splitAt: 1_754_355_600_000,
    storeNo: "ST002", payMerchantNo: "PM_M902", businessMode: "THIRD_PARTY",
    invoiceStatus: "NO_INVOICE",
  },
  {
    // 未报备分账接收方：payMerchantNo 为空，发起分账会被拦
    settleNo: "ST9004", subOrderNo: "SUB2026080504", orderNo: "SO2026080504", merchantNo: "M901",
    grossMinor: 41_800, commissionMinor: 2_090, serviceFeeMinor: 627, netMinor: 39_083,
    trafficSource: "PLATFORM", commissionRate: 500, status: "PENDING",
    createdAt: 1_754_438_400_000, storeNo: null, payMerchantNo: null,
    businessMode: "THIRD_PARTY", invoiceStatus: "NO_INVOICE",
  },
  {
    // 自营轨道：走对账→确认→付款，不分账
    settleNo: "ST9006", subOrderNo: "SUB2026080506", orderNo: "SO2026080506", merchantNo: "M905",
    grossMinor: 12_800, commissionMinor: 640, serviceFeeMinor: 0, netMinor: 12_160,
    trafficSource: "PLATFORM", commissionRate: 500, status: "PENDING_RECON",
    createdAt: 1_754_265_600_000, storeNo: "ST005", payMerchantNo: "PM_M905",
    businessMode: "SELF_OPERATED", invoiceStatus: "PENDING_INVOICE",
  },
];

/** 分账指令流水：**失败的也在这里** —— 出问题时要看的恰恰是它们。 */
export const splitRecords: SplitLog[] = [
  { settleNo: "ST9002", subOrderNo: "SUB2026080502", splitAction: "SPLIT", amountMinor: 3_721,
    result: "SUCCESS", requestNo: "SPL-ST9002", providerNo: "STUB-SPL-ST9002",
    message: null, createdAt: 1_754_355_600_000 },
  { settleNo: "ST9001", subOrderNo: "SUB2026080501", splitAction: "SUBSIDY", amountMinor: 200,
    result: "SUCCESS", requestNo: "SUB-ST9001", providerNo: "STUB-SUB-ST9001",
    message: null, createdAt: 1_754_442_000_000 },
  { settleNo: "ST9004", subOrderNo: "SUB2026080504", splitAction: "SPLIT", amountMinor: 39_083,
    result: "FAIL", requestNo: "SPL-ST9004-F1", providerNo: null,
    message: "分账接收方账户状态异常（PSP 返回 ACCOUNT_ABNORMAL）", createdAt: 1_754_442_000_000 },
];

/**
 * 费率版本（后端 stl_fee_rule）。**只增不改**：调费率是插新版本，旧版本永久保留。
 * effectiveFrom = 0 的四条是初始版本，等价于上线前 application.yml 里的两个默认值。
 */
/**
 * 分账超时兜底天数：冻结超过它仍未分账成功，解冻回平台。
 *
 * **不放进费率表**：它不是费率，是结算策略；而且后端至今没有这个配置项。
 * 此前它挂在旧的 `FeeRule` 上，让人以为是可配的 —— 页面上能改，改了没有任何效果。
 * 摆成常量至少诚实：它现在只驱动 mock 里的解冻校验。
 */
export const SETTLE_FREEZE_DAYS = 15;

export const feeRules: FeeRuleVersion[] = [
  { ruleNo: "FR-INIT-TP-OWNED", businessMode: "THIRD_PARTY", trafficSource: "MERCHANT_OWNED",
    rateBp: 0, effectiveFrom: 0, enabled: 1,
    remark: "自带客流零佣金：他带来的客户在别家消费才是平台收益（R16）" },
  { ruleNo: "FR-INIT-TP-PLAT", businessMode: "THIRD_PARTY", trafficSource: "PLATFORM",
    rateBp: 500, effectiveFrom: 0, enabled: 1, remark: "平台客流 5%" },
  { ruleNo: "FR-INIT-SO-OWNED", businessMode: "SELF_OPERATED", trafficSource: "MERCHANT_OWNED",
    rateBp: 0, effectiveFrom: 0, enabled: 1, remark: "自营先与第三方取齐" },
  { ruleNo: "FR-INIT-SO-PLAT", businessMode: "SELF_OPERATED", trafficSource: "PLATFORM",
    rateBp: 500, effectiveFrom: 0, enabled: 1, remark: "自营先与第三方取齐" },
];

/**
 * 进项票。**两种都要有**：抬头对得上的（能核验）与对不上的（不能核验，
 * 而界面要说清是这个原因）。只造能过的话，那条拦截分支永远看不见。
 */
export const purchaseInvoices: PurchaseInvoice[] = [
  {
    invoiceNo: "PI2026080001", entityNo: "M801", period: "202608",
    invoiceCode: "3100213130", invoiceNumber: "00841122", invoiceType: "SPECIAL",
    titleName: "杭州小林果蔬有限公司", titleTaxNo: "91330100MA2xxxxx1A",
    amountMinor: 128_000, taxAmountMinor: 14_690, taxRate: 1300,
    invoiceDate: 1_756_080_000_000, imageUrl: null,
    status: "SUBMITTED", rejectReason: null, titleMatched: true,
    settleNos: ["ST9101"],
  },
  {
    // 抬头对不上 —— 点核验应当被拦，且要说清原因
    invoiceNo: "PI2026080002", entityNo: "M802", period: "202608",
    invoiceCode: "3100213130", invoiceNumber: "00841135", invoiceType: "NORMAL",
    titleName: "杭州小林果蔬商行", titleTaxNo: "91330100MA2xxxxx2B",
    amountMinor: 96_000, taxAmountMinor: 11_010, taxRate: 1300,
    invoiceDate: 1_755_993_600_000, imageUrl: null,
    status: "SUBMITTED", rejectReason: null, titleMatched: false,
    settleNos: ["ST9102"],
  },
];

/** 买家的开票申请。个人抬头与公司抬头各一条 —— 公司的要税号，个人的没有 */
export const buyerInvoiceRequests: BuyerInvoiceRequest[] = [
  {
    requestNo: "IR2026082601", orderNo: "SO2026082611", titleType: "COMPANY",
    title: "杭州某某科技有限公司", taxNo: "91330100MA2yyyyy1C",
    email: "fin@example.com", amountMinor: 45_800, status: "PENDING",
    invoiceNo: null, issuedAt: null, rejectReason: null, createdAt: 1_756_166_400_000,
  },
  {
    requestNo: "IR2026082602", orderNo: "SO2026082612", titleType: "PERSONAL",
    title: "王女士", taxNo: null,
    email: "wang@example.com", amountMinor: 16_800, status: "ISSUED",
    invoiceNo: "0084119922", issuedAt: 1_756_080_000_000, rejectReason: null,
    createdAt: 1_755_993_600_000,
  },
];
