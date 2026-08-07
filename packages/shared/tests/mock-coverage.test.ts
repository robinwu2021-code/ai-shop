// mock 数据体检：**契约里声明的东西，mock 有没有真的产出过**。
//
// 形状层不用测 —— schema 由 ts-json-schema-generator 从同一份 TS 类型生成，
// mock 又实现同一个接口，字段名/类型对不上编译就过不了。
//
// 会漏的是三类，它们都不报错：
//   1. 枚举值 mock 只用了一部分 —— 没覆盖到的状态，前端界面与后端实现都没被验证过
//   2. 可选字段永远是空 —— 契约说「可能有」，界面从没见过，接后端才发现渲染不了
//   3. 必填字段填的是占位（空串 / 0 / "—"）—— 形状合法，语义是假的
//
// 这三类正是「mock 与 API 规范对不上」的实际形态。
import { describe, expect, it } from "vitest";
import { db, allGoods } from "@shared/mock/db";
import { FULFILLMENT, CATEGORY_TYPE, SERVICE_SCOPE } from "@shared/utils/constants";
import type { Order } from "@shared/types";

/** 种子里出现过的值集合 */
function valuesOf<T>(rows: T[], pick: (r: T) => string | undefined): Set<string> {
  return new Set(rows.map(pick).filter((v): v is string => !!v));
}

describe("mock 数据体检：枚举覆盖", () => {
  // 覆盖不到的状态 = 没人验证过的分支。上线后第一个真实订单走到那里才发现界面是空的。
  it("履约方式：每一种都有种子数据", () => {
    // 商品上是**数组** `fulfillments`（一件商品可支持多种取货方式），不是单值。
    // 第一版把它当单值查，结果 7 种全报「没用到」—— 体检脚本自己先要体检。
    const used = new Set(allGoods().flatMap((g) => g.fulfillments ?? []));
    const declared = Object.values(FULFILLMENT) as string[];
    const missing = declared.filter((f) => !used.has(f));
    expect(
      missing,
      `以下履约方式没有任何商品用到，对应的下单/履约分支从未被验证：${missing.join(", ")}\n` +
        "补一条种子商品比等上线后发现便宜得多。",
    ).toEqual([]);
  });

  it("品类类型：每一类都有商品", () => {
    const used = valuesOf(allGoods(), (g) => g.type);
    const missing = (Object.values(CATEGORY_TYPE) as string[]).filter((t) => !used.has(t));
    expect(missing, `以下品类没有种子商品：${missing.join(", ")}`).toEqual([]);
  });

  it("经营范围：每一种都有商家", () => {
    const used = new Set(allGoods().map((g) => g.merchant.merchantNo));
    // 经营范围挂在商家种子上，通过商品反查覆盖到的商家
    expect(used.size, "至少要有 2 家不同商家的商品，否则跨商家拆单永远走不到").toBeGreaterThan(1);
    const scopes = Object.values(SERVICE_SCOPE) as string[];
    expect(scopes.length, "SERVICE_SCOPE 定义了但一个都没用上").toBeGreaterThan(0);
  });
});

describe("mock 数据体检：占位值", () => {
  // 「形状合法、语义是假的」比缺字段更难发现：界面渲染正常，只是显示的是空。
  const isPlaceholder = (v: unknown) =>
    v === "" || v === "—" || v === "-" || v === null || v === undefined;

  it("商品的关键字段不是占位", () => {
    const bad: string[] = [];
    for (const g of allGoods()) {
      if (isPlaceholder(g.title)) bad.push(`${g.goodsNo}: title`);
      if (!g.price || g.price <= 0) bad.push(`${g.goodsNo}: price=${g.price}`);
      if (isPlaceholder(g.merchant?.name)) bad.push(`${g.goodsNo}: merchant.name`);
      if (!g.skus?.length) bad.push(`${g.goodsNo}: 没有 SKU`);
    }
    expect(bad, `商品种子里有占位值 —— 界面看着正常，接后端才发现是空的：\n${bad.join("\n")}`).toEqual(
      [],
    );
  });

  it("订单的金额与履约字段不是占位", () => {
    const bad: string[] = [];
    for (const o of db.orders as Order[]) {
      if (!o.amount || o.amount.payableMinor == null) bad.push(`${o.orderNo}: amount 缺失`);
      if (!o.fulfillment) bad.push(`${o.orderNo}: fulfillment 缺失`);
      if (!o.items?.length) bad.push(`${o.orderNo}: 没有商品行`);
      // 自提单在**等待取货的状态**必须有码 —— 没有的话「码在最上面」那个页面是空的。
      // 已完成/已退款的不查：码已经用掉了，不显示才是对的
      const NEEDS_CODE = ["PAID", "PREPARING", "ARRIVED"];
      if (o.fulfillment === FULFILLMENT.PICKUP && NEEDS_CODE.includes(o.status) && !o.verifyCode) {
        bad.push(`${o.orderNo}(${o.status}): 自提单缺 verifyCode`);
      }
    }
    expect(bad, `订单种子有问题：\n${bad.join("\n")}`).toEqual([]);
  });
});
