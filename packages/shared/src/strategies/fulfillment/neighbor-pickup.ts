// 邻里自提：货送到**团发起人家里**，参团的邻居上门取（ADR-005 决策 1）。
//
// 与「到店自提」的差别不只是地点，是**承接方的性质**：
//   · 到店自提 → 承接方是入驻商家，常驻点，收履约服务费
//   · 邻里自提 → 承接方是**用户本人**，团粒度（一团一销），**零报酬**
//
// ⚠️ 零报酬这条不能松：一旦承接有报酬，那个邻居就是团长，
// ADR-004 消掉的四个合规问题（分销层级、个税代扣、提现风控、职业化刷单）会原样回来。
// 因此它也只能限定在「自己发起的团」里，不能开放为「任何人可申请做自提点」。
//
// 求团买床垫、校服这类东西本来就没有门店可提 —— 缺了这条策略，求团那条线落不了地。
import type { FulfillmentStrategy } from "./types";
import { sixDigitCode } from "./types";

export const neighborPickupFulfillment: FulfillmentStrategy = {
  plan({ pickupNo, communities }) {
    // 临时点不在社区主数据里（它随团创建、随团消失），所以查不到就用团上带的名字。
    // 这与常驻点的查法不同，是 scope=GROUP_INSTANCE 的必然结果。
    const pk = communities?.flatMap((c) => c.pickups).find((p) => p.pickupNo === pickupNo);
    return {
      pickupNo,
      pickupName: pk?.name,
      descKey: "fulfillmentDesc.NEIGHBOR_PICKUP",
    };
  },

  // 同样出六位取货码，但核销的人是发起人，作用域限该团（E16）
  issueCode: sixDigitCode,

  track: (order) => order.timeline,
};
