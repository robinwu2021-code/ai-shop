/**
 * 下载引导的二维码点阵（TDD-C端入驻意向-行业口径 §5）。
 *
 * <p>钉的是「这张码扫出来真的是那个链接」这件事能被证伪 ——
 * 二维码最坏的失败方式是**画出来很正常、扫出来不对**：空串编出的码、
 * 或者被截断的链接，肉眼与正确的码没有任何区别。
 */
import { describe, expect, it } from "vitest";
import { qrMatrix } from "@/shared/qrcode";

const APK = "https://www.hxmall.top/download/hxmall-merchant.apk";

describe("下载二维码", () => {
  it("★★★ 空串给空点阵 —— 不画一个「编了空字符串」的合法码", () => {
    // 那种码扫出来是空的，看上去却和正常的一模一样
    expect(qrMatrix("")).toEqual([]);
  });

  it("★★★ 点阵是正方形，且版本跟着内容长度走", () => {
    const m = qrMatrix(APK);
    expect(m.length).toBeGreaterThan(0);
    for (const row of m) expect(row.length).toBe(m.length);
    // 二维码的边长恒为 4×版本+17，所以对 21 取模能认出它是不是合法尺寸
    expect((m.length - 17) % 4).toBe(0);

    // 更长的内容要用更大的版本 —— 写死版本号的话长链接会直接抛 overflow
    const longer = qrMatrix(APK + "?from=" + "x".repeat(200));
    expect(longer.length).toBeGreaterThan(m.length);
  });

  it("★★★ 内容不同点阵就不同 —— 否则「画出来了」证明不了「编的是它」", () => {
    const a = qrMatrix(APK);
    const b = qrMatrix(APK.replace("apk", "ipa"));
    expect(a.length).toBe(b.length);
    expect(JSON.stringify(a)).not.toBe(JSON.stringify(b));
  });

  it("★★ 三个定位点在三个角上 —— 点阵方向没被转置", () => {
    const m = qrMatrix(APK);
    const n = m.length;
    // 定位图形是 7×7 的回字：四角的 (0,0) 必为黑，(6,6) 也必为黑
    const finder = (r0: number, c0: number) =>
      m[r0][c0] && m[r0 + 6][c0 + 6] && !m[r0 + 1][c0 + 1];
    expect(finder(0, 0)).toBe(true);
    expect(finder(0, n - 7)).toBe(true);
    expect(finder(n - 7, 0)).toBe(true);
    // 右下角**没有**定位点，这条把「四角都画了」这种错实现排除掉
    expect(m[n - 1][n - 1]).toBe(false);
  });
});
