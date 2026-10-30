package ai.neargo.shop.commerce.service.impl;

import ai.neargo.shop.commerce.dto.CatalogContracts.SourceReq;
import ai.neargo.shop.commerce.policy.CatalogSourceTransportPolicy;
import ai.neargo.shop.commerce.policy.SourceWithdrawalPolicy;
import ai.neargo.shop.spi.commerce.ChannelCatalogSourcePort.ValidatedSource;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Kind;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Source;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Status;
import ai.neargo.shop.spi.commerce.ChannelSourceWithdrawalProvider;

import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Pure proof partition: only a separately persisted, native-revalidated withdrawal can omit a
 * configured source.
 */
public final class CatalogSourceDispositionResolver {
    private final ChannelSourceValidationPort positive;
    private final Map<Owner, ChannelSourceWithdrawalProvider> withdrawals;
    private final Clock clock;

    public CatalogSourceDispositionResolver(
            ChannelSourceValidationPort positive,
            List<ChannelSourceWithdrawalProvider> providers,
            Clock clock) {
        this.positive = positive;
        this.clock = clock;
        var owners = new EnumMap<Owner, ChannelSourceWithdrawalProvider>(Owner.class);
        for (var provider : providers)
            if (provider == null
                    || provider.owner() == null
                    || owners.putIfAbsent(provider.owner(), provider) != null)
                throw new IllegalStateException("Duplicate original source withdrawal owner");
        withdrawals = Map.copyOf(owners);
    }

    public record Partition(
            List<ValidatedSource> active, List<ChannelSourceWithdrawalProvider.Facts> withdrawn) {
        public Partition {
            active = List.copyOf(active);
            withdrawn = List.copyOf(withdrawn);
        }
    }

    public Partition partition(
            String entity,
            String store,
            Owner owner,
            List<SourceReq> configured,
            List<ChannelSourceWithdrawalProvider.Data> previouslyVerified) {
        require(
                owner != null
                        && configured != null
                        && !configured.isEmpty()
                        && configured.size() <= 50
                        && previouslyVerified != null);
        var active = new ArrayList<ValidatedSource>();
        var removed = new ArrayList<ChannelSourceWithdrawalProvider.Facts>();
        var seen = new HashSet<String>();
        for (var source : configured) {
            require(
                    source != null
                            && source.sourceOwner() == owner
                            && source.priority() != null
                            && source.priority() >= 0
                            && source.priority() <= 10000
                            && seen.add(source.sourceRef()));
            var q =
                    new Source(
                            entity,
                            store,
                            owner,
                            owner == Owner.FOOD ? Kind.MENU : Kind.SELECTION,
                            source.sourceRef(),
                            source.selectionRevision());
            CatalogSourceTransportPolicy.validateSource(q);
            var withdrawalQuery =
                    new ChannelSourceWithdrawalProvider.Query(
                            entity, store, owner, source.sourceRef(), source.selectionRevision());
            var explicit =
                    previouslyVerified.stream()
                            .filter(d -> d != null && withdrawalQuery.equals(d.query()))
                            .toList();
            require(explicit.size() <= 1);
            if (!explicit.isEmpty()) {
                var provider = withdrawals.get(owner);
                require(provider != null);
                var current = provider.capture(withdrawalQuery);
                SourceWithdrawalPolicy.validate(withdrawalQuery, current, clock);
                require(explicit.getFirst().equals(current.data()));
                removed.add(current);
            } else {
                // No catch/fallback: every ordinary failed positive validation continues to block
                // the aggregate.
                var proof = positive.validate(q);
                CatalogSourceTransportPolicy.validateProof(q, proof, clock);
                require(proof.status() == Status.VALID);
                active.add(
                        new ValidatedSource(
                                owner,
                                source.sourceRef(),
                                source.selectionRevision(),
                                source.priority(),
                                proof));
            }
        }
        return new Partition(active, removed);
    }

    private static void require(boolean value) {
        if (!value) throw new IllegalStateException("Original source disposition is not proven");
    }
}
