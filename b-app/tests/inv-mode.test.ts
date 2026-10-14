import { describe, expect, it } from "vitest";
import { describeBlockers, describeStocked, invModeLabel } from "../src/shared/inv-mode";

const DICT: Record<string, string> = {
  "stockSettings.blocker.INBOUND": "进货单 {no} 待收货",
  "stockSettings.blocker.RESERVATION": "线上订单待出库",
  "stockSettings.onHand": "{title} 实存 {n}",
  "invMode.on": "记库存",
  "invMode.off": "不记库存",
  "invMode.inherit": "跟随品类（{state}）",
};
const t = (k: string, p: Record<string, unknown> = {}) =>
  (DICT[k] ?? k).replace(/\{(\w+)\}/g, (_, x) => String(p[x]));

describe("记库存开关的弹框文案", () => {
  it("拦住的单据：带商品名与单号，同一张单只说一次", () => {
    const s = describeBlockers(t, [
      { goodsNo: "G1", title: "香梨", onHand: 0, blockers: [
        { kind: "INBOUND", docNo: "PO1" }, { kind: "INBOUND", docNo: "PO1" }] },
      { goodsNo: "G2", title: "青提", onHand: 0, blockers: [{ kind: "RESERVATION", docNo: "L9" }] },
    ]);
    expect(s).toBe("香梨 · 进货单 PO1 待收货；青提 · 线上订单待出库");
  });

  it("还有库存的：逐件写出实存", () => {
    expect(describeStocked(t, [{ goodsNo: "G1", title: "香梨", onHand: 30, blockers: [] }]))
      .toBe("香梨 实存 30");
  });

  it("跟随品类要写出跟到的结果", () => {
    expect(invModeLabel(t, "INHERIT", true)).toBe("跟随品类（记库存）");
    expect(invModeLabel(t, "INHERIT", false)).toBe("跟随品类（不记库存）");
    expect(invModeLabel(t, "OFF", true)).toBe("不记库存");
  });
});
