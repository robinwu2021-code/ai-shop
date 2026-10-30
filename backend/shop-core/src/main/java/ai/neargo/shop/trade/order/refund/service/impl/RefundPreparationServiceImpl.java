package ai.neargo.shop.trade.order.refund.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.product.StockPort;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.trade.entity.OrdAfterSale;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.command.OrderActor;
import ai.neargo.shop.trade.order.entity.OrdCreateIntent;
import ai.neargo.shop.trade.order.entity.OrdRefundExecution;
import ai.neargo.shop.trade.order.mapper.AfterSaleMapper;
import ai.neargo.shop.trade.order.mapper.CreateIntentMapper;
import ai.neargo.shop.trade.order.mapper.PaymentMapper;
import ai.neargo.shop.trade.order.mapper.StatusCasMapper;
import ai.neargo.shop.trade.order.mapper.StoreOrderMapper;
import ai.neargo.shop.trade.order.refund.model.RefundPlan;
import ai.neargo.shop.trade.order.refund.port.AfterSaleLineStore;
import ai.neargo.shop.trade.order.refund.service.BasicRefundPolicy;
import ai.neargo.shop.trade.order.refund.service.OriginalRefundEvidenceService;
import ai.neargo.shop.trade.order.refund.service.RefundExecutionStore;
import ai.neargo.shop.trade.order.refund.service.RefundFinancialOwnerService;
import ai.neargo.shop.trade.order.refund.service.RefundPreparationService;
import ai.neargo.shop.trade.order.refund.service.RefundableQuery;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Current child facts are read after serialization, including the original successful financial receipt. */
@Service
public class RefundPreparationServiceImpl implements RefundPreparationService {
    private final AfterSaleMapper afterSales;
    private final StatusCasMapper locks;
    private final PaymentMapper payments;
    private final AfterSaleLineStore afterSaleLines;
    private final RefundFinancialOwnerService financialOwner;
    private final OriginalRefundEvidenceService originalEvidence;
    private final CreateIntentMapper intents;
    private final RefundableQuery query;
    private final RefundExecutionStore store;
    private final SettlePort settle;
    private final ObjectMapper json;
    private final ai.neargo.shop.trade.order.refund.service.RefundObligationService obligations;
    private final ai.neargo.shop.spi.platform.PlatformSwitchPort flags;
    private final boolean closureEnabled;
    private final StoreOrderMapper children;
    private final TransactionTemplate transactions;

    public RefundPreparationServiceImpl(AfterSaleMapper afterSales, StatusCasMapper locks, PaymentMapper payments,
            CreateIntentMapper intents, RefundableQuery query, RefundExecutionStore store, AfterSaleLineStore afterSaleLines, RefundFinancialOwnerService financialOwner, OriginalRefundEvidenceService originalEvidence,
            SettlePort settle, ObjectMapper json, StoreOrderMapper children,
            @Qualifier("transactionManager") PlatformTransactionManager manager,
            ai.neargo.shop.trade.order.refund.service.RefundObligationService obligations,
            ai.neargo.shop.spi.platform.PlatformSwitchPort flags,
            @org.springframework.beans.factory.annotation.Value("${shop.refund.closure.enabled:false}") boolean closureEnabled) {
        this.obligations=obligations; this.flags=flags; this.closureEnabled=closureEnabled;
        this.afterSales = afterSales;
        this.locks = locks;
        this.payments = payments;
        this.intents = intents;
        this.afterSaleLines = afterSaleLines;
        this.financialOwner = financialOwner;
        this.originalEvidence = originalEvidence;
        this.query = query;
        this.store = store;
        this.settle = settle;
        this.json = json;
        this.children=children;
        this.transactions=new TransactionTemplate(manager);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public String prepare(String no, OrderActor actor) {
        return DataScopeContext.executeWithoutScope(() -> {
            OrdAfterSale as=afterSale(no);
            OrdSubOrder child=children.selectOne(Wrappers.<OrdSubOrder>lambdaQuery()
                    .eq(OrdSubOrder::getSubOrderNo,as.getSubOrderNo()));
            if(child==null || actor==null || !actor.owns(child)) throw BizException.of(ErrorCode.NOT_FOUND);
            OrdRefundExecution existing=store.byAfterSale(no);
            if(existing!=null) return existing.getExecutionNo();
            if(!OrdAfterSale.REFUNDING.equals(as.getStatus())
                    || !Objects.equals(locks.openAfterSaleNo(child.getSubOrderNo()),no))
                throw BizException.of(ErrorCode.ORDER_STATE_ILLEGAL);
            OrdOrder parent=payments.selectOne(Wrappers.<OrdOrder>lambdaQuery().eq(OrdOrder::getOrderNo,child.getOrderNo()));
            String fingerprint=financialOwner.originFingerprint(as,child,parent);
            Prefetched proof=null;
            if(fingerprint!=null) {
                if(TransactionSynchronizationManager.isActualTransactionActive())
                    throw new IllegalStateException("SOURCE_REFUND_PREPARATION_REQUIRES_NO_TRANSACTION");
                proof=new Prefetched(fingerprint,financialOwner.origin(as,child,parent));
            }
            Prefetched captured=proof;
            return transactions.execute(tx -> prepareCurrent(no,actor,captured));
        });
    }
    private record Prefetched(String fingerprint,SettlePort.PaymentReceipt receipt) { }

    private String prepareCurrent(String no, OrderActor actor, Prefetched proof) {
        OrdAfterSale hint = afterSale(no);
        OrdSubOrder child = locks.lockOrder(hint.getSubOrderNo());
        if (child == null || actor == null || !actor.owns(child)) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if ("MANUAL_OFFLINE".equals(child.getAdmissionKind())) {
            throw BizException.of(ErrorCode.CHECKOUT_PAYMENT_CONFLICT);
        }
        OrdAfterSale as = afterSale(no);
        OrdRefundExecution previous = store.byAfterSale(no);
        if (previous != null) {
            return previous.getExecutionNo();
        }
        if (!OrdAfterSale.REFUNDING.equals(as.getStatus())
                || !Objects.equals(locks.openAfterSaleNo(child.getSubOrderNo()), no)) {
            throw BizException.of(ErrorCode.ORDER_STATE_ILLEGAL);
        }
        List<OrdItem> items = query.lockItems(child);
        var saved = afterSaleLines.lines(as.getAfterSaleNo());
        RefundPlan plan = BasicRefundPolicy.select(child, items, saved.isEmpty() ? null : saved, null);
        if (!saved.isEmpty() && !plan.lines().equals(saved)) throw BizException.of(ErrorCode.AFTERSALE_AMOUNT_INVALID);
        long approved = as.getApprovedMinor() == null ? as.getRefundMinor() : as.getApprovedMinor();
        if (approved != plan.totalMinor() || OrdAfterSale.EXCHANGE.equals(as.getType())
                || (OrdAfterSale.RETURN_REFUND.equals(as.getType()) && as.getReturnReceivedAt() == null)) {
            throw BizException.of(ErrorCode.AFTERSALE_AMOUNT_INVALID);
        }
        OrdOrder parent = payments.selectOne(Wrappers.<OrdOrder>lambdaQuery()
                .eq(OrdOrder::getOrderNo, child.getOrderNo()));
        plan = originalEvidence.freeze(child, items, plan);
        OrdRefundExecution row = frozen(as, child, items, plan);
        String currentFingerprint=financialOwner.originFingerprint(as,child,parent);
        if(!Objects.equals(currentFingerprint,proof==null?null:proof.fingerprint()))
            throw BizException.of(ErrorCode.CONFLICT);
        SettlePort.PaymentReceipt source = parent == null ? null
                : proof == null ? financialOwner.origin(as,child,parent) : proof.receipt();
        if (parent != null && parent.getCurrency() != null) {
            row.setCurrency(parent.getCurrency());
        }
        freezeSource(row, parent, source);
        store.insert(row);
        return row.getExecutionNo();
    }

    private OrdAfterSale afterSale(String no) {
        OrdAfterSale row = afterSales.selectOne(Wrappers.<OrdAfterSale>lambdaQuery()
                .eq(OrdAfterSale::getAfterSaleNo, no));
        if (row == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return row;
    }

    private OrdRefundExecution frozen(OrdAfterSale as, OrdSubOrder child, List<OrdItem> items, RefundPlan plan) {
        OrdRefundExecution row = new OrdRefundExecution();
        row.setExecutionNo("RF-" + as.getAfterSaleNo());
        boolean versioned=closureEnabled && obligations.hasScope(child.getOrderNo());
        row.setClosureVersion(versioned?2:1);
        row.setClosureStatus(versioned?"PENDING":"LEGACY");
        row.setReturnCoupon(versioned && flags.bool("refund.return-coupon",true));
        row.setAfterSaleNo(as.getAfterSaleNo());
        row.setSubOrderNo(child.getSubOrderNo());
        row.setOrderNo(child.getOrderNo());
        row.setAmountMinor(plan.totalMinor());
        row.setZeroLiability(BasicRefundPolicy.zeroLiability(child));
        row.setCurrency("CNY");
        row.setPlanJson(json.writeValueAsString(plan));
        row.setStockNeedsJson(json.writeValueAsString(plan.lines().stream().map(line -> {
            var item = items.stream().filter(i -> i.getId() == line.itemId()).findFirst().orElseThrow();
            return new StockPort.SkuQty(item.getSkuNo(), line.qty(),
                    item.getSupplyStoreNo() == null ? child.getStoreNo() : item.getSupplyStoreNo());
        }).toList()));
        OrdCreateIntent intent = intents.selectOne(Wrappers.<OrdCreateIntent>lambdaQuery()
                .eq(OrdCreateIntent::getPayNo, child.getOrderNo()));
        row.setStockAuthority(intent == null ? null : intent.getStockAuthority());
        boolean shipped = items.stream().anyMatch(i -> i.getShippedQty() != null && i.getShippedQty() > 0)
                || !OrdSubOrder.WAIT_FULFILL.equals(child.getStatus());
        row.setRestockApproved(Boolean.TRUE.equals(as.getRestockApproved()));
        boolean inventoryV2 = Integer.valueOf(2).equals(child.getInventoryLifecycleVersion());
        boolean physicallyReceivedReturn = OrdAfterSale.RETURN_REFUND.equals(as.getType())
                && as.getReturnReceivedAt() != null && Boolean.TRUE.equals(as.getRestockApproved());
        // V2 records the physical receipt and saleable disposition before refund execution;
        // restoring those units again here would duplicate stock. Unshipped V2 refunds still
        // release their reservation through the resource worker.
        row.setRestoreStock(!shipped || (physicallyReceivedReturn && !inventoryV2));
        row.setReason(as.getReason());
        row.setStatus(OrdRefundExecution.PREPARED);
        row.setPhase(OrdRefundExecution.REVERSAL);
        row.setResourceStatus(OrdRefundExecution.PENDING);
        row.setNextAttemptAt(0L);
        return row;
    }

    private static void freezeSource(OrdRefundExecution row, OrdOrder parent, SettlePort.PaymentReceipt source) {
        if (source == null || parent == null || !OrdOrder.PAID.equals(parent.getStatus())
                || !"SUCCESS".equals(source.status()) || !Objects.equals(source.userNo(), parent.getUserNo())
                || !Objects.equals(source.currency(), parent.getCurrency())
                || !Objects.equals(source.payChannel(), parent.getPayChannel())
                || !Objects.equals(source.tradeNo(), parent.getPayTradeNo())
                || parent.getPayAmount() == null || source.amountMinor() != parent.getPayAmount()) {
            row.setStatus(OrdRefundExecution.FAILED);
            row.setErrorCode("ORIGIN_PAYMENT_MISMATCH");
            return;
        }
        row.setOriginPaymentNo(source.paymentNo());
        row.setPayChannel(source.payChannel());
        row.setCurrency(source.currency());
    }
}
