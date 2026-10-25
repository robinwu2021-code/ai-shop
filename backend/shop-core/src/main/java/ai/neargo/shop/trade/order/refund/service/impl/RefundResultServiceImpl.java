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
import ai.neargo.shop.trade.order.mapper.RefundExecutionMapper;
import ai.neargo.shop.trade.order.refund.service.BasicRefundPolicy;
import ai.neargo.shop.trade.order.refund.service.OriginalRefundEvidenceService;
import ai.neargo.shop.trade.order.refund.service.RefundObligationService;
import ai.neargo.shop.trade.order.machine.order.OrderStatus;
import ai.neargo.shop.trade.order.refund.model.RefundClosureFacts;
import ai.neargo.shop.trade.order.refund.policy.RefundClosurePolicy;
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
    private ai.neargo.shop.trade.order.printing.service.OrderPrintFactService printFacts;
    @org.springframework.beans.factory.annotation.Autowired
    public void setPrintFacts(ai.neargo.shop.trade.order.printing.service.OrderPrintFactService facts) {this.printFacts=facts;}
    private final RefundExecutionStore store;
    private final StatusCasMapper locks;
    private final AfterSaleMapper afterSales;
    private final TradeStatusWriter statuses;
    private final AfterSaleSlots slots;
    private final RefundLedger ledger;
    private final RefundExecutionMapper sources;
    private final ai.neargo.shop.trade.order.refund.service.RefundObligationService obligations;
    private final ObjectMapper json;
    private final OriginalRefundEvidenceService originalEvidence;
    private final OutboxEventBus events;

    public RefundResultServiceImpl(RefundExecutionStore store, StatusCasMapper locks, AfterSaleMapper afterSales,
            TradeStatusWriter statuses, AfterSaleSlots slots, RefundLedgerMapper ledger, ObjectMapper json,
            OutboxEventBus events, RefundExecutionMapper sources,
            RefundObligationService obligations, OriginalRefundEvidenceService originalEvidence) {
        this.obligations=obligations;
        this.originalEvidence = originalEvidence;
        this.sources = sources;
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
        var originalItems = sources.items(child.getSubOrderNo());
        // This lane still accepts only the original full refund. Never infer that from a cash receipt.
        BasicRefundPolicy.verify(child, originalItems, plan);
        if (!plan.originalPortions().isEmpty() || !plan.allocationReleaseTargets().isEmpty())
            originalEvidence.verify(child, originalItems, plan);
        if (!Objects.equals(current.getAmountMinor(), plan.totalMinor())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        var recorded = ledger.record(plan);
        long quantity = originalItems.stream().mapToLong(item -> item.getQty()).sum();
        // Legacy execution closes benefits independently; versioned obligations replace this adapter in T2.
        long terminated = originalItems.stream().mapToLong(item -> item.getRefundedQty() == null ? 0 : item.getRefundedQty()).sum()
                + plan.lines().stream().mapToLong(line -> line.qty()).sum();
        var decision = new RefundClosurePolicy().decide(new RefundClosureFacts(quantity, 0, terminated,
                child.getPayAmount(), recorded.subRefundedMinor(), true));
        current.setStatus(OrdRefundExecution.SUCCEEDED);
        current.setSucceededAt(result.succeededAt());
        current.setResourceStatus(OrdRefundExecution.PENDING);
        current.setNextAttemptAt(0L);
        if (Integer.valueOf(2).equals(current.getClosureVersion())) {
            store.save(current);
            obligations.prepare(current.getExecutionNo());
            return;
        }
        finish(current, child, as, decision.targetOrderStatus() == ai.neargo.shop.trade.order.machine.order.OrderStatus.REFUNDED);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public void closePending(String no) {
        DataScopeContext.executeWithoutScope(() -> {
            var hint=store.read(no);
            if(hint==null || !Integer.valueOf(2).equals(hint.getClosureVersion())) return null;
            var child=locks.lockOrder(hint.getSubOrderNo());
            var current=store.lock(no);
            if(!OrdRefundExecution.SUCCEEDED.equals(current.getStatus()) || "DONE".equals(current.getClosureStatus())
                    || !obligations.settled(no)) return null;
            var items=sources.items(child.getSubOrderNo());
            long ordered=items.stream().mapToLong(it -> it.getQty()).sum();
            long terminated=items.stream().mapToLong(it -> it.getRefundedQty()==null?0:it.getRefundedQty()).sum();
            var decision=new RefundClosurePolicy().decide(new RefundClosureFacts(ordered,0,terminated,
                    child.getPayAmount(),child.getRefundedMinor(),true));
            var as=afterSales.selectOne(Wrappers.<OrdAfterSale>lambdaQuery()
                    .eq(OrdAfterSale::getAfterSaleNo,current.getAfterSaleNo()).last("FOR UPDATE"));
            if(as==null || !OrdAfterSale.REFUNDING.equals(as.getStatus())
                    || !Objects.equals(locks.openAfterSaleNo(child.getSubOrderNo()),as.getAfterSaleNo()))
                throw BizException.of(ErrorCode.CONFLICT);
            finish(current,child,as,decision.targetOrderStatus()==OrderStatus.REFUNDED);
            current.setClosureStatus("DONE");
            store.save(current);
            return null;
        });
    }

    private void finish(OrdRefundExecution current,OrdSubOrder child,OrdAfterSale as,boolean fullyRefunded) {
        as.setRefundPaymentNo(current.getPaymentNo());
        as.setRefundedAt(current.getSucceededAt());
        as.setSplitReversed(true);
        statuses.afterSale(as, current.getAmountMinor()==0 ? AfterSaleEvent.BENEFITS_SETTLED : AfterSaleEvent.REFUND_SUCCEEDED,
                Actor.SYSTEM, null, current.getAmountMinor()==0 ? "零现金退款权益已结清" : "原路退款已确认到账",
                AfterSaleContext.builder().refundProof(new ai.neargo.shop.trade.order.machine.aftersale.RefundTerminalProof(
                        true,current.getAmountMinor(),current.getAmountMinor()>0,true,false)).build());
        if (afterSales.updateById(as) != 1) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        slots.releaseIfDone(as);
        if (fullyRefunded) {
            statuses.order(child, OrderEvent.FULLY_REFUNDED, Actor.SYSTEM, null,
                    "子单现金已全额退回", OrderContext.authorizedOnly().withFullyRefunded(true));
        }
        if (printFacts != null) printFacts.recordControl(child.getOrderNo(),child.getSubOrderNo(),
                "REFUND_CONFIRMED","REFUND:"+current.getExecutionNo(),java.time.Instant.ofEpochMilli(current.getSucceededAt()));
        events.publish(new OrderEvents.AfterSaleRefunded(as.getAfterSaleNo(), as.getSubOrderNo(),
                as.getUserNo(), current.getAmountMinor()));
    }
}
