package ai.neargo.shop.trade.port;

import ai.neargo.shop.trade.service.OrderStateMachine;

import ai.neargo.shop.spi.trade.FulfillmentQueryPort;
import ai.neargo.shop.spi.trade.OrderEvents;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdStatusLog;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderItemMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.StatusLogMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper;
import ai.neargo.shop.spi.user.UserQueryPort;
import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * {@link FulfillmentQueryPort} 实现。**订单状态的唯一写入口仍在 trade**。
 *
 * <p>返回给 fulfillment 的结构在这里就已经裁剪掉金额与完整手机号 ——
 * 越权防线④不只在 VO 层，跨模块边界上也拦一道：下游模块拿不到的东西，
 * 无论它怎么写都泄漏不了。
 *
 * <p><b>为什么这里要 {@code executeWithoutScope}</b>：{@code ord_sub_order} 是**一表三维**
 * （user / merchant / pickup）。会话是商家时，数据域拦截器按 {@code entity_no} 过滤 ——
 * 而自提点要看的恰恰是「**别家商家**的货到我点上」，那些行的 entity_no 不是他。
 * 拦截器一过滤，核销台就永远查不到别家的单，症状是「扫码提示码不存在」。
 * 因此这里显式豁免行级维度，改由**方法参数里的 {@code pickupNo} + 上层
 * {@code PickupService.requireScope} 的作用域校验**来保证只看得到自己的点。
 * 换句话说：过滤条件从「拦截器按会话维度」换成「显式参数 + 显式校验」，不是取消过滤。
 */
@Component
public class FulfillmentQueryPortImpl implements FulfillmentQueryPort {

    private final SubOrderMapper subOrderMapper;
    private final OrderItemMapper itemMapper;
    private final StatusLogMapper statusLogMapper;
    private final UserQueryPort userPort;
    private final OutboxEventBus eventBus;

    public FulfillmentQueryPortImpl(SubOrderMapper subOrderMapper, OrderItemMapper itemMapper,
                                    StatusLogMapper statusLogMapper, UserQueryPort userPort,
                                    OutboxEventBus eventBus) {
        this.subOrderMapper = subOrderMapper;
        this.itemMapper = itemMapper;
        this.statusLogMapper = statusLogMapper;
        this.userPort = userPort;
        this.eventBus = eventBus;
    }

    @Override
    public Optional<PickupOrder> findByVerifyCode(String verifyCode) {
        if (verifyCode == null || verifyCode.isBlank()) {
            return Optional.empty();
        }
        OrdSubOrder sub = DataScopeContext.executeWithoutScope(() ->
                subOrderMapper.selectOne(Wrappers.<OrdSubOrder>lambdaQuery()
                        .eq(OrdSubOrder::getVerifyCode, verifyCode).last("limit 1")));
        return Optional.ofNullable(sub).map(this::toPickupOrder);
    }

    @Override
    public List<PickupOrder> ordersOfPickup(String pickupNo, String status) {
        var w = Wrappers.<OrdSubOrder>lambdaQuery().eq(OrdSubOrder::getPickupNo, pickupNo);
        if (status != null && !status.isBlank()) {
            w.eq(OrdSubOrder::getStatus, status);
        } else {
            // 默认只给「还没取走的」：核销台关心的是待办，不是历史
            w.in(OrdSubOrder::getStatus, List.of(OrdSubOrder.WAIT_FULFILL, OrdSubOrder.FULFILLING));
        }
        w.orderByDesc(OrdSubOrder::getId);
        return DataScopeContext.executeWithoutScope(() -> subOrderMapper.selectList(w))
                .stream().map(this::toPickupOrder).toList();
    }

    @Override
    public List<PickupOrder> ordersOfGroup(String groupNo, String status) {
        if (groupNo == null || groupNo.isBlank()) {
            return List.of();
        }
        var w = Wrappers.<OrdSubOrder>lambdaQuery().eq(OrdSubOrder::getGroupNo, groupNo);
        if (status != null && !status.isBlank()) {
            w.eq(OrdSubOrder::getStatus, status);
        } else {
            // 不传状态时给「还没取走的」—— 发起人打开这个页面就是要看还剩谁没来拿
            w.in(OrdSubOrder::getStatus, List.of(OrdSubOrder.WAIT_FULFILL, OrdSubOrder.FULFILLING));
        }
        w.orderByDesc(OrdSubOrder::getId);
        return DataScopeContext.executeWithoutScope(() -> subOrderMapper.selectList(w))
                .stream().map(this::toPickupOrder).toList();
    }

    @Override
    @Transactional
    public boolean complete(String subOrderNo, String operatorNo, String label) {
        OrdSubOrder sub = DataScopeContext.executeWithoutScope(() ->
                subOrderMapper.selectOne(Wrappers.<OrdSubOrder>lambdaQuery()
                        .eq(OrdSubOrder::getSubOrderNo, subOrderNo).last("limit 1")));
        if (sub == null || OrdSubOrder.COMPLETED.equals(sub.getStatus())) {
            return false;
        }
        // 经状态机：核销一个已退款的单必须被拦下
        if (!OrderStateMachine.canTransit(OrderStateMachine.subOrderGraph(),
                sub.getStatus(), OrdSubOrder.COMPLETED)) {
            return false;
        }
        /*
         * 降级单：**核销不等于完成**（F-4）。
         *
         * 供货方就是自提点运营者时，核销那个「独立第三方」并不独立 ——
         * 自己发货、自己核销、自己证明送到了。这一单只剩买家能证明货到了手上，
         * 所以核销只把它推进到「配送中」，由买家的确认收货收口。
         *
         * 返回 false 而不是抛异常：核销台点了没反应会让店主反复点，
         * 而这不是错误、是这一单本来就该这么走。上层据此提示「已登记，待买家确认」。
         */
        if (sub.getRequireBuyerConfirm() != null && sub.getRequireBuyerConfirm() == 1) {
            if (!OrdSubOrder.FULFILLING.equals(sub.getStatus())) {
                sub.setStatus(OrdSubOrder.FULFILLING);
                DataScopeContext.executeWithoutScope(() -> subOrderMapper.updateById(sub));
                appendLog(subOrderNo, OrdSubOrder.FULFILLING,
                        "已核销，等待买家确认收货", operatorNo);
            }
            return false;
        }

        // ⚠️ **写路径同样要豁免**：核销是「商家把买家的单推进到已完成」，天然跨属主。
        // 不豁免的话，UPDATE 会被拼上当前会话的 user_no（店主自己），影响行数 0 ——
        // 而 updateById 返回 0 不会抛异常：核销接口返回成功、订单状态纹丝不动，
        // 店主以为核销了、顾客的单还卡在待取货。这是最难查的一类 bug，因为没有任何报错。
        sub.setStatus(OrdSubOrder.COMPLETED);
        DataScopeContext.executeWithoutScope(() -> subOrderMapper.updateById(sub));

        OrdStatusLog log = new OrdStatusLog();
        log.setSubOrderNo(subOrderNo);
        log.setStatus(OrdSubOrder.COMPLETED);
        log.setLabel(label);
        log.setOperatorType(operatorNo == null ? OrdStatusLog.BY_SYSTEM : OrdStatusLog.BY_MERCHANT);
        log.setOperatorNo(operatorNo);
        log.setAt(System.currentTimeMillis());
        log.setTenantNo("MAIN");
        log.setCreatedAt(java.time.LocalDateTime.now());
        statusLogMapper.insert(log);

        // 核销是自提线走到终态的唯一出口：评价开放、结算解冻计时、通知用户都挂在这条事件上
        eventBus.publish(new OrderEvents.SubOrderCompleted(sub.getSubOrderNo(), sub.getOrderNo(),
                sub.getEntityNo(), sub.getUserNo()));
        return true;
    }

    private PickupOrder toPickupOrder(OrdSubOrder s) {
        List<PickupOrder.Item> items = itemMapper.selectList(Wrappers.<OrdItem>lambdaQuery()
                        .eq(OrdItem::getSubOrderNo, s.getSubOrderNo())).stream()
                .map(i -> new PickupOrder.Item(i.getGoodsNo(), i.getSkuNo(), i.getTitle(),
                        i.getCover(), i.getSpec(), i.getQty() == null ? 0 : i.getQty()))
                .toList();

        var buyer = userPort.find(s.getUserNo());
        return new PickupOrder(s.getSubOrderNo(), s.getVerifyCode(), s.getStatus(),
                s.getPickupNo(), s.getEntityNo(), s.getEntityName(),
                buyer.map(UserQueryPort.UserBrief::nickname).orElse("邻居"),
                buyer.map(UserQueryPort.UserBrief::phoneTail).orElse(""),
                s.getGroupNo(), items);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public List<String> markArrived(List<String> subOrderNos, String pickupNo, String operatorNo) {
        if (subOrderNos == null || subOrderNos.isEmpty()) {
            return List.of();
        }
        List<OrdSubOrder> rows = DataScopeContext.executeWithoutScope(() ->
                subOrderMapper.selectList(Wrappers.<OrdSubOrder>lambdaQuery()
                        .in(OrdSubOrder::getSubOrderNo, subOrderNos)));

        List<String> moved = new java.util.ArrayList<>();
        for (OrdSubOrder sub : rows) {
            /*
             * 只认**本自提点**的单。不校验的话，一个自提点能把别家点的货标成到货，
             * 而那边的买家会收到「可以来取了」，白跑一趟。
             */
            if (pickupNo != null && !pickupNo.equals(sub.getPickupNo())) {
                continue;
            }
            // 幂等：已到货（FULFILLING）或已核销（COMPLETED）的重复点击是常态，静默跳过
            if (!OrdSubOrder.WAIT_FULFILL.equals(sub.getStatus())) {
                continue;
            }
            sub.setStatus(OrdSubOrder.FULFILLING);
            DataScopeContext.executeWithoutScope(() -> subOrderMapper.updateById(sub));
            appendLog(sub.getSubOrderNo(), OrdSubOrder.FULFILLING, "已到自提点，可来取货", operatorNo);
            moved.add(sub.getSubOrderNo());
        }
        return moved;
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public void reportException(String subOrderNo, String operatorNo, String label) {
        OrdSubOrder sub = DataScopeContext.executeWithoutScope(() ->
                subOrderMapper.selectOne(Wrappers.<OrdSubOrder>lambdaQuery()
                        .eq(OrdSubOrder::getSubOrderNo, subOrderNo).last("limit 1")));
        if (sub == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        /*
         * 已核销的单不能再报异常：货已经交到买家手上，此时的短少/破损是售后问题，
         * 走售后有明确的责任认定，而这里只是自提点的留痕，两条路的处置完全不同。
         */
        if (OrdSubOrder.COMPLETED.equals(sub.getStatus())) {
            throw BizException.of(ErrorCode.ORDER_STATE_ILLEGAL);
        }
        /*
         * **只留痕，状态不动、钱不动。**
         * 短少/破损的责任在供货方还是承接方尚未定（矩阵 M4），自动退款等于默认平台兜底。
         * 买家在订单时间线上看得到这条，可以自己走售后。
         */
        appendLog(subOrderNo, sub.getStatus(), label, operatorNo);
    }

    /** 时间线留痕。状态原样传入 —— 留痕不等于状态迁移。 */
    private void appendLog(String subOrderNo, String status, String label, String operatorNo) {
        OrdStatusLog log = new OrdStatusLog();
        log.setSubOrderNo(subOrderNo);
        log.setStatus(status);
        log.setLabel(label);
        log.setOperatorType(operatorNo == null ? OrdStatusLog.BY_SYSTEM : OrdStatusLog.BY_MERCHANT);
        log.setOperatorNo(operatorNo);
        log.setAt(System.currentTimeMillis());
        log.setTenantNo("MAIN");
        log.setCreatedAt(java.time.LocalDateTime.now());
        statusLogMapper.insert(log);
    }
}
