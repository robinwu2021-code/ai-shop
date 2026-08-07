// 履约策略的接口与共用工具。独立成文件，避免 index ↔ 实现 的循环依赖。
import type { Community, Order, OrderTimelineNode } from "@shared/types";

export interface FulfillmentPlanInput {
  pickupNo?: string;
  communities?: Community[];
  /** APPOINTMENT：用户选定的预约开始时间戳 */
  appointmentAt?: number;
}

export interface FulfillmentPlan {
  pickupNo?: string;
  pickupName?: string;
  appointmentAt?: number;
  /** 展示给用户的履约说明的 i18n key */
  descKey: string;
}

export interface FulfillmentStrategy {
  plan(input: FulfillmentPlanInput): FulfillmentPlan;
  /** 生成核销码 / 取货码；快递返回空串 */
  issueCode(): string;
  /** 是否在支付成功时立即发放（虚拟商品发码 / 卡券入卡包） */
  readonly instant?: boolean;
  track(order: Order): OrderTimelineNode[];
}

/** 共用：6 位数字取货码 */
export function sixDigitCode(): string {
  return String(Math.floor(100000 + Math.random() * 900000));
}

/** 共用：兑换码 / 卡号（虚拟商品与卡券） */
export function redeemCode(): string {
  const s = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  let out = "";
  for (let i = 0; i < 12; i += 1) out += s[Math.floor(Math.random() * s.length)];
  return out.replace(/(.{4})(.{4})(.{4})/, "$1-$2-$3");
}
