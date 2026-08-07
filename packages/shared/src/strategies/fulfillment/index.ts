// 履约策略扩展点 —— 按履约方式分发。
// 交易主干不动，新增履约形态只需在此注册一个实现。
import { FULFILLMENT } from "@shared/utils/constants";
import type { FulfillmentType } from "@shared/types";
import type { FulfillmentStrategy } from "./types";
import { pickupFulfillment } from "./pickup";
import { neighborPickupFulfillment } from "./neighbor-pickup";
import { deliveryFulfillment } from "./delivery";
import { expressFulfillment } from "./express";
import { storeVerifyFulfillment } from "./store-verify";
import { appointmentFulfillment } from "./appointment";
import { instantFulfillment } from "./instant";

const REGISTRY: Record<FulfillmentType, FulfillmentStrategy> = {
  [FULFILLMENT.PICKUP]: pickupFulfillment,
  [FULFILLMENT.NEIGHBOR_PICKUP]: neighborPickupFulfillment,
  [FULFILLMENT.DELIVERY]: deliveryFulfillment,
  [FULFILLMENT.EXPRESS]: expressFulfillment,
  [FULFILLMENT.STORE_VERIFY]: storeVerifyFulfillment,
  [FULFILLMENT.APPOINTMENT]: appointmentFulfillment,
  [FULFILLMENT.INSTANT]: instantFulfillment,
};

export function fulfillmentFor(type: FulfillmentType): FulfillmentStrategy {
  return REGISTRY[type] ?? pickupFulfillment;
}

export type {
  FulfillmentStrategy,
  FulfillmentPlan,
  FulfillmentPlanInput,
} from "./types";
export { sixDigitCode, redeemCode } from "./types";
