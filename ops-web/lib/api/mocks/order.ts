// 覆盖范围：订单管理（P-4.1）。
import * as db from "@/lib/mock/db";
import { STUCK_MINUTES } from "@/lib/constants";
import { ORDER_TRANSITIONS } from "@/lib/types";
import type { ExceptionKind, Order, OrderException } from "@/lib/types";
import type { OrderApi } from "../contracts/order";
import { fail, notFound } from "@/lib/biz-error";
import { wait } from "./_wait";

function find(orderNo: string): Order {
  const o = db.orders.find((x) => x.orderNo === orderNo);
  if (!o) notFound("订单", "Order", orderNo);
  return o;
}

/**
 * 把一条订单算成异常单；不异常返回 null。
 *
 * 实时算 —— 见 contracts/order.ts 里 listExceptionOrders 的说明。
 */
function toException(o: Order): OrderException | null {
  const thresholdMinutes = STUCK_MINUTES[o.status];
  if (thresholdMinutes == null) return null; // 终态不设时限
  const since = o.statusAt ?? o.paidAt ?? o.createdAt;
  const stuckMinutes = Math.floor((Date.now() - new Date(since).getTime()) / 60_000);
  if (stuckMinutes <= thresholdMinutes) return null;
  // 待支付超时还没关单 = 关单任务本身出了问题，与"卡住"不是一回事，处置也不同
  const kind: ExceptionKind = o.status === "PENDING_PAY" ? "PAY_TIMEOUT" : "STUCK";
  return { order: o, kind, stuckMinutes, thresholdMinutes };
}

export const orderMock: OrderApi = {
  listOrders: (q = {}) =>
    wait(
      db.paginate(db.orders, q.page, q.size, (o) =>
        db.scopeHit(q, o) &&
        db.eqHit(q.status, o.status) &&
        db.eqHit(q.fulfillType, o.fulfillType) &&
        db.eqHit(q.trafficSource, o.trafficSource) &&
        db.kwHit(q.keyword, o.orderNo, o.parentNo, o.merchantName, o.buyerNickname),
      ),
    ),

  // async：同步 throw 拿不到 rejected promise（见 mocks/merchant.ts 顶部说明）
  getOrder: async (orderNo) => {
    const o = db.orders.find((x) => x.orderNo === orderNo);
    if (!o) notFound("订单", "Order", orderNo);
    return wait(o);
  },

  listSiblingOrders: (parentNo) => wait(db.orders.filter((o) => o.parentNo === parentNo)),

  listExceptionOrders: (q = {}) => {
    const all = db.orders
      .map(toException)
      .filter((e): e is OrderException => e !== null)
      // 卡得越久越靠前：队列的意义就是先处理最久的那条
      .sort((a, b) => b.stuckMinutes - a.stuckMinutes);
    return wait(
      db.paginate(all, q.page, q.size, (e) =>
        db.eqHit(q.kind, e.kind) && db.kwHit(q.keyword, e.order.orderNo, e.order.merchantName, e.order.buyerNickname),
      ),
    );
  },

  interveneOrder: async ({ orderNo, to, remark }) => {
    const o = find(orderNo);
    if (!remark.trim()) fail("人工改状态必须写原因 —— 这是覆盖了系统的判断", "Changing the state by hand needs a reason — it overrides the system's own judgement");
    // 人工干预也走状态机：绕过去就等于这套状态机不存在
    db.assertTransition(ORDER_TRANSITIONS, o.status, to, "订单", "Order");
    db.orderInterventions.unshift({
      orderNo, from: o.status, to, remark: remark.trim(), operator: "admin", at: new Date().toISOString(),
    });
    o.status = to;
    o.statusAt = new Date().toISOString();
    return wait(o, 350);
  },

  listOrderInterventions: (orderNo) => wait(db.orderInterventions.filter((x) => x.orderNo === orderNo)),

  proxyCancelOrder: async ({ orderNo, reason }) => {
    const o = find(orderNo);
    if (!reason.trim()) fail("代客取消必须写原因，否则用户来问时没人说得清", "Cancelling for a customer needs a reason, or nobody can explain it when they ask");
    db.assertTransition(ORDER_TRANSITIONS, o.status, "CANCELLED", "订单", "Order");
    // 已支付的取消必然退款：契约里不给"不退款"这条路，这里也不需要判断
    db.orderInterventions.unshift({
      orderNo, from: o.status, to: "CANCELLED", remark: `代客取消：${reason.trim()}`,
      operator: "admin", at: new Date().toISOString(),
    });
    o.status = "CANCELLED";
    o.statusAt = new Date().toISOString();
    return wait(o, 350);
  },

  createProxyOrder: async ({ buyerNickname, communityNo, merchantNo, fulfillType, items, reason }) => {
    if (!buyerNickname.trim()) fail("请填写下单人", "Enter who the order is for");
    if (!reason.trim()) fail("代客下单必须写原因 —— 它绕过了用户自主下单", "A proxy order needs a reason — it bypasses the customer ordering for themselves");
    if (!items.length) fail("至少要选一个商品", "Pick at least one item");

    const community = db.communities.find((x) => x.communityNo === communityNo);
    if (!community) notFound("社区", "Community", communityNo);
    const merchant = db.merchants.find((x) => x.merchantNo === merchantNo);
    if (!merchant) notFound("商家", "Merchant", merchantNo);

    const lines = items.map(({ skuNo, qty }) => {
      const sku = db.skus.find((x) => x.skuNo === skuNo);
      if (!sku) notFound("商品", "Item", skuNo);
      // 一次只能下一个商家的货：全站按商家拆单（E3），混着下会拆出一张对不上的单
      if (sku.merchantNo !== merchantNo) fail(`${sku.title.zh} 不属于所选商家，跨商家请分开下单`, `${sku.title.en ?? sku.title.zh} belongs to another merchant — place separate orders`);
      if (sku.status !== "ON_SALE") fail(`${sku.title.zh} 当前不可售（${sku.status}）`, `${sku.title.en ?? sku.title.zh} is not on sale (${sku.status})`);
      if (!Number.isInteger(qty) || qty <= 0) fail("数量必须是正整数", "Quantity must be a positive whole number");
      if (qty > sku.stock) fail(`${sku.title.zh} 库存不足，仅剩 ${sku.stock}`, `${sku.title.en ?? sku.title.zh} is short on stock — only ${sku.stock} left`);
      const price = sku.prices.CN;
      if (price == null) fail(`${sku.title.zh} 缺少价格，无法下单`, `${sku.title.en ?? sku.title.zh} has no price and cannot be ordered`);
      return { sku, line: { skuNo, title: sku.title.zh, qty, price } };
    });

    // 校验全部通过后才扣库存：中途抛错会留下扣了一半的库存
    for (const { sku, line } of lines) sku.stock -= line.qty;

    const now = new Date().toISOString();
    const seq = db.orders.length + 1;
    const order: Order = {
      orderNo: `SO${now.slice(0, 10).replace(/-/g, "")}P${seq}`,
      parentNo: `PO${now.slice(0, 10).replace(/-/g, "")}P${seq}`,
      status: "PENDING_PAY", // 代客下单**不代付款**：钱必须由用户自己付
      merchantNo, merchantName: merchant.name,
      communityNo, communityName: community.name,
      fulfillType, trafficSource: "PLATFORM",
      buyerNickname: buyerNickname.trim(),
      items: lines.map((x) => x.line),
      payAmount: lines.reduce((s, x) => s + x.line.price * x.line.qty, 0),
      createdAt: now, paidAt: null, statusAt: now,
    };
    db.orders.unshift(order);
    db.orderInterventions.unshift({
      orderNo: order.orderNo, from: "PENDING_PAY", to: "PENDING_PAY",
      remark: `代客下单：${reason.trim()}`, operator: "admin", at: now,
    });
    return wait(order, 400);
  },
};
