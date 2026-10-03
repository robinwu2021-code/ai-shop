package ai.neargo.shop.trade.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.trade.PeriodOrderPort;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderItemMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper;
import ai.neargo.shop.trade.service.AfterSaleService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** {@link PeriodOrderPort} 的实现。见接口注释。 */
@Component
public class PeriodOrderPortImpl implements PeriodOrderPort {

    private static final Logger log = LoggerFactory.getLogger(PeriodOrderPortImpl.class);

    private final SubOrderMapper subOrderMapper;
    private final OrderItemMapper itemMapper;
    private final AfterSaleService afterSaleService;

    public PeriodOrderPortImpl(SubOrderMapper subOrderMapper, OrderItemMapper itemMapper,
                               AfterSaleService afterSaleService) {
        this.subOrderMapper = subOrderMapper;
        this.itemMapper = itemMapper;
        this.afterSaleService = afterSaleService;
    }

    @Override
    public List<PeriodLine> lines(Collection<String> periodNos) {
        if (periodNos == null || periodNos.isEmpty()) {
            return List.of();
        }
        /*
         * ★ 绕数据域：调用方是商家会话（B 端看期）或定时任务（无会话）。
         * 子单登记了 SELF / MERCHANT 维度，不绕的话任务里查出来恒为空 ——
         * 表现是「每一期都没达到起订量」，然后被自动取消、全额退款。
         * 边界靠 period_no 等值条件：期号本身已经钉死了商家（调用方先校验过期的归属）。
         */
        List<OrdSubOrder> subs = DataScopeContext.executeWithoutScope(() ->
                subOrderMapper.selectList(Wrappers.<OrdSubOrder>lambdaQuery()
                        .in(OrdSubOrder::getPeriodNo, periodNos)));
        if (subs.isEmpty()) {
            return List.of();
        }
        Map<String, OrdSubOrder> byNo = subs.stream()
                .collect(Collectors.toMap(OrdSubOrder::getSubOrderNo, s -> s, (a, b) -> a));
        List<OrdItem> items = DataScopeContext.executeWithoutScope(() ->
                itemMapper.selectList(Wrappers.<OrdItem>lambdaQuery()
                        .in(OrdItem::getSubOrderNo, byNo.keySet())
                        .orderByAsc(OrdItem::getId)));
        List<PeriodLine> out = new ArrayList<>(items.size());
        for (OrdItem i : items) {
            if (Boolean.TRUE.equals(i.getIsGift())) {
                continue;   // 赠品不算份数：它不是买家订的，也不该让起订量「看着够了」
            }
            OrdSubOrder s = byNo.get(i.getSubOrderNo());
            out.add(new PeriodLine(s.getPeriodNo(), s.getSubOrderNo(), s.getUserNo(), s.getStatus(),
                    s.getPickupNo(), s.getPickupName(),
                    i.getGoodsNo(), i.getSkuNo(), i.getTitle(), i.getSpec(),
                    i.getQty() == null ? 0 : i.getQty(),
                    i.getAmount() == null ? 0L : i.getAmount()));
        }
        return List.copyOf(out);
    }

    @Override
    public int refundAll(String periodNo, String reason) {
        List<OrdSubOrder> subs = DataScopeContext.executeWithoutScope(() ->
                subOrderMapper.selectList(Wrappers.<OrdSubOrder>lambdaQuery()
                        .eq(OrdSubOrder::getPeriodNo, periodNo)
                        .notIn(OrdSubOrder::getStatus,
                                List.of(OrdSubOrder.WAIT_PAY, OrdSubOrder.CANCELLED, OrdSubOrder.REFUNDED))));
        int started = 0;
        for (OrdSubOrder s : subs) {
            /*
             * 逐张独立：afterSaleService 是代理，每一张各自一个事务。
             * 一张的分账回退失败会停在 REFUNDING（RefundRetryJob 接着重试），
             * 不能让它把同一期里其余买家的退款一起回滚掉。
             */
            try {
                afterSaleService.systemRefund(s.getSubOrderNo(), reason, reason);
                started++;
            } catch (RuntimeException e) {
                log.warn("[集单] 退款未完成 period={} sub={}：{}（已留在售后单上等重试）",
                        periodNo, s.getSubOrderNo(), e.getMessage());
            }
        }
        return started;
    }
}
