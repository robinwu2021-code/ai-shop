// 交易：结算、下单、支付、订单 —— C 端替身的一域。
//
// 从 `api/mock.ts`（1728 行 / 86 个接口）按域拆出来；实现一个字没改。
// 合并在 `mocks/index.ts`，那里的类型标注保证**一个接口都不能少**。

import type { CreateOrderReq } from "../contract";
import { allCommunitySeeds, assertTransition, db, delay, findGoodsSeed, merchantBrief, nextNo, paginate, persist, pick, pointBalance, pushMessage, pushPoint, toCommunity, toGoods } from "@shared/mock/db";
import { fulfillmentFor } from "@shared/strategies/fulfillment";
import { earnPointsFor, pricingFor } from "@shared/strategies/pricing";
import type { Coupon, InvoiceRequest, Order, OrderItem, PageQuery } from "@shared/types";
import { CATEGORY_TYPE, FULFILLMENT, TRADE_RULES } from "@shared/utils/constants";
import { currentCurrency } from "@shared/utils/money";
import { buyNGetM, giftQtyFor } from "@shared/utils/promotion";
import {
  findOrder,
  grantPointsOnComplete,
  issueCard,
  pushTimeline,
} from "./_shared";
import type { ShopApi } from "../contract";

/**
 * mock 内部的订单形状：契约的 `Order` **加上**一个分组键。
 *
 * <p>`payGroupNo` 只是 mock 用来把同一次结算拆出的子单串起来的东西，
 * 后端库里与 VO 里都没有这一列 —— 放进契约类型的后果，是端上写出
 * 一段只在 mock 下成立的逻辑（详情页那句拆单提示就是这么哑了半年）。
 */
type MockOrder = Order & { payGroupNo?: string };

export const tradeMock: Pick<ShopApi,
  "createOrder"
  | "payMethods"
  | "payOrder"
  | "orderList"
  | "applyInvoice"
  | "myInvoices"
  | "invoiceOfOrder"
  | "orderDetail"
  | "cancelOrder"
> = {
  // ---------------------------------------------------------------- 交易
  async createOrder(req: CreateOrderReq) {
    // 幂等：同一 key 重复提交返回同一单
    const dup = db.orders.find((o) => o.idempotencyKey === req.idempotencyKey);
    if (dup) return delay(dup);

    /*
     * 上门预约的两道闸，**与后端逐条同形**。
     *
     * mock 放行而后端拒收，是最坏的一种不一致：本地怎么点都对，
     * 一连真后端就拿到一个说不清的错误，而那时候没人会想到是 mock 太宽松。
     */
    if (req.fulfillment === FULFILLMENT.APPOINTMENT) {
      if (!req.appointmentAt || req.appointmentAt <= Date.now()) {
        throw new Error("请选择上门时段");
      }
      if (!req.addressId) throw new Error("上门服务需要收货地址");
    }

    const items: OrderItem[] = req.items.map((it) => {
      const seed = findGoodsSeed(it.goodsNo);
      const g = toGoods(seed);
      const sku = g.skus.find((s) => s.skuNo === it.skuNo);
      const rawSku = seed.skus.find((s) => s.skuNo === it.skuNo);
      if (!sku || !rawSku) throw new Error("规格不存在");
      if (g.cutoffAt && Date.now() > g.cutoffAt) throw new Error(`「${g.title}」已过截单时间`);
      if (rawSku.stock < it.qty) throw new Error(`「${g.title}」库存不足`);
      rawSku.stock -= it.qty; // 锁库（改种子，重开可读回）
      return {
        goodsNo: g.goodsNo,
        merchantNo: g.merchant.merchantNo,
        skuNo: sku.skuNo,
        title: g.title,
        cover: g.cover,
        spec: sku.spec,
        price: sku.price,
        qty: it.qty,
        type: g.type,
        nominalGram: sku.nominalGram,
        weighed: g.weighed,
        points: g.points,
      };
    });

    // 买赠：赠品作为价格为 0 的独立行，不参与计价，履约时随单发出
    const giftItems: OrderItem[] = [];
    for (const it of items) {
      const g = toGoods(findGoodsSeed(it.goodsNo));
      const n = giftQtyFor(buyNGetM(g.promotions), it.qty);
      if (n > 0) giftItems.push({ ...it, price: 0, qty: n, isGift: true });
    }
    items.push(...giftItems);

    if (!items.length) throw new Error("订单商品为空");

    const couponSeed = db.couponSeeds.find((c) => c.couponNo === req.couponNo);
    const coupon: Coupon | undefined = couponSeed
      ? { ...couponSeed, title: pick(couponSeed.title), scopeDesc: pick(couponSeed.scopeDesc) }
      : undefined;

    // 用户可用积分不能超过账户余额 —— 这条必须在服务端校验，端上传什么都不能信
    const balance = pointBalance(db.points);
    const wantPoints = Math.max(0, Math.min(balance, req.usePoints ?? 0));

    const plan = fulfillmentFor(req.fulfillment).plan({
      pickupNo: req.pickupNo,
      communities: allCommunitySeeds().map(toCommunity),
      appointmentAt: req.appointmentAt,
    });

    /**
     * **按商家拆单**（E3）。购物车跨商家时拆成多笔子订单，一单只属于一个商家。
     *
     * 为什么必须拆：分账以子订单为单位（ADR-002 §5）——
     * 一笔钱要分给几家、各分多少，不拆就没有承载的单据；
     * 退款回退分账、履约服务费归属、商家看自己的单，全都依赖这个粒度。
     *
     * 用户感知不变：同一次结算的子订单共享一个**支付组号**，一次付掉整组。
     */
    const byMerchant = new Map<string, OrderItem[]>();
    for (const it of items) {
      byMerchant.set(it.merchantNo, [...(byMerchant.get(it.merchantNo) ?? []), it]);
    }

    const payGroupNo = nextNo("PG");
    const created: Order[] = [];

    for (const [merchantNo, subItems] of byMerchant) {
      const priced = subItems.filter((it) => !it.isGift);
      const head = priced[0] ?? subItems[0]!;
      // 优惠只作用在**第一笔**子订单上：券与积分是整单概念，
      // 按商家摊分需要业务口径（哪家承担、怎么摊），未定之前不臆造 —— 见 M4/B10
      const isFirst = created.length === 0;
      const amount = pricingFor(head.type).estimate(subItems, {
        fulfillment: req.fulfillment,
        currency: currentCurrency(),
        coupon: isFirst ? coupon : undefined,
        usePoints: isFirst ? wantPoints : 0,
        earnPoints: earnPointsFor(subItems),
      });

      const order: MockOrder = {
        orderNo: nextNo("SO"),
        status: "WAIT_PAY",
        fulfillment: req.fulfillment,
        items: subItems,
        amount,
        pickupNo: plan.pickupNo,
        pickupName: plan.pickupName,
        appointmentAt: plan.appointmentAt,
        createdAt: Date.now(),
        payDeadlineAt: Date.now() + TRADE_RULES.payTimeoutMinutes * 60_000,
        timeline: [{ status: "WAIT_PAY", label: "已下单，待支付", at: Date.now() }],
        // 幂等 key 只挂在首单上：重复提交时靠它命中，返回同一组
        idempotencyKey: isFirst ? req.idempotencyKey : undefined,
        groupNo: req.groupNo,
        merchantNo,
        merchantName: merchantBrief(merchantNo).name,
        // mock 内部的分组键。**不是契约字段** —— 后端没有这一列，
        // 端上要判的是「这次支付覆盖几笔」，那个由 payGroupSize 表达
        payGroupNo,
        payGroupSize: byMerchant.size,
        trafficSource: db.user.merchantNo === merchantNo ? "MERCHANT_OWNED" : "PLATFORM",
      };

      // 抵扣的积分**下单即扣**（不是支付后）：不扣的话用户能同时下多单花同一笔积分
      if (amount.pointsUsed > 0) {
        pushPoint(db.points, "USE", -amount.pointsUsed, "下单抵扣", order.orderNo);
      }
      db.orders.unshift(order);
      created.push(order);
    }

    // 下单成功即从购物车移除这些 sku（赠品行不在购物车里，跳过）
    const orderedSkus = new Set(req.items.map((it) => it.skuNo));
    db.cart = db.cart.filter((c) => !orderedSkus.has(c.skuNo));
    persist();
    return delay(created[0]!);
  },

  async payMethods(orderNo: string) {
    // mock 给「一个可用 + 一个不可用带原因」—— 两种状态都要能在界面上看到
    return {
      currency: "CNY",
      configured: true,
      methods: [
        { methodCode: "TEST", payChannel: "TEST", name: "测试渠道",
          available: true, unavailableReason: null },
        { methodCode: "ALIPAY", payChannel: "ALIPAY", name: "支付宝",
          available: false, unavailableReason: "本单中有店铺尚未开通这种收款方式" },
      ],
    };
  },

  async payOrder(orderNo) {
    const target = findOrder(orderNo);

    /**
     * **一次支付付掉整个支付组**（E3 拆单的另一半）。
     *
     * 拆单是资金侧的需要（分账以子订单为单位），但用户感知必须还是「买了一次」——
     * 只把点进来的那一单置为已支付，用户会在订单列表里看到「付了一单还剩一单」，
     * 而他明明只付了一次钱。
     */
    // 分组键是 mock 内部的（后端没有这一列），所以在这里显式当 MockOrder 用
    const pg = (target as MockOrder).payGroupNo;
    const group = pg
      ? db.orders.filter((o) => (o as MockOrder).payGroupNo === pg)
      : [target];

    for (const o of group) {
      if (o.status !== "WAIT_PAY") continue; // 组内已处理过的跳过，重复点支付不报错
      assertTransition(o.status, "PAID");
      o.status = "PAID";
      o.amount.paidMinor = o.amount.payableMinor;
      pushTimeline(o, "支付成功");

      const strategy = fulfillmentFor(o.fulfillment);

      // 虚拟商品 / 卡券：支付成功即发放，不经备货，直接完成
      if (strategy.instant) {
        /*
         * **发到 `verifyCode` 而不是另起一个 `redeemCode`** ——
         * 后端 `OrderVO` 把自提码 / 核销码 / 兑换码三态合在这一个字段里，
         * mock 另发一个字段的后果是：本机能看到兑换码，真机永远看不到。
         */
        o.verifyCode = strategy.issueCode();
        o.items
          .filter((it) => it.type === CATEGORY_TYPE.CARD)
          .forEach((it) => issueCard(o, it));
        assertTransition(o.status, "COMPLETED");
        o.status = "COMPLETED";
        pushTimeline(o, "已发放");
        grantPointsOnComplete(o);
        continue;
      }

      /*
       * 没有独立的备货态：付款后就是 PAID（待发货），与后端一致。
       *
       * ⚠️ **这里不再断言一次 PAID**：上面几行已经把它从 WAIT_PAY 转成 PAID 了，
       * 再断言一次就是 PAID → PAID，非法迁移直接抛。
       * 此前没暴露，是因为 mock 里<b>一条待付款订单都没有</b> ——
       * 这条路径从来没被走到过。2026-09-02 加了待付款种子，它当场就红了。
       */
      o.verifyCode = strategy.issueCode();
      pushTimeline(o, "商家备货中");
    }

    // 消息按「一次结算」发一条，不是每个子订单发一条 —— 拆单是内部实现，不该泄漏成消息轰炸
    const titles = group
      .flatMap((o) => o.items.filter((i) => !i.isGift).map((i) => i.title))
      .join("、");
    pushMessage(
      "TRADE",
      group.length > 1 ? `支付成功，${group.length} 家商家备货中` : "支付成功，商家备货中",
      `${titles} 到货后会通知你`,
      `/pages/order/index?orderNo=${target.orderNo}`,
    );

    persist();
    /*
     * **返回 PayInit，不是 Order**（C-1/C-2 之后）。
     *
     * mock 仍然直接把订单推进成已支付 —— 那是 mock 的便利，
     * 而返回值要与真实契约一致：端上拿 payParams 去唤起收银台。
     * 返回 Order 的话端上编译不过，那正是我们要的：
     * <b>契约变了，mock 必须跟着变</b>，否则 mock 下能跑而真实链路跑不通。
     */
    return delay({
      orderNo,
      payChannel: "TEST",
      payParams: {
        prepayId: "mock_" + orderNo,
        outTradeNo: orderNo,
        amount: String(target.amount ?? 0),
        testChannel: "true",
      },
    });
  },

  async orderList(q: PageQuery & { status?: string; fulfillments?: string[] }) {
    // 两个条件正交，各筛各的 —— 与真实后端同形（页签是谓词，不是状态值）
    const want = q.fulfillments?.length ? new Set(q.fulfillments) : null;
    const list = db.orders.filter(
      (o) => (!q.status || o.status === q.status) && (!want || want.has(o.fulfillment)),
    );
    return delay(paginate(list, q.page, q.size));
  },

  async applyInvoice(req) {
    const order = db.orders.find((x) => x.orderNo === req.orderNo);
    if (!order) throw new Error("订单不存在");
    // mock 也照真实边界来：未支付的单不能开票。恒成功的话，
    // 「什么时候该出现这个入口」这段永远走不到
    if (order.status === "WAIT_PAY" || order.status === "CANCELLED") {
      throw new Error("这笔订单还没有成交，无法开票");
    }
    if (req.titleType === "COMPANY" && !req.taxNo?.trim()) {
      throw new Error("单位抬头需要税号");
    }
    const exist = db.invoiceRequests.find((x) => x.orderNo === req.orderNo);
    if (exist && exist.status !== "REJECTED") throw new Error("这笔订单已经申请过发票");
    if (exist) {
      // 被驳回后改抬头重提：**改同一条，不插新的** —— 与后端一致，
      // 插新的话同一订单会有两条，运营分不清该开哪张
      Object.assign(exist, { ...req, status: "REQUESTED", rejectReason: undefined });
      persist();
      return delay({ ...exist });
    }
    const r: InvoiceRequest = {
      requestNo: `INV${Date.now()}`,
      orderNo: req.orderNo,
      titleType: req.titleType,
      title: req.title,
      taxNo: req.taxNo,
      email: req.email,
      // 开票金额 = **应付**，不是商品小计：运费与优惠都在里面。
      // 取错的话票面金额与消费者实付对不上，对方入账时会被退回
      amountMinor: order.amount.payableMinor,
      status: "REQUESTED",
      createdAt: Date.now(),
    };
    db.invoiceRequests.push(r);
    persist();
    return delay({ ...r });
  },

  async myInvoices() {
    return delay(db.invoiceRequests.map((x) => ({ ...x })));
  },

  async invoiceOfOrder(orderNo) {
    const r = db.invoiceRequests.find((x) => x.orderNo === orderNo);
    // 没申请过返回 null 而不是抛错：那是常态不是错误
    return delay(r ? { ...r } : null);
  },

  async orderDetail(orderNo) {
    const o = findOrder(orderNo);
    /*
     * 同支付组的兄弟单一起带上（对齐后端 OrderVO 的支付视角）。
     * 收银台靠它显示「本次付款覆盖 N 笔订单」——
     * mock 不给的话，那一屏在 mock 下永远是哑的，而它恰恰是最该被看见的一屏。
     *
     * 只在**确实跨了商家**时带：单商家时 subOrders 等于把自己抄一遍，端上也不渲染。
     */
    const pg = (o as MockOrder).payGroupNo;
    const siblings = pg
      ? db.orders.filter((x) => (x as MockOrder).payGroupNo === pg)
      : [];
    return delay(siblings.length > 1 ? { ...o, subOrders: siblings } : o);
  },

  async cancelOrder(orderNo) {
    const o = findOrder(orderNo);
    assertTransition(o.status, "CANCELLED");
    o.status = "CANCELLED";
    // 释放锁库
    o.items.forEach((it) => {
      const sku = findGoodsSeed(it.goodsNo).skus.find((s) => s.skuNo === it.skuNo);
      if (sku) sku.stock += it.qty;
    });
    if (o.amount.pointsUsed > 0) {
      pushPoint(db.points, "REFUND", o.amount.pointsUsed, "订单取消返还", o.orderNo);
    }
    pushTimeline(o, "订单已取消");
    persist();
    return delay(o);
  },
};
