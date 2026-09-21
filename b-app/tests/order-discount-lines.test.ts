/**
 * B 端订单详情的优惠明细（优惠券全链路梳理 批 3）：这单减了什么、谁出的钱。
 * 此前商家只看得到应付 —— 顾客问「怎么少了 5 块」答不上来，对账时也分不清是自己让的还是平台补的。
 */
import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

const vue = readFileSync(resolve(__dirname, "../src/pages/order/index.vue"), "utf-8");

describe("B 端订单明细", () => {
  it("★★★ 逐条列出 discountLines", () => {
    expect(vue).toContain('v-for="(d, i) in order.discountLines ?? []"');
  });

  it("★★ 说出谁出的钱", () => {
    expect(vue).toContain("order.funderPlatform");
    expect(vue).toContain("order.funderMerchant");
  });
});
