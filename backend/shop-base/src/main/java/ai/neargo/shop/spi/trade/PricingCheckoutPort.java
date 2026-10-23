package ai.neargo.shop.spi.trade;

import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Credentials;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;

import java.math.BigDecimal;
import java.util.List;

/** Consumer-safe calculated prices. No owner receipts, credentials or internal snapshots are exposed. */
public interface PricingCheckoutPort {
    public record Selection(List<String> couponNos, String storedValueAccountNo, Long pointsToUse) {
        public Selection { couponNos = couponNos == null ? List.of() : List.copyOf(couponNos); }
        public static Selection none() { return new Selection(List.of(), null, null); }
    }
    public record TotalsVO(long goodsMinor, long discountMinor, long netMinor, long taxMinor,
            long receivableMinor, long remainingDueMinor) { }
    public record LineVO(String sourceLineNo, String componentRef, String invoiceLineRef, String kind,
            String goodsNo, String skuNo, BigDecimal quantityStart, BigDecimal quantityEnd,
            long goodsMinor, long netMinor, long taxMinor, long receivableMinor) { }
    public record QuoteVO(String quoteNo, String status, String expiresAt, long sourceVersion,
            String sourceHash, String currency, List<LineVO> lines, TotalsVO totals, String confirmationDigest) {
        public QuoteVO { lines = List.copyOf(lines); }
    }
    QuoteVO quoteStaff(Scope scope, String actorNo, PricingSourceRef source, Selection selection, String key);
    QuoteVO quoteCustomer(PricingSourceRef source, Credentials credentials, Selection selection, String key);
    QuoteVO getStaff(Scope scope, String actorNo, PricingSourceRef source, String quoteNo);
    QuoteVO getCustomer(PricingSourceRef source, Credentials credentials, String quoteNo);
    QuoteVO revalidateStaff(Scope scope, String actorNo, PricingSourceRef source, String quoteNo, String digest);
    QuoteVO revalidateCustomer(PricingSourceRef source, Credentials credentials, String quoteNo, String digest);
}
