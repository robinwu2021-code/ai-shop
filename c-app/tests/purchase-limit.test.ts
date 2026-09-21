/**
 * 每人限购（待办设计 P1）。
 *
 * 此前 **只有 mock 在拦**：加购超限抛「每人限购 N 件」，而真后端一处都不拦 ——
 * 本机点一遍是对的，线上买 50 件照样成交，mock 替一条不存在的规则背了书。
 * 现在后端拦了（PurchaseLimitFlowTest），这里守的是 mock 与后端**同一口径**，
 * 以及下单页到顶那句话把「库存」与「限购」分开说。
 */
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { db } from "@shared/mock/db";
import { ApiError } from "@shared/net/http-client";
import { boughtQtyOf, PURCHASE_LIMIT_EXCEEDED, requireWithinLimit } from "@/api/mocks/_shared";
import { aftersaleMock } from "@/api/mocks/aftersale";
import type { Order } from "@shared/types";
import zh from "@/i18n/locale/zh-CN";

/** G001 在种子里限购 5 */
const G = "G001";

let ordersBackup: Order[];
beforeEach(() => {
  ordersBackup = db.orders;
  db.orders = [];
});
afterEach(() => {
  db.orders = ordersBackup;
});

function order(status: Order["status"], qty: number, isGift = false): Order {
  return { status, items: [{ goodsNo: G, skuNo: "G001S1", qty, isGift }] } as unknown as Order;
}

function codeOf(fn: () => void): number {
  try {
    fn();
    return 0;
  } catch (e) {
    return e instanceof ApiError ? e.code : -1;
  }
}

describe("mock 与后端同一口径", () => {
  it("★★★ 已取消、已退款、赠品都不算已买", () => {
    db.orders = [order("WAIT_PAY", 2), order("CANCELLED", 3), order("REFUNDED", 1), order("COMPLETED", 1, true)];
    expect(boughtQtyOf(G)).toBe(2);
  });

  it("★★★ 超了抛与后端同号的错，而不是一句裸字符串", () => {
    db.orders = [order("COMPLETED", 4)];
    expect(codeOf(() => requireWithinLimit(G, 1))).toBe(0);
    expect(codeOf(() => requireWithinLimit(G, 2))).toBe(PURCHASE_LIMIT_EXCEEDED);
    expect(PURCHASE_LIMIT_EXCEEDED, "与后端 ErrorCode.PURCHASE_LIMIT_EXCEEDED 同号").toBe(20007);
  });

  it("★★ 预览给出限购挡住的上限与原因", async () => {
    db.orders = [order("COMPLETED", 3)];
    const r = await aftersaleMock.orderPreview!({
      items: [{ goodsNo: G, skuNo: "G001S1", qty: 1 }],
      fulfillment: "STORE_PICKUP",
    } as Parameters<NonNullable<typeof aftersaleMock.orderPreview>>[0]);
    const it = r.items![0]!;
    expect(it.maxQty, "限购 5、已买 3 → 2").toBe(2);
    expect(it.limitReason).toBe("PER_USER");
    expect(it.boughtQty).toBe(3);
  });

  it("加购那条自写的拦截已删 —— 不再有第二套口径", () => {
    const src = readFileSync(resolve(__dirname, "../src/api/mocks/catalog.ts"), "utf-8");
    expect(src).not.toContain("每人限购 ${g.limitPerUser} 件");
    expect(src).toContain("requireWithinLimit(");
  });
});

describe("到顶那句话分开说", () => {
  const vue = readFileSync(resolve(__dirname, "../src/pages/order-confirm/index.vue"), "utf-8");

  it("★★★ 限购挡住时说限购，不说「仅剩」", () => {
    const at = vue.indexOf("function maxText");
    const body = vue.slice(at, at + 600);
    expect(body).toContain('row?.limitReason === "PER_USER"');
    expect(body).toContain("confirm.qtyLimitBought");
    expect(body).toContain("confirm.qtyLeft");
  });

  it("文案写了", () => {
    const c = (zh as unknown as { confirm: Record<string, string> }).confirm;
    expect(c.qtyLimit).toContain("{limit}");
    expect(c.qtyLimitBought).toContain("{bought}");
  });
});
