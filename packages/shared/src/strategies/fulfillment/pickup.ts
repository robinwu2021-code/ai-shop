// 到店自提：绑定**商家门店**（PickupPoint.type=STORE），出取货码，商家在履约台核销。
// ADR-005 后语义收敛 —— 送到邻居家那种走 neighbor-pickup，承接方性质不同。
import type { FulfillmentStrategy } from "./types";
import { sixDigitCode } from "./types";

export const pickupFulfillment: FulfillmentStrategy = {
  plan({ pickupNo, communities }) {
    const pk = communities
      ?.flatMap((c) => c.pickups)
      .find((p) => p.pickupNo === pickupNo);
    return {
      pickupNo,
      pickupName: pk?.name,
      descKey: "fulfillmentDesc.PICKUP",
    };
  },

  issueCode: sixDigitCode,

  track: (order) => order.timeline,
};
