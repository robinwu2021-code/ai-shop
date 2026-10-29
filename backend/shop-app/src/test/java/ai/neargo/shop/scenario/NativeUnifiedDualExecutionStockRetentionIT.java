package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.event.outbox.OutboxEnvelope;
import ai.neargo.shop.event.outbox.runtime.OutboxFingerprint;
import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade;
import ai.neargo.shop.invbridge.InvMirrorEvent;
import ai.neargo.shop.invbridge.InventoryLifecycleCommand;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort;
import ai.neargo.shop.spi.product.StockPort;
import ai.neargo.shop.spi.trade.ExecutionEvidenceLookup;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** Dedicated fresh native HOST: actual configured unified publisher/store/JDBC reader/mirror consumer. */
@EnabledIfSystemProperty(named = "beauty.execution.test.native", matches = "true")
@EnabledIfEnvironmentVariable(named = "C04B2B_UNIFIED_NATIVE_MYSQL_URL", matches = ".+")
@TestPropertySource(properties = {
    "spring.datasource.url=${C04B2B_UNIFIED_NATIVE_MYSQL_URL}",
    "shop.outbox.sources.platform.mode=unified", "shop.outbox.sources.inventory.mode=legacy",
    "shop.outbox.enabled=false", "shop.outbox.legacy-scan.enabled=false"
})
class NativeUnifiedDualExecutionStockRetentionIT extends DualExecutionStockRetentionFixture {
    @Autowired UnifiedOutboxFacade facade;

    @Test
    void actualFreshUnifiedReserveAndAllocateSurviveNativeDatetimeZeroRoundTripAndProveDual() throws Exception {
        try (var connection = web.getBean("dataSource", javax.sql.DataSource.class).getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
        }
        assertThat(facade.enabled("PLATFORM")).isTrue();
        assertThat(jdbc.queryForObject("SELECT DATETIME_PRECISION FROM information_schema.COLUMNS"
                + " WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sys_outbox' AND COLUMN_NAME='occurred_at'",
                Integer.class)).isZero();
        var request = prepared(true);
        var events = jdbc.queryForList("SELECT event_no FROM sys_outbox WHERE aggregate_type='INVENTORY'"
                + " AND aggregate_id=? AND event_type IN (?,?) ORDER BY id", String.class,
                request.stockOriginal().orderNo(), InvMirrorEvent.RESERVE, InvMirrorEvent.ALLOCATE);
        assertThat(events).hasSizeGreaterThanOrEqualTo(2);
        for (String number : events) {
            var actual = readEnvelope(number);
            assertThat(actual.occurredAt()).isEqualTo(actual.occurredAt().truncatedTo(ChronoUnit.SECONDS));
            assertThat(OutboxFingerprint.fingerprint(json, actual)).isEqualTo(storedHash(number));
        }
        var core = evidence.queryOriginalEvidence(new ExecutionEvidenceLookup(request.reference()));
        var mirror = mirrorEvidence.freeze(request.lookup(), core);
        assertThat(mirror.expectedNeeds()).isEqualTo(request.stockOriginal().needs());
        assertThat(mirror.events()).allSatisfy(event -> assertThat(event.sourceSystem()).isEqualTo("PLATFORM"));
        assertThat(current(() -> aggregate.retainV2(request)).outcome())
                .isEqualTo(ExecutionStockRetentionPort.Outcome.RETAINED);
    }

    @Test
    void historicalSuppliedFractionalIdentityIsNotNormalizedOrGuessedAndCannotAcceptARoute() {
        var request = prepared(true);
        var original = request.stockOriginal();
        String number = "old-fraction-" + sku;
        var command = new InventoryLifecycleCommand(InvMirrorEvent.RESERVE, original.orderNo(),
                original.needs().stream()
                .map(n -> new StockPort.SkuQty(n.skuNo(), n.quantity(), n.sourceStoreNo(), n.fulfillmentRef(),
                        original.lifecycleVersion())).toList(), original.orderNo());
        var supplied = new OutboxEnvelope(number, "PLATFORM", number, "INVENTORY", original.orderNo(),
                InvMirrorEvent.RESERVE, 1, "ORDER:" + original.orderNo(), 0, json.writeValueAsString(command),
                Instant.parse("2026-10-29T00:00:00.123456Z"));
        facade.accept("PLATFORM", supplied);
        assertThat(storedHash(number)).isEqualTo(OutboxFingerprint.fingerprint(json, supplied));
        assertThat(readEnvelope(number).occurredAt()).isNotEqualTo(supplied.occurredAt());
        assertThat(OutboxFingerprint.fingerprint(json, readEnvelope(number))).isNotEqualTo(storedHash(number));
        var result = current(() -> aggregate.retainV2(request));
        assertThat(result.outcome()).isEqualTo(ExecutionStockRetentionPort.Outcome.PENDING);
        assertThat(result.reason()).isEqualTo("MIRROR_FINGERPRINT_CONFLICT");
        assertThat(routes.find("MAIN", result.routeNo())).isNull();
        assertNoPins(request.lookup());
    }

    @Test
    void partialUnifiedMetadataIsUnavailableEvidenceRatherThanAnEmptyHistoricalSubset() {
        var request = prepared(true);
        String number = jdbc.queryForObject("SELECT event_no FROM sys_outbox WHERE aggregate_type='INVENTORY'"
                + " AND aggregate_id=? AND event_type=? ORDER BY id LIMIT 1", String.class,
                request.stockOriginal().orderNo(), InvMirrorEvent.RESERVE);
        assertThat(jdbc.update("UPDATE sys_outbox SET source_event_no=NULL WHERE event_no=?", number)).isOne();
        var result = current(() -> aggregate.retainV2(request));
        assertThat(result.outcome()).isEqualTo(ExecutionStockRetentionPort.Outcome.PENDING);
        assertThat(result.reason()).isEqualTo("PARTIAL_UNIFIED_MIRROR_METADATA");
        assertThat(routes.find("MAIN", result.routeNo())).isNull();
        assertNoPins(request.lookup());
    }

    private OutboxEnvelope readEnvelope(String number) {
        return jdbc.queryForObject("SELECT * FROM sys_outbox WHERE event_no=?", (rs, n) -> new OutboxEnvelope(
                rs.getString("event_no"), rs.getString("source_system"), rs.getString("source_event_no"),
                rs.getString("aggregate_type"), rs.getString("aggregate_id"), rs.getString("event_type"),
                rs.getInt("schema_version"), rs.getString("stream_key"), rs.getLong("stream_sequence"),
                rs.getString("payload"), rs.getTimestamp("occurred_at").toInstant()), number);
    }

    private String storedHash(String number) {
        return jdbc.queryForObject("SELECT payload_hash FROM sys_outbox WHERE event_no=?", String.class, number);
    }
}
