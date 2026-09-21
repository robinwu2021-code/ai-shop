/**
 * 下单页改数量（原型 k02/k04，执行计划 B2）。
 *
 * 守三件事：
 * ① 上限**取自后端**，不由端上按缓存的库存猜 —— 猜大了提交才报错，猜小了少卖；
 * ② 到顶时**说出还剩几件**：压暗却不说，用户以为点坏了；
 * ③ **不写回购物车** —— 下单页的数量是这一单的意图，他改完没付款就退出，
 *    购物车不该被悄悄改掉。
 */
import { describe, expect, it, vi } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { aftersaleMock } from "@/api/mocks/aftersale";

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

const confirm = code("src/pages/order-confirm/index.vue");

describe("上限来自后端", () => {
  it("★★★ maxQtyOf 取自预览返回的 items，不自己算", () => {
    expect(confirm).toContain("maxQtyOf");
    expect(confirm).toMatch(/\(p\.items \?\? \[\]\)[\s\S]{0,120}maxQty/);
  });

  it("★★★ 预览没给上限的行不设限 —— 宁可提交时拦，也别凭旧数挡人", () => {
    const at = confirm.indexOf("maxQtyOf.value = Object.fromEntries");
    const body = confirm.slice(at, at + 260);
    expect(body).toContain("maxQty != null");
  });

  it("mock 也给上限，否则步进器在本机永远不压暗", async () => {
    const r = await aftersaleMock.orderPreview!({
      items: [{ goodsNo: "G001", skuNo: "G001S1", qty: 1 }],
      fulfillment: "STORE_PICKUP",
    } as Parameters<NonNullable<typeof aftersaleMock.orderPreview>>[0]);
    expect(r.items[0]!.maxQty, "不给上限 = 这条规则在本机跑不到").toBeGreaterThan(0);
  });
});

describe("到顶要说出为什么", () => {
  const tpl = raw("src/pages/order-confirm/index.vue");

  it("★★★ 加号压暗的同时给一句「仅剩 N 件」", () => {
    expect(tpl).toContain("atMax(it)");
    expect(tpl, "压暗却不说，用户以为点坏了").toContain("confirm.qtyLeft");
  });

  it("超过上限时给提示而不是静默不动", () => {
    const at = confirm.indexOf("function setQty");
    const body = confirm.slice(at, at + 700);
    expect(body, "到顶那句话库存与限购要分开说（P1）").toContain("maxText(it.skuNo)");
  });
});

describe("只改这一单", () => {
  it("★★★ setQty 不碰购物车", () => {
    const at = confirm.indexOf("function setQty");
    const body = confirm.slice(at, at + 700);
    expect(body, "写回购物车 = 他没付款就退出，购物车被悄悄改了")
      .not.toMatch(/cart\.(update|setQty|add|remove)/);
  });

  it("★★★ 改完要重算 —— 金额、优惠、券的可用性全跟着变", () => {
    const at = confirm.indexOf("function setQty");
    const body = confirm.slice(at, at + 700);
    expect(body).toContain("refreshAmount()");
  });

  it("减到 0 是把这一行移出本单", () => {
    const at = confirm.indexOf("function setQty");
    const body = confirm.slice(at, at + 700);
    expect(body).toContain("next <= 0");
    expect(body).toContain("filter");
  });
});

vi.mock("@/api/mocks/_shared", async (orig) => {
  const m = await (orig() as Promise<Record<string, unknown>>);
  return { ...m, delay: <T>(v: T) => Promise.resolve(v) };
});
