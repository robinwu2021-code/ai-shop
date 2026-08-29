// 提现与税 mock 数据（P-12.2）。
//
// 样本刻意覆盖运营真会卡住的四种情形，不是随机造的数：
// - 正常小额：一路通过
// - 超过可提余额：必须拒
// - 商家没报备收款账户：批了钱也打不出去
// - 大额：要写复核说明
import type { InvoiceRequest, InvoiceTitle, TaxRule, Withdrawal } from "@/lib/types";

export const withdrawals: Withdrawal[] = [
  {
    withdrawNo: "WD901", merchantNo: "M903", merchantName: "邻家便利",
    amount: 128_00, availableBalance: 356_80, bankAccountMasked: "招商银行 ****6612",
    status: "PENDING", appliedAt: "2026-08-05T02:10:00Z",
  },
  {
    withdrawNo: "WD902", merchantNo: "M905", merchantName: "快修家电服务",
    // 超过可提余额：审批时必须被拦下
    amount: 900_00, availableBalance: 420_00, bankAccountMasked: "建设银行 ****3390",
    status: "PENDING", appliedAt: "2026-08-05T03:40:00Z",
  },
  {
    withdrawNo: "WD903", merchantNo: "M901", merchantName: "阿姨家的菜摊",
    // M901 的 settleAccountReady 是 false：没有收款账户，批了也打不出去
    amount: 60_00, availableBalance: 88_50, bankAccountMasked: "—",
    status: "PENDING", appliedAt: "2026-08-05T05:00:00Z",
  },
  {
    withdrawNo: "WD904", merchantNo: "M903", merchantName: "邻家便利",
    // 大额：超过复核阈值，必须写复核说明
    amount: 8_000_00, availableBalance: 12_000_00, bankAccountMasked: "招商银行 ****6612",
    status: "PENDING", appliedAt: "2026-08-04T09:00:00Z",
  },
  {
    withdrawNo: "WD905", merchantNo: "M902", merchantName: "老张水果店",
    amount: 200_00, availableBalance: 640_00, bankAccountMasked: "农业银行 ****7712",
    status: "PAID", appliedAt: "2026-07-28T02:00:00Z",
    decidedAt: "2026-07-28T06:00:00Z", decidedBy: "finance01",
  },
];

export const invoiceRequests: InvoiceRequest[] = [
  {
    invoiceNo: "IV901", merchantNo: "M903", merchantName: "邻家便利",
    period: "2026-07", amount: 3_200_00, settledAmount: 3_200_00,
    titleType: "COMPANY", title: "杭州邻家便利店有限公司", taxNo: "91330100MA2AB1234X",
    status: "PENDING", appliedAt: "2026-08-01T02:00:00Z",
  },
  {
    invoiceNo: "IV902", merchantNo: "M905", merchantName: "快修家电服务",
    // 申请金额高于已结算金额：超出部分就是虚开
    period: "2026-07", amount: 1_800_00, settledAmount: 1_260_00,
    titleType: "COMPANY", title: "杭州快修家电服务有限公司", taxNo: "91330100MA2CD5678Y",
    status: "PENDING", appliedAt: "2026-08-01T03:20:00Z",
  },
  {
    invoiceNo: "IV903", merchantNo: "M902", merchantName: "老张水果店",
    period: "2026-07", amount: 640_00, settledAmount: 980_00,
    // 企业抬头却没填税号
    titleType: "COMPANY", title: "老张水果店", taxNo: null,
    status: "PENDING", appliedAt: "2026-08-02T01:00:00Z",
  },
  {
    invoiceNo: "IV904", merchantNo: "M901", merchantName: "阿姨家的菜摊",
    period: "2026-06", amount: 120_00, settledAmount: 320_00,
    titleType: "PERSONAL", title: "王秀兰", taxNo: null,
    status: "ISSUED", serialNo: "FP20260705001",
    appliedAt: "2026-07-02T02:00:00Z", decidedAt: "2026-07-05T06:00:00Z",
  },
];

/** 个税代扣规则。起征点 800 元、税率 20% —— 与劳务报酬的常见口径对齐。 */
/**
 * 平台开票抬头。**初始五项全空，与后端 `TITLE_DEFAULT` 一致** ——
 * mock 里填一份漂亮的假抬头，就看不出「还没配」这个最常见的真实状态了，
 * 而那恰恰是这一屏要让人一眼发现的东西。
 */
export const invoiceTitle: InvoiceTitle = {
  companyName: "", taxNo: "", address: "", phone: "", bankAccount: "",
};

export const taxRule: TaxRule = {
  threshold: 800_00,
  rate: 2000,
  updatedAt: "2026-06-01T00:00:00Z",
  updatedBy: "finance01",
};
