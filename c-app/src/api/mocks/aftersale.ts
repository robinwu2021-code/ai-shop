// 售后 —— C 端替身的一域。
//
// 从 `api/mock.ts`（1728 行 / 86 个接口）按域拆出来；实现一个字没改。
// 合并在 `mocks/index.ts`，那里的类型标注保证**一个接口都不能少**。

import { db, delay, findGoodsSeed, nextNo, persist, pick, pointBalance, pushMessage, toGoods } from "@shared/mock/db";
import { pricingFor } from "@shared/strategies/pricing";
import type { AfterSaleReason, Coupon, Order, OrderItem } from "@shared/types";
import { FULFILLMENT, PAY_MODE, TRADE_RULES } from "@shared/utils/constants";
import { currentCurrency } from "@shared/utils/money";
import {
  findOrder,
  findOrderByAfterSale,
  pushTimeline,
  settleRefund,
} from "./_shared";
import type { ShopApi } from "../contract";

export const aftersaleMock: Pick<ShopApi,
  "applyAfterSale"
  | "afterSaleReasons"
  | "orderPreview"
  | "orderCapability"
  | "afterSaleList"
  | "fillReturnExpress"
  | "raiseDispute"
> = {
  // ---------------------------------------------------------------- 售后
  async applyAfterSale(orderNo, reason, images, type = "REFUND_ONLY") {
    const o = findOrder(orderNo);
    /*
     * **订单状态不动。** 售后是挂在订单上的另一张单，两者并存 ——
     * 一个「已完成」的订单照样能申请售后，把它改成「退款中」就丢失了
     * 「货其实已经收到了」这个事实，也让订单列表的其它页签少一条。
     */
    o.afterSale = {
      afterSaleNo: nextNo("AS"),
      // mock 里 Order 就是子订单，两个号取同一个值
      subOrderNo: o.orderNo,
      orderNo: o.orderNo,
      type,
      status: "APPLIED",
      reason,
      images,
      // 整单退：mock 不做部分退款
      refundMinor: o.amount.paidMinor || o.amount.payableMinor,
      instant: type === "REFUND_ONLY"
        && (o.amount.paidMinor || o.amount.payableMinor) <= TRADE_RULES.instantRefundMaxMinor,
      updatedAt: Date.now(),
    };
    pushTimeline(o, `已申请${type === "RETURN_REFUND" ? "退货退款" : "仅退款"}：${reason}`);

    /**
     * 极速退：小额自动通过。**只对「仅退款」生效** ——
     * 退货退款要等货回来才能退，自动退等于货款两失。
     */
    if (type === "REFUND_ONLY" && o.amount.paidMinor <= TRADE_RULES.instantRefundMaxMinor) {
      settleRefund(o, "极速退款已到账");
    }
    persist();
    // 返回售后单本身 —— 与后端同形（端上拿它刷新，不是拿它替换订单）
    return delay(o.afterSale!);
  },

  async afterSaleReasons() {
    // 与后端 AfterSaleServiceImpl.REASONS 同一份清单（码，不是文案）
    return delay<AfterSaleReason[]>([
      "NOT_WANTED", "DAMAGED", "MISSING", "WRONG_ITEM", "QUALITY", "EXPIRED", "OTHER",
    ]);
  },

  async orderPreview(req) {
    /*
     * mock 里没有服务端活动，沿用与下单同一套定价策略 —— 两者算出同一个数才是 mock 的价值。
     * **不扣库存**：预览是只读的，用户会在结算页反复改地址与履约方式。
     */
    const items: OrderItem[] = req.items.map((it) => {
      const g = toGoods(findGoodsSeed(it.goodsNo));
      const sku = g.skus.find((s) => s.skuNo === it.skuNo);
      if (!sku) throw new Error("规格不存在");
      return {
        goodsNo: g.goodsNo, merchantNo: g.merchant.merchantNo, skuNo: sku.skuNo,
        title: g.title, cover: g.cover, spec: sku.spec, price: sku.price, qty: it.qty,
        type: g.type, nominalGram: sku.nominalGram, weighed: g.weighed, points: g.points,
      };
    });
    if (!items.length) throw new Error("订单商品为空");
    const couponSeed = db.couponSeeds.find((c) => c.couponNo === req.couponNo);
    const coupon: Coupon | undefined = couponSeed
      ? { ...couponSeed, title: pick(couponSeed.title), scopeDesc: pick(couponSeed.scopeDesc) }
      : undefined;
    const amount = pricingFor(items[0]!.type).estimate(items, {
      fulfillment: req.fulfillment,
      currency: currentCurrency(),
      coupon,
      usePoints: Math.max(0, Math.min(pointBalance(db.points), req.usePoints ?? 0)),
      earnPoints: 0,
    });
    return delay({ amount, items });
  },

  /**
   * 结算页能力提示。
   *
   * mock 里造两种小微形态：**不可开票**与**额度将超**。
   * 造成「全都正常」的话这块提示永远不出现，等于没做 —— mock 的价值恰恰是
   * 让人在开发时就看见那几条提示长什么样。
   */
  async orderCapability(req) {
    const seen = new Map<string, { name: string; micro: boolean }>();
    for (const it of req.items) {
      const g = toGoods(findGoodsSeed(it.goodsNo));
      // 约定：mock 里 merchantNo 以 M9 开头的当作小微，用来演示提示
      seen.set(g.merchant.merchantNo, {
        name: g.merchant.name,
        micro: g.merchant.merchantNo.startsWith("M9"),
      });
    }
    const merchants = [...seen.entries()].map(([merchantNo, m]) => ({
      merchantNo,
      merchantName: m.name,
      invoiceCapable: !m.micro,
      // 小微通常没有 H5/APP —— 混合购物车里有一件小微的货，整单就只剩 JSAPI
      payMethods: m.micro ? ["JSAPI"] : ["JSAPI", "H5", "APP"],
      quotaExhausted: false,
      quotaWouldExceed: false,
    }));
    // 与后端同口径：一个商家都没配时返回 null（未配置），不是空数组（无交集）
    const configured = merchants.filter((m) => m.payMethods.length);
    const usable = configured.length
      ? configured.map((m) => m.payMethods).reduce((a, b) => a.filter((x) => b.includes(x)))
      : null;
    /*
     * 支付方式（线上/当面）。mock 里的约定：**自提与到店核销给当面付**，
     * 快递不给 —— 与后端那道闸同口径（货已寄出，没有当面收款的那一刻）。
     *
     * 不是「全都给」：开发期看不见「这一单只能线上付」长什么样的话，
     * 那个分支等于没做。这与上面造两种小微形态是同一个理由。
     */
    const offlineOk = req.fulfillment === FULFILLMENT.PICKUP
      || req.fulfillment === FULFILLMENT.STORE_VERIFY
      || req.fulfillment === FULFILLMENT.DELIVERY;
    return delay({
      usablePayMethods: usable,
      anyNotInvoiceCapable: merchants.some((m) => !m.invoiceCapable),
      merchants,
      usablePayModes: offlineOk ? [PAY_MODE.ONLINE, PAY_MODE.OFFLINE] : [PAY_MODE.ONLINE],
    });
  },

  async afterSaleList() {
    // 售后是独立资源：从订单上摘出来，而不是拿订单状态冒充
    return delay(db.orders.filter((o) => o.afterSale).map((o) => o.afterSale!));
  },

  async fillReturnExpress(afterSaleNo, expressNo) {
    const o = findOrderByAfterSale(afterSaleNo);
    const as = o.afterSale!;
    if (as.type !== "RETURN_REFUND") throw new Error("该售后单不是退货退款");
    // 只有商家同意之后才谈得上寄回 —— 没同意就寄，货可能被拒收。
    // 后端同意即进 REFUNDING，没有独立的「等寄回」「已收货」两态：
    // 退货物流走 expressNo 字段，不是状态（见 AfterSaleStatus 的说明）
    if (as.status !== "REFUNDING") throw new Error("商家同意后才能填写退货单号");
    if (!expressNo.trim()) throw new Error("请填写退货运单号");
    as.returnExpressNo = expressNo.trim();
    as.updatedAt = Date.now();
    pushTimeline(o, `已寄回，运单号 ${as.returnExpressNo}`);
    persist();
    return delay(as);
  },

  async raiseDispute(afterSaleNo, reason) {
    const o = findOrderByAfterSale(afterSaleNo);
    const as = o.afterSale!;
    // **只有被驳回才谈得上申诉** —— 商家还没处理就上升，等于跳过协商
    if (as.status !== "REJECTED") throw new Error("商家驳回后才能申请平台介入");
    as.status = "ARBITRATING";
    as.disputeReason = reason;
    as.updatedAt = Date.now();
    pushTimeline(o, "已申请平台介入");
    pushMessage(
      "TRADE",
      "平台介入已受理",
      "客服会在 1 个工作日内联系双方核实",
      `/pages/order/index?orderNo=${o.orderNo}`,
    );
    persist();
    return delay(as);
  },
};
