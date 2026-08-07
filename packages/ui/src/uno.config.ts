// UnoCSS 配置（两端共用，各 app 的 uno.config.ts 只做转发）：presetApplet 同一套类名在 H5 / App / 小程序都可用。
// 关键：主题色映射到 CSS 变量 var(--sh-*) → 换肤（改根节点 data-skin/data-theme 或 .sh-root 类）
// 即全局生效，无需重编译、无需重渲染。
import { defineConfig, transformerDirectives, transformerVariantGroup } from "unocss";
import { presetApplet, presetRemRpx, transformerAttributify } from "unocss-applet";

export default defineConfig({
  presets: [presetApplet(), presetRemRpx({ baseFontSize: 16 })],
  transformers: [
    transformerDirectives(),
    transformerVariantGroup(),
    transformerAttributify({ prefixedOnly: true }),
  ],
  theme: {
    colors: {
      primary: "var(--sh-primary)",
      "on-primary": "var(--sh-on-primary)",
      "primary-tint": "var(--sh-primary-tint)",
      success: "var(--sh-success)",
      "success-tint": "var(--sh-success-tint)",
      warning: "var(--sh-warning)",
      "warning-tint": "var(--sh-warning-tint)",
      danger: "var(--sh-danger)",
      "danger-tint": "var(--sh-danger-tint)",
      bg: "var(--sh-bg)",
      surface: "var(--sh-surface)",
      elev: "var(--sh-elev)",
      ink: "var(--sh-ink)",
      sub: "var(--sh-sub)",
      faint: "var(--sh-faint)",
      line: "var(--sh-line)",
    },
    borderRadius: {
      // 五档，组件层只许用这五个（由 design-tokens 规范测试拦截）。
      // ⚠️ 必须与 packages/shared/src/design/tokens.ts 的 radius 完全一致 ——
      // 之前两处各写一套（12/20/28/40 vs 16/24/32/44），
      // 于是 `rounded-md` 是 20rpx 而 `radius.md` 是 24rpx，同一个「md」两个值。
      sm: "16rpx",
      md: "24rpx",
      lg: "32rpx",
      xl: "44rpx",
      full: "9999px",
    },
  },
});
