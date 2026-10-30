package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.event.OutboxConsumer;
import ai.neargo.shop.event.outbox.runtime.JdbcOutboxStore;
import ai.neargo.shop.event.outbox.runtime.OutboxFingerprint;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort;
import ai.neargo.shop.spi.trade.ExecutionEvidenceLookup;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

/** Actual offline adoption requires its own fresh native HOST with no preexisting unified rows. */
@EnabledIfSystemProperty(named = "beauty.execution.test.native", matches = "true")
@EnabledIfEnvironmentVariable(named = "C04B2B_ADOPTION_NATIVE_MYSQL_URL", matches = ".+")
@TestPropertySource(properties = {
    "spring.datasource.url=${C04B2B_ADOPTION_NATIVE_MYSQL_URL}",
    "shop.outbox.sources.platform.mode=legacy", "shop.outbox.sources.inventory.mode=legacy",
    "shop.outbox.enabled=false", "shop.outbox.pump.enabled=false", "shop.outbox.legacy-scan.enabled=false"
})
class NativeAdoptedDualExecutionStockRetentionIT extends DualExecutionStockRetentionFixture {
    @Autowired @Qualifier("dataSource") javax.sql.DataSource host;
    @Autowired @Qualifier("transactionManager") PlatformTransactionManager hostTx;
    @Autowired ObjectProvider<OutboxConsumer> actualConsumers;

    @Test
    void completeActualLegacyManifestIsAdoptedPendingAndMirrorVerifiesNativeFingerprintAndSeparateChecksum()
            throws Exception {
        try (var connection = host.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_outbox WHERE source_system IS NOT NULL",
                Long.class)).as("Offline adoption uses a dedicated fresh native source").isZero();
        var request = prepared(true);
        var store = new JdbcOutboxStore(JdbcOutboxStore.Source.PLATFORM, host, hostTx, json);
        var records = store.inspectLegacy(500);
        assertThat(records).isNotEmpty();
        assertThat((long) records.size()).isEqualTo(store.unadoptedCount());
        var consumers = actualConsumers.orderedStream().toList();
        var routing = new ai.neargo.shop.config.OutboxEventRoutes(json, sub -> {
            var roots = jdbc.queryForList("SELECT order_no FROM ord_sub_order WHERE sub_order_no=?", String.class, sub);
            assertThat(roots).hasSize(1);
            return roots.getFirst();
        });
        var decisions = records.stream().map(record -> {
            var states = new LinkedHashMap<String, String>();
            var external = new LinkedHashMap<String, Boolean>();
            consumers.stream().filter(consumer -> consumer.supports(record.eventType())).forEach(consumer -> {
                states.put(consumer.handlerId(), "PENDING");
                external.put(consumer.handlerId(), consumer.externalWorkflow());
            });
            assertThat(states).as("Every adopted event retains only its actual supported handler targets: %s",
                    record.eventType()).isNotEmpty();
            var body = json.readTree(jdbc.queryForObject("SELECT payload FROM sys_outbox WHERE event_no=?",
                    String.class, record.eventNo()));
            // This fixture emits single-root source events; grouped arrivals require a separately reviewed manifest.
            assertThat(body.has("subOrderNos")).isFalse();
            var publications = routing.expand("PLATFORM", new LegacyRoutingView(record, body));
            assertThat(publications).hasSize(1);
            String stream = publications.getFirst().options().streamKey();
            return new JdbcOutboxStore.LegacyDecision(record, stream, states, external,
                    "Native fixture reviews complete immutable legacy bytes; actual targets remain pending");
        }).toList();
        assertThat(store.adoptLegacy(decisions, true, Instant.now())).isEqualTo(records.size());
        var mirror = mirrorEvidence.freeze(request.lookup(), evidence.queryOriginalEvidence(
                new ExecutionEvidenceLookup(request.reference())));
        assertThat(mirror.events()).allSatisfy(event -> {
            assertThat(event.sourceSystem()).isEqualTo("PLATFORM");
            assertThat(event.payloadHash()).isNotEqualTo(event.rawPayloadSha256())
                    .isNotEqualTo(event.adoptionChecksum());
            assertThat(event.adoptionChecksum()).isEqualTo(OutboxFingerprint.legacyChecksum(json,
                    event.aggregateType(), event.aggregateId(), event.eventType(), event.payload(), event.createdAt()));
            assertThat(records.stream().filter(record -> record.eventNo().equals(event.eventNo())).findFirst()
                    .orElseThrow().checksum()).isEqualTo(event.adoptionChecksum());
        });
        assertThat(current(() -> aggregate.retainV2(request)).outcome())
                .isEqualTo(ExecutionStockRetentionPort.Outcome.RETAINED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_outbox_delivery d JOIN sys_outbox e"
                + " ON e.event_no=d.event_no WHERE e.aggregate_id=? AND d.handler_id='inventory-mirror'"
                + " AND d.status='PENDING'", Integer.class, request.stockOriginal().orderNo())).isPositive();
    }
    /** Routes the actual persisted legacy body through the production selector, without claiming owner effects. */
    private record LegacyRoutingView(JdbcOutboxStore.LegacyRecord original, tools.jackson.databind.JsonNode body)
            implements ai.neargo.shop.event.DomainEvent {
        @com.fasterxml.jackson.annotation.JsonValue
        public tools.jackson.databind.JsonNode payload() {
            return body;
        }
        @Override
        public String aggregateType() {
            return original.aggregateType();
        }
        @Override
        public String aggregateId() {
            return original.aggregateId();
        }
        @Override
        public String eventType() {
            return original.eventType();
        }
    }

}
