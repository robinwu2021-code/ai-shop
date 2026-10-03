import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { orderNoOf } from "../src/shared/order-no";

describe("微信购物订单跳回来的单号还原", () => {
  it("第一次付款：out_trade_no 就是订单号，原样返回", () => {
    expect(orderNoOf("O202609200023090012951")).toBe("O202609200023090012951");
  });

  it("重试付款：去掉 -2 / -3 后缀还原成订单号", () => {
    expect(orderNoOf("O202609200023090012951-2")).toBe("O202609200023090012951");
    expect(orderNoOf("O202609200023090012951-13")).toBe("O202609200023090012951");
  });

  it("空值不炸", () => {
    expect(orderNoOf("")).toBe("");
    expect(orderNoOf(undefined)).toBe("");
    expect(orderNoOf(null)).toBe("");
  });

  it("只削结尾，单号中间的短横不动", () => {
    // 真订单号不含短横；这条钉的是「万一将来含了，也别从中间切」
    expect(orderNoOf("A-1-B")).toBe("A-1-B");
  });

  /*
   * **源码级断言**：上面四条只证明函数对，证明不了详情页用了它。
   * 把 import 去掉、或者 onLoad 里改回直接取 q.orderNo，上面全绿而功能已经坏了 ——
   * 这正是本仓库反复吃过的那种假绿。
   */
  it("订单详情页确实拿它处理了 onLoad 的入参", () => {
    const src = readFileSync(
      resolve(__dirname, "../src/pages/order/index.vue"), "utf8");
    expect(src).toContain('from "@/shared/order-no"');
    expect(src).toMatch(/orderNo\.value\s*=\s*orderNoOf\(/);
  });
});
