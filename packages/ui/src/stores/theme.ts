// 主题 store：皮肤(色) × 明暗(风格)。切换即时全局生效并持久化。
// H5/App：改根节点 data-skin / data-theme
// 小程序：无 documentElement，改由 sh-scaffold 组件把 skin-*/mode-* 类挂到页面根 view
import { defineStore } from "pinia";
import { DEFAULT_MODE, DEFAULT_SKIN, type ModeId, type SkinId } from "@shared/design/tokens";
import { STORAGE } from "@shared/utils/constants";
import { applyThemeToRoot, systemPrefersDark } from "@shared/ports/theme";

export const useThemeStore = defineStore("theme", {
  state: () => ({
    skin: DEFAULT_SKIN as SkinId,
    mode: DEFAULT_MODE as ModeId,
  }),

  getters: {
    /** auto 解析为实际明暗，供 .sh-root 类名与组件判断使用 */
    resolvedMode(state): "light" | "dark" {
      if (state.mode === "auto") return systemPrefersDark() ? "dark" : "light";
      return state.mode;
    },
    /** 小程序端页面根 view 的类名 */
    rootClass(): string[] {
      return [`skin-${this.skin}`, `mode-${this.resolvedMode}`];
    },
  },

  actions: {
    init() {
      this.skin = (uni.getStorageSync(STORAGE.skin) as SkinId) || DEFAULT_SKIN;
      this.mode = (uni.getStorageSync(STORAGE.mode) as ModeId) || DEFAULT_MODE;
      applyThemeToRoot(this.skin, this.resolvedMode);
    },
    setSkin(skin: SkinId) {
      this.skin = skin;
      uni.setStorageSync(STORAGE.skin, skin);
      applyThemeToRoot(this.skin, this.resolvedMode);
    },
    /** 每个页面挂载时重新应用一次：tabBar/导航栏在首屏可能尚未就绪 */
    reapply() {
      applyThemeToRoot(this.skin, this.resolvedMode);
    },
    setMode(mode: ModeId) {
      this.mode = mode;
      uni.setStorageSync(STORAGE.mode, mode);
      applyThemeToRoot(this.skin, this.resolvedMode);
    },
  },
});
