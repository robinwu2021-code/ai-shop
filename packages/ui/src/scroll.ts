// 回到页面顶部。
//
// 为什么不能直接用 `uni.pageScrollTo`：桌面 H5 下 sh-scaffold 的应用框是自己滚的
// （见该组件里那段注释），滚动条不在 window 上，`pageScrollTo` 会静默无效 ——
// 「提交被驳回 → 滚回顶部看错误提示」这种交互会在 PC 上悄悄失灵，而手机上一切正常，
// 属于最难被发现的那类差异。这里两边都招呼一遍。
export function scrollToTop(duration = 200): void {
  uni.pageScrollTo({ scrollTop: 0, duration });
  // #ifdef H5
  // 滚动盒是框**内层**的 .sh-scaffold（框自己不滚，见 sh-scaffold 的样式注释）
  document.querySelector(".sh-frame > .sh-scaffold")?.scrollTo({ top: 0, behavior: "smooth" });
  // #endif
}
