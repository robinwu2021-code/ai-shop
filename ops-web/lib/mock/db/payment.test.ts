// 支付管理的规则测试（P-4.2）。
//
// 这一域动的是真金白银，所以测的都是「做错会赔钱」的那几条：
// 重复处置 = 二次退款、补单金额可改 = 改错就是多付、关单太快 = 持续制造掉单。
import { beforeEach, describe, expect, it } from "vitest";
import { paymentMock } from "@/lib/api/mocks/payment";
import { closeRule, reconDiffs } from "@/lib/mock/db";
import { MAX_UNPAID_CLOSE_MINUTES, MIN_UNPAID_CLOSE_MINUTES } from "@/lib/constants";

/** 每个用例都从同一份快照开始，避免用例之间互相污染。 */
const snapshot = reconDiffs.map((d) => ({ ...d }));
const ruleSnapshot = { ...closeRule };

beforeEach(() => {
  reconDiffs.splice(0, reconDiffs.length, ...snapshot.map((d) => ({ ...d })));
  Object.assign(closeRule, ruleSnapshot);
});

describe("对账差异处置（P-4.2.1 / 4.2.2）", () => {
  it("处置结论不能为空 —— 没有结论的「已处理」等于没处理", async () => {
    await expect(
      paymentMock.resolveReconDiff({ diffNo: "RC2026080603", resolution: "   " }),
    ).rejects.toThrow(/不能为空/);
  });

  it("渠道有、平台无：必须选补单或退款，不能只写结论", async () => {
    await expect(
      paymentMock.resolveReconDiff({ diffNo: "RC2026080601", resolution: "用户确实付了" }),
    ).rejects.toThrow(/必须选择处置方式/);
  });

  it("补单会生成补单号，且**不接受自定义金额**（金额恒等于渠道实收）", async () => {
    const d = await paymentMock.resolveReconDiff({
      diffNo: "RC2026080601", action: "CREATE_ORDER", resolution: "关单过快导致，按渠道实收补单",
    });
    expect(d.status).toBe("RESOLVED");
    expect(d.recoveredOrderNo).toBeTruthy();
    // 契约里根本没有 amount 入参 —— 能改就一定会有人改错
    expect(Object.keys(d)).not.toContain("recoveredAmount");
  });

  it("非 CHANNEL_ONLY 的差异传了补单/退款要报错，而不是被静默忽略", async () => {
    await expect(
      paymentMock.resolveReconDiff({ diffNo: "RC2026080603", action: "REFUND", resolution: "冲正" }),
    ).rejects.toThrow(/不支持补单\/退款/);
  });

  it("**已处置的不能再处置** —— 重复处置就是二次补单或二次退款", async () => {
    await paymentMock.resolveReconDiff({
      diffNo: "RC2026080602", action: "REFUND", resolution: "订单已取消，原路退回",
    });
    await expect(
      paymentMock.resolveReconDiff({ diffNo: "RC2026080602", action: "REFUND", resolution: "再退一次" }),
    ).rejects.toThrow(/不能重复处置/);
  });

  it("忽略也要写理由，否则下个月同样的差异没人知道为什么放过", async () => {
    await expect(
      paymentMock.ignoreReconDiff({ diffNo: "RC2026080604", resolution: "" }),
    ).rejects.toThrow(/理由/);
    const d = await paymentMock.ignoreReconDiff({
      diffNo: "RC2026080604", resolution: "渠道手续费导致的分位差，金额在容忍范围内",
    });
    expect(d.status).toBe("IGNORED");
    expect(d.resolvedBy).toBeTruthy();
  });

  it("按类型与状态筛选", async () => {
    const open = await paymentMock.listReconDiffs({ status: "PENDING", size: 100 });
    expect(open.records.every((d) => d.status === "PENDING")).toBe(true);
    const chOnly = await paymentMock.listReconDiffs({ type: "CHANNEL_ONLY", size: 100 });
    expect(chOnly.records.every((d) => d.type === "CHANNEL_ONLY")).toBe(true);
  });
});

describe("关单策略（P-4.2.3）", () => {
  it(`关单时限不得少于 ${MIN_UNPAID_CLOSE_MINUTES} 分钟 —— 关得太快就是在制造掉单`, async () => {
    await expect(
      paymentMock.saveCloseRule({ unpaidMinutes: 1, remindBeforeMinutes: 0, autoRefundOnLateCallback: false }),
    ).rejects.toThrow(/不得少于/);
  });

  it(`关单时限不得超过 ${MAX_UNPAID_CLOSE_MINUTES} 分钟，否则库存被长期占住`, async () => {
    await expect(
      paymentMock.saveCloseRule({ unpaidMinutes: 5000, remindBeforeMinutes: 5, autoRefundOnLateCallback: false }),
    ).rejects.toThrow(/不得超过/);
  });

  it("提醒提前量必须小于关单时限，否则提醒发出时订单已经关了", async () => {
    await expect(
      paymentMock.saveCloseRule({ unpaidMinutes: 15, remindBeforeMinutes: 15, autoRefundOnLateCallback: false }),
    ).rejects.toThrow(/必须小于关单时限/);
  });

  it("合法配置落库并留痕", async () => {
    const r = await paymentMock.saveCloseRule({
      unpaidMinutes: 20, remindBeforeMinutes: 5, autoRefundOnLateCallback: true,
    });
    expect(r.unpaidMinutes).toBe(20);
    expect(r.autoRefundOnLateCallback).toBe(true);
    expect(r.updatedBy).toBe("admin");
    // 真落库：重新读一次还是新值（伪实现会在这里露馅）
    expect((await paymentMock.getCloseRule()).unpaidMinutes).toBe(20);
  });
});
