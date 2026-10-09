package ai.neargo.shop.trade.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.trade.SubOrderBuyerPort;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class SubOrderBuyerPortImpl implements SubOrderBuyerPort {

    private final SubOrderMapper subOrderMapper;

    public SubOrderBuyerPortImpl(SubOrderMapper subOrderMapper) {
        this.subOrderMapper = subOrderMapper;
    }

    /**
     * ⚠️ 绕过数据域：调用方是 outbox 消费者，没有会话 —— fail-closed 会把查询拼成 {@code 1=0}，
     * 通知静默发不出去。只读收件人，不做任何鉴权判定（同 {@link OrderSceneQueryPortImpl}）。
     */
    @Override
    public Optional<Buyer> buyerOf(String subOrderNo) {
        OrdSubOrder sub = DataScopeContext.executeWithoutScope(() ->
                subOrderMapper.selectOne(Wrappers.<OrdSubOrder>lambdaQuery()
                        .select(OrdSubOrder::getUserNo, OrdSubOrder::getOrderNo)
                        .eq(OrdSubOrder::getSubOrderNo, subOrderNo).last("LIMIT 1")));
        return sub == null ? Optional.empty() : Optional.of(new Buyer(sub.getUserNo(), sub.getOrderNo()));
    }
}
