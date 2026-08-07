// 计价策略扩展点 —— 按品类分发。
// 交易主干（下单/支付/售后/佣金）只有一条，差异全部下沉到这里。
// 接口与共用计算在 ./types，本文件只做注册与分发（避免循环依赖）。
import { CATEGORY_TYPE } from "@shared/utils/constants";
import type { CategoryType } from "@shared/types";
import type { PricingStrategy } from "./types";
import { noFreightPricing } from "./types";
import { fixedPricing } from "./fixed";
import { weighedPricing } from "./weighed";

const REGISTRY: Record<CategoryType, PricingStrategy> = {
  [CATEGORY_TYPE.GOODS]: fixedPricing,
  [CATEGORY_TYPE.FRESH]: weighedPricing,
  // 服务 / 虚拟 / 卡券：按次固定价，无运费、无履约后差价
  [CATEGORY_TYPE.SERVICE]: noFreightPricing,
  [CATEGORY_TYPE.VIRTUAL]: noFreightPricing,
  [CATEGORY_TYPE.CARD]: noFreightPricing,
};

export function pricingFor(type: CategoryType): PricingStrategy {
  return REGISTRY[type] ?? fixedPricing;
}

export type { PricingStrategy, PricingContext } from "./types";
export { baseAmount, earnPointsFor, noFreightPricing } from "./types";
