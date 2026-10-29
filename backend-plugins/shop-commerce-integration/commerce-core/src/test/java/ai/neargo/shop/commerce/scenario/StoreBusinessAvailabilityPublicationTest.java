package ai.neargo.shop.commerce.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import ai.neargo.shop.commerce.dto.AvailabilityContracts.StorePublish;
import ai.neargo.shop.commerce.mapper.AvailabilityPublicationMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.ResourceMappingMapper;
import ai.neargo.shop.commerce.policy.ChannelIdentity;
import ai.neargo.shop.commerce.service.impl.CatalogPersistence;
import ai.neargo.shop.commerce.service.impl.ChannelAvailabilityServiceImpl;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.commerce.ChannelAvailabilityGateway;
import ai.neargo.shop.spi.commerce.ChannelAvailabilitySourcePort;
import ai.neargo.shop.spi.commerce.ChannelConnectionIdentityPort;
import ai.neargo.shop.spi.commerce.ChannelResourceMappingPort;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

class StoreBusinessAvailabilityPublicationTest {
    static class Source implements ChannelAvailabilitySourcePort {
        State state = State.OPEN;
        boolean unavailable;
        int captures;

        public Kind kind() {
            return Kind.STORE;
        }

        public Owner owner() {
            return Owner.FOOD;
        }

        public Facts capture(Query query) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            captures++;
            if (unavailable) throw new IllegalStateException("Original owner unavailable");
            var now = Instant.now();
            return new Facts(
                    new Data(query, state, null, null, "ORIGINAL_STORE_REVISION"),
                    now,
                    now.plusSeconds(25),
                    "ORIGINAL_CALENDAR_RECEIPT");
        }
    }

    static class Gateway implements ChannelAvailabilityGateway {
        int sends, queries;
        Publication sent, queried;

        public String profileNo() {
            return "PP";
        }

        public Set<Capability> capabilities() {
            return Set.of(Capability.AVAILABILITY);
        }

        public boolean supportsAvailability(ChannelAvailabilitySourcePort.Kind kind) {
            return kind == ChannelAvailabilitySourcePort.Kind.STORE;
        }

        public boolean supportsStoreMode(ChannelAvailabilitySourcePort.StoreMode mode) {
            return mode != null;
        }

        public Result publishAvailability(Publication p) {
            sends++;
            sent = p;
            return new Result(p.operationNo(), PricingSnapshotHash.of(p), Status.UNKNOWN, null);
        }

        public Result queryAvailability(Publication p) {
            queries++;
            queried = p;
            return new Result(
                    p.operationNo(),
                    PricingSnapshotHash.of(p),
                    Status.CONFIRMED,
                    "ACTUAL_SUPPLIER_STORE_RECEIPT");
        }
    }

    final StorePublish request =
            new StorePublish("CON", ChannelAvailabilitySourcePort.StoreMode.ACCEPTING_ORDERS);

    void run(
            Source source,
            Gateway gateway,
            BiConsumer<CatalogSynchronizationTest.State, ChannelAvailabilityServiceImpl> test) {
        new CatalogSynchronizationTest()
                .runWithoutPublishing(
                        s -> {
                            s.sql()
                                    .update(
                                            "UPDATE cim_authorization_revision SET"
                                                    + " granted_scopes='[\"AVAILABILITY\"]'");
                            s.sql()
                                    .update(
                                            "UPDATE cim_store_connection SET channel_store_key=?",
                                            ChannelIdentity.storeKey(
                                                    "test:TW",
                                                    false,
                                                    "REMOTE_ACCOUNT",
                                                    "REMOTE_STORE"));
                            var mappings = mock(ResourceMappingMapper.class);
                            var mappingAuthority = mock(ChannelResourceMappingPort.class);
                            ChannelConnectionIdentityPort identity =
                                    expected -> {
                                        assertThat(
                                                        TransactionSynchronizationManager
                                                                .isActualTransactionActive())
                                                .isFalse();
                                        var now = Instant.now();
                                        return new ChannelConnectionIdentityPort.Proof(
                                                ChannelConnectionIdentityPort.Status.VERIFIED,
                                                expected,
                                                "ORIGINAL_STORE_IDENTITY",
                                                now,
                                                now.plusSeconds(25));
                                    };
                            var service =
                                    new ChannelAvailabilityServiceImpl(
                                            s.context().getBean(CatalogPersistence.class),
                                            s.context()
                                                    .getBean(AvailabilityPublicationMapper.class),
                                            mappings,
                                            mappingAuthority,
                                            () -> List.of(source),
                                            List.of(gateway),
                                            Clock.systemUTC(),
                                            () -> identity);
                            test.accept(s, service);
                            verifyNoInteractions(mappings, mappingAuthority);
                        });
    }

    @Test
    void storeIdentityNeedsNoSkuMappingAndUncertainRecoveryQueriesOnlyOriginalFrozenGeneration() {
        var source = new Source();
        var gateway = new Gateway();
        run(
                source,
                gateway,
                (s, service) -> {
                    service.publishStore("E", "S", "A", request, "STORE_KEY");
                    assertThat(gateway.sends).isZero();
                    service.recoverDue(10);
                    assertThat(gateway.sends).isOne();
                    assertThat(gateway.sent.mappingNo()).isNull();
                    assertThat(gateway.sent.identity().externalRef()).isEqualTo("REMOTE_STORE");
                    int calls = source.captures;
                    source.unavailable = true;
                    s.sql().update("UPDATE cim_account SET active_authorization_no='ROTATED'");
                    s.sql()
                            .update(
                                    "UPDATE cim_operation SET next_recovery_at='2000-01-01"
                                            + " 00:00:00'");
                    service.recoverDue(10);
                    assertThat(gateway.queries).isOne();
                    assertThat(gateway.queried).isEqualTo(gateway.sent);
                    assertThat(source.captures).isEqualTo(calls);
                    assertThat(gateway.sends).isOne();
                    assertThat(
                                    s.sql()
                                            .queryForList(
                                                    "SELECT attempt_kind FROM cim_operation_attempt"
                                                            + " ORDER BY id",
                                                    String.class))
                            .containsExactly("SEND", "QUERY");
                });
    }

    @Test
    void
            changedSourceBeforeFirstSendInvalidatesIntentThenSameRecoveryContributorCreatesActualNextGeneration() {
        var source = new Source();
        var gateway = new Gateway();
        run(
                source,
                gateway,
                (s, service) -> {
                    service.publishStore("E", "S", "A", request, "STORE_KEY");
                    source.state = ChannelAvailabilitySourcePort.State.SUSPENDED;
                    service.recoverDue(10);
                    assertThat(gateway.sends).isZero();
                    assertThat(
                                    s.sql()
                                            .queryForObject(
                                                    "SELECT status FROM cim_operation",
                                                    String.class))
                            .isEqualTo("FAILED");
                    s.sql()
                            .update(
                                    "UPDATE cim_availability_publication SET"
                                            + " next_source_check_at='2000-01-01 00:00:00'");
                    service.recoverDue(10);
                    assertThat(gateway.sends).isOne();
                    assertThat(gateway.sent.generation()).isEqualTo(2);
                    assertThat(gateway.sent.source().state())
                            .isEqualTo(ChannelAvailabilitySourcePort.State.SUSPENDED);
                    assertThat(
                                    s.sql()
                                            .queryForObject(
                                                    "SELECT COUNT(*) FROM"
                                                            + " cim_availability_publication",
                                                    Integer.class))
                            .isEqualTo(2);
                });
    }

    @Test
    void unresolvedOriginalSendBlocksAnotherStoreGenerationAndMissingGrantCannotPublish() {
        var source = new Source();
        var gateway = new Gateway();
        run(
                source,
                gateway,
                (s, service) -> {
                    service.publishStore("E", "S", "A", request, "FIRST");
                    service.recoverDue(10);
                    assertThatThrownBy(() -> service.publishStore("E", "S", "A", request, "SECOND"))
                            .isInstanceOf(RuntimeException.class);
                    s.sql()
                            .update(
                                    "UPDATE cim_authorization_revision SET"
                                            + " granted_scopes='[\"CATALOG\"]'");
                    assertThatThrownBy(
                                    () ->
                                            service.publishStore(
                                                    "E",
                                                    "S",
                                                    "A",
                                                    new StorePublish(
                                                            "CON",
                                                            ChannelAvailabilitySourcePort.StoreMode
                                                                    .PHYSICAL_OPEN),
                                                    "MISSING_AVAILABILITY_GRANT"))
                            .isInstanceOf(RuntimeException.class);
                });
    }
}
