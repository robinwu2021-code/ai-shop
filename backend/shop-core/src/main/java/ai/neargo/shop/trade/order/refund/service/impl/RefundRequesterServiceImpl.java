package ai.neargo.shop.trade.order.refund.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.trade.entity.OrdAfterSale;
import ai.neargo.shop.trade.entity.OrdStatusLog;
import ai.neargo.shop.trade.order.mapper.AfterSaleMapper;
import ai.neargo.shop.trade.order.mapper.OrderStatusLogMapper;
import ai.neargo.shop.trade.order.refund.service.RefundRequesterService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** Append-only source decisions survive process restarts without inventing a historical applicant. */
@Service
public class RefundRequesterServiceImpl implements RefundRequesterService {
    private final AfterSaleMapper cases;
    private final OrderStatusLogMapper logs;

    public RefundRequesterServiceImpl(AfterSaleMapper cases, OrderStatusLogMapper logs) {
        this.cases = cases;
        this.logs = logs;
    }

    @Override
    public String requestedBy(String afterSaleNo) {
        return DataScopeContext.executeWithoutScope(() -> {
            OrdAfterSale source = cases.selectOne(Wrappers.<OrdAfterSale>lambdaQuery()
                    .eq(OrdAfterSale::getAfterSaleNo, afterSaleNo));
            if (source == null) return null;
            OrdStatusLog first = logs.selectOne(Wrappers.<OrdStatusLog>lambdaQuery()
                    .eq(OrdStatusLog::getAggregateType, "AFTER_SALE")
                    .eq(OrdStatusLog::getAggregateNo, afterSaleNo)
                    .eq(OrdStatusLog::getTenantNo, source.getTenantNo())
                    .eq(OrdStatusLog::getSubOrderNo, source.getSubOrderNo())
                    .eq(OrdStatusLog::getStatus, OrdAfterSale.REFUNDING)
                    .orderByAsc(OrdStatusLog::getId).last("LIMIT 1"));
            if (first == null) return null;
            if (OrdStatusLog.BY_SYSTEM.equals(first.getOperatorType())) return "SYSTEM:" + afterSaleNo;
            String person = first.getOperatorNo();
            if (person == null || person.isBlank()) return null;
            return switch (first.getOperatorType() == null ? "" : first.getOperatorType()) {
                case OrdStatusLog.BY_PLATFORM -> person;
                case OrdStatusLog.BY_USER -> Objects.equals(person, source.getUserNo()) ? person : null;
                case OrdStatusLog.BY_MERCHANT -> Objects.equals(person, source.getEntityNo()) ? null : person;
                default -> null;
            };
        });
    }
}
