// 覆盖范围：订单管理（P-4.1）。
import type { FulfillmentType, Order, OrderException, OrderIntervention, OrderStatus, Page, PayMode } from "@/lib/types";
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
   * 代客下单（客服电话代下，P-4.1.4）。需求梳理见
   * `docs/requirements/代客下单-需求梳理.md`；四条边界都在后端强制：
   *
   * - **必须落在真实顾客名下**：`userNo`（从人档取）或 `phone`（完整手机号）。
   *   此前这里收的是一个自由文本昵称，照它做出来的是一张**没有主人的订单**：
   *   顾客在 C 端看不到、付不了款、也退不了。
   *   给 `phone` 时后端会按这个号建账号（走的就是登录那条建户路），
   *   所以顾客日后用同一个号登录就能看到这张单 —— **没装过 App 的人也能电话下单**；
   * - **不代付款**：`OFFLINE` 落待线下付（当面付给商家），`ONLINE` 落待支付由顾客自己付；
   * - **不代用券、不代扣积分** —— 那是顾客的资产，所以这里根本没有这两个字段；
   * - **不代填地址**：只放行到点自取那几种履约方式，快递/自送要地址，客服没法核对。
   *
   * 一次只能下**一个商家**的商品：全站按商家拆单（E3），跨商家应该下多单。
   *
   * `idempotencyKey` 在**打开表单那一刻**生成：手一抖不能变成两单
   * （两单会真的锁两份库存、也真的要顾客付两次）。
   */
  createProxyOrder(v: {
    /** 顾客账号；为空时用 `phone` */
    userNo?: string;
    /** 顾客完整手机号。**只在 `userNo` 为空时看它** —— 没有账号就按这个号建一个 */
    phone?: string;
    merchantNo: string;
    fulfillType: FulfillmentType;
    pickupNo?: string;
    payMode: PayMode;
    items: { skuNo: string; qty: number }[];
    reason: string;
    idempotencyKey: string;
  }): Promise<Order>;
}
