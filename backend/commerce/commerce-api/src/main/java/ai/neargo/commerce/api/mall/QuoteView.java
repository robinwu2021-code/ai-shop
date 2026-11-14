package ai.neargo.commerce.api.mall;

import java.time.Instant;
import java.util.List;
import ai.neargo.commerce.api.common.*;

public record QuoteView(String quoteNo, StoreRef store, Money total, Money credit, Money payable, List<QuoteComponent> components, long entitlementVersion, long balanceVersion, Instant expiresAt, QuoteCreditView creditBreakdown, String collectionMethod) {
    public QuoteView(String quoteNo, StoreRef store, Money total, Money credit, Money payable,
            List<QuoteComponent> components,long entitlementVersion,long balanceVersion,Instant expiresAt,QuoteCreditView breakdown) {
        this(quoteNo,store,total,credit,payable,components,entitlementVersion,balanceVersion,expiresAt,breakdown,null);
    }
    public QuoteView(String quoteNo, StoreRef store, Money total, Money credit, Money payable,
            List<QuoteComponent> components, long entitlementVersion, long balanceVersion, Instant expiresAt) {
        this(quoteNo, store, total, credit, payable, components, entitlementVersion, balanceVersion, expiresAt, null, null);
    }
}
