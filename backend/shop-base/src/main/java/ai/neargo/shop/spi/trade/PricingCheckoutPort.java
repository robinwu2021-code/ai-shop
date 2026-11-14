package ai.neargo.shop.spi.trade;

import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Credentials;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import java.math.BigDecimal;
import java.util.List;

/**
 * Consumer-safe calculated prices. No owner receipts, credentials or internal snapshots are
 * exposed.
 */
public interface PricingCheckoutPort {
    public record Selection(List<String> couponNos, String storedValueAccountNo, Long pointsToUse) {
        public Selection {
            couponNos = couponNos == null ? List.of() : List.copyOf(couponNos);
        }

        public static Selection none() {
            return new Selection(List.of(), null, null);
        }
    }

    public record TotalsVO(
            long goodsMinor,
            long discountMinor,
            long netMinor,
            long taxMinor,
            long receivableMinor,
            long remainingDueMinor,
            @JsonInclude(Include.NON_NULL) Long feeMinor) {
        public TotalsVO(long goods, long discount, long net, long tax, long due, long remaining) {
            this(goods, discount, net, tax, due, remaining, null);
        }
    }

    public record LineVO(
            String sourceLineNo,
            String componentRef,
            String invoiceLineRef,
            String kind,
            String goodsNo,
            String skuNo,
            BigDecimal quantityStart,
            BigDecimal quantityEnd,
            long goodsMinor,
            long netMinor,
            long taxMinor,
            long receivableMinor,
            @JsonInclude(Include.NON_NULL) Long feeMinor) {
        public LineVO(
                String source,
                String component,
                String invoice,
                String kind,
                String goods,
                String sku,
                BigDecimal from,
                BigDecimal to,
                long amount,
                long net,
                long tax,
                long due) {
            this(
                    source, component, invoice, kind, goods, sku, from, to, amount, net, tax, due,
                    null);
        }
    }

    public record QuoteVO(
            String quoteNo,
            String status,
            String expiresAt,
            long sourceVersion,
            String sourceHash,
            String currency,
            List<LineVO> lines,
            TotalsVO totals,
            String confirmationDigest) {
        public QuoteVO {
            lines = List.copyOf(lines);
        }
    }

    QuoteVO quoteStaff(
            Scope scope, String actorNo, PricingSourceRef source, Selection selection, String key);

    QuoteVO quoteCustomer(
            PricingSourceRef source, Credentials credentials, Selection selection, String key);

    QuoteVO getStaff(Scope scope, String actorNo, PricingSourceRef source, String quoteNo);

    QuoteVO getCustomer(PricingSourceRef source, Credentials credentials, String quoteNo);

    QuoteVO revalidateStaff(
            Scope scope, String actorNo, PricingSourceRef source, String quoteNo, String digest);

    QuoteVO revalidateCustomer(
            PricingSourceRef source, Credentials credentials, String quoteNo, String digest);
}
