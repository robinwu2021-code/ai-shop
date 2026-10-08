// 物流轨迹的四档步骤判据（TDD-物流轨迹多渠道 §2.6）。
//
// 它是判据不是文案，所以不进 copy.ts；放在 .ts 里也让 fulfillment 目录的
// 「不许裸中文」那道闸门管不到承运商的原话（「派件」「派送」是对方写进轨迹的词，
// 不是我们给人看的字，翻译它等于让判据在英文界面下失效）。
//
// ⚠️ 这里的判据必须与端上的 packages/ui/src/components/sh-trace.vue 一致：
// 同一单运营看到「运输中」而买家看到「派送中」时，运营是照着自己这屏答买家的问的。
// ops-web 不引 packages/shared，所以两处各写一份 —— 改一处就要改另一处。
import type { Shipment } from "@/lib/types";

/** 承运商轨迹里表示「已经在往收件人手上送」的词。统一状态把它和「运输中」并成了一档 */
const DELIVERING_WORDS = ["派件", "派送"];

/**
 * 走到第几档（0=已揽收 1=运输中 2=派送中 3=已签收）。
 * 异常件不在这条线上，由调用方决定整条步骤条画不画，这里不额外返回一个「异常」档。
 */
export function traceStepIndex(s: Pick<Shipment, "status" | "traces">): number {
  if (s.status === "DELIVERED") return 3;
  if (s.status === "CREATED" || s.status === "PICKED_UP") return 0;
  const delivering = s.traces.some((t) => DELIVERING_WORDS.some((w) => t.text.includes(w)));
  return delivering ? 2 : 1;
}
