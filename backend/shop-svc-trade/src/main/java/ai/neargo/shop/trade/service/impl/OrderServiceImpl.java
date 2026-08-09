package ai.neargo.shop.trade.service.impl;

import ai.neargo.shop.spi.user.PickupQueryPort;
import ai.neargo.shop.trade.service.OrderService;
import ai.neargo.shop.trade.service.OrderStateMachine;

import ai.neargo.shop.spi.marketing.AttributionPort;
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
    private final SettlePort settlePort;
    private final StatusLogMapper statusLogMapper;
    private final PickupQueryPort pickupPort;
    private final IdempotencyService idempotency;
    private final OutboxEventBus eventBus;

    public OrderServiceImpl(OrderMapper orderMapper, SubOrderMapper subOrderMapper, OrderItemMapper itemMapper,
                            CartItemMapper cartMapper, GoodsQueryPort goodsPort, StockPort stockPort,
                            MerchantQueryPort merchantPort, AttributionPort attributionPort,
                            CouponPort couponPort, SettlePort settlePort,
                            StatusLogMapper statusLogMapper,
                            PickupQueryPort pickupPort,
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
        this.settlePort = settlePort;
        this.statusLogMapper = statusLogMapper;
        this.pickupPort = pickupPort;
        this.idempotency = idempotency;
        this.eventBus = eventBus;
    }

    // ---------------------------------------------------------------- 预览与下单

    @Override
    public OrderVO preview(CreateOrderCommand cmd) {
        Split split = split(cmd);
        // 预览不落库、不锁库存：用户可能在结算页反复改地址与履约方式。
        // 但**优惠要按下单时同一套规则算**，否则结算页显示的金额和实付对不上
        return split.toVO(allocateCoupon(cmd, split));
    }

    /** 券分摊。预览与下单共用 —— 两处各算一次必然算出两个数。 */
    private CouponPort.Allocation allocateCoupon(CreateOrderCommand cmd, Split split) {
        if (cmd.couponNo() == null || cmd.couponNo().isBlank() || split.groups.isEmpty()) {
            return CouponPort.Allocation.none();
        }
        return couponPort.allocate(SecurityUtils.currentUserNo(), cmd.couponNo(),
                split.groups.stream()
                        .map(g -> new CouponPort.MerchantAmount(g.merchantNo, g.goodsAmount()))
                        .toList());
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

        // ⑤ 锁库存 —— 放在落库之前：库存不足就整单失败，不留半张订单
        try {
            stockPort.lock(orderNo, split.items.stream()
                    .map(i -> new StockPort.SkuQty(i.skuNo(), i.qty()))
                    .toList());
        } catch (RuntimeException e) {
            throw BizException.of(ErrorCode.STOCK_NOT_ENOUGH);
        }

        // 券分摊：与预览同一套规则（allocateCoupon 是唯一实现）
        CouponPort.Allocation allocation = allocateCoupon(cmd, split);

        // ⑥ 落库：主单 + 子单 + 行，同一事务
        OrdOrder order = new OrdOrder();
        order.setOrderNo(orderNo);
        order.setUserNo(userNo);
        order.setPayAmount(split.payAmount() - allocation.totalDiscount());
        order.setGoodsAmount(split.goodsAmount());
        order.setFreightAmount(split.freightAmount());
        order.setDiscountAmount(allocation.totalDiscount());
        order.setCurrency(CURRENCY_CNY);
        order.setStatus(OrdOrder.WAIT_PAY);
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
            long discount = allocation.discountOf(g.merchantNo);
            sub.setDiscountAmount(discount);
            // 出资方分列（Q9）：合成一列的话 M7 分账无法判断该扣谁的钱
            sub.setDiscountPlatform(allocation.byMerchant() ? 0L : discount);
            sub.setDiscountMerchant(allocation.byMerchant() ? discount : 0L);
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

    @Override
    public PageData<OrderVO> list(String status, long page, long size) {
        // Q6：列表是子单粒度
        var w = Wrappers.<OrdSubOrder>lambdaQuery()
                .eq(OrdSubOrder::getUserNo, SecurityUtils.currentUserNo());
        if (status != null && !status.isBlank()) {
            w.eq(OrdSubOrder::getStatus, status);
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
        OrderVO toVO(CouponPort.Allocation allocation) {
            List<OrderVO> children = groups.stream().map(g -> new OrderVO(
                    null, null, OrdOrder.WAIT_PAY, null, g.merchantNo, g.merchantName,
                    g.lines.stream().map(l -> new OrderVO.ItemVO(
                            l.snapshot.goodsNo(), g.merchantNo, l.snapshot.skuNo(), l.snapshot.title(),
                            l.snapshot.cover(), l.snapshot.spec(), l.snapshot.price(), l.qty,
                            l.amount(), l.snapshot.categoryType())).toList(),
                    OrderVO.Amount.of(g.goodsAmount(), g.freight,
                            allocation.discountOf(g.merchantNo), 0L, CURRENCY_CNY),
                    null, null, null, null, 0L, null, null, null, List.of(), null)).toList();

            return new OrderVO(null, null, OrdOrder.WAIT_PAY, null, null, null,
                    children.stream().flatMap(c -> c.items().stream()).toList(),
                    OrderVO.Amount.of(goodsAmount(), freightAmount(),
                            allocation.totalDiscount(), 0L, CURRENCY_CNY),
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
                s.getSubOrderNo(), s.getOrderNo(), s.getStatus(), s.getFulfillment(),
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
                nz(i.getAmount()), i.getCategoryType());
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
