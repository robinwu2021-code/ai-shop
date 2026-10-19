package ai.neargo.shop.trade.order.refund.service;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.trade.order.entity.OrdRefundExecution;
import ai.neargo.shop.trade.order.mapper.RefundExecutionMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Short execution claims release their locks before calling another financial or stock authority. */
@Component
public class RefundExecutionStore {
    private final RefundExecutionMapper mapper;

    public RefundExecutionStore(RefundExecutionMapper mapper) {
        this.mapper = mapper;
    }

    public OrdRefundExecution read(String no) {
        return DataScopeContext.executeWithoutScope(() -> mapper.selectOne(Wrappers.<OrdRefundExecution>lambdaQuery()
                .eq(OrdRefundExecution::getExecutionNo, no)));
    }

    public OrdRefundExecution byAfterSale(String no) {
        return DataScopeContext.executeWithoutScope(() -> mapper.selectOne(Wrappers.<OrdRefundExecution>lambdaQuery()
                .eq(OrdRefundExecution::getAfterSaleNo, no)));
    }

    public OrdRefundExecution lock(String no) {
        return DataScopeContext.executeWithoutScope(() -> mapper.selectOne(Wrappers.<OrdRefundExecution>lambdaQuery()
                .eq(OrdRefundExecution::getExecutionNo, no).last("FOR UPDATE")));
    }

    public void insert(OrdRefundExecution row) {
        DataScopeContext.executeWithoutScope(() -> mapper.insert(row));
    }

    public void save(OrdRefundExecution row) {
        int count = DataScopeContext.executeWithoutScope(() -> mapper.updateById(row));
        if (count != 1) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OrdRefundExecution claim(String no) {
        OrdRefundExecution row = lock(no);
        if (row == null || OrdRefundExecution.SUCCEEDED.equals(row.getStatus())
                || OrdRefundExecution.FAILED.equals(row.getStatus()) && !pendingReversal(row)
                || row.getNextAttemptAt() > System.currentTimeMillis()) {
            return null;
        }
        row.setStatus(OrdRefundExecution.PROCESSING);
        row.setNextAttemptAt(System.currentTimeMillis() + 120_000L);
        save(row);
        return row;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void cashReady(String no) {
        OrdRefundExecution row = lock(no);
        if (row != null && !OrdRefundExecution.SUCCEEDED.equals(row.getStatus())) {
            row.setPhase(OrdRefundExecution.CASH);
            save(row);
        }
    }

    public List<OrdRefundExecution> due(long now, int limit) {
        return DataScopeContext.executeWithoutScope(() -> mapper.selectList(Wrappers.<OrdRefundExecution>lambdaQuery()
                .and(q -> q.ne(OrdRefundExecution::getStatus, OrdRefundExecution.FAILED)
                        .ne(OrdRefundExecution::getStatus, OrdRefundExecution.SUCCEEDED)
                        .or(r -> r.eq(OrdRefundExecution::getStatus, OrdRefundExecution.SUCCEEDED)
                                .ne(OrdRefundExecution::getResourceStatus, OrdRefundExecution.DONE))
                        .or(r -> r.eq(OrdRefundExecution::getStatus, OrdRefundExecution.FAILED)
                                .eq(OrdRefundExecution::getPhase, OrdRefundExecution.REVERSAL)
                                .isNull(OrdRefundExecution::getPaymentNo)
                                .eq(OrdRefundExecution::getErrorCode, "REVERSE_UNCONFIRMED")))
                .and(q -> q.isNull(OrdRefundExecution::getErrorCode)
                        .or().ne(OrdRefundExecution::getErrorCode, "REFUND_EXECUTION_CONFLICT"))
                .le(OrdRefundExecution::getNextAttemptAt, now).orderByAsc(OrdRefundExecution::getId)
                .last("LIMIT " + Math.max(1, Math.min(limit, 200)))));
    }

    private boolean pendingReversal(OrdRefundExecution row) {
        // Only repair the former classification error, never revive a definitively failed cash send.
        return OrdRefundExecution.REVERSAL.equals(row.getPhase()) && row.getPaymentNo()==null
                && "REVERSE_UNCONFIRMED".equals(row.getErrorCode());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resources(String no, String status, String error) {
        DataScopeContext.executeWithoutScope(() -> mapper.update(null, Wrappers.<OrdRefundExecution>lambdaUpdate()
                .eq(OrdRefundExecution::getExecutionNo, no).eq(OrdRefundExecution::getStatus, OrdRefundExecution.SUCCEEDED)
                .set(OrdRefundExecution::getResourceStatus, status).set(OrdRefundExecution::getResourceErrorCode, error)
                .set(OrdRefundExecution::getNextAttemptAt, System.currentTimeMillis() + 120_000L)
                .setSql("version = version + 1")));
    }
}
