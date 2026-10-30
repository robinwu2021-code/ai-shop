package ai.neargo.shop.spi.trade;

import ai.neargo.shop.common.pricing.PricingSnapshotHash;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;

/**
 * Both local and remote adapters verify the original immutable receipt and bound refresh lifetime.
 */
public final class IndustryChannelFulfillmentSourceFacts {
    private IndustryChannelFulfillmentSourceFacts() {}

    public static void validate(
            IndustryChannelFulfillmentSourcePort.Query expected,
            IndustryChannelFulfillmentSourcePort.Facts facts,
            Instant now) {
        if (expected == null
                || expected.scope() == null
                || !"MAIN".equals(expected.scope().tenantNo())
                || !"1".equals(expected.revision())
                || facts == null
                || facts.data() == null
                || !expected.equals(facts.data().query())
                || !PricingSnapshotHash.of(facts.data()).equals(facts.contentHash())
                || !List.of("PICKED_UP", "HANDED_OVER").contains(facts.data().stage())
                || facts.data().occurredAt() == null
                || facts.data().occurredAt().isAfter(now)
                || facts.observedAt() == null
                || facts.observedAt().isAfter(now)
                || facts.data().occurredAt().isAfter(facts.observedAt())
                || facts.validUntil() == null
                || !facts.validUntil().isAfter(now)
                || facts.validUntil().isAfter(facts.observedAt().plusSeconds(30))
                || facts.data().lines() == null
                || facts.data().lines().isEmpty()
                || facts.data().lines().size() > 1000)
            throw new IllegalStateException("Invalid original fulfillment source evidence");
        text(facts.data().receiptNo(), 64);
        text(facts.verificationReceiptRef(), 128);
        for (var hash :
                List.of(
                        facts.data().originalSourceHash(),
                        facts.data().requestHash(),
                        facts.data().planHash(),
                        facts.data().inventoryReceiptHash()))
            if (hash == null || !hash.matches("[a-f0-9]{64}"))
                throw new IllegalStateException("Invalid original evidence hash");
        var seen = new HashSet<String>();
        for (var line : facts.data().lines()) {
            if (line == null) throw new IllegalStateException("Missing original line");
            text(line.sourceLineRef(), 128);
            text(line.componentRef(), 128);
            text(line.coreItemRef(), 128);
            text(line.unit(), 32);
            if (!seen.add(line.coreItemRef())
                    || line.quantity() == null
                    || !line.quantity().matches("(?:0|[1-9][0-9]{0,13})(?:\\.[0-9]{1,6})?")
                    || new BigDecimal(line.quantity()).signum() <= 0)
                throw new IllegalStateException("Invalid physical action delta");
        }
    }

    private static void text(String value, int max) {
        if (value == null
                || value.isBlank()
                || value.length() > max
                || value.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalStateException("Invalid original receipt identity");
    }
}
