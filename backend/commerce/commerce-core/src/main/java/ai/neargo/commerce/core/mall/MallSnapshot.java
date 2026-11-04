package ai.neargo.commerce.core.mall;

import ai.neargo.commerce.api.catalog.*;
import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.mall.*;
import ai.neargo.commerce.core.spi.ConversionSource;

import java.time.Instant;
import java.util.*;

/** Immutable business snapshots shared by order settlement/fulfillment, never host objects. */
public final class MallSnapshot {
    private MallSnapshot() {}

    public record CreditBalance(long version, long available) {}

    public record CreditLot(String lotNo, long version, long available, Long expiresAt) {}

    public record CreditUse(String lotNo, long version, long amount) {}

    public record BasePlan(String planNo, PlanTier tier) {}

    public record Sources(
            String currency,
            List<BasePlan> basePlans,
            Set<String> permanentCodes,
            List<ConversionSource> conversions) {
        public Sources(String currency, List<BasePlan> basePlans, Set<String> permanentCodes) {
            this(currency, basePlans, permanentCodes, List.of());
        }

        public Sources {
            conversions = conversions == null ? List.of() : List.copyOf(conversions);
            basePlans = List.copyOf(basePlans);
            permanentCodes = Set.copyOf(permanentCodes);
        }
    }

    public record Component(
            ComponentAllocation.Allocation allocation, PlanDraft plan, Map<String, String> ends) {
        public Component {
            ends = Map.copyOf(ends);
        }
    }

    public record OrderComponent(
            String orderComponentNo,
            String orderItemNo,
            Component snapshot,
            String fulfillmentStatus,
            long version) {}

    public record ShipmentItem(String orderComponentNo, String hardwareSkuNo, int quantity) {}

    public record Line(
            SkuDraft sku,
            int quantity,
            List<Component> components,
            long skuVersion,
            long skuDefinitionVersion,
            long productVersion) {
        public Line {
            components = List.copyOf(components);
        }
    }

    public record Purchase(String buyerKind, String initiatedBy, RawPrincipalIdentity initiatorIdentity,
            ai.neargo.commerce.api.offline.OfflineCollectionTerms offline) {
        public Purchase {
            if (!("ACCOUNT".equals(buyerKind) || "STORE".equals(buyerKind))
                    || initiatedBy == null || initiatedBy.isBlank()
                    || "STORE".equals(buyerKind) && offline == null)
                throw new IllegalArgumentException("INVALID_PURCHASE_OWNERSHIP");
        }
    }

    public record Quote(
            String quoteNo,
            StoreRef store,
            String entityNo,
            String buyerNo,
            String businessType,
            String intent,
            String currency,
            long valuationAt,
            long expiresAt,
            long entitlementVersion,
            long accountVersion,
            long balanceVersion,
            long catalogVersion,
            long businessTypeVersion,
            String pricingPolicyVersion,
            String stackingPolicyVersion,
            List<Line> lines,
            List<CreditUse> creditUses,
            List<ConversionSource> conversions,
            Long convertedCredit,
            Long historicalAvailableCredit,
            RawPrincipalIdentity buyerIdentity,
            MoneyPolicy moneyPolicy, Purchase purchase) {
        public Quote(String quoteNo, StoreRef store, String entityNo, String buyerNo, String businessType,
                String intent, String currency, long valuationAt, long expiresAt, long entitlementVersion,
                long accountVersion, long balanceVersion, long catalogVersion, long businessTypeVersion,
                String pricingPolicyVersion, String stackingPolicyVersion, List<Line> lines,
                List<CreditUse> creditUses, List<ConversionSource> conversions, Long convertedCredit,
                Long historicalAvailableCredit, RawPrincipalIdentity buyerIdentity, MoneyPolicy moneyPolicy) {
            this(quoteNo, store, entityNo, buyerNo, businessType, intent, currency, valuationAt, expiresAt,
                    entitlementVersion, accountVersion, balanceVersion, catalogVersion, businessTypeVersion,
                    pricingPolicyVersion, stackingPolicyVersion, lines, creditUses, conversions,
                    convertedCredit, historicalAvailableCredit, buyerIdentity, moneyPolicy, null);
        }

        public Quote(String quoteNo, StoreRef store, String entityNo, String buyerNo, String businessType,
                String intent, String currency, long valuationAt, long expiresAt, long entitlementVersion,
                long accountVersion, long balanceVersion, long catalogVersion, long businessTypeVersion,
                String pricingPolicyVersion, String stackingPolicyVersion, List<Line> lines,
                List<CreditUse> creditUses, List<ConversionSource> conversions, Long convertedCredit,
                Long historicalAvailableCredit, RawPrincipalIdentity buyerIdentity) {
            this(quoteNo, store, entityNo, buyerNo, businessType, intent, currency, valuationAt, expiresAt,
                    entitlementVersion, accountVersion, balanceVersion, catalogVersion, businessTypeVersion,
                    pricingPolicyVersion, stackingPolicyVersion, lines, creditUses, conversions,
                    convertedCredit, historicalAvailableCredit, buyerIdentity, null);
        }
        public Quote(String quoteNo, StoreRef store, String entityNo, String buyerNo, String businessType,
                String intent, String currency, long valuationAt, long expiresAt, long entitlementVersion,
                long accountVersion, long balanceVersion, long catalogVersion, long businessTypeVersion,
                String pricingPolicyVersion, String stackingPolicyVersion, List<Line> lines,
                List<CreditUse> creditUses, List<ConversionSource> conversions, Long convertedCredit, Long historicalAvailableCredit) {
            this(quoteNo, store, entityNo, buyerNo, businessType, intent, currency, valuationAt, expiresAt,
                    entitlementVersion, accountVersion, balanceVersion, catalogVersion, businessTypeVersion,
                    pricingPolicyVersion, stackingPolicyVersion, lines, creditUses, conversions, convertedCredit, historicalAvailableCredit, null);
        }
        public Quote(String quoteNo, StoreRef store, String entityNo, String buyerNo, String businessType,
                String intent, String currency, long valuationAt, long expiresAt, long entitlementVersion,
                long accountVersion, long balanceVersion, long catalogVersion, long businessTypeVersion,
                String pricingPolicyVersion, String stackingPolicyVersion, List<Line> lines,
                List<CreditUse> creditUses, List<ConversionSource> conversions, Long convertedCredit) {
            this(quoteNo, store, entityNo, buyerNo, businessType, intent, currency, valuationAt, expiresAt,
                    entitlementVersion, accountVersion, balanceVersion, catalogVersion, businessTypeVersion,
                    pricingPolicyVersion, stackingPolicyVersion, lines, creditUses, conversions, convertedCredit, null);
        }
        public Quote(
                String quoteNo,
                StoreRef store,
                String entityNo,
                String buyerNo,
                String businessType,
                String intent,
                String currency,
                long valuationAt,
                long expiresAt,
                long entitlementVersion,
                long accountVersion,
                long balanceVersion,
                long catalogVersion,
                long businessTypeVersion,
                String pricingPolicyVersion,
                String stackingPolicyVersion,
                List<Line> lines,
                List<CreditUse> creditUses) {
            this(
                    quoteNo,
                    store,
                    entityNo,
                    buyerNo,
                    businessType,
                    intent,
                    currency,
                    valuationAt,
                    expiresAt,
                    entitlementVersion,
                    accountVersion,
                    balanceVersion,
                    catalogVersion,
                    businessTypeVersion,
                    pricingPolicyVersion,
                    stackingPolicyVersion,
                    lines,
                    creditUses,
                    List.of(),
                    0L);
        }

        public Quote {
            purchase = purchase == null ? new Purchase("ACCOUNT", buyerNo, buyerIdentity, null) : purchase;
            if (purchase.offline() != null && !currency.equals(purchase.offline().currency()))
                throw new IllegalArgumentException("OFFLINE_CURRENCY_MISMATCH");
            moneyPolicy = moneyPolicy == null ? MoneyPolicy.legacy(currency) : moneyPolicy;
            if (!currency.equals(moneyPolicy.currency())) throw new IllegalArgumentException("MONEY_POLICY_CURRENCY");
            if (convertedCredit == null) {
                if ("UPGRADE".equals(intent))
                    throw new IllegalArgumentException("missing upgrade credit evidence");
                convertedCredit = 0L;
            }
            conversions = conversions == null ? List.of() : List.copyOf(conversions);
            lines = List.copyOf(lines);
            creditUses = List.copyOf(creditUses);
        }

        public long gross() {
            return lines.stream()
                    .mapToLong(l -> Math.multiplyExact(l.sku().listPrice().minor(), l.quantity()))
                    .reduce(0, Math::addExact);
        }

        public long deal() {
            return lines.stream()
                    .mapToLong(l -> Math.multiplyExact(l.sku().price().minor(), l.quantity()))
                    .reduce(0, Math::addExact);
        }

        public long convertedValue() {
            return conversions.stream()
                    .mapToLong(ConversionSource::value)
                    .reduce(0, Math::addExact);
        }

        public long credit() {
            return Math.addExact(historicalCredit(), convertedCredit);
        }

        public long historicalCredit() {
            return creditUses.stream().mapToLong(CreditUse::amount).reduce(0, Math::addExact);
        }

        public boolean digital() {
            return lines.stream()
                    .flatMap(l -> l.components().stream())
                    .anyMatch(c -> c.allocation().component().kind() != ComponentKind.HARDWARE);
        }

        public boolean hardware() {
            return lines.stream()
                    .flatMap(l -> l.components().stream())
                    .anyMatch(c -> c.allocation().component().kind() == ComponentKind.HARDWARE);
        }

        public List<QuoteComponent> components() {
            return lines.stream()
                    .flatMap(l -> l.components().stream())
                    .map(
                            c -> {
                                var a = c.allocation();
                                var t = a.component();
                                return new QuoteComponent(
                                        t.componentNo(),
                                        t.kind(),
                                        new Money(currency, a.gross()),
                                        new Money(currency, a.deal()),
                                        new Money(currency, a.credit()),
                                        new Money(currency, a.cash()),
                                        t.periodDays() == null ? 0 : t.periodDays(),
                                        a.cycles().size());
                            })
                    .toList();
        }

        public QuoteView view() {
            return new QuoteView(
                    quoteNo,
                    store,
                    new Money(currency, deal()),
                    new Money(currency, credit()),
                    new Money(currency, deal() - credit()),
                    components(),
                    entitlementVersion,
                    balanceVersion,
                    Instant.ofEpochMilli(expiresAt),
                    historicalAvailableCredit == null ? null : new ai.neargo.commerce.api.mall.QuoteCreditView(
                            new Money(currency, convertedValue()), new Money(currency, historicalAvailableCredit),
                            new Money(currency, credit()), new Money(currency, historicalCredit()),
                            new Money(currency, Math.subtractExact(Math.addExact(historicalAvailableCredit, convertedValue()), credit())),
                            Instant.ofEpochMilli(valuationAt)));
        }
    }

    public record Order(
            String orderNo,
            Quote quote,
            String idempotencyKey,
            String requestHash,
            ShippingAddress shippingAddress,
            OrderState.Axes axes,
            long createdAt,
            long version) {
        public long paymentDueAt() {
            return quote.purchase().offline() == null ? quote.expiresAt()
                    : Math.addExact(createdAt, quote.purchase().offline().paymentWindowMs());
        }

        public OrderView view() {
            return new OrderView(
                    orderNo,
                    quote.store(),
                    status(),
                    new Money(quote.currency(), quote.deal() - quote.credit()),
                    quote.components(),
                    Instant.ofEpochMilli(createdAt));
        }

        public OrderStatus status() {
            return axes.status();
        }

        public Order withAxes(OrderState.Axes s) {
            return new Order(
                    orderNo,
                    quote,
                    idempotencyKey,
                    requestHash,
                    shippingAddress,
                    s,
                    createdAt,
                    Math.addExact(version, 1));
        }
    }
}
