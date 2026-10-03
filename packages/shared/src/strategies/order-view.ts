// 订单展示：`(状态 × 履约方式 × 附加信息) → 用户看到什么`。
//
// **状态集合封闭，履约集合开放**（《订单状态-统一整理》）。
// 订单状态只回答「这单走到哪了」，履约方式回答「怎么交付」——两条正交的轴。
// 把它们乘在一起再命名成状态（此前的 `ARRIVED` / `SHIPPED`），
// 代价是**每加一种履约就要加一批状态**：服务类差点又加了 `TO_USE` / `TO_SERVE`，
// 再来个「同城闪送」还要再加一轮。
//
// 所以映射放在这一层，且**三端共用一份**：各写一份的下场，
// 后端 OrderStatusView 的类注释里已经记过一次 ——
// 「c-app / b-app / ops-web 三套还互不相同，而后端下发的是库里那六个」。
import { FULFILLMENT } from "@shared/utils/constants";
import type { FulfillmentType, OrderStatus } from "@shared/types";

/**
 * 履约方式的**交付形态**。页签谓词与文案都按它分组，
 * 而不是逐个列举履约值 —— 加一种履约只需归入某一类。
 */
export const DELIVERY_SHAPE = {
  /** 买家去某处取实物：自提点、邻居家 */
  SELF_PICKUP: "SELF_PICKUP",
  /** 实物送到买家手上：商家自送、快递 */
  SHIP_TO_BUYER: "SHIP_TO_BUYER",
  /** 买家去消费服务：到店核销 */
  SELF_SERVE: "SELF_SERVE",
  /** 服务方到约定地点：上门预约 */
  SERVE_TO_BUYER: "SERVE_TO_BUYER",
  /** 付款即得：虚拟商品、卡券 */
  IMMEDIATE: "IMMEDIATE",
} as const;

export type DeliveryShape = (typeof DELIVERY_SHAPE)[keyof typeof DELIVERY_SHAPE];

const SHAPE_OF: Record<FulfillmentType, DeliveryShape> = {
  [FULFILLMENT.PICKUP]: DELIVERY_SHAPE.SELF_PICKUP,
  [FULFILLMENT.NEIGHBOR_PICKUP]: DELIVERY_SHAPE.SELF_PICKUP,
  [FULFILLMENT.DELIVERY]: DELIVERY_SHAPE.SHIP_TO_BUYER,
  [FULFILLMENT.EXPRESS]: DELIVERY_SHAPE.SHIP_TO_BUYER,
  [FULFILLMENT.STORE_VERIFY]: DELIVERY_SHAPE.SELF_SERVE,
  [FULFILLMENT.APPOINTMENT]: DELIVERY_SHAPE.SERVE_TO_BUYER,
  [FULFILLMENT.INSTANT]: DELIVERY_SHAPE.IMMEDIATE,
};

export function shapeOf(fulfillment?: FulfillmentType | null): DeliveryShape {
  return (fulfillment && SHAPE_OF[fulfillment]) || DELIVERY_SHAPE.SELF_PICKUP;
}

/** 归入某形态的全部履约值 —— 列表接口的 `fulfillments` 参数用它 */
export function fulfillmentsOf(...shapes: DeliveryShape[]): FulfillmentType[] {
  const want = new Set<DeliveryShape>(shapes);
  return (Object.keys(SHAPE_OF) as FulfillmentType[]).filter((f) => want.has(SHAPE_OF[f]));
}

/** 买家的下一步动作 —— 页签怎么归并、要不要给按钮，都看它 */
export const NEXT_ACTION = {
  /** 等着，用户不用做任何事 */
  WAIT: "WAIT",
  /** 该用户动了：去取货 / 去店里用 */
  GO: "GO",
  /** 到点在场（有约定时间） */
  BE_THERE: "BE_THERE",
  /** 流程已结束 */
  NONE: "NONE",
} as const;

export type NextAction = (typeof NEXT_ACTION)[keyof typeof NEXT_ACTION];

export interface OrderViewInfo {
  /** 预约开始时间戳（`APPOINTMENT`）。有值时文案要带上时间 */
  appointmentAt?: number | null;
}

export interface OrderView {
  /** 状态文案的 i18n key */
  labelKey: string;
  next: NextAction;
  /** 文案是否需要 `{t}` 参数（预约时间）。为 true 时调用方要传 */
  needsTime: boolean;
}

/**
 * 订单展示。**新增履约方式时改这里，不要改 `OrderStatus`**。
 *
 * i18n key 一律 `orderView.<状态>_<形态>`，缺省回落 `orderView.<状态>`。
 */
export function orderView(
  status: OrderStatus,
  fulfillment?: FulfillmentType | null,
  info: OrderViewInfo = {},
): OrderView {
  const shape = shapeOf(fulfillment);

  if (status === "WAIT_OFFLINE_PAY") {
    /*
     * 线下支付：钱要当面给。**下一步动作按形态分** ——
     *   自提 / 到店核销 → 去（到店时一起付）
     *   上门服务 / 送货  → 到点在场（师傅来的时候付）
     * 落到默认的 NONE 是错的：这单明明还需要买家做一件事，
     * 而 NONE 会让页签把它归进「没我事了」那一组。
     */
    const next = shape === DELIVERY_SHAPE.SELF_PICKUP || shape === DELIVERY_SHAPE.SELF_SERVE
      ? NEXT_ACTION.GO
      : NEXT_ACTION.BE_THERE;
    return { labelKey: "orderView.WAIT_OFFLINE_PAY", next, needsTime: false };
  }

  if (status === "PAID") {
    // 交付方还没行动。四种形态下用户都只能等 —— 差别只在文案叫「备货」还是「待发货」
    return {
      labelKey: shape === DELIVERY_SHAPE.SHIP_TO_BUYER
        ? "orderView.PAID_SHIP_TO_BUYER"
        : "orderView.PAID",
      next: NEXT_ACTION.WAIT,
      needsTime: false,
    };
  }

  if (status === "FULFILLING") {
    switch (shape) {
      case DELIVERY_SHAPE.SELF_PICKUP:
        // 已到自提点，等买家来取
        return { labelKey: "orderView.FULFILLING_SELF_PICKUP", next: NEXT_ACTION.GO, needsTime: false };
      case DELIVERY_SHAPE.SELF_SERVE:
        // 码已出，随时到店用掉。**没有时间约束**，所以是 GO 不是 BE_THERE
        return { labelKey: "orderView.FULFILLING_SELF_SERVE", next: NEXT_ACTION.GO, needsTime: false };
      case DELIVERY_SHAPE.SERVE_TO_BUYER:
        /*
         * 约好了时间。**没有时间的「待服务」等于没说** —— 用户要知道的正是几点，
         * 所以有 appointmentAt 时用带时间的文案，没有时才回落。
         */
        return info.appointmentAt
          ? { labelKey: "orderView.FULFILLING_SERVE_AT", next: NEXT_ACTION.BE_THERE, needsTime: true }
          : { labelKey: "orderView.FULFILLING_SERVE_TO_BUYER", next: NEXT_ACTION.BE_THERE, needsTime: false };
      default:
        // 在路上
        return { labelKey: "orderView.FULFILLING_SHIP_TO_BUYER", next: NEXT_ACTION.WAIT, needsTime: false };
    }
  }

  return { labelKey: `orderView.${status}`, next: NEXT_ACTION.NONE, needsTime: false };
}

/**
 * 页签谓词。**页签是查询条件，不是状态值** —— 想把两个页签并成一个，
 * 改这里的 `shapes`，后端一行不用改。
 */
export interface OrderTabSpec {
  key: string;
  status?: OrderStatus;
  /** 只要这些交付形态；不填 = 不限 */
  shapes?: DeliveryShape[];
}

/** 谓词 → 列表接口参数 */
export function tabQuery(spec: OrderTabSpec): { status?: OrderStatus; fulfillments?: string[] } {
  return {
    ...(spec.status ? { status: spec.status } : {}),
    ...(spec.shapes?.length ? { fulfillments: fulfillmentsOf(...spec.shapes) } : {}),
  };
}

/**
 * 订单状态的**色调库件名**。返回的是 `@ai-shop/ui` 的全局类，页面直接绑上去。
 *
 * <p><b>为什么不写在页面的 scoped 样式里</b>：那样每个页面都要自己维护一份
 * 「哪几个状态是灰的」，而 order 与 orders 两页此前正是各写了一遍。
 * 更要命的是 scoped 选择器带 `[data-v-x]`，权重高于全局库件 ——
 * 页面里留一条 `color`，库件那条就被压掉，而闸门看不出来。
 *
 * <p>三档：待付款是**要办的事**（warning）；已完成 / 已取消 / 已退款是
 * **结束了的事**（quiet）；其余在进行中，用主色。
 */
/**
 * 那串码该叫什么。
 *
 * ⚠️ **后端把三种码合在一个字段里**（`OrderVO.verifyCode` 的注释写着
 * 「自提码/核销码/兑换码三态共用」），端上不该另外要一个 `redeemCode` ——
 * 早先就是那么写的，而那个字段后端从来不发：**买虚拟商品拿到的码，
 * 被标成了「取货码」**，而卡包里那张券要用的是「兑换码」这个说法。
 *
 * <p>放在这里而不是各页各写一份：订单详情与收银台都要用它，
 * 两处各写一个三分支，迟早只改其中一处。
 */
export function codeLabelKey(
  type?: string | null,
  fulfillment?: FulfillmentType | null,
): string {
  if (type === "VIRTUAL" || type === "CARD") return "pay.redeemCode";
  if (fulfillment === "STORE_VERIFY") return "pay.verifyCodeStore";
  return "pay.verifyCode";
}

export function statusTone(status: OrderStatus): string {
  if (status === "WAIT_PAY" || status === "WAIT_OFFLINE_PAY") return "is-warning";
  if (status === "COMPLETED" || status === "CANCELLED" || status === "REFUNDED") return "txt-quiet";
  return "txt-primary";
}
