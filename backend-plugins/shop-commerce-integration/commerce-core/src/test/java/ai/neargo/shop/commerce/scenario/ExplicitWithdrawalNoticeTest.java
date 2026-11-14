package ai.neargo.shop.commerce.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.commerce.config.CommerceCatalogWithdrawalAutoConfiguration;
import ai.neargo.shop.commerce.dto.CatalogContracts.CreateCatalogScopeReq;
import ai.neargo.shop.commerce.dto.CatalogContracts.SaveSyncPolicyReq;
import ai.neargo.shop.commerce.dto.CatalogContracts.SourceReq;
import ai.neargo.shop.commerce.dto.SourceNoticeContracts;
import ai.neargo.shop.commerce.service.SourceNoticeService;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import ai.neargo.shop.spi.commerce.ChannelSourceWithdrawalProvider;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

class ExplicitWithdrawalNoticeTest {
    static class NativeOwner implements ChannelSourceWithdrawalProvider {
        final AtomicBoolean disabled = new AtomicBoolean();
        final Instant withdrawnAt = Instant.now().minusSeconds(1);

        public Owner owner() {
            return Owner.FOOD;
        }

        public Facts capture(Query query) {
            if (!disabled.get()) throw new IllegalStateException("original head not disabled");
            var data =
                    new Data(
                            query,
                            "DISABLED:2:ACTUAL_ACTION",
                            "a".repeat(64),
                            "ACTUAL_ACTION",
                            "b".repeat(64),
                            withdrawnAt);
            var now = Instant.now();
            return new Facts(
                    data,
                    PricingSnapshotHash.of(data),
                    now,
                    now.plusSeconds(25),
                    "ORIGINAL_ACTION_PROOF");
        }

        public Disposition disposition(Query query) {
            var now = Instant.now();
            return new Disposition(
                    query,
                    disabled.get() ? State.WITHDRAWN : State.ACTIVE,
                    disabled.get() ? capture(query) : null,
                    now,
                    now.plusSeconds(25),
                    "ORIGINAL_HEAD");
        }
    }

    void run(BiConsumer<CatalogSynchronizationTest.State, NativeOwner> test) {
        var owner = new NativeOwner();
        new CatalogSynchronizationTest()
                .runWith(
                        runner ->
                                runner.withConfiguration(
                                                AutoConfigurations.of(
                                                        CommerceCatalogWithdrawalAutoConfiguration
                                                                .class))
                                        .withBean(
                                                ChannelSourceWithdrawalProvider.class, () -> owner)
                                        .withPropertyValues(
                                                "shop.commerce.catalog.withdrawal.enabled=true"),
                        state -> {
                            state.source().evidenceTtlSeconds = 25;
                            var scope =
                                    state.scopes()
                                            .createScope(
                                                    "E",
                                                    "S",
                                                    "A",
                                                    new CreateCatalogScopeReq(
                                                            "CON",
                                                            "STORE",
                                                            "Menu",
                                                            List.of(
                                                                    new SourceReq(
                                                                            Owner.FOOD,
                                                                            "MENU",
                                                                            "R1",
                                                                            0))),
                                                    "scope");
                            state.policies()
                                    .savePolicy(
                                            "E",
                                            "S",
                                            scope.scopeNo(),
                                            "A",
                                            new SaveSyncPolicyReq(
                                                    0L, true, true, false, false, 0, "QUERY_ONLY"),
                                            "enable");
                            test.accept(state, owner);
                        });
    }

    @Test
    void explicitNativeDisablePersistsProofBeforeQueuingAndRetainsOriginalSourceSubscription() {
        run(
                (state, owner) -> {
                    owner.disabled.set(true);
                    state.source().outage = true;
                    var notices = state.context().getBean(SourceNoticeService.class);
                    notices.receive(
                            new SourceNoticeContracts.Notice(
                                    "PLATFORM",
                                    "ACTUAL_DISABLE",
                                    "E",
                                    "S",
                                    Owner.FOOD,
                                    "MENU",
                                    Instant.now().minusSeconds(1),
                                    "WITHDRAWAL"));
                    assertThat(notices.recoverDue(10)).isOne();
                    assertThat(
                                    state.sql()
                                            .queryForObject(
                                                    "SELECT COUNT(*) FROM cim_source_withdrawal"
                                                            + " WHERE status='CURRENT'",
                                                    Integer.class))
                            .isOne();
                    assertThat(
                                    state.sql()
                                            .queryForObject(
                                                    "SELECT selection_revision FROM"
                                                            + " cim_catalog_source WHERE"
                                                            + " status='ACTIVE'",
                                                    String.class))
                            .isEqualTo("R1");
                    assertThat(
                                    state.sql()
                                            .queryForObject(
                                                    "SELECT remote_consistency FROM"
                                                            + " cim_catalog_scope",
                                                    String.class))
                            .isEqualTo("STALE");
                    assertThat(state.gateway().sends).hasValue(0);
                });
    }

    @Test
    void ordinaryValidationFailureNeverAcquiresWithdrawalPermissionEvenIfOwnerCouldProveDisabled() {
        run(
                (state, owner) -> {
                    owner.disabled.set(true);
                    state.source().outage = true;
                    var notices = state.context().getBean(SourceNoticeService.class);
                    notices.receive(
                            new SourceNoticeContracts.Notice(
                                    "PLATFORM",
                                    "ORDINARY_CHANGE",
                                    "E",
                                    "S",
                                    Owner.FOOD,
                                    "MENU",
                                    Instant.now().minusSeconds(1)));
                    assertThat(notices.recoverDue(10)).isZero();
                    assertThat(
                                    state.sql()
                                            .queryForObject(
                                                    "SELECT COUNT(*) FROM cim_source_withdrawal",
                                                    Integer.class))
                            .isZero();
                    assertThat(
                                    state.sql()
                                            .queryForObject(
                                                    "SELECT status FROM cim_source_notice",
                                                    String.class))
                            .isEqualTo("PENDING");
                    assertThat(state.gateway().sends).hasValue(0);
                });
    }
}
