package ai.neargo.shop.trade.service.impl;

import ai.neargo.shop.trade.service.MerchantOrderService;
import ai.neargo.shop.spi.user.UserQueryPort;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.trade.dto.OrderVO;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.trade.entity.OrdStatusLog;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers.StatusLogMapper;
import ai.neargo.shop.trade.service.OrderStateMachine;
import ai.neargo.shop.trade.service.OrderStatusView;
import org.springframework.transaction.annotation.Transactional;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderItemMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper;
import ai.neargo.common.data.scope.DataScopeContext;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MerchantOrderServiceImpl implements MerchantOrderService {

    private final SubOrderMapper subOrderMapper;
    private final OrderItemMapper itemMapper;
    private final StatusLogMapper statusLogMapper;
    /** 社区在**主单**上，子单没有 —— 运营按社区做数据域隔离，所以平台侧要 join 出来 */
    private final ai.neargo.shop.trade.mapper.TradeMappers.OrderMapper orderMapper;
    /** 顾客列表要昵称与头像；**完整手机号不出这个 Port**（B12） */
    private final UserQueryPort userPort;

    public MerchantOrderServiceImpl(SubOrderMapper subOrderMapper, OrderItemMapper itemMapper,
                                    ai.neargo.shop.trade.mapper.TradeMappers.OrderMapper orderMapper,
                                    StatusLogMapper statusLogMapper, UserQueryPort userPort) {
        this.subOrderMapper = subOrderMapper;
        this.itemMapper = itemMapper;
        this.statusLogMapper = statusLogMapper;
        this.orderMapper = orderMapper;
        this.userPort = userPort;
    }

    @Override
    public OrderVO detail(String merchantNo, String storeNo, String subOrderNo) {
        return toVO(require(merchantNo, storeNo, subOrderNo));
    }

    @Override
    @Transactional
    public OrderVO ship(String merchantNo, String storeNo, String subOrderNo, String expressNo) {
        if (expressNo == null || expressNo.isBlank()) {
            /*
             * 没有单号的「已发货」对买家没有任何用处 —— 他既查不到物流，
             * 也无法判断该不该继续等。所以这里拦住，而不是存一个空单号。
             */
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        OrdSubOrder sub = require(merchantNo, storeNo, subOrderNo);
        String no = expressNo.trim();
        /*
         * 状态机对 from==to 是**幂等友好**的（为回调重放设计），所以重复发货不会在这里被拒。
         * 于是要在这一层区分两件事：
         *   同一个单号再发一次 → 重复点击，空操作；
         *   换了一个单号     → 这是「改单号」，允许（填错单号必须能改，
         *                      拒了商家只能打客服），但**必须留痕** ——
         *                      买家那边的物流号变了却查不到是谁改的，是纠纷的开始。
         */
        boolean shipped = OrdSubOrder.FULFILLING.equals(sub.getStatus());
        if (shipped && no.equals(sub.getExpressNo())) {
            return toVO(sub);
        }
        OrderStateMachine.assertSubOrderTransit(sub.getStatus(), OrdSubOrder.FULFILLING);
        String old = sub.getExpressNo();
        sub.setStatus(OrdSubOrder.FULFILLING);
        sub.setExpressNo(no);
        save(sub);
        log(sub, OrdSubOrder.FULFILLING,
                shipped ? "商家改快递单号：" + old + " → " + no : "商家发货：" + no,
                merchantNo);
        return toVO(sub);
    }

    @Override
    @Transactional
    public OrderVO delivered(String merchantNo, String storeNo, String subOrderNo) {
        OrdSubOrder sub = require(merchantNo, storeNo, subOrderNo);
        OrderStateMachine.assertSubOrderTransit(sub.getStatus(), OrdSubOrder.COMPLETED);
        sub.setStatus(OrdSubOrder.COMPLETED);
        save(sub);
        /*
         * 留痕写「商家标记送达」而不是「已完成」——
         * 买家自己确认收货也会把单推到 COMPLETED，纠纷时要能分清是谁点的。
         */
        log(sub, OrdSubOrder.COMPLETED, "商家标记送达", merchantNo);
        return toVO(sub);
    }

    /**
     * 取一单并校验归属。
     *
     * <p><b>查不到就是 NOT_FOUND，不是 FORBIDDEN</b>：后者等于确认「这个单号是真的」，
     * 而单号可枚举 —— 那就成了一个订单探测器。
     */
    private OrdSubOrder require(String merchantNo, String storeNo, String subOrderNo) {
        OrdSubOrder sub = DataScopeContext.executeWithoutScope(() ->
                subOrderMapper.selectOne(Wrappers.<OrdSubOrder>lambdaQuery()
                        .eq(OrdSubOrder::getSubOrderNo, subOrderNo)
                        .eq(OrdSubOrder::getEntityNo, merchantNo)
                        // 门店维度再收窄：只按主体判的话，A 店店员能翻出 B 店的单
                        .eq(storeNo != null && !storeNo.isBlank(), OrdSubOrder::getStoreNo, storeNo)
                        .last("limit 1")));
        if (sub == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return sub;
    }

    private void save(OrdSubOrder sub) {
        DataScopeContext.executeWithoutScope(() -> subOrderMapper.updateById(sub));
    }

    private void log(OrdSubOrder sub, String status, String label, String operatorNo) {
        OrdStatusLog row = new OrdStatusLog();
        row.setSubOrderNo(sub.getSubOrderNo());
        row.setStatus(status);
        row.setLabel(label);
        row.setOperatorType("MERCHANT");
        row.setOperatorNo(operatorNo);
        row.setAt(System.currentTimeMillis());
        row.setTenantNo("MAIN");
        row.setCreatedAt(java.time.LocalDateTime.now());
        DataScopeContext.executeWithoutScope(() -> statusLogMapper.insert(row));
    }

    @Override
    public PageData<OrderVO> list(String merchantNo, java.util.Collection<String> storeNos,
                                  String status, long page, long size) {
        var w = Wrappers.<OrdSubOrder>lambdaQuery().eq(OrdSubOrder::getEntityNo, merchantNo);
        /*
         * 门店过滤。**结算键 entity_no 仍然保留** —— 两个键各管各的：
         * entity_no 是「这单的钱算谁的」（历史快照，门店换主体也不改），
         * store_no 是「这单在哪家店履约」。
         *
         * null = 不过滤（属主的「全部门店」，含早于多门店、store_no 为空的历史单）；
         * **空集合 = 一家都看不到**，不是「不过滤」——
         * 把空集合当成不过滤，是「没被授权的员工反而看到全部」这类越权最常见的写法。
         */
        if (storeNos != null) {
            if (storeNos.isEmpty()) {
                return PageData.of(List.of(), 0, page, size);
            }
            w.in(OrdSubOrder::getStoreNo, storeNos);
        }
        /*
         * 按**展示状态**筛。端上传来的是 PAID / SHIPPED / ARRIVED 这类词
         * （b-app 的三个标签页就是它们），而库里存的是 WAIT_FULFILL / FULFILLING ——
         * 此前直接拿去比库状态，一条也匹配不上：商家的「待发货」永远是空的，
         * 而「全部」是好的，所以看起来只是几个页签没数据。
         */
        List<String> stored = OrderStatusView.toStored(status);
        if (!stored.isEmpty()) {
            w.in(OrdSubOrder::getStatus, stored);
            // 发货与到货在库里是同一个状态，靠履约方式区分
            Boolean pickupOnly = OrderStatusView.pickupOnly(status);
            if (Boolean.TRUE.equals(pickupOnly)) {
                w.in(OrdSubOrder::getFulfillment, PICKUP_FULFILLMENTS);
            } else if (Boolean.FALSE.equals(pickupOnly)) {
                w.and(x -> x.notIn(OrdSubOrder::getFulfillment, PICKUP_FULFILLMENTS)
                        .or().isNull(OrdSubOrder::getFulfillment));
            }
        }
        w.orderByDesc(OrdSubOrder::getId);

        /*
         * ★ **显式豁免数据域**（与 MerchantGoodsServiceImpl 同一套做法）。
         *
         * 商家用的是消费者令牌，会话的数据域维度是 SELF；而 `ord_sub_order` 上
         * SELF 锚定的是 `user_no`（买家）。不豁免的话，SQL 会追加
         * `user_no = <商家自己的 userNo>` —— 商家在订单页只看得到**他自己买过的单**，
         * 卖出去的一单都看不到，而且不报错，只是"今天没有订单"。
         *
         * 这里可以豁免，是因为归属判断已经由上面那句 `eq(entity_no, merchantNo)` 做掉了，
         * 而 merchantNo 来自 BizContext（授权边界），不是请求参数。
         */
        Page<OrdSubOrder> p = DataScopeContext.executeWithoutScope(() ->
                subOrderMapper.selectPage(Page.of(page, size), w));
        List<OrderVO> records = p.getRecords().stream().map(this::toVO).toList();
        return PageData.of(records, p.getTotal(), p.getCurrent(), p.getSize());
    }

    /**
     * 商家视角：**有金额**（这是他自己的钱），但**没有买家完整手机号** ——
     * 需要联系买家走平台客服通道，而不是把号码散出去（M11/B12）。
     */
    private OrderVO toVO(OrdSubOrder s) {
        List<OrderVO.ItemVO> items = itemMapper.selectList(Wrappers.<OrdItem>lambdaQuery()
                        .eq(OrdItem::getSubOrderNo, s.getSubOrderNo())).stream()
                .map(i -> new OrderVO.ItemVO(i.getGoodsNo(), s.getEntityNo(), i.getSkuNo(),
                        i.getTitle(), i.getCover(), i.getSpec(),
                        i.getPrice() == null ? 0L : i.getPrice(),
                        i.getQty() == null ? 0 : i.getQty(),
                        i.getAmount() == null ? 0L : i.getAmount(), i.getCategoryType(),
                        Boolean.TRUE.equals(i.getIsGift())))
                .toList();

        // paidAt 只在主单上（子单没有这一列），同 toOpsVO 的做法 join 回去取
        var main = DataScopeContext.executeWithoutScope(() ->
                orderMapper.selectOne(Wrappers.<ai.neargo.shop.trade.entity.OrdOrder>lambdaQuery()
                        .eq(ai.neargo.shop.trade.entity.OrdOrder::getOrderNo, s.getOrderNo())
                        .last("limit 1")));

        return new OrderVO(s.getSubOrderNo(), s.getOrderNo(),
                // 同 C 端：下发展示状态。b-app 的「待发货/已发货/待核销」三个标签页靠它区分
                OrderStatusView.of(s.getStatus(), s.getFulfillment()), s.getFulfillment(),
                s.getEntityNo(), s.getEntityName(), items,
                OrderVO.Amount.of(nz(s.getGoodsAmount()), nz(s.getFreightAmount()),
                        nz(s.getDiscountAmount()), nz(s.getPayAmount()), "CNY"),
                s.getVerifyCode(), s.getPickupNo(), s.getPickupName(),
                null,
                s.getCreatedAt() == null ? 0L
                        : s.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                main == null ? null : main.getPaidAt(),
                // 商家也要看得到自己填了什么单号 —— 否则改单号之后无从核对
                s.getExpressNo(), s.getTrafficSource(), receiverFor(s), List.of(), null);
    }

    private static final int PHONE_TAIL = 4;

    /**
     * 商家看到的收件人。**脱敏口径分两档**：
     *
     * <ul>
     *   <li><b>商家自送</b>（{@code MERCHANT_DELIVERY}）→ <b>完整手机号</b>。
     *       送到楼下找不到人就得打电话，给后四位等于让人站在原地干瞪眼。
     *       2026-08-12 产品确认放开这一档</li>
     *   <li>其余履约方式 → 后四位。B12 的原判断不变：商家不需要能打给每一个买家，
     *       需要联系时走平台客服通道</li>
     * </ul>
     *
     * <p><b>判断写在这里而不是 VO 上</b>：VO 只是形状，「谁能看到多少」是装配时的决定。
     * 写进 VO 的话，换一个装配路径（比如平台端）就会不知不觉套用商家的口径。
     */
    private static OrderVO.Receiver receiverFor(OrdSubOrder s) {
        if (s.getReceiverName() == null && s.getReceiverAddress() == null) {
            return null;
        }
        String phone = s.getReceiverPhone();
        boolean selfDelivery = MERCHANT_DELIVERY.equals(s.getFulfillment());
        return new OrderVO.Receiver(s.getReceiverName(),
                selfDelivery ? phone : tail(phone), s.getReceiverAddress());
    }

    private static String tail(String phone) {
        return phone == null || phone.length() < PHONE_TAIL ? null
                : "****" + phone.substring(phone.length() - PHONE_TAIL);
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    // ---------------------------------------------------------------- 工作台（B-11.1）

    /** 履约方式。取值域见 {@link OrdSubOrder#getFulfillment()} 的注释。 */
    private static final String EXPRESS = "EXPRESS";
    private static final String MERCHANT_DELIVERY = "MERCHANT_DELIVERY";
    private static final java.util.Set<String> PICKUP =
            java.util.Set.of("STORE_PICKUP", "NEIGHBOR_PICKUP");
    /** 同 PICKUP，给 SQL 的 in/notIn 用（Wrapper 要 Collection） */
    private static final List<String> PICKUP_FULFILLMENTS =
            List.of("STORE_PICKUP", "NEIGHBOR_PICKUP");

    @Override
    public TodoCounts todo(String merchantNo, java.util.Collection<String> storeNos,
                           java.util.Collection<String> pickupNos) {
        /*
         * 发货两个数：**商家视角，按门店**。
         * 空集合 = 一家门店都没被授权 → 0，而不是「不过滤」看到全主体。
         */
        int toShip = 0;
        int toDeliver = 0;
        int toStock = 0;
        if (storeNos == null || !storeNos.isEmpty()) {
            for (OrdSubOrder o : scan(merchantNo, storeNos,
                    w -> w.eq(OrdSubOrder::getStatus, OrdSubOrder.WAIT_FULFILL))) {
                // 已发货（FULFILLING）不再是待办 —— 剩下的是买家收货，商家没事可做
                String f = o.getFulfillment();
                if (PICKUP_FULFILLMENTS.contains(f)) {
                    /*
                     * **待备货**：把货备好送到买家选的那个自提点去，这是供货方的活。
                     *
                     * 与下面的 toPick 是同一批单的两头，但**两个数不相等**：
                     * 买家常常选别家的点。把 toPick 改成按自提点算之后，
                     * 「我有货要送出去」这件事一度**在工作台上完全消失了** ——
                     * 有活、没数字、也没入口。这一格补的就是它。
                     */
                    toStock += 1;
                } else if (MERCHANT_DELIVERY.equals(f)) {
                    toDeliver += 1;
                } else {
                    // fulfillment 为空按快递算，与下单侧的默认一致
                    toShip += 1;
                }
            }
        }

        /*
         * 自提两个数：**自提点承接方视角，按自提点，且不限商家**。
         *
         * 与 `PickupService.picking` / `orders` 同一口径 —— 它们也是按点取、不按商家过滤，
         * 因为一个自提点承接多家商家的货（ADR-005），别家的货同样要我分、我核。
         *
         * 这里曾经与上面合用一次「按门店」的扫描，于是买家选了别家点的那些单
         * 被算进了我的「待分拣」：**工作台显示 1，点进去分拣单 0 件**。
         * 商家看到的是「有活，但找不到」，而两边的代码各自都说得通。
         */
        int toVerify = 0;
        int toPick = 0;
        if (pickupNos != null && !pickupNos.isEmpty()) {
            List<OrdSubOrder> atMyPickups = DataScopeContext.executeWithoutScope(() ->
                    subOrderMapper.selectList(Wrappers.<OrdSubOrder>lambdaQuery()
                            .in(OrdSubOrder::getPickupNo, pickupNos)
                            .in(OrdSubOrder::getFulfillment, PICKUP_FULFILLMENTS)
                            .in(OrdSubOrder::getStatus,
                                    OrdSubOrder.WAIT_FULFILL, OrdSubOrder.FULFILLING)));
            for (OrdSubOrder o : atMyPickups) {
                /*
                 * 自提两段：**到货前是分拣，到货后才是核销**。
                 * 合成一个数的话，商家看到「待核销 12」却在自提点找不到货 ——
                 * 因为那 12 单根本还没到店。
                 */
                if (OrdSubOrder.WAIT_FULFILL.equals(o.getStatus())) {
                    toPick += 1;
                } else {
                    toVerify += 1;
                }
            }
        }
        return new TodoCounts(toShip, toDeliver, toStock, toVerify, toPick);
    }

    @Override
    public StatsSummary stats(String merchantNo, java.util.Collection<String> storeNos) {
        if (storeNos != null && storeNos.isEmpty()) {
            return new StatsSummary(0, 0, 0, 0, 0d);
        }
        /*
         * 只统计**已付款**的单：WAIT_PAY 还不是生意，把它算进 GMV
         * 会让商家看到一个自己收不到的数字，而且刷新一下就变小。
         * 已退款（REFUNDED）保留在内：那笔钱确实成交过，退款在结算侧另算。
         */
        java.time.LocalDate today = java.time.LocalDate.now();
        java.time.LocalDateTime monthStart = today.withDayOfMonth(1).atStartOfDay();
        List<OrdSubOrder> rows = scan(merchantNo, storeNos,
                w -> w.ne(OrdSubOrder::getStatus, OrdSubOrder.WAIT_PAY)
                        .ge(OrdSubOrder::getCreatedAt, monthStart));

        int todayOrders = 0;
        long todayGmv = 0;
        long monthGmv = 0;
        int owned = 0;
        int attributed = 0;
        for (OrdSubOrder o : rows) {
            monthGmv += o.getPayAmount() == null ? 0 : o.getPayAmount();
            if (o.getCreatedAt() != null && o.getCreatedAt().toLocalDate().isEqual(today)) {
                todayOrders += 1;
                todayGmv += o.getPayAmount() == null ? 0 : o.getPayAmount();
            }
            if (o.getTrafficSource() != null && !o.getTrafficSource().isBlank()) {
                attributed += 1;
                if ("MERCHANT_OWNED".equals(o.getTrafficSource())) {
                    owned += 1;
                }
            }
        }
        /*
         * 分母是**有归因的单**，不是全部单 —— 归因上线之前的历史单 traffic_source 为空，
         * 算进分母会把商家的自带客流比例凭空冲低，而那个比例决定他的费率档。
         */
        double rate = attributed == 0 ? 0d : owned / (double) attributed;
        return new StatsSummary(todayOrders, todayGmv, rows.size(), monthGmv, rate);
    }

    /**
     * 按主体 + 门店范围捞子单。
     *
     * <p>豁免数据域的理由与 {@link #list} 完全一致：商家用的是消费者令牌，
     * 数据域 SELF 在 {@code ord_sub_order} 上锚的是 {@code user_no}（买家），
     * 不豁免的话商家只看得到**他自己买过的单**，卖出去的一单都不算 ——
     * 而工作台不会报错，只是永远显示 0。
     */
    private List<OrdSubOrder> scan(String merchantNo, java.util.Collection<String> storeNos,
                                   java.util.function.Consumer<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<OrdSubOrder>> extra) {
        var w = Wrappers.<OrdSubOrder>lambdaQuery().eq(OrdSubOrder::getEntityNo, merchantNo);
        if (storeNos != null) {
            w.in(OrdSubOrder::getStoreNo, storeNos);
        }
        extra.accept(w);
        return DataScopeContext.executeWithoutScope(() -> subOrderMapper.selectList(w));
    }

    // ---------------------------------------------------------------- 顾客（B-11.10）

    /** 沉默的判定：来过两次以上、却已经 30 天没来。只来过一次的不算沉默，那是没留住 */
    private static final int SILENT_MIN_ORDERS = 2;
    private static final int SILENT_DAYS = 30;

    @Override
    public List<CustomerSummary> customers(String merchantNo, java.util.Collection<String> storeNos) {
        if (storeNos != null && storeNos.isEmpty()) {
            return List.of();
        }
        List<OrdSubOrder> rows = scan(merchantNo, storeNos,
                w -> w.ne(OrdSubOrder::getStatus, OrdSubOrder.WAIT_PAY));

        // 按买家聚合。一个人在本店下过几单、花了多少、最后一次是什么时候
        java.util.Map<String, java.util.List<OrdSubOrder>> byUser = rows.stream()
                .filter(o -> o.getUserNo() != null)
                .collect(java.util.stream.Collectors.groupingBy(OrdSubOrder::getUserNo));

        long now = System.currentTimeMillis();
        List<CustomerSummary> out = new java.util.ArrayList<>();
        for (var e : byUser.entrySet()) {
            var orders = e.getValue();
            long spent = orders.stream().mapToLong(o -> o.getPayAmount() == null ? 0 : o.getPayAmount()).sum();
            long last = orders.stream()
                    .map(OrdSubOrder::getCreatedAt)
                    .filter(java.util.Objects::nonNull)
                    .mapToLong(t -> t.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
                    .max().orElse(0L);
            int days = last == 0 ? 0 : (int) java.time.Duration.ofMillis(now - last).toDays();
            boolean silent = orders.size() >= SILENT_MIN_ORDERS && days >= SILENT_DAYS;
            /*
             * 来源取**最早一单**的归因：这个人当初是谁带来的，不会因为他后来从
             * 平台首页再进来一次就变成平台客流。费率档按自带客流算，口径必须稳定。
             */
            String source = orders.stream()
                    .min(java.util.Comparator.comparing(OrdSubOrder::getId))
                    .map(OrdSubOrder::getTrafficSource)
                    .filter(x -> x != null && !x.isBlank())
                    .orElse("PLATFORM");

            var brief = userPort.find(e.getKey());
            out.add(new CustomerSummary(e.getKey(),
                    brief.map(p -> p.nickname()).orElse("邻居"),
                    brief.map(p -> p.avatar()).orElse(""),
                    orders.size(), spent, last, days, silent, source));
        }
        // 沉默客户排前面 —— 那是店主唯一能立刻行动的一批
        out.sort(java.util.Comparator.comparing(CustomerSummary::silent).reversed()
                .thenComparing(java.util.Comparator.comparingLong(CustomerSummary::lastOrderAt).reversed()));
        return out;
    }

    // ---------------------------------------------------------------- 平台端（P-4.1）

    /**
     * 各状态允许卡多久（分钟）。**按状态分别给，不是一刀切**：
     * 待支付 15 分钟就该关单，而「已到自提点待取」放一天很正常 ——
     * 一刀切会把正常单刷进异常队列，而队列一旦变成噪音就没人看了。
     * 终态（COMPLETED / CANCELLED / REFUNDED）不设时限。
     */
    private static final java.util.Map<String, Long> STUCK_MINUTES = java.util.Map.of(
            OrderStatusView.WAIT_PAY, 15L,
            OrderStatusView.PAID, 120L,
            OrderStatusView.SHIPPED, 240L,
            OrderStatusView.ARRIVED, 1440L);

    @Override
    public PageData<OpsOrderVO> opsList(String status, String merchantNo, String keyword,
                                        long page, long size) {
        var w = Wrappers.<OrdSubOrder>lambdaQuery();
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
        if (merchantNo != null && !merchantNo.isBlank()) {
            w.eq(OrdSubOrder::getEntityNo, merchantNo);
        }
        if (keyword != null && !keyword.isBlank()) {
            w.and(x -> x.like(OrdSubOrder::getSubOrderNo, keyword)
                    .or().like(OrdSubOrder::getOrderNo, keyword));
        }
        w.orderByDesc(OrdSubOrder::getId);

        /*
         * 平台侧是**跨商家**查询，必须解除数据域 —— 运营的会话没有 entity 维度，
         * 不解除的话这里会按运营自己的 user_no 过滤，结果恒为空。
         */
        Page<OrdSubOrder> p = DataScopeContext.executeWithoutScope(() ->
                subOrderMapper.selectPage(Page.of(page, size), w));
        List<OpsOrderVO> rows = p.getRecords().stream().map(this::toOpsVO).toList();
        return PageData.of(rows, p.getTotal(), page, size);
    }

    @Override
    public OpsOrderVO opsDetail(String subOrderNo) {
        return toOpsVO(requireAny(subOrderNo));
    }

    @Override
    public List<OpsOrderVO> siblings(String parentNo) {
        return DataScopeContext.executeWithoutScope(() ->
                        subOrderMapper.selectList(Wrappers.<OrdSubOrder>lambdaQuery()
                                .eq(OrdSubOrder::getOrderNo, parentNo)
                                .orderByAsc(OrdSubOrder::getId)))
                .stream().map(this::toOpsVO).toList();
    }

    @Override
    public List<OrderExceptionVO> exceptions() {
        long now = System.currentTimeMillis();
        /*
         * 只扫非终态的单。异常队列是**实时算出来的视图**，不落表 ——
         * 落表就会过期：订单已经推进了，异常记录还挂在那里，
         * 运营会去处理一个不存在的问题。
         */
        List<OrdSubOrder> live = DataScopeContext.executeWithoutScope(() ->
                subOrderMapper.selectList(Wrappers.<OrdSubOrder>lambdaQuery()
                        .in(OrdSubOrder::getStatus, OrdSubOrder.WAIT_PAY,
                                OrdSubOrder.WAIT_FULFILL, OrdSubOrder.FULFILLING)));

        List<OrderExceptionVO> out = new java.util.ArrayList<>();
        for (OrdSubOrder o : live) {
            OpsOrderVO vo = toOpsVO(o);
            Long threshold = STUCK_MINUTES.get(vo.status());
            if (threshold == null) {
                continue;
            }
            long stuck = (now - vo.statusAt()) / 60_000L;
            if (stuck <= threshold) {
                continue;
            }
            // 待支付超时是关单任务本身出了问题，与「卡在某个环节」不是一回事
            String kind = OrderStatusView.WAIT_PAY.equals(vo.status()) ? "PAY_TIMEOUT" : "STUCK";
            out.add(new OrderExceptionVO(vo, kind, stuck, threshold));
        }
        out.sort((a, b) -> Long.compare(b.stuckMinutes(), a.stuckMinutes()));
        return out;
    }

    @Override
    public List<InterventionVO> interventions(String subOrderNo) {
        return DataScopeContext.executeWithoutScope(() ->
                        statusLogMapper.selectList(Wrappers.<OrdStatusLog>lambdaQuery()
                                .eq(OrdStatusLog::getSubOrderNo, subOrderNo)
                                .eq(OrdStatusLog::getOperatorType, OrdStatusLog.BY_PLATFORM)
                                .orderByDesc(OrdStatusLog::getId)))
                .stream()
                .map(l -> new InterventionVO(subOrderNo, null, l.getStatus(), l.getLabel(),
                        l.getOperatorNo(), l.getAt() == null ? 0L : l.getAt()))
                .toList();
    }

    @Override
    @Transactional
    public OpsOrderVO intervene(String subOrderNo, String to, String remark, String operatorNo) {
        if (remark == null || remark.isBlank()) {
            // 改状态这件事事后要说得清是谁、为什么 —— 没有原因的干预无法复盘
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        OrdSubOrder sub = requireAny(subOrderNo);
        List<String> target = OrderStatusView.toStored(to);
        if (target.isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        String stored = target.get(0);
        /*
         * 迁移由**后端状态机**判定，不采信端上那份 ORDER_TRANSITIONS ——
         * 那份表只是让界面提前把不可能的选项灰掉。两份表都存在时，
         * 必须有一份是权威，否则迟早出现「界面允许、后端拒绝」或更糟的反过来。
         */
        OrderStateMachine.assertSubOrderTransit(sub.getStatus(), stored);
        String from = OrderStatusView.of(sub.getStatus(), sub.getFulfillment());

        sub.setStatus(stored);
        DataScopeContext.executeWithoutScope(() -> subOrderMapper.updateById(sub));

        OrdStatusLog log = new OrdStatusLog();
        log.setSubOrderNo(subOrderNo);
        log.setStatus(stored);
        log.setLabel("人工干预（" + from + " → " + to + "）：" + remark.trim());
        log.setOperatorType(OrdStatusLog.BY_PLATFORM);
        log.setOperatorNo(operatorNo);
        log.setAt(System.currentTimeMillis());
        log.setTenantNo("MAIN");
        log.setCreatedAt(java.time.LocalDateTime.now());
        statusLogMapper.insert(log);
        return toOpsVO(sub);
    }

    /** 平台侧取单：不限商家，但仍要解除数据域（运营会话没有 entity 维度）。 */
    private OrdSubOrder requireAny(String subOrderNo) {
        OrdSubOrder sub = DataScopeContext.executeWithoutScope(() ->
                subOrderMapper.selectOne(Wrappers.<OrdSubOrder>lambdaQuery()
                        .eq(OrdSubOrder::getSubOrderNo, subOrderNo).last("limit 1")));
        if (sub == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return sub;
    }

    private OpsOrderVO toOpsVO(OrdSubOrder s) {
        List<OrdItem> items = DataScopeContext.executeWithoutScope(() ->
                itemMapper.selectList(Wrappers.<OrdItem>lambdaQuery()
                        .eq(OrdItem::getSubOrderNo, s.getSubOrderNo())));
        // 社区在主单上，子单没有
        var main = DataScopeContext.executeWithoutScope(() ->
                orderMapper.selectOne(Wrappers.<ai.neargo.shop.trade.entity.OrdOrder>lambdaQuery()
                        .eq(ai.neargo.shop.trade.entity.OrdOrder::getOrderNo, s.getOrderNo())
                        .last("limit 1")));

        long created = s.getCreatedAt() == null ? 0L
                : s.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        /*
         * statusAt 取最后一条状态日志的时间；没有日志就退回 updatedAt。
         * **不能用 createdAt** —— 异常单的「卡了多久」是从进入当前状态算起的，
         * 用下单时间算，一条正常流转了三天的单会被当成卡了三天。
         */
        OrdStatusLog last = DataScopeContext.executeWithoutScope(() ->
                statusLogMapper.selectOne(Wrappers.<OrdStatusLog>lambdaQuery()
                        .eq(OrdStatusLog::getSubOrderNo, s.getSubOrderNo())
                        .orderByDesc(OrdStatusLog::getId).last("limit 1")));
        long statusAt = last != null && last.getAt() != null ? last.getAt()
                : (s.getUpdatedAt() == null ? created
                : s.getUpdatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());

        return new OpsOrderVO(s.getSubOrderNo(), s.getOrderNo(),
                OrderStatusView.of(s.getStatus(), s.getFulfillment()),
                s.getEntityNo(), s.getEntityName(),
                main == null ? null : main.getCommunityNo(),
                s.getPickupNo(), s.getFulfillment(), s.getTrafficSource(),
                s.getPickupName(),
                items.stream().map(i -> new OpsOrderVO.ItemVO(i.getSkuNo(), i.getTitle(),
                        i.getQty() == null ? 0 : i.getQty(),
                        i.getPrice() == null ? 0L : i.getPrice())).toList(),
                s.getPayAmount() == null ? 0L : s.getPayAmount(),
                created, null, statusAt);
    }
}
