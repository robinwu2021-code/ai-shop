package ai.neargo.shop.food.commerce.port;

import ai.neargo.shop.commerce.policy.SourceWithdrawalPolicy;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.food.dto.MenuWithdrawalSourceContracts;
import ai.neargo.shop.food.service.MenuWithdrawalSourceService;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import ai.neargo.shop.spi.commerce.ChannelSourceWithdrawalProvider;

import java.time.Clock;
import java.util.Set;

/** An original DISABLED menu is not a VALID menu, nor a missing-menu shortcut. */
public final class FoodSourceWithdrawalProvider implements ChannelSourceWithdrawalProvider {
    private final MenuWithdrawalSourceService original;
    private final Clock clock;

    public FoodSourceWithdrawalProvider(MenuWithdrawalSourceService original, Clock clock) {
        this.original = original;
        this.clock = clock;
    }

    @Override
    public Owner owner() {
        return Owner.FOOD;
    }

    @Override
    public Disposition disposition(Query query) {
        SourceWithdrawalPolicy.query(query);
        if (query.owner() != Owner.FOOD)
            throw new IllegalArgumentException("Wrong withdrawal owner");
        var expected =
                new MenuWithdrawalSourceContracts.Query(
                        query.entityNo(),
                        query.storeNo(),
                        query.sourceRef(),
                        query.previousRevision());
        var actual = original.disposition(expected);
        if (actual == null || !expected.equals(actual.query()) || actual.headVersion() < 0)
            throw new IllegalStateException("Missing original menu disposition");
        boolean disabled = "DISABLED".equals(actual.headStatus());
        if (!disabled && !Set.of("DRAFT", "PUBLISHED").contains(actual.headStatus()))
            throw new IllegalStateException("Unknown original menu state");
        var result =
                new Disposition(
                        query,
                        disabled ? State.WITHDRAWN : State.ACTIVE,
                        disabled ? convert(query, actual.withdrawal()) : null,
                        actual.verifiedAt(),
                        actual.validUntil(),
                        actual.receiptNo());
        SourceWithdrawalPolicy.disposition(query, result, clock);
        return result;
    }

    @Override
    public Facts capture(Query query) {
        SourceWithdrawalPolicy.query(query);
        if (query.owner() != Owner.FOOD)
            throw new IllegalArgumentException("Wrong withdrawal owner");
        var expected =
                new MenuWithdrawalSourceContracts.Query(
                        query.entityNo(),
                        query.storeNo(),
                        query.sourceRef(),
                        query.previousRevision());
        var proof = original.capture(expected);
        return convert(query, proof);
    }

    private Facts convert(Query query, MenuWithdrawalSourceContracts.Proof proof) {
        var expected =
                new MenuWithdrawalSourceContracts.Query(
                        query.entityNo(),
                        query.storeNo(),
                        query.sourceRef(),
                        query.previousRevision());
        if (proof == null
                || proof.data() == null
                || !expected.equals(proof.data().query())
                || proof.data().menuVersion() < 0)
            throw new IllegalStateException("Missing original menu withdrawal");
        var data =
                new Data(
                        query,
                        "DISABLED:" + proof.data().menuVersion() + ":" + proof.data().actionNo(),
                        proof.data().previousContentHash(),
                        proof.data().actionNo(),
                        proof.data().actionHash(),
                        proof.data().withdrawnAt());
        var facts =
                new Facts(
                        data,
                        PricingSnapshotHash.of(data),
                        proof.verifiedAt(),
                        proof.validUntil(),
                        proof.receiptNo());
        SourceWithdrawalPolicy.validate(query, facts, clock);
        return facts;
    }
}
