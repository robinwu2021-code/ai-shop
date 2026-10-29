package ai.neargo.shop.spi.trade;

import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Reference;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Scope;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Collection;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.CollectionFacts;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Data;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.Facts;
import ai.neargo.shop.spi.trade.IndustryChannelOrderSourcePort.PlatformAcceptance;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Shared original admission checks conserve invoices and allocations without re-pricing or issuing
 * payment authority.
 */
public final class IndustryChannelOrderFacts {
    private IndustryChannelOrderFacts() {}

    public static void reference(Reference reference) {
        require(reference != null);
        scope(reference.scope());
        key(reference.sourceSystem(), 32);
        key(reference.sourceNo(), 32);
        key(reference.importRef(), 32);
        require(reference.sourceVersion() >= 0 && hash(reference.sourceHash()));
    }

    public static void scope(Scope scope) {
        require(scope != null && "MAIN".equals(scope.tenantNo()));
        key(scope.entityNo(), 32);
        key(scope.storeNo(), 32);
    }

    /**
     * Captured facts are accepted only for the exact original owner reference and a current bounded
     * read receipt.
     */
    public static void validate(Reference reference, Facts facts, Clock clock) {
        reference(reference);
        require(facts != null && reference.equals(facts.reference()) && facts.data() != null);
        freshness(facts.observedAt(), facts.validUntil(), clock);
        key(facts.receiptRef(), 256);
        var d = facts.data();
        require(
                reference.scope().equals(d.scope())
                        && reference.sourceSystem().equals(d.sourceSystem())
                        && reference.sourceNo().equals(d.sourceNo())
                        && reference.sourceVersion() == d.sourceVersion()
                        && reference.importRef().equals(d.importRef())
                        && reference.sourceHash().equals(PricingSnapshotHash.of(d))
                        && d.currency() != null
                        && d.currency().matches("[A-Z]{3}")
                        && d.channel() != null
                        && d.collection() != null
                        && d.fulfillment() != null
                        && d.party() != null
                        && d.orderedAt() != null
                        && !d.orderedAt().isAfter(clock.instant())
                        && d.totals() != null);
        key(d.externalOrderRef(), 128);
        require(hash(d.externalDocumentHash()) && hash(d.externalFinancialHash()));
        key(d.externalReadReceiptRef(), 256);
        key(d.channel().providerCode(), 32);
        key(d.channel().profileRef(), 32);
        require(hash(d.channel().channelStoreKey()));
        key(d.channel().externalAccountRef(), 128);
        key(d.channel().externalStoreRef(), 128);
        require(d.channel().market() != null && d.channel().market().matches("[A-Z]{2}"));
        key(d.party().partyNo(), 32);
        if (d.collection() == Collection.PLATFORM_COLLECTED) key(d.collectionRef(), 128);
        require(
                !d.invoiceLines().isEmpty()
                        && d.invoiceLines().size() <= 1000
                        && !d.components().isEmpty()
                        && d.components().size() <= 10000);
        var originals = new HashMap<String, IndustryChannelOrderSourcePort.InvoiceLine>();
        long gross = 0, discount = 0, tax = 0, net = 0;
        for (var line : d.invoiceLines()) {
            require(line != null);
            key(line.externalLineRef(), 128);
            key(line.unit(), 16);
            quantity(line.quantity());
            require(originals.putIfAbsent(line.externalLineRef(), line) == null);
            money(
                    line.grossExTaxMinor(),
                    line.merchantDiscountExTaxMinor(),
                    line.taxMinor(),
                    line.netMinor());
            gross = Math.addExact(gross, line.grossExTaxMinor());
            discount = Math.addExact(discount, line.merchantDiscountExTaxMinor());
            tax = Math.addExact(tax, line.taxMinor());
            net = Math.addExact(net, line.netMinor());
        }
        var sums = new HashMap<String, long[]>();
        var quantities = new HashMap<String, BigDecimal>();
        var components = new HashSet<String>();
        var allocations = new HashSet<List<String>>();
        for (var component : d.components()) {
            require(component != null);
            key(component.externalLineRef(), 128);
            key(component.sourceLineRef(), 128);
            key(component.componentRef(), 64);
            key(component.productionKind(), 32);
            key(component.goodsNo(), 32);
            key(component.skuNo(), 32);
            key(component.title(), 256);
            key(component.unit(), 16);
            key(component.allocationBasisRef(), 256);
            quantity(component.physicalQuantity());
            require(
                    component.externalKind() != null
                            && components.add(component.componentRef())
                            && Set.of("MAIN", "OPTION", "SKU", "BUNDLE_MEMBER")
                                    .contains(component.externalKind()));
            boolean main = "MAIN".equals(component.externalKind());
            require(!main || component.parentExternalLineRef() == null);
            String root = main ? component.externalLineRef() : component.parentExternalLineRef();
            var original = originals.get(root);
            require(original != null);
            require(allocations.add(List.of(component.sourceLineRef(), component.componentRef())));
            if (main) {
                require(original.unit().equals(component.unit()));
                quantities.merge(root, component.physicalQuantity(), BigDecimal::add);
            }
            money(
                    component.grossExTaxMinor(),
                    component.merchantDiscountExTaxMinor(),
                    component.taxMinor(),
                    component.netMinor());
            var sum = sums.computeIfAbsent(root, ignored -> new long[4]);
            sum[0] = Math.addExact(sum[0], component.grossExTaxMinor());
            sum[1] = Math.addExact(sum[1], component.merchantDiscountExTaxMinor());
            sum[2] = Math.addExact(sum[2], component.taxMinor());
            sum[3] = Math.addExact(sum[3], component.netMinor());
        }
        for (var line : d.invoiceLines()) {
            var sum = sums.get(line.externalLineRef());
            require(
                    sum != null
                            && sum[0] == line.grossExTaxMinor()
                            && sum[1] == line.merchantDiscountExTaxMinor()
                            && sum[2] == line.taxMinor()
                            && sum[3] == line.netMinor()
                            && quantities.containsKey(line.externalLineRef())
                            && quantities.get(line.externalLineRef()).compareTo(line.quantity())
                                    == 0);
        }
        var totals = d.totals();
        require(
                totals.grossExTaxMinor() == gross
                        && totals.merchantDiscountExTaxMinor() == discount
                        && totals.taxMinor() == tax
                        && totals.merchantChargesMinor() >= 0
                        && totals.platformSubsidyMinor() >= 0
                        && totals.platformFeesMinor() >= 0
                        && totals.externalDeliveryFeesMinor() >= 0
                        && Math.addExact(net, totals.merchantChargesMinor())
                                == totals.merchantReceivableMinor()
                        && Math.addExact(
                                        Math.addExact(
                                                Math.subtractExact(
                                                        totals.merchantReceivableMinor(),
                                                        totals.platformSubsidyMinor()),
                                                totals.platformFeesMinor()),
                                        totals.externalDeliveryFeesMinor())
                                == totals.customerPayableMinor()
                        && totals.customerPayableMinor() >= 0);
    }

    /**
     * A payment-only revision may change document hash, but the original financial/composition
     * fingerprint must match.
     */
    public static void collection(
            Reference reference, Data data, CollectionFacts facts, Clock clock) {
        require(
                facts != null
                        && reference.equals(facts.reference())
                        && data.externalOrderRef().equals(facts.externalOrderRef())
                        && hash(facts.externalDocumentHash())
                        && data.externalFinancialHash().equals(facts.externalFinancialHash())
                        && data.currency().equals(facts.currency())
                        && facts.collection() != null);
        freshness(facts.observedAt(), facts.validUntil(), clock);
        key(facts.receiptRef(), 256);
        if (facts.collection() == Collection.PLATFORM_COLLECTED) {
            require(
                    facts.collectedMinor() != null
                            && facts.collectedMinor() == data.totals().customerPayableMinor());
            key(facts.originalCollectionRef(), 128);
        } else require(facts.collectedMinor() == null);
    }

    /**
     * Acceptance of another order or changed financial contents cannot borrow the original local
     * preparation.
     */
    public static void acceptance(
            Reference reference, Data data, PlatformAcceptance facts, Clock clock) {
        require(
                facts != null
                        && reference.equals(facts.reference())
                        && data.externalOrderRef().equals(facts.externalOrderRef())
                        && hash(facts.externalDocumentHash())
                        && data.externalFinancialHash().equals(facts.externalFinancialHash())
                        && (facts.acceptedAt() == null
                                || !facts.acceptedAt().isAfter(clock.instant())));
        freshness(facts.observedAt(), facts.validUntil(), clock);
        key(facts.platformReceiptRef(), 256);
        key(facts.receiptRef(), 256);
    }

    public static void freshness(Instant observed, Instant until, Clock clock) {
        require(
                observed != null
                        && !observed.isAfter(clock.instant())
                        && until != null
                        && until.isAfter(clock.instant())
                        && !until.isAfter(observed.plusSeconds(30)));
    }

    public static void key(String value, int max) {
        require(
                value != null
                        && !value.isBlank()
                        && value.length() <= max
                        && value.equals(value.strip())
                        && value.chars().noneMatch(Character::isISOControl));
    }

    public static boolean hash(String value) {
        return value != null && value.matches("[a-f0-9]{64}");
    }

    private static void quantity(BigDecimal value) {
        require(
                value != null
                        && value.signum() > 0
                        && value.stripTrailingZeros().scale() <= 6
                        && value.compareTo(new BigDecimal("99999999999999.999999")) <= 0);
    }

    private static void money(long gross, long discount, long tax, long net) {
        require(
                gross >= 0
                        && discount >= 0
                        && discount <= gross
                        && tax >= 0
                        && Math.addExact(Math.subtractExact(gross, discount), tax) == net);
    }

    /** Policy flags require JSON booleans, rather than numeric or string coercion. */
    public static final class BooleanValue extends ValueDeserializer<Boolean> {
        @Override
        public Boolean deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() != JsonToken.VALUE_TRUE
                    && parser.currentToken() != JsonToken.VALUE_FALSE)
                return context.reportInputMismatch(Boolean.class, "Boolean token required");
            return parser.getBooleanValue();
        }
    }

    private static void require(boolean value) {
        if (!value) throw new IllegalStateException("Invalid original channel admission facts");
    }
}
