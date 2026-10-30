package ai.neargo.shop.trade.order.service.lifecycle.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PayModes;
import ai.neargo.shop.spi.settle.OrdinaryPaymentPort;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.trade.entity.OrdIndustryOrderSource;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers.IndustryOrderSourceMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper;
import ai.neargo.shop.trade.order.entity.OrdPaymentResolution;
import ai.neargo.shop.trade.order.service.lifecycle.PaymentLifecycleService;
import ai.neargo.shop.trade.order.service.lifecycle.PaymentResolutionService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Financial owner reads finish before Core locks; projection validates immutable receipts locally. */
@Service
public class PaymentLifecycleServiceImpl implements PaymentLifecycleService {
    private final SettlePort settlement;
    private final PaymentResolutionService resolutions;
    private final IndustryOrderSourceMapper industrySources;
    private final ObjectProvider<OrdinaryPaymentPort> paymentOwners;
    private final SubOrderMapper subOrders;

    public PaymentLifecycleServiceImpl(SettlePort settlement, PaymentResolutionService resolutions) {
        this(settlement, resolutions, null, null, null);
    }

    public PaymentLifecycleServiceImpl(SettlePort settlement, PaymentResolutionService resolutions,
            IndustryOrderSourceMapper industrySources, ObjectProvider<OrdinaryPaymentPort> paymentOwners) {
        this(settlement, resolutions, industrySources, paymentOwners, null);
    }

    public PaymentLifecycleServiceImpl(SettlePort settlement, PaymentResolutionService resolutions,
            SubOrderMapper subOrders) {
        this(settlement, resolutions, null, null, subOrders);
    }

    @Autowired
    public PaymentLifecycleServiceImpl(SettlePort settlement, PaymentResolutionService resolutions,
            IndustryOrderSourceMapper industrySources, ObjectProvider<OrdinaryPaymentPort> paymentOwners,
            SubOrderMapper subOrders) {
        this.settlement = settlement;
        this.resolutions = resolutions;
        this.industrySources = industrySources;
        this.paymentOwners = paymentOwners;
        this.subOrders = subOrders;
    }

    @Override
    public SettlePort.PaymentReceipt readConfirmed(String orderNo, String channel, String tradeNo) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Payment receipt reads require no caller transaction");
        }
        var source = industrySources == null ? null : DataScopeContext.executeWithoutScope(() ->
                industrySources.selectOne(Wrappers.<OrdIndustryOrderSource>lambdaQuery()
                        .eq(OrdIndustryOrderSource::getTenantNo, "MAIN")
                        .eq(OrdIndustryOrderSource::getOrderNo, orderNo)));
        if (source == null) {
            return tradeNo == null ? settlement.successPayment(orderNo)
                    : settlement.successPayment(orderNo, channel, tradeNo);
        }
        var owner = paymentOwners == null ? null : paymentOwners.getIfUnique();
        if (!"BOUND".equals(source.getStatus()) || source.getBuyerNo() == null || source.getBuyerNo().isBlank()
                || source.getPaymentOutTradeNo() == null || source.getPaymentOutTradeNo().isBlank() || owner == null) {
            throw BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
        }
        var found = owner.query(new OrdinaryPaymentPort.Reference(orderNo, source.getBuyerNo(),
                source.getPaymentOutTradeNo()));
        var actual = found == null || !found.found() ? null : found.payment();
        if (actual != null && !source.getPaymentOutTradeNo().equals(actual.outTradeNo())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        return actual == null || actual.amountMinor() == null ? null : new SettlePort.PaymentReceipt(
                actual.paymentNo(), actual.orderNo(), actual.userNo(), actual.payChannel(), actual.tradeNo(),
                actual.amountMinor(), actual.currency(), actual.status(), actual.documentNo());
    }

    @Override
    public ConfirmedPayment requireConfirmed(OrdOrder order, String channel, String tradeNo) {
        if (OrdOrder.WAIT_OFFLINE_PAY.equals(order.getStatus()) && PayModes.OFFLINE.equals(channel)) {
            if (subOrders == null) throw BizException.of(ErrorCode.CHECKOUT_DEPENDENCY_UNAVAILABLE);
            long managed = DataScopeContext.executeWithoutScope(() -> subOrders.selectCount(
                    Wrappers.<OrdSubOrder>lambdaQuery().eq(OrdSubOrder::getTenantNo, order.getTenantNo())
                            .eq(OrdSubOrder::getOrderNo, order.getOrderNo())
                            .eq(OrdSubOrder::getFulfillmentAuthority, OrdSubOrder.SERVICE_EXECUTION)));
            if (managed > 0) throw BizException.of(ErrorCode.CHECKOUT_UNSUPPORTED_SELECTION);
            return new ConfirmedPayment(channel, tradeNo);
        }
        return requireConfirmed(order, channel, tradeNo, readConfirmed(order.getOrderNo(), channel, tradeNo));
    }

    @Override
    public ConfirmedPayment requireConfirmed(
            OrdOrder order, String channel, String tradeNo, SettlePort.PaymentReceipt receipt) {
        if (receipt == null || !"SUCCESS".equals(receipt.status())
                || !Objects.equals(order.getOrderNo(), receipt.orderNo())
                || !Objects.equals(order.getUserNo(), receipt.userNo())
                || order.getPayAmount() == null || order.getPayAmount() != receipt.amountMinor()
                || !Objects.equals(order.getCurrency(), receipt.currency())
                || receipt.tradeNo() == null || receipt.tradeNo().isBlank()
                || receipt.payChannel() == null || receipt.payChannel().isBlank()
                || channel != null && !Objects.equals(channel, receipt.payChannel())
                || tradeNo != null && !Objects.equals(tradeNo, receipt.tradeNo())) {
            resolutions.record(order, receipt, OrdPaymentResolution.RECEIPT_MISMATCH);
            throw BizException.of(ErrorCode.CONFLICT);
        }
        if (OrdOrder.CANCELLED.equals(order.getStatus()) || OrdOrder.CLOSED.equals(order.getStatus())) {
            resolutions.record(order, receipt, OrdPaymentResolution.LATE_PAYMENT);
            throw BizException.of(ErrorCode.CONFLICT);
        }
        if (OrdOrder.PAID.equals(order.getStatus()) && order.getPayTradeNo() != null
                && !Objects.equals(order.getPayTradeNo(), receipt.tradeNo())) {
            resolutions.record(order, receipt, OrdPaymentResolution.DUPLICATE_PAYMENT);
            throw BizException.of(ErrorCode.CONFLICT);
        }
        return new ConfirmedPayment(receipt.payChannel(), receipt.tradeNo(), receipt);
    }

    @Override
    public void verifyConfirmed(OrdOrder order, ConfirmedPayment proof) {
        if (proof == null) throw BizException.of(ErrorCode.CONFLICT);
        requireConfirmed(order, proof.channel(), proof.tradeNo(), proof.receipt());
    }
}
