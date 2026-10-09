// 运单走到第几步。**只此一份** —— 步骤条与摘要行都读它。
//
// 两处各写一个三分支的下场在这个仓库里已经见过几次：一处改了另一处没改，
// 而界面上两句话互相矛盾（步骤条停在「运输中」、摘要行写「派送中」），没有任何报错。

import type { ShipmentTrace } from "../types";

/** 四档步骤。顺序固定 —— 统一状态就这四个，EXCEPTION 不占一档（它可能之后又派送成功） */
export const TRACE_STEPS = ["picked", "transit", "delivering", "signed"] as const;

export type TraceStepKey = (typeof TRACE_STEPS)[number];

/**
 * 走到第几步（0–3）。
 *
 * <p><b>派送中靠高级状态码区分</b>：统一状态把「运输中」与「派送中」并成一档，
 * 而收件人最想知道的恰好是「是不是今天能到」。拿不到状态码时退回第 1 步，不猜。
 */
export function traceStepIndex(trace: Pick<ShipmentTrace, "status" | "nodes">): number {
  const st = trace.status;
  if (st === "DELIVERED") return 3;
  // 后端 2026-10-09 起给出「派件中」这一档（TDD-物流模块 批 3）：有它就不必再猜节点文字
  if (st === "DELIVERING") return 2;
  if (st === "CREATED") return 0;
  if (st === "PICKED_UP") return 0;
  const delivering = (trace.nodes || []).some(
    (n) => (n.text || "").includes("派件") || (n.text || "").includes("派送"),
  );
  return delivering ? 2 : 1;
}

/** 当前这一步的词条键（`trace.step.*`）。摘要行用它，步骤条用 {@link traceStepIndex} */
export function traceStepKey(trace: Pick<ShipmentTrace, "status" | "nodes">): TraceStepKey {
  return TRACE_STEPS[traceStepIndex(trace)]!;
}

/**
 * 异常件不走步骤条：它不在那条线上，硬塞进去会让人以为还在正常运输。
 * 已取消同理 —— 那条线已经断了。
 */
export function traceHasSteps(trace: Pick<ShipmentTrace, "status">): boolean {
  return trace.status !== "EXCEPTION" && trace.status !== "CANCELLED";
}
