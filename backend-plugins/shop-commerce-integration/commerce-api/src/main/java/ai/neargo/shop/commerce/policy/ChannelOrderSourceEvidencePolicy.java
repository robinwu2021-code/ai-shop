package ai.neargo.shop.commerce.policy;

import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway;
import ai.neargo.shop.spi.commerce.ChannelOrderSourceEvidencePort.Evidence;
import ai.neargo.shop.spi.commerce.ChannelOrderSourceEvidencePort.Query;

import java.time.Clock;

/** Both local and standalone owners require the same scoped, fresh, original read evidence. */
public final class ChannelOrderSourceEvidencePolicy {
    private ChannelOrderSourceEvidencePolicy() {}

    public static void query(Query query) {
        require(query != null && query.owner() != null);
        CommerceValidation.text(query.entityNo(), 32);
        CommerceValidation.text(query.storeNo(), 32);
        CommerceValidation.text(query.importNo(), 32);
        require(
                query.originalDocumentHash() != null
                        && query.originalDocumentHash().matches("[a-f0-9]{64}"));
    }

    public static Evidence validate(Query query, Evidence evidence, Clock clock) {
        query(query);
        require(
                evidence != null
                        && query.equals(evidence.scope())
                        && evidence.original() != null
                        && evidence.routing() != null
                        && evidence.current() != null);
        var command = evidence.original();
        var route = evidence.routing();
        var original = command.original();
        require(
                original != null
                        && original.document() != null
                        && query.entityNo().equals(command.entityNo())
                        && query.storeNo().equals(command.storeNo())
                        && query.importNo().equals(command.importNo())
                        && query.originalDocumentHash().equals(original.documentHash())
                        && original.documentHash()
                                .equals(PricingSnapshotHash.of(original.document()))
                        && command.connectionNo().equals(route.connectionNo())
                        && command.connectionRevisionNo().equals(route.connectionRevisionNo()));
        CommerceValidation.text(evidence.operationNo(), 32);
        CommerceValidation.text(evidence.attemptRef(), 32);
        CommerceValidation.text(route.providerCode(), 32);
        CommerceValidation.text(route.profileNo(), 32);
        require(
                route.channelStoreKey() != null
                        && route.channelStoreKey().matches("[a-f0-9]{64}")
                        && route.market() != null
                        && route.market().matches("[A-Z]{2}"));
        var document = original.document();
        ChannelOrderPolicy.validate(
                new ChannelOrderGateway.Query(
                        query.entityNo(),
                        query.storeNo(),
                        route.connectionNo(),
                        route.profileNo(),
                        document.externalAccountRef(),
                        document.externalStoreRef(),
                        document.externalOrderRef(),
                        null,
                        null,
                        null),
                evidence.current(),
                clock);
        return evidence;
    }

    private static void require(boolean value) {
        if (!value) throw new IllegalStateException("Invalid original channel order evidence");
    }
}
