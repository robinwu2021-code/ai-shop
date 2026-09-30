// 端能力：客服会话。小程序 = 微信客服（企业微信那款，wx.openCustomerServiceChat）。
//
// 其余端没有这个能力：H5/App 上显示平台邮箱 —— 画一颗点了没反应的按钮比没有按钮更糟。

/**
 * `wx.openCustomerServiceChat` 的最小签名。
 *
 * **不写 `declare const wx: any`**：那样写连参数名拼错都查不出来，
 * 而这个 API 失败是静默的（见下），拼错的代价恰恰是「点了没反应」。
 * 这里只声明用得到的那几个字段，多的等用到再加。
 */
declare const wx: {
  openCustomerServiceChat?: (opts: {
    extInfo: { url: string };
    corpId: string;
    success?: () => void;
    fail?: (err: unknown) => void;
  }) => void;
};

export interface WxKfParams {
  /**
   * 企业微信 CorpID。**同主体还不够，必须在小程序后台绑过** ——
   * 没绑的表现是 `errCode 6`（corpId is not bound to current miniprogram）。
   */
  corpId: string;
  /** 客服接入链接（企微后台 → 应用管理 → 微信客服 → 客服账号详情） */
  url: string;
}

/**
 * 打开微信客服会话。
 *
 * <p><b>整条调用链必须同步</b>：这个 API 在 iOS 上要求由用户手势<u>直接</u>触发，
 * 中间插一次 `await`（比如点了再去拉配置）就会被判「并非点击触发」而失败 ——
 * 而 Android 能过，于是那样写的代码<b>只在 iOS 真机上现形</b>。
 * 所以参数由调用方预先备好（走冷启动的 bootstrap），这里不做任何异步。
 *
 * @param onFail 打不开时的回调。**务必给** —— 这个 API 最常见的两种失败
 *               （corpId 没绑、基础库低于 2.20.0）在界面上都表现为「点了没反应」，
 *               与压根没接这个功能时一模一样；不接住就分不清「没配」和「配错了」。
 * @returns 是否真的发起了调用。false = 这个端没有这个能力，调用方该回落
 */
export function openWxCustomerService(params: WxKfParams, onFail?: (err: unknown) => void): boolean {
  // #ifdef MP-WEIXIN
  if (typeof wx !== "undefined" && typeof wx.openCustomerServiceChat === "function") {
    wx.openCustomerServiceChat({
      extInfo: { url: params.url },
      corpId: params.corpId,
      fail: (err: unknown) => {
        console.warn(
          "[kf] 微信客服打不开 —— 检查 corpId 是否已在小程序后台绑定、基础库是否 ≥2.20.0",
          err,
        );
        onFail?.(err);
      },
    });
    return true;
  }
  // #endif
  return false;
}

/**
 * 参数齐不齐。**两个都要有** —— 拿半截参数调过去失败是静默的，
 * 界面上与「压根没配」一模一样，所以回落要整体判，不能一个一个判。
 */
export function wxKfConfigured(p: Partial<WxKfParams> | undefined): boolean {
  return Boolean(p?.corpId && p?.url);
}
