/**
 * 下单失败要说清「改什么」，切支付方式摘券要说一声（执行计划 B4）。
 *
 * 后端那句话回答的是「这次请求为什么被拒」，而买家要知道的是「我现在该改什么」：
 * 库存变少了去改数量，券失效了重选一张，地址超范围就换地址。
 * 认不出的码**回落后端原句** —— 编一句「提交失败，请重试」等于把真原因藏掉。
 */
import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

function code(rel: string): string {
  return readFileSync(resolve(__dirname, "..", rel), "utf-8")
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .replace(/<!--[\s\S]*?-->/g, "")
    .replace(/\/\/[^\n]*/g, "");
}

const confirm = code("src/pages/order-confirm/index.vue");

describe("失败要说清改什么", () => {
  const body = confirm.slice(
    confirm.indexOf("function submitFailText"),
    confirm.indexOf("async function submit()"),
  );

  it("★★★ 三类错误各有各的一句话", () => {
    expect(body, "库存").toContain("confirm.failStock");
    expect(body, "券").toContain("confirm.failCoupon");
    expect(body, "配送范围").toContain("confirm.failRange");
  });

  it("★★★ 认不出的码回落后端原句，不编一句「请重试」", () => {
    expect(body).toContain("(e as Error).message");
    expect(body).not.toContain("confirm.failUnknown");
  });

  it("★★★ 券失效时当场摘掉并重算 —— 留着它，再点一次还是同一个错", () => {
    const submit = confirm.slice(confirm.indexOf("async function submit()"));
    expect(submit).toContain("COUPON_ERRORS.has");
    expect(submit).toContain('couponNo.value = ""');
    expect(submit).toContain("refreshAmount()");
  });
});

describe("当面付摘掉平台券要说一声", () => {
  it("★★★ 不再静默摘券", () => {
    const at = confirm.indexOf("watch(platformCouponBlocked");
    const body = confirm.slice(at, at + 400);
    expect(body).toContain('couponNo.value = ""');
    expect(body, "静默摘掉 = 金额悄悄涨回去，用户以为页面算错了")
      .toContain("confirm.couponDroppedOffline");
  });
});
