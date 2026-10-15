// 售后 —— C 端替身的一域。
//
// 从 `api/mock.ts`（1728 行 / 86 个接口）按域拆出来；实现一个字没改。
// 合并在 `mocks/index.ts`，那里的类型标注保证**一个接口都不能少**。

import { db, delay, findGoodsSeed, nextNo, persist, pick, pointBalance, pushMessage, toGoods } from "@shared/mock/db";
import { pricingFor } from "@shared/strategies/pricing";
import { couponDiscount } from "@shared/strategies/pricing/types";
import type { AfterSaleReason, Coupon, Order, OrderItem } from "@shared/types";
import { ACTIVITY_NONE, FULFILLMENT, PAY_MODE } from "@shared/utils/constants";
import { currentCurrency } from "@shared/utils/money";
import { orderStageOf } from "@shared/strategies/after-sale";
import {
  findOrder,
  findOrderByAfterSale,
  pushTimeline,
  boughtQtyOf,
  limitOf,
  couponSeedOfUserCoupon,
} from "./_shared";
import type { ShopApi } from "../contract";

export const aftersaleMock: Pick<ShopApi,
  "applyAfterSale"
  | "afterSaleReasons"
  | "orderPreview"
  | "orderCapability"
  | "afterSaleList"
  | "cancelAfterSale"
  | "fillReturnExpress"
  | "raiseDispute"
> = {
  // ---------------------------------------------------------------- 售后
  async applyAfterSale(orderNo, reason, images, type = "REFUND_ONLY") {
    const o = findOrder(orderNo);
    if (!["PAID", "FULFILLING", "COMPLETED"].includes(o.status) || (o.afterSale && !["CLOSED", "REJECTED"].includes(o.afterSale.status))) throw new Error("当前订单不能重复申请退款");
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
      // 极速退已废除（售后规则 §2.3）：退款一律先审核，apply 不再秒退
      instant: false,
      // 申请当时的履约进度快照（与后端一致，B 端审核据它显示实况）
      fulfillStage: orderStageOf(o),
      updatedAt: Date.now(),
    };
    pushTimeline(o, `已申请${type === "RETURN_REFUND" ? "退货退款" : "仅退款"}：${reason}`);
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
    if ((req.usePoints ?? 0) !== 0) throw new Error("本期暂不支持积分抵扣");
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
    const couponSeed = couponSeedOfUserCoupon(req.couponNo);
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
    /*
     * **mock 里的一个店铺活动「满 30 减 5」**（优惠券全链路梳理 批 2）：按顾客的选择生效，
     * 券在活动之后的金额上算 —— 与后端 discountsOf 同一个顺序。没有它的话，
     * 下单页「换活动 / 不参加 / 换回最省」那一整段在本机永远看不见，改坏了也不知道。
     */
    const MOCK_ACT = { activityNo: "MOCK-CUT-5", name: "满 30 减 5", amountMinor: 500 };
    const merchantNo = items[0]!.merchantNo;
    const merchantName = toGoods(findGoodsSeed(items[0]!.goodsNo)).merchant.name;
    const goodsMinor = items.reduce((n, it) => n + it.price * it.qty, 0);
    const actHits = goodsMinor >= 3000;
    const choice = req.activityChoices?.find((c) => c.merchantNo === merchantNo)?.activityNo;
    const actOff = actHits && choice !== ACTIVITY_NONE ? MOCK_ACT.amountMinor : 0;
    const couponOff = couponDiscount(coupon, goodsMinor - actOff);
    amount.discountMinor = actOff + couponOff;
    amount.payableMinor = Math.max(0, goodsMinor + amount.freightMinor - amount.discountMinor
      - (amount.pointsDeductMinor ?? 0));
    const discountLines = [
      ...(actOff ? [{ kind: "ACTIVITY" as const, name: MOCK_ACT.name, amountMinor: actOff }] : []),
      ...(couponOff && coupon ? [{ kind: "COUPON" as const, name: coupon.title, amountMinor: couponOff }] : []),
    ];
    /*
     * 最省组合：与后端一样把「活动 × 券」一起枚举（这里只有一个活动 + 券包里的券）。
     * 同额取靠前的：活动参加在前、券「不用」在前 —— 建议的总是改动最少的那个。
     */
    const heldNos = ["UC0001", ...db.couponSeeds.filter((c) => c.received).map((c) => `UC-${c.couponNo}`)];
    let best = { act: false, coupon: null as string | null, total: -1 };
    for (const act of actHits ? [true, false] : [false]) {
      const off = act ? MOCK_ACT.amountMinor : 0;
      for (const no of [null, ...heldNos]) {
        const seed = couponSeedOfUserCoupon(no ?? undefined);
        const c = seed ? { ...seed, title: pick(seed.title), scopeDesc: pick(seed.scopeDesc) } : undefined;
        const cOff = no ? couponDiscount(c, goodsMinor - off) : 0;
        if (no && cOff <= 0) continue;
        if (off + cOff > best.total) best = { act, coupon: no, total: off + cOff };
      }
    }
    const offers = {
      merchants: actHits ? [{
        merchantNo, merchantName, options: [MOCK_ACT],
        chosen: actOff ? MOCK_ACT.activityNo : ACTIVITY_NONE,
      }] : [],
      suggestedChoices: actHits
        ? [{ merchantNo, activityNo: best.act ? MOCK_ACT.activityNo : ACTIVITY_NONE }] : [],
      suggestedCouponNo: best.coupon,
      suggestedDiscountMinor: Math.max(best.total, 0),
    };
    /*
     * **每行给出上限**（执行计划 B2）：下单页的步进器要知道还能加到几。
     * mock 里给库存那个数；不给的话步进器在本机永远不压暗，改坏了也看不出来。
     */
    const withMax = items.map((it) => {
      const g = toGoods(findGoodsSeed(it.goodsNo));
      const sku = g.skus.find((s) => s.skuNo === it.skuNo);
      const stock = sku?.stock ?? null;
      // 每人限购（P1）：与后端同一口径 —— 同一件货其他规格在这一单里占掉的名额要扣掉
      const limit = limitOf(it.goodsNo);
      if (!limit || stock == null) return { ...it, maxQty: stock, limitReason: "STOCK" as const };
      const bought = boughtQtyOf(it.goodsNo);
      const others = items.filter((o) => o !== it && o.goodsNo === it.goodsNo).reduce((n, o) => n + o.qty, 0);
      const byLimit = Math.max(0, limit - bought - others);
      return byLimit < stock
        ? { ...it, maxQty: byLimit, limitReason: "PER_USER" as const, limitPerUser: limit, boughtQty: bought }
        : { ...it, maxQty: stock, limitReason: "STOCK" as const, limitPerUser: limit, boughtQty: bought };
    });
    return delay({ amount, items: withMax, discountLines, offers });
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
      /*
       * 自送圆心与半径。**mock 里必须给一个真值**，理由与上面造两种小微形态一样：
       * 都放空（= 三条放行全命中）的话，「这家送不到这个地址」那条提示
       * 在开发期永远不出现，等于没做。
       *
       * 落在杭州西湖区、半径 3 公里：种子里那条没坐标的地址会被放行（本就该放行），
       * 而在地图上选到市区外的地址会被判出圈 —— 两种都看得见。
       */
      deliveryLatE6: 30_200_000,
      deliveryLngE6: 120_100_000,
      deliveryRadiusM: 3000,
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
      pointsEnabled: false,
      usablePayMethods: usable,
      anyNotInvoiceCapable: merchants.some((m) => !m.invoiceCapable),
      merchants,
      usablePayModes: offlineOk ? [PAY_MODE.ONLINE, PAY_MODE.OFFLINE] : [PAY_MODE.ONLINE],
    });
  },

  async cancelAfterSale(afterSaleNo) {
    const o = findOrderByAfterSale(afterSaleNo);
    if (!["APPLIED", "NEGOTIATING", "WAIT_RETURN", "REJECTED"].includes(o.afterSale!.status)) {
      throw new Error("当前售后无法撤销");
    }
    o.afterSale!.status = "CLOSED";
    o.afterSale!.updatedAt = Date.now();
    pushTimeline(o, "买家撤销售后");
    persist();
    return delay(o.afterSale!);
  },

  async afterSaleList() {
    // 售后是独立资源：从订单上摘出来，而不是拿订单状态冒充
    return delay(db.orders.filter((o) => o.afterSale).map((o) => o.afterSale!));
  },

  async fillReturnExpress(afterSaleNo, expressNo) {
    const o = findOrderByAfterSale(afterSaleNo);
    const as = o.afterSale!;
    if (as.type !== "RETURN_REFUND") throw new Error("该售后单不是退货退款");
    // 只有商家同意退货之后才谈得上寄回（后端 WAIT_RETURN → 填运单 → RETURNING）
    if (as.status !== "WAIT_RETURN") throw new Error("商家同意后才能填写退货单号");
    if (!expressNo.trim()) throw new Error("请填写退货运单号");
    as.returnExpressNo = expressNo.trim();
    as.status = "RETURNING";
    as.updatedAt = Date.now();
    pushTimeline(o, `已寄回，运单号 ${as.returnExpressNo}`);
    persist();
    return delay(as);
  },

  async raiseDispute(afterSaleNo, reason) {
    const o = findOrderByAfterSale(afterSaleNo);
    const as = o.afterSale!;
    // 能不能上升由后端判（被驳回 / 在途待签收 / 商家超时）；mock 只模拟最常见的「被驳回」
    if (!(as.canEscalate ?? as.status === "REJECTED")) throw new Error("现在还不能申请平台介入");
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
