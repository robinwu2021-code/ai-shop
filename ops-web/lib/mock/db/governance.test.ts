// 商家治理的规则测试（P-11.1.2 认证标 / 11.1.3 类目授权 / 11.1.4 违规处置 / 11.1.5 信用档案）。
//
// 测的是「放行了就收不回来」的那几条：给正在毁约的商家挂认证标、
// 撤掉还有在售商品的类目授权、把授权撤空、以及把所有违规都算成毁约。
import { beforeEach, describe, expect, it } from "vitest";
import { merchantMock } from "@/lib/api/mocks/merchant";
import { merchants, skus, violations } from "@/lib/mock/db";
import { MAX_MERCHANT_BREACH } from "@/lib/constants";

const mSnapshot = merchants.map((m) => ({ ...m, categoryCodes: [...m.categoryCodes], qualifications: [...m.qualifications] }));
const vSnapshot = violations.map((v) => ({ ...v }));
const sSnapshot = skus.map((s) => ({ ...s }));

beforeEach(() => {
  merchants.splice(0, merchants.length,
    ...mSnapshot.map((m) => ({ ...m, categoryCodes: [...m.categoryCodes], qualifications: [...m.qualifications] })));
  violations.splice(0, violations.length, ...vSnapshot.map((v) => ({ ...v })));
  skus.splice(0, skus.length, ...sSnapshot.map((s) => ({ ...s })));
});

describe("认证标（P-11.1.2）", () => {
  it("**毁约达上限的商家不能授标** —— 认证标是平台的背书，赔的是平台的信用", async () => {
    const bad = merchants.find((m) => m.breachCount >= MAX_MERCHANT_BREACH)!;
    // 先让它处于可授标的审核状态，确保这次拒绝是毁约次数导致的，而不是状态
    bad.status = "ACTIVE";
    await expect(merchantMock.setMerchantVerified(bad.merchantNo, true)).rejects.toThrow(/毁约次数/);
  });

  it("撤销认证标不受毁约次数限制（要撤的恰恰是这种商家）", async () => {
    const bad = merchants.find((m) => m.breachCount >= MAX_MERCHANT_BREACH)!;
    bad.verified = true;
    const m = await merchantMock.setMerchantVerified(bad.merchantNo, false);
    expect(m.verified).toBe(false);
  });
});

describe("类目授权（P-11.1.3）", () => {
  // 「可授权」的前提是**正常经营**（ACTIVE），不是「审核通过」——
  // 审核状态在申请单上，商家档案上只有经营状态
  const approved = () => merchants.find((m) => m.status === "ACTIVE" && m.qualifications.length)!;

  it("非正常经营的商家不能配授权 —— 封禁中还给他放新类目没有道理", async () => {
    const banned = merchants.find((m) => m.status !== "ACTIVE")
      ?? (await merchantMock.setMerchantStatus(merchants[0]!.merchantNo, "SUSPENDED", "测试封禁"));
    await expect(
      merchantMock.setMerchantAuthCodes({ merchantNo: banned.merchantNo, codes: ["DAILY"], reason: "先配上" }),
    ).rejects.toThrow(/正常经营/);
  });

  it("缺资质的类目授不了", async () => {
    const m = approved();
    /*
     * 挑 PACKAGED_FOOD：它要「仅销售预包装食品备案」，而样本里**没有任何商家**有这张。
     *
     * 此前这里是「有家电维修资质就试 FRESH_VEG，否则试 SERVICE_REPAIR」——
     * 断言的成立依赖于恰好选中哪个商家。一期收敛（V22）停用 SERVICE_REPAIR 之后，
     * 它撞的是「授权码不存在」而不是「资质尚未上传」，测的已经不是这条规则了。
     */
    await expect(
      merchantMock.setMerchantAuthCodes({ merchantNo: m.merchantNo, codes: ["PACKAGED_FOOD"], reason: "扩类目" }),
    ).rejects.toThrow(/尚未上传/);
  });

  it("**不能把授权撤空** —— 商家会静默失去上架能力，要停就走封禁或归档", async () => {
    const m = approved();
    await expect(
      merchantMock.setMerchantAuthCodes({ merchantNo: m.merchantNo, codes: [], reason: "先都撤了" }),
    ).rejects.toThrow(/不能把授权撤空/);
  });

  it("**该类目下还有在售商品的不能撤** —— 撤了架上还挂着那类商品", async () => {
    // M903 有 FRESH_VEG 授权，且 SKU1001（叶菜 → FRESH_VEG）在售
    await expect(
      merchantMock.setMerchantAuthCodes({ merchantNo: "M903", codes: ["DAILY"], reason: "收缩经营范围" }),
    ).rejects.toThrow(/在售商品/);
  });

  it("先下架商品，再撤授权就能通过", async () => {
    for (const s of skus) {
      if (s.merchantNo === "M903" && s.categoryNo.startsWith("CAT11")) s.status = "OFF_SALE";
    }
    const m = await merchantMock.setMerchantAuthCodes({ merchantNo: "M903", codes: ["DAILY"], reason: "收缩经营范围" });
    expect(m.categoryCodes).toEqual(["DAILY"]);
  });

  it("改授权必须写原因", async () => {
    await expect(
      merchantMock.setMerchantAuthCodes({ merchantNo: "M903", codes: ["DAILY", "FRESH_VEG"], reason: " " }),
    ).rejects.toThrow(/原因/);
  });
});

describe("违规处置（P-11.1.4）", () => {
  it("必须写清事实与证据出处", async () => {
    await expect(
      merchantMock.recordViolation({ merchantNo: "M903", type: "SERVICE", action: "WARN", detail: "" }),
    ).rejects.toThrow(/事实/);
  });

  it("**只有毁约计入 breachCount** —— 别的也计，ADR-003 的阈值就失去意义了", async () => {
    const before = merchants.find((m) => m.merchantNo === "M903")!.breachCount;
    await merchantMock.recordViolation({
      merchantNo: "M903", type: "SERVICE", action: "WARN", detail: "配送迟到，工单 #1",
    });
    expect(merchants.find((m) => m.merchantNo === "M903")!.breachCount).toBe(before);

    await merchantMock.recordViolation({
      merchantNo: "M903", type: "BREACH", action: "WARN", detail: "成团后未发货，工单 #2",
    });
    expect(merchants.find((m) => m.merchantNo === "M903")!.breachCount).toBe(before + 1);
  });

  it("封禁走同一张状态机，并真的把商家推到 SUSPENDED", async () => {
    const v = await merchantMock.recordViolation({
      merchantNo: "M903", type: "FAKE_GOODS", action: "SUSPEND", detail: "售假，抽检报告 #3",
    });
    expect(v.action).toBe("SUSPEND");
    expect(merchants.find((m) => m.merchantNo === "M903")!.status).toBe("SUSPENDED");
  });

  it("**已封禁的再封一次要抛错**，而不是静默重复记一条", async () => {
    const banned = merchants.find((m) => m.status === "SUSPENDED")!;
    await expect(
      merchantMock.recordViolation({
        merchantNo: banned.merchantNo, type: "BREACH", action: "SUSPEND", detail: "再封一次",
      }),
    ).rejects.toThrow(/不允许从/);
  });

  it("违规记录按商家查得到，且新记录在最前", async () => {
    await merchantMock.recordViolation({
      merchantNo: "M903", type: "PRICE_FRAUD", action: "LIMIT", detail: "先涨后降，截图 #4",
    });
    const list = await merchantMock.listViolations({ merchantNo: "M903" });
    expect(list[0].type).toBe("PRICE_FRAUD");
    expect(list.every((v) => v.merchantNo === "M903")).toBe(true);
  });
});
