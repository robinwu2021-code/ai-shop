package ai.neargo.shop.trade.port;

import ai.neargo.shop.spi.trade.OpenOrderPort;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@link OpenOrderPort} 的实现。
 *
 * <p><b>判据是「终态之外的一切」，不是列举进行中的状态。</b>
 * 反过来写（`in(WAIT_PAY, PAID, FULFILLING)`）的话，将来加一个新状态就会被漏掉，
 * 而漏掉的表现是「有在途单的人也能注销」—— 那正是这道闸要防的事，
 * 且不报错、要等他来找货才发现。
 */
@Component
public class OpenOrderPortImpl implements OpenOrderPort {

    /** 终态：钱与货都已了结，注销不影响任何人 */
    private static final List<String> SETTLED =
            List.of(OrdSubOrder.COMPLETED, OrdSubOrder.CANCELLED, OrdSubOrder.REFUNDED);

    private final SubOrderMapper subOrderMapper;

    public OpenOrderPortImpl(SubOrderMapper subOrderMapper) {
        this.subOrderMapper = subOrderMapper;
    }

    @Override
    public boolean hasOpenOrders(String userNo) {
        if (userNo == null || userNo.isBlank()) {
            return false;
        }
        Long n = subOrderMapper.selectCount(Wrappers.<OrdSubOrder>lambdaQuery()
                .eq(OrdSubOrder::getUserNo, userNo)
                .notIn(OrdSubOrder::getStatus, SETTLED));
        return n != null && n > 0;
    }
}
