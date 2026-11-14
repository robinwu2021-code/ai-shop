package ai.neargo.shop.pay.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.channel.PaymentAccountLookup;
import ai.neargo.shop.pay.entity.StlPayment;
import ai.neargo.shop.pay.mapper.SettleMappers;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 按商户单号查子商户号（F12 · Review R14）：收款单取它自己的；退款单取原交易的。
 * 先读 {@code stl_payment.sub_merchant_id}（F08 落的），存量行为空时回退到请求快照（与下单同一规则 {@link DirectAccounts}）。
 */
@Component
public class PaymentAccountLookupImpl implements PaymentAccountLookup {

    private final SettleMappers.PaymentMapper payments;
    private final PaymentLedgerService ledger;

    public PaymentAccountLookupImpl(SettleMappers.PaymentMapper payments, PaymentLedgerService ledger) {
        this.payments = payments;
        this.ledger = ledger;
    }

    @Override
    public Optional<String> subMerchantOf(String outTradeNo) {
        StlPayment found = byOutTradeNo(outTradeNo);
        StlPayment row = found != null && StlPayment.REFUND.equals(found.getDirection())
                ? originOf(found) : found;
        if (row == null) {
            return Optional.empty();
        }
        if (row.getSubMerchantId() != null && !row.getSubMerchantId().isBlank()) {
            return Optional.of(row.getSubMerchantId());
        }
        return ledger.paymentRequest(row.getOutTradeNo())
                .flatMap(DirectAccounts::of)
                .map(r -> r.subMerchantId());
    }

    private StlPayment originOf(StlPayment refund) {
        String origin = refund.getOriginPaymentNo();
        return origin == null ? null : DataScopeContext.executeWithoutScope(() ->
                payments.selectOne(Wrappers.<StlPayment>lambdaQuery().eq(StlPayment::getPaymentNo, origin)));
    }

    private StlPayment byOutTradeNo(String outTradeNo) {
        if (outTradeNo == null || outTradeNo.isBlank()) {
            return null;
        }
        return DataScopeContext.executeWithoutScope(() -> payments.selectOne(
                Wrappers.<StlPayment>lambdaQuery().eq(StlPayment::getOutTradeNo, outTradeNo)));
    }
}
