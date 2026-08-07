// 端能力：支付。一期只接微信支付（小程序 JSAPI / App SDK）。
// ⚠️ 端侧不写死任何 PSP 参数，也不自判成功 —— 支付结果一律以后端回调为准。
export interface PayParams {
  /** 后端下单返回的支付参数（微信 JSAPI 五件套等） */
  [k: string]: unknown;
}

export interface PayResult {
  /** 端侧唤起结果，仅表示「用户完成了交互」，不代表支付成功 */
  invoked: boolean;
  cancelled: boolean;
}

export async function requestPayment(params: PayParams): Promise<PayResult> {
  // #ifdef MP-WEIXIN || APP-PLUS
  return new Promise((resolve) => {
    uni.requestPayment({
      provider: "wxpay",
      ...(params as object),
      success: () => resolve({ invoked: true, cancelled: false }),
      fail: (e) => resolve({ invoked: false, cancelled: /cancel/i.test(e.errMsg || "") }),
    } as UniApp.RequestPaymentOptions);
  });
  // #endif

  // #ifdef H5
  // H5 预览环境无真实支付通道，直接放行由后端 Stub 推进状态
  return { invoked: true, cancelled: false };
  // #endif
}
