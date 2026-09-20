/**
 * 优惠要说出依据（TDD-C端优惠依据）。
 *
 * 线上实测：商品 ¥39.90、优惠 −¥10.00，而减这 10 块的是一个叫「abc」的商家直减活动。
 * 端上从头到尾只有「优惠 −¥10.00」五个字 —— 活动？券？两者叠加？买家看不出来，
 * **而后端一直知道**（`pmt_apply` 每笔都记着是哪个活动）。
 *
 * 这里守两件事：明细来自服务端（不由端上猜）、为空时整段不渲染（不显示一个「无」）。
 */
import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { aftersaleMock } from "@/api/mocks/aftersale";

function src(rel: string): string {
  return readFileSync(resolve(__dirname, "..", rel), "utf-8");
}
/** 判之前剥注释：解释规则的那句话自己也要能通过规则 */
function code(rel: string): string {
  return src(rel)
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .replace(/<!--[\s\S]*?-->/g, "")
    .replace(/\/\/[^\n]*/g, "");
}

const confirm = code("src/pages/order-confirm/index.vue");
const order = code("src/pages/order/index.vue");

describe("两处都把依据写出来", () => {
  it("★★★ 确认页逐条渲染 discountLines", () => {
    expect(confirm).toContain('v-for="(d, i) in discountLines"');
    expect(confirm, "只显示金额不显示名字，等于没说").toContain("discountLabel(d)");
  });

  it("★★★ 订单详情页同样逐条渲染", () => {
    expect(order).toContain("order.discountLines");
    expect(order).toContain("confirm.fromActivity");
  });

  it("★★★ 明细只来自服务端，端上不猜", () => {
    // 预览失败那一支要清空：留着上一次的明细，会让人以为这次也减了同样的东西
    expect(confirm).toContain("discountLines.value = p.discountLines ?? []");
    expect(confirm).toContain("discountLines.value = []");
  });
});

describe("为空时整段不渲染", () => {
  it("没有优惠就没有这几行 —— 不显示「无优惠」", () => {
    // v-for 在空数组上天然不渲染；这里钉住的是**没有**「否则显示一句话」的兜底分支
    const at = confirm.indexOf('v-for="(d, i) in discountLines"');
    const around = confirm.slice(at - 300, at + 400);
    expect(around).not.toContain("noDiscount");
  });
});

describe("mock 给得出明细", () => {
  it("★★★ 有优惠时 mock 也带上依据 —— 否则这一段在本机永远看不见", async () => {
    const r = await aftersaleMock.orderPreview!({
      items: [{ goodsNo: "G001", skuNo: "G001S1", qty: 2 }],
      fulfillment: "STORE_PICKUP",
    } as Parameters<NonNullable<typeof aftersaleMock.orderPreview>>[0]);
    if (r.amount.discountMinor > 0) {
      expect(r.discountLines?.length, "有优惠却不给依据").toBeGreaterThan(0);
      expect(r.discountLines![0]!.name).toBeTruthy();
      expect(r.discountLines![0]!.amountMinor).toBeGreaterThan(0);
    } else {
      expect(r.discountLines ?? []).toHaveLength(0);
    }
  });
});
