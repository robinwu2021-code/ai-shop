package ai.neargo.shop.trade.order.refund.service.impl;

import ai.neargo.shop.event.AfterCommit;
import ai.neargo.shop.spi.settle.RefundDispatchResult;
import ai.neargo.shop.spi.settle.RefundDispatchResult.DispatchStatus;
import ai.neargo.shop.spi.settle.RefundRequest;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.trade.order.command.OrderActor;
import ai.neargo.shop.trade.order.entity.OrdRefundExecution;
import ai.neargo.shop.trade.order.refund.model.RefundPlan;
import ai.neargo.shop.trade.order.refund.service.BasicRefundExecutionService;
import ai.neargo.shop.trade.order.refund.service.RefundExecutionStore;
import ai.neargo.shop.trade.order.refund.service.RefundPreparationService;
import ai.neargo.shop.trade.order.refund.service.RefundResourceService;
import ai.neargo.shop.trade.order.refund.service.RefundResultService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** The durable plan is committed before sends; every restart follows the original execution. */
@Service
public class BasicRefundExecutionServiceImpl implements BasicRefundExecutionService {
    private static final Logger log = LoggerFactory.getLogger(BasicRefundExecutionServiceImpl.class);
    private final RefundPreparationService preparation;
    private final RefundExecutionStore store;
    private final RefundResultService results;
    private final RefundResourceService resources;
    private final SettlePort settle;
    private final ObjectMapper json;

    public BasicRefundExecutionServiceImpl(RefundPreparationService preparation, RefundExecutionStore store,
            RefundResultService results, RefundResourceService resources, SettlePort settle, ObjectMapper json) {
        this.preparation = preparation;
        this.store = store;
        this.results = results;
        this.resources = resources;
        this.settle = settle;
        this.json = json;
    }

    @Override
    public String prepare(String afterSaleNo, OrderActor actor) {
        String no = preparation.prepare(afterSaleNo, actor);
        AfterCommit.run("refund execution " + no, () -> dispatch(no));
        return no;
    }

    @Override
    public void dispatch(String no) {
        OrdRefundExecution row = store.claim(no);
        if (row == null) {
            return;
        }
        try {
            if (!OrdRefundExecution.CASH.equals(row.getPhase())) {
                RefundPlan plan = json.readValue(row.getPlanJson(), RefundPlan.class);
                boolean confirmed = settle.prepareRefundReversal(new SettlePort.SplitReversal(row.getSubOrderNo(),
                        row.getAfterSaleNo(), plan.goodsMinor(), plan.freightMinor(), plan.goodsMinor()),
                        Boolean.TRUE.equals(row.getZeroLiability()));
                if (!confirmed) {
                    results.apply(new RefundDispatchResult(no, null, DispatchStatus.FAILED,
                            "REVERSE_UNCONFIRMED", false, null));
                    return;
                }
                store.cashReady(no);
            }
            applyResult(settle.requestRefund(request(row)));
        } catch (RuntimeException failure) {
            results.apply(new RefundDispatchResult(no, row.getPaymentNo(), DispatchStatus.UNKNOWN,
                    "REFUND_EXECUTION_UNKNOWN", true, null));
            log.warn("refund {} awaits original execution recovery: {}", no, failure.getClass().getSimpleName());
        }
    }

    @Override
    public void applyResult(RefundDispatchResult result) {
        if (result == null) {
            throw new IllegalArgumentException("refund result required");
        }
        RefundDispatchResult verified = result.status() == DispatchStatus.SUCCEEDED
                ? settle.queryRefundExecution(request(store.read(result.executionNo()))) : result;
        results.apply(verified);
        if (verified.status() == DispatchStatus.SUCCEEDED) {
            resources.recover(verified.executionNo());
        }
    }

    @Override
    public void recover(String no) {
        OrdRefundExecution row = store.read(no);
        if (row == null) {
            return;
        }
        if (OrdRefundExecution.SUCCEEDED.equals(row.getStatus())) {
            resources.recover(no);
        } else if (OrdRefundExecution.CASH.equals(row.getPhase())) {
            RefundDispatchResult result = settle.queryRefundExecution(request(row));
            if ("REFUND_NOT_PREPARED".equals(result.errorCode())
                    || "REFUND_NOT_DISPATCHED".equals(result.errorCode())) {
                dispatch(no);
            } else {
                applyResult(result);
            }
        } else {
            dispatch(no);
        }
    }

    @Override
    public OrdRefundExecution find(String afterSaleNo) {
        return store.byAfterSale(afterSaleNo);
    }

    private RefundRequest request(OrdRefundExecution row) {
        if (row == null) {
            throw new IllegalStateException("REFUND_EXECUTION_MISSING");
        }
        return new RefundRequest(row.getExecutionNo(), row.getAfterSaleNo(), row.getSubOrderNo(),
                row.getOriginPaymentNo(), row.getAmountMinor(), row.getCurrency(), row.getReason());
    }

    @Override
    public int recoverDue(long now, int limit) {
        int attempted = 0;
        for (OrdRefundExecution row : store.due(now, limit)) {
            try {
                recover(row.getExecutionNo());
                attempted++;
            } catch (RuntimeException failure) {
                log.warn("refund recovery {} retained: {}", row.getExecutionNo(), failure.getClass().getSimpleName());
            }
        }
        return attempted;
    }
}
