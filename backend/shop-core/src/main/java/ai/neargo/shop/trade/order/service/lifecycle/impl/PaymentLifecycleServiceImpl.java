package ai.neargo.shop.trade.order.service.lifecycle.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.settle.OrdinaryPaymentPort;
import ai.neargo.shop.trade.entity.OrdIndustryOrderSource;
import ai.neargo.shop.trade.mapper.TradeMappers.IndustryOrderSourceMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PayModes;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.order.service.lifecycle.PaymentLifecycleService;
import ai.neargo.shop.trade.order.service.lifecycle.PaymentResolutionService;
import ai.neargo.shop.trade.order.entity.OrdPaymentResolution;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class PaymentLifecycleServiceImpl implements PaymentLifecycleService {
    private final SettlePort settlement;
    private final PaymentResolutionService resolutions;
    private final IndustryOrderSourceMapper industrySources;
    private final ObjectProvider<OrdinaryPaymentPort> paymentOwners;

    public PaymentLifecycleServiceImpl(SettlePort settlement, PaymentResolutionService resolutions) {
        this(settlement, resolutions, null, null);
    }

    @Autowired
    public PaymentLifecycleServiceImpl(SettlePort settlement, PaymentResolutionService resolutions,
            IndustryOrderSourceMapper industrySources, ObjectProvider<OrdinaryPaymentPort> paymentOwners) {
        this.settlement = settlement; this.resolutions = resolutions;
        this.industrySources = industrySources; this.paymentOwners = paymentOwners;
    }

    @Override
    public ConfirmedPayment requireConfirmed(OrdOrder order, String channel, String tradeNo) {
        if (OrdOrder.WAIT_OFFLINE_PAY.equals(order.getStatus()) && PayModes.OFFLINE.equals(channel)) {
            return new ConfirmedPayment(channel, tradeNo);
        }
        var source = industrySources == null ? null : DataScopeContext.executeWithoutScope(() ->
                industrySources.selectOne(Wrappers.<OrdIndustryOrderSource>lambdaQuery()
                        .eq(OrdIndustryOrderSource::getTenantNo, order.getTenantNo())
                        .eq(OrdIndustryOrderSource::getOrderNo, order.getOrderNo())));
        SettlePort.PaymentReceipt receipt;
        if (source != null) {
            if (!"BOUND".equals(source.getStatus()) || !Objects.equals(order.getUserNo(), source.getBuyerNo())
                    || source.getPaymentOutTradeNo() == null || paymentOwners == null
                    || paymentOwners.getIfUnique() == null) throw BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
            var found = paymentOwners.getIfUnique().query(new OrdinaryPaymentPort.Reference(
                    order.getOrderNo(), order.getUserNo(), source.getPaymentOutTradeNo()));
            var actual = found == null || !found.found() ? null : found.payment();
            if (actual != null && !source.getPaymentOutTradeNo().equals(actual.outTradeNo())) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
            receipt = actual == null || actual.amountMinor() == null ? null : new SettlePort.PaymentReceipt(
                    actual.paymentNo(), actual.orderNo(), actual.userNo(), actual.payChannel(), actual.tradeNo(),
                    actual.amountMinor(), actual.currency(), actual.status(), actual.documentNo());
        } else {
            receipt = tradeNo == null ? settlement.successPayment(order.getOrderNo())
                    : settlement.successPayment(order.getOrderNo(), channel, tradeNo);
        }
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
        var receipt = proof == null ? null : proof.receipt();
        if (receipt == null || !"SUCCESS".equals(receipt.status())
                || !Objects.equals(order.getOrderNo(), receipt.orderNo())
                || !Objects.equals(order.getUserNo(), receipt.userNo())
                || order.getPayAmount() == null || order.getPayAmount() != receipt.amountMinor()
                || !Objects.equals(order.getCurrency(), receipt.currency())
                || !Objects.equals(proof.channel(), receipt.payChannel())
                || !Objects.equals(proof.tradeNo(), receipt.tradeNo())) throw BizException.of(ErrorCode.CONFLICT);
        if (OrdOrder.CANCELLED.equals(order.getStatus()) || OrdOrder.CLOSED.equals(order.getStatus())) {
            resolutions.record(order, receipt, OrdPaymentResolution.LATE_PAYMENT);
            throw BizException.of(ErrorCode.CONFLICT);
        }
        if (OrdOrder.PAID.equals(order.getStatus()) && order.getPayTradeNo() != null
                && !Objects.equals(order.getPayTradeNo(), receipt.tradeNo())) {
            resolutions.record(order, receipt, OrdPaymentResolution.DUPLICATE_PAYMENT);
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

}
