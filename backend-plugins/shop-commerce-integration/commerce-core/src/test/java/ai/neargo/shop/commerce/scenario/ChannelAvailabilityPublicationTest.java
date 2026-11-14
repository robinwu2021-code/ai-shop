package ai.neargo.shop.commerce.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.commerce.dto.AvailabilityContracts;
import ai.neargo.shop.commerce.entity.CimResourceMapping;
import ai.neargo.shop.commerce.mapper.AvailabilityPublicationMapper;
import ai.neargo.shop.commerce.mapper.CommerceMappers.ResourceMappingMapper;
import ai.neargo.shop.commerce.service.impl.CatalogPersistence;
import ai.neargo.shop.commerce.service.impl.ChannelAvailabilityServiceImpl;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.commerce.ChannelAvailabilityGateway;
import ai.neargo.shop.spi.commerce.ChannelAvailabilitySourcePort;
import ai.neargo.shop.spi.commerce.ChannelAvailabilitySourcePort.Data;
import ai.neargo.shop.spi.commerce.ChannelAvailabilitySourcePort.Facts;
import ai.neargo.shop.spi.commerce.ChannelAvailabilitySourcePort.Kind;
import ai.neargo.shop.spi.commerce.ChannelAvailabilitySourcePort.Query;
import ai.neargo.shop.spi.commerce.ChannelAvailabilitySourcePort.State;
import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway;
import ai.neargo.shop.spi.commerce.ChannelResourceMappingPort;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

class ChannelAvailabilityPublicationTest {
    final AvailabilityContracts.Publish request =
            new AvailabilityContracts.Publish("CON", "MAP", "MR");
    final ChannelResourceIdentityGateway.Identity identity =
            new ChannelResourceIdentityGateway.Identity("REMOTE_SKU", List.of());

    static class Source implements ChannelAvailabilitySourcePort {
        int calls;
        boolean unavailable;
        String quantity = "1.25";

        public Kind kind() {
            return Kind.STOCK;
        }

        public Facts capture(Query query) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            calls++;
            if (unavailable) throw new IllegalStateException("Owner unavailable");
            var now = Instant.now();
            return new Facts(
                    new Data(query, State.QUANTITY, quantity, "KG", "R1"),
                    now,
                    now.plusSeconds(25),
                    "ORIGINAL_INVENTORY");
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

        public boolean supportsAvailability(Kind kind) {
            return kind == Kind.STOCK;
        }

        public Result publishAvailability(Publication p) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            sends++;
            sent = p;
            return new Result(p.operationNo(), PricingSnapshotHash.of(p), Status.UNKNOWN, null);
        }

        public Result queryAvailability(Publication p) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            queries++;
            queried = p;
            return new Result(
                    p.operationNo(),
                    PricingSnapshotHash.of(p),
                    Status.CONFIRMED,
                    "MOCK_AVAILABILITY_RECEIPT");
        }
    }

    void run(
            BiConsumer<CatalogSynchronizationTest.State, ChannelAvailabilityServiceImpl> assertion,
            Source source,
            Gateway gateway) {
        new CatalogSynchronizationTest()
                .runWithoutPublishing(
                        s -> {
                            s.sql()
                                    .execute(
                                            "UPDATE cim_authorization_revision SET"
                                                + " granted_scopes='[\"CATALOG\",\"AVAILABILITY\"]'");
                            var mappings = mock(ResourceMappingMapper.class);
                            var map = new CimResourceMapping();
                            map.setActiveRevisionNo("MR");
                            map.setExternalRef("REMOTE_SKU");
                            map.setExternalIdentityHash("SKU_HASH");
                            when(mappings.selectOne(any())).thenReturn(map);
                            ChannelResourceMappingPort authority =
                                    ref ->
                                            new ChannelResourceMappingPort.Resolution(
                                                    "MAP",
                                                    "MR",
                                                    ChannelResourceIdentityGateway.ObjectType.SKU,
                                                    identity,
                                                    ChannelSourceValidationPort.Owner.RETAIL,
                                                    "SKU",
                                                    "ORIGINAL_SKU_REV",
                                                    null,
                                                    "a".repeat(64),
                                                    "HISTORICAL",
                                                    "FRESH");
                            var service =
                                    new ChannelAvailabilityServiceImpl(
                                            s.context().getBean(CatalogPersistence.class),
                                            s.context()
                                                    .getBean(AvailabilityPublicationMapper.class),
                                            mappings,
                                            authority,
                                            () -> source == null ? List.of() : List.of(source),
                                            List.of(gateway),
                                            Clock.systemUTC());
                            assertion.accept(s, service);
                        });
    }

    @Test
    void timeoutRecoveryQueriesTheFrozenPayloadEvenAfterSourceAndGrantChange() {
        var source = new Source();
        var gateway = new Gateway();
        run(
                (s, service) -> {
                    var op = service.publishStock("E", "S", "A", request, "KEY");
                    assertThat(gateway.sends).isZero();
                    assertThat(service.recoverDue(10)).isZero();
                    assertThat(gateway.sends).isOne();
                    source.unavailable = true;
                    int calls = source.calls;
                    s.sql().execute("UPDATE cim_account SET active_authorization_no='ROTATED'");
                    s.sql()
                            .execute(
                                    "UPDATE cim_operation SET next_recovery_at='2000-01-01"
                                            + " 00:00:00'");
                    assertThat(service.recoverDue(10)).isOne();
                    assertThat(gateway.sends).isOne();
                    assertThat(gateway.queries).isOne();
                    assertThat(source.calls).isEqualTo(calls);
                    assertThat(gateway.queried).isEqualTo(gateway.sent);
                    assertThat(
                                    s.sql()
                                            .queryForList(
                                                    "SELECT attempt_kind FROM cim_operation_attempt"
                                                            + " ORDER BY id",
                                                    String.class))
                            .containsExactly("SEND", "QUERY");
                    assertThat(
                                    s.sql()
                                            .queryForObject(
                                                    "SELECT status FROM"
                                                            + " cim_availability_publication",
                                                    String.class))
                            .isEqualTo("SUCCEEDED");
                },
                source,
                gateway);
    }

    @Test
    void changedOwnerEvidenceBlocksSendAndUnknownOwnerCannotCreateAnIntent() {
        var source = new Source();
        var gateway = new Gateway();
        run(
                (s, service) -> {
                    service.publishStock("E", "S", "A", request, "KEY");
                    source.quantity = "2";
                    assertThat(service.recoverDue(10)).isZero();
                    assertThat(gateway.sends).isZero();
                    assertThat(
                                    s.sql()
                                            .queryForObject(
                                                    "SELECT COUNT(*) FROM cim_operation_attempt",
                                                    Integer.class))
                            .isZero();
                },
                source,
                gateway);
        run(
                (s, service) -> {
                    assertThatThrownBy(() -> service.publishStock("E", "S", "A", request, "KEY"))
                            .isInstanceOf(UnsupportedOperationException.class);
                    assertThat(
                                    s.sql()
                                            .queryForObject(
                                                    "SELECT COUNT(*) FROM"
                                                            + " cim_availability_publication",
                                                    Integer.class))
                            .isZero();
                },
                null,
                new Gateway());
    }

    @Test
    void unresolvedRemoteWriteBlocksANewerValueForTheSameOriginalResource() {
        var source = new Source();
        var gateway = new Gateway();
        run(
                (s, service) -> {
                    service.publishStock("E", "S", "A", request, "KEY");
                    service.recoverDue(10);
                    source.quantity = "2";
                    assertThatThrownBy(
                                    () -> service.publishStock("E", "S", "A", request, "NEW_KEY"))
                            .isInstanceOf(RuntimeException.class);
                    assertThat(gateway.sends).isOne();
                    assertThat(
                                    s.sql()
                                            .queryForObject(
                                                    "SELECT COUNT(*) FROM"
                                                            + " cim_availability_publication",
                                                    Integer.class))
                            .isOne();
                },
                source,
                gateway);
    }
}
