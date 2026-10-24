package ai.neargo.shop.spi.trade;

import ai.neargo.shop.spi.trade.IndustryOrderPort.Identity;
import java.util.List;
import java.util.Map;

/** Original source-owned checkout; amounts, routes and payer facts are always resolved by their real owners. */
public interface IndustryCheckoutPort {
    record PayCommand(Identity identity, String orderNo, String quoteNo, PricingSourceRef source,
            String confirmationDigest, String payChannel) { }
    record ChannelVO(String payChannel, boolean available, String reason) { }
    record PaymentVO(String outTradeNo, String payChannel, String status, Map<String, Object> payParams,
            String paramsExpiresAt) {
        public PaymentVO { payParams = payParams == null ? Map.of() : Map.copyOf(payParams); }
    }
    record View(String orderNo, String subOrderNo, String quoteNo, long sourceVersion, String sourceHash,
            String economicsHash, String confirmationDigest, String orderStatus, String currency, long amountMinor,
            Long payDeadlineAt, List<ChannelVO> channels, PaymentVO payment, boolean readyToAccept) {
        public View { channels = List.copyOf(channels); }
    }
    View get(Identity identity, String orderNo);
    View pay(PayCommand command);
    View queryPayment(Identity identity, String orderNo);
    View finalizeCheckout(Identity identity, String orderNo);
}
