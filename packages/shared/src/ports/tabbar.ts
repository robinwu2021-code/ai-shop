// 端能力：隐藏原生 tabBar。
//
// 自定义底部菜单（sh-tabbar）替换了原生 tabBar（理由见该组件顶部注释：字号锁死、
// 不吃 CSS 变量、不吃 i18n）。但「隐藏原生 tabBar」各端表现不同：
//   H5   —— pages.json 的 `custom:true` 已把原生 tabBar 藏掉，无需再做；
//   App  —— `custom:true` **不保证**藏住原生 tabBar，它会和自定义菜单叠成**两排**
//           （真机上肉眼可见「多了一层菜单」），必须显式 uni.hideTabBar()。
//
// 放到 port 里而不是组件里：条件编译 `#ifdef` 铁律禁止出现在页面/组件中（见 shell.ts）。
export function hideNativeTabBar(): void {
  // #ifdef APP-PLUS
  try {
    uni.hideTabBar({ animation: false });
  } catch {
    // 非 tabBar 页调用会抛 —— 无害，忽略
  }
  // #endif
}
