// 预约履约：服务品类的到店/上门预约。用户选定时段后出核销码，服务人到点核销。
// 改期与违约金规则在 shared/constants TRADE_RULES 里，端上不硬编码。
import type { FulfillmentStrategy } from "./types";
import { sixDigitCode } from "./types";

export const appointmentFulfillment: FulfillmentStrategy = {
  plan: ({ appointmentAt }) => ({
    appointmentAt,
    descKey: "fulfillmentDesc.APPOINTMENT",
  }),

  issueCode: sixDigitCode,

  track: (order) => order.timeline,
};
