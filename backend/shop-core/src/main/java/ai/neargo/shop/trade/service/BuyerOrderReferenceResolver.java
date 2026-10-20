package ai.neargo.shop.trade.service;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.trade.dto.OrderVO;
import org.springframework.stereotype.Service;

/** Unknown old links prefer an exact order; explicit payment links always follow the stored association. */
@Service
public class BuyerOrderReferenceResolver {
    private final OrderService orders;
    private final SettlePort payments;
    public BuyerOrderReferenceResolver(OrderService orders, SettlePort payments) {
        this.orders = orders; this.payments = payments;
    }
    public OrderVO resolve(String reference, boolean paymentReference, String client, String buyerNo) {
        if (reference == null || reference.isBlank() || buyerNo == null || buyerNo.isBlank())
            throw BizException.of(ErrorCode.NOT_FOUND);
        if (!paymentReference) {
            try { return orders.detail(reference, client); }
            catch (BizException failure) {
                if (failure.errorCode() != ErrorCode.NOT_FOUND) throw failure;
            }
        }
        var payment = payments.paymentByOutTradeNo(reference);
        if (payment == null || !buyerNo.equals(payment.userNo()) || payment.orderNo() == null
                || payment.orderNo().isBlank()) throw BizException.of(ErrorCode.NOT_FOUND);
        // Reuse the authoritative order query and its buyer checks after resolving the payment.
        return orders.detail(payment.orderNo(), client);
    }
}
