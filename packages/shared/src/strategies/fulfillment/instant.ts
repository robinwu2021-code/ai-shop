// 即时发放履约：虚拟商品发兑换码、卡券入卡包。无物流、无核销窗口，支付成功即完成。
import type { FulfillmentStrategy } from "./types";
import { redeemCode } from "./types";

export const instantFulfillment: FulfillmentStrategy = {
  plan: () => ({ descKey: "fulfillmentDesc.INSTANT" }),

  issueCode: redeemCode,

  /** 支付成功即发放，订单直接走到完成态 */
  instant: true,

  track: (order) => order.timeline,
};
