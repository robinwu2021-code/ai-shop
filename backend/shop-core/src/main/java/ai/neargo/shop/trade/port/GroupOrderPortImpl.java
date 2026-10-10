package ai.neargo.shop.trade.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.trade.GroupOrderPort;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper;
import ai.neargo.shop.trade.service.AfterSaleService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/** {@link GroupOrderPort} 的实现。见接口注释。 */
@Component
public class GroupOrderPortImpl implements GroupOrderPort {

    private static final Logger log = LoggerFactory.getLogger(GroupOrderPortImpl.class);

    private final SubOrderMapper subOrderMapper;
    private final AfterSaleService afterSaleService;

    public GroupOrderPortImpl(SubOrderMapper subOrderMapper, AfterSaleService afterSaleService) {
        this.subOrderMapper = subOrderMapper;
        this.afterSaleService = afterSaleService;
    }

    @Override
    public int refundAll(String groupNo, String reason) {
        if (groupNo == null || groupNo.isBlank()) {
            return 0;
        }
        /*
         * ★ 绕数据域：调用方是定时任务（无会话）、商家散团或运营中止 ——
         * 参团的子单属于各个买家，任何一种会话下直查都拿不全。
         * 边界靠 group_no 等值条件：团号已经钉死了是哪一个团（调用方先校验过归属）。
         */
        List<OrdSubOrder> subs = DataScopeContext.executeWithoutScope(() ->
                subOrderMapper.selectList(Wrappers.<OrdSubOrder>lambdaQuery()
                        .eq(OrdSubOrder::getGroupNo, groupNo)
                        .notIn(OrdSubOrder::getStatus,
                                List.of(OrdSubOrder.WAIT_PAY, OrdSubOrder.CANCELLED, OrdSubOrder.REFUNDED))));
        int started = 0;
        for (OrdSubOrder s : subs) {
            // 逐张独立：一张的分账回退失败停在 REFUNDING 等重试，不连累同团其余买家
            try {
                if (afterSaleService.systemRefund(s.getSubOrderNo(), reason, reason).isPresent()) {
                    started++;
                }
            } catch (RuntimeException e) {
                log.warn("[拼团] 退款未完成 group={} sub={}：{}（已留在售后单上等重试）",
                        groupNo, s.getSubOrderNo(), e.getMessage());
            }
        }
        return started;
    }
}
