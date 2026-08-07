// 端能力：书写方向。
// H5/App 写根节点 dir/lang；小程序没有 documentElement，靠 sh-scaffold 的 .is-rtl 类
// （`direction: rtl` 是标准 CSS，小程序 WebView 同样生效，flex 行会自动镜像）。
import type { Lang } from "@shared/types";

export function applyDirection(lang: Lang, rtl: boolean): void {
  // #ifdef H5 || APP-PLUS
  if (typeof document !== "undefined" && document.documentElement) {
    document.documentElement.setAttribute("dir", rtl ? "rtl" : "ltr");
    document.documentElement.setAttribute("lang", lang);
  }
  // #endif
}
