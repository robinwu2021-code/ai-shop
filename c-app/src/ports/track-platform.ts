// 看件页（pages/track）的端差异（TDD-收件人物流触达与分享裂变 §3）。
//
// 页面里不写 #ifdef（见 design-tokens 闸门「端差异下沉到 ports/」），条件编译收在这里：
//   · 小程序：先绑手机号，再按「本人」校验看件（trackMine）
//   · H5：免登录收窄视图，并可用微信 URL Link 唤起小程序
export const trackPlatform = { miniApp: false, h5: false };

// #ifdef MP-WEIXIN
trackPlatform.miniApp = true;
// #endif
// #ifdef H5
trackPlatform.h5 = true;
// #endif

/** H5 下整页跳到外部链接（微信 URL Link）；其它端无此能力，什么都不做。 */
export function openExternalLink(url: string): void {
  // #ifdef H5
  window.location.href = url;
  // #endif
}
