package ai.neargo.shop.trade.service;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.trade.dto.OrderVO;

import java.util.List;

/** 交易主干（[API 清单 §2.4]）。 */
public interface OrderService {

    /**
     * 结算预览：**按商家拆单 + 试算金额**，不落库、不锁库存。
     * 端上进结算页先调它，拿到的子单结构就是下单后的结构 —— 两处用同一套拆单逻辑，
     * 否则「预览显示 2 个包裹、下单变 3 个」这种问题会反复出现。
     */
    OrderVO preview(CreateOrderCommand cmd);

    /** 下单。幂等由 {@code Idempotency-Key} 保证；库存在此锁定，支付成功才实扣。 */
    OrderVO create(CreateOrderCommand cmd, String idempotencyKey);

    /**
     * 下单，但**买家是传进来的那个人**（代客下单，P-4.1.4）。
     *
     * <p>与 {@link #create} 的唯一区别就是买家从哪儿来：那个取当前登录人，
     * 这个由调用方给。<b>其余一切完全相同</b> —— 拆单、锁库存、算优惠、
     * 归因、支付方式校验都走同一段代码，否则代客下的单迟早在某个环节与自助单不一样，
     * 而那种差异只会在出问题时才被发现。
     *
     * <p><b>调用方必须自己把住权限</b>：这个方法不判「谁有资格替别人下单」。
     * 目前唯一的调用方是 {@code PlatformOrderService#createProxyOrder}（判 order:order:proxy）。
     */
    OrderVO createFor(String userNo, CreateOrderCommand cmd, String idempotencyKey);

    /**
     * 同上，但**支付时限由调用方指定**（分钟）。
     *
     * <p>只为代客下单而有：平台通用时限是给「人正看着屏幕」那条路配的，
     * 而电话下单的人要挂了电话、打开小程序、找到订单才付得上。
     *
     * <p><b>没有对应的端上入口</b>：这个参数只在服务端之间传，
     * 端上能传的话，任何人都能给自己的单要一个更长的时限。
     *
     * @param payMinutes 支付时限（分钟）；null 时按平台关单策略
     */
    OrderVO createFor(String userNo, CreateOrderCommand cmd, String idempotencyKey, Integer payMinutes);

    /** 发起支付，返回端上调起支付所需的参数。**端侧不自判成功**，以回调/回查为准。 */
    /**
     * 发起支付：落流水 + 向通道下单，拿回端上唤起收银台的参数。
     *
     * @param payChannel 端上选的通道。**可为空** —— 空时按该商家所在市场
     *                   取第一个可用通道。端上应当把结算台给的那个传进来，
     *                   否则「结算页显示的」与「实际用的」可能不是同一个
     */
    /**
     * 这一单能用哪些支付方式（C-1）。
     *
     * <p>交集规则与结算页逐字一致：<b>一笔支付覆盖整单，有一家不支持就用不了</b>；
     * 而「一家都没配」当作未配置放行，不当作「一种都不支持」。
     * 两处算出不同结果的话，用户在结算页看到的与收银台看到的就不是一回事。
     */
    ai.neargo.shop.trade.dto.OrderPayMethodVO payMethods(String orderNo);

    PayResult pay(String orderNo, String payChannel);

    /** 支付结果回查（端上轮询用）。 */
    OrderVO payResult(String orderNo);

    /** 支付成功（回调驱动）。幂等：重复回调不会重复扣库存、重复发事件。 */
    void markPaid(String orderNo, String payChannel, String payTradeNo);

    /**
     * 订单详情。**同时接受主单号与子单号**（Q6）：
     * 主单号 → 支付视角（合计 + subOrders）；子单号 → 订单视角（单商家 + 核销码 + 时间线）。
     */
    OrderVO detail(String orderNo);

    /**
     * 关闭超时未支付的订单并释放库存（R7）。
     * 由定时任务调用；参数化「当前时间」是为了让测试不必真等 15 分钟。
     *
     * @param now 判定基准时间（毫秒）
     * @return 关闭的订单数
     */
    int closeExpiredOrders(long now);

    /**
     * 关掉指定的一笔待支付单（对账自查用：通道明确回「没有这笔」）。
     *
     * <p>与 {@link #closeExpiredOrders} 走同一段关单逻辑 —— 关单要连着释放库存、券、积分，
     * 两处各写一遍的话，漏掉的那一项会让库存一直占着，而没有任何报错。
     *
     * <p>已经不是待支付就当没事发生：对账每一轮都可能再撞到同一笔。
     */
    void closeUnpaid(String orderNo, String reason);

    /** 订单列表：**子单粒度**（Q6）—— 用户心智里「订单」就是按店分的。 */
    /**
     * 买家订单列表。
     *
     * @param status       抽象状态（{@code WAIT_PAY / PAID / FULFILLING / COMPLETED / ...}）。
     *                     <b>不再接受 ARRIVED / SHIPPED</b> —— 那是「状态 × 履约」的组合，
     *                     现在由 {@code fulfillments} 单独表达
     * @param fulfillments 想要的履约方式；空 = 不限。与 {@code status} <b>正交</b>：
     *                     「待取货」= {@code FULFILLING} + 自提类，
     *                     「待使用」= {@code FULFILLING} + 服务类
     */
    PageData<OrderVO> list(String status, java.util.List<String> fulfillments, long page, long size);

    OrderVO cancel(String orderNo, String reason);

    /**
     * 这张子单关闭后券与积分的去向（待办设计 P3）；非关闭态返回 null。
     * C 端与 B 端的订单详情共用这一份 —— 两边说的话要一样。
     */
    default OrderVO.Returned returnedOf(ai.neargo.shop.trade.entity.OrdSubOrder sub) {
        return null;
    }

    /** 这张子单减了什么、谁出的钱（C / B 订单详情共用） */
    default List<OrderVO.DiscountLine> discountLinesOf(ai.neargo.shop.trade.entity.OrdSubOrder sub) {
        return List.of();
    }

    /**
     * 确认收货（C-4.4）。**非自提线的终态出口** —— 自提线走核销台。
     * 两条线殊途同归到 COMPLETED，评价与结算都以它为准。
     */
    OrderVO confirmReceipt(String subOrderNo);

    /**
     * @param items       下单行；为空时取购物车勾选行
     * @param fulfillment 履约方式：STORE_PICKUP / NEIGHBOR_PICKUP / MERCHANT_DELIVERY / EXPRESS
     * @param usePoints   想用多少积分。<b>只是意愿值</b> —— 服务端按
     *                    「商家开关 → 抵扣上限 → 账户余额 → 并发」四道闸截断，
     *                    传多少都不会超。null / 0 = 不用积分
     */
    /**
     * @param appointmentAt 预约开始时间戳。<b>仅 {@code APPOINTMENT} 履约需要，且必填</b> ——
     *                      缺了商家不知道该几点上门，买家也不知道自己约了没有
     */
    /**
     * @param payMode   {@link ai.neargo.shop.common.PayModes} 的取值。空按 {@code ONLINE} 处理 ——
     *                  存量端上不传这个字段，不能因为补了它就让老版本下不了单
     * @param payScene  下单端（{@link ai.neargo.shop.common.PayScenes}），由网关从
     *                  {@code X-Client} 头解析。<b>快照进订单</b>，积分发放的端判定读它
     */
    /**
     * @param appointmentSlotNo 预约时段编号。<b>这家店开了时段就必填</b>，
     *                          没开则忽略（走 {@code appointmentAt} 的旧路）。
     *                          归属会在占位那条 SQL 里比对 —— 端上传别家店的时段号占不到
     */
    /**
     * @param groupNo   参团：团号（参团 = 带团号下单，按团价收，付款成功才算成员）
     * @param openGroup 开团：按这件货在跑的拼团活动开一个新团，下单人即发起人。与 groupNo 二选一
     */
    record CreateOrderCommand(List<Item> items, String fulfillment, String pickupNo,
                              String addressId, String couponNo, Long usePoints, String remark,
                              Long appointmentAt, String payMode, String payScene,
                              String appointmentSlotNo, String groupNo, boolean openGroup,
                              /**
                               * 顾客在下单页对活动的选择（优惠券全链路梳理 批 2）：商家号 → 活动号，
                               * 或 {@code CampaignPort.CHOICE_NONE}（这家店不参加）。
                               * <b>没出现的店按最优</b>；null / 空 = 全部按最优，与加这个字段之前一致
                               */
                              java.util.Map<String, String> activityChoices) {

        /** 不带活动选择的签名：存量调用方（代客下单、测试）照旧全部按最优 */
        public CreateOrderCommand(List<Item> items, String fulfillment, String pickupNo,
                                  String addressId, String couponNo, Long usePoints, String remark,
                                  Long appointmentAt, String payMode, String payScene,
                                  String appointmentSlotNo, String groupNo, boolean openGroup) {
            this(items, fulfillment, pickupNo, addressId, couponNo, usePoints, remark,
                    appointmentAt, payMode, payScene, appointmentSlotNo, groupNo, openGroup, null);
        }

        /** 换一组活动选择与券（预览里枚举最省组合时用） */
        public CreateOrderCommand withChoices(java.util.Map<String, String> choices, String coupon) {
            return new CreateOrderCommand(items, fulfillment, pickupNo, addressId, coupon, usePoints, remark,
                    appointmentAt, payMode, payScene, appointmentSlotNo, groupNo, openGroup, choices);
        }

        /** 不参团的下单（代客下单、测试与存量调用方）。行为与加团字段之前逐字相同 */
        public CreateOrderCommand(List<Item> items, String fulfillment, String pickupNo,
                                  String addressId, String couponNo, Long usePoints, String remark,
                                  Long appointmentAt, String payMode, String payScene,
                                  String appointmentSlotNo) {
            this(items, fulfillment, pickupNo, addressId, couponNo, usePoints, remark,
                    appointmentAt, payMode, payScene, appointmentSlotNo, null, false);
        }

        /** 这张单要不要走团：参团或开团 */
        public boolean grouped() {
            return (groupNo != null && !groupNo.isBlank()) || openGroup;
        }

        public record Item(String goodsNo, String skuNo, int qty) {
        }
    }

    /**
     * @param payParams 端上调起支付的参数（微信 JSAPI 的 timeStamp/nonceStr/package/paySign 等）。
     *                  S2 是 stub 通道，S4 换真微信支付时这个结构不变
     */
    /**
     * @param settled <b>这笔已经付掉了，端上不要唤起收银台</b>（应付 0 元的单）。
     *                此时 {@code payParams} 为空。
     *                <p>端上<b>不该</b>改成按 {@code payChannel == "FREE"} 判：
     *                那是把通道名当协议用，将来多一个免支付的来源
     *                （全额积分抵扣、全额券）就要改端上。见 TDD-零元订单支付 §4.2。
     */
    record PayResult(String orderNo, String payChannel, java.util.Map<String, String> payParams,
                     boolean settled) {
    }

    /**
     * 结算页能力提示：能不能开票、能用哪些支付方式、额度够不够。
     *
     * <p>与 {@link #preview} 分开而不是合并：preview 回答「多少钱」，
     * 这个回答「付得了吗、票拿得到吗」。合并的话每次查订单详情都会多带三次查询，
     * 而那三件事在下单之后就不再变化。
     */
    ai.neargo.shop.trade.dto.CheckoutCapabilityVO capability(CreateOrderCommand cmd);
}
