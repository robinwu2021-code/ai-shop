import type { ComposerTranslation } from "vue-i18n";

/**
 * 把一张 base64 图存到相册。**这才是本地生活场景里真正会用的分享方式**——
 * 「复制链接」要接收方点开，「存图发群/发朋友圈」直接扫/直接看，前者在这个场景里几乎没人用。
 *
 * App/小程序走 `saveImageToPhotosAlbum`（先落一份临时文件，这两个平台都不接受直接传 base64）；
 * H5 存不了相册，退回「新开一个图片页」，交给用户自己长按保存——不是最好的体验，
 * 但比一个假装能用的按钮强。
 *
 * @param filenamePrefix 落盘临时文件名前缀，纯排障用（不同调用方存的图分得清）
 */
export function saveBase64Image(b64: string | null | undefined, filenamePrefix: string, t: ComposerTranslation) {
  if (!b64) return;
  // #ifdef H5
  window.open(`data:image/png;base64,${b64}`, "_blank");
  // #endif
  // #ifndef H5
  const fs = uni.getFileSystemManager();
  // uni.env 没进这版 @dcloudio/types，但 USER_DATA_PATH 是文档里的标准 API
  const path = `${(uni as unknown as { env: { USER_DATA_PATH: string } }).env.USER_DATA_PATH}/${filenamePrefix}-${Date.now()}.png`;
  fs.writeFile({
    filePath: path,
    data: b64,
    encoding: "base64",
    success: () => {
      uni.saveImageToPhotosAlbum({
        filePath: path,
        success: () => uni.showToast({ title: t("store.imageSaved"), icon: "none" }),
        fail: () => uni.showToast({ title: t("store.imageSaveFailed"), icon: "none" }),
      });
    },
    fail: () => uni.showToast({ title: t("store.imageSaveFailed"), icon: "none" }),
  });
  // #endif
}
