// 端能力：页面自绘顶栏时「按钮该放多高、右边要让出多宽」。
//
// 小程序右上角有微信的胶囊（··· ◎），它的上沿和高度各机型不同 —— 写死会一高一低，
// 还可能压在胶囊底下；所以小程序上读 getMenuButtonBoundingClientRect 对齐它。
// H5 / App 没有胶囊：从状态栏往下留一条 32px 高的行，右边留 12px。
// 取不到就给一组保守值，调用方照样能画。

export interface NavBox {
  /** 按钮行的上沿（px，距屏顶） */
  top: number;
  /** 按钮行的高度（px）—— 圆钮直径就用它 */
  height: number;
  /** 右边要让出的宽度（px）：胶囊 + 它到屏幕右边的距离 */
  right: number;
  /** 屏宽（px），给 rpx 换算用 */
  winW: number;
}

export function navBox(): NavBox {
  try {
    const sys = uni.getSystemInfoSync();
    const sb = sys.statusBarHeight ?? 0;
    // #ifdef MP-WEIXIN
    const m = uni.getMenuButtonBoundingClientRect();
    if (m && m.height) return { top: m.top, height: m.height, right: sys.windowWidth - m.left, winW: sys.windowWidth };
    // #endif
    return { top: sb + 6, height: 32, right: 12, winW: sys.windowWidth };
  } catch {
    return { top: 26, height: 32, right: 12, winW: 375 };
  }
}
