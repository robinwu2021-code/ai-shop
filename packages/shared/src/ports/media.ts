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
/**
 * 单张图的上限。**必须与后端 `BizUploadController.MAX_BYTES` 一致** ——
 * 两边各写一个数的话，端上放行、服务端拒绝，商家白等一次上传却看不出原因。
 */
export const MAX_IMAGE_BYTES = 5 * 1024 * 1024;

export type ImageSource = "camera" | "album";

export interface PickedImage {
  /** 端上的临时路径，上传前用于预览 */
  tempPath: string;
  /** 字节数；用于在上传前挡住超大图（小程序单文件有上限） */
  size: number;
}

/**
 * 长边上限（ADR-026 T7）。`sizeType: compressed` 只降质量、不缩尺寸，手机原图常见 4000px；
 * 图片经应用服务器出流量受 5 Mbps 限制，线上一张原图 910 KB。1600px 够商品图与证件看清字，
 * 体积一般降到几百 KB 以内。
 */
export const MAX_IMAGE_EDGE = 1600;

/**
 * 算出缩放后的宽高（等比，长边压到 max）。没超过就返回 null —— 小图再压一遍只会更糊。
 * 单独拆出来是为了能测：uni 的 API 在测试里跑不了。
 */
export function fitLongEdge(width: number, height: number, max = MAX_IMAGE_EDGE): { width: number; height: number } | null {
  if (!(width > 0 && height > 0)) return null;
  const long = Math.max(width, height);
  if (long <= max) return null;
  const k = max / long;
  return { width: Math.round(width * k), height: Math.round(height * k) };
}

/** 失败一律退回原图：压缩是锦上添花，**不能挡住上传**。H5 没有 compressImage，走的就是这条。 */
function shrink(img: PickedImage): Promise<PickedImage> {
  return new Promise((resolve) => {
    const keep = () => resolve(img);
    try {
      uni.getImageInfo({
        src: img.tempPath,
        success: (info) => {
          const to = fitLongEdge(info.width, info.height);
          if (!to) return keep();
          uni.compressImage({
            src: img.tempPath,
            quality: 85,
            // 小程序认 compressedWidth / compressHeight（数字），App 认 width / height（带 px 的字符串）
            compressedWidth: to.width,
            compressHeight: to.height,
            width: `${to.width}px`,
            height: `${to.height}px`,
            success: (r) => {
              const out = r.tempFilePath;
              uni.getFileInfo({
                filePath: out,
                success: (f) => resolve({ tempPath: out, size: f.size }),
                // 拿不到新大小就沿用原图的：它只会更大，用来挡超大图是偏保守的那一侧
                fail: () => resolve({ tempPath: out, size: img.size }),
              });
            },
            fail: keep,
          } as UniApp.CompressImageOptions);
        },
        fail: keep,
      });
    } catch {
      keep();
    }
  });
}

/**
 * 选图或拍照。
 * `sizeType: compressed` 很关键 —— 原图在手机上动辄 5MB，
 * 小店老板用流量传四五张就会放弃。选完再把长边压到 {@link MAX_IMAGE_EDGE}。
 */
export function pickImages(count = 1, source: ImageSource[] = ["camera", "album"]): Promise<PickedImage[]> {
  return new Promise<PickedImage[]>((resolve, reject) => {
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
  }).then((list) => Promise.all(list.map(shrink)));
}

/** 兼容旧调用（售后凭证、评价晒单只要路径） */
export function chooseImages(count = 3): Promise<string[]> {
  return pickImages(count, ["camera", "album"]).then((list) => list.map((i) => i.tempPath));
}
