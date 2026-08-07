// 端能力：拍照 / 选图。
//
// 用途：售后凭证、评价晒单、**商家拍照建商品**。
//
// 端差异不在「能不能拍」—— 小程序与 App 都能拍能选能压缩。真正的差异是三条：
//   1. 小程序上传必须配 uploadFile 合法域名 + HTTPS；App 无此限制
//   2. 小程序**不能跑本地模型**，识别只能在服务端；App 可接端侧 SDK 但包体涨得多
//   3. 小程序切后台会挂起，长传要断点重来；App 可后台续传
//
// 结论：**识别统一放服务端**。两端一套逻辑，小程序不掉队，App 也不用为端侧模型撑大包体。
export type ImageSource = "camera" | "album";

export interface PickedImage {
  /** 端上的临时路径，上传前用于预览 */
  tempPath: string;
  /** 字节数；用于在上传前挡住超大图（小程序单文件有上限） */
  size: number;
}

/**
 * 选图或拍照。
 * `sizeType: compressed` 很关键 —— 原图在手机上动辄 5MB，
 * 小店老板用流量传四五张就会放弃。
 */
export function pickImages(count = 1, source: ImageSource[] = ["camera", "album"]): Promise<PickedImage[]> {
  return new Promise((resolve, reject) => {
    uni.chooseImage({
      count,
      sizeType: ["compressed"],
      sourceType: source,
      success: (res) => {
        const files = (res.tempFiles ?? []) as { path?: string; size?: number }[];
        const paths = res.tempFilePaths as string[];
        resolve(
          paths.map((p, i) => ({ tempPath: p, size: files[i]?.size ?? 0 })),
        );
      },
      // 用户取消也走 fail —— 调用方要区分「取消」与「真错误」，这里如实抛出
      fail: (e) => reject(new Error(e.errMsg || "已取消")),
    });
  });
}

/** 兼容旧调用（售后凭证、评价晒单只要路径） */
export function chooseImages(count = 3): Promise<string[]> {
  return pickImages(count, ["camera", "album"]).then((list) => list.map((i) => i.tempPath));
}
