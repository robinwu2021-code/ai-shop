package ai.neargo.shop.commerce.channel;

import ai.neargo.shop.commerce.policy.SourceWithdrawalPolicy;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import ai.neargo.shop.spi.commerce.ChannelSourceWithdrawalInternalApi;
import ai.neargo.shop.spi.commerce.ChannelSourceWithdrawalProvider;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCalls;

import java.time.Clock;
import java.util.function.Supplier;

public final class RemoteFoodSourceWithdrawalProvider implements ChannelSourceWithdrawalProvider {
    private final Supplier<ChannelSourceWithdrawalInternalApi> api;
    private final Clock clock;

    public RemoteFoodSourceWithdrawalProvider(
            Supplier<ChannelSourceWithdrawalInternalApi> api, Clock clock) {
        this.api = api;
        this.clock = clock;
    }

    @Override
    public Owner owner() {
        return Owner.FOOD;
    }

    @Override
    public Facts capture(Query query) {
        SourceWithdrawalPolicy.query(query);
        if (query.owner() != Owner.FOOD) throw new IllegalArgumentException("Wrong source owner");
        var proof = ServiceCalls.call(ServiceName.FOOD, () -> api.get().capture(query));
        SourceWithdrawalPolicy.validate(query, proof, clock);
        return proof;
    }

    @Override
    public Disposition disposition(Query query) {
        SourceWithdrawalPolicy.query(query);
        if (query.owner() != Owner.FOOD) throw new IllegalArgumentException("Wrong source owner");
        var proof = ServiceCalls.call(ServiceName.FOOD, () -> api.get().disposition(query));
        SourceWithdrawalPolicy.disposition(query, proof, clock);
        return proof;
    }
}
