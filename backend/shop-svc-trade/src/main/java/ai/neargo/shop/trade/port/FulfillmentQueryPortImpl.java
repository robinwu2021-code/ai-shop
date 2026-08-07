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
 * （user / merchant / pickup）。会话是商家时，数据域拦截器按 {@code merchant_no} 过滤 ——
 * 而自提点要看的恰恰是「**别家商家**的货到我点上」，那些行的 merchant_no 不是他。
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
                sub.getMerchantNo(), sub.getUserNo()));
        return true;
    }

    private PickupOrder toPickupOrder(OrdSubOrder s) {
        List<PickupOrder.Item> items = itemMapper.selectList(Wrappers.<OrdItem>lambdaQuery()
                        .eq(OrdItem::getSubOrderNo, s.getSubOrderNo())).stream()
                .map(i -> new PickupOrder.Item(i.getGoodsNo(), i.getTitle(), i.getSpec(),
                        i.getQty() == null ? 0 : i.getQty()))
                .toList();

        var buyer = userPort.find(s.getUserNo());
        return new PickupOrder(s.getSubOrderNo(), s.getVerifyCode(), s.getStatus(),
                s.getPickupNo(), s.getMerchantNo(), s.getMerchantName(),
                buyer.map(UserQueryPort.UserBrief::nickname).orElse("邻居"),
                buyer.map(UserQueryPort.UserBrief::phoneTail).orElse(""),
                s.getGroupNo(), items);
    }
}
