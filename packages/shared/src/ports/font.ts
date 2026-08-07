// 端能力：远程字体加载（Google Fonts）。
//
// 为什么只给拉丁与阿拉伯配远程字体、中文用系统字体：
//   中文字体子集动辄 3–10 MB，远程加载在移动端不可接受；而系统 PingFang SC / Noto Sans SC
//   本身质量足够。拉丁与阿拉伯的系统默认（尤其小程序 WebView 里的）字形较弱，正是要补的地方。
//
// 以下 woff2 均为 **可变字体**，一个文件覆盖 400/500/600/700 全字重（已实测 Google Fonts 返回值）。
//
// ⚠️ 生产必须自托管：
//   1) fonts.gstatic.com 在中国大陆访问不稳定；
//   2) 微信小程序要求 uni.loadFontFace 的域名进 downloadFile 白名单，且必须 https。
//   把 woff2 放到自己的 CDN，只需改下面两个常量。
const FONT_SOURCES = {
  latin: "https://fonts.gstatic.com/s/inter/v20/UcC73FwrK3iLTeHuS_nVMrMxCp50SjIa1ZL7W0Q5nw.woff2",
  arabic:
    "https://fonts.gstatic.com/s/notokufiarabic/v27/CSRk4ydQnPyaDxEXLFF6LZVLKrodrOYFFkCqIzAUWw.woff2",
} as const;

export const FONT_FAMILY = {
  latin: "Inter",
  arabic: "Noto Kufi Arabic",
} as const;

function load(family: string, url: string): Promise<void> {
  return new Promise((resolve) => {
    try {
      uni.loadFontFace({
        family,
        global: true,
        source: `url("${url}")`,
        success: () => resolve(),
        // 加载失败不阻塞：CSS 里排在前面的自定义字体缺失时会自动回落到系统字体栈
        fail: () => resolve(),
      });
    } catch {
      resolve();
    }
  });
}

/** 应用启动时调用。失败静默降级到系统字体，不影响可用性。 */
export function initFonts(): void {
  void load(FONT_FAMILY.latin, FONT_SOURCES.latin);
  void load(FONT_FAMILY.arabic, FONT_SOURCES.arabic);
}
