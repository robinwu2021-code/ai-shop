// 送货上门：从自提点二次配送到家。一期自提为主、送货上门为辅。
//
// 与快递的区别：快递是跨区物流（有运单号），送货上门是社区内的最后一段，
// 由团长或社区骑手完成，没有运单号，靠取货码在门口交接。
import type { FulfillmentStrategy } from "./types";
import { sixDigitCode } from "./types";

export const deliveryFulfillment: FulfillmentStrategy = {
  plan({ pickupNo, communities }) {
    const pk = communities
      ?.flatMap((c) => c.pickups)
      .find((p) => p.pickupNo === pickupNo);
    return {
      pickupNo,
      pickupName: pk?.name,
      descKey: "fulfillmentDesc.DELIVERY",
    };
  },

  // 送到门口仍需核销码交接，避免放错门
  issueCode: sixDigitCode,

  track: (order) => order.timeline,
};
