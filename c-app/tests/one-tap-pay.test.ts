/**
 * 一键支付（原型 k02/k11，执行计划 B3）。
 *
 * 此前首次下单要点两次：提交 → 落到收银台页 → 再点一次「立即支付」。
 * 第二次点击不提供任何信息，纯粹是一道多出来的手续。
 *
 * **修法刻意不是「把支付逻辑搬进下单页」**：那一段里有 0 元已结清、用户取消、
 * 唤起失败要把通道原话说出来、付完收订阅授权、团单落团页、幂等键作废 ——
 * 复制一份出去，两处迟早分岔，而分岔的是钱的路。所以逻辑只留一份，
 * 下单页带 `auto=1` 过去，收银台页加载完自己拉起。
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
const pay = code("src/pages/pay/index.vue");

describe("提交之后不再多点一次", () => {
  it("★★★ 线上支付跳收银台时带 auto=1", () => {
    expect(confirm).toContain("auto=1");
    const at = confirm.indexOf("const auto =");
    const body = confirm.slice(at, at + 260);
    expect(body, "当面付没有「拉起支付」这一步，不该带 auto").toContain("PAY_MODE.ONLINE");
  });

  it("★★★ 收银台页见到 auto=1 就自己拉起支付", () => {
    const at = pay.indexOf("onLoad(");
    const body = pay.slice(at, at + 600);
    expect(body).toContain('q?.auto === "1"');
    expect(body).toContain("pay()");
  });

  it("★★★ 只在还没付的单上自动拉起 —— 已付的单再拉一次是重复扣款的入口", () => {
    const at = pay.indexOf("onLoad(");
    const body = pay.slice(at, at + 600);
    expect(body).toContain('order.value?.status === "WAIT_PAY"');
  });
});

describe("支付逻辑仍然只有一份", () => {
  it("★★★ 下单页不自己调 requestPayment / payOrder", () => {
    expect(confirm, "把支付搬进下单页 = 两处分岔，而分岔的是钱的路")
      .not.toContain("requestPayment");
    expect(confirm).not.toContain("api.payOrder");
  });

  it("收银台页仍然握着那几个分支（0 元、取消、唤起失败）", () => {
    expect(pay).toContain("init.settled");
    expect(pay).toContain("res.cancelled");
    expect(pay).toContain("res.invoked");
  });
});

describe("按钮说清这一下会发生什么", () => {
  it("线上支付的按钮写金额，当面付仍是「提交订单」", () => {
    const tpl = readFileSync(
      resolve(__dirname, "..", "src/pages/order-confirm/index.vue"),
      "utf-8",
    );
    expect(tpl).toContain("confirm.payNow");
    expect(tpl).toContain("confirm.submit");
  });
});
