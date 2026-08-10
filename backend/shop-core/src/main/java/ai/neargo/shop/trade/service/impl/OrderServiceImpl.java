package ai.neargo.shop.trade.service.impl;

import ai.neargo.shop.spi.user.PickupQueryPort;
import ai.neargo.shop.trade.service.OrderService;
import ai.neargo.shop.trade.service.OrderStateMachine;
import ai.neargo.shop.trade.service.OrderStatusView;

import ai.neargo.shop.spi.marketing.AttributionPort;
import ai.neargo.shop.spi.marketing.CampaignPort;
import ai.neargo.shop.spi.marketing.CouponPort;
import ai.neargo.shop.spi.product.GoodsQueryPort;
import ai.neargo.shop.spi.product.StockPort;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.spi.trade.OrderEvents;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.trade.dto.OrderVO;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdStatusLog;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.entity.TrdCartItem;
import ai.neargo.shop.trade.mapper.TradeMappers.CartItemMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderItemMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.StatusLogMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 交易主干实现。下单链路严格按 TDD-backend §7.1 的八步走。
 *
 * <p><b>拆单是这里最重要的一件事</b>（E3/ADR-002）：购物车跨商家时按 {@code merchantNo} 分组，
 * 每组一个子订单，各自算钱、各自履约、各自分账。预览与下单**共用同一个拆分方法**，
 * 否则「预览 2 个包裹、下单变 3 个」这类问题会一直复发。
 */
@Service
public class OrderServiceImpl implements OrderService {

    /** 15 分钟未支付关单。与端上倒计时一致，两边都从 {@code expireAt} 读，不各自算。 */
    private static final Duration PAY_TTL = Duration.ofMinutes(15);
    private static final String CURRENCY_CNY = "CNY";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final OrderMapper orderMapper;
    private final SubOrderMapper subOrderMapper;
    private final OrderItemMapper itemMapper;
    private final CartItemMapper cartMapper;
    private final GoodsQueryPort goodsPort;
    private final StockPort stockPort;
    private final MerchantQueryPort merchantPort;
    private final AttributionPort attributionPort;
    private final CouponPort couponPort;
    /** 店铺活动的自动优惠（满减）。此前 mkt_campaign 没有任何消费方 */
    private final CampaignPort campaignPort;
    private final SettlePort settlePort;
    private final StatusLogMapper statusLogMapper;
    private final PickupQueryPort pickupPort;
    /** 取买家绑定的社区，下单时固化到主单 —— 运营按社区做数据域隔离 */
    private final ai.neargo.shop.spi.user.UserQueryPort userPort;
    private final IdempotencyService idempotency;
    private final OutboxEventBus eventBus;

    public OrderServiceImpl(OrderMapper orderMapper, SubOrderMapper subOrderMapper, OrderItemMapper itemMapper,
                            CartItemMapper cartMapper, GoodsQueryPort goodsPort, StockPort stockPort,
                            MerchantQueryPort merchantPort, AttributionPort attributionPort,
                            CouponPort couponPort, CampaignPort campaignPort, SettlePort settlePort,
                            StatusLogMapper statusLogMapper,
                            PickupQueryPort pickupPort,
                            ai.neargo.shop.spi.user.UserQueryPort userPort,
                            IdempotencyService idempotency, OutboxEventBus eventBus) {
        this.orderMapper = orderMapper;
        this.subOrderMapper = subOrderMapper;
        this.itemMapper = itemMapper;
        this.cartMapper = cartMapper;
        this.goodsPort = goodsPort;
        this.stockPort = stockPort;
        this.merchantPort = merchantPort;
        this.attributionPort = attributionPort;
        this.couponPort = couponPort;
        this.campaignPort = campaignPort;
        this.settlePort = settlePort;
        this.statusLogMapper = statusLogMapper;
        this.pickupPort = pickupPort;
        this.userPort = userPort;
        this.idempotency = idempotency;
        this.eventBus = eventBus;
    }

    // ---------------------------------------------------------------- 预览与下单

    @Override
    public OrderVO preview(CreateOrderCommand cmd) {
        Split split = split(cmd);
        // 预览不落库、不锁库存：用户可能在结算页反复改地址与履约方式。
        // 但**优惠要按下单时同一套规则算**，否则结算页显示的金额和实付对不上
        return split.toVO(discountsOf(cmd, split));
    }

    /**
     * 每个 SKU 该送几件赠品。
     *
     * <p>按 goodsNo 查规则、按行的购买数算件数。同一商品分散在多行（不同规格）时
     * **各行分别算** —— 合并算会让「买 2 件 A 规格 + 2 件 B 规格」凑出一份赠品，
     * 而商家的「买 2 送 1」说的是同一规格。
     */
    private Map<String, Integer> giftQtyOf(Split split) {
        if (split.items.isEmpty()) {
            return new HashMap<>();
        }
        Map<String, CampaignPort.GiftRule> rules = campaignPort.giftRules(
                split.items.stream().map(i -> i.snapshot.goodsNo()).distinct().toList());
        Map<String, Integer> out = new HashMap<>();
        for (Line line : split.items) {
            CampaignPort.GiftRule rule = rules.get(line.snapshot.goodsNo());
            if (rule == null) {
                continue;
            }
            int n = rule.giftQty(line.qty);
            if (n > 0) {
                out.merge(line.snapshot.skuNo(), n, Integer::sum);
            }
        }
        return out;
    }

    /**
     * 优惠合计：**先活动、后券**。预览与下单共用 —— 两处各算一次必然算出两个数。
     *
     * <p>顺序不是随便定的。满减是自动生效的（用户没得选），券是用户挑的；
     * 券作用在满减**之后**的金额上，「这张券帮我省了多少」才是他心里那个增量。
     * 反过来（先券后满减）会让同一张券在不同订单里显示的减免额对不上用户的心算。
     *
     * <p>门槛判定也随之落在活动后金额上 —— 这是保守的一侧：
     * 满 100 减 10 之后剩 95，再要用「满 100 可用」的券就不行了。
     * 宽松的一侧（按原价判门槛）会让商家承担两次优惠而事先算不出来。
     */
    private Discounts discountsOf(CreateOrderCommand cmd, Split split) {
        if (split.groups.isEmpty()) {
            return Discounts.none();
        }
        CampaignPort.Discount auto = campaignPort.autoDiscount(split.groups.stream()
                .map(g -> new CampaignPort.MerchantAmount(g.merchantNo, g.goodsAmount()))
                .toList());
        if (cmd.couponNo() == null || cmd.couponNo().isBlank()) {
            return new Discounts(auto, CouponPort.Allocation.none());
        }
        CouponPort.Allocation coupon = couponPort.allocate(SecurityUtils.currentUserNo(), cmd.couponNo(),
                split.groups.stream()
                        .map(g -> new CouponPort.MerchantAmount(
                                g.merchantNo, g.goodsAmount() - auto.of(g.merchantNo)))
                        .toList());
        return new Discounts(auto, coupon);
    }

    /**
     * 一笔订单上的全部优惠。
     *
     * <p>把两种优惠合成一个对象，而不是让下面的落库代码分别问两次 ——
     * 分别问的话，每加一种优惠就要在主单、子单、VO 三处各改一遍，
     * 而漏改一处的症状是「金额对不上」，最难查的那种。
     */
    private record Discounts(CampaignPort.Discount auto, CouponPort.Allocation coupon) {

        static Discounts none() {
            return new Discounts(CampaignPort.Discount.none(), CouponPort.Allocation.none());
        }

        long total() {
            return auto.total() + coupon.totalDiscount();
        }

        long of(String merchantNo) {
            return auto.of(merchantNo) + coupon.discountOf(merchantNo);
        }

        /** 商家出资部分。活动**恒为商家出资**（店铺级活动平台不掏这个钱） */
        long merchantFunded(String merchantNo) {
            return auto.of(merchantNo) + (coupon.byMerchant() ? coupon.discountOf(merchantNo) : 0L);
        }

        long platformFunded(String merchantNo) {
            return coupon.byMerchant() ? 0L : coupon.discountOf(merchantNo);
        }
    }

    @Override
    @Transactional
    public OrderVO create(CreateOrderCommand cmd, String idempotencyKey) {
        String userNo = SecurityUtils.currentUserNo();
        return idempotency.execute(idempotencyKey, "POST /mp/order", userNo, OrderVO.class,
                () -> doCreate(cmd, userNo));
    }

    /**
     * ⚠️ 事务注解放在 {@link #create} 上而不是这里：本方法是被同类的 lambda 调用的，
     * 自调用不走代理，写在这里的 {@code @Transactional} 完全不生效 ——
     * 那样「锁了库存但订单没落库」会变成常态，且测试很难发现。
     */
    private OrderVO doCreate(CreateOrderCommand cmd, String userNo) {
        Split split = split(cmd);
        if (split.items.isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }

        String orderNo = BizKey.next(BizKey.ORDER);
        long now = System.currentTimeMillis();

        /*
         * 买赠：算出每行该送几件。
         *
         * **赠品不阻断下单**：库存不够就少送，而不是让整单失败 ——
         * 为了一件免费的赠品把一笔真实成交挡掉，代价与收益完全不成比例。
         * 少送几件在订单里看得见（赠品行的 qty），商家侧也能对上。
         */
        Map<String, Integer> gifts = giftQtyOf(split);

        // ⑤ 锁库存 —— 放在落库之前：库存不足就整单失败，不留半张订单
        try {
            List<StockPort.SkuQty> lock = new ArrayList<>();
            for (Line i : split.items) {
                // 赠品与付费件是同一个 SKU（活动表里没有「赠哪件」），合并成一次锁
                lock.add(new StockPort.SkuQty(i.skuNo(), i.qty() + gifts.getOrDefault(i.skuNo(), 0)));
            }
            stockPort.lock(orderNo, lock);
        } catch (RuntimeException e) {
            /*
             * 连赠品一起锁失败时**退回只锁付费件**再试一次：
             * 这一步就是「库存不够少送」的落地 —— 不重试的话，
             * 赠品缺货会表现成「这单买不了」，而用户根本没要那个赠品。
             */
            gifts.clear();
            try {
                stockPort.lock(orderNo, split.items.stream()
                        .map(i -> new StockPort.SkuQty(i.skuNo(), i.qty()))
                        .toList());
            } catch (RuntimeException retry) {
                throw BizException.of(ErrorCode.STOCK_NOT_ENOUGH);
            }
        }

        // 优惠：与预览同一套规则（discountsOf 是唯一实现）
        Discounts discounts = discountsOf(cmd, split);

        // ⑥ 落库：主单 + 子单 + 行，同一事务
        OrdOrder order = new OrdOrder();
        order.setOrderNo(orderNo);
        order.setUserNo(userNo);
        order.setPayAmount(split.payAmount() - discounts.total());
        order.setGoodsAmount(split.goodsAmount());
        order.setFreightAmount(split.freightAmount());
        order.setDiscountAmount(discounts.total());
        order.setCurrency(CURRENCY_CNY);
        order.setStatus(OrdOrder.WAIT_PAY);
        /*
         * 社区固化到主单上。**运营按社区做数据域隔离** —— 不写的话，
         * 平台端按社区筛订单永远是空的，而列表本身是好的，看起来只是「这个社区没单」。
         *
         * 固化而不是每次现查用户当前绑定：用户搬家换社区后，历史订单仍属于当时那个社区，
         * 否则昨天的单会跳到新社区的报表里。
         */
        userPort.communityOf(userNo).ifPresent(order::setCommunityNo);
        order.setPayDeadlineAt(now + PAY_TTL.toMillis());
        orderMapper.insert(order);

        List<String> subOrderNos = new ArrayList<>();
        for (Group g : split.groups) {
            String subOrderNo = BizKey.next(BizKey.SUB_ORDER);
            subOrderNos.add(subOrderNo);

            OrdSubOrder sub = new OrdSubOrder();
            sub.setSubOrderNo(subOrderNo);
            sub.setOrderNo(orderNo);
            sub.setUserNo(userNo);
            sub.setEntityNo(g.merchantNo);
            /*
             * 双写门店（M2）：entity_no 是**结算键**（分账/积分/对账都按它），
             * store_no 是**履约键**（发货/自提/评价/门店报表按它）。
             * 单店时两者恒等 —— 这正是多门店能分阶段发布的原因。
             *
             * 取不到门店不让下单失败：订单照常创建，履约侧按「空 → 默认门店」兜底。
             * 为了一个统计维度把下单挡住，代价和收益完全不成比例。
             */
            sub.setStoreNo(merchantPort.defaultStoreNo(g.merchantNo).orElse(null));
            sub.setEntityName(g.merchantName);
            sub.setFulfillment(cmd.fulfillment());
            sub.setPickupNo(cmd.pickupNo());
            // 自提点名称快照（C6）：页面要显示名字，且自提点改名不该影响历史订单
            sub.setPickupName(pickupPort.find(cmd.pickupNo()).map(p -> p.name()).orElse(null));
            sub.setAddressId(cmd.addressId());
            // ★ 归因在下单这一刻固化，不是结算时回查（TDD-backend §7.4）
            sub.setTrafficSource(attributionPort.resolveTrafficSource(userNo, g.merchantNo));
            sub.setGoodsAmount(g.goodsAmount());
            sub.setFreightAmount(g.freight);
            long discount = discounts.of(g.merchantNo);
            sub.setDiscountAmount(discount);
            // 出资方分列（Q9）：合成一列的话 M7 分账无法判断该扣谁的钱
            sub.setDiscountPlatform(discounts.platformFunded(g.merchantNo));
            sub.setDiscountMerchant(discounts.merchantFunded(g.merchantNo));
            sub.setPayAmount(g.goodsAmount() + g.freight - discount);
            sub.setStatus(OrdSubOrder.WAIT_PAY);
            sub.setRemark(cmd.remark());
            subOrderMapper.insert(sub);
            appendStatusLog(subOrderNo, OrdSubOrder.WAIT_PAY, "已下单，待付款",
                    OrdStatusLog.BY_USER, userNo);

            for (Line line : g.lines) {
                OrdItem item = new OrdItem();
                item.setSubOrderNo(subOrderNo);
                item.setOrderNo(orderNo);
                item.setGoodsNo(line.snapshot.goodsNo());
                item.setSkuNo(line.snapshot.skuNo());
                item.setTitle(line.snapshot.title());
                item.setCover(line.snapshot.cover());
                item.setSpec(line.snapshot.spec());
                item.setPrice(line.snapshot.price());
                item.setQty(line.qty);
                item.setAmount(line.amount());
                item.setCategoryType(line.snapshot.categoryType());
                itemMapper.insert(item);

                int giftQty = gifts.getOrDefault(line.snapshot.skuNo(), 0);
                if (giftQty > 0) {
                    /*
                     * 赠品作为**独立的一行**，价格 0、amount 0、is_gift=1。
                     * 不合并进付费行（把 qty 加上去）—— 那样订单里就分不清
                     * 「买了 3 件」还是「买 2 件送 1 件」，而这两者的售后与分账都不同。
                     */
                    OrdItem gift = new OrdItem();
                    gift.setSubOrderNo(subOrderNo);
                    gift.setOrderNo(orderNo);
                    gift.setGoodsNo(line.snapshot.goodsNo());
                    gift.setSkuNo(line.snapshot.skuNo());
                    gift.setTitle(line.snapshot.title());
                    gift.setCover(line.snapshot.cover());
                    gift.setSpec(line.snapshot.spec());
                    gift.setPrice(0L);
                    gift.setQty(giftQty);
                    gift.setAmount(0L);
                    gift.setCategoryType(line.snapshot.categoryType());
                    gift.setIsGift(true);
                    itemMapper.insert(gift);
                }
            }
        }

        // 清掉已下单的购物车行
        cartMapper.delete(Wrappers.<TrdCartItem>lambdaQuery()
                .eq(TrdCartItem::getUserNo, userNo)
                .in(TrdCartItem::getSkuNo, split.items.stream().map(Line::skuNo).toList()));

        // 核销券。放在落库之后：券核销失败要能连订单一起回滚
        couponPort.markUsed(userNo, cmd.couponNo(), orderNo);

        // ⑦ 发事件（只写 outbox，与业务同事务）
        eventBus.publish(new OrderEvents.OrderCreated(orderNo, userNo, subOrderNos, split.payAmount()));

        return detail(orderNo);   // 下单返回支付视角：端上下一步就是去收银台
    }

    // ---------------------------------------------------------------- 支付

    @Override
    public PayResult pay(String orderNo) {
        OrdOrder order = requireOwnOrder(orderNo);
        if (!OrdOrder.WAIT_PAY.equals(order.getStatus())) {
            throw BizException.of(ErrorCode.ORDER_STATE_ILLEGAL);
        }
        if (order.getPayDeadlineAt() != null && order.getPayDeadlineAt() < System.currentTimeMillis()) {
            throw BizException.of(ErrorCode.ORDER_STATE_ILLEGAL);
        }
        // S2 是 stub 通道：返回的参数结构与微信 JSAPI 一致，S4 换真通道时端上不用改
        return new PayResult(orderNo, "STUB", Map.of(
                "prepayId", "stub_" + orderNo,
                "amount", String.valueOf(order.getPayAmount())));
    }

    @Override
    public OrderVO payResult(String orderNo) {
        return detail(orderNo);
    }

    @Override
    @Transactional
    public void markPaid(String orderNo, String payChannel, String payTradeNo) {
        OrdOrder order = orderMapper.selectOne(Wrappers.<OrdOrder>lambdaQuery()
                .eq(OrdOrder::getOrderNo, orderNo).last("limit 1"));
        if (order == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if (OrdOrder.PAID.equals(order.getStatus())) {
            return;   // 幂等：回调会重发，重复到达不能重复扣库存、重复发事件
        }
        OrderStateMachine.assertOrderTransit(order.getStatus(), OrdOrder.PAID);

        order.setStatus(OrdOrder.PAID);
        order.setPayChannel(payChannel);
        order.setPayTradeNo(payTradeNo);
        order.setPaidAt(System.currentTimeMillis());
        orderMapper.updateById(order);

        // 锁定转实扣
        stockPort.confirm(orderNo);

        for (OrdSubOrder sub : subOrders(orderNo)) {
            OrderStateMachine.assertSubOrderTransit(sub.getStatus(), OrdSubOrder.WAIT_FULFILL);
            sub.setStatus(OrdSubOrder.WAIT_FULFILL);
            // 核销码在支付成功后生成：未付款的订单不该有能核销的码。
            // 全局唯一由 uk_verify_code 兜底 —— 撞码时插入失败总比核销台扫出两单强
            sub.setVerifyCode(newVerifyCode());
            subOrderMapper.updateById(sub);
            appendStatusLog(sub.getSubOrderNo(), OrdSubOrder.WAIT_FULFILL, "支付成功，待备货",
                    OrdStatusLog.BY_SYSTEM, null);
        }

        // 结算单与支付状态同事务生成（理由见 SettlePort#generateForOrder）
        settlePort.generateForOrder(orderNo);

        eventBus.publish(new OrderEvents.OrderPaid(orderNo, order.getUserNo(),
                order.getPayAmount(), payChannel));
    }

    // ---------------------------------------------------------------- 查询与取消

    @Override
    public OrderVO detail(String orderNo) {
        // Q6：先按子单号查（C 端绝大多数请求是订单视角），查不到再按主单号
        OrdSubOrder sub = subOrderMapper.selectOne(Wrappers.<OrdSubOrder>lambdaQuery()
                .eq(OrdSubOrder::getSubOrderNo, orderNo)
                .eq(OrdSubOrder::getUserNo, SecurityUtils.currentUserNo())
                .last("limit 1"));
        if (sub != null) {
            OrdOrder order = orderMapper.selectOne(Wrappers.<OrdOrder>lambdaQuery()
                    .eq(OrdOrder::getOrderNo, sub.getOrderNo()).last("limit 1"));
            return orderView(sub, order);
        }
        OrdOrder order = requireOwnOrder(orderNo);
        return payView(order, subOrders(orderNo));
    }

    /** 自提类履约，给展示状态的反向过滤用（SHIPPED 与 ARRIVED 在库里是同一个状态）。 */
    private static final List<String> PICKUP_FULFILLMENTS =
            List.of("STORE_PICKUP", "NEIGHBOR_PICKUP");

    @Override
    public PageData<OrderVO> list(String status, long page, long size) {
        // Q6：列表是子单粒度
        var w = Wrappers.<OrdSubOrder>lambdaQuery()
                .eq(OrdSubOrder::getUserNo, SecurityUtils.currentUserNo());
        // 同 B 端：端上传的是展示状态（PAID / SHIPPED / ARRIVED），库里存的是 WAIT_FULFILL / FULFILLING
        List<String> stored = OrderStatusView.toStored(status);
        if (!stored.isEmpty()) {
            w.in(OrdSubOrder::getStatus, stored);
            Boolean pickupOnly = OrderStatusView.pickupOnly(status);
            if (Boolean.TRUE.equals(pickupOnly)) {
                w.in(OrdSubOrder::getFulfillment, PICKUP_FULFILLMENTS);
            } else if (Boolean.FALSE.equals(pickupOnly)) {
                w.and(x -> x.notIn(OrdSubOrder::getFulfillment, PICKUP_FULFILLMENTS)
                        .or().isNull(OrdSubOrder::getFulfillment));
            }
        }
        w.orderByDesc(OrdSubOrder::getId);

        Page<OrdSubOrder> p = subOrderMapper.selectPage(Page.of(page, size), w);
        if (p.getRecords().isEmpty()) {
            return PageData.empty(page, size);
        }
        Map<String, OrdOrder> orders = orderMapper.selectList(Wrappers.<OrdOrder>lambdaQuery()
                        .in(OrdOrder::getOrderNo,
                                p.getRecords().stream().map(OrdSubOrder::getOrderNo).distinct().toList()))
                .stream().collect(Collectors.toMap(OrdOrder::getOrderNo, o -> o, (a, b) -> a));

        List<OrderVO> records = p.getRecords().stream()
                .map(s -> orderView(s, orders.get(s.getOrderNo())))
                .toList();
        return PageData.of(records, p.getTotal(), p.getCurrent(), p.getSize());
    }

    @Override
    @Transactional
    public OrderVO cancel(String orderNo, String reason) {
        // 端上可能传子单号（订单列表上点取消）：解析成主单，一次支付整体取消
        OrdOrder order = resolveOrder(orderNo);
        OrderStateMachine.assertOrderTransit(order.getStatus(), OrdOrder.CANCELLED);

        order.setStatus(OrdOrder.CANCELLED);
        order.setCancelReason(reason);
        orderMapper.updateById(order);

        for (OrdSubOrder sub : subOrders(order.getOrderNo())) {
            OrderStateMachine.assertSubOrderTransit(sub.getStatus(), OrdSubOrder.CANCELLED);
            sub.setStatus(OrdSubOrder.CANCELLED);
            subOrderMapper.updateById(sub);
            appendStatusLog(sub.getSubOrderNo(), OrdSubOrder.CANCELLED, "订单已取消",
                    OrdStatusLog.BY_USER, order.getUserNo());
        }
        stockPort.release(order.getOrderNo());
        couponPort.release(order.getOrderNo());
        return detail(order.getOrderNo());
    }

    @Override
    @Transactional
    public int closeExpiredOrders(long now) {
        List<OrdOrder> expired = orderMapper.selectList(Wrappers.<OrdOrder>lambdaQuery()
                .eq(OrdOrder::getStatus, OrdOrder.WAIT_PAY)
                .le(OrdOrder::getPayDeadlineAt, now));

        for (OrdOrder order : expired) {
            // 状态先改再释放库存：改失败（并发下已被支付）就不该释放
            order.setStatus(OrdOrder.CLOSED);
            order.setCancelReason("支付超时");
            orderMapper.updateById(order);

            for (OrdSubOrder sub : subOrders(order.getOrderNo())) {
                if (!OrdSubOrder.WAIT_PAY.equals(sub.getStatus())) {
                    continue;
                }
                sub.setStatus(OrdSubOrder.CANCELLED);
                subOrderMapper.updateById(sub);
                appendStatusLog(sub.getSubOrderNo(), OrdSubOrder.CANCELLED, "支付超时，订单关闭",
                        OrdStatusLog.BY_SYSTEM, null);
            }
            // 幂等：release 只作用于 LOCKED 的锁定行，重复跑不会把库存加两遍
            stockPort.release(order.getOrderNo());
            couponPort.release(order.getOrderNo());
        }
        return expired.size();
    }

    @Override
    @Transactional
    public OrderVO confirmReceipt(String subOrderNo) {
        OrdSubOrder sub = subOrderMapper.selectOne(Wrappers.<OrdSubOrder>lambdaQuery()
                .eq(OrdSubOrder::getSubOrderNo, subOrderNo)
                .eq(OrdSubOrder::getUserNo, SecurityUtils.currentUserNo())
                .last("limit 1"));
        if (sub == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        OrderStateMachine.assertSubOrderTransit(sub.getStatus(), OrdSubOrder.COMPLETED);
        sub.setStatus(OrdSubOrder.COMPLETED);
        subOrderMapper.updateById(sub);
        appendStatusLog(subOrderNo, OrdSubOrder.COMPLETED, "已确认收货",
                OrdStatusLog.BY_USER, SecurityUtils.currentUserNo());
        return detail(subOrderNo);
    }

    // ---------------------------------------------------------------- 拆单

    /** 预览与下单共用。**唯一的拆单实现**。 */
    private Split split(CreateOrderCommand cmd) {
        List<CreateOrderCommand.Item> requested = cmd.items() == null || cmd.items().isEmpty()
                ? selectedCartItems() : cmd.items();
        if (requested.isEmpty()) {
            return new Split(List.of(), List.of());
        }

        Map<String, GoodsQueryPort.SkuSnapshot> snapshots =
                goodsPort.snapshot(requested.stream().map(CreateOrderCommand.Item::skuNo).toList());

        List<Line> lines = new ArrayList<>();
        for (CreateOrderCommand.Item item : requested) {
            GoodsQueryPort.SkuSnapshot s = snapshots.get(item.skuNo());
            if (s == null || !s.onSale()) {
                throw BizException.of(ErrorCode.NOT_FOUND);   // 下架商品不允许进入结算
            }
            lines.add(new Line(s, item.qty()));
        }

        // 按商家分组 —— 保持插入序，让预览与订单详情里子单的顺序稳定
        Map<String, List<Line>> byMerchant = lines.stream().collect(Collectors.groupingBy(
                l -> l.snapshot.merchantNo(), LinkedHashMap::new, Collectors.toList()));

        List<Group> groups = byMerchant.entrySet().stream().map(e -> {
            String merchantName = merchantPort.find(e.getKey())
                    .map(MerchantQueryPort.MerchantBrief::merchantName).orElse("");
            // S2 运费恒 0：运费模板属于 P-5.2.3（运营端配置），S3 履约一起做。
            // 先给 0 而不是编一个假数字 —— 假数字会被端上当真的展示给用户
            return new Group(e.getKey(), merchantName, e.getValue(), 0L);
        }).toList();

        return new Split(lines, groups);
    }

    private List<CreateOrderCommand.Item> selectedCartItems() {
        return cartMapper.selectList(Wrappers.<TrdCartItem>lambdaQuery()
                        .eq(TrdCartItem::getUserNo, SecurityUtils.currentUserNo())
                        .eq(TrdCartItem::getSelected, true)).stream()
                .map(c -> new CreateOrderCommand.Item(c.getGoodsNo(), c.getSkuNo(), c.getQty()))
                .toList();
    }

    private record Line(GoodsQueryPort.SkuSnapshot snapshot, int qty) {
        String skuNo() {
            return snapshot.skuNo();
        }

        long amount() {
            return snapshot.price() * qty;
        }
    }

    private record Group(String merchantNo, String merchantName, List<Line> lines, long freight) {
        long goodsAmount() {
            return lines.stream().mapToLong(Line::amount).sum();
        }
    }

    private record Split(List<Line> items, List<Group> groups) {
        long goodsAmount() {
            return groups.stream().mapToLong(Group::goodsAmount).sum();
        }

        long freightAmount() {
            return groups.stream().mapToLong(Group::freight).sum();
        }

        long payAmount() {
            return goodsAmount() + freightAmount();
        }

        /** 预览走**支付视角**：结算页要看的是合计金额与按商家的分组。 */
        OrderVO toVO(Discounts discounts) {
            List<OrderVO> children = groups.stream().map(g -> new OrderVO(
                    null, null, OrdOrder.WAIT_PAY, null, g.merchantNo, g.merchantName,
                    g.lines.stream().map(l -> new OrderVO.ItemVO(
                            l.snapshot.goodsNo(), g.merchantNo, l.snapshot.skuNo(), l.snapshot.title(),
                            l.snapshot.cover(), l.snapshot.spec(), l.snapshot.price(), l.qty,
                            l.amount(), l.snapshot.categoryType(), false)).toList(),
                    OrderVO.Amount.of(g.goodsAmount(), g.freight,
                            discounts.of(g.merchantNo), 0L, CURRENCY_CNY),
                    null, null, null, null, 0L, null, null, null, List.of(), null)).toList();

            return new OrderVO(null, null, OrdOrder.WAIT_PAY, null, null, null,
                    children.stream().flatMap(c -> c.items().stream()).toList(),
                    OrderVO.Amount.of(goodsAmount(), freightAmount(),
                            discounts.total(), 0L, CURRENCY_CNY),
                    null, null, null, null, 0L, null, null, null, List.of(), children);
        }
    }

    // ---------------------------------------------------------------- 装配

    private OrdOrder requireOwnOrder(String orderNo) {
        // 属主鉴权：查询条件带 userNo，而不是查出来再判 —— 防 IDOR 的第一层
        OrdOrder order = orderMapper.selectOne(Wrappers.<OrdOrder>lambdaQuery()
                .eq(OrdOrder::getOrderNo, orderNo)
                .eq(OrdOrder::getUserNo, SecurityUtils.currentUserNo())
                .last("limit 1"));
        if (order == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return order;
    }

    /** 接受主单号或子单号，统一解析成主单（属主校验同样在查询条件里）。 */
    private OrdOrder resolveOrder(String orderNo) {
        OrdSubOrder sub = subOrderMapper.selectOne(Wrappers.<OrdSubOrder>lambdaQuery()
                .eq(OrdSubOrder::getSubOrderNo, orderNo)
                .eq(OrdSubOrder::getUserNo, SecurityUtils.currentUserNo())
                .last("limit 1"));
        return requireOwnOrder(sub == null ? orderNo : sub.getOrderNo());
    }

    private List<OrdSubOrder> subOrders(String orderNo) {
        return subOrderMapper.selectList(Wrappers.<OrdSubOrder>lambdaQuery()
                .eq(OrdSubOrder::getOrderNo, orderNo).orderByAsc(OrdSubOrder::getId));
    }

    private List<OrdItem> itemsOf(String subOrderNo) {
        return itemMapper.selectList(Wrappers.<OrdItem>lambdaQuery()
                .eq(OrdItem::getSubOrderNo, subOrderNo).orderByAsc(OrdItem::getId));
    }

    /** 订单视角（Q6）：单商家，有履约方式、核销码与时间线。 */
    private OrderVO orderView(OrdSubOrder s, OrdOrder order) {
        return new OrderVO(
                // 下发**展示状态**而不是库状态：端上的标签页按前者筛（见 OrderStatusView 的注释）
                s.getSubOrderNo(), s.getOrderNo(),
                OrderStatusView.of(s.getStatus(), s.getFulfillment()), s.getFulfillment(),
                s.getEntityNo(), s.getEntityName(),
                itemsOf(s.getSubOrderNo()).stream().map(this::toItemVO).toList(),
                OrderVO.Amount.of(nz(s.getGoodsAmount()), nz(s.getFreightAmount()),
                        nz(s.getDiscountAmount()),
                        order != null && order.getPaidAt() != null ? nz(s.getPayAmount()) : 0L,
                        order == null ? CURRENCY_CNY : order.getCurrency()),
                s.getVerifyCode(), s.getPickupNo(), s.getPickupName(),
                order == null ? null : order.getPayDeadlineAt(),
                millis(s.getCreatedAt()),
                order == null ? null : order.getPaidAt(),
                // 买家要靠它查物流；此前库里有这一列而 VO 里没有，发货对买家不可见
                s.getExpressNo(),
                s.getTrafficSource(),
                timelineOf(s.getSubOrderNo()),
                null);
    }

    /** 支付视角（Q6）：合计金额 + 各商家子单；**不给 fulfillment**，跨商家可能不同。 */
    private OrderVO payView(OrdOrder order, List<OrdSubOrder> subs) {
        List<OrderVO> children = subs.stream().map(s -> orderView(s, order)).toList();
        List<OrderVO.ItemVO> allItems = children.stream().flatMap(c -> c.items().stream()).toList();
        return new OrderVO(
                order.getOrderNo(), order.getOrderNo(), order.getStatus(), null,
                null, null, allItems,
                OrderVO.Amount.of(nz(order.getGoodsAmount()), nz(order.getFreightAmount()),
                        nz(order.getDiscountAmount()),
                        order.getPaidAt() == null ? 0L : nz(order.getPayAmount()),
                        order.getCurrency()),
                null, null, null,
                order.getPayDeadlineAt(), millis(order.getCreatedAt()), order.getPaidAt(),
                // 支付视角跨商家，没有单一快递号 —— 它在每个子单上
                null, null, List.of(), children);
    }

    private OrderVO.ItemVO toItemVO(OrdItem i) {
        return new OrderVO.ItemVO(i.getGoodsNo(), null, i.getSkuNo(), i.getTitle(), i.getCover(),
                i.getSpec(), nz(i.getPrice()), i.getQty() == null ? 0 : i.getQty(),
                nz(i.getAmount()), i.getCategoryType(), Boolean.TRUE.equals(i.getIsGift()));
    }

    private List<OrderVO.TimelineNode> timelineOf(String subOrderNo) {
        return statusLogMapper.selectList(Wrappers.<OrdStatusLog>lambdaQuery()
                        .eq(OrdStatusLog::getSubOrderNo, subOrderNo)
                        .orderByAsc(OrdStatusLog::getAt).orderByAsc(OrdStatusLog::getId)).stream()
                .map(l -> new OrderVO.TimelineNode(l.getStatus(), l.getLabel(), nz(l.getAt())))
                .toList();
    }

    private void appendStatusLog(String subOrderNo, String status, String label,
                                 String operatorType, String operatorNo) {
        OrdStatusLog log = new OrdStatusLog();
        log.setSubOrderNo(subOrderNo);
        log.setStatus(status);
        log.setLabel(label);
        log.setOperatorType(operatorType);
        log.setOperatorNo(operatorNo);
        log.setAt(System.currentTimeMillis());
        log.setTenantNo("MAIN");
        log.setCreatedAt(java.time.LocalDateTime.now());
        statusLogMapper.insert(log);
    }

    /** 6 位核销码。撞码由 uk_verify_code 兜底 —— 插入失败总比核销台扫出两单强。 */
    private String newVerifyCode() {
        return "%06d".formatted(RANDOM.nextInt(1_000_000));
    }

    private static long millis(java.time.LocalDateTime t) {
        return t == null ? 0L : t.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }
}
