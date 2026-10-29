package ai.neargo.shop.trade.order.refund.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.event.AfterCommit;
import ai.neargo.shop.spi.settle.RefundDispatchResult.DispatchStatus;
import ai.neargo.shop.spi.settle.RefundDispatchResult;
import ai.neargo.shop.spi.settle.RefundRequest;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.command.OrderActor;
import ai.neargo.shop.trade.order.entity.OrdRefundExecution;
import ai.neargo.shop.trade.order.mapper.StoreOrderMapper;
import ai.neargo.shop.trade.order.refund.model.RefundPlan;
import ai.neargo.shop.trade.order.refund.policy.LineShares;
import ai.neargo.shop.trade.order.refund.service.BasicRefundExecutionService;
import ai.neargo.shop.trade.order.refund.service.RefundExecutionStore;
import ai.neargo.shop.trade.order.refund.service.RefundFinancialOwnerService;
import ai.neargo.shop.trade.order.refund.service.RefundPreparationService;
import ai.neargo.shop.trade.order.refund.service.RefundResourceService;
import ai.neargo.shop.trade.order.refund.service.RefundResultService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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
    private final RefundFinancialOwnerService financialOwner;
    private final ObjectMapper json;
    private final ai.neargo.shop.trade.order.refund.service.RefundObligationService obligations;
    private final StoreOrderMapper children;

    public BasicRefundExecutionServiceImpl(RefundPreparationService preparation, RefundExecutionStore store,
            RefundResultService results, RefundResourceService resources, SettlePort settle, ObjectMapper json,
            RefundFinancialOwnerService financialOwner,
            ai.neargo.shop.trade.order.refund.service.RefundObligationService obligations,
            StoreOrderMapper children) {
        this.obligations=obligations;
        this.children = children;
        this.preparation = preparation;
        this.store = store;
        this.results = results;
        this.resources = resources;
        this.settle = settle;
        this.financialOwner = financialOwner;
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
        OrdRefundExecution row = RefundExecutionStore.requireFunded(store.claim(no));
        if (row == null) {
            return;
        }
        try {
            if (!OrdRefundExecution.CASH.equals(row.getPhase())) {
                RefundPlan plan = json.readValue(row.getPlanJson(), RefundPlan.class);
                boolean confirmed = settle.prepareRefundReversal(new SettlePort.SplitReversal(row.getSubOrderNo(),
                        row.getAfterSaleNo(), plan.goodsMinor(), plan.freightMinor(), goodsPaid(row.getSubOrderNo())),
                        Boolean.TRUE.equals(row.getZeroLiability()));
                if (!confirmed) {
                    results.apply(new RefundDispatchResult(no, null, DispatchStatus.ACCEPTED,
                            "REVERSE_UNCONFIRMED", true, null));
                    return;
                }
                store.cashReady(no);
            }
            applyResult(financialOwner.request(source(no)));
        } catch (RuntimeException failure) {
            source(no);
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
        var original = source(result.executionNo());
        RefundDispatchResult verified = result.status() == DispatchStatus.SUCCEEDED
                ? financialOwner.query(original) : result;
        results.apply(verified);
        if (verified.status() == DispatchStatus.SUCCEEDED) {
            recoverSuccessful(verified.executionNo());
        }
    }

    @Override
    public void recover(String no) {
        OrdRefundExecution row = source(no);
        if (row == null) {
            return;
        }
        if (OrdRefundExecution.SUCCEEDED.equals(row.getStatus())) {
            recoverSuccessful(no);
        } else if (OrdRefundExecution.CASH.equals(row.getPhase())) {
            RefundDispatchResult result = financialOwner.query(row);
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

    /**
     * 回退比例的分母：子单商品实付（与积分路径同一口径 {@link LineShares#goodsPaid}）。
     *
     * <p>不能用本次方案的货款 —— 那样每笔部分退的比例都是 100%：退 1/3 件就冲回商家整单所得、
     * 结算单提前转 REVERSED，同单下一笔部分退再也回退不成。
     * 子单读不到时不猜分母：抛出去由 dispatch 记成 UNKNOWN，留给原执行单续跑。
     */
    private long goodsPaid(String subOrderNo) {
        OrdSubOrder sub = DataScopeContext.executeWithoutScope(() -> children.selectOne(
                Wrappers.<OrdSubOrder>lambdaQuery().eq(OrdSubOrder::getSubOrderNo, subOrderNo).last("LIMIT 1")));
        if (sub == null) {
            throw new IllegalStateException("REFUND_EXECUTION_SOURCE_UNKNOWN");
        }
        return LineShares.goodsPaid(sub);
    }

    private void recoverSuccessful(String no) {
        var row = source(no);
        if(row!=null && Integer.valueOf(2).equals(row.getClosureVersion())) {
            obligations.recover(no);
            results.closePending(no);
        }
        resources.recover(no);
    }

    @Override
    public OrdRefundExecution find(String afterSaleNo) {
        return store.byAfterSale(afterSaleNo);
    }

    private OrdRefundExecution source(String no) {
        return RefundExecutionStore.requireFunded(store.currentSource(no));
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
