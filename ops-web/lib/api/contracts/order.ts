// 覆盖范围：订单管理（P-4.1）。
import type { FulfillType, Order, OrderException, OrderIntervention, OrderStatus, Page } from "@/lib/types";
import type { OrderQ, PageQ } from "../query";

export type ExceptionQ = PageQ & { kind?: string };

export interface OrderApi {
  listOrders(q?: OrderQ): Promise<Page<Order>>;
  getOrder(orderNo: string): Promise<Order>;
  /** 同一次结算拆出的全部子订单（E3 按商家拆单，详情抽屉要能看到兄弟单）。 */
  listSiblingOrders(parentNo: string): Promise<Order[]>;

  // ── 异常单处理（P-4.1.4）────────────────────────────────────────

  /**
   * 异常单队列。**实时算出来的视图**，不是一张表 ——
   * 存下来的异常记录会过期：订单已经推进了，记录还挂着，运营去处理一个不存在的问题。
   */
  listExceptionOrders(q?: ExceptionQ): Promise<Page<OrderException>>;

  /**
   * 人工把订单推到另一个状态。
   *
   * - `to` 必须是 `ORDER_TRANSITIONS` 允许的下一步 —— 人工干预也不能绕过状态机；
   * - `remark` **必填**：改状态是覆盖了系统的判断，不写原因下次没人知道为什么。
   */
  interveneOrder(v: { orderNo: string; to: OrderStatus; remark: string }): Promise<Order>;

  /** 某单的人工干预历史。 */
  listOrderInterventions(orderNo: string): Promise<OrderIntervention[]>;

  // ── 代客下单 / 代客取消（P-4.1.5）──────────────────────────────

  /**
   * 代客取消。
   *
   * 已支付的单**只能带退款取消** —— 契约里不给"取消但不退款"这条路：
   * 那等于平台收了钱又不发货，任何理由都不成立。
   */
  proxyCancelOrder(v: { orderNo: string; reason: string }): Promise<Order>;

  /**
   * 代客下单（客服电话代下）。
   *
   * - 一次只能下**一个商家**的商品：全站按商家拆单（E3），跨商家应该下多单；
   * - 落到 `PENDING_PAY` 而不是已支付 —— 代客下单不代付款，钱必须由用户自己付；
   * - `reason` 必填：代客下单绕过了用户自主下单，得留下为什么。
   */
  createProxyOrder(v: {
    buyerNickname: string;
    communityNo: string;
    merchantNo: string;
    fulfillType: FulfillType;
    items: { skuNo: string; qty: number }[];
    reason: string;
  }): Promise<Order>;
}
