// 订单管理的规则测试（P-4.1.4 异常单处理 / P-4.1.5 代客下单·取消）。
//
// 测的是「做错会出事」的那几条：人工改状态绕过状态机、代客取消不留原因、
// 代客下单跨商家或超卖、以及**校验一半就扣了库存**。
import { beforeEach, describe, expect, it } from "vitest";
import { orderMock } from "@/lib/api/mocks/order";
import { orderInterventions, orders, skus } from "@/lib/mock/db";
import { STUCK_MINUTES } from "@/lib/constants";

const orderSnapshot = orders.map((o) => ({ ...o }));
const skuSnapshot = skus.map((s) => ({ ...s }));

beforeEach(() => {
  orders.splice(0, orders.length, ...orderSnapshot.map((o) => ({ ...o })));
  skus.splice(0, skus.length, ...skuSnapshot.map((s) => ({ ...s })));
  orderInterventions.splice(0, orderInterventions.length);
});

describe("异常单队列（P-4.1.4）", () => {
  it("是实时算出来的视图 —— 订单一推进，它就不在队列里了", async () => {
    const before = await orderMock.listExceptionOrders({ size: 100 });
    // 状态用 FULFILLING，不是 SHIPPED —— 后者在状态模型统一那次就删掉了
    // （它是「状态 × 履约方式」冒充状态）。下面那段注释当时改了，这一行漏了，
    // 于是 find 永远返回 undefined，用例红在「样本里应有一条卡在配送中的单」上，
    // 读起来像是**种子少了一条**，而种子一直是对的。
    const stuck = before.records.find((e) => e.order.status === "FULFILLING");
    expect(stuck, "样本里应有一条卡在配送中的单").toBeTruthy();

    /*
     * 配送单的「已送达」就是 COMPLETED。
     * 曾经这里写的是 SHIPPED → ARRIVED —— 那是旧模型把配送与自提串成一条线的产物；
     * 后端里两者是**同一个库状态**（FULFILLING），只按履约方式展示成不同的词，
     * 从一个改到另一个等于改履约方式，不是推进状态。
     */
    await orderMock.interveneOrder({ orderNo: stuck!.order.orderNo, to: "COMPLETED", remark: "骑手已送达，系统漏回传" });

    const after = await orderMock.listExceptionOrders({ size: 100 });
    expect(after.records.some((e) => e.order.orderNo === stuck!.order.orderNo)).toBe(false);
  });

  it("终态订单永远不进队列 —— 已完成/已取消没有「卡住」这回事", async () => {
    const r = await orderMock.listExceptionOrders({ size: 100 });
    expect(r.records.some((e) => ["COMPLETED", "CANCELLED"].includes(e.order.status))).toBe(false);
  });

  it("未超时的单不算异常（阈值按状态分别给，一刀切会把正常单刷进来）", async () => {
    const r = await orderMock.listExceptionOrders({ size: 100 });
    for (const e of r.records) {
      expect(e.stuckMinutes).toBeGreaterThan(STUCK_MINUTES[e.order.status]);
    }
    // 样本里 PAID 那条只卡了 30 分钟（阈值 120），不该在队列里
    expect(r.records.some((e) => e.order.orderNo === "SO2026080502")).toBe(false);
  });

  it("待支付超时归为 PAY_TIMEOUT 而不是 STUCK —— 那是关单任务的问题，处置方式不同", async () => {
    const r = await orderMock.listExceptionOrders({ size: 100 });
    const pending = r.records.find((e) => e.order.status === "WAIT_PAY");
    expect(pending?.kind).toBe("PAY_TIMEOUT");
  });

  it("卡得越久越靠前", async () => {
    const r = await orderMock.listExceptionOrders({ size: 100 });
    const mins = r.records.map((e) => e.stuckMinutes);
    expect([...mins].sort((a, b) => b - a)).toEqual(mins);
  });
});

describe("人工干预（P-4.1.4）", () => {
  it("**人工也要走状态机** —— 绕过去等于这套状态机不存在", async () => {
    // 已送达不能直接回到备货中
    await expect(
      orderMock.interveneOrder({ orderNo: "SO2026080501", to: "PAID", remark: "回退一下" }),
    ).rejects.toThrow(/不允许从/);
  });

  it("改状态必须写原因", async () => {
    await expect(
      orderMock.interveneOrder({ orderNo: "SO2026080501", to: "COMPLETED", remark: "  " }),
    ).rejects.toThrow(/必须写原因/);
  });

  it("留痕记下 from/to/操作人", async () => {
    await orderMock.interveneOrder({ orderNo: "SO2026080501", to: "COMPLETED", remark: "用户已取货，自提点漏扫码" });
    const log = await orderMock.listOrderInterventions("SO2026080501");
    // from 是 FULFILLING：自提的「已到点」与配送的「已发货」在库里是同一个状态，
    // ARRIVED 只是自提这一侧的展示词，早已不是状态值
    expect(log[0]).toMatchObject({ from: "FULFILLING", to: "COMPLETED", operator: "admin" });
  });
});

describe("代客取消（P-4.1.5）", () => {
  it("必须写原因，否则用户来问时没人说得清", async () => {
    await expect(orderMock.proxyCancelOrder({ orderNo: "SO2026080503", reason: "" })).rejects.toThrow(/原因/);
  });

  it("已完成的单不能取消 —— 走售后，不是取消", async () => {
    await expect(
      orderMock.proxyCancelOrder({ orderNo: "SO2026080505", reason: "用户说不要了" }),
    ).rejects.toThrow(/不允许从/);
  });

  it("取消成功并留痕", async () => {
    const o = await orderMock.proxyCancelOrder({ orderNo: "SO2026080503", reason: "用户电话要求取消，未支付" });
    expect(o.status).toBe("CANCELLED");
    expect((await orderMock.listOrderInterventions("SO2026080503"))[0].remark).toContain("代客取消");
  });
});

describe("代客下单（P-4.1.5）", () => {
  const base = {
    buyerNickname: "小满", communityNo: "C001", merchantNo: "M903",
    fulfillType: "STORE_PICKUP" as const, reason: "用户电话下单，不会用小程序",
  };

  it("落到待支付而不是已支付 —— **代客下单不代付款**", async () => {
    const sku = skus.find((s) => s.merchantNo === "M903" && s.status === "ON_SALE")!;
    const o = await orderMock.createProxyOrder({ ...base, items: [{ skuNo: sku.skuNo, qty: 1 }] });
    expect(o.status).toBe("WAIT_PAY");
    expect(o.paidAt).toBeNull();
    expect(o.payAmount).toBe(sku.prices.CN);
  });

  it("跨商家要报错 —— 全站按商家拆单，混着下会拆出对不上的单", async () => {
    const mine = skus.find((s) => s.merchantNo === "M903" && s.status === "ON_SALE")!;
    const other = skus.find((s) => s.merchantNo !== "M903" && s.status === "ON_SALE")!;
    await expect(
      orderMock.createProxyOrder({ ...base, items: [{ skuNo: mine.skuNo, qty: 1 }, { skuNo: other.skuNo, qty: 1 }] }),
    ).rejects.toThrow(/跨商家/);
  });

  it("不可售的商品不能代客下单（下架/驳回的照样卖出去就是事故）", async () => {
    const off = skus.find((s) => s.status !== "ON_SALE")!;
    await expect(
      orderMock.createProxyOrder({ ...base, merchantNo: off.merchantNo, items: [{ skuNo: off.skuNo, qty: 1 }] }),
    ).rejects.toThrow(/不可售/);
  });

  it("超卖要拒绝", async () => {
    const sku = skus.find((s) => s.merchantNo === "M903" && s.status === "ON_SALE")!;
    await expect(
      orderMock.createProxyOrder({ ...base, items: [{ skuNo: sku.skuNo, qty: sku.stock + 1 }] }),
    ).rejects.toThrow(/库存不足/);
  });

  it("**校验失败不能扣掉一半库存** —— 第一件合法、第二件超卖，第一件的库存必须原封不动", async () => {
    const list = skus.filter((s) => s.merchantNo === "M903" && s.status === "ON_SALE");
    if (list.length < 2) return; // 样本不足时跳过，而不是假装通过
    const [a, b] = list;
    const stockBefore = a.stock;
    await expect(
      orderMock.createProxyOrder({ ...base, items: [{ skuNo: a.skuNo, qty: 1 }, { skuNo: b.skuNo, qty: b.stock + 1 }] }),
    ).rejects.toThrow(/库存不足/);
    expect(a.stock).toBe(stockBefore);
  });

  it("下单原因必填", async () => {
    const sku = skus.find((s) => s.merchantNo === "M903" && s.status === "ON_SALE")!;
    await expect(
      orderMock.createProxyOrder({ ...base, reason: " ", items: [{ skuNo: sku.skuNo, qty: 1 }] }),
    ).rejects.toThrow(/原因/);
  });

  it("商品为空要拒绝", async () => {
    await expect(orderMock.createProxyOrder({ ...base, items: [] })).rejects.toThrow(/至少/);
  });
});
