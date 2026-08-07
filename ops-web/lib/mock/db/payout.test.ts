// 提现与税的规则测试（P-12.2）。
//
// 提现是运营端**唯一会把钱打出去**的动作，所以这里测得最密：
// 超额提现、没有收款账户、封禁中的商家、大额没复核说明、以及"手动置为已打款"。
import { beforeEach, describe, expect, it } from "vitest";
import { financeMock } from "@/lib/api/mocks/finance";
import { invoiceRequests, merchants, taxRule, withdrawals } from "@/lib/mock/db";
import { MAX_TAX_RATE, MIN_WITHDRAW_AMOUNT, WITHDRAW_REVIEW_THRESHOLD } from "@/lib/constants";

const wSnapshot = withdrawals.map((w) => ({ ...w }));
const iSnapshot = invoiceRequests.map((i) => ({ ...i }));
const mSnapshot = merchants.map((m) => ({ ...m }));
const tSnapshot = { ...taxRule };

beforeEach(() => {
  withdrawals.splice(0, withdrawals.length, ...wSnapshot.map((w) => ({ ...w })));
  invoiceRequests.splice(0, invoiceRequests.length, ...iSnapshot.map((i) => ({ ...i })));
  merchants.splice(0, merchants.length, ...mSnapshot.map((m) => ({ ...m })));
  Object.assign(taxRule, tSnapshot);
});

describe("提现审批（P-12.2.1）", () => {
  it("**通过后落 APPROVED 而不是 PAID** —— 打款结果来自渠道回执，手动做平就是在钱没到账时结案", async () => {
    const w = await financeMock.decideWithdrawal({ withdrawNo: "WD901", pass: true });
    expect(w.status).toBe("APPROVED");
    expect(w.decidedBy).toBe("admin");
  });

  it("申请金额超过可提余额要拒绝", async () => {
    await expect(financeMock.decideWithdrawal({ withdrawNo: "WD902", pass: true })).rejects.toThrow(/超过可提余额/);
  });

  it("**没报备分账接收方的不能通过** —— 批了钱也打不出去", async () => {
    await expect(financeMock.decideWithdrawal({ withdrawNo: "WD903", pass: true })).rejects.toThrow(/尚未报备分账接收方/);
  });

  it("封禁中的商家不能通过 —— 解封是另一条链路上的决定", async () => {
    const m = merchants.find((x) => x.merchantNo === "M903")!;
    m.status = "SUSPENDED";
    await expect(financeMock.decideWithdrawal({ withdrawNo: "WD901", pass: true })).rejects.toThrow(/封禁中/);
  });

  it(`超过 ${WITHDRAW_REVIEW_THRESHOLD / 100} 元必须写复核说明 —— 大额是最容易被冒用的口子`, async () => {
    await expect(financeMock.decideWithdrawal({ withdrawNo: "WD904", pass: true })).rejects.toThrow(/复核说明/);
    const w = await financeMock.decideWithdrawal({
      withdrawNo: "WD904", pass: true, remark: "已与商家电话核对账户，录音存档 #2026-0804",
    });
    expect(w.status).toBe("APPROVED");
  });

  it(`低于 ${MIN_WITHDRAW_AMOUNT / 100} 元不能通过 —— 手续费比本金还贵`, async () => {
    const w = withdrawals.find((x) => x.withdrawNo === "WD901")!;
    w.amount = MIN_WITHDRAW_AMOUNT - 1;
    await expect(financeMock.decideWithdrawal({ withdrawNo: "WD901", pass: true })).rejects.toThrow(/不得低于/);
  });

  it("驳回必须写原因（原样回商家 B 端）", async () => {
    await expect(financeMock.decideWithdrawal({ withdrawNo: "WD901", pass: false })).rejects.toThrow(/必须写原因/);
    const w = await financeMock.decideWithdrawal({
      withdrawNo: "WD901", pass: false, remark: "账户户名与主体不一致，请更正后重新申请",
    });
    expect(w.status).toBe("REJECTED");
  });

  it("**已打款的不能再审批** —— 状态机拦住重复放款", async () => {
    await expect(financeMock.decideWithdrawal({ withdrawNo: "WD905", pass: true })).rejects.toThrow(/不允许从/);
  });
});

describe("发票（P-12.2.2）", () => {
  it("**开票金额不得超过该周期已结算金额** —— 超出部分就是虚开", async () => {
    await expect(
      financeMock.issueInvoice({ invoiceNo: "IV902", serialNo: "FP20260801001" }),
    ).rejects.toThrow(/超过该周期已结算金额/);
  });

  it("企业抬头必须有纳税人识别号", async () => {
    await expect(
      financeMock.issueInvoice({ invoiceNo: "IV903", serialNo: "FP20260801002" }),
    ).rejects.toThrow(/纳税人识别号/);
  });

  it("发票流水号不能为空 —— 没有流水号的「已开票」查不到票", async () => {
    await expect(financeMock.issueInvoice({ invoiceNo: "IV901", serialNo: "  " })).rejects.toThrow(/流水号/);
  });

  it("**已开票的不能重复开** —— 重复开票就是重复虚开", async () => {
    await financeMock.issueInvoice({ invoiceNo: "IV901", serialNo: "FP20260801003" });
    await expect(
      financeMock.issueInvoice({ invoiceNo: "IV901", serialNo: "FP20260801004" }),
    ).rejects.toThrow(/不能重复处理/);
  });

  it("驳回要写原因，且驳回后同样不能再开", async () => {
    await expect(financeMock.rejectInvoice({ invoiceNo: "IV902", reason: "" })).rejects.toThrow(/原因/);
    await financeMock.rejectInvoice({ invoiceNo: "IV902", reason: "开票金额超过已结算金额，请按实际金额重新申请" });
    await expect(
      financeMock.issueInvoice({ invoiceNo: "IV902", serialNo: "FP20260801005" }),
    ).rejects.toThrow(/不能重复处理/);
  });
});

describe("个税代扣规则（P-12.2.3）", () => {
  it(`税率不得超过 ${MAX_TAX_RATE / 100}% —— 超过一定是配置错误`, async () => {
    await expect(financeMock.saveTaxRule({ threshold: 80000, rate: MAX_TAX_RATE + 1 })).rejects.toThrow(/税率不得超过/);
  });

  it("起征点不能为负", async () => {
    await expect(financeMock.saveTaxRule({ threshold: -1, rate: 2000 })).rejects.toThrow(/起征点/);
  });

  it("合法配置落库并留痕", async () => {
    const r = await financeMock.saveTaxRule({ threshold: 100000, rate: 1500 });
    expect(r.threshold).toBe(100000);
    expect(r.updatedBy).toBe("admin");
    // 真落库：重新读一次还是新值（伪实现会在这里露馅）
    expect((await financeMock.getTaxRule()).rate).toBe(1500);
  });
});
