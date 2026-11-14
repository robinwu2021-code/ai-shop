package ai.neargo.shop.commerce.channel;

import ai.neargo.shop.commerce.policy.CommerceValidation;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentGateway.Stage;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentSourceInternalApi;
import ai.neargo.shop.spi.commerce.ChannelFulfillmentSourcePort;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCalls;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.function.Supplier;

/**
 * A remote original receipt never becomes a guessed local handover or a second original business
 * mutation.
 */
public final class RemoteFoodChannelFulfillmentSourcePort implements ChannelFulfillmentSourcePort {
    private final Supplier<ChannelFulfillmentSourceInternalApi> api;
    private final Clock clock;

    public RemoteFoodChannelFulfillmentSourcePort(
            Supplier<ChannelFulfillmentSourceInternalApi> api, Clock clock) {
        this.api = api;
        this.clock = clock;
    }

    @Override
    public Owner owner() {
        return Owner.FOOD;
    }

    @Override
    public Facts capture(Query query) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Remote FOOD custody needs an external transaction boundary");
        }
        require(query != null);
        CommerceValidation.text(query.entityNo(), 32);
        CommerceValidation.text(query.storeNo(), 32);
        CommerceValidation.text(query.sourceOrderRef(), 32);
        CommerceValidation.text(query.coreOrderRef(), 32);
        CommerceValidation.text(query.sourceFulfillmentRef(), 32);
        CommerceValidation.text(query.sourceRevision(), 128);
        require(query.sourceRevision().matches("CUSTODY_V1:[a-f0-9]{64}"));
        var facts = ServiceCalls.call(ServiceName.FOOD, () -> api.get().capture(query));
        require(facts != null && facts.data() != null && query.equals(facts.data().query()));
        var data = facts.data();
        require(
                data.stage() == Stage.HANDED_OVER
                        && data.packageRef() == null
                        && data.carrierCode() == null
                        && data.trackingNo() == null);
        require(
                facts.observedAt() != null
                        && !facts.observedAt().isAfter(clock.instant())
                        && facts.validUntil() != null
                        && facts.validUntil().isAfter(clock.instant())
                        && !facts.validUntil().isAfter(facts.observedAt().plusSeconds(30))
                        && data.occurredAt() != null
                        && !data.occurredAt().isAfter(facts.observedAt()));
        require(
                ("FOOD:CUSTODY:" + query.sourceFulfillmentRef() + ":" + query.sourceRevision())
                        .equals(data.sourceReceiptRef()));
        require(
                ("FOOD:FULFILLMENT:" + PricingSnapshotHash.of(data))
                        .equals(facts.verificationReceiptRef()));
        require(!data.lines().isEmpty() && data.lines().size() <= 1000);
        var seen = new HashSet<List<String>>();
        for (var line : data.lines()) {
            require(line != null);
            CommerceValidation.text(line.sourceLineRef(), 128);
            CommerceValidation.text(line.componentRef(), 128);
            CommerceValidation.text(line.coreItemRef(), 128);
            CommerceValidation.text(line.unit(), 32);
            CommerceValidation.text(line.quantity(), 32);
            require(
                    line.quantity().matches("(?:0|[1-9][0-9]{0,13})(?:\\.[0-9]{1,6})?")
                            && new BigDecimal(line.quantity()).signum() > 0
                            && seen.add(
                                    List.of(
                                            line.sourceLineRef(),
                                            line.componentRef(),
                                            line.coreItemRef())));
        }
        return facts;
    }

    private static void require(boolean valid) {
        if (!valid) {
            throw new IllegalStateException("Invalid original FOOD custody receipt");
        }
    }
}
