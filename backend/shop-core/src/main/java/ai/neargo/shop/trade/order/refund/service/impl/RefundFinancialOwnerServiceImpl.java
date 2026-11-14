package ai.neargo.shop.trade.order.refund.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.settle.OrdinaryPaymentPort;
import ai.neargo.shop.spi.settle.OrdinaryRefundPort;
import ai.neargo.shop.spi.settle.RefundDispatchResult;
import ai.neargo.shop.spi.settle.RefundRequest;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.trade.entity.OrdAfterSale;
import ai.neargo.shop.trade.entity.OrdIndustryOrderSource;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers.IndustryOrderSourceMapper;
import ai.neargo.shop.trade.order.entity.OrdRefundExecution;
import ai.neargo.shop.trade.order.mapper.AfterSaleMapper;
import ai.neargo.shop.trade.order.mapper.StoreOrderMapper;
import ai.neargo.shop.trade.order.refund.service.RefundFinancialOwnerService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.Objects;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/** Source refunds keep local liability reversal and use the actual PAY cash owner for all financial work. */
@Service
public class RefundFinancialOwnerServiceImpl implements RefundFinancialOwnerService {
    private final IndustryOrderSourceMapper sources;
    private final AfterSaleMapper cases;
    private final StoreOrderMapper children;
    private final ObjectProvider<OrdinaryPaymentPort> payments;
    private final ObjectProvider<OrdinaryRefundPort> refunds;
    private final SettlePort legacy;

    public RefundFinancialOwnerServiceImpl(IndustryOrderSourceMapper sources, AfterSaleMapper cases,
            StoreOrderMapper children, ObjectProvider<OrdinaryPaymentPort> payments,
            ObjectProvider<OrdinaryRefundPort> refunds, SettlePort legacy) {
        this.sources = sources; this.cases = cases; this.children = children;
        this.payments = payments; this.refunds = refunds; this.legacy = legacy;
    }

    @Override
    public String originFingerprint(OrdAfterSale afterSale, OrdSubOrder child, OrdOrder parent) {
        var source=source(afterSale,child);
        if(source==null) return null;
        if(parent==null) throw conflict();
        return PricingSnapshotHash.of(new OriginIdentity(source.getTenantNo(),source.getEntityNo(),source.getStoreNo(),
                source.getBuyerNo(),source.getOrderNo(),source.getSubOrderNo(),source.getRequestHash(),
                source.getEconomicsHash(),source.getPaymentOutTradeNo(),source.getPaymentState(),
                parent.getPayAmount(),parent.getCurrency(),parent.getPayChannel(),parent.getPayTradeNo(),parent.getStatus()));
    }
    private record OriginIdentity(String tenant,String entity,String store,String buyer,String order,String child,
                                  String requestHash,String economicsHash,String outTradeNo,String state,
                                  Long amount,String currency,String channel,String trade,String orderStatus) { }

    @Override
    public SettlePort.PaymentReceipt origin(OrdAfterSale afterSale, OrdSubOrder child, OrdOrder parent) {
        if (parent == null) return null;
        var source = source(afterSale, child);
        if (source == null) return legacy.refundOrigin(parent.getOrderNo(), parent.getPayTradeNo(), parent.getPayChannel());
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("SOURCE_REFUND_ORIGIN_QUERY_REQUIRES_NO_TRANSACTION");
        var lookup = payments.getObject().query(new OrdinaryPaymentPort.Reference(parent.getOrderNo(),
                child.getUserNo(), source.getPaymentOutTradeNo()));
        var payment = lookup == null ? null : lookup.payment();
        if (!Boolean.TRUE.equals(lookup == null ? null : lookup.found()) || payment == null
                || !"SUCCESS".equals(payment.status()) || !Objects.equals(payment.orderNo(), parent.getOrderNo())
                || !Objects.equals(payment.userNo(), child.getUserNo())
                || !Objects.equals(payment.outTradeNo(), source.getPaymentOutTradeNo())
                || !Objects.equals(payment.tradeNo(), parent.getPayTradeNo())
                || !Objects.equals(payment.payChannel(), parent.getPayChannel())
                || !Objects.equals(payment.currency(), parent.getCurrency())
                || !Objects.equals(payment.amountMinor(), parent.getPayAmount())) throw conflict();
        return new SettlePort.PaymentReceipt(payment.paymentNo(), payment.orderNo(), payment.userNo(),
                payment.payChannel(), payment.tradeNo(), payment.amountMinor(), payment.currency(),
                payment.status(), payment.documentNo());
    }

    @Override
    public RefundDispatchResult request(OrdRefundExecution row) {
        var source = source(row);
        if (source == null) return legacy.requestRefund(requestOf(row));
        return refunds.getObject().request(command(row, source)).outcome();
    }

    @Override
    public RefundDispatchResult query(OrdRefundExecution row) {
        var source = source(row);
        if (source == null) return legacy.queryRefundExecution(requestOf(row));
        return refunds.getObject().query(command(row, source)).outcome();
    }

    private OrdIndustryOrderSource source(OrdRefundExecution row) {
        if (row == null) throw conflict();
        OrdAfterSale afterSale = DataScopeContext.executeWithoutScope(() -> cases.selectOne(
                Wrappers.<OrdAfterSale>lambdaQuery().eq(OrdAfterSale::getAfterSaleNo, row.getAfterSaleNo())));
        OrdSubOrder child = DataScopeContext.executeWithoutScope(() -> children.selectOne(
                Wrappers.<OrdSubOrder>lambdaQuery().eq(OrdSubOrder::getSubOrderNo, row.getSubOrderNo())));
        if (afterSale == null || child == null || !Objects.equals(row.getOrderNo(), child.getOrderNo())
                || !Objects.equals(afterSale.getSubOrderNo(), child.getSubOrderNo())) throw conflict();
        return source(afterSale, child);
    }

    private OrdIndustryOrderSource source(OrdAfterSale afterSale, OrdSubOrder child) {
        var source = DataScopeContext.executeWithoutScope(() -> sources.selectOne(
                Wrappers.<OrdIndustryOrderSource>lambdaQuery().eq(OrdIndustryOrderSource::getOrderNo, child.getOrderNo())
                        .eq(OrdIndustryOrderSource::getSubOrderNo, child.getSubOrderNo())));
        if (source == null) {
            if (afterSale.getSourceSystem() != null) throw conflict();
            return null;
        }
        if (!"BOUND".equals(source.getStatus()) || source.getPaymentOutTradeNo() == null
                || source.getPaymentOutTradeNo().isBlank() || !"SUCCESS".equals(source.getPaymentState())
                || !Objects.equals(source.getTenantNo(), child.getTenantNo())
                || !Objects.equals(source.getEntityNo(), child.getEntityNo())
                || !Objects.equals(source.getStoreNo(), child.getStoreNo())
                || !Objects.equals(source.getBuyerNo(), child.getUserNo())
                || !Objects.equals(afterSale.getUserNo(), child.getUserNo())
                || !Objects.equals(afterSale.getEntityNo(), child.getEntityNo())) throw conflict();
        return source;
    }

    private static OrdinaryRefundPort.Execution command(OrdRefundExecution row, OrdIndustryOrderSource source) {
        return new OrdinaryRefundPort.Execution(row.getOrderNo(), source.getBuyerNo(), source.getPaymentOutTradeNo(), requestOf(row));
    }
    private static RefundRequest requestOf(OrdRefundExecution row) {
        return new RefundRequest(row.getExecutionNo(), row.getAfterSaleNo(), row.getSubOrderNo(), row.getOriginPaymentNo(),
                row.getAmountMinor(), row.getCurrency(), row.getReason());
    }
    private static BizException conflict() { return BizException.of(ErrorCode.CONFLICT); }
}
