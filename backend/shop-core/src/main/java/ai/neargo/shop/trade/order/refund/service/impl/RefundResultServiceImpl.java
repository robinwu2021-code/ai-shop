package ai.neargo.shop.trade.order.refund.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.spi.settle.RefundDispatchResult;
import ai.neargo.shop.spi.settle.RefundDispatchResult.DispatchStatus;
import ai.neargo.shop.spi.trade.OrderEvents;
import ai.neargo.shop.trade.entity.OrdAfterSale;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.aftersale.AfterSaleSlots;
import ai.neargo.shop.trade.order.entity.OrdRefundExecution;
import ai.neargo.shop.trade.order.machine.aftersale.AfterSaleContext;
import ai.neargo.shop.trade.order.machine.aftersale.AfterSaleEvent;
import ai.neargo.shop.trade.order.machine.order.OrderContext;
import ai.neargo.shop.trade.order.machine.order.OrderEvent;
import ai.neargo.shop.trade.order.mapper.AfterSaleMapper;
import ai.neargo.shop.trade.order.mapper.RefundLedgerMapper;
import ai.neargo.shop.trade.order.mapper.StatusCasMapper;
import ai.neargo.shop.trade.order.refund.model.RefundPlan;
import ai.neargo.shop.trade.order.refund.port.impl.MybatisRefundLedgerStore;
import ai.neargo.shop.trade.order.refund.service.RefundExecutionStore;
import ai.neargo.shop.trade.order.refund.service.RefundLedger;
import ai.neargo.shop.trade.order.refund.service.RefundResultService;
import ai.neargo.shop.trade.order.statemachine.Actor;
import ai.neargo.shop.trade.order.transition.TradeStatusWriter;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Child-first locking makes replay and concurrent shipment decisions observe current refund facts. */
@Service
public class RefundResultServiceImpl implements RefundResultService {
    private final RefundExecutionStore store;
    private final StatusCasMapper locks;
    private final AfterSaleMapper afterSales;
    private final TradeStatusWriter statuses;
    private final AfterSaleSlots slots;
    private final RefundLedger ledger;
    private final ObjectMapper json;
    private final OutboxEventBus events;

    public RefundResultServiceImpl(RefundExecutionStore store, StatusCasMapper locks, AfterSaleMapper afterSales,
            TradeStatusWriter statuses, AfterSaleSlots slots, RefundLedgerMapper ledger, ObjectMapper json,
            OutboxEventBus events) {
        this.store = store;
        this.locks = locks;
        this.afterSales = afterSales;
        this.statuses = statuses;
        this.slots = slots;
        this.ledger = new RefundLedger(new MybatisRefundLedgerStore(ledger));
        this.json = json;
        this.events = events;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public void apply(RefundDispatchResult result) {
        DataScopeContext.executeWithoutScope(() -> {
            OrdRefundExecution hint = store.read(result.executionNo());
            if (hint == null) {
                throw BizException.of(ErrorCode.NOT_FOUND);
            }
            OrdSubOrder child = locks.lockOrder(hint.getSubOrderNo());
            OrdRefundExecution current = store.lock(result.executionNo());
            if (OrdRefundExecution.SUCCEEDED.equals(current.getStatus())) {
                return null;
            }
            if (current.getPaymentNo() != null && result.paymentNo() != null
                    && !Objects.equals(current.getPaymentNo(), result.paymentNo())) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
            if (result.paymentNo() != null) {
                current.setPaymentNo(result.paymentNo());
            }
            current.setErrorCode(result.errorCode());
            current.setNextAttemptAt(System.currentTimeMillis() + 120_000L);
            if (result.status() == DispatchStatus.SUCCEEDED) {
                complete(current, child, result);
            } else {
                current.setStatus(result.status() == DispatchStatus.ACCEPTED
                        ? OrdRefundExecution.PROCESSING : result.status().name());
            }
            store.save(current);
            return null;
        });
    }

    private void complete(OrdRefundExecution current, OrdSubOrder child, RefundDispatchResult result) {
        if (result.paymentNo() == null || result.succeededAt() == null
                || !OrdRefundExecution.CASH.equals(current.getPhase())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        RefundPlan plan = json.readValue(current.getPlanJson(), RefundPlan.class);
        OrdAfterSale as = afterSales.selectOne(Wrappers.<OrdAfterSale>lambdaQuery()
                .eq(OrdAfterSale::getAfterSaleNo, current.getAfterSaleNo()).last("FOR UPDATE"));
        if (as == null || !OrdAfterSale.REFUNDING.equals(as.getStatus())
                || !Objects.equals(locks.openAfterSaleNo(child.getSubOrderNo()), as.getAfterSaleNo())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        ledger.record(plan);
        as.setRefundPaymentNo(result.paymentNo());
        as.setRefundedAt(result.succeededAt());
        as.setSplitReversed(true);
        statuses.afterSale(as, AfterSaleEvent.REFUND_SUCCEEDED, Actor.SYSTEM, null,
                "原路退款已确认到账", AfterSaleContext.builder().build());
        if (afterSales.updateById(as) != 1) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        slots.releaseIfDone(as);
        statuses.order(child, OrderEvent.FULLY_REFUNDED, Actor.SYSTEM, null,
                "子单现金已全额退回", OrderContext.authorizedOnly().withFullyRefunded(true));
        current.setStatus(OrdRefundExecution.SUCCEEDED);
        current.setSucceededAt(result.succeededAt());
        current.setResourceStatus(OrdRefundExecution.PENDING);
        current.setNextAttemptAt(0L);
        events.publish(new OrderEvents.AfterSaleRefunded(as.getAfterSaleNo(), as.getSubOrderNo(),
                as.getUserNo(), current.getAmountMinor()));
    }
}
