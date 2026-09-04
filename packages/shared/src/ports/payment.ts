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
  /**
   * 唤起失败时**通道自己说的原因**（`wx.requestPayment` 的 errMsg）。
   *
   * <b>此前这个字段不存在，于是失败被静默吞掉</b>：页面只处理了「用户取消」，
   * 非取消的失败一句话都不留，继续往下走。
   * 而微信在这一步会说很具体的话（未开通支付、appid 不匹配、签名错、参数缺失），
   * 那句话是排查这条链唯一的输入 —— 丢掉它，剩下的只有「点了没反应」。
   */
  failReason?: string;
}

export async function requestPayment(params: PayParams): Promise<PayResult> {
  // #ifdef MP-WEIXIN || APP-PLUS
  return new Promise((resolve) => {
    uni.requestPayment({
      provider: "wxpay",
      ...(params as object),
      success: () => resolve({ invoked: true, cancelled: false }),
      fail: (e) => {
        const msg = e?.errMsg || "";
        // 取消是正常路径；其余一律带上通道原话
        const cancelled = /cancel/i.test(msg);
        if (!cancelled) {
          // 控制台留一份：真机上 toast 一闪而过，而这句话往往是唯一的线索
          console.error("[pay] requestPayment 失败：", msg, e);
        }
        resolve({ invoked: false, cancelled, failReason: cancelled ? undefined : msg });
      },
    } as UniApp.RequestPaymentOptions);
  });
  // #endif

  // #ifdef H5
  // H5 预览环境无真实支付通道，直接放行由后端 Stub 推进状态
  return { invoked: true, cancelled: false };
  // #endif
}
