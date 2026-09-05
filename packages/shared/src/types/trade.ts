// 交易主干：下单、订单、发票、售后
//
// 三端共用的契约镜像，按域切开的一份 —— 口径与切开之前逐字相同，见 `index.ts`。

import type { CategoryType, CurrencyCode, FulfillmentType, TrafficSource } from "./core";
import type { MerchantCapability } from "./merchant";

// ---------------------------------------------------------------- 售后

/**
 * 售后原因。**取值与后端 `/mp/after-sale/reasons` 下发的一致** ——
 * 端上不再自己硬编码一份清单（此前那份少两个、多一个，两边各自漂移，
 * 运营改后端那份端上纹丝不动）。
 *
 * 后端下发的是**码**不是文案：这是三语 App，翻译得留在端上。
 */
export type AfterSaleReason =
  | "NOT_WANTED"
  | "DAMAGED"
  | "MISSING"
  | "WRONG_ITEM"
  | "QUALITY"
  | "EXPIRED"
  | "OTHER";
// ---------------------------------------------------------------- 购物车

export interface CartItem {
  /** 商品单号 */
  goodsNo: string;
  /** SKU 单号。购物车按 SKU 去重，不是按商品 */
  skuNo: string;
  /** 商品标题快照 */
  title: string;
  /** 封面图快照 */
  cover: string;
  /** 规格文案快照 */
  spec: string;
  /** 加购时的单价（最小货币单位）。结算时以服务端最新价为准，不一致会提示 */
  price: number;
  /** 数量 */
  qty: number;
  /** 商品形态 */
  type: CategoryType;
  /** 用户选定的履约方式。跨履约方式的商品结算时会拆单 */
  fulfillment: FulfillmentType;
  /**
   * 所属商家。**后端 `CartItemVO` 一直在发这两个字段，是这里此前没声明**——
   * 于是数据到了端上就被丢掉，购物车只能按履约方式分组，店名一个字都显示不出来。
   *
   * 后果不是「少个标签」：用户从头到尾看到「一单」，提交后拿到的是按商家拆出的
   * N 笔子订单（`ord_sub_order`）。见 TDD-购物车商家可见。
   */
  merchantNo: string;
  /**
   * 商家名。**购物车按它分组** —— 一车东西来自几家店，
   * 结算时会拆成几笔子订单，分组是把这件事提前说清楚（见 TDD-购物车商家可见）。
   */
  merchantName: string;
  /**
   * 失效（已下架 / 已删除）。**为真即不可勾选结算**，端上放进失效区。
   *
   * ⚠️ **这里此前叫 `invalidReason?: string`，而后端从来没有发过那个名字** ——
   * 后端 `CartItemVO` 发的一直是 `invalid: boolean`。同物异名的后果不是「少个字段」：
   * 端上按 `!invalidReason` 判有效，于是**已下架的商品在购物车里完全正常**，
   * 能勾能结算，一直到下单那一刻才被后端拒。
   *
   * <p>不反过来让后端发那句中文原因：那是要显示给用户的话，而这个 app 有三门语言。
   * 端上拿 `invalid` 与 {@link available} 两个事实自己组装本地化文案，
   * i18n 守卫也才管得着它。见 TDD-购物车与下单优化 §3.6。
   */
  invalid?: boolean;
  /**
   * 可售库存。`0` = 售罄。
   *
   * ⚠️ **缺省表示「后端没给」，不是 0** —— 旧版本后端与 mock 都可能不发。
   * 每一处都要按「空 = 不设上限」处理；默认成 0 的后果是整车一件都加不了，
   * 而且只在没带这个字段的环境里才出现。
   */
  available?: number;
  /** 买赠自动带出的赠品件数（不计价） */
  giftQty?: number;
  /** 赠品说明，如「买 2 送 1」 */
  giftLabel?: string;
}
export type OrderStatus =
  | "WAIT_PAY"
  /**
   * 等商家当面收款（线下支付）。
   *
   * <p>**与 WAIT_PAY 分开是必须的**：那个的下一步是「去付款页」，
   * 这个的下一步是「见到商家/师傅时把钱给他」——
   * 合成一个的话，端上会给线下单画一个点不动的支付按钮。
   *
   * <p>也没有回到 WAIT_PAY 的边：改主意想线上付要重新下单。
   * 否则「这单收没收到钱」就有了两个真源（商家确认 与 支付回调），
   * 而它们可能同时到达。
   */
  | "WAIT_OFFLINE_PAY"
  /** 已付款，交付方还没行动。库里叫 `WAIT_FULFILL`，同一件事 */
  | "PAID"
  /**
   * 交付方已行动，等交接完成。
   *
   * ⚠️ **这里曾经是 `ARRIVED` 与 `SHIPPED` 两个值** —— 它们不是状态，
   * 是「状态 × 履约方式」的组合冒充状态：库里同为 `FULFILLING`，
   * 只因自提要「去取」、快递要「等着」而被拆成两个。
   *
   * 拆的动机是对的（用户下一步动作不同），做法不可扩展：
   * **每加一种履约就要加一批状态**（服务类差点又加了 `TO_USE`/`TO_SERVE`）。
   *
   * 现在的模型：状态集合封闭，履约集合开放，
   * 展示由 `(状态 × 履约 × 信息)` 决定 —— 见 {@link orderView}
   * 与《订单状态-统一整理》。页签是**谓词**（status + fulfillments），不是状态值。
   */
  | "FULFILLING"
  | "COMPLETED"
  | "CANCELLED"
  | "REFUNDED";

/**
 * 订单预览的返回。**后端返的是完整 OrderVO，这里只声明端上要用的那部分** ——
 * 预览页只关心金额与行，声明全套会让每次后端加字段都得改端上类型。
 */
export interface OrderPreview {
  /**
   * 试算出来的金额。**页面显示的应付必须等于这里的 payableMinor** ——
   * 端上不要自己再算一遍：优惠叠加顺序（先活动后券）在后端，
   * 两处各算一次必然算出两个数，而用户看到的是「确认页 46.40、付款 51.40」。
   */
  amount: OrderAmount;
  /** 试算出来的订单行，含赠品行（价格 0）。数量与下单后落库的一致 */
  items: OrderItem[];
}
/**
 * 结算页的<b>能力提示</b>：这一车货能不能开票、能用哪些支付方式、额度还够不够。
 *
 * <p>与 {@link OrderPreview} 分开是有意的：preview 回答「多少钱」，
 * 这个回答「付得了吗、票拿得到吗」。
 *
 * <p>三件事一起给，是因为它们的共同后果都是<b>付款那一刻才炸</b>——
 * 小微没有 H5/App 支付方式（混合购物车整单付不了）、小微不能开票
 * （买完才发现补救不了）、额度用尽（通道直接拒收）。
 * 每一条单独看都像偶发故障，放在一起看才是同一件事：
 * 平台放弱主体进来了，而结算页还没告诉买家这意味着什么。
 */
export interface CheckoutCapability {
  /**
   * 整单可用的支付方式 = <b>各商家支持集合的交集</b>。
   *
   * 交集而非并集：一笔支付覆盖整单，有一家不支持就用不了。
   * <b>空数组 = 这一车货没有任何方式能付</b>，端上要拦在结算页 ——
   * 让他点下去只会得到一个说不清原因的「支付失败」。
   *
   * <b>null = 未配置</b>（一个商家都还没进件完）——端上<b>不要拦</b>。
   * 两者混成空数组的话，一个完全正常的订单会被拦死。
   */
  usablePayMethods: string[] | null;
  /** 车里有商家开不了票。**必须在付款前告诉用户**：买完才发现，平台补救不了 */
  anyNotInvoiceCapable: boolean;
  /** 逐商家的能力，端上据此在对应的商家分组上打标 */
  merchants: MerchantCapability[];
  /**
   * 整单可用的**支付方式**（`PAY_MODE`：ONLINE / OFFLINE）。
   *
   * ⚠️ **与 `usablePayMethods` 是两根轴，别混**：那个是**通道**
   * （WECHAT / ALIPAY / H5…），这个是**线上付还是当面付**。
   * 一笔订单要同时确定两者。
   *
   * 同样取交集（一笔支付覆盖整单）。**ONLINE 永远在里面**，
   * 所以不会是空集，也就不需要 `null` 那一档 —— 与 `usablePayMethods`
   * 的取舍不同，因为那边真的可能「没配过」。
   */
  usablePayModes: string[];
}
export interface OrderItem {
  /** 商品单号 */
  goodsNo: string;
  /** 所属商家 —— 分账与「我买过的商家」都依赖它落在订单行上 */
  merchantNo: string;
  /** SKU 单号 */
  skuNo: string;
  /** 下单时的商品标题**快照**。商品后续改名不影响历史订单 */
  title: string;
  /** 封面图快照 */
  cover: string;
  /** 规格文案快照 */
  spec: string;
  /** 成交单价（最小货币单位）快照。改价不追溯已成交订单 */
  price: number;
  /** 数量 */
  qty: number;
  /** 商品形态 */
  type: CategoryType;
  /** FRESH 且按重计价：下单时的标称重量（克） */
  nominalGram?: number;
  /** 是否已实际称重。称重后按实重产生差价，见 `OrderAmount.weighAdjustMinor` */
  weighed?: boolean;
  /** 赠品行：价格为 0，不参与计价，履约时随单发出 */
  isGift?: boolean;
  /** 该商品每件赠送的积分 */
  points?: number;
}
export interface OrderAmount {
  /** 商品小计（最小货币单位），不含运费与优惠 */
  goodsMinor: number;
  /** 运费 */
  freightMinor: number;
  /** 优惠合计（券 + 活动），正数表示减掉多少 */
  discountMinor: number;
  /** 应付：`goodsMinor + freightMinor - discountMinor - pointsDeductMinor` */
  payableMinor: number;
  /** 实付。未支付时为 0；称重差价补退后与 payableMinor 可能不等 */
  paidMinor: number;
  /** 称重差价（正=补款 负=退款），仅 FRESH */
  weighAdjustMinor?: number;
  /** 积分抵扣的金额 */
  pointsDeductMinor: number;
  /** 本单使用的积分数 */
  pointsUsed: number;
  /** 本单可获得的积分（订单完成时才真正入账） */
  pointsEarn: number;
  /** 下单时的货币，订单一经创建即锁定，不随用户切市场变化 */
  currency: CurrencyCode;
}
/**
 * 收件人。下单时固化在子订单上，**不是用户当前的地址簿条目**。
 *
 * 三端共用：C 端订单详情、B 端配送/发货、平台端查单。
 */
export interface OrderReceiver {
  /** 收货人姓名。取不到时为空 —— 空就是空，不要回落成「顾客」 */
  name?: string;
  /** 脱敏程度由后端定，见 `Order.receiver` 的说明 */
  phone?: string;
  /** 省市区 + 详细，拼好的一行 */
  address?: string;
}
/**
 * 开票申请的状态（ADR-017 §3.4 条件 2）。
 *
 * 本版是**手工开票**：运营在票据系统里开完，回来回填票号。
 * 接票据系统是第二步，届时在 `ISSUED` 之后延长状态机，不改前面的。
 */
export type InvoiceRequestStatus = "REQUESTED" | "ISSUED" | "REJECTED";
/** 抬头类型。单位抬头必须有税号，否则对方入不了账 —— 票开出来等于白开 */
export type InvoiceTitleType = "PERSONAL" | "COMPANY";
/**
 * 开票申请：**平台开给消费者**的销项票。
 *
 * 与结算侧的采购发票（`stl_purchase_invoice`）是两回事：
 * 那是**进项**（供应商开给平台，决定平台能不能列支成本），
 * 这是**销项**（平台开给消费者，决定归集资金模式成不成立）。
 */
export interface InvoiceRequest {
  /** 开票申请号 */
  requestNo: string;
  /** 按**主单**申请，不按子单 —— 消费者眼里那是一次购买，票也该是一张 */
  orderNo: string;
  /** `PERSONAL` 个人 / `COMPANY` 单位。单位抬头必须有税号 */
  titleType: InvoiceTitleType;
  /** 发票抬头 */
  title: string;
  /** 单位抬头必填 */
  taxNo?: string;
  /** 电子票只能发到这里，填错就是开了也收不到 */
  email: string;
  /** 开票金额快照。**不实时读订单** —— 退款会改订单金额，已开的票不会跟着变 */
  amountMinor: number;
  /** 状态 */
  status: InvoiceRequestStatus;
  /** 发票号。开出来之后才有 */
  invoiceNo?: string;
  /** 开票时刻。空 = 还没开 */
  issuedAt?: number;
  /** 驳回原因。不写原因的驳回等于让消费者再猜一遍 */
  rejectReason?: string;
  /** 申请时刻 */
  createdAt?: number;
}
export interface Order {
  /** 订单单号 */
  orderNo: string;
  /** 订单状态。粗粒度；售后细节见 `afterSale` */
  status: OrderStatus;
  /** 履约方式，下单时锁定 */
  fulfillment: FulfillmentType;
  /** 订单行。含赠品行（`isGift`，价格为 0） */
  items: OrderItem[];
  /** 金额明细 */
  amount: OrderAmount;
  /** 自提码 / 核销码 */
  verifyCode?: string;
  /** VIRTUAL：兑换码；CARD：卡号 */
  redeemCode?: string;
  /** PICKUP：自提点单号 */
  pickupNo?: string;
  /** PICKUP：自提点名称快照 */
  pickupName?: string;
  /** EXPRESS：快递单号，发货后才有 */
  expressNo?: string;
  /** APPOINTMENT：预约开始时间戳 */
  appointmentAt?: number;
  /** 下单时间 */
  createdAt: number;
  /** 支付截止时间。超时自动取消，仅 WAIT_PAY 有意义 */
  payDeadlineAt?: number;
  /** 状态流转轨迹，按时间正序。订单详情的进度条据此渲染 */
  timeline: OrderTimelineNode[];
  /** 下单幂等 key。端上生成，重复提交返回同一笔订单而不是新建 */
  idempotencyKey?: string;
  /** 下单人昵称。团长视角（分拣单/核销台）要看得见是谁的单 */
  buyerNickname?: string;
  /**
   * 收件人（下单时的**快照**，自提单没有）。
   *
   * 快照而不是现查地址：买家下完单把地址改成新家，商家看到的就跟着变了，
   * 而货已经按旧地址在路上。
   *
   * ⚠️ **`phone` 的脱敏程度由后端按履约方式决定**：商家自送给完整号
   * （送到楼下找不到人就得打电话），其余履约方式给 `****1234`。
   * 端上**不要自己判**要不要打码 —— 两处规则迟早分叉。
   */
  receiver?: OrderReceiver;
  /** 已评价 */
  reviewed?: boolean;
  /** 积分是否已发放（幂等标记，防止重复核销重复发分） */
  pointsGranted?: boolean;
  /**
   * 客流来源。**决定平台费率档**：商家自带客流建议零佣金 —— 他带来的客户
   * 在别家的消费才是平台的收益（ADR-004 §6）。从店铺码/店铺分享进入即为 MERCHANT_OWNED。
   */
  trafficSource?: TrafficSource;
  /** 参与的团。邻里自提的核销作用域就靠它裁剪（E16） */
  groupNo?: string;
  /** 售后单。订单状态只有粗粒度的 REFUNDING/REFUNDED，细节在这里 */
  afterSale?: AfterSale;
  /**
   * 本单归属的商家。**一单只属于一个商家** —— 购物车跨商家时拆成多笔子订单（E3）。
   * 不拆的话分账无从谈起：一笔钱要分给几家、各分多少，没有承载的单据。
   */
  merchantNo?: string;
  /** 商家名快照 */
  merchantName?: string;
  /**
   * 支付组号。同一次结算拆出的子订单共享它，**一次支付付掉整组**。
   * 用户感知是「买了一次」，资金与分账感知是「N 笔各归各家」。
   *
   * ⚠️ **后端叫 `payOrderNo`，库里是 `ord_order.order_no`** —— 三处三个名字。
   * 按这个名去后端或库里找会找不到（2026-08-17 人工测试时撞到）。
   */
  payGroupNo?: string;
  /**
   * **仅支付视角**：这次付款覆盖的各商家订单。订单视角为空。
   *
   * 后端 `OrderVO` 一直在发（同一个结构承担订单/支付两种视角），
   * 端上此前没声明 —— 于是收银台是整条拆单链路里**唯一哑掉的一屏**：
   * 购物车说会拆 2 单、确认页说会拆 2 单、订单详情各自标着商家，
   * 中间付款那一步却只有一个总额。
   */
  subOrders?: Order[];
}
export interface OrderTimelineNode {
  /** 流转到的状态 */
  status: OrderStatus;
  /** 展示文案，如「已到货，请到自提点取货」。后端下发已本地化 */
  label: string;
  /** 发生时间 */
  at: number;
}
/**
 * 子单状态（`ord_sub_order.status`）。
 *
 * **与 {@link OrderStatus} 不是同一套**：主单管钱（付没付、退没退），
 * 子单管货（这家商家的这批货履约到哪一步了）。一张主单拆给三家商家时，
 * 三个子单各走各的 —— 把两者合成一个字段，那三家里有一家发了货就说不清了。
 */
export type SubOrderStatus =
  | "WAIT_PAY"
  | "WAIT_FULFILL"
  | "FULFILLING"
  | "COMPLETED"
  | "CANCELLED"
  | "REFUNDED";
// ================================================================ 售后（完整状态机）

/**
 * 售后类型。**仅退款与退货退款的流程根本不同** ——
 * 仅退款同意即退；退货退款必须**先收到货再退款**，否则「退款了货没回来」。
 * 此前两者走同一条路，是售后闭环缺的后半段（B-7.3）。
 */
export type AfterSaleType = "REFUND_ONLY" | "RETURN_REFUND";
/**
 * 售后单状态。**这是后端 `OrdAfterSale` 真实存的取值。**
 *
 * ⚠️ 这里此前是完全另一套：`PENDING`/`AGREED`/`RETURNING`/`RECEIVED`/`DONE`/`DISPUTED`，
 * 与后端**只有 `REJECTED` 一个词重合**。c/b 两端按它判断、按它建 i18n 词条，
 * 于是售后详情页的状态永远落进兜底分支，「填退货单号」按钮永远不出现
 * （它 gate 在一个后端永远不会下发的 `AGREED` 上）。
 *
 * 那一套描述的是**想象中更细的流程**：同意 → 寄回 → 收货 → 退款四步。
 * 后端没有把「寄回中」「已收货」做成独立状态 —— 商家一同意就进 `REFUNDING`，
 * 退货物流走 `expressNo` 字段而不是状态。粒度差异是真实的设计选择，
 * 端上不能自己补一套更细的词然后假装后端会给。
 */
export type AfterSaleStatus =
  | "APPLIED" // 待商家处理
  | "REFUNDING" // 商家已同意，退款处理中；退货退款时这一段也含「等买家寄回」
  | "REFUNDED" // 退款完成
  | "REJECTED" // 商家驳回
  | "ARBITRATING" // 用户不服，已上升平台裁决
  | "CLOSED"; // 用户撤销，或超时关闭
export interface AfterSale {
  /**
   * 售后单号。**售后是独立资源，不是订单上的一个字段** ——
   * 它有自己的生命周期（申请→同意/驳回→寄回→收货→退款），能被取消、能上升平台，
   * 一个订单还可能先后发起多次。挂在订单下用 orderNo 寻址，第二次申请就没法表达了。
   * 后端一开始就是这么建的（/mp/after-sale/{afterSaleNo}/**），这里向它对齐。
   */
  afterSaleNo: string;
  /**
   * 所属**子订单**号（`SUB…`）。
   *
   * ⚠️ **要关联回订单卡片用的是这个，不是下面的 `orderNo`。**
   * C/B 两端列表里的一行是一张子订单，而 `Order.orderNo` 字段里装的就是子订单号
   * （后端 `OrderVO.orderNo` = `SUB…`）；售后单上的 `orderNo` 却是**主单号**（`SO…`）。
   * 两个字段同名不同物 —— 按 `orderNo` 去 join 一条也匹配不上，
   * 而症状是「售后页签空着」，与它本来要修的 bug 一模一样。
   */
  subOrderNo: string;
  /**
   * 所属**主订单**号（`SO…`）。跨商家下单会拆成多笔子订单，它们共用这一个主单号。
   * 展示「同一次下单」时用它，关联单张订单卡片请用 {@link subOrderNo}。
   */
  orderNo: string;
  /** 售后类型：仅退款 / 退货退款 */
  type: AfterSaleType;
  /** 售后单状态，独立于订单状态流转 */
  status: AfterSaleStatus;
  /** 用户填写的售后原因 */
  reason: string;
  /** 举证图（破损、少件的照片）。是否必填由售后类型决定 */
  images: string[];
  /**
   * 这张售后单要退的钱（分）。**不等于订单金额** ——
   * 一张子订单可以只退其中一件，也可以先后发起多次。
   *
   * <p>后端一直在发（`AfterSaleVO.refundMinor`），只是契约里漏了声明，
   * 于是 B 端售后页拿不到它，只能退而求其次显示**整张子订单的应付**。
   * 单件单品的单子上两个数恰好相等，所以这个错在联调环境里看不出来 ——
   * 直到有人退三件里的一件。
   */
  refundMinor: number;
  /**
   * 极速退：金额在阈值内的仅退款，系统自动通过。
   * **商家只可见不可拒**，所以这类单上不该出现同意/驳回按钮。
   */
  instant?: boolean;
  /** 商家同意/驳回时的说明 */
  merchantReply?: string;
  /** 用户寄回的运单号（RETURN_REFUND） */
  returnExpressNo?: string;
  /** 上升平台时用户的申诉理由 */
  disputeReason?: string;
  /** 最后一次状态变更时间。超时自动同意等时效规则以它为基准 */
  updatedAt: number;
  /** 申请时间 */
  createdAt?: number;
  /** 责任方，平台裁决后才有（口径未定） */
  liability?: string;
  /** 售后自己的时间线（申请 → 同意 → 寄回 → 退款），与订单时间线分开 */
  timeline?: { status: string; label: string; at: number }[];
}
