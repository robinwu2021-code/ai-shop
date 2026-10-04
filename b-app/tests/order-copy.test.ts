/**
 * 「复制订单信息给供应商」拼装口径（TDD-虹选鲜果运营落地 §5）。
 *
 * <p>断言落在**内容**上，不是「有没有复制」：供应商照着这段文字拣货发货，
 * 少一行都可能发错。三条守住三件最容易回退的事：
 *   ① 不含售价（漏出去就是把毛利给了供应商）；
 *   ② 没有的段跳过而不是显示「null」（自提单无收件人）；
 *   ③ 赠品要在里面（否则供应商漏发）。
 */
import { describe, expect, it } from "vitest";
import type { Order } from "@shared/types";
import { datetime } from "@shared/utils/datetime";
import { buildOrderCopyText } from "@/utils/order-copy";

/** 测试用的 t：原样返回 key，断言就能锚在 key 上，不依赖具体文案 */
const t = ((k: string) => k) as unknown as Parameters<typeof buildOrderCopyText>[1];

function order(over: Partial<Order> = {}): Order {
  return {
    orderNo: "O2026100400001",
    status: "WAIT_SHIP",
    fulfillment: "EXPRESS",
    createdAt: 1_790_000_000_000,
    items: [
      { goodsNo: "G1", merchantNo: "M1", skuNo: "S1", title: "阳光玫瑰", cover: "", spec: "2斤装", price: 3800, qty: 2, type: "FRESH" },
    ],
    amount: { payableMinor: 7600, currency: "CNY", freightMinor: 0 } as Order["amount"],
    receiver: { name: "张三", phone: "13900005678", address: "深圳市福田区XX路1号101" },
    timeline: [],
    ...over,
  } as Order;
}

describe("复制订单信息给供应商", () => {
  it("★★★ 含商品+数量+收件人+地址，且**不含售价**", () => {
    const text = buildOrderCopyText(order(), t);
    expect(text).toContain("O2026100400001");
    expect(text).toContain(datetime(1_790_000_000_000));
    expect(text).toContain("张三 13900005678");
    expect(text).toContain("深圳市福田区XX路1号101");
    expect(text).toContain("阳光玫瑰 2斤装 ×2");
    // 不含售价：3800/38.00/¥ 一个都不该出现 —— 漏价就是把毛利给供应商
    expect(text).not.toContain("3800");
    expect(text).not.toContain("38.00");
    expect(text).not.toContain("¥");
  });

  it("自提单无收件人：收货/地址两行跳过，不出现 null", () => {
    const text = buildOrderCopyText(order({ fulfillment: "PICKUP", receiver: undefined }), t);
    expect(text).not.toContain("null");
    expect(text).not.toContain("undefined");
    expect(text).not.toContain("order.receiver");
    expect(text).not.toContain("order.copyAddr");
    // 商品行仍在
    expect(text).toContain("阳光玫瑰 2斤装 ×2");
  });

  it("★★ 赠品要在里面并标出来，否则供应商漏发", () => {
    const text = buildOrderCopyText(order({
      items: [
        { goodsNo: "G1", merchantNo: "M1", skuNo: "S1", title: "阳光玫瑰", cover: "", spec: "2斤装", price: 3800, qty: 2, type: "FRESH" },
        { goodsNo: "G2", merchantNo: "M1", skuNo: "S2", title: "赠品小番茄", cover: "", spec: "1盒", price: 0, qty: 1, type: "FRESH", isGift: true },
      ],
    }), t);
    expect(text).toContain("赠品小番茄 1盒 ×1");
    expect(text).toContain("order.copyGift");
  });
});
