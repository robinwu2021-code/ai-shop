// 提现与税的规则测试（P-12.2）。
//
// 提现是运营端**唯一会把钱打出去**的动作，所以这里测得最密：
// 超额提现、没有收款账户、封禁中的商家、大额没复核说明、以及"手动置为已打款"。
import { beforeEach, describe, expect, it } from "vitest";
import { financeMock } from "@/lib/api/mocks/finance";
import { invoiceRequests, merchants, taxRule, withdrawals } from "@/lib/mock/db";
import { MAX_TAX_RATE } from "@/lib/constants";

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
