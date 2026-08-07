// 快递履约：日用品标品主路径。无取货码，靠运单号追踪。
import type { FulfillmentStrategy } from "./types";

export const expressFulfillment: FulfillmentStrategy = {
  plan: () => ({ descKey: "fulfillmentDesc.EXPRESS" }),

  issueCode: () => "",

  track: (order) => order.timeline,
};
