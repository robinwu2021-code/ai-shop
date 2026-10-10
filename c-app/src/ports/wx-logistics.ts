// 打开微信官方物流轨迹页（TDD-物流轨迹多渠道 §2.6）。
//
// **这是小程序独有能力**：微信「物流轨迹」插件（logisticsPlugin）只在 MP-WEIXIN 里存在，
// requirePlugin 本身也只有小程序运行时才有。H5 / App 下后端不会下发 wx-plugin 渠道，
// 那个入口按钮根本不出现，所以另外两端这里是空实现。
//
// 页面里不写 #ifdef（端差异散在几十个文件里就没法「加一个端只改一处」，见 design-tokens 闸门），
// 条件编译收在这个 port 里。
//
// requirePlugin 是小程序运行时注入的全局（声明在 src/env.d.ts），返回 unknown，这里收窄。
type LogisticsPlugin = { openWaybillTracking: (o: { waybillToken: string }) => void };

/**
 * 打开微信物流详情页。
 *
 * @param waybillToken 后端用 trace_waybill 换好、落在运单上的 token（见 WxPluginDisplay）
 * @returns 成功打开返回 true；插件没加载成功或不在小程序端返回 false，由调用方决定怎么提示
 */
export function openWxWaybillTracking(waybillToken: string): boolean {
  // #ifdef MP-WEIXIN
  try {
    (requirePlugin("logisticsPlugin") as LogisticsPlugin).openWaybillTracking({ waybillToken });
    return true;
  } catch (e) {
    return false;
  }
  // #endif
  // eslint-disable-next-line no-unreachable
  return false;
}
