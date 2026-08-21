// 交易域（矩阵 P-4.1 订单管理）。
// ⚠️ 按商家拆单（矩阵 §七 E3）：一个用户单 = 若干子订单，每个子订单归属一个 merchantNo，
//    分账、售后、结算全部以子订单为单位。列表展示的就是子订单。

/** 履约方式（ADR-005：自提点分 STORE / NEIGHBOR 两类）。 */
/**
 * 履约方式。**取值与后端 `ord_sub_order.fulfillment` 一致**。
 *
 * ⚠️ 这里曾经写成 `PICKUP_STORE` / `PICKUP_NEIGHBOR` —— 同一个概念、词序反了，
 * 于是按它筛后端一条也匹配不上。ops-web 只跑 mock，所以从没被真实响应打脸过。
 */
export type FulfillmentType =
  | "STORE_PICKUP"
  | "NEIGHBOR_PICKUP"
  | "MERCHANT_DELIVERY"
  | "EXPRESS"
  /**
   * 到店核销（SERVICE 商品）。2026-08-17 接通。
   * 此前这里叫 `SERVICE`：同一个概念的第二个名字，且后端两个都不下发。
   */
  | "STORE_VERIFY"
  /** 上门预约（SERVICE 商品）。2026-08-17 接通，带预约时段与上门地址 */
  | "APPOINTMENT";

/** 流量来源（矩阵 P-12.1.7 按 trafficSource 分档计费）。 */
export type TrafficSource = "MERCHANT_OWNED" | "PLATFORM" | "INVITE" | "CHANNEL";

/**
 * 订单状态。**抽象状态，与履约方式无关**，三端同一套。
 *
 * ⚠️ 这里曾经是另一套：`PENDING_PAY` / `PREPARING` / `DELIVERING` / `AFTER_SALE`。
 * 同一条订单，运营端叫一个名字、C/B 端叫另一个、库里又是第三个 ——
 * 而 ops-web 只跑 mock，所以三套并存了很久没人发现。
 *
 * ⚠️ 后来又有过 `SHIPPED` / `ARRIVED` 两个值。它们**不是状态**，
 * 是「状态 × 履约方式」的组合冒充状态 —— 代价是每加一种履约就要加一批状态。
 * 现在下发抽象状态 + 履约方式，显示成什么由展示层决定
 * （见《订单状态-统一整理》与 shared 的 `orderView`）。
 */
export type OrderStatus =
  | "WAIT_PAY"
  /** 已付款，交付方还没行动。库里那一列存的是 WAIT_FULFILL */
  | "PAID"
  /** 交付方已行动，等交接完成。自提说「已到点」、快递说「已发货」，同一个状态 */
  | "FULFILLING"
  | "COMPLETED"
  | "CANCELLED"
  | "REFUNDED";

/**
 * 允许的人工干预迁移。与后端 `OrderStateMachine` 的子单图同源 ——
 * 运营能改到哪，最终由后端说了算；这份表只是让界面**提前**把不可能的选项灰掉，
 * 而不是让人点下去再吃一个报错。
 *
 * 没有「售后中」这个态：售后是挂在订单上的**另一张单**，不是订单本身的状态。
 * 把它做成状态，订单就会在「售后中」和真实状态之间二选一，而两者其实并存。
 */
export const ORDER_TRANSITIONS: Record<OrderStatus, OrderStatus[]> = {
  WAIT_PAY: ["PAID", "CANCELLED"],
  PAID: ["FULFILLING", "COMPLETED", "REFUNDED", "CANCELLED"],
  FULFILLING: ["COMPLETED", "REFUNDED"],
  COMPLETED: ["REFUNDED"],
  CANCELLED: [],
  REFUNDED: [],
};

export interface OrderItem {
  /** SKU 单号 */
  skuNo: string;
  /** 下单时的商品标题快照 */
  title: string;
  /** 件数 */
  qty: number;
  /** 最小货币单位（分） */
  price: number;
}

export interface Order {
  /** 子订单号。**列表展示的就是子订单** —— 分账、售后、结算都以它为单位 */
  orderNo: string;
  /** 父单号（同一次结算拆出的子订单共享） */
  parentNo: string;
  /** 订单状态。允许的流转见 `ORDER_TRANSITIONS` */
  status: OrderStatus;
  /** 归属商家。一个子订单只属于一个商家 */
  merchantNo: string;
  /** 商家名快照 */
  merchantName: string;
  /** 归属社区。运营按社区做数据域隔离 */
  communityNo: string;
  /** 社区名快照 */
  communityName: string;
  /** 自提点编号；配送/快递单为空 */
  pickupNo?: string;
  /** 履约方式 */
  fulfillType: FulfillmentType;
  /** 流量来源。**决定平台费率档**（P-12.1.7） */
  trafficSource: TrafficSource;
  /** 买家昵称 */
  buyerNickname: string;
  /** 订单行 */
  items: OrderItem[];
  /** 实付，最小货币单位（分） */
  payAmount: number;
  /** 下单时间（ISO 8601 字符串） */
  createdAt: string;
  /** 支付时间。未支付为 null */
  paidAt?: string | null;
  /** 进入**当前状态**的时刻。异常单的"卡了多久"从这里算，不是从 createdAt 算 */
  statusAt?: string;
}

// ── 异常单与代客操作（P-4.1.4 / P-4.1.5）─────────────────────────────

/**
 * 异常单的成因。
 *
 * ⚠️ 异常单**不是一张表**，而是对订单表的一个视图 —— 一旦落成记录就会过期：
 * 订单已经推进了，异常记录还挂在那里，运营会去处理一个不存在的问题。
 * 所以 `OrderException` 由 `orders` 实时算出来，不存。
 */
export type ExceptionKind =
  /** 卡在某个状态超过阈值（下面 STUCK_MINUTES 按状态给的时限） */
  | "STUCK"
  /** 待支付超时未关单：关单任务本身出了问题 */
  | "PAY_TIMEOUT";

export interface OrderException {
  /** 关联的订单快照。异常单是**实时算出来的视图**，不落表 */
  order: Order;
  /** 异常成因 */
  kind: ExceptionKind;
  /** 已经卡了多少分钟（从进入当前状态算起，mock 用 createdAt/paidAt 近似） */
  stuckMinutes: number;
  /** 该状态允许卡多久（分钟），用于在界面上说明"为什么它算异常" */
  thresholdMinutes: number;
}

/** 人工干预的留痕。改状态这件事必须留下是谁、为什么。 */
export interface OrderIntervention {
  /** 被干预的订单 */
  orderNo: string;
  /** 原状态 */
  from: OrderStatus;
  /** 改为的状态。**必须是 `ORDER_TRANSITIONS` 允许的迁移** */
  to: OrderStatus;
  /** 干预原因，必填 —— 改状态这件事必须说清楚为什么 */
  remark: string;
  /** 操作人（STAFF 账号） */
  operator: string;
  /** 操作时间 */
  at: string;
}
