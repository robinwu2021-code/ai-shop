/**
 * 下单页的三条「说清楚」（待办设计 P5 / P6 / P9）。
 *
 * - P5 活动刚结束：金额会自己变，不说为什么变他会以为页面算错了；
 * - P6 库存变少：自动压到上限并说一句，而不是等提交时报「库存不足」让他猜改成几；
 *   送不到：后端预览的标记与端上算的取并集，并给「换配送方式」出口；
 * - P9 记住上次的在线支付方式；**当面付不记**。
 */
import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import zh from "@/i18n/locale/zh-CN";

const vue = readFileSync(resolve(__dirname, "../src/pages/order-confirm/index.vue"), "utf-8");
const code = vue.replace(/\/\*[\s\S]*?\*\//g, "").replace(/<!--[\s\S]*?-->/g, "").replace(/\/\/[^\n]*/g, "");
const confirm = (zh as unknown as { confirm: Record<string, string> }).confirm;

function body(name: string, len = 700): string {
  const at = code.indexOf(name);
  expect(at, `${name} 不见了`).toBeGreaterThan(-1);
  return code.slice(at, at + len);
}

describe("P5 活动结束", () => {
  it("★★★ 拿上一次的明细比，而且在覆盖之前比", () => {
    const at = code.indexOf("endedNotice.value = endedActivities(discountLines.value");
    const over = code.indexOf("discountLines.value = p.discountLines ?? []");
    expect(at).toBeGreaterThan(-1);
    expect(at, "先覆盖再比 = 永远比不出差别").toBeLessThan(over);
  });

  it("只比活动，券的去留由券那一行说", () => {
    expect(body("function endedActivities")).toContain('d.kind === "ACTIVITY"');
    expect(confirm.activityEnded).toContain("{name}");
  });
});

describe("P6 库存变少 / 送不到", () => {
  it("★★★ 超过上限就压，压完重新问价", () => {
    const b = body("function clampToMax");
    expect(b).toContain("it.qty > max");
    expect(b, "上限 0 是卖完了，别悄悄删整行").toContain("max > 0");
    expect(code).toMatch(/if \(clampToMax\(\)\) \{\s*void refreshAmount\(\);/);
  });

  it("★★ 后端的送不到与端上的取并集，提交拦截也看并集", () => {
    expect(code).toContain("serverOutOfRange.value = p.outOfRange ?? []");
    expect(code).toContain("if (outOfRangeNames.value.length) return");
    expect(code).toContain('@tap.stop="changeFulfillment"');
  });
});

describe("支付方式：小程序里线上与线下互斥（2026-09-28 用户拍板，取代 P9「记住支付方式」）", () => {
  it("★★★ 付法由 capability 推出，不是买家选的，也不存本机", () => {
    expect(code).toMatch(/const payMode = computed<string>\(\(\) =>\s*!onlineReady\.value && canPayOffline\.value \? PAY_MODE\.OFFLINE : PAY_MODE\.ONLINE\)/);
    expect(code, "又出现了可点的付法选项").not.toMatch(/@tap="payMode = /);
    expect(code, "又开始记上次的付法").not.toContain("checkout.payMode");
  });
});
