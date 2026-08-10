// 唯一契约。页面只依赖这个接口，不感知 mock / 真实后端。
// 端点对齐后端 C 端 BFF `/mp/**`（见 docs/api）。
import type {
  AfterSale,
  OrderPreview,
  AfterSaleReason,
  MasterData,
  MerchantApplyReq,
  MerchantApplyStatus,
  CartItem,
  Community,
  Coupon,
  Goods,
  GroupBuy,
  LoginReq,
  LoginResp,
  Order,
  PageQuery,
  PageResult,
  Address,
  GroupRequest,
  AfterSaleType,
  FrequentItem,
  Merchant,
  ReorderResult,
  StoreHome,
  Message,
  PointAccount,
  PointsDeductible,
  PointRecord,
  Review,
  VisitedMerchant,
  User,
  UserCard,
  CategoryType,
  FulfillmentType,
  ReviewScores,
} from "@shared/types";

export interface GoodsQuery extends PageQuery {
  merchantNo?: string;
  type?: CategoryType;
  categoryNo?: string;
  keyword?: string;
  communityNo?: string;
}

export interface CreateOrderReq {
  items: { goodsNo: string; skuNo: string; qty: number }[];
  fulfillment: FulfillmentType;
  pickupNo?: string;
  addressId?: string;
  couponNo?: string;
  /** 使用的积分数（后端会按抵扣上限截断，端上算的只是预览） */
  usePoints?: number;
  remark?: string;
  /** 幂等 key，防重复提交 */
  idempotencyKey: string;
  /** 拼团：参与的团 */
  groupNo?: string;
  /** APPOINTMENT：用户选定的预约开始时间戳 */
  appointmentAt?: number;
}

import type { PointsDeductibleQuery } from "./requests";

/** 预览的入参 = 下单入参**去掉幂等键** —— 预览不创建任何东西，不需要它 */
export type PreviewOrderReq = Omit<CreateOrderReq, "idempotencyKey">;

export interface ShopApi {
  // ---- 用户
  /**
   * 发送短信验证码。
   *
   * **它此前不在端点表里** —— 登录页有验证码输入框，却没有任何地方去发码：
   * mock 下直接把 1234 填进输入框，于是这条缺失一直被盖住，
   * 而真实环境里没有人收得到验证码，登录整条路走不通。
   *
   * 返回体不含验证码（后端刻意如此）—— 这条别为了联调方便破例。
   */
  sendOtp(phone: string): Promise<void>;
  login(req: LoginReq): Promise<LoginResp>;
  profile(): Promise<User>;
  bindCommunity(communityNo: string, pickupNo: string): Promise<User>;

  // ---- 地址簿（送货上门 / 快递的前置）
  addressList(): Promise<Address[]>;
  saveAddress(payload: Omit<Address, "addressId"> & { addressId?: string }): Promise<Address[]>;
  removeAddress(addressId: string): Promise<Address[]>;
  setDefaultAddress(addressId: string): Promise<Address[]>;

  // ---- 社区
  nearbyCommunities(lat?: number, lng?: number): Promise<Community[]>;

  // ---- 商品
  goodsList(q: GoodsQuery): Promise<PageResult<Goods>>;
  goodsDetail(goodsNo: string): Promise<Goods>;

  // ---- 购物车（服务端购物车；本地 store 做乐观更新）
  cartList(): Promise<CartItem[]>;
  cartAdd(goodsNo: string, skuNo: string, qty: number): Promise<CartItem[]>;
  cartUpdate(skuNo: string, qty: number): Promise<CartItem[]>;
  cartRemove(skuNos: string[]): Promise<CartItem[]>;

  // ---- 交易
  createOrder(req: CreateOrderReq): Promise<Order>;
  payOrder(orderNo: string): Promise<Order>;
  orderList(q: PageQuery & { status?: string }): Promise<PageResult<Order>>;
  /**
   * 首页推广位。**是运营位，不是自动热销榜** —— 社区里 SKU 就那么几十个，
   * 按销量自动排出来的「热卖」和「全部商品」几乎是同一个列表，那样没有意义。
   * 它的价值在于平台/商家能主动推某样东西（新店冷启动、滞销清仓、节日主推）。
   * 一期后台配置还没有，先用销量兜底；接上配置时只换这个接口的实现，端上不动。
   */
  promotedGoods(q?: { communityNo?: string; size?: number }): Promise<Goods[]>;
  /**
   * 推荐门店（运营位）。和 promotedGoods 同一套心智：**运营意图，不是销量事实**。
   * 用途是新店冷启动 —— 一家刚入驻的店没有订单、没有评分，
   * 在任何按销量/评分排的列表里都永远排在最后，靠自然流量起不来。
   */
  promotedMerchants(q?: { communityNo?: string; size?: number }): Promise<Merchant[]>;
  orderDetail(orderNo: string): Promise<Order>;
  cancelOrder(orderNo: string): Promise<Order>;
  /**
   * 订单预览：**金额以后端算的为准**。
   *
   * <p>此前确认订单页在客户端自己算（`pricingFor(type).estimate(...)`），
   * 而这个端点后端一直有、端上从没声明过。代价在接通店铺满减那天暴露：
   * 页面显示 ¥298.80、提交后实付 ¥290.80 —— **同一笔单两个金额**。
   *
   * <p>页面里那段注释本来就写着「页面自己算一份、后端算一份，两边迟早对不上」，
   * 担心的正是这个；共享的 pricing 策略解决的是「C 端与 B 端公式一致」，
   * 解决不了「端上不知道服务端有什么优惠」—— 活动、券、积分规则都在服务端，
   * 端上永远只能算出一个乐观的近似值。
   */
  orderPreview(req: PreviewOrderReq): Promise<OrderPreview>;

  // ---- 售后
  /** 申请售后。**仅退款与退货退款流程不同** —— 后者必须先收到货再退款 */
  applyAfterSale(
    orderNo: string,
    reason: string,
    images: string[],
    type?: AfterSaleType,
  ): Promise<Order>;
  /**
   * 退货退款：用户寄回后填运单号，商家据此收货。
   * **按售后单号寻址，不是订单号** —— 售后是独立资源（见 AfterSale.afterSaleNo）。
   */
  /**
   * 售后原因清单。**由后端给，端上不硬编码** —— 两份清单会各自漂移，
   * 而运营改的是后端那份。下发的是**码**，文案由端上按当前语言翻译。
   */
  afterSaleReasons(): Promise<AfterSaleReason[]>;
  /**
   * 我的售后单列表。
   *
   * <p>订单列表的「售后」页签靠它 —— 此前那个页签按
   * `order.status ∈ {REFUNDING, REFUNDED}` 筛，而 `REFUNDING` 是售后单的状态、
   * 订单从来不会是这个值，于是页签只剩「已退款」一种，处理中的一条也看不到。
   */
  afterSaleList(): Promise<AfterSale[]>;
  fillReturnExpress(afterSaleNo: string, expressNo: string): Promise<Order>;
  /** 商家驳回后上升平台裁决（B-7.4）—— 驳回不能是终点，否则用户没有退路 */
  raiseDispute(afterSaleNo: string, reason: string): Promise<Order>;

  // ---- 营销
  couponList(): Promise<Coupon[]>;
  receiveCoupon(couponNo: string): Promise<Coupon>;
  /** 只取当前自提点的团 —— 成团单位是自提点 */
  groupBuyList(pickupNo?: string): Promise<GroupBuy[]>;
  groupBuyDetail(groupNo: string): Promise<GroupBuy>;
  joinGroupBuy(
    groupNo: string,
    qty: number,
  ): Promise<{ group: GroupBuy; justReached: boolean; refundPerMember: number }>;
  /**
   * 用户自发发起一个团。
   * `toMyHome` = 送到我家（邻里自提，ADR-005）—— 求团买床垫、校服这类东西
   * 本来就没有门店可提，缺了这条求团落不了地。**零报酬，只能是自己发起的团。**
   */
  createGroupBuy(
    goodsNo: string,
    pickupNo: string,
    neighbor?: { toMyHome: true; address: string; timeSlot: string },
  ): Promise<GroupBuy>;

  // ---- 邻里自提：发起人侧（C-FF-09/10，作用域限本团）
  /** 我发起的团 */
  myHostedGroups(): Promise<GroupBuy[]>;
  /** 批次签收：整批到货后发起人点一次，之后个别缺损照常走售后 */
  confirmGroupBatch(groupNo: string): Promise<Order[]>;
  /** 发起人轻核销。**作用域严格限该团** —— 与商家履约台是两套权限 */
  verifyGroupPickup(groupNo: string, code: string): Promise<Order>;
  /** 本团待取的订单（只回履约必需字段，脱敏更严，B12） */
  groupPickupOrders(groupNo: string): Promise<Order[]>;

  // ---- 邻里求团（需求先于供给，意向不是订单）
  requestList(pickupNo?: string): Promise<GroupRequest[]>;
  requestDetail(requestNo: string): Promise<GroupRequest>;
  createRequest(payload: {
    pickupNo: string;
    title: string;
    desc: string;
    expectQty: number;
    budgetMinor?: number;
  }): Promise<GroupRequest>;
  /** +1 / 取消 +1。只是意向，不产生订单 */
  toggleInterest(requestNo: string): Promise<GroupRequest>;
  /** 发起人选定某个商家的报价 → 转成正式商家团 */
  chooseQuote(requestNo: string, quoteNo: string): Promise<GroupRequest>;
  /** 选定报价后，+1 的邻居各自二次确认下单（+1 不等于承诺） */
  confirmRequest(requestNo: string): Promise<GroupRequest>;

  // ---- 商家
  merchantList(q?: { keyword?: string; communityNo?: string }): Promise<Merchant[]>;
  /** 我消费过的商家，按最近下单时间倒序 */
  visitedMerchants(): Promise<VisitedMerchant[]>;
  merchantDetail(merchantNo: string): Promise<Merchant>;

  // ---- 门店主页（**一期主获客路径**，ADR-004 决策 3）
  /** 扫码/分享进店。`from=QR` 时写进店归因，决定订单 trafficSource 与费率档 */
  storeHome(merchantNo: string, from?: string): Promise<StoreHome>;
  /** 常买清单：按购买频次排序；未登录时降级为店铺热销 */
  frequentItems(merchantNo: string): Promise<FrequentItem[]>;
  /** 一键再来一单：整单复制到购物车，失效品与涨价品分别回报 */
  reorderFrom(orderNo: string): Promise<ReorderResult>;
  /** 收藏/取消收藏本店 */
  toggleFavoriteStore(merchantNo: string): Promise<boolean>;
  /** 我的常去店（首页入口用） */
  myStores(): Promise<Merchant[]>;

  // ---- 评价
  reviewList(q: { goodsNo?: string; merchantNo?: string }): Promise<Review[]>;
  /** 点赞/取消点赞，返回更新后的评价 */
  toggleReviewLike(reviewNo: string): Promise<Review>;
  /** 发表评价（订单完成后） */
  createReview(payload: {
    orderNo: string;
    goodsNo: string;
    rating: number;
    content: string;
    images: string[];
    /**
     * 三维度评分（可选，B-9.3 / P-13.1.4）。
     * 不填时后端按总分回填三维 —— 老客户端与「懒得细评」的用户都要能提交，
     * 但**平台的评分权重要的是维度分**，没有维度分那套权重就没有输入。
     */
    scores?: ReviewScores;
  }): Promise<Review>;

  // ---- 积分（C 端账户）
  pointAccount(): Promise<PointAccount>;
  pointRecords(): Promise<PointRecord[]>;
  /**
   * 结算页试算：本单最多能抵多少。
   *
   * **服务端算而不是端上算**：抵扣上限依赖券后金额、运费拆分与四级开关，
   * 端上算一遍、服务端下单时再算一遍，两次算法只要有一点不同，
   * 用户就会看到「结算页说能抵 30，下单后只抵了 25」。
   */
  pointsDeductible(q: PointsDeductibleQuery): Promise<PointsDeductible>;

  // ---- 卡包（CARD 品类购买后入包）
  myCards(): Promise<UserCard[]>;

  // ---- 站内消息
  messageList(): Promise<Message[]>;
  readMessage(messageNo: string): Promise<Message[]>;
  readAllMessages(): Promise<Message[]>;


  // ---- 商家入驻
  /**
   * 平台主数据：行业 / 主体类型 / 支付通道。
   *
   * 入驻表单要它才能回答「这个行业能不能选小微」—— 选错主体的后果是进件被拒，
   * 而那时人已经开完店、上完架。
   */
  masterData(): Promise<MasterData>;
  merchantApply(payload: MerchantApplyReq): Promise<MerchantApplyStatus>;
  /**
   * 我的入驻申请状态。**此前提交完就查不到了** ——
   * 商家不知道审到哪一步，只能打电话问运营。
   */
  myMerchantApply(): Promise<MerchantApplyStatus | null>;
}
