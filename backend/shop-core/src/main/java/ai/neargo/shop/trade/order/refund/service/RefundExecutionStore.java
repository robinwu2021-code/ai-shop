package ai.neargo.shop.trade.order.refund.service;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.trade.order.entity.OrdRefundExecution;
import ai.neargo.shop.trade.order.mapper.RefundExecutionMapper;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

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

    /**
     * Commit a durable conflict before the caller rejects it, without retaining Core locks at IO.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public OrdRefundExecution currentSource(String no) {
        return lockSource(no);
    }

    public static OrdRefundExecution requireFunded(OrdRefundExecution row) {
        if (row != null && "REFUND_EXECUTION_CONFLICT".equals(row.getErrorCode())) {
            throw BizException.of(ErrorCode.CHECKOUT_PAYMENT_CONFLICT);
        }
        return row;
    }

    private OrdRefundExecution lockSource(String no) {
        var hint = read(no);
        if (hint == null) {
            return null;
        }
        var child =
                DataScopeContext.executeWithoutScope(() -> mapper.lockSub(hint.getSubOrderNo()));
        var row = lock(no);
        if (row == null) {
            return null;
        }
        boolean valid =
                child != null
                        && "LEGACY_FUNDED".equals(child.getAdmissionKind())
                        && Objects.equals(row.getSubOrderNo(), child.getSubOrderNo())
                        && Objects.equals(row.getOrderNo(), child.getOrderNo())
                        && Objects.equals(row.getTenantNo(), child.getTenantNo())
                        && Objects.equals(row.getSubOrderNo(), hint.getSubOrderNo())
                        && Objects.equals(row.getOrderNo(), hint.getOrderNo())
                        && Objects.equals(row.getAfterSaleNo(), hint.getAfterSaleNo())
                        && DataScopeContext.executeWithoutScope(
                                        () ->
                                                mapper.fundedSource(
                                                        row.getOrderNo(),
                                                        row.getSubOrderNo(),
                                                        row.getAfterSaleNo(),
                                                        row.getTenantNo(),
                                                        child.getEntityNo()))
                                == 1;
        if (!valid) {
            row.setErrorCode("REFUND_EXECUTION_CONFLICT");
            row.setResourceErrorCode("SOURCE_MODE_CONFLICT");
            row.setNextAttemptAt(System.currentTimeMillis() + 120_000L);
            save(row);
        }
        return row;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public OrdRefundExecution claim(String no) {
        OrdRefundExecution row = lockSource(no);
        if (row != null && "REFUND_EXECUTION_CONFLICT".equals(row.getErrorCode())) {
            return row;
        }
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

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public void cashReady(String no) {
        OrdRefundExecution row = lockSource(no);
        if (row != null
                && !"REFUND_EXECUTION_CONFLICT".equals(row.getErrorCode())
                && !OrdRefundExecution.SUCCEEDED.equals(row.getStatus())) {
            row.setPhase(OrdRefundExecution.CASH);
            save(row);
        }
    }

    public List<OrdRefundExecution> due(long now, int limit) {
        return DataScopeContext.executeWithoutScope(() -> mapper.selectList(Wrappers.<OrdRefundExecution>lambdaQuery()
                .and(q -> q.ne(OrdRefundExecution::getStatus, OrdRefundExecution.FAILED)
                        .ne(OrdRefundExecution::getStatus, OrdRefundExecution.SUCCEEDED)
                        .or(r -> r.eq(OrdRefundExecution::getStatus, OrdRefundExecution.SUCCEEDED)
                                .and(p -> p.ne(OrdRefundExecution::getResourceStatus, OrdRefundExecution.DONE)
                                        .or().eq(OrdRefundExecution::getClosureStatus,"PENDING")))
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

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public void resources(String no, String status, String error) {
        var row = lockSource(no);
        if (row == null || "REFUND_EXECUTION_CONFLICT".equals(row.getErrorCode())) {
            return;
        }
        DataScopeContext.executeWithoutScope(() -> mapper.update(null, Wrappers.<OrdRefundExecution>lambdaUpdate()
                .eq(OrdRefundExecution::getExecutionNo, no).eq(OrdRefundExecution::getStatus, OrdRefundExecution.SUCCEEDED)
                .set(OrdRefundExecution::getResourceStatus, status).set(OrdRefundExecution::getResourceErrorCode, error)
                .set(OrdRefundExecution::getNextAttemptAt, System.currentTimeMillis() + 120_000L)
                .setSql("version = version + 1")));
    }
}
