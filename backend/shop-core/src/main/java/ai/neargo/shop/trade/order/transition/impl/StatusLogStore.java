package ai.neargo.shop.trade.order.transition.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.trade.entity.OrdStatusLog;
import ai.neargo.shop.trade.mapper.TradeMappers.StatusLogMapper;
import ai.neargo.shop.trade.order.statemachine.Actor;
import ai.neargo.shop.trade.order.transition.TransitionLog;

import java.time.LocalDateTime;

/** {@link TransitionLog} 写 ord_status_log（V402 扩列后的形状）。 */
public class StatusLogStore implements TransitionLog {

    private static final String TENANT = "MAIN";
    private final StatusLogMapper mapper;

    public StatusLogStore(StatusLogMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void append(Entry e) {
        OrdStatusLog row = new OrdStatusLog();
        row.setSubOrderNo(e.anchorNo() == null ? e.no() : e.anchorNo());
        row.setAggregateType(e.aggregate().name());
        row.setAggregateNo(e.no());
        row.setEvent(e.event());
        row.setFromStatus(e.from());
        row.setStatus(e.to());
        row.setLabel(e.reason());
        row.setOperatorType(operatorType(e.actor()));
        row.setOperatorNo(e.operatorNo());
        row.setAt(System.currentTimeMillis());
        row.setTenantNo(TENANT);
        row.setCreatedAt(LocalDateTime.now());
        DataScopeContext.executeWithoutScope(() -> mapper.insert(row));
    }

    /** 沿用老流水的执行者取值：买家记 USER，通道回调记 SYSTEM（老数据没有 CHANNEL） */
    static String operatorType(Actor actor) {
        return switch (actor) {
            case BUYER -> OrdStatusLog.BY_USER;
            case MERCHANT -> OrdStatusLog.BY_MERCHANT;
            case PLATFORM -> OrdStatusLog.BY_PLATFORM;
            case SYSTEM, CHANNEL -> OrdStatusLog.BY_SYSTEM;
        };
    }
}
