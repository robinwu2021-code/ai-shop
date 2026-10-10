// 银行流水导入的 mock（TDD-供应商结算与双轨资金 §10）。
//
// **为什么 mock 也要真的解析**：只回一个「成功 N 条」的假数字，
// 页面上那三类结果（入库 / 跳过 / 失败明细）就永远只看得到一类 ——
// 而它们对应三种完全不同的处置：入库的不用管、跳过的说明这份传过了、
// 失败的要对着原始文件去看那几行。mock 太干净会把界面上最要紧的那块盖住。
import { describe, expect, it } from "vitest";
import { financeMock } from "@/lib/api/mocks/finance";

const HEAD = "交易日期,交易流水号,借贷标志,发生额,对方户名,对方账号,摘要";

/** 每个用例自带前缀：mock 的已导入集合是模块级的，跨用例不会重置 */
function csv(tag: string, rows: string[]) {
  return [HEAD, ...rows.map((r) => r.replace("{T}", tag))].join("\n") + "\n";
}

describe("银行流水导入 · mock", () => {
  it("认得常见列名，页脚合计行不算失败", async () => {
    const r = await financeMock.importBankFlows("9月.csv", csv("A", [
      "2026/9/20,BF-{T}-1,借,1234.50,深圳虹选,6222021234567890123,货款-E001",
      "2026-09-21,BF-{T}-2,贷,1000.00,某某,6222000000002222,退回",
      "合计,,,2234.50",
    ]));
    expect(r.imported).toBe(2);
    expect(r.failed).toBe(0);
    expect(r.skipped).toBe(0);
  });

  it("★ 重复上传同一份：第二次全部跳过，不是报错", async () => {
    const body = csv("B", [
      "2026-09-20,BF-{T}-1,借,10.00,甲,6222000000001111,x",
      "2026-09-20,BF-{T}-2,借,20.00,乙,6222000000002222,y",
    ]);
    expect((await financeMock.importBankFlows("w.csv", body)).imported).toBe(2);

    const again = await financeMock.importBankFlows("w.csv", body);
    expect(again.imported).toBe(0);
    expect(again.skipped).toBe(2);
    expect(again.failed).toBe(0);
  });

  it("★ 坏行报行号，好行照常入账 —— 财务要对着原始文件看那几行", async () => {
    const r = await financeMock.importBankFlows("mixed.csv", csv("C", [
      "2026-09-20,BF-{T}-1,借,10.00,甲,6222000000001111,x",
      "2026-09-20,BF-{T}-bad,借,abc,乙,6222000000002222,y",
      "2026-09-20,BF-{T}-3,借,30.00,丙,6222000000003333,z",
    ]));
    expect(r.imported).toBe(2);
    expect(r.failed).toBe(1);
    // 行号按原始文件数（表头是第 1 行），不是「第几条记录」
    expect(r.failures[0].line).toBe(3);
    expect(r.failures[0].reason).toContain("金额");
  });

  it("看不出收支方向的那一行要失败，而不是替它猜一个", async () => {
    const r = await financeMock.importBankFlows("nodir.csv",
      [HEAD.replace(",借贷标志", ""), "2026-09-20,BF-D-1,88.00,甲,6222000000001111,x"].join("\n"));
    expect(r.imported).toBe(0);
    expect(r.failures[0].reason).toContain("方向");
  });

  it("认不出表头就整份拒绝，不要解析出一堆空行", async () => {
    const r = await financeMock.importBankFlows("x.csv", "这不是流水,随便写的\n1,2\n");
    expect(r.imported).toBe(0);
    expect(r.failed).toBe(1);
    expect(r.failures[0].reason).toContain("表头");
  });
});
