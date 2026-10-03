package ai.neargo.shop.trade.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.trade.OrderSceneQueryPort;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

@Component
public class OrderSceneQueryPortImpl implements OrderSceneQueryPort {

    private final SubOrderMapper subOrderMapper;
    private final OrderMapper orderMapper;

    public OrderSceneQueryPortImpl(SubOrderMapper subOrderMapper, OrderMapper orderMapper) {
        this.subOrderMapper = subOrderMapper;
        this.orderMapper = orderMapper;
    }

    /**
     * ⚠️ 两处都绕过数据域。发分的调用链既可能来自 B 端会话（商家确认线下收款），
     * 也可能来自<b>定时任务</b>（超时自动确认收货）—— 前者的会话维度对不上订单，
     * 后者根本没有会话。两种情况下 fail-closed 都会把查询拼成 {@code 1=0}，
     * 症状是「端判定读不到端」，而那会被当成「认不出端」放行 ——
     * 于是开关看着开着却谁也拦不住。
     *
     * <p>这里只读一列用于平台策略，不做任何鉴权判定。
     */
    @Override
    public String payChannelOfSubOrder(String subOrderNo) {
        OrdOrder order = orderOfSubOrder(subOrderNo);
        return order == null ? null : order.getPayChannel();
    }

    @Override
    public String paySceneOfSubOrder(String subOrderNo) {
        OrdOrder order = orderOfSubOrder(subOrderNo);
        return order == null ? null : order.getPayScene();
    }

    private OrdOrder orderOfSubOrder(String subOrderNo) {
        OrdSubOrder sub = DataScopeContext.executeWithoutScope(() ->
                subOrderMapper.selectOne(Wrappers.<OrdSubOrder>lambdaQuery()
                        .eq(OrdSubOrder::getSubOrderNo, subOrderNo).last("LIMIT 1")));
        if (sub == null) {
            return null;
        }
        return DataScopeContext.executeWithoutScope(() ->
                orderMapper.selectOne(Wrappers.<OrdOrder>lambdaQuery()
                        .eq(OrdOrder::getOrderNo, sub.getOrderNo()).last("LIMIT 1")));
    }
}
