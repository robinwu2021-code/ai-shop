// 端能力：触达。小程序=订阅消息（一次性授权，必须在关键节点收集）；App=推送通道。
// 一期 App 为 P1，先接应用内消息，厂商通道二期。

/** 小程序订阅消息模板 id（真实 id 由运营端下发，此处为占位常量） */
export const SUBSCRIBE_TMPL = {
  arrived: "TMPL_ARRIVED",
  shipped: "TMPL_SHIPPED",
  afterSale: "TMPL_AFTER_SALE",
  groupBuy: "TMPL_GROUP_BUY",
} as const;

/**
 * 在关键节点收集订阅授权（下单成功页、开团页）。
 * 小程序限制：必须由用户点击行为触发，且是一次性授权。
 */
export function requestSubscribe(tmplIds: string[]): Promise<boolean> {
  // #ifdef MP-WEIXIN
  return new Promise((resolve) => {
    uni.requestSubscribeMessage({
      tmplIds,
      success: () => resolve(true),
      fail: () => resolve(false), // 拒绝不阻塞主流程
    });
  });
  // #endif

  // #ifndef MP-WEIXIN
  return Promise.resolve(false);
  // #endif
}

export function initPush(): void {
  // #ifdef APP-PLUS
  // 二期：APNs / FCM / 厂商通道注册
  // #endif
}
