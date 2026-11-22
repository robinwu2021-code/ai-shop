// 显示一份 PDF（发票打印模拟等）：按端分叉集中在这里，页面不写条件编译。
//
// · H5：下载链接回的是附件 + 禁止嵌入（X-Frame-Options: DENY），取成本页的 blob 地址，交给 <biz-pdf-frame> 内嵌显示。
// · App / 小程序：下载到临时文件，用系统查看器打开；页面上不内嵌（Android WebView 也不渲染 PDF）。

/** 打开 PDF。H5 返回可内嵌的 blob 地址；其它端交给系统查看器并返回空串。下载失败 reject。 */
export async function openPdf(url: string): Promise<string> {
  // #ifdef H5
  const res = await fetch(url);
  if (!res.ok) throw new Error("download failed");
  return URL.createObjectURL(new Blob([await res.blob()], { type: "application/pdf" }));
  // #endif
  // #ifndef H5
  return new Promise<string>((resolve, reject) => {
    uni.downloadFile({
      url,
      success: (r) => {
        if (r.statusCode !== 200) { reject(new Error("download failed")); return; }
        uni.openDocument({ filePath: r.tempFilePath, fileType: "pdf", showMenu: true,
          success: () => resolve(""), fail: () => reject(new Error("open failed")) });
      },
      fail: () => reject(new Error("download failed")),
    });
  });
  // #endif
}

/** 释放 openPdf 返回的地址（H5 的 blob）；空串什么都不做。 */
export function releasePdf(url: string) {
  // #ifdef H5
  if (url) URL.revokeObjectURL(url);
  // #endif
}
