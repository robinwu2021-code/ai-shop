// 端能力：把主题写到「CSS 管不到的地方」。
// 底部菜单已改为自定义组件（sh-tabbar），所以这里只剩导航栏 —— 它仍由客户端渲染。
import { MODE_HEX, type ModeId, type SkinId } from "@shared/design/tokens";

type ResolvedMode = Exclude<ModeId, "auto">;

export function applyThemeToRoot(skin: SkinId, mode: ResolvedMode): void {
  // #ifdef H5 || APP-PLUS
  if (typeof document !== "undefined" && document.documentElement) {
    document.documentElement.setAttribute("data-skin", skin);
    document.documentElement.setAttribute("data-theme", mode);
  }
  // #endif

  try {
    uni.setNavigationBarColor({
      frontColor: mode === "dark" ? "#ffffff" : "#000000",
      backgroundColor: MODE_HEX[mode].surface,
    });
  } catch {
    // 导航栏未就绪时忽略，切页时会重新应用
  }
}

/** 系统是否偏好深色（mode=auto 时用） */
export function systemPrefersDark(): boolean {
  try {
    return uni.getSystemInfoSync().theme === "dark";
  } catch {
    return false;
  }
}
