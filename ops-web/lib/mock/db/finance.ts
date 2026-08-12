// 结算与资金 mock（P-12）。覆盖五种状态与两类失败（可重试 / 已超时），
// 否则「重试上限」与「超时兜底」两条规则在页面上验不到。
import type { FeeRuleVersion, Settlement, SplitLog } from "@/lib/types";

/**
 * 结算单：**一个子订单一张**，与后端 `stl_bill` 同形。
 *
 * 此前这里是周期汇总（period / orderCount），而后端从来不是那么结算的 ——
 * 那份 mock 好看但对不上任何真实数据。
 */
export const settlements: Settlement[] = [
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
