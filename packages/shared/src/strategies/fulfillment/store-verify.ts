// 到店核销履约：服务品类不需预约时走这条。出核销码，到店由商家核销。
import type { FulfillmentStrategy } from "./types";
import { sixDigitCode } from "./types";

export const storeVerifyFulfillment: FulfillmentStrategy = {
  plan: () => ({ descKey: "fulfillmentDesc.STORE_VERIFY" }),

  issueCode: sixDigitCode,

  track: (order) => order.timeline,
};
