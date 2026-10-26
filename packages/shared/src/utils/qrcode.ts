import qrcodeGenerator from "qrcode-generator";

/**
 * 把一段文本编成二维码的**点阵**（每行一串 0/1），交给调用方自己画。
 *
 * <p><b>为什么只到点阵、不画图</b>：小程序、H5、App 三端的 canvas 不是同一套 API
 * （小程序要 `createCanvasContext` 或 2d 上下文 + `SelectorQuery`，H5 是原生 canvas，
 * App 又是另一份实现）。画在 canvas 上就要写三份，而且画完还要 `canvasToTempFilePath`
 * 才能保存——每一步都有平台差异。点阵交给模板用 `<view>` 铺成格子，
 * 三端走的是同一条路径，也天然跟着换肤与 RTL。
 *
 * <p>纠错等级用 <b>M</b>（约 15%）：这一格印在屏幕上给人扫，不是印在纸箱上，
 * 用 H（30%）只会让同样尺寸里的点更密、更难扫。
 */
export type QrMatrix = readonly (readonly boolean[])[];

/**
 * @param text 要编的内容。空串返回空点阵 —— 调用方据此不显示，
 *             而不是画一个「编了个空字符串」的合法二维码：那个码扫出来是空的，
 *             看上去却和正常的一模一样。
 */
export function qrMatrix(text: string): QrMatrix {
  if (!text) return [];
  /*
   * typeNumber 传 0 = 让它按内容长度自己选最小的版本。
   * 写死一个版本的话，链接一变长就抛 "code length overflow"，
   * 而那时候只有那一条特定的长链接会炸。
   */
  const qr = qrcodeGenerator(0, "M");
  qr.addData(text);
  qr.make();
  const n = qr.getModuleCount();
  const rows: boolean[][] = [];
  for (let r = 0; r < n; r++) {
    const row: boolean[] = [];
    for (let c = 0; c < n; c++) row.push(qr.isDark(r, c));
    rows.push(row);
  }
  return rows;
}
