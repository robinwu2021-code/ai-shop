import { describe, expect, it } from "vitest";
import { TRACE_STEPS, traceHasSteps, traceStepIndex, traceStepKey }
  from "../src/strategies/trace-step";
import type { ShipmentTrace } from "../src/types";

/**
 * 「走到第几步」现在**只此一份**：步骤条（sh-trace）与订单页的摘要行都读它。
 *
 * <p>抽出来之前这段在组件里，而摘要行是 2026-10-09 新加的 —— 照原样复制一份的话，
 * 两处迟早分叉：步骤条停在「运输中」、同一行摘要写「派送中」，界面自相矛盾且不报错。
 */
const t = (status: string, texts: string[] = []): Pick<ShipmentTrace, "status" | "nodes"> =>
  ({ status, nodes: texts.map((text, i) => ({ at: 1000 - i, text })) } as never);

describe("运单走到第几步", () => {
  it("★★★ 签收与派送中直接由状态定 —— 后端给了这一档就不必再猜节点文字", () => {
    expect(traceStepIndex(t("DELIVERED"))).toBe(3);
    expect(traceStepIndex(t("DELIVERING"))).toBe(2);
    expect(traceStepKey(t("DELIVERED"))).toBe("signed");
    expect(traceStepKey(t("DELIVERING"))).toBe("delivering");
  });

  it("★★★ 状态只说「运输中」时，靠节点文字认出派件 —— 收件人最想知道的就是今天到不到", () => {
    expect(traceStepIndex(t("IN_TRANSIT", ["【深圳市】快件已到达"]))).toBe(1);
    expect(traceStepIndex(t("IN_TRANSIT", ["【深圳市】派件中，请保持电话畅通"]))).toBe(2);
    expect(traceStepIndex(t("IN_TRANSIT", ["快件正在派送途中"]))).toBe(2);
  });

  it("★★ 刚建单与已揽收都停在第一步；拿不到状态码不猜到派送", () => {
    expect(traceStepIndex(t("CREATED"))).toBe(0);
    expect(traceStepIndex(t("PICKED_UP"))).toBe(0);
    expect(traceStepIndex(t("IN_TRANSIT"))).toBe(1);
  });

  it("★★ 异常与已取消不走步骤条 —— 它们不在那条线上，硬塞进去像是还在正常运输", () => {
    expect(traceHasSteps(t("EXCEPTION"))).toBe(false);
    expect(traceHasSteps(t("CANCELLED"))).toBe(false);
    expect(traceHasSteps(t("IN_TRANSIT"))).toBe(true);
  });

  it("★★ 四档的顺序与键名固定 —— 词条是 trace.step.<键>，改了名界面直接露键名", () => {
    expect(TRACE_STEPS).toEqual(["picked", "transit", "delivering", "signed"]);
    for (const s of ["CREATED", "PICKED_UP", "IN_TRANSIT", "DELIVERING", "DELIVERED"]) {
      expect(TRACE_STEPS).toContain(traceStepKey(t(s)));
    }
  });

  it("★★ 没有节点也不许抛 —— 刚登记的运单就是空数组，摘要行照样要能渲染", () => {
    expect(() => traceStepIndex({ status: "IN_TRANSIT", nodes: [] } as never)).not.toThrow();
    expect(traceStepIndex({ status: "IN_TRANSIT" } as never)).toBe(1);
  });
});
