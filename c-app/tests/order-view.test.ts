/**
 * 订单详情与列表（原型 k07/k09，执行计划 B5）。
 *
 * 守的是两句「说人话」：
 * ① 详情的状态下面要说**接下来会发生什么** ——「待发货」三个字只说了此刻，
 *    没说他要等什么，而那正是他点进这一页想知道的；
 * ② 列表要给**还剩多久**与**省了多少** —— 列表是他决定「先付哪一单」的地方。
 *
 * 以及一条反向的：没写过说明的状态**整行不显示**，不编一句放之四海皆准的话。
 */
import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import zh from "@/i18n/locale/zh-CN";

function raw(rel: string): string {
  return readFileSync(resolve(__dirname, "..", rel), "utf-8");
}
/** 判之前剥注释：解释规则的那句话自己也要能通过规则 */
function code(rel: string): string {
  return raw(rel)
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .replace(/<!--[\s\S]*?-->/g, "")
    .replace(/\/\/[^\n]*/g, "");
}

const detail = code("src/pages/order/index.vue");
const list = code("src/pages/orders/index.vue");

describe("详情：状态下面说下一步", () => {
  it("★★★ 渲染 nextStepText", () => {
    expect(raw("src/pages/order/index.vue")).toContain("nextStepText");
  });

  it("★★★ 没写说明的状态整行不显示，不编话", () => {
    const at = detail.indexOf("const nextStepText");
    const body = detail.slice(at, at + 500);
    expect(body, "i18n 找不到键时会原样返回键名 —— 那正是「还没写过说明」")
      .toContain("text === key");
  });

  it("六个状态都写了说明 —— 漏一个那一格就空着", () => {
    const next = (zh as unknown as { order: { next: Record<string, string> } }).order.next;
    for (const st of ["WAIT_PAY", "WAIT_FULFILL", "FULFILLING", "COMPLETED", "CANCELLED", "REFUNDED"]) {
      expect(next[st], `${st} 没有说明`).toBeTruthy();
    }
  });

  it("订单号可复制 —— 找客服要念这一串", () => {
    expect(detail).toContain("copyOrderNo");
    expect(detail).toContain("setClipboardData");
  });
});

describe("列表：还剩多久、省了多少", () => {
  it("★★★ 待付款卡带倒计时", () => {
    expect(list).toContain("payLeft(o)");
    expect(raw("src/pages/orders/index.vue")).toContain("orders.payLeft");
  });

  it("★★★ 倒计时只给待付款的单，且过期后不显示负数", () => {
    const at = list.indexOf("function payLeft");
    const body = list.slice(at, at + 400);
    expect(body).toContain('o.status !== "WAIT_PAY"');
    expect(body, "过期了还倒数就成了负数").toContain("left > 0");
  });

  it("有优惠的单在列表上说出省了多少", () => {
    expect(raw("src/pages/orders/index.vue")).toContain("orders.saved");
    expect(list).toContain("o.amount.discountMinor");
  });

  it("秒表要清掉 —— 不清的话离开这一页还在每秒跑", () => {
    expect(list).toContain("clearInterval(tick)");
  });
});
